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
//   --backend cpu|cpu-2bit|cpu-1p6|fpga|both   (default fpga)
//                      cpu-2bit / cpu-1p6 are the CPU path with weights held at the two
//                      encodings the FPGA is swept at (2.0 and 1.625 bits/weight), so a
//                      CPU-vs-FPGA comparison can hold the weight format fixed. cpu-1p6 is
//                      the TQ1_0 form
//                      (llama.cpp's format; see mmfree/tq1.hpp) instead of one int8 per
//                      ternary weight. Same arithmetic, 5x less weight traffic — this is
//                      the CPU arm to quote against a 1.6-bit FPGA build, since plain
//                      `cpu` is really an 8-bit-storage baseline. Results are BIT-IDENTICAL
//                      to `cpu` (lossless packing + associative integer accumulation), so
//                      the two differ only in speed.
//   --mode float|fixed (default fixed; only fixed offloads to the engine)
//   --frac N           fixed-point frac bits (default 10)
//   --ids a,b,c        raw token ids (bypass the tokenizer)
//   --prompt TEXT      prompt text (needs --tokenizer)
//   --bench            time generation (prefill/decode tok/s); else stream generated
//                      text live (decoded token-by-token if a tokenizer is reachable,
//                      else live token ids)
//   --serve N          serve N concurrent streams (same prompt) with batched decode — one
//                      engine call per projection/step. Prints aggregate tok/s + asserts
//                      every stream matches single-stream generate. Best with N=batchSize.
//   --distinct-prompts give each --serve stream its OWN prompt and check it against its OWN
//                      single-stream reference. With the default shared prompt the streams
//                      are identical by construction, so streams_ok proves batched decode ==
//                      single-stream and that no stream was dropped, but it is blind to a row
//                      permutation or to cross-contamination between equal values. Prompts
//                      are left-rotations of the ids, so all B have the SAME length and the
//                      timings stay comparable to a shared-prompt run.
//   --gen N            tokens to generate (default 32)
//   --temp F           sampling temperature (0 = greedy, default); >0 enables sampling
//   --top-k K          restrict sampling to the top-K logits (<=0 = full vocab)
//   --seed N           RNG seed for reproducible sampling
//   --no-eos           don't stop at EOS — always emit the full --gen length
//   --warmup N --reps N --profile     bench controls (see mmfree/bench.hpp)
//   --csv              also emit the run's metrics as one "#CSVH <header>" + one
//                      "#CSVR <values>" line, for a scripted sweep (scripts/bench_batch.sh).
//                      Covers TTFT / prefill / token latency / tok-s plus, with --profile,
//                      the op and engine-phase breakdowns as columns.
#include "mmfree/bench.hpp"
#include "mmfree/model.hpp"
#include "mmfree/profile.hpp"
#include "mmfree/simd.hpp"          // simd::backend(), reported at startup
#include "mmfree/tq1.hpp"           // Tq1Backend: --backend cpu-tq1
#include "mmfree/tokenizer.hpp"
#include "mmfree/weights.hpp"

#include "mmfree_lib.h"
#include "mmfree_runtime.h"

#include "fpga_backend.hpp"
#include "manifest.hpp"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <exception>
#include <functional>
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

// The ACTUAL fabric clock, straight from the PS clock driver. The CSV's clk_mhz column is
// the manifest's PL_CLK_MHZ, which is only a REQUEST: PL0_REF exists on 1000/N MHz rungs
// (250, 200, 166.67), so a "230" preset runs at 250 or, after deploy_kria.sh walks it down,
// at 200. Rows labelled 230 in the 2026-08-04 sweeps were measured at both. Recording the
// achieved rate makes every row self-describing instead of requiring the deploy log.
static std::string fclk_hz_str() {
  std::FILE* f = std::fopen("/sys/devices/platform/fclk0/set_rate", "r");
  if (!f) return std::string("unknown");
  char buf[64] = {0};
  const bool ok = std::fgets(buf, sizeof(buf), f) != nullptr;
  std::fclose(f);
  if (!ok) return std::string("unknown");
  std::string v(buf);
  while (!v.empty() && (v.back() == '\n' || v.back() == '\r' || v.back() == ' ')) v.pop_back();
  return v.empty() ? std::string("unknown") : v;
}

static std::string getenv_or(const char* n, const char* d) {
  const char* e = getenv(n);
  return (e && *e) ? std::string(e) : std::string(d);
}

// ---- machine-readable metrics for the batch sweep -------------------------------------
// A run emits one "#CSVH <header>" line and one "#CSVR <values>" line, so a driver script
// sweeping B=1..batchSize takes the header from the first run and appends each row — no
// parsing of the human-readable tables below, which stay exactly as they were. Columns are
// appended in a fixed order (and the op columns sorted by label), so every run of a given
// preset emits an identical header and the rows line up.
struct CsvRow {
  std::string head, vals;
  void add(const char* k, const std::string& v) {
    if (!head.empty()) { head += ','; vals += ','; }
    head += k;
    vals += v;
  }
  void add(const char* k, double v, int prec) {
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%.*f", prec, v);
    add(k, std::string(buf));
  }
  void emit() const { std::printf("#CSVH %s\n#CSVR %s\n", head.c_str(), vals.c_str()); }
};

