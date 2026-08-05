#!/usr/bin/env bash
# final_sweep_board.sh — the KRIA half of the final report data set.
#
#   cd ~/transfer && sudo ./final_sweep_board.sh              # everything (~4-5 h)
#   cd ~/transfer && sudo ./final_sweep_board.sh fpga         # FPGA arms only  (~1 h)
#   cd ~/transfer && sudo ./final_sweep_board.sh cpu          # A53 arms only   (~3-4 h)
#
# Produces, for all three model sizes, at every batch b=1..6, WITH board rail power on
# every single row:
#
#   arm "fpga"  the three shipped presets (mmfree_370m / _1_3b / _2_7b), 1.6-bit weights,
#               engine + A53 host. PASSES independent runs each, gated bit-exact first.
#   arm "cpu"   the same three models on the four A53s with the engine OUT of the loop, at
#               all three weight encodings (int8 / 2-bit / 1.6-bit). ONE pass, REPS=1 — these
#               are slow (2.7B decodes at ~1.3 tok/s) and a second pass would cost hours to
#               resolve a difference the FPGA arms already bound.
#
# ── DESIGN NOTES, because this is meant to be quotable without asterisks ─────────────
#
# POWER=1 ON EVERY ARM, INCLUDING THE CPU ONES. Rail sampling costs ~2% decode, so mixing
# POWER=1 and POWER=0 rows silently biases every comparison drawn across them — that has
# already manufactured one phantom regression here. Sampling everything makes the tax a
# constant of the method rather than a confound, and it means each row's tok/s and its watts
# come from the SAME run, so tok/J is exact instead of being spliced from two runs.
#
# PASSES ARE SEPARATE PROCESSES, NOT REPS. bench_batch.sh already averages REPS runs inside
# one process; that is not the variance that bites. On 2026-08-04 a single (arm, batch) cell
# read 12% low and agreed to 0.12 tok/s across 3/3 reps, because whatever went wrong was
# fixed for that process's lifetime. Only a fresh process — and, for the FPGA, a fresh
# overlay load — resolves it. Hence PASSES independent invocations, and the aggregator
# reports the spread across them and flags any cell where they disagree by more than 3%.
#
# GATE BEFORE TIMING. A wrong engine result is not a crash; it is a plausible number. The
# gate is bit-exact CPU vs engine over every projection. streams_ok is NOT a substitute — it
# compares the engine against itself, so a uniform error corrupts the reference too.
#
# ORDER: CPU arms first, per size, before that size's overlay is loaded. They need no CMA at
# all, so running them first keeps the FPGA arms' allocations off a fragmented pool.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
[ "$(id -u)" -eq 0 ] || { echo "ERROR: run under sudo (overlay load + /dev/mem): sudo ./final_sweep_board.sh" >&2; exit 1; }

WHICH="${1:-all}"
PASSES="${PASSES:-3}"        # independent FPGA passes per preset
GEN="${GEN:-64}"
FPGA_REPS="${FPGA_REPS:-3}"  # in-process reps for the FPGA arms
CPU_REPS="${CPU_REPS:-1}"    # A53 arms: one shot, they are slow
GATE_GEN="${GATE_GEN:-8}"
export POWER=1               # non-negotiable for this data set; see above

PRESETS="mmfree_370m mmfree_1_3b mmfree_2_7b"
CPU_BACKENDS="${CPU_BACKENDS:-cpu cpu-2bit cpu-1p6}"

mkdir -p results/logs
failed=""
t0=$(date +%s)
say() { echo; echo "############################################################"; echo "# $*"; echo "############################################################"; }

# ── A53 CPU-only arms ────────────────────────────────────────────────────────
# No overlay and no gate: the runner installs the CPU backend before it ever opens /dev/mem,
# and the gate's reference IS the CPU path, so there is nothing to compare against.
if [ "$WHICH" = "all" ] || [ "$WHICH" = "cpu" ]; then
    for preset in $PRESETS; do
        for be in $CPU_BACKENDS; do
            say "A53 CPU-only  $preset  backend=$be  (reps=$CPU_REPS, power on)"
            ./bench_batch.sh PRESET="$preset" BACKEND="$be" TAG="final" \
                             GEN="$GEN" REPS="$CPU_REPS" WARMUP=1 POWER=1 \
                || failed="$failed $preset/$be"
        done
    done
fi

# ── FPGA arms ────────────────────────────────────────────────────────────────
if [ "$WHICH" = "all" ] || [ "$WHICH" = "fpga" ]; then
    for preset in $PRESETS; do
        say "FPGA  $preset  ($PASSES passes, reps=$FPGA_REPS, power on)"

        wt=$(grep -hE '^UDMABUF_WT_SZ=' "presets/$preset/preset.env" | cut -d= -f2)
        nd=$(grep -hE '^NUM_DMA=' "presets/$preset/preset.env" | cut -d= -f2)
        need=$(( $(printf '%d' "${wt:-0}") * ${nd:-0} / 1048576 ))
        free=$(( $(awk '/^CmaFree:/{print $2}' /proc/meminfo) / 1024 ))
        held=$(cat /sys/class/u-dma-buf/udmabuf-wt*/size 2>/dev/null | awk '{s+=$1} END{printf "%d", s/1048576}')
        echo "   cma: need ${need} MiB | free ${free} MiB + ${held:-0} MiB held"
        [ "$need" -gt "$(( free + ${held:-0} ))" ] && \
            echo "   !! WARNING: WT need exceeds the CMA budget — reboot or run ./cma_reclaim.sh"

        for pass in $(seq 1 "$PASSES"); do
            # Fresh overlay load per pass: it re-runs the udmabuf allocation, so CMA placement
            # differs between passes. That is deliberate — placement is one of the things the
            # spread is supposed to capture.
            echo "  -- pass $pass: overlay load --"
            if ! ./deploy_kria.sh "$preset" > "results/logs/final_deploy_${preset}_p${pass}.log" 2>&1; then
                echo "  !! deploy FAILED — see results/logs/final_deploy_${preset}_p${pass}.log"
                tail -15 "results/logs/final_deploy_${preset}_p${pass}.log"
                failed="$failed $preset(deploy p$pass)"; continue
            fi
            grep -E "fclk0|WARN|ERROR" "results/logs/final_deploy_${preset}_p${pass}.log" | head -4 | sed 's/^/     /'

            # Gate once per preset, on the first pass. The bitstream does not change between
            # passes, so re-gating each one buys nothing and costs minutes at 2.7B.
            if [ "$pass" = "1" ]; then
                echo "  -- gate (CPU == FPGA, bit-exact) --"
                if ! make gate PRESET="$preset" ARGS="--gen $GATE_GEN" \
                        > "results/logs/final_gate_$preset.log" 2>&1; then
                    echo "  !! GATE FAILED — skipping $preset entirely, its timings would be meaningless"
                    tail -15 "results/logs/final_gate_$preset.log"
                    failed="$failed $preset(GATE)"; break
                fi
                grep -E "GATE|checked" "results/logs/final_gate_$preset.log" | sed 's/^/     /'
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
echo "# every arm completed — pull with:"
echo "#   rsync -a ubuntu@kria:'~/transfer/results/' ~/results/final/"
echo "#   rsync -a ubuntu@kria:'~/transfer/power/'   ~/results/final/power/"
