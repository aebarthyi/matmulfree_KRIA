// fpga_backend.hpp — TernaryBackend implementations that drive the KRIA engine via
// libmmfree. FpgaBackend runs projections on the engine; CompareBackend runs both CPU and
// engine and counts mismatches (the exact-match gate).
#pragma once

#include "mmfree/ternary_backend.hpp"

#include <cstddef>
#include <cstdint>
#include <vector>

struct mmfree_lib;  // opaque handle (mmfree_lib.h)

namespace mmfree_fpga {

// Engine backend. `proj_id` (from mmfree_register) addresses the resident weights, so `wq`
// is ignored. Activations are narrowed int32 -> int16 (clamped). NOT thread-safe.
class FpgaBackend final : public mmfree::TernaryBackend {
 public:
  // `batch` = the bitstream's CoreConfig.batchSize (max rows per weight stream).
  FpgaBackend(mmfree_lib* h, std::size_t maxN, std::size_t batch = 1)
      : h_(h), batch_(batch ? batch : 1), x16_(maxN * (batch ? batch : 1)) {}
  std::size_t batch_size() const override { return batch_; }
  void matmul(int proj_id, const std::int32_t* x, std::int32_t* acc, const std::int8_t* wq,
              std::size_t N, std::size_t M) override;
  void matmul_batch(int proj_id, const std::int32_t* x, std::int32_t* acc,
                    const std::int8_t* wq, std::size_t N, std::size_t M,
                    std::size_t b) override;
  // Column-tile split with a per-range callback, so the caller's per-column work
  // (dequant) runs inside the next chunk's COMPUTE.
  void matmul_batch_chunked(int proj_id, const std::int32_t* x, std::int32_t* acc,
                            const std::int8_t* wq, std::size_t N, std::size_t M,
                            std::size_t b,
                            const std::function<void(std::size_t, std::size_t)>& on_cols) override;
  // Pipelined cluster: produce(n+1) and readback+consume(n-1) run under COMPUTE(n).
  // MMFREE_NO_PIPELINE=1 falls back to the serial base implementation.
  void matmul_seq(const int* proj_ids, const std::int8_t* const* wqs, int k,
                  const std::function<void(int, std::int32_t*)>& produce,
                  const std::function<void(int, const std::int32_t*)>& consume,
                  std::size_t N, std::size_t M, std::size_t b) override;

 private:
  mmfree_lib* h_;
  std::size_t batch_;
  std::vector<std::int16_t> x16_;    // b*N narrowed activations, row-major
  std::vector<std::int32_t> xq_;     // b*N produce() scratch (int32 before narrow)
  std::vector<std::int32_t> acc_;    // b*M readback scratch
};

// `--backend both`: CPU result goes to `acc` (drives generation), the engine's to a
// scratch buffer; mismatch stats accumulate across every invocation.
class CompareBackend final : public mmfree::TernaryBackend {
 public:
  CompareBackend(mmfree_lib* h, std::size_t maxN, std::size_t maxM, std::size_t batch = 1)
      : fpga_(h, maxN, batch), acc_fpga_(maxM * (batch ? batch : 1)) {}
  std::size_t batch_size() const override { return fpga_.batch_size(); }
  void matmul(int proj_id, const std::int32_t* x, std::int32_t* acc, const std::int8_t* wq,
              std::size_t N, std::size_t M) override;
  void matmul_batch(int proj_id, const std::int32_t* x, std::int32_t* acc,
                    const std::int8_t* wq, std::size_t N, std::size_t M,
                    std::size_t b) override;

  std::uint64_t calls = 0;                    // projection invocations checked
  std::uint64_t projections_with_mismatch = 0;
  std::uint64_t total_elem_mismatches = 0;    // summed over all invocations
  std::int64_t worst_abs_diff = 0;            // signed diff with the largest magnitude

 private:
  mmfree::CpuBackend cpu_;
  FpgaBackend fpga_;
  std::vector<std::int32_t> acc_fpga_;
};

}  // namespace mmfree_fpga