// The op-level wall-clock breakdown (matmul / swiglu / rmsnorm / ...), per generation.
static void add_profile_csv(CsvRow& row, int runs) {
  const double n = runs > 0 ? runs : 1;
  auto& prof = mmfree::Profiler::instance();
  auto entries = prof.sorted();
  const double total_ns = static_cast<double>(prof.total_ns());
  // sorted() is by time DESCENDING, which can reorder between batch sizes. Sort by label
  // so a sweep's CSV columns stay aligned row to row.
  std::sort(entries.begin(), entries.end(),
            [](const mmfree::Profiler::Entry& a, const mmfree::Profiler::Entry& b) {
              return a.label < b.label;
            });
  row.add("op_total_ms", total_ns / 1e6 / n, 3);
  double matmul_ns = 0.0;
  for (const auto& e : entries) {
    if (e.label == "matmul" || e.label == "lm_head") matmul_ns += static_cast<double>(e.ns);
    row.add(("op_" + e.label + "_ms").c_str(), e.ns / 1e6 / n, 3);
    row.add(("op_" + e.label + "_pct").c_str(), total_ns > 0 ? 100.0 * e.ns / total_ns : 0.0, 2);
    row.add(("op_" + e.label + "_us_call").c_str(), e.count ? (e.ns / 1e3) / e.count : 0.0, 3);
  }
  row.add("op_matmul_incl_lmhead_pct", total_ns > 0 ? 100.0 * matmul_ns / total_ns : 0.0, 2);
  row.add("op_other_pct", total_ns > 0 ? 100.0 * (total_ns - matmul_ns) / total_ns : 0.0, 2);
}

// ---- engine phase breakdown ----------------------------------------------------------
// The five serialized phases of one projection (see mmfree_lib_stats_t): CPU-side
// activation pack, the three blocking LOAD/COMPUTE/STORE round-trips, then the output
// readback. `name` is the human table's label, `key` the CSV column stem.
struct EnginePhase { const char* name; const char* key; std::uint64_t ns; };

static std::vector<EnginePhase> engine_phases(const mmfree_lib_stats_t& s) {
  return {{"pack", "pack", s.pack_ns},
          {"load (IRQ)", "load", s.load_ns},
          {"compute (IRQ)", "compute", s.compute_ns},
          {"store (IRQ)", "store", s.store_ns},
          {"readback", "readback", s.readback_ns}};
}

static void print_engine_profile(mmfree_lib_t* lib, int runs) {
  mmfree_lib_stats_t s;
  mmfree_lib_get_stats(lib, &s);
  if (!s.calls) return;
  const auto ph = engine_phases(s);
  std::uint64_t tot = 0;
  for (const auto& p : ph) tot += p.ns;
  std::printf("\nbitlinear phase breakdown (%llu calls, %.2f ms total, %.1f us/call):\n",
              (unsigned long long)s.calls, tot / 1e6, (double)tot / 1e3 / s.calls);
  std::printf("  %-14s %10s %8s %10s\n", "phase", "ms", "%", "us/call");
  for (const auto& p : ph)
    std::printf("  %-14s %10.2f %7.1f%% %10.2f\n", p.name, p.ns / 1e6,
                tot ? 100.0 * p.ns / tot : 0.0, (double)p.ns / 1e3 / s.calls);
  std::printf("    (of which cache sync: act %.2f us/call, out %.2f us/call)\n",
              (double)s.act_sync_ns / 1e3 / s.calls, (double)s.out_sync_ns / 1e3 / s.calls);
  // Column-tile splitting moves readback OFF the wall clock rather than making it cheaper,
  // so the phases above shrink while this grows. It is the direct measure of what splitting
  // bought, and the signal for tuning MMFREE_SPLIT_CHUNKS.
  if (s.readback_hidden_ns)
    std::printf("    readback hidden under COMPUTE: %.2f ms (%.2f us/call) — %.0f%% of all "
                "readback was free\n",
                s.readback_hidden_ns / 1e6, (double)s.readback_hidden_ns / 1e3 / s.calls,
                100.0 * s.readback_hidden_ns / (s.readback_hidden_ns + s.readback_ns));
  (void)runs;  // the table is per-call / share-of-total, so it needs no per-run division
}

