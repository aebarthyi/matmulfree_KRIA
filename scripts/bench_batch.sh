#!/usr/bin/env bash
# bench_batch.sh — batch sweep for one deployed bitstream, emitted as a single CSV.
#
# Board-side (staged into the bundle); run from ~/transfer with the overlay already loaded:
#   sudo make deploy-hw PRESET=<preset>            # load the bitstream FIRST
#   ./bench_batch.sh PRESET=<preset> [MODEL=<hf-id>] [GEN=64] [REPS=3] [WARMUP=1] [OUT=file.csv]
#   ./bench_batch.sh PRESET=<preset> BATCHES="1 2 4 6"    # explicit list instead of 1..B
#
# One `make run ... --serve b` per b = 1..MMFREE_BATCH (from the manifest), one CSV row each:
# TTFT, prefill, token latency, tok/s, plus op/engine-phase breakdowns. A row with streams_ok=0
# is a correctness failure. streams_ok uses one shared prompt, so it cannot see a row
# permutation: run `make streams PRESET=<p>` once per bitstream for that.
#
# BACKEND=cpu|cpu-2bit|cpu-1p6: the same sweep with the engine out of the loop (int8 / 2.0 /
# 1.625 bits per weight, bit-identical results) -- the CPU baseline. Needs no overlay or packed
# blob, omits the bl_* columns, and writes a separate _<backend> CSV.
# Match POWER between runs before comparing: POWER=1 costs ~2% decode (this script defaults
# to 0; `make sweep` passes 1).
# Output: results/<preset>[_<model-slug>][_<backend>][_<TAG>]_batch_sweep.csv
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

# ---- args: KEY=VAL only (mirrors the Makefile's convention) -------------------
PRESET=""; MODEL=""; GEN=64; REPS=3; WARMUP=1; OUT=""; BATCHES=""; POWER=0; EXTRA=""; TAG=""; RUNENV=""
BACKEND=fpga
for arg in "$@"; do
    case "$arg" in
        PRESET=*)  PRESET="${arg#*=}"  ;;
        MODEL=*)   MODEL="${arg#*=}"   ;;
        # both = CPU and FPGA cross-checked per projection (slow; don't quote its timings)
        BACKEND=*) BACKEND="${arg#*=}" ;;
        GEN=*)     GEN="${arg#*=}"     ;;
        REPS=*)    REPS="${arg#*=}"    ;;
        WARMUP=*)  WARMUP="${arg#*=}"  ;;
        OUT=*)     OUT="${arg#*=}"     ;;
        BATCHES=*) BATCHES="${arg#*=}" ;;
        POWER=*)   POWER="${arg#*=}"   ;;
        # EXTRA is appended after the sweep's own flags, so it can override them
        # (e.g. EXTRA="--packed-dir /home/ubuntu/transfer/models/<id>/packed-1p6").
        EXTRA=*)   EXTRA="${arg#*=}"   ;;
        # TAG keeps output files apart when one preset+model is swept more than once.
        TAG=*)     TAG="${arg#*=}"     ;;
        # ENV="K=V ..." reaches the workload's environment (sudo strips the caller's).
        ENV=*)     RUNENV="${arg#*=}"  ;;
        *) echo "unknown arg '$arg' (KEY=VAL only)" >&2; exit 2 ;;
    esac
done
[ -n "$PRESET" ] || { echo "usage: $0 PRESET=<preset> [BACKEND=fpga|cpu|cpu-2bit|cpu-1p6|both] [MODEL=<hf-id>] [GEN=n] [REPS=n] [BATCHES=\"1 2 4\"] [OUT=f.csv]" >&2; exit 2; }
case "$BACKEND" in
    fpga|cpu|cpu-2bit|cpu-1p6|cpu-tq1|both) ;;
    *) echo "ERROR: BACKEND must be fpga, cpu, cpu-2bit, cpu-1p6 or both (got '$BACKEND')." >&2; exit 2 ;;
esac

MANIFEST="presets/$PRESET/preset.env"
[ -f "$MANIFEST" ] || { echo "ERROR: no manifest at $MANIFEST — '$PRESET' is not in this bundle." >&2; exit 1; }

# The bitstream's batchSize is the default ladder for every backend, but a hard cap only when
# the engine is in play (cpu* backends may exceed it with an explicit BATCHES).
HW_BATCH=$(grep -hE '^MMFREE_BATCH=' "$MANIFEST" | cut -d= -f2)
HW_BATCH=${HW_BATCH:-1}
if [ -z "$BATCHES" ]; then
    BATCHES=$(seq 1 "$HW_BATCH" | tr '\n' ' ')
