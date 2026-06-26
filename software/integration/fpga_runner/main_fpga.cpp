// main_fpga.cpp — mmfree-cli-fpga: run matmulfreellmCPU with the ternary BitLinear
// projections offloaded to the KRIA engine, and compare against the CPU path.
//
// Same model, same FixedQ510 numerics, same run_bench timing as the submodule CLI —
// only the TernaryBackend differs — so `--backend cpu` vs `--backend fpga` isolates
// exactly "ternary matmul on the A53 vs on the engine" (docs §5). `--backend both`
// runs both per projection and asserts the int32 accumulators are bit-identical
// (the P-D exact-match gate).
//
// Board config mirrors bench.c / smoke_test.c:
//   mmfree-cli-fpga <core_phys> <dma_phys> <uio> <act_udma> <wt_udma> <out_udma> [opts]
// Geometry defaults to the a16 engine; override with MMFREE_AWIDTH/XDIM/MAXACC/MAXN/MAXM.
// Multi-port act/wt paths get the port index appended (MMFREE_ACT<i>/WT<i> override),
// and DMA i defaults to 0xA0020000+(i-1)*0x10000 (MMFREE_DMA<i> override) — like bench.c.
//
// Options:
//   --blob PATH        model.mmfree (CPU weights/norms/scales; default ./model.mmfree)
//   --packed-dir DIR   dir with <prefix>.port{p}.bin + <prefix>.manifest.tsv (default .)
//   --prefix NAME      packed-blob prefix (default "weights")
//   --tokenizer PATH   tokenizer.mmtok (default <blob dir>/tokenizer.mmtok; only for --prompt)
//   --backend cpu|fpga|both   (default fpga)
//   --mode float|fixed (default fixed; only fixed offloads to the engine)
//   --frac N           fixed-point frac bits (default 10)
//   --ids a,b,c        raw token ids (bypass the tokenizer)
//   --prompt TEXT      prompt text (needs --tokenizer)
//   --bench            time generation (prefill/decode tok/s); else just generate ids
//   --serve N          serve N concurrent streams (same prompt) with batched decode — one
//                      engine call per projection/step. Prints aggregate tok/s + asserts
//                      every stream matches single-stream generate. Best with N=batchSize.
//   --gen N            tokens to generate (default 32)
//   --warmup N --reps N --profile     bench controls (see mmfree/bench.hpp)
#include "mmfree/bench.hpp"
#include "mmfree/model.hpp"
#include "mmfree/tokenizer.hpp"
#include "mmfree/weights.hpp"

#include "mmfree_lib.h"
#include "mmfree_runtime.h"

#include "fpga_backend.hpp"
#include "manifest.hpp"

#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

using namespace mmfree;

static uint32_t env_u32(const char* n, uint32_t d) {
  const char* e = getenv(n);
  return (e && *e) ? (uint32_t)strtoul(e, nullptr, 0) : d;
}
static uint64_t env_u64(const char* n, uint64_t d) {
  const char* e = getenv(n);
  return (e && *e) ? strtoull(e, nullptr, 0) : d;
}
static const char* port_path(char* out, size_t n, const char* base, uint32_t i, const char* pfx) {
  char env[32];
  snprintf(env, sizeof(env), "MMFREE_%s%u", pfx, i);
  const char* e = getenv(env);
  if (e && *e) return e;
  snprintf(out, n, "%s%u", base, i);
  return out;
}

static std::vector<std::int64_t> parse_ids(const std::string& s) {
  std::vector<std::int64_t> ids;
  std::size_t i = 0;
  while (i < s.size()) {
    while (i < s.size() && !(std::isdigit((unsigned char)s[i]) || s[i] == '-')) ++i;
    if (i >= s.size()) break;
    std::size_t j = i + (s[i] == '-' ? 1 : 0);
    while (j < s.size() && std::isdigit((unsigned char)s[j])) ++j;
    ids.push_back(std::stoll(s.substr(i, j - i)));
    i = j;
  }
  return ids;
}

static std::string default_tokenizer_path(const std::string& blob) {
  std::size_t slash = blob.find_last_of('/');
  return (slash == std::string::npos ? "" : blob.substr(0, slash + 1)) + "tokenizer.mmtok";
}

