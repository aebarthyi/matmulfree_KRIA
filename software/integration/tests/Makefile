# Host-side tests for the integration layer. No hardware, no board — these check the
# arithmetic that would otherwise fail SILENTLY on the KRIA (a wrong DMA offset streams
# happily and produces plausible garbage). Correctness of results is gated separately and
# definitively by `make gate` on the board (bit-exact CPU vs FPGA).
#
#   make            # everything
#   make split      # column-tile split plan + readback scatter (C)
#   make packed     # 1.6-bit pack round-trip (Python)
CC       ?= gcc
CFLAGS   ?= -O1 -std=c11 -Wall -Wextra -D_POSIX_C_SOURCE=200809L
SW       := ../..
SUBMOD   := ../../../matmulfreellmCPU/cpp
INCS     := -I$(SW)/include -I../libmmfree -I$(SUBMOD)/include
BUILD    := build

.PHONY: all split packed clean
all: split packed

# test_split_chunks.c #includes mmfree_lib.c to drive the real static chunk math, so it
# needs mmfree_runtime.c linked in for the symbols it references (none are called).
$(BUILD)/test_split_chunks: test_split_chunks.c ../libmmfree/mmfree_lib.c $(SW)/bench/mmfree_runtime.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) $(INCS) test_split_chunks.c $(SW)/bench/mmfree_runtime.c -o $@

split: $(BUILD)/test_split_chunks
	$(BUILD)/test_split_chunks

packed:
	cd .. && PYTHONPATH=. python3 tests/test_packed_roundtrip.py

clean:
	rm -rf $(BUILD)
