#!/usr/bin/env bash
# final_sweep_board.sh — the KRIA half of the final report data set.
#
#   cd ~/transfer && sudo ./final_sweep_board.sh              # everything (~7-8 h)
#   cd ~/transfer && sudo ./final_sweep_board.sh fpga         # FPGA arms only  (~2-3 h)
#   cd ~/transfer && sudo ./final_sweep_board.sh cpu          # A53 arms only   (~4-5 h)
#
# Every preset at b=1..MMFREE_BATCH (from the manifest), with rail power on every row:
#   fpga  PASSES runs, each a fresh process + overlay load (in-process REPS cannot expose a
#         bad cell). Gated bit-exact, and row independence (`make streams`) checked, once per
#         bitstream before timing -- a wrong engine result is a plausible number, not a crash.
#   cpu   the same models on the A53s, engine out of the loop, int8/2-bit/1.6-bit, one pass.
#         Runs first, before any overlay load, so the FPGA arms' CMA is not fragmented.
# POWER=1 on every arm: sampling costs ~2% decode, so mixed settings bias every comparison.
# A failed arm is recorded and skipped. Aggregate with scripts/final_dataset.py.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
[ "$(id -u)" -eq 0 ] || { echo "ERROR: run under sudo (overlay load + /dev/mem): sudo ./final_sweep_board.sh" >&2; exit 1; }

WHICH="${1:-all}"
# 5 passes: with 3, a single transient pass can still move the median.
PASSES="${PASSES:-5}"        # independent FPGA passes per preset
GEN="${GEN:-64}"
FPGA_REPS="${FPGA_REPS:-3}"  # in-process reps for the FPGA arms
CPU_REPS="${CPU_REPS:-1}"    # A53 arms: one shot, they are slow
GATE_GEN="${GATE_GEN:-8}"
STREAMS_GEN="${STREAMS_GEN:-32}"   # per-stream generation for the row-independence check
DO_STREAMS="${DO_STREAMS:-1}"      # 0 skips it (it is a per-bitstream property, not per-run)
export POWER=1               # non-negotiable for this data set; see above

PRESETS="${PRESETS:-mmfree_370m mmfree_1_3b mmfree_2_7b}"
CPU_BACKENDS="${CPU_BACKENDS:-cpu cpu-2bit cpu-1p6}"
# A53 arms only: restrict their ladder, e.g. CPU_BATCHES="1 2" (empty = the FPGA's full ladder).
# A truncated run must be aggregated with --cpu-ladder "$CPU_BATCHES", or final_dataset.py
# reports it incomplete.
CPU_BATCHES="${CPU_BATCHES:-}"
cpu_batch_arg=()
[ -n "$CPU_BATCHES" ] && cpu_batch_arg=(BATCHES="$CPU_BATCHES")
# First pass number, so a top-up run adds passes instead of overwriting them.
# Env vars must go AFTER sudo (sudo strips the environment):
#      sudo PASS_START=4 PASSES=5 ./final_sweep_board.sh fpga      (or `sudo -E`)
# The banner below prints the values actually in effect.
PASS_START="${PASS_START:-1}"

mkdir -p results/logs
failed=""
t0=$(date +%s)
echo "# effective settings: WHICH=$WHICH PRESETS='$PRESETS' passes ${PASS_START}..${PASSES}"
echo "#   gen=$GEN fpga_reps=$FPGA_REPS cpu_reps=$CPU_REPS streams=$DO_STREAMS power=1"
echo "#   cpu ladder: ${CPU_BATCHES:-<full, same as FPGA>}"
[ -n "$CPU_BATCHES" ] && \
    echo "#   !! A53 arms TRUNCATED to b=[$CPU_BATCHES] — aggregate with --cpu-ladder \"$CPU_BATCHES\""
echo "#   (if that is not what you asked for, the env went BEFORE sudo and was stripped)"
# Print each bundle's batch ceiling: a bundle not re-deployed after a preset change looks identical.
for p in $PRESETS; do
    printf '#   %-14s batch ceiling %s   model %s\n' "$p" \
        "$(sed -n 's/^MMFREE_BATCH=//p' "presets/$p/preset.env" 2>/dev/null || echo '?')" \
        "$(sed -n 's/^MMFREE_MODEL=//p' "presets/$p/preset.env" 2>/dev/null || echo '?')"
