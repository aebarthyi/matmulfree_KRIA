// manifest.cpp — parse the packer's tab-separated projection table.
#include "manifest.hpp"

#include <algorithm>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <stdexcept>

namespace mmfree_fpga {

uint32_t Manifest::max_N() const {
  uint32_t m = 0;
  for (const auto& p : projections) m = std::max(m, p.N);
  return m;
}

uint32_t Manifest::max_n_outputs() const {
  uint32_t m = 0;
  for (const auto& p : projections) m = std::max(m, p.n_outputs);
  return m;
}

Manifest read_manifest_tsv(const std::string& path) {
  std::ifstream f(path);
  if (!f) throw std::runtime_error("manifest: cannot open " + path);

  Manifest man;
  std::string line;
  while (std::getline(f, line)) {
    if (line.empty()) continue;
    if (line[0] == '#') {
      // Metadata comment: "# total_bytes_per_port=<n> numPorts=<n>". Tolerate the
      // pure header-comment line ("# name\tbyte_offset\t...") by ignoring misses.
      auto grab = [&](const char* key, uint64_t& out) {
        std::size_t k = line.find(key);
        if (k != std::string::npos) out = std::strtoull(line.c_str() + k + std::string(key).size(), nullptr, 10);
      };
      uint64_t tbp = man.total_bytes_per_port, np = man.num_ports;
      grab("total_bytes_per_port=", tbp);
      grab("numPorts=", np);
      man.total_bytes_per_port = tbp;
      man.num_ports = static_cast<uint32_t>(np);
      continue;
    }
    std::istringstream ss(line);
    std::string name, off, n, m, nout, s;
    if (!std::getline(ss, name, '\t') || !std::getline(ss, off, '\t') ||
        !std::getline(ss, n, '\t') || !std::getline(ss, m, '\t') ||
        !std::getline(ss, nout, '\t') || !std::getline(ss, s, '\t'))
      throw std::runtime_error("manifest: malformed row: " + line);
    ProjEntry e;
    e.name = name;
    e.byte_offset = std::strtoull(off.c_str(), nullptr, 10);
    e.N = static_cast<uint32_t>(std::strtoul(n.c_str(), nullptr, 10));
    e.M = static_cast<uint32_t>(std::strtoul(m.c_str(), nullptr, 10));
    e.n_outputs = static_cast<uint32_t>(std::strtoul(nout.c_str(), nullptr, 10));
    e.s = std::strtod(s.c_str(), nullptr);
    man.projections.push_back(std::move(e));
  }
  if (man.projections.empty()) throw std::runtime_error("manifest: no projections in " + path);
  return man;
}

}  // namespace mmfree_fpga
