# transfer/Makefile — board-side task runner for the KRIA.
#
# This file is the SOURCE for the bundle's Makefile: scripts/build_all.sh stages it
# as transfer/Makefile. The board needs NOTHING from the repo — only the scp'd
# transfer/ dir, which is the working root for every verb here. All paths are
# rooted at this directory (src/, models/, presets/, udmabuf/).
#
#   cd ~/transfer
#   make build                                   # native-compile bench + fpga_runner
#   sudo make deploy-hw PRESET=<preset>          # load bitstream overlay + u-dma-buf driver
#   make gate  PRESET=<preset>                   # fpga_runner exact-match gate (CPU==FPGA)
#   make run   PRESET=<preset>                   # fpga_runner FPGA decode (--bench --profile)
#   make gen   PRESET=<preset> PROMPT="..."      # interactive generate
#   make bench PRESET=<preset> [BATCH=N]         # native sweep benchmark
#
# gate/run/gen pick the model the preset was built for automatically (manifest
# MMFREE_MODEL). MODEL=<hf-id> overrides it to run a different checkpoint on the same
# bitstream. Arg-heavy verbs take PRESET= / MODEL= / BATCH= / ARGS= variables (NOT
# positional goals — make mangles `--flags`). ARGS is passed through verbatim.
#
# Board prerequisites: gcc, g++ (C++17), make, cmake, and kernel headers
# (linux-headers-$(uname -r), for the u-dma-buf driver). sudo is needed for the
# verbs that open /dev/mem (deploy-hw, bench, gate, run, gen).

# Board-local device map. board.conf is KEY=VAL (make-includable AND bash-sourceable);
# these are the defaults (standard KRIA address map) when it is absent. Copy
# board.conf.example to board.conf and edit only if your addresses/nodes differ.
-include board.conf

PRESET     ?= k26_mmfree1_3b_a16_b4
CORE_PHYS  ?= 0xA0010000
DMA_PHYS   ?= 0xA0000000
UIO_DEV    ?= /dev/uio4
ACT_UDMA   ?= /dev/udmabuf-act
WT_UDMA    ?= /dev/udmabuf-wt
OUT_UDMA   ?= /dev/udmabuf-out

# The deployed bitstream's geometry. One bundle carries every preset under presets/;
# PRESET selects which one. The manifest is the single source of truth (emitted on the
# build server by control.EmitCore, shipped here by build_all.sh).
MANIFEST     := presets/$(PRESET)/preset.env
MANIFEST_ABS := $(abspath $(MANIFEST))

# Native-compiled binaries (built here on the board from the staged sources under src/).
# The source nesting mirrors the repo, so the component sub-Makefiles compile unchanged.
BENCH_BIN    := src/software/build/bench
RUNNER_DIR   := src/software/integration/fpga_runner
RUNNER_BIN   := $(RUNNER_DIR)/build/mmfree-cli-fpga

# Which provisioned model to run on the deployed bitstream. Defaults to the HF
# checkpoint the preset was built for (manifest MMFREE_MODEL), so `make gate PRESET=<p>`
# needs nothing else. MODEL=<hf-id> overrides it to re-run a DIFFERENT checkpoint on the
# SAME bitstream (the maxN=8192 a16 engine spans every checkpoint up to 2.7B), so one
# deployed overlay covers all sizes. Model artifacts live under their HF id:
#   models/<hf-id>/{model.mmfree,tokenizer.mmtok,packed/}
MANIFEST_MODEL   := $(shell grep -hE '^MMFREE_MODEL=' $(MANIFEST) 2>/dev/null | cut -d= -f2)
MODEL            ?= $(MANIFEST_MODEL)
MODEL_DIR        ?= models/$(MODEL)
# Which packed blob a preset needs is decided by the BITSTREAM, so derive it from the manifest
# instead of defaulting to the 2-bit dir: packedWeights is an RTL parameter (the unpack gearbox
# only exists when it is set) and the runner hard-errors on a blob/bitstream format mismatch.
# Before this, a packed preset defaulted to $(MODEL_DIR)/packed and failed with a bare
# "manifest: cannot open .../packed/weights.manifest.tsv" — or, worse, found a 2-bit blob there
# and only then reported the mismatch. Every sweep script had to pass --packed-dir by hand and
# a forgotten one looked like a gate bug. An explicit PACKED_DIR= still overrides.
MANIFEST_PACKED  := $(shell grep -hE '^MMFREE_PACKED_WEIGHTS=' $(MANIFEST) 2>/dev/null | cut -d= -f2)
PACKED_DIR       ?= $(MODEL_DIR)/$(if $(filter 1,$(MANIFEST_PACKED)),packed-1p6,packed)
BLOB             ?= $(MODEL_DIR)/model.mmfree

