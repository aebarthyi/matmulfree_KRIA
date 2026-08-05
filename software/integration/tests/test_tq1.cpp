// test_tq1.cpp — correctness of the CPU-side TQ1_0 1.6-bit ternary weights.
//
// Runs on the build server (x86) AND on the board (aarch64), so the same suite covers the
// AVX2 and the NEON extraction paths — the deployment target is the NEON one, and a packing
// bug there would not crash, it would silently produce plausible logits.
//
// Four checks, in order of strength:
//   1. EXHAUSTIVE encode/extract over the whole input domain: all 3^5 = 243 five-trit groups
//      and all 3^4 = 81 four-trit groups. Also asserts the branchless compare form
//      (q>=86)+(q>=171) equals llama.cpp's ((uint16)q*3)>>8 for every one of the 256*5 and
//      256*4 (byte, position) pairs. This is a proof, not a sample.
//   2. Round trip pack -> unpack over random ternary rows for every real MMfreeLM inner dim.
//   3. dot_i32 EXACTLY equals simd::ternary_dot_i32 on the same weights. Exact, not close:
//      the packing is lossless and integer addition is associative, so a differing summation
//      order cannot move the result. This is the property the CPU==FPGA gate depends on.
//   4. dot_f32 matches the scalar fp reference to reduction-order noise.
#include "mmfree/simd.hpp"
#include "mmfree/tq1.hpp"

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <string>
#include <vector>

