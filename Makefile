# matmulfree_KRIA — one task runner for the whole Chisel → bitstream → board flow.
#
# Every verb derives geometry from the preset manifest (generated/<preset>/preset.env,
# emitted by control.EmitCore — the single source of truth) and board addresses from
# board.conf, so nothing is retyped or hand-synced. See docs/REPO_CONSOLIDATION_PLAN.md.
#
#   make help                                   # this list
#   make sim [SPEC=CoreConfig]                  # mill tests (SPEC = name substring)
#   make build PRESET=k26_mmfree370m_a16        # Chisel → bitstream → transfer/<preset>/
#   make pack BLOB=model.mmfree OUT=packed      # pack weights (geometry from manifest)
#   make deploy PRESET=... BOARD=ubuntu@kria    # scp overlay + reload + verify udmabufs
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
CORE_PHYS  ?= 0xA0010000
DMA_PHYS   ?= 0xA0000000
UIO_DEV    ?= /dev/uio4
ACT_UDMA   ?= /dev/udmabuf-act
WT_UDMA    ?= /dev/udmabuf-wt
OUT_UDMA   ?= /dev/udmabuf-out

MANIFEST     := generated/$(PRESET)/preset.env
MANIFEST_ABS := $(abspath $(MANIFEST))
BENCH_BIN    := software/build/bench
SMOKE_BIN    := software/build/smoke_test
RUNNER_DIR   := software/integration/fpga_runner
RUNNER_BIN   := $(RUNNER_DIR)/build/mmfree-cli-fpga

POS_ARGS = $(CORE_PHYS) $(DMA_PHYS) $(UIO_DEV) $(ACT_UDMA) $(WT_UDMA) $(OUT_UDMA)

# Geometry env for a board run: every MMFREE_*/NUM_DMA line from the manifest,
# plus the manifest path (for the runtime cross-check) and an optional BATCH
# override. sudo strips the environment, so we hand it through `sudo env`.
GEOM_ENV = $$(grep -E '^(MMFREE_|NUM_DMA|MM2S_WIDTH|S2MM_WIDTH)' $(MANIFEST) | tr '\n' ' ') \
           MMFREE_MANIFEST=$(MANIFEST_ABS) $(if $(BATCH),MMFREE_BATCH=$(BATCH),)

# Fail early with a clear message if the manifest is missing.
define need_manifest
	@[ -f "$(MANIFEST)" ] || { echo "ERROR: $(MANIFEST) missing — run 'make build PRESET=$(PRESET)' first (it emits the manifest)." >&2; exit 1; }
endef

.PHONY: help sim build pack deploy udmabuf bench smoke gate run

UDMABUF_DIR := external/udmabuf

help:
	@echo "matmulfree_KRIA task runner (PRESET=$(PRESET))"
	@echo
	@echo "  make sim    [SPEC=substr]                     mill tests"
	@echo "  make build  PRESET=<preset>                   Chisel -> bitstream -> transfer/<preset>/"
	@echo "  make pack   BLOB=<f>|CKPT=<id> OUT=<dir>      pack weights (geometry from manifest)"
	@echo "  make deploy PRESET=<p> BOARD=user@host        scp overlay + reload + verify udmabufs"
	@echo "  make udmabuf                                  build + load the vendored u-dma-buf driver (on board)"
	@echo "  make bench  PRESET=<p> [BATCH=N] [ARGS=...]   build + run native bench (on board)"
	@echo "  make smoke  PRESET=<p>                        libmmfree end-to-end smoke test (on board)"
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

# ─── host: pack model weights into per-port blobs ────────────────────────────
pack:
	$(need_manifest)
	@[ -n "$(OUT)" ] || { echo "ERROR: set OUT=<dir>" >&2; exit 1; }
	@[ -n "$(BLOB)$(CKPT)" ] || { echo "ERROR: set BLOB=<model.mmfree> or CKPT=<hf-id>" >&2; exit 1; }
	cd software/integration && python3 -m mmfree_pack.cli \
	    $(if $(BLOB),--blob $(abspath $(BLOB)),--checkpoint $(CKPT)) \
	    --out-dir $(abspath $(OUT)) --manifest $(MANIFEST_ABS) $(ARGS)

# ─── host: deploy a built overlay to the board ───────────────────────────────
# Copies transfer/<preset>/ (incl. the manifest), reloads the overlay, and
# verifies the udmabuf node sizes match the manifest (see scripts/deploy_kria.sh).
deploy:
	$(need_manifest)
	@[ -d "transfer/$(PRESET)" ] || { echo "ERROR: transfer/$(PRESET)/ missing — run 'make build PRESET=$(PRESET)' first." >&2; exit 1; }
	cp $(MANIFEST) generated/$(PRESET)/preset.json transfer/$(PRESET)/ 2>/dev/null || true
	scp -r transfer/$(PRESET) $(BOARD):~/transfer/
	ssh $(BOARD) "sudo ~/transfer/$(PRESET)/deploy_kria.sh"

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

# ─── board: libmmfree end-to-end smoke test ──────────────────────────────────
smoke: $(SMOKE_BIN)
	$(need_manifest)
	sudo env $(GEOM_ENV) LD_LIBRARY_PATH=$(abspath software/build) $(abspath $(SMOKE_BIN)) $(POS_ARGS) $(ARGS)

$(SMOKE_BIN):
	$(MAKE) -C software smoke

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