POS_ARGS = $(CORE_PHYS) $(DMA_PHYS) $(UIO_DEV) $(ACT_UDMA) $(WT_UDMA) $(OUT_UDMA)

# ─── power monitoring (xlnx_platformstats) ───────────────────────────────────
# bench/run sample board rail power for EXACTLY the workload window via
# power_bench.sh (sampler started right before, killed right after), writing
# power/<geometry>_<mode>.{log,samples}. On by default; POWER=0 disables it, and
# it degrades to a plain run if xlnx_platformstats is absent. sudo strips the
# environment, so POWER_* overrides are handed through `sudo env`. Tunables:
# POWER_INTERVAL (s between samples), POWER_ARGS (xlnx_platformstats flags), POWER_DIR.
POWER      ?= 1
PWR        := ./power_bench.sh
BATCH_TAG  := $(if $(strip $(BATCH)),$(BATCH),-)
PWR_ENV    = $(if $(POWER_INTERVAL),POWER_INTERVAL=$(POWER_INTERVAL),) \
             $(if $(POWER_ARGS),POWER_ARGS='$(POWER_ARGS)',) \
             $(if $(POWER_DIR),POWER_DIR=$(POWER_DIR),)
# $(call runwrap,<mode>) → the privileged launcher for a workload. With power on it
# is `sudo env <POWER_*> power_bench.sh <manifest> <mode> <batch>`; with POWER=0 it
# is plain `sudo`. Either way the workload (env $(GEOM_ENV) BINARY ARGS) follows
# verbatim, so its stdout is untouched (`make bench | tee x.csv` still works).
runwrap = $(if $(filter 0,$(POWER)),sudo,sudo env $(PWR_ENV) $(PWR) $(MANIFEST_ABS) $(1) $(BATCH_TAG))

# Bench sweep slug (370m/1.3b/2.7b) for the current MODEL, derived from its HF id the
# same way the build server names it (basename, strip MMfreeLM-, lowercase). Used to
# keep the bench sweep + the runtime cross-check label aligned with the model in play;
# for the default (MODEL == the preset's own checkpoint) it equals the manifest's
# MMFREE_SHAPES, so this is a no-op unless MODEL overrides to a different checkpoint.
MODEL_SHAPES := $(shell printf '%s' '$(notdir $(MODEL))' | sed -E 's/^MMfreeLM-//I' | tr '[:upper:]' '[:lower:]')

# Geometry env for a board run: every MMFREE_*/NUM_DMA line from the manifest, plus
# the manifest path (for the runtime cross-check) and optional BATCH/shapes overrides.
# sudo strips the environment, so we hand it through `sudo env`. A trailing assignment
# wins under `env`: MODEL=<hf-id> re-points the sweep slug, and an explicit
# SHAPES=370m|1.3b|2.7b|pow2 wins outright (bench-only override).
# ENV="K=V K=V" is appended LAST so it wins under `env` — the only way to get a tuning
# variable to the workload, since sudo strips the caller's environment. e.g.
#   make run PRESET=... ENV="MMFREE_SPLIT_CHUNKS=0"     # disable column-tile splitting
GEOM_ENV = $$(grep -E '^(MMFREE_|NUM_DMA|MM2S_WIDTH|S2MM_WIDTH)' $(MANIFEST) | tr '\n' ' ') \
           MMFREE_MANIFEST=$(MANIFEST_ABS) \
           $(if $(BATCH),MMFREE_BATCH=$(BATCH),) \
           $(if $(strip $(MODEL_SHAPES)),MMFREE_SHAPES=$(MODEL_SHAPES),) \
           $(if $(SHAPES),MMFREE_SHAPES=$(SHAPES),) \
           $(if $(ENV),$(ENV),)

