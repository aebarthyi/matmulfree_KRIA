#!/usr/bin/env bash
# final_sweep_board.sh — the KRIA half of the final report data set.
#
#   cd ~/transfer && sudo ./final_sweep_board.sh              # everything (~7-8 h)
#   cd ~/transfer && sudo ./final_sweep_board.sh fpga         # FPGA arms only  (~2-3 h)
#   cd ~/transfer && sudo ./final_sweep_board.sh cpu          # A53 arms only   (~4-5 h)
#
# The estimates are for the defaults below at B8: 5 FPGA passes over an 8-rung ladder, and
# one CPU pass over the same ladder at three encodings. They were ~4-5 h in total at B6 with
# 3 passes; two more passes and two more rungs is where the rest went. Nothing needs babysitting
# — every arm logs, and a failure in one is recorded and skipped rather than aborting the run.
#
# Produces, for all three model sizes, at every batch b=1..MMFREE_BATCH (8 on the shipped
# B8 bitstreams — the ladder comes from the preset manifest, not from this script), WITH
# board rail power on every single row:
#
#   arm "fpga"  the three shipped presets (mmfree_370m / _1_3b / _2_7b), 1.6-bit weights,
#               engine + A53 host. PASSES independent runs each, gated bit-exact first, and
#               row-independence checked once per bitstream with distinct prompts.
#   arm "cpu"   the same three models on the four A53s with the engine OUT of the loop, at
#               all three weight encodings (int8 / 2-bit / 1.6-bit). ONE pass, REPS=1 — these
#               are slow (2.7B decodes at ~1.3 tok/s) and a second pass would cost hours to
#               resolve a difference the FPGA arms already bound.
#
# The CPU arms sweep the SAME ladder as the FPGA arms, which is why they read the preset
# manifest too: they are the denominator, and a CPU row that stops at b=6 against an FPGA
# row that reaches b=8 leaves the top of the ladder with no speedup to quote. final_dataset.py
# enforces this — it reads the ceiling from the CSV's hw_batch_max column and reports any arm
# that falls short of it.
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
# AND CHECK ROW INDEPENDENCE ONCE PER BITSTREAM. The sweep's own streams_ok gives every
# stream the SAME prompt, so the streams are identical by construction and the check is
# invariant under permutation: it cannot see the drain writing row 2 into stream 5. `make
# streams` re-runs the same path with a DIFFERENT prompt per stream against per-stream
# references, which can. It is a property of the bitstream, not of a batch or a pass, so it
# runs once per preset here rather than per row — and it matters most at B8, eight rows being
# the widest drain this design has ever done.
#
# ORDER: CPU arms first, per size, before that size's overlay is loaded. They need no CMA at
# all, so running them first keeps the FPGA arms' allocations off a fragmented pool.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
[ "$(id -u)" -eq 0 ] || { echo "ERROR: run under sudo (overlay load + /dev/mem): sudo ./final_sweep_board.sh" >&2; exit 1; }

WHICH="${1:-all}"
# Five, not three. The headline is the MEDIAN across passes, and with n=3 dropping any single
# pass leaves two whose median is their average — so one transient still moves it, which is
# exactly what the jackknife flagged on the 3-pass sets. At n=5 it cannot.
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
# Pass numbering starts here, so a top-up run adds passes instead of overwriting them.
#
# ⚠️ THE VARIABLES MUST GO AFTER `sudo`, NOT BEFORE IT. sudo resets the environment, so
#      PASS_START=4 PASSES=5 sudo ./final_sweep_board.sh fpga     <-- SILENTLY IGNORED
# runs the default passes 1..3 and overwrites the data you meant to extend. That has now
# happened twice. The working form is
#      sudo PASS_START=4 PASSES=5 ./final_sweep_board.sh fpga
# (or `sudo -E`). The banner below prints the values actually in effect — check it.
PASS_START="${PASS_START:-1}"

mkdir -p results/logs
failed=""
t0=$(date +%s)
echo "# effective settings: WHICH=$WHICH PRESETS='$PRESETS' passes ${PASS_START}..${PASSES}"
echo "#   gen=$GEN fpga_reps=$FPGA_REPS cpu_reps=$CPU_REPS streams=$DO_STREAMS power=1"
echo "#   (if that is not what you asked for, the env went BEFORE sudo and was stripped)"
# The batch ladder is the bitstream's, so print it: a bundle that was not re-deployed after a
# preset change is otherwise indistinguishable from one that was, until the CSVs come up short.
for p in $PRESETS; do
    printf '#   %-14s batch ceiling %s   model %s\n' "$p" \
        "$(sed -n 's/^MMFREE_BATCH=//p' "presets/$p/preset.env" 2>/dev/null || echo '?')" \
        "$(sed -n 's/^MMFREE_MODEL=//p' "presets/$p/preset.env" 2>/dev/null || echo '?')"
