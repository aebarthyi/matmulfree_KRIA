# Host-side tests (no hardware) for arithmetic that would fail silently on the board.
#   make            # everything
#   make split      # column-tile split plan + readback scatter (C)
#   make readback   # threaded vs serial readback (C)
#   make packed     # 1.6-bit pack round-trip (Python)
# The CPU packed-weight kernel tests live in the submodule: make -C ../../../matmulfreellmCPU test
CC       ?= gcc
CFLAGS   ?= -O1 -std=c11 -Wall -Wextra -D_POSIX_C_SOURCE=200809L
SW       := ../..
INCS     := -I$(SW)/include -I../libmmfree
BUILD    := build

.PHONY: all split readback packed clean
all: split readback packed

# The C tests #include mmfree_lib.c to reach its statics; mmfree_runtime.c satisfies
# its references (never called).
$(BUILD)/test_split_chunks: test_split_chunks.c ../libmmfree/mmfree_lib.c $(SW)/bench/mmfree_runtime.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) $(INCS) test_split_chunks.c $(SW)/bench/mmfree_runtime.c -o $@

split: $(BUILD)/test_split_chunks
	$(BUILD)/test_split_chunks

# -fopenmp is required, or the threaded readback path compiles away untested.
$(BUILD)/test_readback: test_readback.c ../libmmfree/mmfree_lib.c $(SW)/bench/mmfree_runtime.c
	@mkdir -p $(BUILD)
	$(CC) $(CFLAGS) -fopenmp $(INCS) test_readback.c $(SW)/bench/mmfree_runtime.c -o $@

# Run twice — threaded, then serial — since rb_threads() caches per process.
readback: $(BUILD)/test_readback
	$(BUILD)/test_readback
	@echo "--- same shapes with the serial fallback forced ---"
	MMFREE_RB_THREADS=1 $(BUILD)/test_readback

packed:
	cd .. && PYTHONPATH=. python3 tests/test_packed_roundtrip.py

clean:
	rm -rf $(BUILD)