define need_manifest
	@[ -f "$(MANIFEST)" ] || { echo "ERROR: no manifest at $(MANIFEST) — '$(PRESET)' is not in this bundle. Built presets: $$(ls presets/ 2>/dev/null | tr '\n' ' ')." >&2; exit 1; }
endef

# RUNNER_ARTIFACTS placed before $(ARGS) so a --blob/--packed-dir in ARGS still wins
# (the runner takes the last occurrence). tokenizer.mmtok is auto-found beside the blob.
RUNNER_ARTIFACTS = --blob $(abspath $(BLOB)) --packed-dir $(abspath $(PACKED_DIR))
define need_blob
	@[ -n "$(strip $(MODEL))" ] || { echo "ERROR: no model for '$(PRESET)' — its manifest has no MMFREE_MODEL. Pass MODEL=<hf-id> to pick one." >&2; exit 1; }
	@[ -f "$(BLOB)" ] || { echo "ERROR: blob '$(BLOB)' not found — the '$(MODEL)' model isn't in this bundle. Build it on the build server (make model PRESET=$(PRESET)) and re-deploy, or pass BLOB=<f>." >&2; exit 1; }
endef

.PHONY: help build bench-bin runner-bin deploy-hw bench gate run gen sweep

help:
	@echo "matmulfree_KRIA board runner (PRESET=$(PRESET))"
	@echo
	@echo "  make build                                    native-compile bench + fpga_runner"
	@echo "  sudo make deploy-hw PRESET=<preset>           load bitstream overlay + u-dma-buf driver"
	@echo "  make bench  PRESET=<p> [BATCH=N] [ARGS=...]   native sweep benchmark (+ power)"
	@echo "  make gate   PRESET=<p> [MODEL=<hf-id>]        fpga_runner exact-match gate (CPU==FPGA)"
	@echo "  make run    PRESET=<p> [MODEL=<hf-id>]        fpga_runner FPGA decode (timed/profiled, + power)"
	@echo "    (bench/run sample rail power via xlnx_platformstats -> power/<geom>_<mode>.{log,samples};"
	@echo "     POWER=0 disables, POWER_INTERVAL / POWER_ARGS / POWER_DIR tune it)"
	@echo "  make gen    PRESET=<p> [MODEL=<hf-id>] PROMPT=\"...\" [GEN=n]   interactive generate"
	@echo "  make sweep  PRESET=<p> [MODEL=<hf-id>] [GEN=n] [REPS=n]      batch sweep b=1..batchSize -> CSV"
	@echo "    (one row per batch: ttft / prefill / token latency / tok-s + op & engine breakdowns)"
	@echo "    BACKEND=cpu  same ladder, engine out of the loop = the CPU baseline (-> _cpu CSV)"
	@echo "    BACKEND=cpu-tq1  CPU arm with 1.6-bit TQ1_0 weights (bit-identical, ~0.6-0.8x on A53)"
	@echo
	@echo "  This preset's model: $(if $(strip $(MODEL)),$(MODEL),(none — bench-only))"
	@echo
	@echo "Bundled presets (presets/):"
	@ls -d presets/*/ 2>/dev/null | sed 's,presets/,  ,;s,/,,' || echo "  (none)"
	@echo "Bundled models (models/):"
	@ls -d models/*/*/ 2>/dev/null | sed 's,models/,  ,;s,/$$,,' || echo "  (none)"

# ─── native compile (delegates to the staged component sub-Makefiles) ────────
# Always delegate so a changed source actually recompiles; the sub-makes do their own
# up-to-date checks, so this is a no-op when nothing changed.
bench-bin:
	$(MAKE) -C src/software
runner-bin:
	$(MAKE) -C $(RUNNER_DIR)
build: bench-bin runner-bin
	@echo "# Built $(BENCH_BIN) and $(RUNNER_BIN)"

# ─── deploy the bitstream overlay + drivers ──────────────────────────────────
# Installs presets/<preset>/{bit.bin,dtbo,shell.json}, builds+loads u-dma-buf from the
# shared udmabuf/ source, loads the overlay, and verifies udmabuf sizes vs the manifest.
deploy-hw:
	$(need_manifest)
	./deploy_kria.sh $(PRESET)

