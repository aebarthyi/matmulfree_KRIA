# Decode pipelining / op-fusion plan (fpga_runner)

Goal: raise decode + serve throughput on the C++ runner by cutting the per-call CPU
overhead that does NOT amortize across the batch (pack, load, readback, IRQ round-trips).
Baseline (board 2026-06-26, b6, single-stream): decode 17.69 tok/s; serve B=6 34.5 agg.
bitlinear per-call (~239 µs): pack 27.9%, compute 30.2%, store 19.1%, load 13.7%, readback 9.1%.

## Reality check on the ceiling (read first)

The earlier "~1.7× by overlapping pack with the engine" assumed back-to-back **independent**
projections. The dependency graph (`hgrn_model.cpp` block(), lines 53-145) says otherwise —
the block is mostly a serial chain `engine → CPU nonlinearity → engine`:

```
rmsnorm(hs)
  i_proj(hs)  ┐
  f_proj(hs)  ├─ ALL read hs  → INDEPENDENT CLUSTER (the only parallelism in the block)
  g_proj(hs)  ┘
  gate/sigmoid(f), swiglu(i,f), hgrn scan        [CPU; needs i_,f_]
  swishgate(g_, recur) → oin_                    [CPU; needs g_ + scan]
  o_proj(oin_)                                   [serial: input depends on g_ readback]
  resid += o; rmsnorm → hs2                       [CPU]
  gate_proj(hs2)                                  [serial: needs hs2]
  swiglu → oin2                                   [CPU]
  down_proj(oin2)                                 [serial: needs oin2]
lm_head(...)  (once/step, standalone, huge: 1024×32000)
```

So only **i/f/g** (3 of 6 projections/block) can be fused/overlapped. o/gate/down/lm_head are
serially data-dependent — their inputs come from the previous op's readback + a CPU nonlinearity,
so the engine genuinely idles between them and there is nothing to pre-pack. Honest target:
**Phase 0 ~15-25% (free), Phase 1 ~10-15%, stacking to roughly ~1.3× combined.** Not 1.7×.

## Verified enablers (checked 2026-06-26)

- **actBram persists across COMPUTEs.** `Core.scala` FSM `sIdle→sLoad→sIdle→sCompute→sIdle→sStore`
  accepts each op independently; `sIdle` flush clears only `inQ` + the actBram *row counter*
  (resets read ptr to row 0 each COMPUTE — exactly what we want), NOT the `SyncReadMem` contents.
  ⇒ pack+LOAD once, then COMPUTE(w_i)/STORE, COMPUTE(w_f)/STORE, COMPUTE(w_g)/STORE.
- **Issue/wait already split:** `mmfree_push_instr` + `mmfree_wait_done` are public
  (`mmfree_runtime.h`); `mmfree_compute_off`/`mmfree_store` = push+wait wrappers.

## Phase 0 — MMFREE_POLL=1 (zero code, do FIRST)

None of the board runs above set it. The IRQ path is poll()+read()+ack+re-arm per op
("tens of µs", `mmfree_runtime.c:287`), paid 3×/projection × ~138 proj/token. Memory
[[udmabuf_cached_mapping_speedup]] records MMFREE_POLL took the Python path 21→26 tok/s (+24%).
```
sudo MMFREE_POLL=1 MMFREE_BATCH=4 ./build/mmfree-cli-fpga $ARGS --backend fpga --bench --gen 128 --reps 5 --profile --ids ...
sudo MMFREE_POLL=1 MMFREE_BATCH=4 ./build/mmfree-cli-fpga $ARGS --serve 4 --bench --gen 128 --reps 5 --ids ...
```
Watch load/compute/store µs/call drop. If big, it reframes Phase 1's value. (Trade-off: a core
busy-spins per op — fine for a dedicated decode box; revisit for shared hosts.)

## Phase 1 — i/f/g shared-input fusion (the real code work)

i_proj, f_proj, g_proj are uniform: same input `hs`, same in_dim=out_dim=H. Today the runner
RMSNorm-quants `hs`, packs, and LOADs it **three times**. Fuse to once:

1. **TernaryBackend** (`matmulfreellmCPU/cpp/include/mmfree/ternary_backend.hpp`): add
   ```cpp
   // k projections sharing ONE input x (own weights wq[j], own output acc[j], uniform dims).
   virtual void matmul_shared_input(const int* proj_ids, int k, const int32_t* x,
                                    int32_t* const* accs, const int8_t* const* wqs,
                                    std::size_t in_dim, std::size_t out_dim, std::size_t b);
   ```
   Default impl loops `matmul_batch` over j ⇒ CpuBackend unchanged, stays bit-exact.
