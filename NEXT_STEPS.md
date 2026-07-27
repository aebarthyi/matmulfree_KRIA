# Next steps — handoff as of 2026-07-26

Written at the end of the batching/benchmark session. Everything below is either measured,
built-and-unmeasured, or designed-and-unbuilt — the state is called out per item so nothing
reads as more finished than it is.

---

## 0. READ FIRST — nothing is committed

All of this session's work is in the working tree, not in git:

```
 M scripts/{build_all.sh,transfer.mk}
 M software/integration/fpga_runner/{Makefile,fpga_backend.cpp,fpga_backend.hpp,main_fpga.cpp}
 M software/integration/libmmfree/{mmfree_lib.c,mmfree_lib.h}
 M src/main/scala/control/CoreConfig.scala
 M src/test/scala/control/CoreSpec.scala
 ?? scripts/{bench_batch.sh,build_queue.sh,split_sweep_csv.py,sweep_all.sh,sweep_split_ab.sh,sweep_split_k.sh}
 ?? software/integration/tests/{Makefile,test_split_chunks.c}
 m matmulfreellmCPU          <- SUBMODULE also dirty
```

The submodule `matmulfreellmCPU` has its own uncommitted changes
(`cpp/include/mmfree/{model.hpp,ternary_backend.hpp}`, `cpp/src/kernels/bitlinear.cpp`,
`cpp/src/model/hgrn_model.cpp`) on branch `main` at `fab501d`. It needs its **own commit
first**, then a pointer bump in this repo — otherwise a fresh clone builds against the old
submodule and the new `matmul_batch_chunked` / `generate_serve(on_token)` calls vanish.

Also note `reports/` and `transfer/` are **gitignored**. So the benchmark CSVs
(`reports/sweeps/`) and every built bitstream (`transfer/presets/`) exist only on this build
server and on the board. If the machine is wiped, the bitstreams are hours of Vivado to
regenerate and the measurements are gone. Decide whether results deserve a versioned home.

---

## 1. Measurements queued (built, shipped to the board, never run)

The board needs a password for `sudo`, so these have to be run by hand from `~/transfer`.

### 1a. The dequant-hiding change — the one with an actual pending question
Per-chunk callback now runs dequant inside the engine's COMPUTE window (see
`memory: column-tile-splitting`, step 2). Built, unit-tested host+board, **never run on
hardware**.

```
cd ~/transfer && sudo ./sweep_split_k.sh
```

- **Baseline to beat: 27.32 tok/s** (2.7B packed, b=4, k=4) from the previous k-sweep. Same
  bitstream and model, so the delta isolates dequant-in-window.
- Run the **whole k range**, not just k=4: more hideable CPU work means more COMPUTE windows
  are worth having, so **the knee is expected to move above k=4**. The previous curve turned
  over at k=8 only because there wasn't enough work to fill the extra windows.
- `make gate` FIRST if the script doesn't (it does). A wrong column range here corrupts
  values silently rather than crashing — dequant applied twice, or not at all.

### 1b. Two bitstreams that closed but were never run
Both built 2026-07-26, in `transfer/presets/` and already on the board.

| preset | WNS | what it is |
|---|---|---|
| `k26_mmfree2_7b_a16_b6_dsp_s8_230` | +0.118 ns | B6 + DSP + banking @230. Throughput-max maxN=8192 build; runs 1.3B **and** 2.7B. |
| `k26_mmfree370m_a16_b6_rb` | +0.048 ns | Config-identical to `k26_mmfree370m_a16_b6` but rebuilt from a tree WITH the runtime-batch RTL. |

```
cd ~/transfer && sudo ./sweep_all.sh "k26_mmfree2_7b_a16_b6_dsp_s8_230" "k26_mmfree370m_a16_b6_rb:370m"
```

- **`_rb` needs `MODEL=370m`** (the `:370m` above). Its manifest asks for
  `ridger/MMfreeLM-370M`, which is not provisioned on the board; `models/370m/` is.
- `_rb` vs `k26_mmfree370m_a16_b6` is the **runtime-batch A/B**: on the old bitstream a b<B
  call still drains all 6 rows, so low-b rows are flat. If runtime batch works, b=1 on `_rb`
  should drop toward B1 cost. That has never been demonstrated on hardware.
- B6@230 matters because **the split gain grows with batch** (+0.5% at b=1 → +4.4% at b=4),
  so b=5/b=6 should extend the trend and compound with B6's ~38% throughput edge over B4.
- fclk: `deploy_kria.sh` sets fclk0 from the manifest, so 230 is handled. Don't bypass it.

---

## 2. Designed, not built

### 2a. swiglu chunk-pairing — the biggest remaining software win
swiglu is **1170 ms/run** at b=4 (11.1% of decode), the largest CPU vector op, and it is
still fully serialized with the engine.

