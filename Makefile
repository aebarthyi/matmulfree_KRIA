# matmulfree_KRIA — BUILD-SERVER task runner (Chisel → bitstream → packed model → ship).
# The board side is transfer/Makefile (source: scripts/transfer.mk), run inside the shipped
# transfer/ bundle; the board needs nothing else from this repo.
#
#   make help                                   # this list
#   make sim   [SPEC=CoreConfig]                # mill tests (SPEC = name substring)
#   make build PRESET=mmfree_2_7b     # Chisel → bitstream → transfer/presets/<p>/ + stage src/
#   make provision MODEL=ridger/MMfreeLM-1.3B   # HF download → transfer/models/<hf-id>/{model.mmfree,tokenizer.mmtok}
#   make pack  PRESET=... [BLOB=f|CKPT=id]      # pack weights → transfer/models/<hf-id>/packed/
#   make deploy BOARD=ubuntu@kria               # rsync transfer/ → board:~/transfer/  (then run board verbs there)
#
# Arg-heavy verbs take PRESET= / SHAPES= / OUT= / ARGS= variables (NOT positional goals
# — make mangles `--flags`). ARGS is passed through verbatim to the underlying tool.

# board.conf (KEY=VAL, make-includable and bash-sourceable) overrides these defaults.
-include board.conf

PRESET     ?= mmfree_2_7b
BOARD      ?= ubuntu@kria
# Remote dir that will CONTAIN transfer/ (the board works from $(BOARD_DEST)/transfer/).
BOARD_DEST ?= ~

# Manifest: generated/<preset>/preset.env from a fresh build, else the bundled copy.
MANIFEST     := $(firstword $(wildcard generated/$(PRESET)/preset.env transfer/presets/$(PRESET)/preset.env) generated/$(PRESET)/preset.env)
MANIFEST_ABS := $(abspath $(MANIFEST))

# CPU reference submodule + the HF checkpoint this preset targets.
CPU_DIR      := matmulfreellmCPU
# MODEL defaults to the manifest's MMFREE_MODEL; MODEL=<hf-id> overrides it (one maxN=8192
# engine spans all sizes). Per-model artifacts: transfer/models/<hf-id>/{model.mmfree,tokenizer.mmtok,packed/}
MANIFEST_MODEL   := $(shell grep -hE '^MMFREE_MODEL=' $(MANIFEST) 2>/dev/null | cut -d= -f2)
MODEL            ?= $(MANIFEST_MODEL)
MODEL_DIR        ?= transfer/models/$(MODEL)
# Same derivation as transfer.mk, so the board's gate/run find what `make pack` wrote.
MANIFEST_PACKED  := $(shell grep -hE '^MMFREE_PACKED_WEIGHTS=' $(MANIFEST) 2>/dev/null | cut -d= -f2)
PACKED_DIR       ?= $(MODEL_DIR)/$(if $(filter 1,$(MANIFEST_PACKED)),packed-1p6,packed)
BLOB             ?= $(MODEL_DIR)/model.mmfree

# Fail early if this preset targets no checkpoint (bench-only preset, or not built yet).
define need_model
	@[ -n "$(strip $(MODEL))" ] || { echo "ERROR: no model for '$(PRESET)' — its manifest has no MMFREE_MODEL (bench-only preset), or you have not run 'make build PRESET=$(PRESET)' yet. Pass MODEL=<hf-id> to override." >&2; exit 1; }
endef

# Fail early with a clear message if the manifest is missing.
define need_manifest
	@[ -f "$(MANIFEST)" ] || { echo "ERROR: no manifest for '$(PRESET)' — run 'make build PRESET=$(PRESET)' first." >&2; exit 1; }
endef

.PHONY: help sim build stage model provision pack deploy

help:
	@echo "matmulfree_KRIA build-server runner (PRESET=$(PRESET))"
	@echo
	@echo "  make sim    [SPEC=substr]                     mill tests"
	@echo "  make build  PRESET=<preset>                   Chisel -> bitstream -> transfer/presets/<p>/ + stage src/"
	@echo "  make stage                                    re-stage transfer/{Makefile,*.sh,src/} only (no Vivado)"
	@echo "  make model  PRESET=<preset>                   provision + pack the preset's model -> transfer/models/<hf-id>/"
	@echo "  make deploy BOARD=user@host [DEPLOY_MODELS=\"ridger/MMfreeLM-1.3B\"]   rsync transfer/ -> <board>:$(BOARD_DEST)/transfer/"
	@echo
	@echo "  This preset targets model: $(if $(strip $(MODEL)),$(MODEL),(none — bench-only or not built yet))"
	@echo "  (advanced: make provision / make pack are the individual stages; MODEL=<hf-id> overrides the preset's model)"
	@echo
	@echo "  Then on the KRIA (in ~/transfer):  make build && sudo make deploy-hw PRESET=<p> && make gate PRESET=<p>"
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

