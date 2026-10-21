# transfer/Makefile — board-side task runner for the KRIA (source: scripts/transfer.mk, staged
# by build_all.sh). The board needs only the scp'd transfer/ dir; all paths are rooted here.
#
#   cd ~/transfer
#   make build                                   # native-compile bench + fpga_runner
#   sudo make deploy-hw PRESET=<preset>          # load bitstream overlay + u-dma-buf driver
#   make gate  PRESET=<preset>                   # fpga_runner exact-match gate (CPU==FPGA)
#   make run   PRESET=<preset>                   # fpga_runner FPGA decode (--bench --profile)
#   make gen   PRESET=<preset> PROMPT="..."      # interactive generate
#   make bench PRESET=<preset> [BATCH=N]         # native sweep benchmark
#
# gate/run/gen use the preset's own model (manifest MMFREE_MODEL); MODEL=<hf-id> overrides it.
# Arg-heavy verbs take PRESET= / MODEL= / BATCH= / ARGS= variables, not positional goals (make
# mangles `--flags`); ARGS is passed through verbatim.
# Board prerequisites: gcc, g++ (C++17), make, cmake, linux-headers-$(uname -r) (for u-dma-buf).

# Board-local device map: board.conf (KEY=VAL; copy board.conf.example) overrides these defaults.
-include board.conf

PRESET     ?= mmfree_2_7b
CORE_PHYS  ?= 0xA0010000
DMA_PHYS   ?= 0xA0000000
UIO_DEV    ?= /dev/uio4
ACT_UDMA   ?= /dev/udmabuf-act
WT_UDMA    ?= /dev/udmabuf-wt
OUT_UDMA   ?= /dev/udmabuf-out

# One bundle carries every preset under presets/; PRESET selects one.
MANIFEST     := presets/$(PRESET)/preset.env
MANIFEST_ABS := $(abspath $(MANIFEST))

# Binaries native-compiled from src/ (nesting mirrors the repo, so sub-Makefiles work unchanged).
BENCH_BIN    := src/software/build/bench
RUNNER_DIR   := src/software/integration/fpga_runner
RUNNER_BIN   := $(RUNNER_DIR)/build/mmfree-cli-fpga

# Model to run: the preset's own checkpoint by default; MODEL=<hf-id> runs another on the same
# bitstream. Artifacts live in models/<hf-id>/{model.mmfree,tokenizer.mmtok,packed/}.
MANIFEST_MODEL   := $(shell grep -hE '^MMFREE_MODEL=' $(MANIFEST) 2>/dev/null | cut -d= -f2)
MODEL            ?= $(MANIFEST_MODEL)
MODEL_DIR        ?= models/$(MODEL)
# The blob format follows the BITSTREAM (packedWeights is an RTL parameter and the runner
# hard-errors on a mismatch), so derive the packed dir from the manifest. PACKED_DIR= overrides.
MANIFEST_PACKED  := $(shell grep -hE '^MMFREE_PACKED_WEIGHTS=' $(MANIFEST) 2>/dev/null | cut -d= -f2)
PACKED_DIR       ?= $(MODEL_DIR)/$(if $(filter 1,$(MANIFEST_PACKED)),packed-1p6,packed)
BLOB             ?= $(MODEL_DIR)/model.mmfree

POS_ARGS = $(CORE_PHYS) $(DMA_PHYS) $(UIO_DEV) $(ACT_UDMA) $(WT_UDMA) $(OUT_UDMA)

# ─── power monitoring (xlnx_platformstats) ───────────────────────────────────
# bench/run sample rail power for exactly the workload window via power_bench.sh, writing
# power/<geometry>_<mode>.{log,samples}. POWER=0 disables; POWER_INTERVAL / POWER_ARGS /
# POWER_DIR tune it (handed through `sudo env`, since sudo strips the environment).
POWER      ?= 1
PWR        := ./power_bench.sh
BATCH_TAG  := $(if $(strip $(BATCH)),$(BATCH),-)
PWR_ENV    = $(if $(POWER_INTERVAL),POWER_INTERVAL=$(POWER_INTERVAL),) \
             $(if $(POWER_ARGS),POWER_ARGS='$(POWER_ARGS)',) \
             $(if $(POWER_DIR),POWER_DIR=$(POWER_DIR),)
# $(call runwrap,<mode>): the privileged launcher (sudo env ... power_bench.sh, or plain sudo
# with POWER=0). The workload's stdout is untouched, so `make bench | tee x.csv` works.
runwrap = $(if $(filter 0,$(POWER)),sudo,sudo env $(PWR_ENV) $(PWR) $(MANIFEST_ABS) $(1) $(BATCH_TAG))

# Bench sweep slug (370m/1.3b/2.7b) for MODEL, derived from its HF id as the build server does.
MODEL_SHAPES := $(shell printf '%s' '$(notdir $(MODEL))' | sed -E 's/^MMfreeLM-//I' | tr '[:upper:]' '[:lower:]')

# Geometry env for a board run, handed through `sudo env` (sudo strips the environment). Later
# assignments win: MODEL re-points the sweep slug, SHAPES= wins outright, and ENV="K=V ..."
# (appended last) passes tuning variables, e.g. ENV="MMFREE_SPLIT_CHUNKS=0".
# MMFREE_HW_BATCH carries the bitstream's compile-time batch under a name BATCH= cannot shadow
# (BATCH= sets MMFREE_BATCH to the runtime batch); the runner reports it as hw_batch_max.
GEOM_ENV = $$(grep -E '^(MMFREE_|NUM_DMA|MM2S_WIDTH|S2MM_WIDTH)' $(MANIFEST) | tr '\n' ' ') \
           MMFREE_MANIFEST=$(MANIFEST_ABS) \
           MMFREE_HW_BATCH=$$(sed -n 's/^MMFREE_BATCH=//p' $(MANIFEST)) \
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

