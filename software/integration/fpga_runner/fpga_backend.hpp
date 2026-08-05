// fpga_backend.hpp — TernaryBackend implementations that drive the KRIA engine.
//
// The C++ model (matmulfreellmCPU) calls TernaryBackend::matmul for each BitLinear
// integer accumulate. FpgaBackend routes that to libmmfree (mmfree_bitlinear) against
// resident per-projection weights; CompareBackend runs BOTH the CPU reduction and the
// engine and asserts they are bit-identical (the P-D exact-match gate).
#pragma once

#include "mmfree/ternary_backend.hpp"

#include <cstddef>
#include <cstdint>
#include <vector>

struct mmfree_lib;  // opaque handle (mmfree_lib.h)

namespace mmfree_fpga {

// Engine backend. `proj_id` (from mmfree_register) addresses the resident weights, so
// `wq` is ignored. Activations are handed to libmmfree as int32 and saturated to the
// engine's int16 LOAD slot during the pack, so they are traversed once. NOT thread-safe:
// one op per call, reusing shared scratch — matches libmmfree's one-in-flight-op contract.
class FpgaBackend final : public mmfree::TernaryBackend {
 public:
  // `batch` = the loaded bitstream's CoreConfig.batchSize (B PE rows). matmul_batch
  // streams the resident weights once and applies them to up to `batch` activation rows.
  FpgaBackend(mmfree_lib* h, std::size_t maxN, std::size_t batch = 1)
      : h_(h), batch_(batch ? batch : 1) { (void)maxN; }
  std::size_t batch_size() const override { return batch_; }
  void matmul(int proj_id, const std::int32_t* x, std::int32_t* acc, const std::int8_t* wq,
              std::size_t N, std::size_t M) override;
  void matmul_batch(int proj_id, const std::int32_t* x, std::int32_t* acc,
                    const std::int8_t* wq, std::size_t N, std::size_t M,
                    std::size_t b) override;
  // Column-tile split with a per-range callback: the caller's per-column work (dequant)
  // runs inside the next tile-chunk's COMPUTE window. This is the overload the model
  // should prefer — matmul_batch above is just this one with a null callback.
  void matmul_batch_chunked(int proj_id, const std::int32_t* x, std::int32_t* acc,
                            const std::int8_t* wq, std::size_t N, std::size_t M,
                            std::size_t b,
                            const std::function<void(std::size_t, std::size_t)>& on_cols) override;
  // Pipelined cluster: quant+pack of projection n+1 and sign-expand+dequant of n-1
  // run inside the COMPUTE(n) wait window (engine ordering stays one-op-at-a-time).
  // MMFREE_NO_PIPELINE=1 forces the serial default (for token-equivalence A/B).
  void matmul_seq(const int* proj_ids, const std::int8_t* const* wqs, int k,
                  const std::function<void(int, std::int32_t*)>& produce,
                  const std::function<void(int, const std::int32_t*)>& consume,
                  std::size_t N, std::size_t M, std::size_t b) override;

 private:
  mmfree_lib* h_;
  std::size_t batch_;
  std::vector<std::int32_t> xq_;     // b*N produce() scratch, packed straight from here
  std::vector<std::int32_t> acc_;    // b*M readback scratch
};

// Verification backend for `--backend both`: computes the CPU reduction into `acc` (so
// generation stays valid), runs the engine into a scratch buffer, and compares element
// by element. Accumulates mismatch stats across every projection invocation.
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
