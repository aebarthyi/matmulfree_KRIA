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
