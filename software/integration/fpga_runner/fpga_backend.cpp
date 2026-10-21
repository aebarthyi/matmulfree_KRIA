// fpga_backend.cpp — see fpga_backend.hpp.
#include "fpga_backend.hpp"

#include "mmfree_lib.h"

#include <cstdlib>
#include <stdexcept>
#include <string>

namespace mmfree_fpga {

// Column-tile chunks for a projection of M outputs. Each chunk adds a COMPUTE + STORE
// round-trip, so only wide projections are split. Mirrored in tests/test_split_chunks.c.
//   MMFREE_SPLIT_CHUNKS=n forces n for every projection (0 = no splitting)
static std::uint32_t split_chunks(std::size_t M) {
  static const long forced = [] {
    const char* e = std::getenv("MMFREE_SPLIT_CHUNKS");
    return (e && *e) ? std::atol(e) : -1L;
  }();
  if (forced >= 0) return static_cast<std::uint32_t>(forced);
  if (M >= 16384) return 8;
  if (M >= 8192)  return 4;
  if (M >= 4096)  return 2;
  return 1;
}

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
  // Narrow b*N int32 -> int16 (clamped), then ONE engine call for all b rows.
  for (std::size_t i = 0; i < b; ++i)
    for (std::size_t n = 0; n < N; ++n) {
      std::int32_t v = x[i * N + n];
      if (v > 32767) v = 32767;
      else if (v < -32768) v = -32768;
      x16_[i * N + n] = static_cast<std::int16_t>(v);
    }
  if (mmfree_bitlinear_batch_split(h_, proj_id, x16_.data(), acc,
                                   static_cast<std::uint32_t>(b), split_chunks(M)) < 0)
    throw std::runtime_error("mmfree_bitlinear_batch failed for proj_id " +
                             std::to_string(proj_id));
}

// libmmfree is C: pass the std::function through as the opaque ctx pointer.
static void chunk_trampoline(void* ctx, std::uint32_t col0, std::uint32_t ncols) {
  (*static_cast<const std::function<void(std::size_t, std::size_t)>*>(ctx))(col0, ncols);
}

void FpgaBackend::matmul_batch_chunked(
    int proj_id, const std::int32_t* x, std::int32_t* acc, const std::int8_t* /*wq*/,
    std::size_t N, std::size_t M, std::size_t b,
    const std::function<void(std::size_t, std::size_t)>& on_cols) {
  for (std::size_t i = 0; i < b; ++i)
    for (std::size_t n = 0; n < N; ++n) {
      std::int32_t v = x[i * N + n];
      if (v > 32767) v = 32767;
      else if (v < -32768) v = -32768;
      x16_[i * N + n] = static_cast<std::int16_t>(v);
    }
  if (mmfree_bitlinear_batch_split_cb(h_, proj_id, x16_.data(), acc,
                                      static_cast<std::uint32_t>(b), split_chunks(M),
                                      on_cols ? chunk_trampoline : nullptr,
                                      const_cast<std::function<void(std::size_t, std::size_t)>*>(
                                          &on_cols)) < 0)
    throw std::runtime_error("mmfree_bitlinear_batch_split_cb failed for proj_id " +
                             std::to_string(proj_id));
}

void FpgaBackend::matmul_seq(const int* proj_ids, const std::int8_t* const* wqs, int k,
                             const std::function<void(int, std::int32_t*)>& produce,
                             const std::function<void(int, const std::int32_t*)>& consume,
                             std::size_t N, std::size_t M, std::size_t b) {
  if (k <= 0) return;
  // MMFREE_NO_PIPELINE=1: serial base implementation.
  static const bool no_pipe = [] { const char* e = std::getenv("MMFREE_NO_PIPELINE");
                                   return e && std::atoi(e) != 0; }();
  if (no_pipe) {
    TernaryBackend::matmul_seq(proj_ids, wqs, k, produce, consume, N, M, b);
    return;
  }

  if (xq_.size() < b * N)  xq_.resize(b * N);
  if (acc_.size() < b * M) acc_.resize(b * M);
  const std::size_t bN = b * N;

  // produce(j) -> narrow -> pack into the single ACT region (free once LOAD drained it).
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
    if (mmfree_seq_compute_issue(h_, proj_ids[n], static_cast<std::uint32_t>(b)) < 0)
      throw std::runtime_error("mmfree_seq_compute_issue failed");

    // Under COMPUTE(n): read back + consume n-1 (OUT holds STORE(n-1) until STORE(n)).
    if (n > 0) {
      mmfree_seq_readback(h_, proj_ids[n - 1], acc_.data(), static_cast<std::uint32_t>(b));
      consume(n - 1, acc_.data());
    }
    // ...and produce + pack n+1 (ACT is free since LOAD(n) drained it).
    if (n + 1 < k) quant_narrow_pack(n + 1);

    if (mmfree_seq_compute_wait(h_) < 0)
      throw std::runtime_error("mmfree_seq_compute_wait failed");
    if (mmfree_seq_store_issue(h_, proj_ids[n], static_cast<std::uint32_t>(b)) < 0)
      throw std::runtime_error("mmfree_seq_store_issue failed");
    if (mmfree_seq_store_wait(h_, proj_ids[n], static_cast<std::uint32_t>(b)) < 0)
      throw std::runtime_error("mmfree_seq_store_wait failed");
    if (n + 1 < k && mmfree_seq_load(h_, static_cast<std::uint32_t>(N)) < 0)
      throw std::runtime_error("mmfree_seq_load failed");
  }
  mmfree_seq_readback(h_, proj_ids[k - 1], acc_.data(), static_cast<std::uint32_t>(b));
  consume(k - 1, acc_.data());
}

void CompareBackend::matmul(int proj_id, const std::int32_t* x, std::int32_t* acc,
                            const std::int8_t* wq, std::size_t N, std::size_t M) {
  cpu_.matmul(-1, x, acc, wq, N, M);
  fpga_.matmul(proj_id, x, acc_fpga_.data(), wq, N, M);

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
  cpu_.matmul_batch(-1, x, acc, wq, N, M, b);
  fpga_.matmul_batch(proj_id, x, acc_fpga_.data(), wq, N, M, b);
  // Each row counts as one projection invocation.
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
