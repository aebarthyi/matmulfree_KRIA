# Host-side tests for the integration layer. No hardware, no board — these check the
# arithmetic that would otherwise fail SILENTLY on the KRIA (a wrong DMA offset streams
# happily and produces plausible garbage). Correctness of results is gated separately and
# definitively by `make gate` on the board (bit-exact CPU vs FPGA).
#
#   make            # everything
#   make split      # column-tile split plan + readback scatter (C)
#   make packed     # 1.6-bit pack round-trip (Python)
#
# The CPU-side packed-weight kernels (TQ1_0 1.6-bit / 2-bit) moved to the submodule, which
# is where the CPU engine lives — this repo keeps the FPGA side. Their exhaustive test runs
# from there:  make -C ../../../matmulfreellmCPU test      (or: ctest -R tq1)
CC       ?= gcc
CFLAGS   ?= -O1 -std=c11 -Wall -Wextra -D_POSIX_C_SOURCE=200809L
SW       := ../..
INCS     := -I$(SW)/include -I../libmmfree
BUILD    := build

.PHONY: all split readback packed clean
all: split readback packed

# test_split_chunks.c #includes mmfree_lib.c to drive the real static chunk math, so it
# needs mmfree_runtime.c linked in for the symbols it references (none are called).
$(BUILD)/test_split_chunks: test_split_chunks.c ../libmmfree/mmfree_lib.c $(SW)/bench/mmfree_runtime.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) $(INCS) test_split_chunks.c $(SW)/bench/mmfree_runtime.c -o $@

split: $(BUILD)/test_split_chunks
	$(BUILD)/test_split_chunks

# Same #include-the-real-source trick, but -fopenmp: without it the threaded readback path
# compiles away and the test would pass without ever having run the code it exists to check.
$(BUILD)/test_readback: test_readback.c ../libmmfree/mmfree_lib.c $(SW)/bench/mmfree_runtime.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -fopenmp $(INCS) test_readback.c $(SW)/bench/mmfree_runtime.c -o $@

# Twice: once threaded (the test forces MMFREE_RB_THREADS=4 / MIN_ELEMS=1 itself) and once
# with the kill switch on, because a knob that disables a fast path is only useful if the
# slow path it falls back to is also right — and rb_threads() caches, so one process cannot
# exercise both.
readback: $(BUILD)/test_readback
	$(BUILD)/test_readback
	@echo "--- same shapes with the serial fallback forced ---"
	MMFREE_RB_THREADS=1 $(BUILD)/test_readback

packed:
	cd .. && PYTHONPATH=. python3 tests/test_packed_roundtrip.py

clean:
	rm -rf $(BUILD)
