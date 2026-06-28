# Decode pipelining / op-overlap plan (fpga_runner)

Goal: raise decode + serve throughput on the C++ runner by cutting the per-call CPU
overhead that sits ON the critical path between engine ops (pack, readback) and by
keeping the engine's command FIFO fed so it doesn't idle between projections.
Baseline (board 2026-06-26, b6, single-stream): decode 17.69 tok/s; serve B=6 34.5 agg.
bitlinear per-call (~239 µs): pack 27.9%, compute 30.2%, store 19.1%, load 13.7%, readback 9.1%.

## Status — verified on board 2026-06-26 (b4, single-stream decode)

Two free/low-risk wins landed and gate-verified (`--backend both`: 0 mismatch / 725 proj):

| step | decode tok/s | matmul µs/call | note |
|------|-------------:|---------------:|------|
| IRQ baseline (b4)        | 18.87 | — | starting point |
| + `MMFREE_POLL=1`        | 23.39 | — | Phase 0; +24%; strips ~22 µs IRQ off each of load/compute/store |
| + b-aware pack           | 25.29 | 190.4 | pack 42.97→20.88 µs/call (skip unused-row scatter); +8% |
| + SIMD quant/dequant     | 28.37 | 161.1 | NEON vcvtnq quant + dequant; CPU front gap 64→35 µs; +12% |
| + scratch hoist          | 29.33 | 153.9 | reuse y/yqb/accb (no per-call alloc); +3% |
| + i/f/g overlap pipeline | 30.60 |   —   | quant+pack(n+1)/readback(n-1) under COMPUTE(n); +4.3% |

Cumulative **single-stream 18.87 → 30.60 = 1.62×; serve B=4 32.46 → 43.76 = 1.35×; prefill
166 → 117 ms.** All bit-exact (gate 0 mismatch/725 proj; pipeline==serial token-equiv over
64 tok) and zero-HW-risk.

The pipeline (+4.3%) came in below the ~+10% projection: the i/f/g cluster is the *small*
projections (the 154 µs matmul avg was inflated by gate/down/lm_head), and with only k=3
back-to-back projections the fill/drain tails (prime proj-0 front+load, drain proj-2 readback)
dominate, while per-proj load+store (~32 µs) are unhideable without the (rejected) HW path.
~1.20× on the cluster is the k=3 ceiling. Option Y (readback-under-store + OUT double-buffer)
would only relocate readback, not beat fill/drain — not worth the offset-cache-sync. STOP HERE.

**Pipeline ROI flipped after the SIMD win.** Per i/f/g projection the CPU work
(pack 20.7 + RMSNorm/quant/narrow ~25 + readback 21.5 + dequant ~10 ≈ **77 µs**) is now
*below* the engine work (load+compute+store = **84 µs**). Before SIMD it was CPU-bound
(106>84, pipeline capped ~1.16×); now engine-bound → the cluster can reach the ~84 µs/proj
floor (~1.67× cluster, **~+9% decode → ~31 tok/s**). Still a meaty refactor for a
single-stream-only gain (no serve benefit). DECISION PENDING: bank 1.50× vs build the pipeline.

NOTE: b-aware pack helps **single-stream (b=1)** only; for **serve (b==B)** the scatter is
unchanged. The serve-side pack lever is OpenMP/SIMD on the scatter, which needs `-fopenmp`
added to the lib `CFLAGS` (absent today, Makefile line 42).

## Dead end: i/f/g shared-input fusion (do NOT pursue — verified closed)

An earlier draft proposed RMSNorm+quant of `hs` ONCE and reusing one LOAD across
i_proj/f_proj/g_proj (they all read `hs`). **This does not work and is settled.**

Each BitLinear carries its OWN inner RMSNorm weight, packed per-projection as
`<tag>.normw` (`pack_weights.py:92` = `sd[<infix>.norm.weight]`). The norm is applied
*before* the int16 quant (`bitlinear.cpp:51,58-62`), so i/f/g produce three DIFFERENT
quantized activations from the same `hs`. Empirically (layer 0, `model.mmfree`):

| pair    | max abs diff | allclose |
|---------|-------------|----------|
| i vs f  | 0.994       | no       |
| i vs g  | 0.379       | no       |
| f vs g  | 0.750       | no       |

So there is no shared LOAD payload. Folding the per-channel norm into the weights
(`W'[o,c] = W[o,c]*g[c]`) would make `W'` non-ternary, breaking the engine contract —
which is why `model.py` already uses the UNFUSED i/f/g form "for correctness." The only
thing genuinely shared across i/f/g is the `rms(hs)` reduction (one sumsq over in_dim),
a negligible slice. Drop this idea.

## Reality check on the ceiling (read first)

The engine is **strictly serial**: one actBram, one instruction stream, so
`LOAD → COMPUTE → STORE` for a projection cannot overlap *each other*, and the LOAD of
the next projection can't run until the current COMPUTE has consumed actBram. There is
no engine-side parallelism to exploit and (per above) no shared-input fusion.