static void add_engine_csv(CsvRow& row, mmfree_lib_t* lib, int runs) {
  const double n = runs > 0 ? runs : 1;
  mmfree_lib_stats_t s;
  mmfree_lib_get_stats(lib, &s);
  const auto ph = engine_phases(s);
  std::uint64_t tot = 0;
  for (const auto& p : ph) tot += p.ns;
  const double calls = s.calls ? (double)s.calls : 1.0;
  row.add("bl_calls_per_run", s.calls / n, 1);
  row.add("bl_total_ms", tot / 1e6 / n, 3);
  row.add("bl_us_call", (double)tot / 1e3 / calls, 3);
  for (const auto& p : ph) {
    row.add((std::string("bl_") + p.key + "_ms").c_str(), p.ns / 1e6 / n, 3);
    row.add((std::string("bl_") + p.key + "_pct").c_str(), tot ? 100.0 * p.ns / tot : 0.0, 2);
    row.add((std::string("bl_") + p.key + "_us_call").c_str(), (double)p.ns / 1e3 / calls, 3);
  }
  row.add("bl_act_sync_us_call", (double)s.act_sync_ns / 1e3 / calls, 3);
  row.add("bl_out_sync_us_call", (double)s.out_sync_ns / 1e3 / calls, 3);
  // Not part of bl_total_ms (it costs no wall time) — see mmfree_lib_stats_t.
  row.add("bl_readback_hidden_ms", s.readback_hidden_ns / 1e6 / n, 3);
  row.add("bl_readback_hidden_pct",
          (s.readback_hidden_ns + s.readback_ns)
              ? 100.0 * s.readback_hidden_ns / (s.readback_hidden_ns + s.readback_ns) : 0.0, 2);
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
  bool bench = false, profile = false, no_eos = false, csv = false, distinct_prompts = false;
  int frac = 10, warmup = 1, reps = 3, serve = 0, top_k = 0;
  std::size_t gen = 32;
  float temperature = 0.0f;             // 0 => greedy
  std::uint64_t seed = 0;

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
    else if (k == "--csv") csv = true;
    else if (k == "--serve") serve = std::atoi(next());
    else if (k == "--distinct-prompts") distinct_prompts = true;
    else if (k == "--temp") temperature = (float)std::atof(next());
    else if (k == "--top-k") top_k = std::atoi(next());
    else if (k == "--seed") seed = (std::uint64_t)std::strtoull(next(), nullptr, 0);
    else if (k == "--no-eos") no_eos = true;
    else { std::fprintf(stderr, "unknown arg: %s\n", k.c_str()); return 2; }
  }
  const bool use_fpga = (backend == "fpga" || backend == "both");
  // cpu-1p6 / cpu-2bit are the CPU arms at the FPGA's two weight encodings; plain `cpu`
  // is the CPU's native int8 (8 bits) and remains its fastest configuration.
  const bool use_tq1 = (backend == "cpu-1p6" || backend == "cpu-tq1");  // cpu-tq1: old name
  const bool use_tq2 = (backend == "cpu-2bit");
  if (backend != "cpu" && !use_tq1 && !use_tq2 && backend != "fpga" && backend != "both") {
    std::fprintf(stderr, "--backend must be cpu|cpu-2bit|cpu-1p6|fpga|both\n");
    return 2;
  }
  ActQuant aq = (mode == "fixed") ? ActQuant::FixedQ510 : ActQuant::Float;
  if (use_fpga && aq != ActQuant::FixedQ510) {
    std::fprintf(stderr, "the engine only runs the fixed (FixedQ510) path; use --mode fixed\n");
    return 2;
  }

  // ---- resolve the prompt ids ----
  // Keep the tokenizer alive past prompt encoding: the streaming generate path below
  // reuses it to decode each new token to text. Loaded lazily (only --prompt strictly
  // needs it; --ids streaming decodes if a tokenizer is reachable, else prints ids).
  std::unique_ptr<Tokenizer> tok;
  auto load_tok = [&]() -> Tokenizer* {
    if (!tok) {
      try { tok = std::make_unique<Tokenizer>(tok_path.empty() ? default_tokenizer_path(blob) : tok_path); }
      catch (const std::exception&) { tok = nullptr; }
    }
    return tok.get();
  };
  std::vector<std::int64_t> ids;
  if (!ids_arg.empty()) {
    ids = parse_ids(ids_arg);
  } else if (!prompt.empty()) {
    Tokenizer* t = load_tok();
    if (!t) { std::fprintf(stderr, "--prompt needs a tokenizer (looked for %s)\n",
                           (tok_path.empty() ? default_tokenizer_path(blob) : tok_path).c_str()); return 2; }
    ids = t->encode(prompt, /*add_bos=*/true);
  } else {
    ids = {1, 415, 310, 29871, 13, 306, 626, 263};  // fixed pseudo-prompt (no tokenizer)
  }
  if (ids.empty()) { std::fprintf(stderr, "no input ids\n"); return 2; }

  // ---- per-stream prompts for --serve ----
  // Default: every stream gets the SAME prompt, which with greedy sampling makes all B
  // streams identical by construction. That checks batched decode == single-stream decode
  // and that no stream is dropped, but identical streams are invariant under permutation:
  // if the engine's drain landed row 2 in stream 5, nothing here would move. --distinct-
  // prompts rotates the ids per stream so each stream has its own continuation, and the
  // check below compares each against its own single-stream reference. Rotation (rather
  // than a longer/shorter prompt) keeps every prompt the same length, so prefill cost and
  // the reported timings stay comparable to a shared-prompt run. Built here, before the
  // engine is opened, so a degenerate configuration fails before touching the hardware.
  std::vector<std::vector<std::int64_t>> prompts;
  if (serve > 0) {
    prompts.assign((std::size_t)serve, ids);
    if (distinct_prompts) {
      for (std::size_t b = 0; b < prompts.size(); ++b)
        std::rotate(prompts[b].begin(), prompts[b].begin() + (b % ids.size()), prompts[b].end());
      // Rotations repeat once B exceeds the prompt length (and earlier for a periodic
      // prompt). Two equal prompts would silently reopen the permutation blind spot for
      // that pair, so refuse rather than report a weaker check as a passing one.
      for (std::size_t b = 1; b < prompts.size(); ++b)
        for (std::size_t c = 0; c < b; ++c)
          if (prompts[b] == prompts[c]) {
            std::fprintf(stderr,
                         "--distinct-prompts: streams %zu and %zu got the same prompt "
                         "(rotations of a %zu-token prompt repeat at B>%zu). Pass a longer "
                         "--prompt/--ids.\n",
                         c, b, ids.size(), ids.size());
            return 2;
          }
    }
  }

  // Report the SIMD backend the kernels were COMPILED with. Not decoration: the CPU side is
  // ~57% of wall time on the FPGA path, and a scalar libmmfreecpu.a produces bit-identical
  // results — so the gate cannot catch it and only a low tok/s would hint at it. (2026-07-30:
  // a whole measurement session ran on a binary that turned out to be ~12% slower on the CPU
  // side than a rebuild from the same sources and flags. A scalar build was ruled out by
  // binary size, so that one was stale code rather than a wrong backend — but nothing printed
  // enough to tell them apart at the time, which is the gap this closes.)
  // simd::backend() reports the ARCH family only — it returns the literal "x86-avx2" for any
  // MMFREE_ARCH_X86 build, AVX-512 included (simd.hpp:39). That is exactly the distinction
  // this line exists to make, so widen it here from the macros the packed-ternary kernels
  // actually switch on (tq1.hpp:71). Measured cost of getting it wrong on the W-2155: the
  // 1.6-bit arm runs 31% slower and the 2-bit arm 24% slower, with nothing else moving —
  // int8 has no AVX-512 path, so a narrow build looks completely healthy in the int8 column.
  // On 2026-08-06 a stray rebuild mid-sweep did precisely this and the old line printed
  // "x86-avx2" before AND after, which is what made two run-groups look valid.