2. **bitlinear.cpp**: add a fused entry `run_proj_shared(tags[k], outs[k], x, rows)` that does
   RMSNorm+quant of x ONCE → yqb, calls `backend->matmul_shared_input(...)`, then dequants the
   k accumulators. (Refactor the existing quant/dequant out of the per-row loop so both paths
   share it.) Integer accumulate is backend-independent ⇒ dequant stays bit-identical.
3. **hgrn_model.cpp block()**: replace the three `run_proj(i/f/g)` calls (lines 70,71,109) with
   one `run_proj_shared({i_proj,f_proj,g_proj}, {i_,f_,g_}, hs_, T)`. NOTE g_proj is currently
   issued later (line 109) but only *consumed* at swishgate (line 111) — safe to move its
   issue up next to i/f, since it reads the same `hs_` and nothing between mutates `hs_`.
4. **FpgaBackend::matmul_shared_input** (`fpga_runner/fpga_backend.cpp`): →
   new lib call `mmfree_bitlinear_shared(h, proj_ids[], k, x, accs[], b)`.
5. **mmfree_lib.c `mmfree_bitlinear_shared`**: pack x ONCE (the de-volatiled fast path),
   `sync_for_device`, push LOAD, wait. Then the pipelined drain loop over j=0..k-1:
   ```
   for j in 0..k:
     push COMPUTE(proj_ids[j].byte_offset, N, M); wait COMPUTE
     push STORE(B*n_outputs)                       // engine draining j
     if j>0: readback(acc[j-1])                    // CPU overlaps STORE(j) / COMPUTE(j+1)
     wait STORE; sync_for_cpu
   readback(acc[k-1])
   ```
   (Overlap detail: readback(j-1) reads the OUT udmabuf that STORE(j) overwrites. COMPUTE(j)
   (72 µs) ≫ readback (22 µs), so issuing readback(j-1) right after STORE(j-1) completes and
   before STORE(j) is safe by timing. For robustness, double-buffer OUT — see Risks.)

Per-block win: saves 2 packs + 2 loads + 2 RMSNorm-quant passes for the cluster, and hides 2
readbacks under COMPUTE. ~2 of 6 projections' CPU front-end removed per block.

## Phase 2 — (optional) only if Phase 0+1 fall short

The serial chain (o/gate/down) can't pre-pack (input not ready). The remaining lever there is
shrinking the inter-op gap: fewer IRQ round-trips (Phase 0 covers most), or moving the small CPU
nonlinearities (swishgate/swiglu/rmsnorm, ~18% of profile) off the critical path — low ROI.
lm_head readback (B×V=B×32000) is large; consider parallelizing its dequant+argmax across
threads (serve already has B independent rows). Measure before investing.

## Correctness gate

Extend **CompareBackend** (`fpga_runner/fpga_backend.cpp`) with a `matmul_shared_input` override
that runs CPU (loop) and FPGA (fused) for all k and diffs each — `--backend both` must print
`GATE PASS (CPU == FPGA)` before any timing. Then re-profile + serve.

## Risks / unknowns to settle at the start of execution

- **actBram persistence on real silicon** (not just RTL intent): write a 2-COMPUTE micro-test
  (LOAD once, COMPUTE w_a → STORE, COMPUTE w_b → STORE, no second LOAD) and check both results
  vs a CPU ref BEFORE building the full path. This is the one true unknown.
- **OUT single-buffering**: the timing argument (COMPUTE ≫ readback) makes single-buffer safe,
  but the robust move is a ping-pong OUT (2 regions). Cheap CMA (out is 512 KB-768 KB);
  needs the overlay OUT_SZ doubled (same dtbo-edit trick as the b4 wt fix) OR just allocate 2×
  inside one buffer if it already has room.
- **proj_ids must point at distinct resident weight slices** — they already do (manifest).

## Execute order

0. MMFREE_POLL=1 measure (5 min).
1. actBram-persistence micro-test (gate the whole plan).
2. Implement Phase 1 top-down: backend iface → lib `mmfree_bitlinear_shared` → bitlinear.cpp
   fused run_proj → block() rewire → CompareBackend override.
3. `--backend both` gate, then profile + serve A/B vs baseline.
4. Update [[fpga_runner_serve_baseline]] with the new numbers.