What CAN overlap is **CPU work against engine work**. From the profile, the engine-side
portion (load+compute+store) is ~63% / ~150 µs of the 239 µs call; the CPU-side portion
(pack+readback) is ~37% / ~88 µs. Today that 88 µs sits ON the critical path. If pack and
readback are moved OFF it — pack the *next* projection while the engine computes the
current one, read back the *previous* output while the engine loads the next — steady-state
per-op wall time approaches the engine portion alone:

```
pack(66.7µs) fits under compute+store(117.6µs)  ✓
readback(21.7µs) fits under load+compute(104.7µs) ✓
```

Theoretical ceiling ~1.5×; honest target **~1.3–1.4×** after sync/queue overhead. This
applies to ALL 6 projections + lm_head (the whole serial chain), not just i/f/g — it does
not depend on shared input. **Phase 0 (`MMFREE_POLL`) shrinks the engine-side IRQ overhead,
which changes this math — measure Phase 0 first, then re-derive the target.**

## Verified enablers (checked 2026-06-26)

- **Issue/wait already split:** `mmfree_push_instr` + `mmfree_wait_done` are public
  (`mmfree_runtime.h:163,167`); `mmfree_load`/`mmfree_compute_off`/`mmfree_store`
  (`mmfree_runtime.c:513,529,546`) are push+wait wrappers. The overlap loop calls the
  raw push/wait directly so CPU work can run between the push and the wait.
- **Command FIFO exists** (control path: AXI-Stream cmd FIFO, 128-bit instrs). Pushing the
  next instruction before waiting on the current one's completion IRQ is the mechanism for
  keeping the engine fed across the inter-op gap. (Depth + deferred-wait semantics: verify
  with the micro-test below before relying on multi-instruction pre-push.)
- **Per-projection state is independent:** `Core.scala` FSM `sIdle→sLoad→sIdle→sCompute→
  sIdle→sStore` accepts each op independently and `sIdle` flush resets only `inQ` + the
  actBram row counter, so back-to-back projections (each with its own LOAD) are clean.

## Phase 0 — MMFREE_POLL=1 (zero code, do FIRST)

None of the board runs above set it. The IRQ path is poll()+read()+ack+re-arm per op
("tens of µs", `mmfree_runtime.c:287`), paid 3×/projection × ~138 proj/token. Memory
[[udmabuf_cached_mapping_speedup]] records MMFREE_POLL took the Python path 21→26 tok/s (+24%).
```
sudo MMFREE_POLL=1 MMFREE_BATCH=4 ./build/mmfree-cli-fpga $ARGS --backend fpga --bench --gen 128 --reps 5 --profile --ids ...
sudo MMFREE_POLL=1 MMFREE_BATCH=4 ./build/mmfree-cli-fpga $ARGS --serve 4 --bench --gen 128 --reps 5 --ids ...
```
Watch load/compute/store µs/call drop. The result re-bases Phase 1's target (POLL removes
IRQ latency from the engine-side critical path). (Trade-off: a core busy-spins per op — fine
for a dedicated decode box; revisit for shared hosts.)

## Phase 1 — software-pipeline pack/readback under the engine (the real code work)

Restructure `mmfree_bitlinear_batch` (and a new multi-projection driver) so the CPU
front/back-end of projection n overlaps the engine work of its neighbours. The transform is
local to the lib + the FpgaBackend; the model, bitlinear.cpp quant/dequant, and the CPU
backend are untouched (so `--backend both` stays the gate).

1. **Double-buffer the ACT and OUT udmabufs.** Two ACT regions (A0/A1) so the CPU can pack
   projection n+1's activation while LOAD/COMPUTE n reads A[n%2]; two OUT regions (O0/O1) so
   readback of O[(n-1)%2] can't race STORE n writing O[n%2]. ACT is ~N*portBytes, OUT is
   ~B*n_outputs*outLaneBytes (≤768 KB) — cheap CMA. Size via the same overlay region-doubling
   trick as the b4 wt fix, or carve two halves of one buffer if it already has room.
2. **New lib entry `mmfree_bitlinear_seq`** that drives a *sequence* of projections sharing
   one input row-set (i/f/g, or any serial run the caller hands it), pipelined:
   ```
   pack act[0] -> A0 ; sync_for_device(A0)
   push LOAD(A0)
   for n in 0..K-1:
     wait LOAD(A[n%2])
     push COMPUTE(proj[n])
     if n+1 < K: pack act[n+1] -> A[(n+1)%2]; sync_for_device   # overlaps COMPUTE n
     wait COMPUTE
     push STORE(O[n%2])
     if n>0: readback O[(n-1)%2] -> dequant acc[n-1]            # overlaps STORE n
     wait STORE; sync_for_cpu(O[n%2])
     if n+1 < K: push LOAD(A[(n+1)%2])
   readback O[(K-1)%2] -> dequant acc[K-1]
   ```
   (Exact interleave is a tuning detail; the invariant is: CPU pack/readback never sits on
   the critical path, and a LOAD is queued before its COMPUTE is needed.)
