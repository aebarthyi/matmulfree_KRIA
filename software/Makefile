# Build natively on the KRIA (Cortex-A53, aarch64). Cross-compile by passing
# CC=aarch64-linux-gnu-gcc from the env if building on a dev host.

CC      ?= gcc
CFLAGS  ?= -Wall -Wextra -O2 -std=c11 -D_POSIX_C_SOURCE=200809L
INCDIRS  = -Iinclude

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

clean:
	rm -rf $(OUT_DIR)

.PHONY: all clean
