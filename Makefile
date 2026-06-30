# matmulfree_KRIA — one task runner for the whole Chisel → bitstream → board flow.
#
# Every verb derives geometry from the preset manifest (generated/<preset>/preset.env,
# emitted by control.EmitCore — the single source of truth) and board addresses from
# board.conf, so nothing is retyped or hand-synced.
#
#   make help                                   # this list
#   make sim [SPEC=CoreConfig]                  # mill tests (SPEC = name substring)
#   make build PRESET=k26_mmfree370m_a16        # Chisel → bitstream → transfer/<preset>/
#   make provision MODEL=ridger/MMfreeLM-1.3B   # HF download → model.mmfree + tokenizer.mmtok
#   make pack BLOB=model.mmfree OUT=packed      # pack weights (geometry from manifest)
#   make deploy PRESET=... BOARD=ubuntu@kria    # scp bundle into the board's repo (no remote exec)
#   make bench PRESET=... [BATCH=N] [ARGS=...]  # build native bench + run (on the board)
#   make gate  PRESET=... ARGS="--blob ..."     # fpga_runner exact-match gate (CPU==FPGA)
#   make run   PRESET=... ARGS="--blob ..."     # fpga_runner FPGA decode (--bench --profile)
#
# Arg-heavy verbs take PRESET= / BATCH= / ARGS= variables (NOT positional goals — make
# mangles `--flags`). ARGS is passed through verbatim to the underlying binary.

# Board addresses + device paths. board.conf is KEY=VAL (make-includable AND
# bash-sourceable); these are the defaults when it is absent (real KRIA nodes).
-include board.conf

PRESET     ?= k26_mmfree370m_a16
BOARD      ?= ubuntu@kria
# Path to this repo's checkout ON THE BOARD (relative to the board user's home,
# or absolute). `make deploy` drops the bundle into $(BOARD_REPO)/transfer/ there,
# so the board-side verbs (bench/run/gate) find it exactly where they expect.
BOARD_REPO ?= matmulfree_KRIA
CORE_PHYS  ?= 0xA0010000
DMA_PHYS   ?= 0xA0000000
UIO_DEV    ?= /dev/uio4
ACT_UDMA   ?= /dev/udmabuf-act
WT_UDMA    ?= /dev/udmabuf-wt
OUT_UDMA   ?= /dev/udmabuf-out

# Manifest resolution: a fresh HOST build emits generated/<preset>/preset.env; a
# deployed BOARD checkout only carries the bundle copy in transfer/<preset>/ (no
# generated/ — it's a build product, not versioned). Prefer whichever exists so the
# same verbs work in both places; fall back to the generated path for error text.
MANIFEST     := $(firstword $(wildcard generated/$(PRESET)/preset.env transfer/$(PRESET)/preset.env) generated/$(PRESET)/preset.env)
MANIFEST_ABS := $(abspath $(MANIFEST))
BENCH_BIN    := software/build/bench
RUNNER_DIR   := software/integration/fpga_runner
RUNNER_BIN   := $(RUNNER_DIR)/build/mmfree-cli-fpga

# CPU reference submodule + the HF checkpoint `make provision` fetches/packs.
CPU_DIR      := matmulfreellmCPU
MODEL        ?= ridger/MMfreeLM-370M

POS_ARGS = $(CORE_PHYS) $(DMA_PHYS) $(UIO_DEV) $(ACT_UDMA) $(WT_UDMA) $(OUT_UDMA)

# Geometry env for a board run: every MMFREE_*/NUM_DMA line from the manifest,
# plus the manifest path (for the runtime cross-check) and optional BATCH/SHAPES
# overrides. sudo strips the environment, so we hand it through `sudo env`.
# A trailing assignment wins under `env`, so SHAPES=370m|1.3b|2.7b re-sweeps a
# different model on the SAME deployed bitstream (the maxN=8192 a16 engine spans
# every checkpoint up to 2.7B) without a rebuild.
GEOM_ENV = $$(grep -E '^(MMFREE_|NUM_DMA|MM2S_WIDTH|S2MM_WIDTH)' $(MANIFEST) | tr '\n' ' ') \
           MMFREE_MANIFEST=$(MANIFEST_ABS) \
           $(if $(BATCH),MMFREE_BATCH=$(BATCH),) $(if $(SHAPES),MMFREE_SHAPES=$(SHAPES),)

