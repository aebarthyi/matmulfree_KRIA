#!/usr/bin/env bash
# bench_batch.sh — batch sweep for one deployed bitstream, emitted as a single CSV.
#
# Runs the fpga_runner once per batch size b = 1..MMFREE_BATCH (the bitstream's compile-time
# batchSize, read from the preset manifest) and collects one row per b:
#
#   time-to-first-token, prefill latency, per-step token latency, tok/s (aggregate and
#   per-stream), plus the op-level and engine-phase profiling breakdowns as columns.
#
# Each b is `--serve b`: b concurrent streams sharing one weight stream per projection per
# decode step. The runner also asserts every stream matches a single-stream decode, so a row
# with streams_ok=0 is a CORRECTNESS failure and its timings mean nothing — check that column
# before reading any number in the row.
#
# This is the BOARD-side script (staged into the bundle by build_all.sh); run it from
# ~/transfer with the target overlay already loaded:
#
#   sudo make deploy-hw PRESET=<preset>            # load the bitstream FIRST
#   ./bench_batch.sh PRESET=<preset> [MODEL=<hf-id>] [GEN=64] [REPS=3] [WARMUP=1] [OUT=file.csv]
#   ./bench_batch.sh PRESET=<preset> BATCHES="1 2 4 6"    # explicit list instead of 1..B
#
# Defaults land the CSV in results/<preset>[_<model-slug>]_batch_sweep.csv. Everything else
# (device paths, geometry env, model resolution) is delegated to the bundle's Makefile, so
# this stays consistent with `make run`/`make gate`.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

# ---- args: KEY=VAL only (mirrors the Makefile's convention) -------------------
PRESET=""; MODEL=""; GEN=64; REPS=3; WARMUP=1; OUT=""; BATCHES=""; POWER=0; EXTRA=""; TAG=""; RUNENV=""
for arg in "$@"; do
    case "$arg" in
        PRESET=*)  PRESET="${arg#*=}"  ;;
        MODEL=*)   MODEL="${arg#*=}"   ;;
        GEN=*)     GEN="${arg#*=}"     ;;
        REPS=*)    REPS="${arg#*=}"    ;;
        WARMUP=*)  WARMUP="${arg#*=}"  ;;
        OUT=*)     OUT="${arg#*=}"     ;;
        BATCHES=*) BATCHES="${arg#*=}" ;;
        POWER=*)   POWER="${arg#*=}"   ;;
        # EXTRA is appended verbatim to the runner's flags, AFTER the sweep's own, so it can
        # override them (the runner takes the last occurrence of a repeated flag). Needed for
        # a non-default weight blob, e.g. the 1.6-bit build:
        #   EXTRA="--packed-dir /home/ubuntu/transfer/models/<id>/packed-1p6"
        EXTRA=*)   EXTRA="${arg#*=}"   ;;
        # TAG distinguishes output files when the same preset+model is swept more than once
        # under different EXTRA (e.g. 2-bit vs 1.6-bit blob), so neither run clobbers the other.
        TAG=*)     TAG="${arg#*=}"     ;;
        # ENV="K=V K=V" reaches the workload's environment (sudo strips the caller's), for
        # tuning knobs like MMFREE_SPLIT_CHUNKS that aren't runner flags.
        ENV=*)     RUNENV="${arg#*=}"  ;;
        *) echo "unknown arg '$arg' (KEY=VAL only)" >&2; exit 2 ;;
    esac
done
[ -n "$PRESET" ] || { echo "usage: $0 PRESET=<preset> [MODEL=<hf-id>] [GEN=n] [REPS=n] [BATCHES=\"1 2 4\"] [OUT=f.csv]" >&2; exit 2; }

MANIFEST="presets/$PRESET/preset.env"
[ -f "$MANIFEST" ] || { echo "ERROR: no manifest at $MANIFEST — '$PRESET' is not in this bundle." >&2; exit 1; }

# The bitstream's compile-time batchSize is the sweep ceiling: --serve b requires
# b <= batchSize (above it the engine has no PE row to put the extra stream in).
HW_BATCH=$(grep -hE '^MMFREE_BATCH=' "$MANIFEST" | cut -d= -f2)
HW_BATCH=${HW_BATCH:-1}
if [ -z "$BATCHES" ]; then
    BATCHES=$(seq 1 "$HW_BATCH" | tr '\n' ' ')
else
    for b in $BATCHES; do
        [ "$b" -le "$HW_BATCH" ] || { echo "ERROR: batch $b exceeds the bitstream's batchSize $HW_BATCH." >&2; exit 1; }
    done
fi

MODEL_ARG=$([ -n "$MODEL" ] && echo "MODEL=$MODEL" || echo "")
SLUG=$([ -n "$MODEL" ] && echo "_$(basename "$MODEL" | tr '[:upper:]' '[:lower:]')" || echo "")
[ -n "$TAG" ] && SLUG="${SLUG}_${TAG}"
[ -n "$OUT" ] || OUT="results/${PRESET}${SLUG}_batch_sweep.csv"
mkdir -p "$(dirname "$OUT")" results/logs

echo "# batch sweep: preset=$PRESET  batchSize=$HW_BATCH  sweep=[$BATCHES]"
echo "#   gen=$GEN tok/stream  warmup=$WARMUP  reps=$REPS  ${MODEL_ARG:-[manifest model]}"
echo "#   -> $OUT"

: > "$OUT"
wrote_header=0
failed=""

for b in $BATCHES; do
    log="results/logs/${PRESET}${SLUG}_b${b}.log"
    echo "=== [$(date -Is)] b=$b -> $log"
    # `make run` supplies the device paths + geometry env from the manifest; ARGS adds the
    # sweep-specific flags. --serve b overrides the runner's default single-stream --bench
    # path, and --csv makes it emit the machine-readable row we collect below.
    if ! make run PRESET="$PRESET" $MODEL_ARG POWER="$POWER" ${RUNENV:+ENV="$RUNENV"} \
            ARGS="--serve $b --gen $GEN --warmup $WARMUP --reps $REPS --csv $EXTRA" \
            > "$log" 2>&1; then
        echo "    FAILED (rc=$?) — see $log"
        failed="$failed $b"
        continue
    fi

    # The runner prints exactly one '#CSVH <header>' and one '#CSVR <values>' line. Capture
    # into variables rather than piping straight to the file: a pipeline's status is sed's,
    # which succeeds even when grep matched nothing, so a run that died before the timed
    # phase would silently append an empty line and be counted as a success.
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

    # Surface correctness + the headline number per row while the sweep runs, so a broken
    # bitstream is obvious immediately instead of after the whole sweep.
    grep -E '^serve B=|^  (ttft|decode|token latency)' "$log" | sed 's/^/    /'
done

echo
if [ -n "$failed" ]; then
    echo "# sweep finished with FAILURES at batch:$failed  (rows missing from $OUT)"
else
    echo "# sweep complete: $OUT"
fi

# The wide CSV above is the raw record (one row per run, every metric its own column) — it is
# ~65 columns and not meant to be read as a table. Split it into the readable sheets:
# _summary (the benchmark table), _ops and _phases (long-format profiling).
if [ "$wrote_header" -eq 1 ] && command -v python3 >/dev/null 2>&1; then
    python3 ./split_sweep_csv.py "$OUT" || echo "  WARN: split failed; the wide CSV is still complete"
fi

echo "# benchmark table:"
column -s, -t < "${OUT%_batch_sweep.csv}_summary.csv" 2>/dev/null || column -s, -t < "$OUT" 2>/dev/null || cat "$OUT"
[ -z "$failed" ]
