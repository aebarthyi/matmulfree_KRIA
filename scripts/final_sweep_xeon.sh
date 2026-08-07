#!/usr/bin/env bash
# final_sweep_xeon.sh — the x86 half of the final report data set.
#
#   ./scripts/final_sweep_xeon.sh                    # everything (~2 h)
#
# Wraps bench_host_cpu.sh to produce the Xeon arm at the same shape as the board arms:
# 3 models x 3 weight encodings x b=1..6, PASSES independent passes, RAPL power on every row.
#
# ── WHY IT SWEEPS THREAD COUNTS ─────────────────────────────────────────────────────
# The optimal OpenMP thread count on this host is MODEL- AND ENCODING-dependent, measured:
# 370M wants 10 (physical cores — it fits the 13.75 MB L3 and is issue-bound, so SMT siblings
# only contend), while 1.3B/2.7B are memory-bound and gain from 20 (SMT hides DRAM latency).
# A single fixed thread count therefore handicaps some rows, and picking per-row winners out
# of an undocumented sweep is what made the previous xeon_best.csv unquotable — it silently
# mixed 10T and 20T rows.
#
# So this runs BOTH and records `omp_threads` on every row. The aggregator then selects the
# better one per (model, encoding) — across the whole batch ladder, so one curve is one
# configuration — and carries the thread count into the output. That turns "best of a sweep"
# from a hidden caveat into a stated method: the Xeon baseline is given its best
# configuration, and the table says which.
#
# ── RAPL ────────────────────────────────────────────────────────────────────────────
# energy_uj is 0400 on stock Ubuntu (PLATYPUS mitigation). Without read access the power
# columns come out EMPTY and this data set is not final. One-time, resets on reboot:
#
#     sudo chmod a+r /sys/class/powercap/intel-rapl:*/energy_uj
#
# This script REFUSES to start without it rather than producing a half-empty CSV.
#
# ── MACHINE MUST BE IDLE ────────────────────────────────────────────────────────────
# bench_host_cpu.sh has a load guard, but note this host is also the Vivado build server:
# a concurrent build makes a CPU sweep measure contention, not the model. Do not start a
# build while this runs.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PASSES="${PASSES:-3}"
THREAD_SET="${THREAD_SET:-10 20}"
MODELS="${MODELS:-ridger/MMfreeLM-370M ridger/MMfreeLM-1.3B ridger/MMfreeLM-2.7B}"
BACKENDS="${BACKENDS:-cpu cpu-2bit cpu-1p6}"
BATCHES="${BATCHES:-1 2 3 4 5 6}"

# ── GEN IS PER MODEL, AND HAS TO BE ─────────────────────────────────────────────────
# decode_tok_s is a rate, so GEN does not change the quantity being measured — only how
# precisely it is estimated. A fixed GEN=64 therefore reads very differently across models
# on this host: 2.7B b=1 is a 1.4 s timed window, but 370M b=1 is 0.34 s, short enough that
# scheduler noise dominates. Measured consequence, GEN=64 over 3 passes: 1.3B and 2.7B agreed
# to 0.09-1.2%, while 370M scattered 2.1-6.0% and tripped the aggregator's 3% outlier flag on
# three cells. These values put every model's b=1 window at >= 2 s. gen_tokens is recorded on
# every row, so the choice is visible in the data rather than assumed.
declare -A GEN_FOR=( [370M]=384 [1.3B]=192 [2.7B]=128 )
GEN_DEFAULT="${GEN:-128}"
gen_for() {   # gen_for <hf-id> -> tokens
    local slug="${1##*-}"                       # ridger/MMfreeLM-1.3B -> 1.3B
    echo "${GEN_FOR[$slug]:-$GEN_DEFAULT}"
}
REPS="${REPS:-3}"
OUT_ROOT="${OUT_ROOT:-$REPO_ROOT/reports/host_cpu/final}"

# Hard-refuse without RAPL: a "final" data set with empty power columns is not final.
ok=0
for d in /sys/class/powercap/intel-rapl:*; do
    [ -r "$d/energy_uj" ] && case "$(cat "$d/name" 2>/dev/null)" in package-*) ok=1 ;; esac
done
if [ "$ok" != "1" ]; then
    cat >&2 <<'EOF'
ERROR: no readable RAPL package counter, so every power column would be empty.

  sudo chmod a+r /sys/class/powercap/intel-rapl:*/energy_uj

(resets on reboot). Re-run this script afterwards. Set ALLOW_NO_POWER=1 to override, but
then the result is NOT the final data set.
EOF
    [ "${ALLOW_NO_POWER:-0}" = "1" ] || exit 1
