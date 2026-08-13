# Build natively on the KRIA (Cortex-A53, aarch64). Cross-compile by passing
# CC=aarch64-linux-gnu-gcc from the env if building on a dev host.
#
# Lean `main` builds the bench harness. The board smoke test,
# the libmmfree helper unit test, and the encode_sanity host check live on the
# `validation` branch (with the rest of the test harness).

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
# per-preset build dirs that isolated them) are gone.
OUT_DIR  = build
BIN      = $(OUT_DIR)/bench

BENCH_OBJS = \
    $(OUT_DIR)/bench.o \
    $(OUT_DIR)/mmfree_runtime.o

all: $(BIN)

$(OUT_DIR):
	@mkdir -p $(OUT_DIR)

$(OUT_DIR)/%.o: bench/%.c | $(OUT_DIR)
	$(CC) $(CFLAGS) $(INCDIRS) -c $< -o $@

$(BIN): $(BENCH_OBJS)
	$(CC) $(CFLAGS) -o $@ $^

# (The hand-written A53 NEON baseline, bench/cpu_bench.c, was dropped: the CPU
# reference is now the full engine in the matmulfreellmCPU submodule, which is what
# scripts/bench_host_cpu.sh and the final sweeps actually measure.)

# (The libmmfree.so target was dropped with the Python bridge that ctypes-loaded it —
# integration/libmmfree/mmfree_lib.c is now consumed only by the C++ fpga_runner, which
# compiles it directly. See software/integration/fpga_runner/Makefile.)

clean:
	rm -rf build

.PHONY: all clean