#if defined(__AVX512F__) && defined(__AVX512BW__) && defined(__AVX512VL__)
  const char* simd_detail = "x86-avx512 (packed-ternary kernels: 512-bit)";
#elif defined(MMFREE_ARCH_X86)
  const char* simd_detail = "x86-avx2 (packed-ternary kernels: 256-bit — NOT the baseline build)";
#else
  const char* simd_detail = nullptr;
#endif
  std::printf("simd backend: %s%s%s\n", mmfree::simd::backend(),
              simd_detail ? "  ->  " : "", simd_detail ? simd_detail : "");
  // Flush it NOW. Piped stdout is fully buffered, so anything that aborts later — a missing
  // blob, an uncaught exception — discards this line, and a caller probing the binary for its
  // build (final_sweep_xeon.sh does exactly that) sees nothing and cannot tell a narrow build
  // from an unreadable one. The banner has to survive the process dying after it.
  std::fflush(stdout);

  // ---- CPU-side weights + model (norms, scales, embeddings, wq for CPU/Float) ----
  Weights w(blob);
  Model model(w, aq, frac);

  // ---- cpu-tq1: same CPU reduction, weights repacked to 1.6 bits on first touch ----
  // Installed before the engine block so `--backend cpu-tq1` never opens a device. The
  // backend packs each projection lazily (keyed on its mmap'd wq pointer) and caches it,
  // so the cost lands on the first decode step — which a --warmup run absorbs.
  std::unique_ptr<mmfree::Tq1Backend> tq1;
  std::unique_ptr<mmfree::Tq2Backend> tq2;
  double cpu_bits = 8.0;
  if (use_tq1) {
    tq1.reset(new mmfree::Tq1Backend());
    model.set_backend(tq1.get(), {});  // no proj_ids: the CPU backends ignore proj_id
    cpu_bits = mmfree::Tq1Backend::format_bits();
    std::printf("weight format: TQ1_0 %.4f bits/weight (%zu-weight blocks, %zu B each)\n",
                cpu_bits, mmfree::tq1::kBlockElems, mmfree::tq1::kBlockBytes);
  } else if (use_tq2) {
    tq2.reset(new mmfree::Tq2Backend());
    model.set_backend(tq2.get(), {});
    cpu_bits = mmfree::Tq2Backend::format_bits();
    std::printf("weight format: 2-bit %.4f bits/weight (%zu-weight blocks, %zu B each)\n",
                cpu_bits, mmfree::tq2::kBlockElems, mmfree::tq2::kBlockBytes);
  } else if (!use_fpga) {
    std::printf("weight format: int8 8.0000 bits/weight (unpacked ternary)\n");
  }

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
    cfg.packed = env_u32("MMFREE_PACKED_WEIGHTS", 0);  // 1.6-bit weight stream (must match bitstream + blob)
    // Guardrail: a 1.6-bit blob fed to a 2-bit engine (or vice versa) is silent
    // garbage. The blob's weight_format tag MUST match the bitstream's packing.
    // Empty tag = pre-tag blob, which is necessarily 2-bit.
    {
      const std::string want = cfg.packed ? "ternary-1p6" : "ternary-2bit";
      const std::string got = man.weight_format.empty() ? "ternary-2bit" : man.weight_format;
      if (got != want) {
        std::fprintf(stderr,
            "FATAL: weight blob format '%s' != bitstream format '%s' "
            "(MMFREE_PACKED_WEIGHTS=%u). Re-pack with the matching preset.\n",
            man.weight_format.empty() ? "(untagged/2-bit)" : man.weight_format.c_str(),
            want.c_str(), cfg.packed);
        return 1;
      }
    }
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
      // The line above this one says WHICH buffer failed and how (missing / unopenable /
      // short). Only the short case is a sizing problem, so lead with the sizes rather
      // than with advice that assumes it: a missing device means the overlay is not loaded.
      std::fprintf(stderr,
                   "mmfree_lib_open failed. This model needs, per the manifest:\n"
                   "  wt  >= %llu B/port  (total_bytes_per_port)\n"
                   "  act >= %zu B/port\n  out >= %zu B\n"
                   "If a buffer is genuinely too small, regenerate the DT overlay with a "
                   "larger wt buffer (e.g. UDMABUF_WT_SZ=0x01800000) and redeploy; see "
                   "fpga_runner/README.md.\n",
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
    Sampling samp;  // greedy → deterministic, so every stream must match the single-stream ref

    // Correctness reference: each prompt decoded the existing (single-stream) way. This also
    // warms the resident weights / udmabuf mapping before anything is timed. With the shared
    // prompt one reference serves every stream; with --distinct-prompts each stream needs its
    // own, which costs B single-stream generations up front (untimed).
    std::vector<std::vector<std::int64_t>> ref;
    ref.push_back(model.generate(prompts[0], gen, /*eos=*/-1, samp));
    if (distinct_prompts)
      for (std::size_t b = 1; b < Bn; ++b)
        ref.push_back(model.generate(prompts[b], gen, /*eos=*/-1, samp));
    auto ref_for = [&](std::size_t b) -> const std::vector<std::int64_t>& {
      return ref[distinct_prompts ? b : 0];
    };

    // How many of the references are actually distinct. A permutation can only be SEEN
    // where two streams' expected outputs differ, so this is the check's real strength:
    // Bn means every pair is separable, 1 means the run proves nothing about row order.
    std::size_t distinct_refs = 0;
    for (std::size_t b = 0; b < ref.size(); ++b) {
      bool novel = true;
      for (std::size_t c = 0; c < b && novel; ++c) novel = ref[b] != ref[c];
      if (novel) ++distinct_refs;
    }

    using clk = std::chrono::steady_clock;
    auto secs = [](clk::duration d) { return std::chrono::duration<double>(d).count(); };

    // One timed serve run, split at the token boundaries generate_serve's callback exposes:
    //   ttft    — first token of the FIRST stream. Prefill is stream-sequential, so this is
    //             one prompt's prefill: what the batch's first request actually waits.
    //   prefill — first token of the LAST stream, i.e. the whole prefill phase / the point
    //             batched decode can start. Equals ttft at B=1 and grows ~B*ttft above it.
    //   decode  — prefill end -> last token: the steady-state batched phase, the one the
    //             engine's weight-stream amortization actually applies to.
    struct ServeStat { double ttft_s, prefill_s, decode_s, total_s; std::size_t gen_per, steps; };
    std::vector<std::vector<std::int64_t>> out;
    auto run_once = [&]() -> ServeStat {
      std::vector<char> seen(Bn, 0);
      std::size_t firsts = 0;
      clk::time_point t0 = clk::now(), t_first = t0, t_prefill = t0, t_last = t0;
      auto cb = [&](std::size_t b, std::int64_t) {
        clk::time_point now = clk::now();
        if (b < Bn && !seen[b]) {          // a stream's FIRST token = its prefill completing
          seen[b] = 1;
          if (++firsts == 1) t_first = now;
          if (firsts == Bn) t_prefill = now;
        }
        t_last = now;
      };
      out = model.generate_serve(prompts, gen, /*eos=*/-1, samp, cb);
      const std::size_t gen_per = out.empty() ? 0 : out[0].size() - ids.size();
      const std::size_t steps = gen_per > 1 ? gen_per - 1 : 0;   // decode steps after prefill
      return {secs(t_first - t0), secs(t_prefill - t0),
              steps ? secs(t_last - t_prefill) : 0.0, secs(t_last - t0), gen_per, steps};
    };

    // Untimed warmup, then the timed reps. The op profiler and the engine's phase counters
    // accumulate across the timed reps only (warmup excluded) and are divided by reps
    // below, so the breakdown lines up with the reported tok/s — same contract as run_bench.
    for (int i = 0; i < (bench ? warmup : 0); ++i) run_once();
    if (profile) { Profiler::instance().reset(); Profiler::instance().enable(true); }
    if (lib && profile) mmfree_lib_reset_stats(lib);

    const int rr = bench ? reps : 1;
    ServeStat sum{};
    ServeStat last{};
    for (int r = 0; r < rr; ++r) {
      ServeStat s = run_once();
      last = s;
      sum.ttft_s += s.ttft_s; sum.prefill_s += s.prefill_s;
      sum.decode_s += s.decode_s; sum.total_s += s.total_s;
      if (bench)
        std::printf("  run %d: ttft %7.1f ms  prefill %7.1f ms  decode %8.2f tok/s agg\n",
                    r + 1, s.ttft_s * 1e3, s.prefill_s * 1e3,
                    s.decode_s > 0 ? (double)(Bn * s.steps) / s.decode_s : 0.0);
    }
    if (profile) Profiler::instance().enable(false);

    const double n = rr > 0 ? rr : 1;
    const double ttft = sum.ttft_s / n, prefill = sum.prefill_s / n;
    const double decode = sum.decode_s / n, total = sum.total_s / n;
    const std::size_t gen_per = last.gen_per, steps = last.steps;
    // Aggregate = tokens across all B streams; per-stream = one stream's own rate. On a
    // weight-bandwidth-bound engine the aggregate is the metric batching moves.
    const double dec_agg  = decode > 0 ? (double)(Bn * steps) / decode : 0.0;
    const double all_agg  = total  > 0 ? (double)(Bn * gen_per) / total : 0.0;
    const double tok_lat  = steps ? decode * 1e3 / steps : 0.0;  // ms per batched decode step
    const double pre_tps  = prefill > 0 ? (double)(Bn * ids.size()) / prefill : 0.0;

    std::size_t bad = 0;
    std::vector<std::string> notes;
    char note[256];
    for (std::size_t b = 0; b < out.size(); ++b) {
      if (out[b] == ref_for(b)) continue;
      ++bad;
      // A row swap has a signature the plain mismatch count hides: stream b reproduces
      // ANOTHER stream's reference exactly. Say so — that distinguishes "the drain wrote
      // the wrong slot" from "the arithmetic is wrong", which need different fixes.
      std::size_t matched = ref.size();
      for (std::size_t c = 0; c < ref.size(); ++c)
        if (c != b && out[b] == ref[c]) { matched = c; break; }
      if (matched < ref.size())
        snprintf(note, sizeof(note),
                 "  stream %zu MISMATCH — it is stream %zu's reference verbatim "
                 "(ROW PERMUTATION, not an arithmetic error)", b, matched);
      else {
        std::size_t at = 0;
        while (at < out[b].size() && at < ref_for(b).size() && out[b][at] == ref_for(b)[at]) ++at;
        snprintf(note, sizeof(note), "  stream %zu MISMATCH — diverges at token %zu of %zu",
                 b, at, out[b].size());
      }
      notes.emplace_back(note);
    }
    if (out.size() != Bn) {   // a dropped stream is a failure even if the survivors match
      snprintf(note, sizeof(note), "  serve returned %zu streams, expected %zu",
               out.size(), Bn);
      notes.emplace_back(note);
      bad += Bn - std::min(out.size(), Bn);
    }
    // Without distinct prompts the streams are identical by construction and a permutation
    // is undetectable. Say what the check did and did not cover rather than letting an
    // "OK" line imply more than it proves.
    if (!distinct_prompts)
      notes.emplace_back("  (shared prompt: proves batched == single-stream and no stream "
                         "dropped; blind to row order — rerun with --distinct-prompts)");
    else if (distinct_refs < Bn) {
      snprintf(note, sizeof(note),
               "  WARN: only %zu of %zu references are distinct — streams sharing a "
               "reference stay interchangeable. Try a longer --gen or --prompt.",
               distinct_refs, Bn);
      notes.emplace_back(note);
    }

    std::printf("serve B=%zu: %s\n", Bn,
                bad ? "STREAM MISMATCH (serve != single-stream)"
                    : (distinct_prompts
                           ? "OK (every stream == its OWN single-stream reference)"
                           : "OK (all streams == single-stream)"));
    for (const auto& s : notes) std::printf("%s\n", s.c_str());
    std::printf("  ttft (1st stream):   %8.1f ms\n", ttft * 1e3);
    std::printf("  prefill (all %zu):    %8.1f ms  (%.2f tok/s over %zu prompt tokens)\n",
                Bn, prefill * 1e3, pre_tps, Bn * ids.size());
    std::printf("  token latency:       %8.2f ms/step (all %zu streams advance per step)\n",
                tok_lat, Bn);
    std::printf("  decode:              %8.2f tok/s aggregate  (%.2f tok/s/stream)\n",
                dec_agg, Bn ? dec_agg / Bn : 0.0);
    std::printf("  overall:             %8.2f tok/s aggregate  (%zu gen tok/stream, %.3f s/run)\n",
                all_agg, gen_per, total);

    if (profile) print_profile(rr);
    if (lib && profile) print_engine_profile(lib, rr);

    if (csv) {
      CsvRow row;
      row.add("preset", getenv_or("MMFREE_PRESET", "?"));
      row.add("clk_mhz", getenv_or("MMFREE_CLK_MHZ", "?"));
      // The rate the fabric ACTUALLY ran at, which clk_mhz above does not give.
      row.add("fclk_hz", fclk_hz_str());
      row.add("hw_batch", getenv_or("MMFREE_BATCH", "?"));
      // The BITSTREAM's compile-time batchSize, which hw_batch above is NOT: bench_batch.sh
      // passes BATCH=b per run, so MMFREE_BATCH carries the runtime batch and hw_batch just
      // duplicates the `batch` column. The ceiling has to come from the manifest, and it is
      // what tells a downstream aggregator which batch ladder this row is a complete or
      // partial cover of — b=1..batchSize all come out of one build (cmpBatch), b>batchSize
      // is physically impossible, so 1..this IS the expected sweep.
      row.add("hw_batch_max", getenv_or("MMFREE_HW_BATCH", getenv_or("MMFREE_BATCH", "?").c_str()));
      row.add("packed_weights", getenv_or("MMFREE_PACKED_WEIGHTS", "0"));
      row.add("model", blob);
      // CPU-side weight representation, so an int8 arm and a TQ1_0 arm are
      // distinguishable in the CSV (0 = the engine owns the matmul).
      row.add("cpu_wt_bits", use_fpga ? 0.0 : cpu_bits, 4);
      row.add("batch", (double)Bn, 0);
      row.add("prompt_tokens", (double)ids.size(), 0);
      row.add("gen_tokens", (double)gen_per, 0);
      row.add("reps", (double)rr, 0);
      row.add("streams_ok", bad ? "0" : "1");
      // How strong that streams_ok is. distinct_prompts=0 means the streams were identical
      // by construction, so the row carries no evidence about row order; =1 means each
      // stream was checked against its own reference, and distinct_refs is how many of
      // those references were actually different (Bn = every pair separable).
      row.add("distinct_prompts", distinct_prompts ? 1.0 : 0.0, 0);
      row.add("distinct_refs", (double)distinct_refs, 0);
      row.add("ttft_ms", ttft * 1e3, 3);
      row.add("prefill_ms", prefill * 1e3, 3);
      row.add("prefill_tok_s", pre_tps, 3);
      row.add("token_latency_ms", tok_lat, 4);
      row.add("decode_tok_s", dec_agg, 3);
      row.add("decode_tok_s_per_stream", Bn ? dec_agg / Bn : 0.0, 3);
      row.add("overall_tok_s", all_agg, 3);
      row.add("decode_s", decode, 4);
      row.add("total_s", total, 4);
      if (profile) add_profile_csv(row, rr);
      if (lib && profile) add_engine_csv(row, lib, rr);
      row.emit();
    }
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
    BenchResult br = run_bench(model, ids, opts, blob.c_str(), mode.c_str(), frac);

    if (lib && profile) print_engine_profile(lib, reps);

    // Single-stream row, column-compatible with the --serve sweep so a B=1 reference from
    // the plain decode path can sit in the same CSV. Single-stream decode emits one token
    // per step, so its token latency is just the inverse of its decode rate, and prefill
    // IS the time to first token.
    if (csv) {
      CsvRow row;
      row.add("preset", getenv_or("MMFREE_PRESET", "?"));
      row.add("clk_mhz", getenv_or("MMFREE_CLK_MHZ", "?"));
      // The rate the fabric ACTUALLY ran at, which clk_mhz above does not give.
      row.add("fclk_hz", fclk_hz_str());
      row.add("hw_batch", getenv_or("MMFREE_BATCH", "?"));
      // The bitstream's compile-time batchSize (see the --serve row above): the ceiling of
      // the batch ladder, as opposed to hw_batch's per-run value.
      row.add("hw_batch_max", getenv_or("MMFREE_HW_BATCH", getenv_or("MMFREE_BATCH", "?").c_str()));
      row.add("packed_weights", getenv_or("MMFREE_PACKED_WEIGHTS", "0"));
      row.add("model", blob);
      // CPU-side weight representation, so an int8 arm and a TQ1_0 arm are
      // distinguishable in the CSV (0 = the engine owns the matmul).
      row.add("cpu_wt_bits", use_fpga ? 0.0 : cpu_bits, 4);
      row.add("batch", 1.0, 0);
      row.add("prompt_tokens", (double)br.prompt_tokens, 0);
      row.add("gen_tokens", (double)br.gen_tokens, 0);
      row.add("reps", (double)reps, 0);
      row.add("streams_ok", "1");
      // One stream, so row order is trivially right and there is exactly one reference —
      // present only to keep this row column-compatible with the --serve rows.
      row.add("distinct_prompts", 0.0, 0);
      row.add("distinct_refs", 1.0, 0);
      row.add("ttft_ms", br.avg_prefill_s * 1e3, 3);
      row.add("prefill_ms", br.avg_prefill_s * 1e3, 3);
      row.add("prefill_tok_s",
              br.avg_prefill_s > 0 ? br.prompt_tokens / br.avg_prefill_s : 0.0, 3);
      row.add("token_latency_ms", br.avg_decode_tps > 0 ? 1e3 / br.avg_decode_tps : 0.0, 4);
      row.add("decode_tok_s", br.avg_decode_tps, 3);
      row.add("decode_tok_s_per_stream", br.avg_decode_tps, 3);
      row.add("overall_tok_s", br.avg_overall_tps, 3);
      row.add("decode_s", br.avg_decode_tps > 0 ? br.gen_tokens / br.avg_decode_tps : 0.0, 4);
      row.add("total_s", br.avg_overall_tps > 0 ? br.gen_tokens / br.avg_overall_tps : 0.0, 4);
      if (profile) add_profile_csv(row, reps);
      if (lib && profile) add_engine_csv(row, lib, reps);
      row.emit();
    }
  } else {
    Sampling samp;                      // greedy unless --temp/--top-k given
    samp.temperature = temperature; samp.top_k = top_k; samp.seed = seed;
    Tokenizer* t = load_tok();          // null → no tokenizer reachable; stream ids instead
    // Stop at the model's EOS once we have a tokenizer (it knows the id); --no-eos
    // forces a full --gen run (e.g. to inspect post-answer behavior). Without a
    // tokenizer there's no reliable EOS, so run the full length.
    const std::int64_t eos = (t && !no_eos) ? t->eos_id() : -1;
    // Live streaming: print each new token the moment generate() produces it. With a
    // tokenizer we decode incrementally — re-decode the full id list each step and emit
    // only the newly-appended text, which keeps BPE merges + leading spaces correct
    // (decoding a lone piece would mangle them). flush so it appears token-by-token.
    std::vector<std::int64_t> grown = ids;
    std::string shown = t ? t->decode(ids) : std::string{};
    if (t) { std::fputs(shown.c_str(), stdout); std::fflush(stdout); }
    auto on_token = [&](std::int64_t id) {
      grown.push_back(id);
      if (t) {
        std::string full = t->decode(grown);
        if (full.size() >= shown.size() && full.compare(0, shown.size(), shown) == 0) {
          std::fputs(full.c_str() + shown.size(), stdout);   // emit only the new suffix
        } else {                                              // (rare) re-render from scratch
          std::fputs("\r", stdout); std::fputs(full.c_str(), stdout);
        }
        shown = std::move(full);
      } else {
        std::printf("%lld ", (long long)id);                  // no tokenizer: live ids
      }
      std::fflush(stdout);
    };
    model.generate(ids, gen, eos, samp, on_token);
    std::fputc('\n', stdout);
  }

  if (tq1)
    std::printf("cpu-1p6: packed %zu projections, %.1f MiB resident (%.4f bits/weight)\n",
                tq1->packed_tensors(), (double)tq1->packed_bytes() / (1024.0 * 1024.0), cpu_bits);
  if (tq2)
    std::printf("cpu-2bit: packed %zu projections, %.1f MiB resident (%.4f bits/weight)\n",
                tq2->packed_tensors(), (double)tq2->packed_bytes() / (1024.0 * 1024.0), cpu_bits);
  if (lib) mmfree_lib_close(lib);
  return rc;
}
