# mmfree-cli-fpga — matmulfreellmCPU with ternary projections on the KRIA engine.
#
# Links three pieces:
#   1. libmmfreecpu.a   — the C++ model (submodule, via its CMake)
#   2. mmfree_runtime.o + mmfree_lib.o — the C engine driver
#   3. the runner       — main_fpga.cpp + fpga_backend.cpp + manifest.cpp
#
# Build natively on the KRIA (aarch64); an x86 build is a compile check only. SIMD is
# picked from the host arch; override with MMFREE_SIMD=ARM|X86|X86_512|SCALAR.
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

# ARCH picks only the DEFAULT backend; MMFREE_SIMD may override it.
ARCH := $(shell uname -m)
ifeq ($(ARCH),aarch64)
  MMFREE_SIMD ?= ARM
else ifneq (,$(filter $(ARCH),x86_64 amd64))
  MMFREE_SIMD ?= X86
else
  MMFREE_SIMD ?= SCALAR
endif
# Defines follow MMFREE_SIMD (possibly overridden), NOT ARCH, so the library and the runner
# glue always agree.
ifeq ($(MMFREE_SIMD),ARM)
  SIMD_DEF   := -DMMFREE_ARCH_ARM
  SIMD_FLAGS :=
else ifeq ($(MMFREE_SIMD),X86)
  SIMD_DEF   := -DMMFREE_ARCH_X86
  SIMD_FLAGS := -mavx2 -mfma
else ifeq ($(MMFREE_SIMD),X86_512)
  # AVX-512 ternary-dot paths; SIGILLs on hosts without AVX-512, so opt-in only.
  SIMD_DEF   := -DMMFREE_ARCH_X86
  SIMD_FLAGS := -mavx512f -mavx512bw -mavx512dq -mavx512vl -mfma
else
  SIMD_DEF   :=
  SIMD_FLAGS :=
endif

# Default optimization for BOTH the C++ kernels (CPU_OPT -> submodule MMFREE_OPT_FLAGS) and
# the runner glue + C driver (OPT). aarch64 here means the K26's Cortex-A53.
# NEVER add -ffast-math: it changes quant_q510's int16s and breaks the CPU==FPGA gate.
ifeq ($(ARCH),aarch64)
  DEFAULT_OPT := -O3 -mcpu=cortex-a53
else
  DEFAULT_OPT := -O3
endif

BUILD    := build
INCS     := -I$(SUBMOD)/include -I$(SW)/include -I$(LIBMMFREE)
CXXFLAGS ?= -O2 -std=c++17 -Wall -Wextra -fopenmp
# -fopenmp on the C side enables mmfree_lib.c's threaded readback (else it stays serial).
CFLAGS   ?= -O2 -std=c11 -Wall -Wextra -D_POSIX_C_SOURCE=200809L -fopenmp
# OPT is appended to CXXFLAGS/CFLAGS (GCC takes the last -O).
OPT      ?= $(DEFAULT_OPT)

CPULIB   := $(BUILD)/libmmfreecpu.a
RUNNER   := $(BUILD)/mmfree-cli-fpga
CXX_SRCS := main_fpga.cpp fpga_backend.cpp manifest.cpp
C_OBJS   := $(BUILD)/mmfree_runtime.o $(BUILD)/mmfree_lib.o

.PHONY: all clean FORCE
all: $(RUNNER)
FORCE:

$(BUILD):
	@mkdir -p $(BUILD)

# 1. the C++ model library, built by the submodule's own CMake. Depends on the submodule
# sources so an edit or bump never links a stale archive.
CPU_SRCS := $(shell find $(SUBMOD)/src $(SUBMOD)/include -type f \( -name '*.cpp' -o -name '*.hpp' \) 2>/dev/null)
# CPU_OPT overrides the submodule's Release flags (no -ffast-math, see above).
CPU_OPT ?= $(DEFAULT_OPT)
# Flags are not a file: record them in a stamp so changing CPU_OPT/OPT rebuilds.
CPU_OPT_STAMP := $(BUILD)/.cpu_opt
# FORCE runs the recipe every time, but it only rewrites the stamp when the value changes.
$(CPU_OPT_STAMP): FORCE | $(BUILD)
	@[ "$$(cat $@ 2>/dev/null)" = "$(CPU_OPT)|$(OPT)" ] || { \
	    printf '%s' '$(CPU_OPT)|$(OPT)' > $@; \
	    echo "# flags -> CPU_OPT='$(CPU_OPT)' OPT='$(OPT)' (rebuilding)"; }

$(CPULIB): $(CPU_SRCS) $(SUBMOD)/CMakeLists.txt $(CPU_OPT_STAMP) | $(BUILD)
	cmake -S $(SUBMOD) -B $(BUILD)/cpp -DMMFREE_SIMD=$(MMFREE_SIMD) -DCMAKE_BUILD_TYPE=Release \
	    $(if $(strip $(CPU_OPT)),-DMMFREE_OPT_FLAGS="$(CPU_OPT)",) >/dev/null
	cmake --build $(BUILD)/cpp --target mmfreecpu -j
	cp $(BUILD)/cpp/libmmfreecpu.a $@

# 2. the C engine driver.
$(BUILD)/mmfree_runtime.o: $(SW)/bench/mmfree_runtime.c $(CPU_OPT_STAMP) | $(BUILD)
	$(CC) $(CFLAGS) $(OPT) $(INCS) -c $< -o $@
$(BUILD)/mmfree_lib.o: $(LIBMMFREE)/mmfree_lib.c $(CPU_OPT_STAMP) | $(BUILD)
	$(CC) $(CFLAGS) $(OPT) $(INCS) -c $< -o $@

# 3. link the runner.
$(RUNNER): $(CXX_SRCS) $(CPULIB) $(C_OBJS) $(CPU_OPT_STAMP)
	$(CXX) $(CXXFLAGS) $(OPT) $(SIMD_DEF) $(SIMD_FLAGS) $(INCS) \
	    $(CXX_SRCS) $(C_OBJS) $(CPULIB) -fopenmp -o $@

clean:
	rm -rf $(BUILD)