.PHONY: help build bench-bin runner-bin deploy-hw bench gate streams run gen sweep

help:
	@echo "matmulfree_KRIA board runner (PRESET=$(PRESET))"
	@echo
	@echo "  make build                                    native-compile bench + fpga_runner"
	@echo "  sudo make deploy-hw PRESET=<preset>           load bitstream overlay + u-dma-buf driver"
	@echo "  make bench  PRESET=<p> [BATCH=N] [ARGS=...]   native sweep benchmark (+ power)"
	@echo "  make gate   PRESET=<p> [MODEL=<hf-id>]        fpga_runner exact-match gate (CPU==FPGA)"
	@echo "  make streams PRESET=<p> [STREAMS=n]           batched decode with a DIFFERENT prompt per"
	@echo "                                                stream, each vs its own single-stream run"
	@echo "                                                (the row-order check the sweep cannot do)"
	@echo "  make run    PRESET=<p> [MODEL=<hf-id>]        fpga_runner FPGA decode (timed/profiled, + power)"
	@echo "    (bench/run sample rail power via xlnx_platformstats -> power/<geom>_<mode>.{log,samples};"
	@echo "     POWER=0 disables, POWER_INTERVAL / POWER_ARGS / POWER_DIR tune it)"
	@echo "  make gen    PRESET=<p> [MODEL=<hf-id>] PROMPT=\"...\" [GEN=n]   interactive generate"
	@echo "  make sweep  PRESET=<p> [MODEL=<hf-id>] [GEN=n] [REPS=n]      batch sweep b=1..batchSize -> CSV"
	@echo "    (one row per batch: ttft / prefill / token latency / tok-s + op & engine breakdowns)"
	@echo "    BACKEND=cpu  same ladder, engine out of the loop = the CPU baseline (-> _cpu CSV)"
	@echo "    BACKEND=cpu-2bit / cpu-1p6  CPU arm on 2-bit / 1.6-bit packed weights (bit-identical)"
	@echo
	@echo "  This preset's model: $(if $(strip $(MODEL)),$(MODEL),(none — bench-only))"
	@echo
	@echo "Bundled presets (presets/):"
	@ls -d presets/*/ 2>/dev/null | sed 's,presets/,  ,;s,/,,' || echo "  (none)"
	@echo "Bundled models (models/):"
	@ls -d models/*/*/ 2>/dev/null | sed 's,models/,  ,;s,/$$,,' || echo "  (none)"

# ─── native compile (delegates to the staged component sub-Makefiles) ────────
# Always delegate: the sub-makes do their own up-to-date checks.
bench-bin:
	$(MAKE) -C src/software
runner-bin:
	$(MAKE) -C $(RUNNER_DIR)
build: bench-bin runner-bin
	@echo "# Built $(BENCH_BIN) and $(RUNNER_BIN)"

# ─── deploy the bitstream overlay + drivers ──────────────────────────────────
# Installs presets/<preset>/ + u-dma-buf, loads the overlay, verifies udmabuf sizes vs the manifest.
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

# ─── fpga_runner — per-stream row-order check ────────────────────────────────
# The sweep's streams_ok uses one shared prompt, so it cannot see a row permutation. This runs
# STREAMS streams from distinct prompts (rotations of STREAMS_PROMPT, which must tokenize to at
# least STREAMS tokens) and checks each against its own single-stream decode; non-zero exit on
# any mismatch. Run once per bitstream. STREAMS defaults to the compile-time batch.
#   sudo make streams PRESET=mmfree_370m               # all 8 rows of a B8 build
#   sudo make streams PRESET=mmfree_2_7b STREAMS=6 GEN=32
STREAMS ?= $(if $(BATCH),$(BATCH),$(shell sed -n 's/^MMFREE_BATCH=//p' $(MANIFEST) 2>/dev/null))
STREAMS_PROMPT ?= The quick brown fox jumps over the lazy dog while a tired old bear watches from the hill above the river
streams: runner-bin
	$(need_manifest)
	$(need_blob)
	@[ -n "$(strip $(STREAMS))" ] || { echo "ERROR: no stream count — the manifest has no MMFREE_BATCH; pass STREAMS=<n>." >&2; exit 1; }
	sudo env $(GEOM_ENV) $(abspath $(RUNNER_BIN)) $(POS_ARGS) $(RUNNER_ARTIFACTS) \
	    --backend fpga --serve $(STREAMS) --distinct-prompts --gen $(GEN) \
	    --prompt "$(STREAMS_PROMPT)" $(ARGS)

# ─── fpga_runner — FPGA decode ───────────────────────────────────────────────
run: runner-bin
	$(need_manifest)
	$(need_blob)
	$(call runwrap,run) env $(GEOM_ENV) $(abspath $(RUNNER_BIN)) $(POS_ARGS) $(RUNNER_ARTIFACTS) --backend fpga --bench --profile $(ARGS)

# ─── batch sweep → CSV ───────────────────────────────────────────────────────
# One `make run` per b = 1..MMFREE_BATCH into one CSV (bench_batch.sh). The overlay must already
# be loaded. BATCHES="1 2 4" / GEN / REPS / WARMUP / OUT tune it. BACKEND=cpu runs the same
# ladder with the engine out of the loop (no overlay or packed blob needed; separate _cpu CSV).
#   make sweep PRESET=mmfree_370m GEN=64 REPS=3
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
# Single-stream decode of PROMPT=, streamed as generated; stops at EOS or after GEN=<n> tokens.
# TEMP>0 samples (TOPK/SEED tune it); TEMP=0 (default) is greedy.
#   make gen PRESET=mmfree_1_3b PROMPT="The capital of France is" GEN=40
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
