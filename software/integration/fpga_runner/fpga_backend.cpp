// fpga_backend.cpp — see fpga_backend.hpp.
#include "fpga_backend.hpp"

#include "mmfree_lib.h"

#include <cstdlib>
#include <stdexcept>
#include <string>

namespace mmfree_fpga {

void FpgaBackend::matmul(int proj_id, const std::int32_t* x, std::int32_t* acc,
                         const std::int8_t* /*wq*/, std::size_t N, std::size_t M) {
  for (std::size_t n = 0; n < N; ++n) {
    std::int32_t v = x[n];
    if (v > 32767) v = 32767;
    else if (v < -32768) v = -32768;
    x16_[n] = static_cast<std::int16_t>(v);
  }
  if (mmfree_bitlinear(h_, proj_id, x16_.data(), acc) < 0)
    throw std::runtime_error("mmfree_bitlinear failed for proj_id " + std::to_string(proj_id));
  (void)M;
}

void FpgaBackend::matmul_batch(int proj_id, const std::int32_t* x, std::int32_t* acc,
                               const std::int8_t* /*wq*/, std::size_t N, std::size_t M,
                               std::size_t b) {
  // Narrow b*N int32 -> int16 (already saturated by the FixedQ510 quant; clamp
  // defensively), row-major, then ONE weight-broadcast engine call for all b rows.
  for (std::size_t i = 0; i < b; ++i)
    for (std::size_t n = 0; n < N; ++n) {
      std::int32_t v = x[i * N + n];
      if (v > 32767) v = 32767;
      else if (v < -32768) v = -32768;
      x16_[i * N + n] = static_cast<std::int16_t>(v);
    }
  if (mmfree_bitlinear_batch(h_, proj_id, x16_.data(), acc, static_cast<std::uint32_t>(b)) < 0)
    throw std::runtime_error("mmfree_bitlinear_batch failed for proj_id " +
                             std::to_string(proj_id));
  (void)M;
}

void FpgaBackend::matmul_seq(const int* proj_ids, const std::int8_t* const* wqs, int k,
                             const std::function<void(int, std::int32_t*)>& produce,
                             const std::function<void(int, const std::int32_t*)>& consume,
                             std::size_t N, std::size_t M, std::size_t b) {
  if (k <= 0) return;
  // Escape hatch: serial default (for the token-equivalence A/B and as a fallback).
  static const bool no_pipe = [] { const char* e = std::getenv("MMFREE_NO_PIPELINE");
                                   return e && std::atoi(e) != 0; }();
  if (no_pipe) {
    TernaryBackend::matmul_seq(proj_ids, wqs, k, produce, consume, N, M, b);
    return;
  }

  if (xq_.size() < b * N)  xq_.resize(b * N);
  if (acc_.size() < b * M) acc_.resize(b * M);
  const std::size_t bN = b * N;

  // produce projection j -> int32 xq_, narrow to int16 x16_, scatter into the
  // (single) ACT region. Caller guarantees ACT is free (prior LOAD drained it).
  auto quant_narrow_pack = [&](int j) {
    produce(j, xq_.data());
    for (std::size_t i = 0; i < bN; ++i) {
      std::int32_t v = xq_[i];
      if (v > 32767) v = 32767;
      else if (v < -32768) v = -32768;
      x16_[i] = static_cast<std::int16_t>(v);
    }
    if (mmfree_seq_pack(h_, x16_.data(), static_cast<std::uint32_t>(N),
                        static_cast<std::uint32_t>(b)) < 0)
      throw std::runtime_error("mmfree_seq_pack failed");
  };

  quant_narrow_pack(0);
  if (mmfree_seq_load(h_, static_cast<std::uint32_t>(N)) < 0)
    throw std::runtime_error("mmfree_seq_load failed");

  for (int n = 0; n < k; ++n) {
    if (mmfree_seq_compute_issue(h_, proj_ids[n]) < 0)
      throw std::runtime_error("mmfree_seq_compute_issue failed");

    // --- CPU work hidden under COMPUTE(n) ---
    // Sign-expand + dequant the PREVIOUS projection (OUT still holds STORE(n-1);
    // STORE(n) hasn't been issued yet, so the single OUT region is safe to read).
    if (n > 0) {
      mmfree_seq_readback(h_, proj_ids[n - 1], acc_.data(), static_cast<std::uint32_t>(b));
      consume(n - 1, acc_.data());
    }
    // RMSNorm+quant+pack the NEXT projection into ACT (free since LOAD(n) drained it).
    if (n + 1 < k) quant_narrow_pack(n + 1);
    // ----------------------------------------

    if (mmfree_seq_compute_wait(h_) < 0)
      throw std::runtime_error("mmfree_seq_compute_wait failed");
    if (mmfree_seq_store_issue(h_, proj_ids[n]) < 0)
      throw std::runtime_error("mmfree_seq_store_issue failed");
    if (mmfree_seq_store_wait(h_, proj_ids[n]) < 0)
      throw std::runtime_error("mmfree_seq_store_wait failed");
    if (n + 1 < k && mmfree_seq_load(h_, static_cast<std::uint32_t>(N)) < 0)
      throw std::runtime_error("mmfree_seq_load failed");
  }
  // Last projection: no COMPUTE left to hide its readback under.
  mmfree_seq_readback(h_, proj_ids[k - 1], acc_.data(), static_cast<std::uint32_t>(b));
  consume(k - 1, acc_.data());
}

void CompareBackend::matmul(int proj_id, const std::int32_t* x, std::int32_t* acc,
                            const std::int8_t* wq, std::size_t N, std::size_t M) {
  cpu_.matmul(-1, x, acc, wq, N, M);                       // CPU result -> acc (used downstream)
  fpga_.matmul(proj_id, x, acc_fpga_.data(), wq, N, M);    // engine result -> scratch

  ++calls;
  std::uint64_t mism = 0;
  for (std::size_t m = 0; m < M; ++m) {
    std::int64_t d = static_cast<std::int64_t>(acc[m]) - static_cast<std::int64_t>(acc_fpga_[m]);
    if (d) {
      ++mism;
      if (std::llabs(d) > std::llabs(worst_abs_diff)) worst_abs_diff = d;
    }
  }
  if (mism) {
    ++projections_with_mismatch;
    total_elem_mismatches += mism;
  }
}

void CompareBackend::matmul_batch(int proj_id, const std::int32_t* x, std::int32_t* acc,
                                  const std::int8_t* wq, std::size_t N, std::size_t M,
                                  std::size_t b) {
  cpu_.matmul_batch(-1, x, acc, wq, N, M, b);                       // CPU -> acc (downstream)
  fpga_.matmul_batch(proj_id, x, acc_fpga_.data(), wq, N, M, b);    // engine -> scratch
  // Compare row by row; each row is one projection invocation for the stats.
  for (std::size_t i = 0; i < b; ++i) {
    ++calls;
    std::uint64_t mism = 0;
    for (std::size_t m = 0; m < M; ++m) {
      std::int64_t d = static_cast<std::int64_t>(acc[i * M + m]) -
                       static_cast<std::int64_t>(acc_fpga_[i * M + m]);
      if (d) {
        ++mism;
        if (std::llabs(d) > std::llabs(worst_abs_diff)) worst_abs_diff = d;
      }
    }
    if (mism) {
      ++projections_with_mismatch;
      total_elem_mismatches += mism;
    }
  }
}

}  // namespace mmfree_fpga
