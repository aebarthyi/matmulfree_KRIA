# Host-side tests for the integration layer. No hardware, no board — these check the
# arithmetic that would otherwise fail SILENTLY on the KRIA (a wrong DMA offset streams
# happily and produces plausible garbage). Correctness of results is gated separately and
# definitively by `make gate` on the board (bit-exact CPU vs FPGA).
#
#   make            # everything
#   make split      # column-tile split plan + readback scatter (C)
#   make packed     # 1.6-bit pack round-trip (Python)
#   make tq1        # CPU-side TQ1_0 1.6-bit weights: exhaustive + bit-exact vs int8 (C++)
CC       ?= gcc
CXX      ?= g++
CFLAGS   ?= -O1 -std=c11 -Wall -Wextra -D_POSIX_C_SOURCE=200809L
# -O2 for the C++ test: the SIMD kernels under test are only meaningful optimized, and the
# exactness claim must hold at the optimization level the runner actually ships with.
CXXFLAGS ?= -O2 -std=c++17 -Wall -Wextra
SW       := ../..
SUBMOD   := ../../../matmulfreellmCPU/cpp
INCS     := -I$(SW)/include -I../libmmfree -I$(SUBMOD)/include
BUILD    := build

# Same SIMD selection the submodule's CMakeLists does, so this test exercises the SAME
# extraction path the runner will: AVX2 on the build server, NEON on the KRIA.
UNAME_M  := $(shell uname -m)
ifneq (,$(filter $(UNAME_M),x86_64 amd64))
  # MMFREE_SIMD=X86_512 exercises the AVX-512 ternary-dot paths instead of AVX2. The
  # exhaustive checks in test_tq1.cpp are the gate for those paths being bit-exact.
  ifeq ($(MMFREE_SIMD),X86_512)
    SIMD_FLAGS := -DMMFREE_ARCH_X86 -mavx512f -mavx512bw -mavx512dq -mavx512vl -mfma
  else
    SIMD_FLAGS := -DMMFREE_ARCH_X86 -mavx2 -mfma
  endif
else ifneq (,$(filter $(UNAME_M),aarch64 arm64))
  SIMD_FLAGS := -DMMFREE_ARCH_ARM -mcpu=cortex-a53
else
  SIMD_FLAGS :=
endif

.PHONY: all split readback packed tq1 clean
all: split readback packed tq1

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

readback: $(BUILD)/test_readback
	$(BUILD)/test_readback

packed:
	cd .. && PYTHONPATH=. python3 tests/test_packed_roundtrip.py

# Header-only kernels (tq1.hpp) + OpenMP, because Tq1Backend packs rows with parallel_rows
# and the matmul loops are omp-parallel — same as the runner.
$(BUILD)/test_tq1: test_tq1.cpp $(SUBMOD)/include/mmfree/tq1.hpp
	@mkdir -p $(BUILD)
	$(CXX) $(CXXFLAGS) $(SIMD_FLAGS) -fopenmp $(INCS) test_tq1.cpp -o $@

tq1: $(BUILD)/test_tq1
	$(BUILD)/test_tq1

clean:
	rm -rf $(BUILD)