# ─── host: Chisel → bitstream + bundle (emits the manifest as step 1) ────────
build:
	PRESET=$(PRESET) ./scripts/build_all.sh

# ─── host: re-stage the board-side sources only ──────────────────────────────
# The board compiles from transfer/src/ and runs transfer/Makefile, both COPIES: re-stage after
# editing software/ or scripts/, or the next board run silently measures the old code.
stage:
	STAGE_ONLY=1 ./scripts/build_all.sh

# ─── host: model = provision + pack (the one command you need) ───────────────
# The headline verb: provision + pack the preset's model into transfer/models/<hf-id>/.
model:
	$(need_model)
	$(MAKE) provision PRESET=$(PRESET) MODEL=$(MODEL)
	$(MAKE) pack      PRESET=$(PRESET) MODEL=$(MODEL)
	@echo "# Model ready -> $(MODEL_DIR)/ (model.mmfree + tokenizer.mmtok + packed/); ready to 'make deploy'"

# ─── host: provision model.mmfree + tokenizer.mmtok from an HF checkpoint ────
# matmulfreellmCPU/tools/provision.py: download the checkpoint -> model.mmfree + tokenizer.mmtok
# (into $(MODEL_DIR), or OUT=<dir>). Needs: pip install -r $(CPU_DIR)/tools/requirements.txt
provision: PROV_OUT := $(if $(OUT),$(OUT),$(MODEL_DIR))
provision:
	$(need_model)
	@[ -f "$(CPU_DIR)/tools/provision.py" ] || { echo "ERROR: $(CPU_DIR)/tools/provision.py missing — run 'git submodule update --init --recursive'." >&2; exit 1; }
	cd $(CPU_DIR) && python3 tools/provision.py --model $(MODEL) \
	    --out-dir $(abspath $(PROV_OUT)) $(ARGS)
	@echo "# Provisioned -> $(PROV_OUT)/{model.mmfree,tokenizer.mmtok}"

# ─── host: pack model weights into per-port blobs ────────────────────────────
# BLOB defaults to what `make provision` wrote and OUT to $(PACKED_DIR); CKPT=<hf-id> packs
# straight from a checkpoint.
PACK_OUT := $(if $(OUT),$(OUT),$(PACKED_DIR))
pack:
	$(need_manifest)
	$(need_model)
	@[ -n "$(CKPT)" ] || [ -f "$(BLOB)" ] || { echo "ERROR: blob '$(BLOB)' not found — provision '$(MODEL)' first (make provision PRESET=$(PRESET)), or pass BLOB=<f> / CKPT=<hf-id>." >&2; exit 1; }
	cd software/integration && python3 -m mmfree_pack.cli \
	    $(if $(CKPT),--checkpoint $(CKPT),--blob $(abspath $(BLOB))) \
	    --out-dir $(abspath $(PACK_OUT)) --manifest $(MANIFEST_ABS) $(ARGS)
	@echo "# Packed -> $(PACK_OUT)/weights.port{0..3}.bin (+ manifest); the board's gate/run read it by default"

# ─── host: ship the self-contained bundle to the board ───────────────────────
# rsync the whole transfer/ tree to $(BOARD):$(BOARD_DEST)/transfer/ (incremental; runs nothing
# on the board). DEPLOY_MODELS="ridger/MMfreeLM-1.3B ..." limits which model dirs ship (HW
# presets + src always ship): include the wanted <org>/<name> dirs and their parents, exclude the rest.
MODEL_FILTER := $(if $(strip $(DEPLOY_MODELS)),$(foreach s,$(DEPLOY_MODELS),--include=/models/$(s)/***) --include=/models/ --include=/models/*/ --exclude=/models/*/* --exclude=/models/*,)
deploy:
	@[ -d "transfer" ] || { echo "ERROR: transfer/ missing — run 'make build PRESET=$(PRESET)' first." >&2; exit 1; }
	rsync -aL --info=progress2 $(MODEL_FILTER) transfer/ $(BOARD):$(BOARD_DEST)/transfer/ || { \
	    echo >&2; \
	    echo "ERROR: rsync into $(BOARD):$(BOARD_DEST)/transfer/ failed." >&2; \
	    echo "  'No such file or directory' -> create it once on the board: mkdir -p ~/transfer" >&2; \
	    echo "  'Permission denied'         -> stale root-owned files: sudo chown -R \$$USER ~/transfer" >&2; \
	    exit 1; }
	@echo
	@echo "# Shipped transfer/ -> $(BOARD):$(BOARD_DEST)/transfer/"
	@echo "# Now, ON THE KRIA (in ~/transfer):"
	@echo "#   make build                                  # native-compile bench + fpga_runner"
	@echo "#   sudo make deploy-hw PRESET=$(PRESET)         # load overlay + drivers + verify udmabufs"
	@echo "#   make gate PRESET=$(PRESET)                   # CPU==FPGA exact-match gate"
