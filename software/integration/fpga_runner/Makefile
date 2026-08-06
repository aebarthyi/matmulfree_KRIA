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

# ARCH picks only the DEFAULT backend; MMFREE_SIMD may override it.
ARCH := $(shell uname -m)
ifeq ($(ARCH),aarch64)
  MMFREE_SIMD ?= ARM
else ifneq (,$(filter $(ARCH),x86_64 amd64))
  MMFREE_SIMD ?= X86
else
  MMFREE_SIMD ?= SCALAR
endif
# Derive the defines from MMFREE_SIMD (the possibly-overridden value), NOT from ARCH. These
# used to be set in the ARCH branches above, which meant MMFREE_SIMD=SCALAR on aarch64 built
# the LIBRARY scalar (cmake sees SCALAR) while the runner glue was still compiled with
# -DMMFREE_ARCH_ARM -- an incoherent mix, produced silently. A scalar libmmfreecpu.a costs
# ~12% of wall time on this workload (elementwise -43%, rmsnorm -41% when NEON is restored),
# so this is not a cosmetic inconsistency.
ifeq ($(MMFREE_SIMD),ARM)
  SIMD_DEF   := -DMMFREE_ARCH_ARM
  SIMD_FLAGS :=
else ifeq ($(MMFREE_SIMD),X86)
  SIMD_DEF   := -DMMFREE_ARCH_X86
  SIMD_FLAGS := -mavx2 -mfma
else ifeq ($(MMFREE_SIMD),X86_512)
  # AVX-512: enables tq1.hpp's 512-bit ternary-dot paths (MMFREE_AVX512). Host-specific -- the
  # binary will SIGILL on an x86_64 without AVX-512, so this is opt-in, never the default.
  SIMD_DEF   := -DMMFREE_ARCH_X86
  SIMD_FLAGS := -mavx512f -mavx512bw -mavx512dq -mavx512vl -mfma
else
  SIMD_DEF   :=
  SIMD_FLAGS :=
endif

# Default optimization flags, applied to BOTH the C++ kernels (CPU_OPT -> the submodule's
# MMFREE_OPT_FLAGS) and the runner glue + C driver (OPT). Measured on the KRIA at 2.7B packed
# b=4: -O3 -mcpu=cortex-a53 cut host CPU time 12.8% and lifted decode 4.6% (29.56 -> 30.91
# tok/s) with the CPU==FPGA gate passing — the CPU side is ~57% of wall time on the FPGA path,
# so this is one of the larger single wins available. -mcpu goes here rather than in the
# submodule because it is target-specific: aarch64 here always means the K26's Cortex-A53.
# NEVER add -ffast-math: it changes quant_q510's int16s and so the engine's accumulators,
# breaking the gate. Override per-build (e.g. CPU_OPT=-O2 to reproduce the old baseline).
ifeq ($(ARCH),aarch64)
  DEFAULT_OPT := -O3 -mcpu=cortex-a53
else
  DEFAULT_OPT := -O3
endif

BUILD    := build
INCS     := -I$(SUBMOD)/include -I$(SW)/include -I$(LIBMMFREE)
CXXFLAGS ?= -O2 -std=c++17 -Wall -Wextra -fopenmp
# -fopenmp on the C side too: mmfree_lib.c's readback loop can be threaded across the four
# A53s (MMFREE_RB_THREADS). Without the flag the pragma is ignored and readback stays
# single-threaded — it still builds and is still correct, just slower.
CFLAGS   ?= -O2 -std=c11 -Wall -Wextra -D_POSIX_C_SOURCE=200809L -fopenmp
# OPT is APPENDED to CXXFLAGS/CFLAGS (GCC takes the last -O), so it raises the optimization
# level for the runner glue + C engine driver without having to restate the whole flag set.
# This is not just cosmetic: fpga_backend.cpp holds the int32->int16 narrowing loops and
# mmfree_lib.c holds pack_act_port0, the scalar strided activation scatter that measures
# ~6% of wall time. Pair it with CPU_OPT (which covers the C++ kernels) — see below.
#   make CPU_OPT=-O2 OPT=-O2                 # reproduce the pre-2026-07-30 baseline
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

# 1. the C++ model library, built by the submodule's own CMake.
# Depend on the submodule's actual sources, not just the build dir: without this the .a is
# treated as up to date forever, so an edit to the model (or a submodule bump) links the
# STALE archive — which shows up as a baffling undefined-reference to the function you just
# changed, or worse, silently benchmarks the old code. The inner cmake --build still does
# its own incremental check, so this is a no-op when nothing changed.
CPU_SRCS := $(shell find $(SUBMOD)/src $(SUBMOD)/include -type f \( -name '*.cpp' -o -name '*.hpp' \) 2>/dev/null)
# CPU_OPT overrides the submodule's Release optimization flags (default there: -O2). These
# kernels are ~50% of FPGA-offload wall time, so this is a real tuning knob:
#   make CPU_OPT=-O2                         # reproduce the pre-2026-07-30 baseline
# NEVER put -ffast-math here: it changes quant_q510's int16s and so the engine's int32
# accumulators, which breaks the CPU==FPGA gate.
CPU_OPT ?= $(DEFAULT_OPT)
# Flags are not a file, so a CPU_OPT change alone would leave the .a "up to date" and
# silently benchmark the OLD flags — the same stale-archive trap the CPU_SRCS dependency
# above guards against. Record the flags in a stamp and depend on it, so changing them
# reconfigures and rebuilds.
CPU_OPT_STAMP := $(BUILD)/.cpu_opt
# FORCE (not .PHONY on the stamp): the recipe is consulted on every run, but it only WRITES
# when the value differs, so the stamp's mtime — and hence the .a rebuild — is triggered by a
# real flag change and not by every make invocation.
$(CPU_OPT_STAMP): FORCE | $(BUILD)
	@[ "$$(cat $@ 2>/dev/null)" = "$(CPU_OPT)|$(OPT)" ] || { \
	    printf '%s' '$(CPU_OPT)|$(OPT)' > $@; \
	    echo "# flags -> CPU_OPT='$(CPU_OPT)' OPT='$(OPT)' (rebuilding)"; }

$(CPULIB): $(CPU_SRCS) $(SUBMOD)/CMakeLists.txt $(CPU_OPT_STAMP) | $(BUILD)
	cmake -S $(SUBMOD) -B $(BUILD)/cpp -DMMFREE_SIMD=$(MMFREE_SIMD) -DCMAKE_BUILD_TYPE=Release \
	    $(if $(strip $(CPU_OPT)),-DMMFREE_OPT_FLAGS="$(CPU_OPT)",) >/dev/null
	cmake --build $(BUILD)/cpp --target mmfreecpu -j
	cp $(BUILD)/cpp/libmmfreecpu.a $@

# 2. the C engine driver (mirror software/Makefile `lib`).
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