elif [ "${BACKEND#cpu}" = "$BACKEND" ]; then   # any cpu* backend: no PE rows to cap
    for b in $BATCHES; do
        [ "$b" -le "$HW_BATCH" ] || { echo "ERROR: batch $b exceeds the bitstream's batchSize $HW_BATCH." >&2; exit 1; }
    done
fi

MODEL_ARG=$([ -n "$MODEL" ] && echo "MODEL=$MODEL" || echo "")
SLUG=$([ -n "$MODEL" ] && echo "_$(basename "$MODEL" | tr '[:upper:]' '[:lower:]')" || echo "")
# Tag non-default backends so a CPU baseline never overwrites the FPGA CSV for the same preset.
[ "$BACKEND" = "fpga" ] || SLUG="${SLUG}_${BACKEND}"
[ -n "$TAG" ] && SLUG="${SLUG}_${TAG}"
[ -n "$OUT" ] || OUT="results/${PRESET}${SLUG}_batch_sweep.csv"
mkdir -p "$(dirname "$OUT")" results/logs

echo "# batch sweep: preset=$PRESET  backend=$BACKEND  batchSize=$HW_BATCH  sweep=[$BATCHES]"
echo "#   gen=$GEN tok/stream  warmup=$WARMUP  reps=$REPS  ${MODEL_ARG:-[manifest model]}"
case "$BACKEND" in cpu*) echo "#   CPU baseline — engine not used (no overlay/blob needed); bl_* phase columns absent" ;; esac
echo "#   -> $OUT"

: > "$OUT"
wrote_header=0
failed=""

for b in $BATCHES; do
    log="results/logs/${PRESET}${SLUG}_b${b}.log"
    echo "=== [$(date -Is)] b=$b -> $log"
    # `make run` supplies device paths + geometry env. --backend goes in ARGS because the run
    # target hardcodes `--backend fpga` and the runner keeps the LAST repeated flag; $EXTRA
    # stays last so it can still override. BATCH=$b gives each b its own power samples file,
    # and POWER_DIR keeps arms and passes of one preset apart.
    if ! make run PRESET="$PRESET" $MODEL_ARG POWER="$POWER" BATCH="$b" \
            POWER_DIR="power/${PRESET}${SLUG}" ${RUNENV:+ENV="$RUNENV"} \
            ARGS="--backend $BACKEND --serve $b --gen $GEN --warmup $WARMUP --reps $REPS --csv $EXTRA" \
            > "$log" 2>&1; then
        echo "    FAILED (rc=$?) — see $log"
        failed="$failed $b"
        continue
    fi

    # Capture into variables, not a pipeline into the file: the pipeline's status is sed's,
    # so a run that died early would silently append an empty row.
    row=$(grep -m1 '^#CSVR ' "$log" | sed 's/^#CSVR //')
    if [ -z "$row" ]; then
        echo "    WARN: no #CSVR row in $log (did the run reach the timed phase?)"
        failed="$failed $b"
        continue
    fi
    if [ "$wrote_header" -eq 0 ]; then
        hdr=$(grep -m1 '^#CSVH ' "$log" | sed 's/^#CSVH //')
        [ -n "$hdr" ] || { echo "    WARN: row but no #CSVH header in $log"; failed="$failed $b"; continue; }
        printf '%s\n' "$hdr" >> "$OUT"
        wrote_header=1
    fi
    printf '%s\n' "$row" >> "$OUT"

    # Surface correctness + the headline number per row as the sweep runs.
    grep -E '^serve B=|^  (ttft|decode|token latency)' "$log" | sed 's/^/    /'
done

echo
if [ -n "$failed" ]; then
    echo "# sweep finished with FAILURES at batch:$failed  (rows missing from $OUT)"
else
    echo "# sweep complete: $OUT"
fi

# Split the ~65-column wide CSV (the raw record) into _summary / _ops / _phases sheets.
if [ "$wrote_header" -eq 1 ] && command -v python3 >/dev/null 2>&1; then
    python3 ./split_sweep_csv.py "$OUT" || echo "  WARN: split failed; the wide CSV is still complete"
fi

echo "# benchmark table:"
column -s, -t < "${OUT%_batch_sweep.csv}_summary.csv" 2>/dev/null || column -s, -t < "$OUT" 2>/dev/null || cat "$OUT"
[ -z "$failed" ]