done
say() { echo; echo "############################################################"; echo "# $*"; echo "############################################################"; }

# ── Preflight: get the PREVIOUS final set out of the way ─────────────────────
# The CSV name is <preset>[_<backend>]_final[_pN], which does NOT encode the bitstream. So a
# B8 run under the shipped preset names writes over a B6 run under the same names — and if
# this run fails partway, what is left in results/ is a MIX of two bitstreams' rows under one
# set of filenames, which the aggregator has no way to separate and will happily merge into
# one median. Archive first, so a half-finished run can only ever be incomplete, never wrong.
#
# Skipped when PASS_START > 1, which IS the deliberate top-up case: those runs are meant to
# land alongside the passes already there.
if [ "$PASS_START" = "1" ] && [ "${ARCHIVE:-1}" = "1" ]; then
    prev=$(ls -d results/*_final*_batch_sweep.csv 2>/dev/null | wc -l)
    if [ "$prev" -gt 0 ]; then
        arc="results/prev_$(date +%Y%m%d_%H%M%S)"
        mkdir -p "$arc"
        mv results/*_final* "$arc"/ 2>/dev/null || true
        # Power dirs are named the same way and are just as ambiguous.
        for d in power/*_final*; do [ -e "$d" ] && mv "$d" "$arc"/ 2>/dev/null; done
        # power/idle is NOT named *_final*, so it would otherwise survive into this run and
        # silently supply last session's idle draw as this session's net-of-idle denominator.
        [ -e power/idle ] && mv power/idle "$arc"/ 2>/dev/null
        echo "#"
        echo "#   archived $prev previous *_final* CSV(s) + their power dirs -> $arc/"
        echo "#   (they were written by whatever bitstream was loaded then; this run's are new)"
    fi
fi

# ── Idle rail baseline, ONCE per sweep ───────────────────────────────────────
# tok/J in the final data set is computed against power NET of idle, which needs an idle
# figure from this board, in this session, sampled by the same instrument as the workload —
# the Xeon side has measured one since bench_host_cpu.sh existed, and comparing a net-of-idle
# Xeon number against a total-draw KRIA number is not like-for-like.
#
# power_bench.sh takes an arbitrary workload command, so the idle "workload" is just a sleep:
# same sampler, same parser, same summary line, no special case in the tool.
#
# ONCE, not per arm: the rails do not care which preset is loaded when nothing is running,
# and 10 s x every (preset, backend, batch) cell would add minutes to the sweep for a number
# that does not vary. It is taken BEFORE any overlay load, so it is the resting draw of the
# board as the sweep found it.
IDLE_S="${IDLE_S:-10}"
if [ "${POWER:-1}" = "1" ] && [ "${DO_IDLE:-1}" = "1" ]; then
    say "idle rail baseline (${IDLE_S}s, no workload)"
    MANIFEST_ANY="$(ls presets/*/preset.env 2>/dev/null | head -n1)"
    POWER_DIR="power/idle" ./power_bench.sh "${MANIFEST_ANY:--}" idle - sleep "$IDLE_S" \
        || echo "#   WARN: idle baseline failed — rows will fall back to total draw" >&2
fi

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

            # Gate once per preset, on the first pass of this invocation. The bitstream does
            # not change between passes, so re-gating each one buys nothing and costs minutes
            # at 2.7B — but a top-up run still gates once, since it may be a different day.
            if [ "$pass" = "$PASS_START" ]; then
                echo "  -- gate (CPU == FPGA, bit-exact) --"
                if ! make gate PRESET="$preset" ARGS="--gen $GATE_GEN" \
                        > "results/logs/final_gate_$preset.log" 2>&1; then
                    echo "  !! GATE FAILED — skipping $preset entirely, its timings would be meaningless"
                    tail -15 "results/logs/final_gate_$preset.log"
                    failed="$failed $preset(GATE)"; break
                fi
                grep -E "GATE|checked" "results/logs/final_gate_$preset.log" | sed 's/^/     /'

                # Row independence, once per bitstream (see the header). A failure here is
                # NOT fatal to the run — the timings stay valid as timings — but it makes
                # every batched row unquotable, so it is recorded as a failure and the
                # aggregator's streams_ok check is what stops the number reaching a report.
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
echo "# every arm completed — pull with:"
echo "#   rsync -a ubuntu@kria:'~/transfer/results/' ~/results/final/"
echo "#   rsync -a ubuntu@kria:'~/transfer/power/'   ~/results/final/power/"
echo "# then, on the build server:"
echo "#   ./scripts/final_dataset.py --board ~/results/final --xeon reports/host_cpu/final"
echo "# which is the thing that decides whether this run is quotable. It checks the ladder"
echo "# per model against hw_batch_max, so a short arm shows up as missing b=, not as clean."