fi

RUNNER="$REPO_ROOT/software/integration/fpga_runner/build/mmfree-cli-fpga"
[ -x "$RUNNER" ] || { echo "ERROR: build the runner first: make -C software/integration/fpga_runner" >&2; exit 1; }

# ── The binary must use the widest SIMD this host has ───────────────────────────────
# The runner's Makefile defaults x86 to MMFREE_SIMD=X86 (-mavx2), and that default is right
# for a portable build but WRONG for a baseline measurement on an AVX-512 part: measured on
# this W-2155, 370M 2-bit b=6 reads 159 tok/s built AVX2 against 206 built AVX-512, a 29%
# handicap. Quoting an FPGA speedup against a hobbled Xeon would overstate it by that much,
# silently, with nothing in the CSV to show for it. So: if the host advertises avx512f and
# the binary contains no zmm register use, refuse.
if grep -qw avx512f /proc/cpuinfo; then
    # ASK THE BINARY, do not count instructions. Counting zmm anywhere in the file was too
    # weak and passed the exact build it existed to reject: on 2026-08-06 a plain `make`
    # relinked the runner with the default -mavx2, and the file still contained 167 zmm from
    # libmmfreecpu.a (which was not rebuilt) while the header-only packed-ternary kernels
    # compiled into main_fpga.o had dropped to 256-bit. 167 != 0, so the guard said fine, and
    # the rows that followed read 24-31% low on exactly those kernels.
    #
    # The runner now self-reports from the macros tq1.hpp actually switches on, so a probe run
    # is authoritative where an instruction count is circumstantial. The banner prints before
    # any weights are loaded or devices opened, so a deliberately bogus blob is enough to get
    # it — and stderr/exit status are irrelevant, we only want that line.
    simd_line=$("$RUNNER" 0 0 - - - - --backend cpu --blob /nonexistent-probe 2>/dev/null \
                | grep -m1 '^simd backend:' || true)
    case "$simd_line" in
        *avx512*) echo "# SIMD: ${simd_line#simd backend: }" ;;
        "")
            echo "WARNING: could not read the SIMD banner from $RUNNER — probe produced no" >&2
            echo "         'simd backend:' line. Falling back to an instruction count." >&2
            zmm=$(objdump -d "$RUNNER" 2>/dev/null | grep -c zmm || true)
            echo "# SIMD: unverified, $zmm zmm instructions present (count is NOT proof)" >&2
            [ "${ALLOW_NARROW_SIMD:-0}" = "1" ] || exit 1
            ;;
        *)
            cat >&2 <<EOF
ERROR: this host supports AVX-512 but the runner reports:
           $simd_line

