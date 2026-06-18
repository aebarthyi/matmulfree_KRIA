# Build natively on the KRIA (Cortex-A53, aarch64). Cross-compile by passing
# CC=aarch64-linux-gnu-gcc from the env if building on a dev host.

CC      ?= gcc
CFLAGS  ?= -Wall -Wextra -O2 -std=c11 -D_POSIX_C_SOURCE=200809L
INCDIRS  = -Iinclude

# Bench geometry must match the loaded bitstream's CoreConfig preset. These -D
# flags only set the COMPILED-IN DEFAULTS:
#   make                              → k26_bench   (aWidth=16, xDim=4)
#   make BENCH_PRESET=k26_bench16     → k26_bench16 (aWidth=8,  xDim=16)
#
# The geometry is resolved at RUNTIME, so one binary targets any array size
# without rebuilding — override the defaults with env vars at launch:
#   MMFREE_AWIDTH MMFREE_XDIM MMFREE_MAXACC MMFREE_MAXN MMFREE_MAXM
# e.g.  sudo MMFREE_XDIM=32 MMFREE_AWIDTH=8 ./build/k26_bench/bench ...
# (still MUST match the loaded bitstream, or the core wedges → timeout/dump).
#
# Objects + binary live under build/$(BENCH_PRESET)/ — make only tracks
# timestamps, so without per-preset dirs a preset switch would silently reuse
# objects compiled with the OLD default flags.
BENCH_PRESET ?= k26_bench
ifeq ($(BENCH_PRESET),k26_bench16)
CFLAGS += -DBENCH_AWIDTH=8 -DBENCH_XDIM=16
endif
ifeq ($(BENCH_PRESET),k26_bench32)
CFLAGS += -DBENCH_AWIDTH=8 -DBENCH_XDIM=32
endif
ifeq ($(BENCH_PRESET),k26_bench64)
CFLAGS += -DBENCH_AWIDTH=8 -DBENCH_XDIM=64
endif
ifeq ($(BENCH_PRESET),k26_mmfree370m)
# 4-HP-port geometry sized for every MMfreeLM-370M projection; defaults the
# sweep to the model's projection shapes instead of the pow2 cross sweep.
# BENCH_CLK_MHZ=250 matches build_all.sh's PL_CLK_MHZ for this preset (sets
# the eff% ceiling only — run with MMFREE_CLK_MHZ=<rate> against bitstreams
# built at other clocks, e.g. 100 or 250).
CFLAGS += -DBENCH_AWIDTH=8 -DBENCH_XDIM=64 -DBENCH_MAX_N=4096 -DBENCH_MAX_M=32000 -DBENCH_SHAPES_370M=1 -DBENCH_CLK_MHZ=250
endif
ifeq ($(BENCH_PRESET),k26_mmfree370m_a16)
# Signed int16-activation sibling: aWidth=16, xDim=32 (still 512-bit/4-port),
# signed activations (BENCH_SIGNED). Same 370M projection sweep. Must run
# against a bitstream built from the k26_mmfree370m_a16 preset (signedAct=true).
CFLAGS += -DBENCH_AWIDTH=16 -DBENCH_XDIM=32 -DBENCH_MAX_N=4096 -DBENCH_MAX_M=32000 -DBENCH_SHAPES_370M=1 -DBENCH_SIGNED=1 -DBENCH_CLK_MHZ=250
endif

OUT_DIR  = build/$(BENCH_PRESET)
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

# CPU NEON baseline — preset-independent (its own 370M shape table), so it
# lives at build/cpu_bench, not under a preset dir. -O3 + OpenMP for the
# multi-core GEMV; NEON intrinsics are on by default for aarch64. Building on
# an x86 host needs CC=aarch64-linux-gnu-gcc.
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

build:
	@mkdir -p build

clean:
	rm -rf build

.PHONY: all cpu lib test-lib smoke clean