Blocker: `gate_proj` emits `[B, 2I]` with gate in cols `[0,I)` and up in `[I,2I)`, and swiglu
pairs column `i` with `i+I` — so a contiguous column chunk **cannot** be swiglu'd alone.

Fix: give the split driver an explicit chunk **schedule** instead of deriving sequential
ranges, so the model can request gate-chunk `j` then up-chunk `j` adjacently and fire swiglu
on the pair. `mmfree_split_plan()` already returns an inspectable plan and the blob layout
supports arbitrary tile ranges, so this is a scheduling change, not a data one. Note
`down_proj`/`o_proj` outputs are column-local and would work with the existing sequential
callback today.

Budget check before building: the COMPUTE window at k=4 is ~2571 ms and readback already
consumes ~957 ms of it. Whatever 1a leaves unused is what swiglu can claim.

### 2b. STORE ‖ COMPUTE (RTL) — ~+5%
`m_axis` (STORE) is a **separate physical port** from `s_axis`, so these can overlap. The
only conflict is `outBram` (STORE reads while COMPUTE's drain writes) → needs double
buffering. **Resource-feasible**: packed B4 build is URAM 25% (16/64), LUT 43%, BRAM 40%;
doubling outBram lands ~50% URAM. DSP is the tight resource at 82% and this adds none.

Worth ~486 ms/run (store is 314 ms at k=0, 486 at k=4) ≈ +5% decode at the ~95 ms-per-1%
exchange rate the split established. It also retires the per-chunk store tax, which is half
of what caps chunking.

### 2c. Deeper instruction queue — only bundled with 2b
On its own this does **not** buy concurrency: `AxiInstructionHandler` runs
`idle→decoding→dispatching→awaitingDone→completed` and the Core FSM
(`sIdle/sLoad/sCompute/sStore`) executes one op to completion regardless of how many are
queued. It also misses most of the per-op cost, because **the CPU programs the DMA**
(`mmfree_seq_compute_issue` does 4 `mmfree_dma_mm2s_start()` AXI-Lite writes before pushing
the instruction). Removing that needs descriptor/scatter-gather DMA, not a queue.

Explicitly **not worth doing**: `LOAD ‖ COMPUTE`. `s_axis` carries both activations and
weights, distinguished only by FSM state — one physical port. Would need a second slave port
plus a double-buffered `actBram`, for a phase that is only 3.9% of engine time.

### 2d. Packer per-chunk padding (small, unblocks 370M/1.3B)
1.6-bit chunk boundaries need `t*N*olt` divisible by 5 and beat-aligned; since `olt=256` and
`256%5==1` that means **`N%5==0`**. 2.7B (N=2560) is fine, but 370M/1.3B `gate_proj`
(N=1024/2048) falls back to k=1 on the packed blob, and `down_proj` (N=6912) only allows
boundaries at multiples of 5 tiles. Having the packer emit chunks as independently-padded
entries would unblock splitting on the smaller models. Cost is ≤4 trits + ≤1 beat per chunk.

---

## 3. Open questions

- **370M b=5 anomaly.** decode is nearly flat vs b=4 (79.29 vs 78.83 tok/s) while token
  latency jumps 50.7 → 63.1 ms, then b=6 recovers to 86.10. Odd batch counts may hit a worse
  drain path, or that single run was noisy. Never explained — re-run b=5 a few times before
  theorising.
- **~217 ms of unexplained per-chunk cost** at k=4. The balance sheet closes overall, but
  after accounting store (+171 ms) and pack (+5 ms) there is a residual charged to compute.
  Hypothesis is weight-DMA ramp-up — k separate MM2S transfers instead of one long one, so k
  ramp-ups and possible engine stalls between chunks. **Unmeasured.** Instrumenting the DMA
  start/first-beat latency would confirm or kill it, and it decides whether 2b is worth more
  than the +5% estimated.

---

## 4. Where things stand (measured, for reference)

2.7B packed 1.6-bit @230 MHz, b=4, all gates PASS / all `streams_ok=1`:

| config | decode tok/s |
|---|---|
| 2-bit, no split (session start) | 24.47 |
| 1.6-bit packing | 26.17 |
| + column-tile split (k=4) | **27.32** |
| + dequant hidden | **? — item 1a** |

370M B6 @250 scales 35.5 → 86.1 tok/s over b=1..6. Chunk-count sweep (2.7B packed, b=4):
k=2 → +4.0%, **k=4 → +4.4% (peak)**, k=8 → +1.3%, k=16 → **−2.0%**; benefit saturates as
`(k−1)/k` while cost grows linearly in k.

Tooling built this session: `bench_batch.sh` (batch sweep → CSV), `split_sweep_csv.py`
(wide CSV → tidy summary/ops/phases sheets), `sweep_all.sh`, `sweep_split_ab.sh`,
`sweep_split_k.sh`, `build_queue.sh` (unattended Vivado queue),
`software/integration/tests/` (`make split`). See `memory: batch-sweep-csv-tooling`.