That is a 256-bit build of the packed-ternary kernels. It understates the Xeon baseline by
~29% and would inflate every FPGA-vs-Xeon comparison drawn from it. Rebuild — and note that
a plain \`make\` here defaults to -mavx2, so the SIMD variable is not optional:

  make -C software/integration/fpga_runner clean
  MMFREE_SIMD=X86_512 make -C software/integration/fpga_runner

Set ALLOW_NARROW_SIMD=1 to override, but then this is NOT the final data set.
EOF
            [ "${ALLOW_NARROW_SIMD:-0}" = "1" ] || exit 1
            ;;
    esac
fi

# ── The binary must not CHANGE mid-sweep ────────────────────────────────────────────
# The check above runs once, at startup, which is where it was blind: on 2026-08-06 an
# unrelated `make` in the runner directory relinked this binary three hours into a sweep,
# with the Makefile's default -mavx2 rather than X86_512. The two run-groups that followed
# read 24-31% low on the packed-ternary arms (int8 was untouched — it has no AVX-512 path,
# so the int8 column looked perfectly healthy) and had to be thrown away and redone.
# Nothing in the CSV or the per-run banner showed it: the runner's "simd backend:" line
# reported the ARCH family, identical before and after.
#
# So: fingerprint the binary now and re-check before every run-group. Abort rather than
# warn — the runs already completed are still good, and the script is idempotent, so
# stopping costs only the group that has not started yet.
BIN_FINGERPRINT=$(stat -c '%Y %s' "$RUNNER")
assert_binary_unchanged() {
    local now; now=$(stat -c '%Y %s' "$RUNNER")
    [ "$now" = "$BIN_FINGERPRINT" ] && return 0
    cat >&2 <<EOF

ERROR: $RUNNER changed during the sweep
       (was "$BIN_FINGERPRINT", now "$now" — mtime size).

Something rebuilt it while measurements were in flight, so runs before and after this point
are not the same binary and cannot go in one data set. Every group already written is
untouched and still valid; this aborts before starting another.

  1. rebuild deliberately:  make -C software/integration/fpga_runner clean
                            MMFREE_SIMD=X86_512 make -C software/integration/fpga_runner
  2. delete any group whose runs straddle the rebuild
  3. re-run this script — it skips what is complete and redoes only the gaps
EOF
    exit 1
}

echo "############################################################"
echo "# Xeon final sweep: $PASSES passes x {$THREAD_SET} threads"
echo "#   models   : $MODELS"
echo "#   backends : $BACKENDS"
echo "#   batches  : $BATCHES   reps=$REPS   gen: 370M=$(gen_for x-370M) 1.3B=$(gen_for x-1.3B) 2.7B=$(gen_for x-2.7B)"
echo "#   out      : $OUT_ROOT"
echo "############################################################"

# One sweep PER MODEL now, since GEN differs per model and bench_host_cpu.sh takes a single
# GEN for whatever model list it is given. Output nests one level deeper —
# <root>/<slug>/t<threads>_p<pass>/ — and final_dataset.py globs recursively for t*_p*.
count() { set -- $1; echo $#; }
EXPECT_ROWS=$(( $(count "$BACKENDS") * $(count "$BATCHES") ))

# bench_host_cpu.sh refuses to start above MAX_LOAD (default nproc/4) so it never measures
# contention instead of the model. That guard is right, but it also fires on OUR OWN residual
# load: a pass that just finished saturating 10-20 threads leaves the 1-min average around 10
# against a threshold of 5, so every pass after the first was refused instantly and the run
# came back with one pass of six. Wait for the machine to actually settle rather than
# weakening the guard — a real Vivado build will still block, which is the point.
MAX_LOAD_LOCAL="${MAX_LOAD:-$(( $(nproc) / 4 ))}"
settle() {
    local waited=0
    while [ "$MAX_LOAD_LOCAL" != "0" ]; do
        local l1; l1=$(awk '{printf "%d", $1 + 0.5}' /proc/loadavg)
        [ "$l1" -le "$MAX_LOAD_LOCAL" ] && { [ "$waited" -gt 0 ] && echo "    settled after ${waited}s (load $(awk '{print $1}' /proc/loadavg))"; return 0; }
        [ "$waited" -ge "${SETTLE_MAX:-900}" ] && { echo "    WARNING: load still $(awk '{print $1}' /proc/loadavg) after ${waited}s — starting anyway"; return 0; }
        [ "$waited" = 0 ] && echo "    waiting for load to fall below $MAX_LOAD_LOCAL (now $(awk '{print $1}' /proc/loadavg))..."
        sleep 20; waited=$((waited + 20))
    done
}

t0=$(date +%s); failed=""
for model in $MODELS; do
    slug="${model##*-}"
    g=$(gen_for "$model")
    for pass in $(seq 1 "$PASSES"); do
        for th in $THREAD_SET; do
            out="$OUT_ROOT/$slug/t${th}_p${pass}"
            # Idempotent: a complete pass is left alone, so re-running after a partial
            # failure fills only the gaps instead of redoing hours of work.
            if [ -f "$out/host_cpu_sweep.csv" ] && \
               [ "$(( $(wc -l < "$out/host_cpu_sweep.csv") - 1 ))" -ge "$EXPECT_ROWS" ]; then
                echo "=== $slug pass $pass, ${th}T — already complete ($EXPECT_ROWS rows), skipping"
                continue
            fi
            rm -rf "$out"          # partial output would append and duplicate rows
            echo
            echo "=== [$(date -Is)] $slug pass $pass, ${th} threads, gen=$g -> $out ==="
            assert_binary_unchanged
            settle
            OMP_THREADS="$th" MODELS="$model" BACKENDS="$BACKENDS" BATCHES="$BATCHES" \
            GEN="$g" REPS="$REPS" OUT="$out" \
                ./scripts/bench_host_cpu.sh || failed="$failed ${slug}/t${th}p${pass}"
        done
    done
done

echo
echo "############################################################"
echo "# DONE in $(( ($(date +%s) - t0) / 60 )) min"
echo "############################################################"
echo "Per-pass CSVs: $OUT_ROOT/t*_p*/host_cpu_sweep.csv"
echo "Aggregate with: ./scripts/final_dataset.py"
[ -n "$failed" ] && { echo "# FAILURES:$failed"; exit 1; }
echo "# all passes complete"
