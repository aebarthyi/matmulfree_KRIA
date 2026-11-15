#!/usr/bin/env bash
# final_sweep_xeon.sh — the x86 half of the final report data set.
#
#   ./scripts/final_sweep_xeon.sh                    # everything (~8-10 h)
#
# Wraps bench_host_cpu.sh: 3 models x 3 encodings x b=1..8, PASSES passes, RAPL power on every
# row, at every thread count in THREAD_SET. The best count is model/encoding-dependent, so
# final_dataset.py picks one per (model, encoding) across the whole ladder and records it.
# Needs readable RAPL (refuses without it; resets on reboot):
#     sudo chmod a+r /sys/class/powercap/intel-rapl:*/energy_uj
# This host is also the Vivado build server: do not build while this runs.
set -uo pipefail

# /etc/profile.d/nec-ve.sh (NEC Vector Engine, installed 2026-09-03) puts the VE's own nproc,
# lscpu, ps, free, mpstat... ahead of /usr/bin. They describe the VE card (8 cores), not this
# host, and its ps aborts. Drop them for this script and everything it runs.
PATH=$(printf '%s' "$PATH" | tr ':' '\n' | grep -v '^/opt/nec/ve/' | paste -sd: -)

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# 5 passes, matching the board arms (with 3, one perturbed pass still moves the median).
PASSES="${PASSES:-5}"
THREAD_SET="${THREAD_SET:-10 20}"
MODELS="${MODELS:-ridger/MMfreeLM-370M ridger/MMfreeLM-1.3B ridger/MMfreeLM-2.7B}"
BACKENDS="${BACKENDS:-cpu cpu-2bit cpu-1p6}"
BATCHES="${BATCHES:-1 2 3 4 5 6 7 8}"

# GEN per model so every model's b=1 timed window is >= 2 s (shorter is scheduler noise).
declare -A GEN_FOR=( [370M]=384 [1.3B]=192 [2.7B]=128 )
GEN_DEFAULT="${GEN:-128}"
gen_for() {   # gen_for <hf-id> -> tokens
    local slug="${1##*-}"                       # ridger/MMfreeLM-1.3B -> 1.3B
    echo "${GEN_FOR[$slug]:-$GEN_DEFAULT}"
}
REPS="${REPS:-3}"
OUT_ROOT="${OUT_ROOT:-$REPO_ROOT/reports/host_cpu/final}"
# Prompt as comma-separated token ids (--ids). Empty = the runner's fixed 8-token pseudo-prompt.
# Use the SAME ids as the board sweep (final_sweep_board.sh PROMPT_IDS) so prefill is identical.
PROMPT_IDS="${PROMPT_IDS:-}"
if [ -n "$PROMPT_IDS" ]; then
    EXPECT_PROMPT=$(echo "$PROMPT_IDS" | tr ',' '\n' | grep -c .)
else
    EXPECT_PROMPT=8
fi

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
# The runner's x86 default is -mavx2, which understates an AVX-512 host by ~29%: refuse it.
if grep -qw avx512f /proc/cpuinfo; then
    # Ask the binary (its self-reported SIMD banner) rather than counting zmm: a stale
    # libmmfreecpu.a can carry zmm while the header-only kernels were rebuilt 256-bit. The
    # banner prints before anything is loaded, so a bogus blob is enough to get it.
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
# A rebuild mid-sweep (e.g. an unrelated `make` relinking with -mavx2) is invisible in the CSV,
# so fingerprint the binary and re-check before every run-group. Abort: the script is
# idempotent and completed groups stay valid.
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

