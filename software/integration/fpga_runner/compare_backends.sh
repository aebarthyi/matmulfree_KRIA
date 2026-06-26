#!/usr/bin/env bash
# compare_backends.sh — the Phase-E deliverable: one CPU-vs-CPU+FPGA table.
#
# Runs the exact-match gate first (parity must hold before any timing is meaningful),
# then times decode on both backends with the SAME binary/model/harness — only the
# TernaryBackend differs — and renders a side-by-side table led by decode tok/s.
#
# Usage (run under sudo; the engine driver needs /dev/mem + udmabuf):
#   sudo ./compare_backends.sh <core_phys> <dma_phys> <uio> <act> <wt> <out> \
#        --blob model.mmfree --packed-dir packed [--gen 128 --reps 5 --ids 1,415,310]
#
# Everything you pass is forwarded verbatim to mmfree-cli-fpga as the COMMON args; the
# script appends --backend {both,cpu,fpga} and --bench/--profile itself, so do NOT put
# --backend or --bench in the args. Env knobs:
#   BIN=path        runner binary           (default <script dir>/build/mmfree-cli-fpga)
#   OUT_DIR=dir     where logs are written  (default ./compare_out)
#   PARSE_ONLY=1    skip running; re-render the table from existing logs in OUT_DIR
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="${BIN:-$SCRIPT_DIR/build/mmfree-cli-fpga}"
OUT_DIR="${OUT_DIR:-./compare_out}"
PARSE_ONLY="${PARSE_ONLY:-0}"

COMMON=("$@")
for a in "${COMMON[@]:-}"; do
    case "$a" in
        --backend|--bench)
            echo "error: do not pass $a — the script sets the backend/bench mode itself" >&2
            exit 2 ;;
    esac
done

mkdir -p "$OUT_DIR"
GATE_LOG="$OUT_DIR/gate.log"
CPU_LOG="$OUT_DIR/cpu.log"
FPGA_LOG="$OUT_DIR/fpga.log"

if [ "$PARSE_ONLY" != "1" ]; then
    [ -x "$BIN" ] || { echo "error: runner not found/executable: $BIN (build it: make)" >&2; exit 1; }

    echo "==> [1/3] exact-match gate (--backend both)"
    "$BIN" "${COMMON[@]}" --backend both | tee "$GATE_LOG"
    if ! grep -q "GATE PASS" "$GATE_LOG"; then
        echo; echo "GATE did not pass — refusing to report timing (a speedup with wrong" >&2
        echo "numerics is meaningless). See $GATE_LOG." >&2
        exit 1
    fi

    echo; echo "==> [2/3] CPU baseline (--backend cpu --bench --profile)"
    "$BIN" "${COMMON[@]}" --backend cpu  --bench --profile | tee "$CPU_LOG"

    echo; echo "==> [3/3] CPU + FPGA (--backend fpga --bench --profile)"
    "$BIN" "${COMMON[@]}" --backend fpga --bench --profile | tee "$FPGA_LOG"
fi

for f in "$CPU_LOG" "$FPGA_LOG"; do
    [ -f "$f" ] || { echo "error: missing $f (run without PARSE_ONLY first)" >&2; exit 1; }
done

# ---- extract metrics from a bench+profile log ----
# echoes: "<decode_tps> <prefill_ms> <overall_tps> <matmul_pct>"
metrics() {
    awk '
        /avg decode:/   { dec = $3 }
        /avg prefill:/  { pre = $3 }
        /avg overall:/  { ovr = $3 }
        /matmul \(incl lm_head\):/ { mm = $4; sub(/%/, "", mm) }
        END { printf "%s %s %s %s", (dec==""?"-":dec), (pre==""?"-":pre),
                                     (ovr==""?"-":ovr), (mm==""?"-":mm) }
    ' "$1"
}

read -r C_DEC C_PRE C_OVR C_MM <<<"$(metrics "$CPU_LOG")"
read -r F_DEC F_PRE F_OVR F_MM <<<"$(metrics "$FPGA_LOG")"

speedup="$(awk -v c="$C_DEC" -v f="$F_DEC" \
    'BEGIN { if (c+0>0 && f!="-") printf "%.2fx", f/c; else print "n/a" }')"
gate="$(grep -qs 'GATE PASS' "$GATE_LOG" && echo PASS || echo '? (no gate log)')"

printf '\n'
printf '================ CPU vs CPU+FPGA  (decode steady-state) ================\n'
printf '%-32s %14s %14s\n' 'metric' 'CPU' 'CPU+FPGA'
printf '%-32s %14s %14s   (%s)\n' 'decode tok/s  [headline]' "$C_DEC" "$F_DEC" "$speedup"
printf '%-32s %14s %14s\n' 'prefill ms'                "$C_PRE" "$F_PRE"
printf '%-32s %14s %14s\n' 'overall tok/s'             "$C_OVR" "$F_OVR"
printf '%-32s %13s%% %13s%%\n' 'matmul+lm_head % of profile' "$C_MM" "$F_MM"
printf '========================================================================\n'
printf 'parity gate: %s\n' "$gate"
printf 'logs: %s/{gate,cpu,fpga}.log\n' "$OUT_DIR"
