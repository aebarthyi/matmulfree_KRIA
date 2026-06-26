# mmfree-cli-fpga — runs matmulfreellmCPU with ternary projections on the KRIA engine.
#
# Builds three pieces and links them:
#   1. libmmfreecpu.a   — the C++ model (submodule, via its CMake; DI: FPGA-agnostic)
#   2. mmfree_runtime.o + mmfree_lib.o — the C engine driver (software/, same as `make lib`)
#   3. the runner       — main_fpga.cpp + fpga_backend.cpp + manifest.cpp
#
# Build natively on the KRIA (aarch64). An x86 host build is a compile/type check only
# (the engine driver opens /dev/mem + udmabuf at run time). SIMD is auto-selected from
# the host arch; override with MMFREE_SIMD=ARM|X86|SCALAR.
#
#   make                                  # build build/mmfree-cli-fpga
#   sudo ./build/mmfree-cli-fpga 0xA0010000 0xA0000000 /dev/uio4 \
#        /dev/udmabuf-act /dev/udmabuf-wt /dev/udmabuf-out \
#        --blob model.mmfree --packed-dir packed --backend both

CXX ?= g++
CC  ?= gcc

SW        := ../..
SUBMOD    := ../../../matmulfreellmCPU/cpp
LIBMMFREE := $(SW)/integration/libmmfree

ARCH := $(shell uname -m)
ifeq ($(ARCH),aarch64)
  MMFREE_SIMD ?= ARM
  SIMD_DEF    := -DMMFREE_ARCH_ARM
  SIMD_FLAGS  :=
else ifneq (,$(filter $(ARCH),x86_64 amd64))
  MMFREE_SIMD ?= X86
  SIMD_DEF    := -DMMFREE_ARCH_X86
  SIMD_FLAGS  := -mavx2 -mfma
else
  MMFREE_SIMD ?= SCALAR
  SIMD_DEF    :=
  SIMD_FLAGS  :=
endif

BUILD    := build
INCS     := -I$(SUBMOD)/include -I$(SW)/include -I$(LIBMMFREE)
CXXFLAGS ?= -O2 -std=c++17 -Wall -Wextra -fopenmp
CFLAGS   ?= -O2 -std=c11 -Wall -Wextra -D_POSIX_C_SOURCE=200809L

CPULIB   := $(BUILD)/libmmfreecpu.a
RUNNER   := $(BUILD)/mmfree-cli-fpga
CXX_SRCS := main_fpga.cpp fpga_backend.cpp manifest.cpp
C_OBJS   := $(BUILD)/mmfree_runtime.o $(BUILD)/mmfree_lib.o

.PHONY: all clean
all: $(RUNNER)

$(BUILD):
	@mkdir -p $(BUILD)

# 1. the C++ model library, built by the submodule's own CMake.
$(CPULIB): | $(BUILD)
	cmake -S $(SUBMOD) -B $(BUILD)/cpp -DMMFREE_SIMD=$(MMFREE_SIMD) -DCMAKE_BUILD_TYPE=Release >/dev/null
	cmake --build $(BUILD)/cpp --target mmfreecpu -j
	cp $(BUILD)/cpp/libmmfreecpu.a $@

# 2. the C engine driver (mirror software/Makefile `lib`).
$(BUILD)/mmfree_runtime.o: $(SW)/bench/mmfree_runtime.c | $(BUILD)
	$(CC) $(CFLAGS) $(INCS) -c $< -o $@
$(BUILD)/mmfree_lib.o: $(LIBMMFREE)/mmfree_lib.c | $(BUILD)
	$(CC) $(CFLAGS) $(INCS) -c $< -o $@

# 3. link the runner.
$(RUNNER): $(CXX_SRCS) $(CPULIB) $(C_OBJS)
	$(CXX) $(CXXFLAGS) $(SIMD_DEF) $(SIMD_FLAGS) $(INCS) \
	    $(CXX_SRCS) $(C_OBJS) $(CPULIB) -fopenmp -o $@

clean:
	rm -rf $(BUILD)