# Fail early with a clear message if the manifest is missing.
define need_manifest
	@[ -f "$(MANIFEST)" ] || { echo "ERROR: no manifest for '$(PRESET)' (looked in generated/ and transfer/). On the host run 'make build PRESET=$(PRESET)'; on the board run 'make deploy PRESET=$(PRESET)' from the host first." >&2; exit 1; }
endef

.PHONY: help sim build provision pack deploy udmabuf bench gate run

UDMABUF_DIR := external/udmabuf

help:
	@echo "matmulfree_KRIA task runner (PRESET=$(PRESET))"
	@echo
	@echo "  make sim    [SPEC=substr]                     mill tests"
	@echo "  make build  PRESET=<preset>                   Chisel -> bitstream -> transfer/<preset>/"
	@echo "  make provision MODEL=<hf-id> [OUT=<dir>]      HF download -> model.mmfree + tokenizer.mmtok"
	@echo "  make pack   BLOB=<f>|CKPT=<id> OUT=<dir>      pack weights (geometry from manifest)"
	@echo "  make deploy PRESET=<p> BOARD=user@host        scp bundle into <board>:$(BOARD_REPO)/transfer/ (run deploy_kria.sh yourself on the board)"
	@echo "  make udmabuf                                  build + load the vendored u-dma-buf driver (on board)"
	@echo "  make bench  PRESET=<p> [BATCH=N] [ARGS=...]   build + run native bench (on board)"
	@echo "  make gate   PRESET=<p> ARGS=\"--blob ...\"      fpga_runner exact-match gate"
	@echo "  make run    PRESET=<p> ARGS=\"--blob ...\"      fpga_runner FPGA decode"
	@echo
	@echo "Built presets (generated/):"
	@ls -d generated/*/ 2>/dev/null | sed 's,generated/,  ,;s,/,,' || echo "  (none — run make build)"

# ─── host: simulate ──────────────────────────────────────────────────────────
sim:
ifeq ($(strip $(SPEC)),)
	./mill matmulfree_KRIA.test
else
	./mill matmulfree_KRIA.test -z "$(SPEC)"
endif

# ─── host: Chisel → bitstream (emits the manifest as step 1) ─────────────────
build:
	PRESET=$(PRESET) ./scripts/build_all.sh

# ─── host: provision model.mmfree + tokenizer.mmtok from an HF checkpoint ────
# Runs matmulfreellmCPU/tools/provision.py: download the MMfreeLM checkpoint, pack
# the ternary weights -> cpp/model.mmfree and the tokenizer -> cpp/tokenizer.mmtok.
# These two files are exactly what `make pack` (FPGA per-port blobs) and `make run`/
# `gate` consume — the tokenizer is auto-found next to the blob. MODEL=<hf-id> picks
# the checkpoint; OUT=<dir> overrides where the artifacts land (default $(CPU_DIR)/cpp);
# ARGS passes provision flags through (--skip-download, --weights-only, --tokenizer-only).
# Needs the provisioning deps once: pip install -r $(CPU_DIR)/tools/requirements.txt
provision:
	@[ -f "$(CPU_DIR)/tools/provision.py" ] || { echo "ERROR: $(CPU_DIR)/tools/provision.py missing — run 'git submodule update --init --recursive'." >&2; exit 1; }
	cd $(CPU_DIR) && python3 tools/provision.py --model $(MODEL) \
	    $(if $(OUT),--out-dir $(abspath $(OUT)),) $(ARGS)

# ─── host: pack model weights into per-port blobs ────────────────────────────
pack:
	$(need_manifest)
	@[ -n "$(OUT)" ] || { echo "ERROR: set OUT=<dir>" >&2; exit 1; }
	@[ -n "$(BLOB)$(CKPT)" ] || { echo "ERROR: set BLOB=<model.mmfree> or CKPT=<hf-id>" >&2; exit 1; }
	cd software/integration && python3 -m mmfree_pack.cli \
	    $(if $(BLOB),--blob $(abspath $(BLOB)),--checkpoint $(CKPT)) \
	    --out-dir $(abspath $(OUT)) --manifest $(MANIFEST_ABS) $(ARGS)

# ─── host: ship a built bundle to the board's repo checkout ──────────────────
# scp transfer/<preset>/ into $(BOARD):$(BOARD_REPO)/transfer/ — the same place the
# board-side Makefile (bench/run/gate) and deploy_kria.sh expect it, NOT a loose
# ~/transfer/ in $HOME. Pure file transfer: this does not load the overlay or run
# anything on the board (that's deploy_kria.sh, which you run yourself on the KRIA).
deploy:
	$(need_manifest)
	@[ -d "transfer/$(PRESET)" ] || { echo "ERROR: transfer/$(PRESET)/ missing — run 'make build PRESET=$(PRESET)' first." >&2; exit 1; }
	@cp $(MANIFEST) transfer/$(PRESET)/ 2>/dev/null || true
	@cp generated/$(PRESET)/preset.json transfer/$(PRESET)/ 2>/dev/null || true
	@scp -r transfer/$(PRESET) $(BOARD):$(BOARD_REPO)/transfer/ || { \
	    echo >&2; \
	    echo "ERROR: scp into $(BOARD):$(BOARD_REPO)/transfer/ failed. On the KRIA:" >&2; \
	    echo "  'No such file or directory' -> the dir doesn't exist yet:  mkdir -p ~/$(BOARD_REPO)/transfer" >&2; \
	    echo "  'Permission denied'         -> it's root-owned (stale sudo):  sudo chown -R \$$USER ~/$(BOARD_REPO)/transfer" >&2; \
	    echo "then re-run this deploy." >&2; \
	    exit 1; }
	@echo
	@echo "# Shipped transfer/$(PRESET)/ -> $(BOARD):$(BOARD_REPO)/transfer/$(PRESET)/"
	@echo "# Now, ON THE KRIA (from ~/$(BOARD_REPO)):"
	@echo "#   sudo ./transfer/$(PRESET)/deploy_kria.sh   # install overlay + load app + verify udmabufs"
	@echo "#   make bench PRESET=$(PRESET)                # manifest is read from the bundle"

# ─── board: build + load the vendored u-dma-buf kernel module ────────────────
# Needs kernel headers (linux-headers-$(uname -r)). `make deploy` does this too,
# from the shipped copy; this verb is for running straight from a repo checkout.
udmabuf:
	@[ -f "$(UDMABUF_DIR)/u-dma-buf.c" ] || { echo "ERROR: $(UDMABUF_DIR) not initialized — run 'git submodule update --init --recursive'." >&2; exit 1; }
	@if lsmod | grep -qw u_dma_buf; then echo "u-dma-buf already loaded"; else \
	    ( cd $(UDMABUF_DIR) && $(MAKE) && sudo insmod u-dma-buf.ko ) && echo "loaded u-dma-buf.ko"; \
	fi

# ─── board: native bench ─────────────────────────────────────────────────────
bench: $(BENCH_BIN)
	$(need_manifest)
	sudo env $(GEOM_ENV) $(abspath $(BENCH_BIN)) $(POS_ARGS) $(ARGS)

$(BENCH_BIN):
	$(MAKE) -C software

# ─── board: fpga_runner — exact-match gate (CPU vs FPGA) ─────────────────────
gate: $(RUNNER_BIN)
	$(need_manifest)
	sudo env $(GEOM_ENV) $(abspath $(RUNNER_BIN)) $(POS_ARGS) --backend both $(ARGS)

# ─── board: fpga_runner — FPGA decode ────────────────────────────────────────
run: $(RUNNER_BIN)
	$(need_manifest)
	sudo env $(GEOM_ENV) $(abspath $(RUNNER_BIN)) $(POS_ARGS) --backend fpga --bench --profile $(ARGS)

$(RUNNER_BIN):
	$(MAKE) -C $(RUNNER_DIR)