done
say() { echo; echo "############################################################"; echo "# $*"; echo "############################################################"; }

# ── Preflight: get the PREVIOUS final set out of the way ─────────────────────
# CSV names do not encode the bitstream, so archive the previous set first: a half-finished
# run is then only incomplete, never a mix of two bitstreams. Skipped for top-ups (PASS_START>1).
if [ "$PASS_START" = "1" ] && [ "${ARCHIVE:-1}" = "1" ]; then
    prev=$(ls -d results/*_final*_batch_sweep.csv 2>/dev/null | wc -l)
    if [ "$prev" -gt 0 ]; then
        arc="results/prev_$(date +%Y%m%d_%H%M%S)"
        mkdir -p "$arc"
        mv results/*_final* "$arc"/ 2>/dev/null || true
        # Power dirs are named the same way and are just as ambiguous.
        for d in power/*_final*; do [ -e "$d" ] && mv "$d" "$arc"/ 2>/dev/null; done
        # power/idle is not *_final*; left behind it would become this session's idle denominator.
        [ -e power/idle ] && mv power/idle "$arc"/ 2>/dev/null
        echo "#"
        echo "#   archived $prev previous *_final* CSV(s) + their power dirs -> $arc/"
        echo "#   (they were written by whatever bitstream was loaded then; this run's are new)"
    fi
fi

# ── Idle rail baseline, ONCE per sweep ───────────────────────────────────────
# tok/J is net of idle, so sample this board's idle draw with the same instrument (power_bench.sh
# with a sleep as the workload), once per sweep, before any overlay load.
IDLE_S="${IDLE_S:-10}"
if [ "${POWER:-1}" = "1" ] && [ "${DO_IDLE:-1}" = "1" ]; then
    say "idle rail baseline (${IDLE_S}s, no workload)"
    MANIFEST_ANY="$(ls presets/*/preset.env 2>/dev/null | head -n1)"
    POWER_DIR="power/idle" ./power_bench.sh "${MANIFEST_ANY:--}" idle - sleep "$IDLE_S" \
        || echo "#   WARN: idle baseline failed — rows will fall back to total draw" >&2
fi

# ── A53 CPU-only arms ────────────────────────────────────────────────────────
# No overlay and no gate: the gate's reference IS the CPU path.
if [ "$WHICH" = "all" ] || [ "$WHICH" = "cpu" ]; then
    for preset in $PRESETS; do
        for be in $CPU_BACKENDS; do
            say "A53 CPU-only  $preset  backend=$be  (reps=$CPU_REPS, power on, b=[${CPU_BATCHES:-full}])"
            # An array, not an unquoted ${CPU_BATCHES:+...}, which would word-split "1 2".
            ./bench_batch.sh PRESET="$preset" BACKEND="$be" TAG="final" \
                             GEN="$GEN" REPS="$CPU_REPS" WARMUP=1 POWER=1 \
                             "${cpu_batch_arg[@]}" \
                || failed="$failed $preset/$be"
        done
    done
fi