namespace {

int g_fail = 0;

void check(bool ok, const char* what) {
  if (!ok) {
    std::printf("  FAIL: %s\n", what);
    ++g_fail;
  }
}

// ---- 1. exhaustive encode/extract -----------------------------------------------------
void test_exhaustive() {
  std::printf("[1] exhaustive encode/extract over the full domain\n");

  // The two extraction forms must agree for EVERY byte and position.
  int form_mismatch = 0;
  for (int q = 0; q < 256; ++q) {
    for (std::size_t l = 0; l < 5; ++l) {
      const std::uint8_t ql = (std::uint8_t)((std::uint8_t)q * mmfree::tq1::pow3()[l]);
      const int cmp_form = (int)(ql >= 86) + (int)(ql >= 171) - 1;
      const int mul_form = (int)(((std::uint16_t)ql * 3) >> 8) - 1;  // llama.cpp's form
      if (cmp_form != mul_form) ++form_mismatch;
    }
  }
  check(form_mismatch == 0, "compare form != llama.cpp multiply-shift form");
  std::printf("      compare form == ((uint16)q*3)>>8 for all 256 x 5 (byte, position)\n");

  // All 243 five-trit groups: pack a block whose group-A byte 0 carries them, read back.
  int five_bad = 0;
  for (int v = 0; v < 243; ++v) {
    int u[5] = {(v / 81) % 3, (v / 27) % 3, (v / 9) % 3, (v / 3) % 3, v % 3};
    std::vector<std::int8_t> w(mmfree::tq1::kBlockElems, 0);
    for (std::size_t l = 0; l < 5; ++l) w[0 + 32 * l] = (std::int8_t)(u[l] - 1);
    std::vector<std::uint8_t> p(mmfree::tq1::row_bytes(mmfree::tq1::kBlockElems));
    mmfree::tq1::pack_row(p.data(), w.data(), mmfree::tq1::kBlockElems);
    for (std::size_t l = 0; l < 5; ++l)
      if (mmfree::tq1::trit_at(p[0], l) != u[l] - 1) ++five_bad;
  }
  check(five_bad == 0, "five-trit group round trip");
  std::printf("      all 243 five-trit groups round trip\n");

  // All 81 four-trit groups (the qh remainder).
  int four_bad = 0;
  for (int v = 0; v < 81; ++v) {
    int u[4] = {(v / 27) % 3, (v / 9) % 3, (v / 3) % 3, v % 3};
    std::vector<std::int8_t> w(mmfree::tq1::kBlockElems, 0);
    for (std::size_t l = 0; l < 4; ++l) w[240 + 0 + 4 * l] = (std::int8_t)(u[l] - 1);
    std::vector<std::uint8_t> p(mmfree::tq1::row_bytes(mmfree::tq1::kBlockElems));
    mmfree::tq1::pack_row(p.data(), w.data(), mmfree::tq1::kBlockElems);
    for (std::size_t l = 0; l < 4; ++l)
      if (mmfree::tq1::trit_at(p[mmfree::tq1::kQsBytes], l) != u[l] - 1) ++four_bad;
  }
  check(four_bad == 0, "four-trit group round trip");
  std::printf("      all 81 four-trit groups round trip\n");

  // 2-bit format: exhaustive over all 3^4 = 81 four-trit groups (its whole domain, since a
  // byte holds exactly 4 trits and only 81 of the 256 byte values are reachable).
  int tq2_bad = 0;
  for (int v = 0; v < 81; ++v) {
    int u[4] = {v % 3, (v / 3) % 3, (v / 9) % 3, (v / 27) % 3};
    std::vector<std::int8_t> w(mmfree::tq2::kBlockElems, 0);
    for (std::size_t l = 0; l < 4; ++l) w[0 + 64 * l] = (std::int8_t)(u[l] - 1);
    std::vector<std::uint8_t> p(mmfree::tq2::row_bytes(mmfree::tq2::kBlockElems));
    mmfree::tq2::pack_row(p.data(), w.data(), mmfree::tq2::kBlockElems);
    for (std::size_t l = 0; l < 4; ++l)
      if (mmfree::tq2::trit_at(p[0], l) != u[l] - 1) ++tq2_bad;
  }
  check(tq2_bad == 0, "2-bit four-trit group round trip");
  std::printf("      all 81 two-bit groups round trip (2.0000 bits/weight)\n");
}

// ---- 2/3/4. round trip + dot equivalence on real shapes -------------------------------
void test_shapes() {
  // Every inner dim the released MMfreeLM checkpoints use, plus a couple of extras.
  const std::size_t dims[] = {256, 512, 1024, 2048, 2560, 2816, 5632, 6912};
  std::mt19937 rng(12345);
  std::uniform_int_distribution<int> trit(-1, 1);
  std::uniform_int_distribution<int> act(-32768, 32767);  // the Q5.10 int16 range
  std::uniform_real_distribution<float> actf(-4.0f, 4.0f);

  std::printf("[2] pack/unpack round trip   [3] dot_i32 bit-exact   [4] dot_f32 tolerance\n");
  std::printf("      %-6s  %-33s |  %s\n", "N", "TQ1_0: bits roundtrip dot_i32 relerr",
              "2-bit: bits roundtrip dot_i32 relerr");
  for (std::size_t n : dims) {
    if (!mmfree::tq1::fits(n)) {
      std::printf("      %-6zu (skipped: not a multiple of 256)\n", n);
      continue;
    }
    std::vector<std::int8_t> w(n), back(n);
    std::vector<std::int32_t> x(n);
    std::vector<float> xf(n);
    for (std::size_t i = 0; i < n; ++i) {
      w[i] = (std::int8_t)trit(rng);
      x[i] = act(rng);
      xf[i] = actf(rng);
    }
    std::vector<std::uint8_t> p(mmfree::tq1::row_bytes(n));
    mmfree::tq1::pack_row(p.data(), w.data(), n);
    mmfree::tq1::unpack_row(back.data(), p.data(), n);

    bool rt = true;
    for (std::size_t i = 0; i < n; ++i) rt = rt && (back[i] == w[i]);

    // Reference (int8 kernel) vs packed kernel — must be EXACTLY equal.
    const std::int32_t ref_i = mmfree::simd::ternary_dot_i32(x.data(), w.data(), n);
    const std::int32_t got_i = mmfree::tq1::dot_i32(x.data(), p.data(), n);
    // The 2-bit control format, same properties, same exactness requirement.
    std::vector<std::uint8_t> p2(mmfree::tq2::row_bytes(n));
    std::vector<std::int8_t> back2(n);
    mmfree::tq2::pack_row(p2.data(), w.data(), n);
    mmfree::tq2::unpack_row(back2.data(), p2.data(), n);
    bool rt2 = true;
    for (std::size_t i = 0; i < n; ++i) rt2 = rt2 && (back2[i] == w[i]);
    const std::int32_t got2_i = mmfree::tq2::dot_i32(x.data(), p2.data(), n);
    const std::int32_t sca2_i =
        mmfree::tq2::dot_scalar<std::int32_t, std::int32_t>(x.data(), p2.data(), n);
    const float got2_f = mmfree::tq2::dot_f32(xf.data(), p2.data(), n);
    check(rt2, "tq2 pack/unpack round trip");
    check(got2_i == ref_i, "tq2 dot_i32 == int8 ternary_dot_i32 (exact)");
    check(sca2_i == ref_i, "tq2 dot_scalar == int8 ternary_dot_i32 (exact)");
    // Scalar packed reference too, so a broken SIMD path can't hide behind a broken pack.
    const std::int32_t sca_i =
        mmfree::tq1::dot_scalar<std::int32_t, std::int32_t>(x.data(), p.data(), n);

    const float ref_f = mmfree::simd::ternary_dot_f32(xf.data(), w.data(), n);
    const float got_f = mmfree::tq1::dot_f32(xf.data(), p.data(), n);
    const float rel = std::fabs(ref_f) > 0.0f ? std::fabs(got_f - ref_f) / std::fabs(ref_f)
                                              : std::fabs(got_f - ref_f);

    check(rt, "pack/unpack round trip");
    check(got_i == ref_i, "dot_i32 == int8 ternary_dot_i32 (exact)");
    check(sca_i == ref_i, "dot_scalar == int8 ternary_dot_i32 (exact)");
    check(rel <= 1e-5f, "dot_f32 within reduction-order tolerance");

    const float rel2 = std::fabs(ref_f) > 0.0f ? std::fabs(got2_f - ref_f) / std::fabs(ref_f)
                                               : std::fabs(got2_f - ref_f);
    check(rel2 <= 1e-5f, "tq2 dot_f32 within reduction-order tolerance");
    std::printf("      %-6zu  tq1 %-6.3f %-9s %-9s %.1e |  tq2 %-5.3f %-9s %-9s %.1e\n", n,
                8.0 * (double)mmfree::tq1::row_bytes(n) / (double)n, rt ? "ok" : "MISMATCH",
                got_i == ref_i ? "exact" : "MISMATCH", (double)rel,
                8.0 * (double)mmfree::tq2::row_bytes(n) / (double)n, rt2 ? "ok" : "MISMATCH",
                got2_i == ref_i ? "exact" : "MISMATCH", (double)rel2);
  }
}

// ---- 3b. many random draws, to catch order-dependent SIMD bugs -------------------------
void test_random_many() {
  std::printf("[3b] 2000 random vectors at N=1024 and N=2560, dot_i32 must be exact\n");
  std::mt19937 rng(999);
  std::uniform_int_distribution<int> trit(-1, 1);
  std::uniform_int_distribution<int> act(-32768, 32767);
  int bad = 0;
  for (int rep = 0; rep < 2000; ++rep) {
    const std::size_t n = (rep % 2) ? 2560 : 1024;
    std::vector<std::int8_t> w(n);
    std::vector<std::int32_t> x(n);
    for (std::size_t i = 0; i < n; ++i) {
      w[i] = (std::int8_t)trit(rng);
      x[i] = act(rng);
    }
    std::vector<std::uint8_t> p(mmfree::tq1::row_bytes(n));
    mmfree::tq1::pack_row(p.data(), w.data(), n);
    if (mmfree::tq1::dot_i32(x.data(), p.data(), n) !=
        mmfree::simd::ternary_dot_i32(x.data(), w.data(), n))
      ++bad;
  }
  check(bad == 0, "random dot_i32 exactness");
  std::printf("      %d/2000 mismatches\n", bad);
}

// ---- backend equivalence: Tq1Backend vs CpuBackend on a whole projection ---------------
void test_backend() {
  std::printf("[5] Tq1Backend vs CpuBackend over a full projection (N=1024, M=512)\n");
  const std::size_t N = 1024, M = 512;
  std::mt19937 rng(7);
  std::uniform_int_distribution<int> trit(-1, 1);
  std::uniform_int_distribution<int> act(-32768, 32767);
  std::vector<std::int8_t> wq(N * M);
  std::vector<std::int32_t> x(N), a_ref(M), a_tq1(M);
  for (auto& v : wq) v = (std::int8_t)trit(rng);
  for (auto& v : x) v = act(rng);

  mmfree::CpuBackend cpu;
  mmfree::Tq1Backend tq;
  mmfree::Tq2Backend tq2b;
  cpu.matmul(0, x.data(), a_ref.data(), wq.data(), N, M);
  tq.matmul(0, x.data(), a_tq1.data(), wq.data(), N, M);
  int bad = 0;
  for (std::size_t o = 0; o < M; ++o) bad += (a_ref[o] != a_tq1[o]);
  check(bad == 0, "Tq1Backend::matmul == CpuBackend::matmul (exact)");
  // Second call must hit the cache and stay identical.
  std::fill(a_tq1.begin(), a_tq1.end(), 0);
  tq.matmul(0, x.data(), a_tq1.data(), wq.data(), N, M);
  for (std::size_t o = 0; o < M; ++o) bad += (a_ref[o] != a_tq1[o]);
  check(bad == 0, "cached second call identical");
  check(tq.packed_tensors() == 1, "weights packed once, not per call");
  std::vector<std::int32_t> a_tq2(M);
  tq2b.matmul(0, x.data(), a_tq2.data(), wq.data(), N, M);
  int bad_2 = 0;
  for (std::size_t o = 0; o < M; ++o) bad_2 += (a_ref[o] != a_tq2[o]);
  check(bad_2 == 0, "Tq2Backend::matmul == CpuBackend::matmul (exact)");
  std::printf("      Tq2Backend: %d/%zu columns differ, %zu bytes (%.4f bits/weight)\n", bad_2, M,
              tq2b.packed_bytes(), mmfree::Tq2Backend::format_bits());
  std::printf("      %zu/%zu columns differ; cache holds %zu tensor(s), %zu bytes\n",
              (std::size_t)bad, M, tq.packed_tensors(), tq.packed_bytes());

  // A shape we can't block must still be correct via the int8 fallback.
  const std::size_t Nodd = 1000;  // 1000 % 256 != 0
  std::vector<std::int8_t> wq2(Nodd * 8);
  std::vector<std::int32_t> x2(Nodd), b_ref(8), b_tq1(8);
  for (auto& v : wq2) v = (std::int8_t)trit(rng);
  for (auto& v : x2) v = act(rng);
  cpu.matmul(0, x2.data(), b_ref.data(), wq2.data(), Nodd, 8);
  tq.matmul(0, x2.data(), b_tq1.data(), wq2.data(), Nodd, 8);
  int bad2 = 0;
  for (std::size_t o = 0; o < 8; ++o) bad2 += (b_ref[o] != b_tq1[o]);
  check(bad2 == 0, "non-blockable N falls back to int8 exactly");
  std::printf("      N=%zu (not a multiple of 256) fallback: %s\n", Nodd,
              bad2 == 0 ? "exact" : "MISMATCH");
}

// ---- optional microbenchmark (--bench): int8 vs TQ1_0 on a full projection --------------
// The whole point of TQ1_0 here is weight TRAFFIC, so the benchmark must use a weight matrix
// far bigger than L2 (1 MiB on the K26) — a cache-resident matrix would measure only the
// extra unpack ALU work and conclude the opposite. Shapes below are real projections.
void bench() {
  struct Shape { const char* name; std::size_t N, M; };
  const Shape shapes[] = {
      {"370M i/f/g/o_proj", 1024, 1024},
      {"370M gate_up", 1024, 5632},
      {"370M lm_head", 1024, 32000},
      {"1.3B gate_up", 2048, 11264},
      {"2.7B down_proj", 6912, 2560},
  };
  std::printf("[bench] int8 vs TQ1_0, full projection (weights streamed once per call)\n");
  std::printf("      %-20s %10s %8s %9s %9s %7s %9s %7s\n", "shape", "weights", "int8MiB",
              "int8 ms", "tq1 ms", "vs int8", "2bit ms", "vs int8");
  std::mt19937 rng(3);
  std::uniform_int_distribution<int> trit(-1, 1);
  std::uniform_int_distribution<int> act(-32768, 32767);

  for (const Shape& s : shapes) {
    std::vector<std::int8_t> wq(s.N * s.M);
    for (auto& v : wq) v = (std::int8_t)trit(rng);
    std::vector<std::int32_t> x(s.N), acc(s.M);
    for (auto& v : x) v = act(rng);
    const std::size_t stride = mmfree::tq1::row_bytes(s.N);
    std::vector<std::uint8_t> p(stride * s.M);
    mmfree::parallel_rows(s.M, [&](std::size_t o) {
      mmfree::tq1::pack_row(p.data() + o * stride, wq.data() + o * s.N, s.N);
    });

    std::vector<std::uint8_t> p2(mmfree::tq2::row_bytes(s.N) * s.M);
    const std::size_t stride2 = mmfree::tq2::row_bytes(s.N);
    mmfree::parallel_rows(s.M, [&](std::size_t o) {
      mmfree::tq2::pack_row(p2.data() + o * stride2, wq.data() + o * s.N, s.N);
    });
    auto time_it = [&](int which) {   // 0 = int8, 1 = TQ1_0, 2 = 2-bit
      const int reps = 5;
      // One untimed pass so both arms start with the same (cold-ish) cache state.
      auto once = [&]() {
#pragma omp parallel for schedule(static)
        for (std::size_t o = 0; o < s.M; ++o)
          acc[o] = which == 2 ? mmfree::tq2::dot_i32(x.data(), p2.data() + o * stride2, s.N)
                   : which == 1 ? mmfree::tq1::dot_i32(x.data(), p.data() + o * stride, s.N)
                                : mmfree::simd::ternary_dot_i32(x.data(), wq.data() + o * s.N, s.N);
      };
      once();
      const auto t0 = std::chrono::steady_clock::now();
      for (int r = 0; r < reps; ++r) once();
      const auto t1 = std::chrono::steady_clock::now();
      return std::chrono::duration<double, std::milli>(t1 - t0).count() / reps;
    };
    const double ms_i8 = time_it(0);
    const double ms_tq = time_it(1);
    const double ms_t2 = time_it(2);
    std::printf("      %-20s %10zu %8.1f %9.2f %9.2f %7.2fx %9.2f %7.2fx\n", s.name, s.N * s.M,
                (double)wq.size() / 1048576.0, ms_i8, ms_tq, ms_i8 / ms_tq, ms_t2,
                ms_i8 / ms_t2);
  }
}

}  // namespace

int main(int argc, char** argv) {
  const bool do_bench = (argc > 1 && std::string(argv[1]) == "--bench");
  std::printf("== TQ1_0 CPU ternary weights (%s backend) ==\n", mmfree::simd::backend());
  std::printf("   block: %zu weights -> %zu bytes (%.4f bits/weight)\n\n", mmfree::tq1::kBlockElems,
              mmfree::tq1::kBlockBytes,
              8.0 * (double)mmfree::tq1::kBlockBytes / (double)mmfree::tq1::kBlockElems);
  test_exhaustive();
  std::printf("\n");
  test_shapes();
  std::printf("\n");
  test_random_many();
  std::printf("\n");
  test_backend();
  std::printf("\n%s\n", g_fail == 0 ? "ALL TQ1_0 CHECKS PASSED" : "TQ1_0 CHECKS FAILED");
  if (do_bench) {
    std::printf("\n");
    bench();
  }
  return g_fail == 0 ? 0 : 1;
}
