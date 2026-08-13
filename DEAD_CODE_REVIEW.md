# Dead-code review — parent repo (`matmulfree_KRIA`), main @ 39ff77a

Survey only; **nothing has been deleted**. Tick what you want pruned and I'll do it.

Scope: the 131 tracked files on `main`. Every "dead" claim below was checked by
cross-reference, not eyeballed — the evidence command is given so you can re-verify.
Two useful facts for the risk calculus:

- `exploration` still carries `mmfree_bridge/`, `cpu_bench.c`, `bench_readback.c` and
  `vector/`, so pruning them from `main` does not lose them even ignoring history.
- The `vector` module and `EmitAxiInstructionHandler` are unused on `exploration` too,
  so deleting them breaks no ablation.

Totals: **~1,320 lines** in tier 1, **~530** in tier 2, **~350** in tier 3, plus
**5.9 MB / 31 files** of stale diagrams in tier 6.

---

## Tier 1 — dead, no consumer anywhere (safe)

- [ ] **`software/integration/mmfree_bridge/` — 7 files, 580 lines.** The Phase D Python
      bridge: ctypes → `libmmfree.so` + a monkeypatch of `FusedBitLinear.forward`. Nothing
      imports it but the docs, and its premise is gone — the PyTorch model it patches was
      removed from the submodule, and `fpga_runner` (C++) replaced it as the offload path.
      *Evidence:* `grep -rn mmfree_bridge --include=*.py --include=*.sh --include=Makefile`
      → only `README.md`, `software/integration/README.md`, and itself.
- [ ] **`software/Makefile`: the `lib` target + `libmmfree.so` (lines 52-63).** Built only
      for the bridge above; the board flow runs `make -C src/software` (default target =
      `bench`) and never `make lib`. **Keep `integration/libmmfree/mmfree_lib.c`** — the
      runner compiles it directly.
- [ ] **`src/main/scala/vector/` — `Vector.scala` (120) + `VectorSpec.scala` (559) = 679
      lines.** A vector-of-PEs dot-product accumulator that nothing instantiates:
      `Core`/`CoreTop`/`EmitCore` use `SystolicArray` instead, so this never reaches RTL or
      a bitstream. `AccumulatorStage` is used only inside `Vector.scala`. VectorSpec is the
      single largest block of dead test code (~1/6 of `make sim`).
      *Evidence:* `grep -rn "new Vector(" src/main` → nothing, on `main` or `exploration`.
- [ ] **`src/main/scala/control/EmitAxiInstructionHandler.scala` — 24 lines.** A standalone
      emitter `App` for just the AXI handler. Nothing invokes it (the flow is `EmitCore`);
      the only mention of its `runMain` line is its own doc comment.
- [ ] **`mmfree_lib_load_weights()` — `mmfree_lib.c:330-340` + decl `mmfree_lib.h:79`.**
      The in-memory-buffer variant of the weight loader. Every caller — the runner and even
      the dead bridge — uses `mmfree_lib_load_weights_file()` instead.
- [ ] **`mmfree_inst_compute()` — `software/include/mmfree_core.h:108`.** Superseded by the
      batched variant; the only other mention is a comment saying the batch one is
      "bit-identical to `mmfree_inst_compute`".
- [ ] **`vivado.jou` — 24 lines, tracked.** A Vivado journal file committed by accident
      (its `vivado_*.backup.jou` / `.log` siblings at the repo root are untracked).
- [ ] **`software/integration/requirements.txt` — 39 lines.** Rewrite rather than delete:
      every pin (`torch<2.4`, `transformers<4.45`, `accelerate`, `einops`, `sentencepiece`,
      `protobuf`, `numpy<2`) plus the triton-cpu build instructions exist for the dead
      bridge and the deleted `phase_e_*` harnesses. What survives on `main` is
      `mmfree_pack`, which needs numpy and lazily imports torch/safetensors only for
      `--checkpoint`.

## Tier 2 — obsolete experiments (dead in every build; keep only as reference)

- [ ] **`software/bench/bench_readback.c` — 129 lines.** A one-question microbenchmark
      ("does the readback loop scale with threads?"). It answered yes, threaded readback
      shipped and is default-on, and the file is in no Makefile (its header says to compile
      it by hand). ⚠️ `mmfree_lib.c:423` cites it as the evidence for that design decision —
      if it goes, that comment should quote the numbers instead of pointing at a dead file.
- [ ] **`software/bench/cpu_bench.c` — 397 lines — plus `software/Makefile`'s `cpu`
      target.** A hand-written A53 NEON baseline for the four 370M projection shapes.
      Nothing runs the binary, the board flow doesn't build it, and the CPU baseline is now
      `matmulfreellmCPU` (what `bench_host_cpu.sh` and the final sweeps actually measure).
      Hardcoded to 370M shapes; sole user of the `CPU_MODE` env knob.

## Tier 3 — orphaned manual tools (not dead, just unreferenced — your call)

None of these is called by any Makefile, script, or doc other than as prose. All still run.

- [ ] **`scripts/build_queue.sh` (74)** — serial multi-preset build driver. Its usage
      examples name `k26_a`/`k26_b`, presets that live only on `exploration` now. Low value
      on a 3-preset branch where 1.3B is cloned rather than built.
- [ ] **`scripts/model_numbers.py` (112)** — analytical tok/s ceiling. Its math assumes
      **2 bits/weight**, so every number it prints predates the shipped 1.6-bit packing.
      Fix the constant or drop it; as-is it will mislead.
- [ ] **`scripts/ternary_stats.py` (95)** — −1/0/+1 distribution of a blob's projections.
      Torch-free, still correct, genuinely useful for a paper figure. Recommend **keep**.