3. **FpgaBackend**: add a `matmul_seq` (or reuse the existing per-call path when K==1) that
   narrows int32→int16 for each projection and calls `mmfree_bitlinear_seq`. The model still
   issues `run_proj` per projection; the *batching of consecutive calls into one seq* can be
   done either (a) by a thin scheduler in main_fpga that recognizes the i/f/g run, or (b)
   simpler first cut: pipeline WITHIN a single `matmul_batch` is already serial per row — the
   cross-projection pipeline needs the model to hand ≥2 projections at once. Start with the
   i/f/g cluster (3 back-to-back, same `hs`) since `block()` already groups them and g_proj
   can be hoisted next to i/f (verified: `hs_` untouched between hgrn_model.cpp:71 and :109).
4. **No shared LOAD** — each of the K projections packs+LOADs its own activation. The win is
   purely hiding pack+readback and removing inter-op CPU gaps, NOT saving LOADs.

Per-cluster win: removes pack (K-1 of K) and readback (K-1 of K) from the critical path and
keeps the engine FIFO fed across the K ops. On the i/f/g cluster that's ~2 packs + ~2
readbacks hidden; extending the scheduler to the full serial chain hides more.

## Phase 2 — (optional) only if Phase 0+1 fall short

The small CPU nonlinearities (swishgate/swiglu/rmsnorm, ~18% of profile) sit between serial
projections; moving them off the critical path is low ROI. lm_head readback (B×V=B×32000) is
large; consider parallelizing its dequant+argmax across threads (serve already has B
independent rows). Measure before investing.

## Correctness gate

Extend **CompareBackend** (`fpga_runner/fpga_backend.cpp`) with the seq path: run CPU
(per-projection loop) and FPGA (pipelined seq) for all K and diff each — `--backend both`
must print `GATE PASS (CPU == FPGA)` before any timing. Then re-profile + serve. The
pipeline is a scheduling change only; the per-projection LOAD/COMPUTE/STORE and the
int-accumulate are unchanged, so the gate should stay exact.

## Risks / unknowns to settle at the start of execution

- **Command-FIFO depth + deferred-wait semantics** (THE gate now): write a micro-test that
  pushes LOAD then COMPUTE without waiting between them, does CPU work, then waits both —
  confirm the engine drains the FIFO correctly and `wait_done` matches completions in order.
  This replaces the old actBram-persistence test (irrelevant now: we don't reuse LOADs).
- **ACT/OUT double-buffering**: OUT ping-pong is required for readback/STORE overlap; ACT
  double-buffer for pack/LOAD overlap. Both cheap CMA; needs the overlay region sizes doubled
  (same dtbo-edit trick as the b4 wt fix) OR two halves of one buffer if room exists.
- **proj_ids point at distinct resident weight slices** — they already do (manifest).

## Execute order

0. ~~`MMFREE_POLL=1` measure~~ — DONE, +24%; make it the default for dedicated decode.
1. ~~b-aware pack~~ — DONE, +8%, gate-verified bit-identical (mmfree_lib.c fast+fallback paths).
1b. ~~SIMD quant/dequant~~ — DONE, +12%, gate-verified bit-exact (simd.hpp quant_q510 +
    dequant_scale; NEON vcvtnq round-to-even == nearbyintf; used in bitlinear.cpp).
2. ~~Command-FIFO deferred-wait micro-test~~ — RESOLVED by RTL read (AxiInstructionHandler.scala):
    NOT viable — 1-deep pending buffer (2nd concurrent INSTR write → SLVERR), single
    non-counting irqPending bit, AND LOAD/COMPUTE share the MM2S channels. So "keep the FIFO
    fed" is dead; the pipeline must use single-op ordering + CPU-work-in-the-wait-window.
3. ~~i/f/g overlap pipeline~~ — DONE + board-verified (+4.3% decode, +1.9% serve). Stage A:
    cluster refactor (matmul_seq virtual, bitlinear_cluster, block() g_proj hoist), serial-
    identical, token-equiv on CPU. Stage B: lib mmfree_seq_* split issue/wait primitives +
    FpgaBackend::matmul_seq (readback(n-1)+produce(n+1) under COMPUTE(n) wait; single ACT/OUT
    region; MMFREE_NO_PIPELINE=1 escape hatch). Gate PASS + pipeline==serial token-equiv.
    DONE — pipelining work complete; k=3 ceiling reached.
3. Double-buffer ACT + OUT (overlay sizing).
4. Implement `mmfree_bitlinear_seq` + FpgaBackend `matmul_seq`; wire the i/f/g cluster first.
5. `--backend both` gate, then profile + serve A/B vs baseline.
6. (serve only) `-fopenmp` on lib CFLAGS + parallelize the b>1 pack scatter.
7. Update [[fpga_runner_serve_baseline]] with the new numbers.
