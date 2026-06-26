// manifest.hpp — read the packer's <prefix>.manifest.tsv (Phase B output).
//
// The TSV sidecar (mmfree_pack/packer.py write()) lists every packed projection so
// the runner can register each at its resident byte offset and map the C++ projection
// tag -> engine proj_id. TSV (not JSON) keeps the runner dependency-free.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace mmfree_fpga {

struct ProjEntry {
  std::string name;       // C++ projection tag, e.g. "model.layers.3.i_proj" / "lm_head"
  uint64_t byte_offset = 0;
  uint32_t N = 0;         // inner dim (activations)
  uint32_t M = 0;         // output dim (cols)
  uint32_t n_outputs = 0; // M padded up to a col-tile (engine STORE count)
  double s = 0.0;         // weight scale (informational; C++ dequant uses the blob's scale_w)
};

struct Manifest {
  std::vector<ProjEntry> projections;
  uint64_t total_bytes_per_port = 0;
  uint32_t num_ports = 0;
  uint32_t max_N() const;          // largest inner dim across projections
  uint32_t max_n_outputs() const;  // largest padded output across projections
};

// Parse <prefix>.manifest.tsv. Throws std::runtime_error on open/parse failure.
Manifest read_manifest_tsv(const std::string& path);

}  // namespace mmfree_fpga