# ── FPGA arms ────────────────────────────────────────────────────────────────
if [ "$WHICH" = "all" ] || [ "$WHICH" = "fpga" ]; then
    for preset in $PRESETS; do
        say "FPGA  $preset  (passes $PASS_START..$PASSES, reps=$FPGA_REPS, power on)"

        wt=$(grep -hE '^UDMABUF_WT_SZ=' "presets/$preset/preset.env" | cut -d= -f2)
        nd=$(grep -hE '^NUM_DMA=' "presets/$preset/preset.env" | cut -d= -f2)
        need=$(( $(printf '%d' "${wt:-0}") * ${nd:-0} / 1048576 ))
        free=$(( $(awk '/^CmaFree:/{print $2}' /proc/meminfo) / 1024 ))
        held=$(cat /sys/class/u-dma-buf/udmabuf-wt*/size 2>/dev/null | awk '{s+=$1} END{printf "%d", s/1048576}')
        echo "   cma: need ${need} MiB | free ${free} MiB + ${held:-0} MiB held"
        [ "$need" -gt "$(( free + ${held:-0} ))" ] && \
            echo "   !! WARNING: WT need exceeds the CMA budget — reboot or run ./cma_reclaim.sh"

        for pass in $(seq "$PASS_START" "$PASSES"); do
            # Fresh overlay load per pass: CMA placement varying between passes is part of the spread.
            echo "  -- pass $pass: overlay load --"
            if ! ./deploy_kria.sh "$preset" > "results/logs/final_deploy_${preset}_p${pass}.log" 2>&1; then
                echo "  !! deploy FAILED — see results/logs/final_deploy_${preset}_p${pass}.log"
                tail -15 "results/logs/final_deploy_${preset}_p${pass}.log"
                failed="$failed $preset(deploy p$pass)"; continue
            fi
            grep -E "fclk0|WARN|ERROR" "results/logs/final_deploy_${preset}_p${pass}.log" | head -4 | sed 's/^/     /'

            # Gate once per preset, on the first pass of this invocation.
            if [ "$pass" = "$PASS_START" ]; then
                echo "  -- gate (CPU == FPGA, bit-exact) --"
                if ! make gate PRESET="$preset" ARGS="--gen $GATE_GEN" \
                        > "results/logs/final_gate_$preset.log" 2>&1; then
                    echo "  !! GATE FAILED — skipping $preset entirely, its timings would be meaningless"
                    tail -15 "results/logs/final_gate_$preset.log"
                    failed="$failed $preset(GATE)"; break
                fi
                grep -E "GATE|checked" "results/logs/final_gate_$preset.log" | sed 's/^/     /'

                # Not fatal (timings stay valid), but recorded: batched rows are not quotable.
                if [ "$DO_STREAMS" = "1" ]; then
                    echo "  -- streams (distinct prompt per row, own reference each) --"
                    if ! make streams PRESET="$preset" GEN="$STREAMS_GEN" \
                            > "results/logs/final_streams_$preset.log" 2>&1; then
                        echo "  !! STREAMS FAILED — batched rows for $preset are not quotable"
                        grep -E "PERMUTATION|mismatch|ERROR|OK \(" \
                            "results/logs/final_streams_$preset.log" | head -8 | sed 's/^/     /'
                        failed="$failed $preset(STREAMS)"
                    else
                        grep -E "OK \(|streams" "results/logs/final_streams_$preset.log" \
                            | head -3 | sed 's/^/     /'
                    fi
                fi
            fi

            ./bench_batch.sh PRESET="$preset" TAG="final_p${pass}" \
                             GEN="$GEN" REPS="$FPGA_REPS" WARMUP=1 POWER=1 \
                || failed="$failed $preset(p$pass)"
            xmutil unloadapp >/dev/null 2>&1; sleep 2
        done
    done
fi

[ -n "${SUDO_USER:-}" ] && chown -R "$SUDO_USER" results power 2>/dev/null

say "DONE in $(( ($(date +%s) - t0) / 60 )) min"
echo "CSVs:    results/*_final*_batch_sweep.csv"
echo "Power:   power/<preset>[_backend]_final*/    (one .samples per BATCH — the b=1..5"
echo "         samples used to overwrite each other; bench_batch.sh now passes BATCH through)"
echo
if [ -n "$failed" ]; then echo "# FAILURES:$failed"; exit 1; fi
DEST="~/results/final_$(date +%Y%m%d)"
echo "# every arm completed — pull into a FRESH directory:"
echo "#   rsync -a ubuntu@kria:'~/transfer/results/' $DEST/"
echo "#   rsync -a ubuntu@kria:'~/transfer/power/'   $DEST/power/"
echo "#"
echo "# NOT into an existing ~/results/final. This run's CSVs overwrite the same-named files"
echo "# there, but any file the PREVIOUS set had and this one does not (a backend or a pass"
echo "# that is not in this run) SURVIVES the rsync and aggregates as if it belonged here."
echo "# That is the same two-bitstreams-under-one-set-of-filenames failure the preflight"
echo "# archives against on the board — rsync just reintroduces it on the build server."
echo "# then, on the build server:"
echo "#   ./scripts/final_dataset.py --board $DEST --xeon reports/host_cpu/final \\"
echo "#       --out reports/final_$(date +%Y%m%d) \\"
case " $PRESETS " in *_2bit*) echo "#       --fpga-formats 1p6,2bit \\" ;; esac
[ -n "$CPU_BATCHES" ] && echo "#       --cpu-ladder \"$CPU_BATCHES\"   <-- REQUIRED: the A53 arms were truncated"
echo "# which is the thing that decides whether this run is quotable. It checks the ladder"
echo "# per model against hw_batch_max, so a short arm shows up as missing b=, not as clean."