- [ ] **`scripts/ooc_synth.tcl` (73)** — out-of-context synth check for `CoreTop`. Still
      works; contains a dead fallback branch for "the pre-`CoreConfig` EmitCore layout".

## Tier 4 — stale docs (describe things that no longer exist)

- [ ] **`software/integration/README.md` (133).** Phase C documents `libmmfree.so`, Phase D
      the bridge, Phase E how to run **`phase_e_layer.py` — a file that has been deleted**.
      Roughly half the document is instructions that cannot work.
- [ ] **`README.md:61-64`.** The repo tree lists `mmfree_bridge/`, `libmmfree.so`, and
      `phase_e_*.py`. Also `README.md:48` presents `vector/` as a live building block.
- [ ] **`architecture_and_hybrid_analysis.md` (175).** Referenced by nothing. Its
      conclusion (bandwidth-bound, beefing up compute buys nothing) still holds, but the
      numbers are the 2026-07-21 4-port 370M era — "~77 MB of weights per token" is the
      2-bit figure, pre-B8 and pre-packing. Either date-stamp it as historical or refresh.
- [ ] **`CoreConfig.scala:64-66` — actively wrong.** The `packedWeights` comment says
      "config plumbing only so far — Core does not yet consume it … wiring is step 2".
      `Core.scala` has 5 references to it and all three shipped presets set it. This one is
      worth fixing whatever else you prune.
- [ ] **Retired preset names in live comments/defaults:** `k26_bench` / `k26_bench32/64` /
      `k26_mmfree370m` in `software/Makefile:20`, `mmfree_runtime.c:32`, `bench.c:31,79,97`,
      and `ActMem.scala:22`. Cosmetic, but they name presets that exist only on
      `exploration`.

## Tier 5 — unexercised config paths (delete only if you accept re-adding later)

Checked against `exploration`, not assumed:

- [ ] **`actMaxFanout`** — 5 refs in `Core.scala` + a manifest key, but **no preset and no
      test sets it on either branch**. `colBankSize=4` is the fanout lever that actually
      shipped. Truly unexercised today; recoverable from history if a future ablation wants it.
- [ ] **`accHeadroomBits` ≠ -1 branch** — every preset and test uses the auto path.
- [ ] **13 `MMFREE_*` keys emitted into `preset.env` with no consumer** (`MMFREE_NLANES`,
      `MMFREE_PORT_BYTES`, `MMFREE_OUT_LANES_PER_TILE`, `MMFREE_OUT_BEAT_LANES`,
      `MMFREE_DSP_ACCUM`, `MMFREE_COL_BANK_SIZE`, `MMFREE_ACT_MAX_FANOUT`,
      `MMFREE_ACT_CASCADE_HEIGHT`, `MMFREE_OUT_CASCADE_HEIGHT`, `MMFREE_OUT_BANK_DEPTH`,
      `MMFREE_NUM_COL_TILES_MAX`, `MMFREE_OUT_LANE_BYTES`, `MMFREE_OUT_LANE_WIDTH`).
      Harmless — the Python side reads `preset.json` and derives geometry itself
      (`geometry.py:69-78`). Arguably documentation. Prune only if you want the manifest to
      mean "consumed".
- [ ] **`CoreConfig.candidates`** — an empty `Map` kept as an extension point, with
      `presets = deployable ++ candidates` and a `CoreConfigSpec` assertion that it stays
      empty. Vestigial by design; costs 3 lines. Recommend **keep**.

## Tier 6 — stale generated artifacts (biggest disk win)

- [ ] **`diagrams/` — 31 files, 5.9 MB.** Referenced by no document in the repo (only its
      own README). Last touched 2026-07-09; `Core.scala` changed 2026-08-09, so they predate
      the banked output memory and the column banking. Also doubly redundant: `d2/` and
      `graphviz/` render **the same four diagrams**, and each is committed as **both `.png`
      and `.svg`** alongside its source. If they're worth keeping, keep one renderer and one
      format and regenerate; if not, they're 5.9 MB of wrong pictures.

## Not tracked, but local cruft (rm, no commit needed)

`runtime/` (2.6 MB — the old PyTorch `mmfreelm` + an empty `triton-cpu`, gitignored, and
the thing the dead `requirements.txt` tells you to build), the root `vivado_*.backup.log` /
`.backup.jou` files, `.Xil/`, `out/`, `build/`.

---

## Checked and cleared — NOT dead (don't re-litigate)

- **`board.conf.example` (root) vs `scripts/board.conf.example`** — looks like a duplicate,
  isn't. The root one is the build server's deploy target (`BOARD`/`DEST`); the `scripts/`
  one is the board device map that ships in the bundle. Different content, both live.
- **`software/bench/bench.c` + `mmfree_runtime.c`** — the board's `make bench` runs them
  (`transfer.mk:46,181`).
- **`mmfree_pack/`** — all five modules imported; `make pack` depends on it.
- **All 25 `CoreConfig` fields are consumed**, including `modelName`/`shapesHint`/
  `residentWtBytes`/`implDirectives` (via `presetEnv` and `udmabufWtResidentBytes`).
- **`outBankDepth` and `actRamStyle`** — I nearly listed these as unexercised; `CoreSpec.scala:733`
  forces a small `outBankDepth` to exercise the banked read mux, and both `CoreSpec` and
  `CoreConfigSpec` cover `actRamStyle`.
- **All three `software/include/*.h`** headers are included by live code.
- **`ReplicatedPipeSkid`, `WeightUnpackGearbox`, `TernaryPack`, `ActMem`, `OutMemUltra`** —
  all instantiated by `Core.scala` and tested.
