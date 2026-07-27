#!/usr/bin/env bash
# sweep_split_k.sh — find the best column-tile chunk count.
#
# The first A/B showed splitting is a TRADE, not a free win: at k=4/8 it hid ~59% of readback
# but handed ~40% of that back in per-chunk cost (extra STOREs, extra COMPUTE issues, and k
# separate weight-DMA transfers instead of one long one). So the chunk count has a knee, and
# the default ladder in fpga_backend.cpp was a guess. This sweeps it.
#
#   cd ~/transfer && sudo ./sweep_split_k.sh                      # k = 0 2 4 8 16 at b=max
#   cd ~/transfer && sudo ./sweep_split_k.sh K="0 2 3 4" BATCH=4
#
# k=0 disables splitting (the baseline). Every run is one batch size only (the largest the
# bitstream supports by default) — the point is the k axis, not the batch axis.
#
# Read bl_readback_hidden_pct (how much readback the split moved off the wall clock) against
# decode_tok_s (what survived after the per-chunk cost). They peak at different k; the second
# one is the one that matters.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"
[ "$(id -u)" -eq 0 ] || { echo "ERROR: run under sudo: sudo ./sweep_split_k.sh" >&2; exit 1; }

PRESET=${PRESET:-k26_mmfree2_7b_a16_b4_dsp_s8_packed}
EXTRA=${EXTRA:---packed-dir $(pwd)/models/ridger/MMfreeLM-2.7B/packed-1p6}
K=${K:-0 2 4 8 16}
GEN=${GEN:-64}
REPS=${REPS:-3}
for arg in "$@"; do case "$arg" in *=*) eval "${arg%%=*}='${arg#*=}'" ;; esac; done

MANIFEST="presets/$PRESET/preset.env"
[ -f "$MANIFEST" ] || { echo "ERROR: no manifest at $MANIFEST" >&2; exit 1; }
BATCH=${BATCH:-$(grep -hE '^MMFREE_BATCH=' "$MANIFEST" | cut -d= -f2)}

echo "# chunk sweep: preset=$PRESET  b=$BATCH  k=[$K]"
./deploy_kria.sh "$PRESET" > "results/logs/deploy_$PRESET.log" 2>&1 || {
    echo "!! deploy-hw FAILED"; tail -20 "results/logs/deploy_$PRESET.log"; exit 1; }

for k in $K; do
    echo "=== k=$k"
    args=("PRESET=$PRESET" "BATCHES=$BATCH" "GEN=$GEN" "REPS=$REPS" "TAG=k$k"
          "ENV=MMFREE_SPLIT_CHUNKS=$k")
    [ -n "$EXTRA" ] && args+=("EXTRA=$EXTRA")
    ./bench_batch.sh "${args[@]}" >/dev/null 2>&1 || { echo "   FAILED"; continue; }
    f="results/${PRESET}_k${k}_summary.csv"
    [ -f "$f" ] && python3 - "$f" "$k" <<'PY'
import csv, sys
r = next(csv.DictReader(open(sys.argv[1])))
print(f"   k={sys.argv[2]:>2}  decode={float(r['decode_tok_s']):6.2f} tok/s  "
      f"toklat={float(r['token_latency_ms']):7.2f} ms  "
      f"hidden={float(r.get('bl_readback_hidden_pct') or 0):5.1f}%  "
      f"engine={float(r['bl_total_ms']):7.1f} ms  ok={r['streams_ok']}")
PY
done

[ -n "${SUDO_USER:-}" ] && chown -R "$SUDO_USER" results 2>/dev/null
python3 ./split_sweep_csv.py --combine "results/split_k" results/${PRESET}_k*_batch_sweep.csv >/dev/null 2>&1
echo
echo "# done -> results/split_k_summary.csv  (pick the k with the best decode_tok_s)"
