#!/usr/bin/env bash
# bench_cflags_ab.sh — A/B the CPU-kernel optimization flags on the board.
#
# WHY THIS IS WORTH MEASURING. At 2.7B packed b=4 the wall clock is only 43% engine and
# 57% host CPU, and just 25% is the actual weight stream — the workload is CPU-bound now,
# not weight-bandwidth-bound. The C++ kernels (bitlinear/rmsnorm/activations + simd.hpp)
# are ~half of wall time and are built by the submodule's CMake, which pins Release to
# -O2 with no -mcpu. Two specific gaps on the KRIA's Cortex-A53:
#   * -O2 in GCC 13 uses -fvect-cost-model=very-cheap, which declines loops with a runtime
#     trip count -- e.g. the scalar RMSNorm scale loop in bitlinear.cpp. -O3 -> `dynamic`.
#   * no -mcpu; the A53 is IN-ORDER dual-issue, so pipeline-tuned scheduling matters far
#     more than on an out-of-order core.
# Both preserve IEEE fp semantics, so the gate must still pass -- that is the point of
# gating each variant rather than assuming.
#
#   cd ~/transfer && sudo ./bench_cflags_ab.sh
#   cd ~/transfer && sudo ./bench_cflags_ab.sh BATCH=6 PRESET=<p>
#
# Each variant is REBUILT from source (a few minutes each), gated, then benched. The
# rebuild is unavoidable: flags are not a file, so the only honest A/B recompiles.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
[ "$(id -u)" -eq 0 ] || { echo "ERROR: run under sudo (gate + /dev/mem): sudo ./bench_cflags_ab.sh" >&2; exit 1; }

PRESET=${PRESET:-k26_mmfree2_7b_a16_b4_dsp_s8_packed}
EXTRA=${EXTRA:---packed-dir $(pwd)/models/ridger/MMfreeLM-2.7B/packed-1p6}
BATCH=${BATCH:-4}
GEN=${GEN:-64}
REPS=${REPS:-3}
GATE_GEN=${GATE_GEN:-4}
for arg in "$@"; do case "$arg" in *=*) eval "${arg%%=*}='${arg#*=}'" ;; esac; done

MANIFEST="presets/$PRESET/preset.env"
[ -f "$MANIFEST" ] || { echo "ERROR: no manifest at $MANIFEST" >&2; exit 1; }

echo "# cflags A/B: preset=$PRESET b=$BATCH"
./deploy_kria.sh "$PRESET" > "results/logs/deploy_$PRESET.log" 2>&1 || {
    echo "!! deploy-hw FAILED"; tail -20 "results/logs/deploy_$PRESET.log"; exit 1; }
grep -E "fclk0|WARN|ERROR" "results/logs/deploy_$PRESET.log" | head -6 | sed 's/^/    /'

# CPU_OPT must be EXPORTED, not passed on the command line. bench_batch.sh shells out to
# `make run`, whose runner-bin prerequisite re-enters the runner Makefile; if CPU_OPT were
# not visible there, the .cpu_opt stamp would differ, the archive would be rebuilt at the
# DEFAULT flags mid-benchmark, and the run would silently measure the wrong binary.
# Exporting makes every nested make agree (the runner Makefile uses CPU_OPT ?=).
run_variant() {
    local tag="$1" opt="$2"
    echo
    echo "############ variant $tag   flags='$opt'"
    # BOTH knobs: CPU_OPT covers the C++ kernels (submodule CMake), OPT covers the runner
    # glue + C driver (fpga_backend's narrowing loops, mmfree_lib's pack scatter). Setting
    # only one would leave ~6-10% of wall time on the old flags and understate the result.
    export CPU_OPT="$opt" OPT="$opt"

    if ! make build > "results/logs/cflags_build_$tag.log" 2>&1; then
        echo "!! BUILD FAILED — see results/logs/cflags_build_$tag.log"
        tail -15 "results/logs/cflags_build_$tag.log"; return 1
    fi
    grep -E "flags ->" "results/logs/cflags_build_$tag.log" | sed 's/^/    /'

    # Bit-exactness first: -O3/-mcpu must not perturb the fp path feeding quant_q510. A
    # gate failure here means the flags changed numerics and the timing below is void.
    echo "  -- gate (CPU == FPGA) --"
    if ! make gate PRESET="$PRESET" ARGS="--gen $GATE_GEN $EXTRA" \
            > "results/logs/cflags_gate_$tag.log" 2>&1; then
        echo "  !! GATE FAILED for $tag — flags changed numerics; NOT timing it"
        grep -aE "checked|GATE|mismatch" "results/logs/cflags_gate_$tag.log" | tail -3 | sed 's/^/      /'
        return 1
    fi
    grep -aE "GATE" "results/logs/cflags_gate_$tag.log" | sed 's/^/    /'

    echo "  -- bench b=$BATCH --"
    ./bench_batch.sh PRESET="$PRESET" BATCHES="$BATCH" GEN="$GEN" REPS="$REPS" \
        POWER=0 TAG="cflags_$tag" EXTRA="$EXTRA" >/dev/null 2>&1 || {
        echo "  !! bench FAILED"; return 1; }
}

# The baseline arm passes -O2 EXPLICITLY rather than an empty string. Since 2026-07-30 the
# Makefile defaults CPU_OPT/OPT to "-O3 -mcpu=cortex-a53", and an exported-but-empty variable
# still counts as DEFINED to make's ?=, so `export CPU_OPT=""` would neither take the default
# nor pass -DMMFREE_OPT_FLAGS — leaving the kernels on CMake's own (now -O3) default and
# quietly turning this into an -O3-vs-O3 comparison.
run_variant o2 "-O2"
run_variant o3 "-O3 -mcpu=cortex-a53"

[ -n "${SUDO_USER:-}" ] && chown -R "$SUDO_USER" results 2>/dev/null

echo
python3 - "$PRESET" <<'PY'
import csv, glob, sys, os
preset = sys.argv[1]
def row(tag):
    f = glob.glob(f"results/{preset}*_cflags_{tag}_summary.csv")
    if not f: return None
    return next(csv.DictReader(open(sorted(f, key=os.path.getmtime)[-1])))
a, b = row("o2"), row("o3")
if not a or not b:
    print("# missing a variant's CSV — check results/ and the logs"); raise SystemExit
print("# %-22s %12s %12s %10s" % ("metric", "-O2 (base)", "-O3+mcpu", "delta"))
for key, label in [("decode_tok_s","decode tok/s"), ("token_latency_ms","token lat ms"),
                   ("op_total_ms","op total ms"), ("bl_total_ms","engine ms")]:
    x, y = float(a[key]), float(b[key])
    print("# %-22s %12.2f %12.2f %9.2f%%" % (label, x, y, (y/x-1)*100))
print("# streams_ok: o2=%s o3=%s" % (a["streams_ok"], b["streams_ok"]))
print()
print("# The engine row should be ~flat — these flags only touch host code. A large engine")
print("# delta means something else moved (fclk, thermal) and the comparison is not clean.")
PY
