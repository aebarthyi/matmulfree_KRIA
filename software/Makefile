# Bench harness. Build natively on the KRIA (aarch64), or cross-compile with
# CC=aarch64-linux-gnu-gcc.

CC      ?= gcc
CFLAGS  ?= -Wall -Wextra -O2 -std=c11 -D_POSIX_C_SOURCE=200809L
INCDIRS  = -Iinclude

# Geometry is read at runtime from MMFREE_* env (exported from the preset manifest), so
# one binary serves every preset; it must match the loaded bitstream or the core wedges.
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

clean:
	rm -rf build

.PHONY: all clean
