# Build natively on the KRIA (Cortex-A53, aarch64). Cross-compile by passing
# CC=aarch64-linux-gnu-gcc from the env if building on a dev host.

CC      ?= gcc
CFLAGS  ?= -Wall -Wextra -O2 -std=c11 -D_POSIX_C_SOURCE=200809L
INCDIRS  = -Iinclude

# Bench geometry is resolved entirely at RUNTIME from the preset manifest, NOT at
# compile time — so ONE binary targets every CoreConfig preset/array size. The
# root Makefile sources generated/<preset>/preset.env and exports the geometry as
# MMFREE_* env when it launches the bench; you can also set them by hand:
#   MMFREE_AWIDTH MMFREE_XDIM MMFREE_MAXACC MMFREE_MAXN MMFREE_MAXM
#   MMFREE_SIGNED MMFREE_BATCH MMFREE_SHAPES MMFREE_CLK_MHZ
# e.g.  sudo MMFREE_XDIM=32 MMFREE_AWIDTH=16 MMFREE_SIGNED=1 ./build/bench ...
# (the resolved geometry MUST match the loaded bitstream, or the core wedges →
# completion timeout + state dump). bench.c keeps a k26_bench compiled-in default
# for when nothing sets the env. The per-preset -DBENCH_* compile flags (and the
# per-preset build dirs that isolated them) are gone — see
# docs/REPO_CONSOLIDATION_PLAN.md.
OUT_DIR  = build
BIN      = $(OUT_DIR)/bench
ENC      = $(OUT_DIR)/encode_sanity

BENCH_OBJS = \
    $(OUT_DIR)/bench.o \
    $(OUT_DIR)/mmfree_runtime.o

all: $(BIN) $(ENC)

$(OUT_DIR):
	@mkdir -p $(OUT_DIR)

$(OUT_DIR)/%.o: bench/%.c | $(OUT_DIR)
	$(CC) $(CFLAGS) $(INCDIRS) -c $< -o $@

$(OUT_DIR)/%.o: tests/%.c | $(OUT_DIR)
	$(CC) $(CFLAGS) $(INCDIRS) -c $< -o $@

$(BIN): $(BENCH_OBJS)
	$(CC) $(CFLAGS) -o $@ $^

$(ENC): $(OUT_DIR)/encode_sanity.o
	$(CC) $(CFLAGS) -o $@ $^

# CPU NEON baseline — its own 370M shape table. -O3 + OpenMP for the multi-core
# GEMV; NEON intrinsics are on by default for aarch64. Building on an x86 host
# needs CC=aarch64-linux-gnu-gcc.
CPU_BIN     = build/cpu_bench
CPU_CFLAGS ?= -Wall -Wextra -O3 -std=c11 -D_POSIX_C_SOURCE=200809L -fopenmp -funroll-loops

cpu: $(CPU_BIN)

$(CPU_BIN): bench/cpu_bench.c | build
	$(CC) $(CPU_CFLAGS) $(INCDIRS) -o $@ $<

# libmmfree.so — BitLinear backend for the Python bridge (Phase C/D). Links the
# proven runtime (mmfree_runtime.c) with the high-level lib (mmfree_lib.c) as a
# shared object. Geometry is set at runtime via the lib cfg, so no -DBENCH_*
# flags matter here. Build natively on the KRIA (aarch64) for deployment; an
# x86 host build is a compile/type check only (won't run without the PL).
LIB_DIR   = integration/libmmfree
LIB       = build/libmmfree.so
LIB_SRCS  = bench/mmfree_runtime.c $(LIB_DIR)/mmfree_lib.c

lib: $(LIB)

$(LIB): $(LIB_SRCS) | build
	$(CC) $(CFLAGS) -fPIC -shared $(INCDIRS) -I$(LIB_DIR) -o $@ $(LIB_SRCS)

# Board-free unit test for the lib's pure-software helpers (act pack, acc expand).
test-lib: $(LIB)
	$(CC) $(CFLAGS) $(LIB_DIR)/tests/test_helpers.c -Lbuild -lmmfree -Wl,-rpath,build \
	    -o build/test_helpers
	./build/test_helpers

# On-board end-to-end smoke test (needs HW). Build natively on the KRIA, then:
#   sudo ./build/smoke_test 0xA0010000 0xA0000000 /dev/uio4 \
#        /dev/udmabuf-act /dev/udmabuf-wt /dev/udmabuf-out
smoke: $(LIB)
	$(CC) $(CFLAGS) $(INCDIRS) -I$(LIB_DIR) $(LIB_DIR)/tests/smoke_test.c \
	    -Lbuild -lmmfree -Wl,-rpath,'$$ORIGIN' -o build/smoke_test

clean:
	rm -rf build

.PHONY: all cpu lib test-lib smoke clean