# ─── native bench ────────────────────────────────────────────────────────────
bench: bench-bin
	$(need_manifest)
	$(call runwrap,bench) env $(GEOM_ENV) $(abspath $(BENCH_BIN)) $(POS_ARGS) $(ARGS)

# ─── fpga_runner — exact-match gate (CPU vs FPGA) ────────────────────────────
gate: runner-bin
	$(need_manifest)
	$(need_blob)
	sudo env $(GEOM_ENV) $(abspath $(RUNNER_BIN)) $(POS_ARGS) $(RUNNER_ARTIFACTS) --backend both $(ARGS)

# ─── fpga_runner — FPGA decode ───────────────────────────────────────────────
run: runner-bin
	$(need_manifest)
	$(need_blob)
	$(call runwrap,run) env $(GEOM_ENV) $(abspath $(RUNNER_BIN)) $(POS_ARGS) $(RUNNER_ARTIFACTS) --backend fpga --bench --profile $(ARGS)

# ─── batch sweep → CSV ───────────────────────────────────────────────────────
# One `make run` per batch size b=1..MMFREE_BATCH, collected into a single CSV (one row
# per b): ttft, prefill latency, per-step token latency, aggregate/per-stream tok/s, plus
# the op and engine-phase breakdowns as columns. The overlay for PRESET must ALREADY be
# loaded (sudo make deploy-hw PRESET=… first) — the sweep does not touch the hardware.
# BATCHES="1 2 4" sweeps an explicit list; GEN/REPS/WARMUP/OUT tune the runs.
#   make sweep PRESET=k26_mmfree370m_a16_b6 GEN=64 REPS=3
# BACKEND=cpu runs the same ladder with the engine out of the loop — the CPU baseline for
# the speedup number. No overlay or packed blob needed, and it writes a separate _cpu CSV:
#   make sweep PRESET=k26_mmfree1_3b_a16_b6_dsp_s8_200 BACKEND=cpu
SWEEP_VARS = $(if $(MODEL),MODEL=$(MODEL),) $(if $(GEN),GEN=$(GEN),) \
             $(if $(REPS),REPS=$(REPS),) $(if $(WARMUP),WARMUP=$(WARMUP),) \
             $(if $(BATCHES),BATCHES='$(BATCHES)',) $(if $(OUT),OUT=$(OUT),) \
             $(if $(POWER),POWER=$(POWER),) $(if $(EXTRA),EXTRA='$(EXTRA)',) \
             $(if $(TAG),TAG=$(TAG),) $(if $(ENV),ENV='$(ENV)',) \
             $(if $(BACKEND),BACKEND=$(BACKEND),)
sweep: runner-bin
	$(need_manifest)
	$(need_blob)
	./bench_batch.sh PRESET=$(PRESET) $(SWEEP_VARS)

# ─── fpga_runner — interactive generation (live streamed text) ───────────────
# Single-stream decode of PROMPT=, printing each token as the engine emits it (decoded
# via tokenizer.mmtok beside the blob). Stops at the model's EOS. GEN=<n> caps tokens.
# TEMP>0 turns on sampling (TOPK/SEED tune it); TEMP=0 (default) is greedy. e.g.
#   make gen PRESET=k26_mmfree1_3b_a16_b4 PROMPT="The capital of France is" GEN=40
#   make gen PRESET=... MODEL=ridger/MMfreeLM-2.7B PROMPT="Once upon a time" TEMP=0.8 TOPK=40 SEED=1
GEN    ?= 64
PROMPT ?= Once upon a time
TEMP   ?= 0
TOPK   ?= 0
SEED   ?= 0
gen: runner-bin
	$(need_manifest)
	$(need_blob)
	sudo env $(GEOM_ENV) $(abspath $(RUNNER_BIN)) $(POS_ARGS) $(RUNNER_ARTIFACTS) \
	    --backend fpga --prompt "$(PROMPT)" --gen $(GEN) \
	    --temp $(TEMP) --top-k $(TOPK) --seed $(SEED) $(ARGS)
