# matmulfree_KRIA — BUILD-SERVER task runner (Chisel → bitstream → packed model → ship).
#
# Responsibility split:
#   BUILD SERVER (this Makefile): build the bitstream, provision + pack the model +
#     tokenizer, stage the C++ sources, and ship the self-contained transfer/ bundle.
#     Everything compute-heavy lives here.
#   KRIA (transfer/Makefile, staged into the bundle): native-compile the C++, load the
#     bitstream + drivers, run benchmarks/gate/gen — entirely from within transfer/.
#
# So the board needs NOTHING from this repo: `make deploy` rsyncs the whole transfer/
# bundle over, and the board works inside it.
#
#   make help                                   # this list
#   make sim   [SPEC=CoreConfig]                # mill tests (SPEC = name substring)
#   make build PRESET=mmfree_2_7b     # Chisel → bitstream → transfer/presets/<p>/ + stage src/
#   make provision MODEL=ridger/MMfreeLM-1.3B   # HF download → transfer/models/<slug>/{model.mmfree,tokenizer.mmtok}
#   make pack  PRESET=... [BLOB=f|CKPT=id]      # pack weights → transfer/models/<slug>/packed/
#   make deploy BOARD=ubuntu@kria               # rsync transfer/ → board:~/transfer/  (then run board verbs there)
#
# Arg-heavy verbs take PRESET= / SHAPES= / OUT= / ARGS= variables (NOT positional goals
# — make mangles `--flags`). ARGS is passed through verbatim to the underlying tool.

# Deploy target. board.conf is KEY=VAL (make-includable AND bash-sourceable); these are
# the defaults when it is absent. (Device paths now live in the bundle's own board.conf.)
-include board.conf

PRESET     ?= mmfree_2_7b
BOARD      ?= ubuntu@kria
# Remote dir that will CONTAIN transfer/ on the board (the bundle lands at
# $(BOARD_DEST)/transfer/, and the board works from there: `cd ~/transfer && make ...`).
BOARD_DEST ?= ~

# Manifest resolution: a fresh build emits generated/<preset>/preset.env (the single
# source of truth); the bundled copy lives at transfer/presets/<preset>/preset.env.
# Prefer whichever exists; fall back to the generated path for error text.
MANIFEST     := $(firstword $(wildcard generated/$(PRESET)/preset.env transfer/presets/$(PRESET)/preset.env) generated/$(PRESET)/preset.env)
MANIFEST_ABS := $(abspath $(MANIFEST))

# CPU reference submodule + the HF checkpoint this preset targets.
CPU_DIR      := matmulfreellmCPU
# The HF checkpoint is baked into the preset manifest (MMFREE_MODEL, set from the
# CoreConfig — the single source of truth). So the common flow is just
# `make model PRESET=<p>`: no MODEL= needed. MODEL=<hf-id> overrides it to
# provision/run a DIFFERENT checkpoint on the same bitstream (one maxN=8192 a16
# engine spans 370M/1.3B/2.7B). Per-model artifacts live INSIDE the bundle under the
# checkpoint's own HF id, so the sizes never clobber each other and `make deploy`
# ships them:  transfer/models/<MODEL>/{model.mmfree,tokenizer.mmtok,packed/}
MANIFEST_MODEL   := $(shell grep -hE '^MMFREE_MODEL=' $(MANIFEST) 2>/dev/null | cut -d= -f2)
MODEL            ?= $(MANIFEST_MODEL)
MODEL_DIR        ?= transfer/models/$(MODEL)
PACKED_DIR       ?= $(MODEL_DIR)/packed
BLOB             ?= $(MODEL_DIR)/model.mmfree

# Fail early with a clear message if this preset targets no checkpoint (bench-only
# preset, or MODEL= not given and no manifest yet).
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
# The board compiles the runner from transfer/src/ and drives it from transfer/Makefile,
# both of which are COPIES. Editing software/ or scripts/ without re-staging means the next
# board run silently measures the previous code, so this is the step between any such edit
# and `make deploy`. No Vivado, no preset, no bitstream touched.
stage:
	STAGE_ONLY=1 ./scripts/build_all.sh

# ─── host: model = provision + pack (the one command you need) ───────────────
# Derives the HF checkpoint from the preset (MMFREE_MODEL) and the pack geometry from
# the same manifest, then runs both stages into transfer/models/<hf-id>/. This is the
# headline verb; `provision` and `pack` below are the individual stages for advanced use.
#   make build PRESET=<p>   # bitstream + manifest (emits MMFREE_MODEL)
#   make model PRESET=<p>   # -> transfer/models/<hf-id>/{model.mmfree,tokenizer.mmtok,packed/}
#   make deploy BOARD=...
model:
	$(need_model)
	$(MAKE) provision PRESET=$(PRESET) MODEL=$(MODEL)
	$(MAKE) pack      PRESET=$(PRESET) MODEL=$(MODEL)
	@echo "# Model ready -> $(MODEL_DIR)/ (model.mmfree + tokenizer.mmtok + packed/); ready to 'make deploy'"

# ─── host: provision model.mmfree + tokenizer.mmtok from an HF checkpoint ────
# Runs matmulfreellmCPU/tools/provision.py: download the MMfreeLM checkpoint, pack the
# ternary weights -> model.mmfree and the tokenizer -> tokenizer.mmtok (exactly what
# `make pack` and the board's gate/run/gen consume — the tokenizer is auto-found beside
# the blob). MODEL defaults to the preset's MMFREE_MODEL; MODEL=<hf-id> overrides it.
# OUT=<dir> overrides where they land (default $(MODEL_DIR), inside the bundle).
# Needs the provisioning deps once: pip install -r $(CPU_DIR)/tools/requirements.txt
provision: PROV_OUT := $(if $(OUT),$(OUT),$(MODEL_DIR))
provision:
	$(need_model)
	@[ -f "$(CPU_DIR)/tools/provision.py" ] || { echo "ERROR: $(CPU_DIR)/tools/provision.py missing — run 'git submodule update --init --recursive'." >&2; exit 1; }
	cd $(CPU_DIR) && python3 tools/provision.py --model $(MODEL) \
	    --out-dir $(abspath $(PROV_OUT)) $(ARGS)
	@echo "# Provisioned -> $(PROV_OUT)/{model.mmfree,tokenizer.mmtok}"

# ─── host: pack model weights into per-port blobs ────────────────────────────
# BLOB defaults to $(MODEL_DIR)/model.mmfree (what `make provision` wrote) and OUT
# defaults to $(PACKED_DIR), so the common path is just `make pack PRESET=<preset>`.
# Override with BLOB=<f>/OUT=<dir>, or CKPT=<hf-id> to pack straight from a checkpoint.
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
# rsync the WHOLE transfer/ tree (HW presets, models, C++ src, drivers, board Makefile)
# into $(BOARD):$(BOARD_DEST)/transfer/. Incremental — unchanged files (incl. multi-GB
# models) don't re-transfer. Pure file transfer: it does not load the overlay or run
# anything on the board (that's `make deploy-hw` over there).
# DEPLOY_MODELS="ridger/MMfreeLM-1.3B ridger/MMfreeLM-2.7B" limits which model dirs
# ship (full HF ids, matching transfer/models/<id>/; default: all present); HW presets
# + src always ship. The org/name nesting means we include the wanted <id>/*** first,
# keep the one-level org dirs as parents, then exclude the other model dirs.
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