int main(int argc, char** argv) {
  if (argc < 7) {
    std::fprintf(stderr,
                 "usage: %s <core_phys> <dma_phys> <uio> <act_udma> <wt_udma> <out_udma> [opts]\n",
                 argv[0]);
    return 2;
  }
  uint64_t core_phys = strtoull(argv[1], nullptr, 0);
  uint64_t dma_phys0 = strtoull(argv[2], nullptr, 0);
  const char* uio_dev = argv[3];
  const char* act_udma = argv[4];
  const char* wt_udma = argv[5];
  const char* out_udma = argv[6];

  std::string blob = "model.mmfree", packed_dir = ".", prefix = "weights", tok_path;
  std::string backend = "fpga", mode = "fixed", ids_arg, prompt;
  bool bench = false, profile = false;
  int frac = 10, warmup = 1, reps = 3, serve = 0;
  std::size_t gen = 32;

  for (int a = 7; a < argc; ++a) {
    std::string k = argv[a];
    auto next = [&]() { return (a + 1 < argc) ? argv[++a] : ""; };
    if (k == "--blob") blob = next();
    else if (k == "--packed-dir") packed_dir = next();
    else if (k == "--prefix") prefix = next();
    else if (k == "--tokenizer") tok_path = next();
    else if (k == "--backend") backend = next();
    else if (k == "--mode") mode = next();
    else if (k == "--frac") frac = std::atoi(next());
    else if (k == "--ids") ids_arg = next();
    else if (k == "--prompt") prompt = next();
    else if (k == "--bench") bench = true;
    else if (k == "--gen") gen = (std::size_t)std::atoll(next());
    else if (k == "--warmup") warmup = std::atoi(next());
    else if (k == "--reps") reps = std::atoi(next());
    else if (k == "--profile") profile = true;
    else if (k == "--serve") serve = std::atoi(next());
    else { std::fprintf(stderr, "unknown arg: %s\n", k.c_str()); return 2; }
  }
  const bool use_fpga = (backend == "fpga" || backend == "both");
  if (backend != "cpu" && backend != "fpga" && backend != "both") {
    std::fprintf(stderr, "--backend must be cpu|fpga|both\n");
    return 2;
  }
  ActQuant aq = (mode == "fixed") ? ActQuant::FixedQ510 : ActQuant::Float;
  if (use_fpga && aq != ActQuant::FixedQ510) {
    std::fprintf(stderr, "the engine only runs the fixed (FixedQ510) path; use --mode fixed\n");
    return 2;
  }

  // ---- resolve the prompt ids ----
  std::vector<std::int64_t> ids;
  if (!ids_arg.empty()) {
    ids = parse_ids(ids_arg);
  } else if (!prompt.empty()) {
    Tokenizer t(tok_path.empty() ? default_tokenizer_path(blob) : tok_path);
    ids = t.encode(prompt, /*add_bos=*/true);
  } else {
    ids = {1, 415, 310, 29871, 13, 306, 626, 263};  // fixed pseudo-prompt (no tokenizer)
  }
  if (ids.empty()) { std::fprintf(stderr, "no input ids\n"); return 2; }

  // ---- CPU-side weights + model (norms, scales, embeddings, wq for CPU/Float) ----
  Weights w(blob);
  Model model(w, aq, frac);

  // ---- engine bring-up (fpga / both): geometry, resident weights, registry ----
  mmfree_lib_t* lib = nullptr;
  std::unique_ptr<mmfree_fpga::FpgaBackend> fpga;
  std::unique_ptr<mmfree_fpga::CompareBackend> compare;
  mmfree_fpga::Manifest man;

  if (use_fpga) {
    mmfree_geom_t g;
    if (mmfree_geom_init(&g, env_u32("MMFREE_AWIDTH", 16), env_u32("MMFREE_XDIM", 32),
                         env_u32("MMFREE_MAXACC", 4096), env_u32("MMFREE_MAXN", 4096),
                         env_u32("MMFREE_MAXM", 32000)) < 0) {
      std::fprintf(stderr, "bad geometry\n");
      return 1;
    }
    man = mmfree_fpga::read_manifest_tsv(packed_dir + "/" + prefix + ".manifest.tsv");

    const uint32_t P = g.numPorts;
    // Batched bitstream: B activation vectors share one weight stream. Must match
    // the loaded bitstream's CoreConfig.batchSize and be <= xDim.
    const uint32_t batch = env_u32("MMFREE_BATCH", 1);
    mmfree_lib_cfg_t cfg;
    std::memset(&cfg, 0, sizeof(cfg));
    cfg.aWidth = g.aWidth; cfg.xDim = g.xDim;
    cfg.maxAcc = g.maxAcc; cfg.maxN = g.maxN; cfg.maxM = g.maxM;
    cfg.batch = batch;
    cfg.core_phys = core_phys; cfg.core_size = 0x1000;
    cfg.num_dma = P; cfg.dma_size = 0x10000;
    cfg.uio_dev = uio_dev;
    cfg.dma_phys[0] = dma_phys0;
    for (uint32_t p = 1; p < P; ++p) {
      char env[32];
      snprintf(env, sizeof(env), "MMFREE_DMA%u", p);
      cfg.dma_phys[p] = env_u64(env, 0xA0020000ull + (p - 1u) * 0x10000ull);
    }
    static char apath[MMFREE_MAX_DMA][256], wpath[MMFREE_MAX_DMA][256];
    for (uint32_t p = 0; p < P; ++p) {
      cfg.act_dev[p] = (P > 1) ? port_path(apath[p], 256, act_udma, p, "ACT") : act_udma;
      cfg.wt_dev[p] = (P > 1) ? port_path(wpath[p], 256, wt_udma, p, "WT") : wt_udma;
    }
    cfg.out_dev = out_udma;
    cfg.weight_bytes_per_port = man.total_bytes_per_port;
    cfg.act_bytes_per_port = (size_t)man.max_N() * g.portBytes;
    // Output holds batch * n_outputs lanes (the engine drains every PE row).
    cfg.out_bytes = (size_t)batch * man.max_n_outputs() * g.outLaneBytes;
    cfg.max_proj = (uint32_t)man.projections.size();

    std::printf("# fpga: aWidth=%u xDim=%u batch=%u ports=%u portBytes=%u accWidth=%u  resident=%llu B/port\n",
                g.aWidth, g.xDim, batch, P, g.portBytes, g.accWidth,
                (unsigned long long)man.total_bytes_per_port);

    lib = mmfree_lib_open(&cfg);
    if (!lib) {
      std::fprintf(stderr,
                   "mmfree_lib_open failed — the resident weights need udmabuf sizes:\n"
                   "  wt  >= %llu B/port  (manifest total_bytes_per_port)\n"
                   "  act >= %zu B/port\n  out >= %zu B\n"
                   "Regenerate the DT overlay with a larger wt buffer (e.g. "
                   "UDMABUF_WT_SZ=0x01800000) and redeploy; see fpga_runner/README.md.\n",
                   (unsigned long long)cfg.weight_bytes_per_port, cfg.act_bytes_per_port,
                   cfg.out_bytes);
      return 1;
    }

    for (uint32_t p = 0; p < P; ++p) {
      std::string path = packed_dir + "/" + prefix + ".port" + std::to_string(p) + ".bin";
      if (mmfree_lib_load_weights_file(lib, p, path.c_str()) < 0) {
        std::fprintf(stderr, "load_weights port %u (%s) failed\n", p, path.c_str());
        return 1;
      }
    }

    std::unordered_map<std::string, int> tag2id;
    for (const auto& e : man.projections) {
      int id = mmfree_register(lib, e.byte_offset, e.N, e.M);
      if (id < 0) { std::fprintf(stderr, "register %s failed\n", e.name.c_str()); return 1; }
      tag2id[e.name] = id;
    }
    std::printf("# registered %zu projections (maxN=%u maxNout=%u)\n",
                man.projections.size(), man.max_N(), man.max_n_outputs());

    if (backend == "both") {
      compare.reset(new mmfree_fpga::CompareBackend(lib, man.max_N(), man.max_n_outputs(), batch));
      model.set_backend(compare.get(), tag2id);
    } else {
      fpga.reset(new mmfree_fpga::FpgaBackend(lib, man.max_N(), batch));
      model.set_backend(fpga.get(), tag2id);
    }
  }

  int rc = 0;

  // ---- serving: B concurrent streams, batched decode (one engine call per projection/step) ----
  if (serve > 0) {
    const std::size_t Bn = static_cast<std::size_t>(serve);
    std::vector<std::vector<std::int64_t>> prompts(Bn, ids);  // same prompt across streams
    Sampling samp;  // greedy → deterministic, so every stream must match the single-stream ref

    // Correctness reference: one stream decoded the existing (single-stream) way.
    std::vector<std::int64_t> ref = model.generate(ids, gen, /*eos=*/-1, samp);

    // Timed serving (best-of-reps when --bench, else one run). The first generate() above
    // also warms the resident weights / udmabuf mapping.
    using clk = std::chrono::steady_clock;
    double best_s = 1e30;
    int rr = bench ? reps : 1;
    std::vector<std::vector<std::int64_t>> out;
    for (int r = 0; r < rr; ++r) {
      auto t0 = clk::now();
      out = model.generate_serve(prompts, gen, /*eos=*/-1, samp);
      double s = std::chrono::duration<double>(clk::now() - t0).count();
      if (s < best_s) best_s = s;
    }

    std::size_t bad = 0;
    for (const auto& st : out) if (st != ref) ++bad;
    const std::size_t gen_per = out.empty() ? 0 : out[0].size() - ids.size();
    const double agg = best_s > 0 ? (double)(Bn * gen_per) / best_s : 0.0;
    std::printf("serve B=%zu: %s\n", Bn,
                bad ? "STREAM MISMATCH (serve != single-stream)" : "OK (all streams == single-stream)");
    std::printf("  %zu gen tok/stream, %.3fs/run -> %.2f tok/s aggregate  (%.2f tok/s/stream)\n",
                gen_per, best_s, agg, Bn ? agg / Bn : 0.0);
    rc = bad ? 1 : 0;
  }
  // ---- exact-match gate: one forward pass touches every projection shape ----
  else if (backend == "both") {
    std::printf("# exact-match gate: forward over %zu tokens (all layers + lm_head)\n", ids.size());
    std::vector<float> logits;
    model.forward(ids.data(), ids.size(), logits);
    std::printf("checked %llu projection invocation(s): %llu mismatched, %llu element diff(s)",
                (unsigned long long)compare->calls,
                (unsigned long long)compare->projections_with_mismatch,
                (unsigned long long)compare->total_elem_mismatches);
    if (compare->total_elem_mismatches)
      std::printf("   worst |diff|=%lld", (long long)compare->worst_abs_diff);
    std::printf("\n%s\n", compare->total_elem_mismatches ? "GATE FAIL" : "GATE PASS (CPU == FPGA)");
    rc = compare->total_elem_mismatches ? 1 : 0;
  } else if (bench) {
    BenchOpts opts;
    opts.gen = gen; opts.warmup = warmup; opts.reps = reps; opts.profile = profile;
    if (lib) mmfree_lib_reset_stats(lib);   // exclude model setup; warmup is folded into reps
    run_bench(model, ids, opts, blob.c_str(), mode.c_str(), frac);

    if (lib && profile) {
      mmfree_lib_stats_t s;
      mmfree_lib_get_stats(lib, &s);
      if (s.calls) {
        struct { const char* name; std::uint64_t ns; } ph[] = {
            {"pack",     s.pack_ns},     {"load (IRQ)",  s.load_ns},
            {"compute (IRQ)", s.compute_ns}, {"store (IRQ)", s.store_ns},
            {"readback", s.readback_ns},
        };
        std::uint64_t tot = s.pack_ns + s.load_ns + s.compute_ns + s.store_ns + s.readback_ns;
        std::printf("\nbitlinear phase breakdown (%llu calls, %.2f ms total, %.1f us/call):\n",
                    (unsigned long long)s.calls, tot / 1e6, (double)tot / 1e3 / s.calls);
        std::printf("  %-14s %10s %8s %10s\n", "phase", "ms", "%", "us/call");
        for (auto& p : ph)
          std::printf("  %-14s %10.2f %7.1f%% %10.2f\n", p.name, p.ns / 1e6,
                      tot ? 100.0 * p.ns / tot : 0.0, (double)p.ns / 1e3 / s.calls);
        std::printf("    (of which cache sync: act %.2f us/call, out %.2f us/call)\n",
                    (double)s.act_sync_ns / 1e3 / s.calls,
                    (double)s.out_sync_ns / 1e3 / s.calls);
      }
    }
  } else {
    Sampling samp;  // greedy
    std::vector<std::int64_t> stream = model.generate(ids, gen, /*eos=*/-1, samp);
    for (std::size_t i = 0; i < stream.size(); ++i)
      std::printf("%lld%s", (long long)stream[i], i + 1 < stream.size() ? " " : "\n");
  }

  if (lib) mmfree_lib_close(lib);
  return rc;
}