# ── Existing output must be on the SAME ladder ───────────────────────────────────────
# Complete pass dirs are skipped, so a ladder change would leave passes on different ladders
# while the thread-count winner is chosen across the whole ladder. Refuse instead.
stale=""; stale_prompt=""
while IFS= read -r f; do
    [ -n "$f" ] || continue
    got=$(awk -F, 'NR==1{for(i=1;i<=NF;i++) if($i=="batch") c=i; next} {print $c}' "$f" \
          | sort -n | uniq | tr '\n' ' ')
    [ "$(echo $got)" = "$(echo $BATCHES)" ] || stale="$stale $(dirname "${f#$OUT_ROOT/}")[$(echo $got)]"
    # Complete passes are skipped below, so one at another prompt length would be kept silently.
    pt=$(awk -F, 'NR==1{for(i=1;i<=NF;i++) if($i=="prompt_tokens") c=i; next} c{print $c}' "$f" \
         | sort -u | tr '\n' ' ')
    [ "$(echo $pt)" = "$EXPECT_PROMPT" ] || stale_prompt="$stale_prompt $(dirname "${f#$OUT_ROOT/}")[$(echo ${pt:-?})]"
done < <(find "$OUT_ROOT" -name host_cpu_sweep.csv 2>/dev/null | sort)
if [ -n "$stale_prompt" ]; then
    cat >&2 <<EOF
ERROR: $OUT_ROOT already holds runs at a DIFFERENT prompt length.

  this run wants : $EXPECT_PROMPT prompt tokens
  already there  :$stale_prompt

Complete passes are skipped, so they would be kept and aggregated as if they were this run.
Point OUT_ROOT at a fresh directory, e.g. OUT_ROOT=reports/host_cpu/final_seq$EXPECT_PROMPT
EOF
    exit 1
fi
if [ -n "$stale" ]; then
    cat >&2 <<EOF
ERROR: $OUT_ROOT already holds runs on a DIFFERENT batch ladder.

  this run wants : $BATCHES
  already there  :$stale

Leaving them would give the aggregator a ladder that changes between passes, and the winning
thread count is chosen across the whole ladder — so the choice itself would depend on which
pass happened to be wider. Archive the old set and run all $PASSES passes fresh:

  mv $OUT_ROOT ${OUT_ROOT}_b1to6_$(date +%Y%m%d)

Set ARCHIVE_PREV=1 to have this script do exactly that move for you.
EOF
    if [ "${ARCHIVE_PREV:-0}" = "1" ]; then
        dest="${OUT_ROOT}_prev_$(date +%Y%m%d_%H%M%S)"
        mv "$OUT_ROOT" "$dest"
        echo "# ARCHIVE_PREV=1: moved the previous set -> $dest" >&2
    else
        exit 1
    fi
fi

echo "############################################################"
echo "# Xeon final sweep: $PASSES passes x {$THREAD_SET} threads"
echo "#   models   : $MODELS"
echo "#   backends : $BACKENDS"
echo "#   batches  : $BATCHES   reps=$REPS   gen: 370M=$(gen_for x-370M) 1.3B=$(gen_for x-1.3B) 2.7B=$(gen_for x-2.7B)"
echo "#   prompt   : $EXPECT_PROMPT tokens$([ -n "$PROMPT_IDS" ] && echo " (--ids $PROMPT_IDS)" || echo " (runner default)")"
echo "#   out      : $OUT_ROOT"
echo "############################################################"

# One sweep per model (GEN differs): output is <root>/<slug>/t<threads>_p<pass>/.
count() { set -- $1; echo $#; }
EXPECT_ROWS=$(( $(count "$BACKENDS") * $(count "$BATCHES") ))

# bench_host_cpu.sh refuses above MAX_LOAD (default nproc/4), and our own previous pass leaves
# the 1-min load above that, so wait for it to settle rather than weakening the guard.
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

# ── No FOREIGN process may be burning a core ────────────────────────────────────────
# settle() gives up after SETTLE_MAX, which is where contention gets through. Anything burning
# CPU in a DIFFERENT session is foreign (everything we spawn shares our session), so abort on
# it. %CPU comes from two /proc/<pid>/stat samples (ps pcpu is a lifetime average); limits are
# % of one core, for one process and for all of them together.
MY_SID=$(awk '{print $6}' "/proc/$$/stat" 2>/dev/null)
FOREIGN_CPU_MAX="${FOREIGN_CPU_MAX:-100}"      # abort if any ONE foreign process exceeds this
FOREIGN_TOTAL_MAX="${FOREIGN_TOTAL_MAX:-150}"  # ...or if all of them together do
FOREIGN_CPU_SHOW="${FOREIGN_CPU_SHOW:-25}"     # ...and report anything above this either way
foreign_hogs() {
    local interval="${1:-2}" hz f pid line rest ticks pct sid comm cl
    hz=$(getconf CLK_TCK)
    declare -A t0
    for f in /proc/[0-9]*/stat; do
        line=$(cat "$f" 2>/dev/null) || continue
        pid=${f#/proc/}; pid=${pid%/stat}
        rest=${line#*") "}                    # comm can contain spaces AND parens
        set -- $rest
        t0[$pid]=$(( ${12} + ${13} ))         # utime + stime, 1-based within rest
    done
    sleep "$interval"
    for f in /proc/[0-9]*/stat; do
        pid=${f#/proc/}; pid=${pid%/stat}
        [ -n "${t0[$pid]:-}" ] || continue
        # Skip kernel threads (empty cmdline). Not `[ -s ]`: procfs reports size 0 for all.
        cl=""; IFS= read -r -d '' cl < "/proc/$pid/cmdline" 2>/dev/null
        [ -n "$cl" ] || continue
        line=$(cat "$f" 2>/dev/null) || continue
        rest=${line#*") "}
        comm=${line#*(}; comm=${comm%%)*}
        set -- $rest
        sid=${4}
        [ "$sid" = "$MY_SID" ] && continue             # us, or something we spawned
        ticks=$(( ${12} + ${13} - t0[$pid] ))
        pct=$(( 100 * ticks / (hz * interval) ))
        [ "$pct" -ge "$FOREIGN_CPU_SHOW" ] && printf '%d %d %s\n' "$pct" "$pid" "$comm"
    done
    return 0
}
assert_box_quiet() {
    local seen max=0 total=0 pct rest
    seen=$(foreign_hogs 2 | sort -rn)
    [ -z "$seen" ] && return 0
    while read -r pct rest; do
        [ -n "$pct" ] || continue
        [ "$pct" -gt "$max" ] && max=$pct
        total=$((total + pct))
    done <<< "$seen"
    if [ "$max" -lt "$FOREIGN_CPU_MAX" ] && [ "$total" -lt "$FOREIGN_TOTAL_MAX" ]; then
        printf '    foreign CPU: %d%% total, largest %d%% — under the limits, proceeding\n' \
            "$total" "$max"
        return 0
    fi
    local hogs; hogs=$(while read -r pct pid comm; do
        printf '    %7s  %4s%%  %s\n' "$pid" "$pct" "$comm"; done <<< "$seen")
    cat >&2 <<EOF

ERROR: something outside this sweep is burning CPU ($total% total, largest $max%; limits are
       $FOREIGN_CPU_MAX% for one process and $FOREIGN_TOTAL_MAX% together), so the next group
       would measure contention rather than the model:

$hogs

The usual culprit is VS Code's file search (\`rg --files --hidden --no-ignore --follow\`) over
transfer/models/ — closing the editor, or excluding that directory from search, fixes it.
Every group already written is untouched and still valid; this aborts before starting another.

Set FOREIGN_CPU_MAX/FOREIGN_TOTAL_MAX higher to relax it, or ALLOW_CONTENTION=1 to proceed —
but then this is NOT the final data set, for the reason in the comment above this check.
EOF
    [ "${ALLOW_CONTENTION:-0}" = "1" ] || exit 1
    echo "### ALLOW_CONTENTION=1: proceeding on a busy box" >&2
}

t0=$(date +%s); failed=""
for model in $MODELS; do
    slug="${model##*-}"
    g=$(gen_for "$model")
    for pass in $(seq 1 "$PASSES"); do
        for th in $THREAD_SET; do
            out="$OUT_ROOT/$slug/t${th}_p${pass}"
            # Idempotent: a complete pass is skipped, so a re-run fills only the gaps.
            if [ -f "$out/host_cpu_sweep.csv" ] && \
               [ "$(( $(wc -l < "$out/host_cpu_sweep.csv") - 1 ))" -ge "$EXPECT_ROWS" ]; then
                echo "=== $slug pass $pass, ${th}T — already complete ($EXPECT_ROWS rows), skipping"
                continue
            fi
            rm -rf "$out"          # partial output would append and duplicate rows
            echo
            echo "=== [$(date -Is)] $slug pass $pass, ${th} threads, gen=$g -> $out ==="
            assert_binary_unchanged
            settle                  # our own tail: waits, then shrugs
            assert_box_quiet        # someone else's hog: waits for nothing, aborts
            OMP_THREADS="$th" MODELS="$model" BACKENDS="$BACKENDS" BATCHES="$BATCHES" \
            GEN="$g" REPS="$REPS" OUT="$out" PROMPT_IDS="$PROMPT_IDS" \
                ./scripts/bench_host_cpu.sh || failed="$failed ${slug}/t${th}p${pass}"
        done
    done
done

echo
echo "############################################################"
echo "# DONE in $(( ($(date +%s) - t0) / 60 )) min"
echo "############################################################"
echo "Per-pass CSVs: $OUT_ROOT/<model>/t*_p*/host_cpu_sweep.csv"
echo "Aggregate with: ./scripts/final_dataset.py"
[ -n "$failed" ] && { echo "# FAILURES:$failed"; exit 1; }
echo "# all passes complete"
