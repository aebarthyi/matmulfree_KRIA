#!/usr/bin/env bash
# scripts/bench_host_cpu.sh — CPU-only decode sweep on the BUILD SERVER (x86), with RAPL power.
# Same runner, model blobs and weight encodings as the board's CPU arms, so rows are comparable.
#
#   ./scripts/bench_host_cpu.sh                          # 3 models x 3 backends x b=1..6
#   BACKENDS=cpu BATCHES="1 6" ./scripts/bench_host_cpu.sh
#   MODELS=ridger/MMfreeLM-370M GEN=32 ./scripts/bench_host_cpu.sh
#
# Env:
#   MODELS     HF-id slugs under transfer/models/   (default: 370M, 1.3B, 2.7B)
#   BACKENDS   cpu (int8) | cpu-2bit | cpu-1p6      (default: all three)
#   BATCHES    --serve values                       (default: 1 2 3 4 5 6)
#   GEN/REPS/WARMUP  tokens / timed reps / warmup   (default: 64 / 3 / 1 — matches the board)
#   OUT        output dir                           (default: reports/host_cpu/<date>)
#   POWER_INTERVAL   RAPL sample period, seconds    (default: 0.2)
#   MAX_LOAD   refuse to start above this 1-min load average (default: nproc/4; 0 disables)
#
# No sudo and no device: cpu* backends never open /dev/mem, so the positional device args are
# placeholders (bench_batch.sh can't be reused: transfer.mk's `run` hardcodes sudo).
# Refuses to start above MAX_LOAD: next to a Vivado build it would measure the contention.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

RUNNER="$REPO_ROOT/software/integration/fpga_runner/build/mmfree-cli-fpga"
MODELS="${MODELS:-ridger/MMfreeLM-370M ridger/MMfreeLM-1.3B ridger/MMfreeLM-2.7B}"
BACKENDS="${BACKENDS:-cpu cpu-2bit cpu-1p6}"
BATCHES="${BATCHES:-1 2 3 4 5 6}"
GEN="${GEN:-64}"
REPS="${REPS:-3}"
WARMUP="${WARMUP:-1}"
POWER_INTERVAL="${POWER_INTERVAL:-0.2}"
OUT="${OUT:-$REPO_ROOT/reports/host_cpu/$(date +%Y-%m-%d)}"

NPROC=$(nproc)
MAX_LOAD="${MAX_LOAD:-$((NPROC / 4))}"

# THREADS default: physical cores, not nproc. 370M (issue-bound, fits L3) runs ~1.75x faster at
# 10 than 20 threads, but 1.3B/2.7B are memory-bound and prefer 20 -- sweep both
# (OMP_THREADS=20 ...) and take the per-model best, as final_sweep_xeon.sh does.
PHYS_CORES=$(awk -F: '/^physical id/{p=$2} /^core id/{print p"-"$2}' /proc/cpuinfo | sort -u | wc -l)
[ "${PHYS_CORES:-0}" -ge 1 ] 2>/dev/null || PHYS_CORES=$NPROC
OMP_THREADS="${OMP_THREADS:-$PHYS_CORES}"
export OMP_NUM_THREADS="$OMP_THREADS"
# Keep threads on distinct cores rather than letting the OS pair them onto SMT siblings.
export OMP_PROC_BIND="${OMP_PROC_BIND:-spread}"
export OMP_PLACES="${OMP_PLACES:-cores}"

[ -x "$RUNNER" ] || { echo "ERROR: $RUNNER missing. Build it with:" >&2
    echo "  make -C software/integration/fpga_runner" >&2; exit 1; }

# ─── Pre-flight: the machine must be idle ───────────────────────────────────
load1=$(awk '{printf "%d", $1 + 0.5}' /proc/loadavg)
if [ "$MAX_LOAD" != "0" ] && [ "$load1" -gt "$MAX_LOAD" ]; then
    echo "ERROR: 1-min load average is $(awk '{print $1}' /proc/loadavg), above MAX_LOAD=$MAX_LOAD." >&2
    echo "       Something else is using this machine (a Vivado build?). Measuring now would" >&2
    echo "       record the contention, not the model. Wait, or set MAX_LOAD=0 to override." >&2
    ps -eo pcpu,pid,comm --sort=-pcpu | head -6 >&2
    exit 1
fi

# ─── RAPL ───────────────────────────────────────────────────────────────────
# energy_uj needs a one-time chmod a+r; without it the power columns are left EMPTY, not guessed.
RAPL_PKG=""; RAPL_DRAM=""
for d in /sys/class/powercap/intel-rapl:*; do
    [ -r "$d/energy_uj" ] || continue
    case "$(cat "$d/name" 2>/dev/null)" in
        package-*) RAPL_PKG="$d" ;;
        dram)      RAPL_DRAM="$d" ;;
    esac
done
HAVE_POWER=0
[ -n "$RAPL_PKG" ] && HAVE_POWER=1
if [ "$HAVE_POWER" = "0" ]; then
    echo "WARN: no readable RAPL energy counter — power columns will be EMPTY." >&2
    echo "      Expose them with (resets on reboot):" >&2
    echo "        sudo chmod a+r /sys/class/powercap/intel-rapl:*/energy_uj" >&2
fi

rapl_uj() {   # $1 = domain dir; prints cumulative uJ, or nothing
    [ -n "$1" ] && cat "$1/energy_uj" 2>/dev/null
}

# The RAPL sampler is a background subshell that would outlive us (reparented to init): trap it.
sampler_pid=""
_cleanup() { [ -n "${sampler_pid:-}" ] && kill "$sampler_pid" 2>/dev/null; sampler_pid=""; }
trap '_cleanup; exit 130' INT TERM
trap _cleanup EXIT
rapl_max() { cat "$1/max_energy_range_uj" 2>/dev/null || echo 0; }
# RAPL counters wrap; correct a negative delta by one range.
uj_delta() { # $1 before, $2 after, $3 domain dir
    [ -n "$1" ] && [ -n "$2" ] || { echo ""; return; }
    awk -v a="$1" -v b="$2" -v m="$(rapl_max "$3")" \
        'BEGIN{d = b - a; if (d < 0) d += m; printf "%.0f", d}'
}

mkdir -p "$OUT/logs" "$OUT/power"
CSV="$OUT/host_cpu_sweep.csv"
: > "$CSV"
wrote_header=0

HOST_CPU=$(sed -n 's/^model name[[:space:]]*: //p' /proc/cpuinfo | head -1)
CORES=$(lscpu | awk -F: '/^Core\(s\) per socket/{gsub(/ /,"",$2); print $2}')
SOCKETS=$(lscpu | awk -F: '/^Socket\(s\)/{gsub(/ /,"",$2); print $2}')
GOV=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null || echo "?")

echo "############################################################"
echo "# host CPU sweep — $(hostname)"
echo "#   cpu    : $HOST_CPU  (${CORES}c x ${SOCKETS}s, $NPROC logical, governor=$GOV)"
echo "#   omp    : OMP_NUM_THREADS=$OMP_THREADS (physical cores=$PHYS_CORES; nproc=$NPROC would pair SMT siblings and cost ~1.8x)"
echo "#   models : $MODELS"
echo "#   backend: $BACKENDS"
echo "#   batches: $BATCHES   gen=$GEN reps=$REPS warmup=$WARMUP"
echo "#   power  : $([ "$HAVE_POWER" = 1 ] && echo "RAPL package$([ -n "$RAPL_DRAM" ] && echo "+dram")" || echo "UNAVAILABLE")"
echo "#   out    : $OUT"
echo "############################################################"

# ─── Idle baseline, so the workload's NET power is available ─────────────────
IDLE_PKG_W=""; IDLE_DRAM_W=""
if [ "$HAVE_POWER" = 1 ]; then
    echo "==> measuring idle baseline (10 s)"
    p0=$(rapl_uj "$RAPL_PKG"); d0=$(rapl_uj "$RAPL_DRAM"); t0=$(date +%s.%N)
    sleep 10
    p1=$(rapl_uj "$RAPL_PKG"); d1=$(rapl_uj "$RAPL_DRAM"); t1=$(date +%s.%N)
    el=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.3f", b-a}')
    IDLE_PKG_W=$(awk -v j="$(uj_delta "$p0" "$p1" "$RAPL_PKG")" -v s="$el" 'BEGIN{printf "%.2f", j/1e6/s}')
    [ -n "$RAPL_DRAM" ] && IDLE_DRAM_W=$(awk -v j="$(uj_delta "$d0" "$d1" "$RAPL_DRAM")" -v s="$el" 'BEGIN{printf "%.2f", j/1e6/s}')
    echo "    idle: pkg ${IDLE_PKG_W} W   dram ${IDLE_DRAM_W:-n/a} W"
fi

# ─── Sweep ──────────────────────────────────────────────────────────────────
failed=""
for model in $MODELS; do
    MDIR="$REPO_ROOT/transfer/models/$model"
    blob="$MDIR/model.mmfree"; tok="$MDIR/tokenizer.mmtok"
    if [ ! -f "$blob" ] || [ ! -f "$tok" ]; then
        echo "SKIP $model — missing model.mmfree/tokenizer.mmtok under $MDIR"
        failed="$failed $model/(missing)"; continue
    fi
    slug=$(printf '%s' "${model##*/}" | sed -E 's/^MMfreeLM-//I' | tr '[:upper:]' '[:lower:]')

    # Pre-fault the mmapped blob into page cache so the first timed arm (and its power window)
    # is not I/O-bound.
    echo "--> pre-touching $(du -h "$blob" | cut -f1) blob into page cache"
    cat "$blob" > /dev/null 2>&1 || true

    for backend in $BACKENDS; do
        for b in $BATCHES; do
            tag="${slug}_${backend}_b${b}"
            log="$OUT/logs/$tag.log"
            pwr="$OUT/power/$tag.pwr"
            echo "=== [$(date -Is)] $slug $backend b=$b -> $log"

            # Raw RAPL trace for exactly the workload window, kept so the summary can be re-derived.
            if [ "$HAVE_POWER" = 1 ]; then
                : > "$pwr"
                ( echo "# t_epoch pkg_uj dram_uj"
                  while :; do
                      printf '%s %s %s\n' "$(date +%s.%N)" \
                          "$(rapl_uj "$RAPL_PKG")" "$(rapl_uj "$RAPL_DRAM")"
                      sleep "$POWER_INTERVAL"
                  done ) >> "$pwr" 2>/dev/null &
                sampler_pid=$!
            fi

            p0=$(rapl_uj "$RAPL_PKG"); d0=$(rapl_uj "$RAPL_DRAM"); t0=$(date +%s.%N)
            # MMFREE_PRESET/CLK name the host in the CSV's preset/clk_mhz columns.
            # --bench is required: without it the runner ignores --warmup/--reps. --profile adds
            # the op_*/bl_* columns so the schema matches the board's rows.
            env MMFREE_PRESET="host_$(hostname)" MMFREE_CLK_MHZ="-" MMFREE_BATCH="-" \
                "$RUNNER" 0 0 - - - - \
                --blob "$blob" --tokenizer "$tok" \
                --backend "$backend" --serve "$b" --gen "$GEN" \
                --warmup "$WARMUP" --reps "$REPS" --bench --profile --csv > "$log" 2>&1
            rc=$?
            t1=$(date +%s.%N); p1=$(rapl_uj "$RAPL_PKG"); d1=$(rapl_uj "$RAPL_DRAM")
            [ -n "$sampler_pid" ] && kill "$sampler_pid" 2>/dev/null

            if [ "$rc" -ne 0 ]; then
                echo "    FAILED (rc=$rc) — see $log"; failed="$failed $tag"; continue
            fi
            row=$(grep -m1 '^#CSVR ' "$log" | sed 's/^#CSVR //')
            if [ -z "$row" ]; then
                echo "    WARN: no #CSVR row in $log"; failed="$failed $tag"; continue
            fi

            wall=$(awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.3f", b-a}')
            pkg_j=""; dram_j=""; pkg_w=""; dram_w=""; net_pkg_w=""
            if [ "$HAVE_POWER" = 1 ]; then
                pkg_j=$(awk -v u="$(uj_delta "$p0" "$p1" "$RAPL_PKG")" 'BEGIN{printf "%.1f", u/1e6}')
                pkg_w=$(awk -v j="$pkg_j" -v s="$wall" 'BEGIN{printf "%.2f", j/s}')
                net_pkg_w=$(awk -v w="$pkg_w" -v i="${IDLE_PKG_W:-0}" 'BEGIN{printf "%.2f", w-i}')
                if [ -n "$RAPL_DRAM" ]; then
                    dram_j=$(awk -v u="$(uj_delta "$d0" "$d1" "$RAPL_DRAM")" 'BEGIN{printf "%.1f", u/1e6}')
                    dram_w=$(awk -v j="$dram_j" -v s="$wall" 'BEGIN{printf "%.2f", j/s}')
                fi
            fi

            # Pull fields by NAME from the header, so a schema change cannot silently shift
            # what gets divided by watts.
            hdr=$(grep -m1 '^#CSVH ' "$log" | sed 's/^#CSVH //')
            fld() { awk -F, -v h="$hdr" -v r="$row" -v want="$1" 'BEGIN{
                n=split(h,H,","); split(r,R,",");
                for(i=1;i<=n;i++) if (H[i]==want) { print R[i]; exit } }'; }
            dtok=$(fld decode_tok_s); r_total=$(fld total_s); r_reps=$(fld reps)

            # TIMED-window power: the process ends with the timed reps, so integrate the trailing
            # reps*total_s seconds of the trace (excludes load + warmup). Falls back to the
            # whole-process mean when the trace is too short.
            timed_pkg_w=""; timed_dram_w=""
            if [ "$HAVE_POWER" = 1 ] && [ -s "$pwr" ] && [ -n "$r_total" ] && [ -n "$r_reps" ]; then
                read -r timed_pkg_w timed_dram_w <<<"$(awk \
                    -v win="$(awk -v t="$r_total" -v n="$r_reps" 'BEGIN{printf "%.3f", t*n}')" \
                    -v pm="$(rapl_max "$RAPL_PKG")" -v dm="$(rapl_max "${RAPL_DRAM:-$RAPL_PKG}")" '
                    /^#/ {next}
                    { t[++n]=$1; p[n]=$2; d[n]=$3 }
                    END{
                      if (n < 3) exit
                      # Unwrap first: the pkg counter wraps about every 44 min at ~100 W.
                      P[1]=p[1]; D[1]=d[1]; po=0; do_=0
                      for (i=2;i<=n;i++) {
                        if (p[i]+po < P[i-1]) po += pm; P[i]=p[i]+po
                        if (d[i]+do_ < D[i-1]) do_ += dm; D[i]=d[i]+do_
                      }
                      cut = t[n] - win
                      s = 0
                      for (i=1;i<=n;i++) if (t[i] >= cut) { s=i; break }
                      if (s == 0 || s >= n) exit
                      dt = t[n] - t[s]
                      if (dt <= 0) exit
                      printf "%.2f %.2f", (P[n]-P[s])/1e6/dt, (D[n]-D[s])/1e6/dt
                    }' "$pwr")"
            fi
            eff_pkg_w="${timed_pkg_w:-$pkg_w}"; eff_dram_w="${timed_dram_w:-$dram_w}"

            # tok/J over package+dram, charged at the TIMED-window power.
            tok_per_j=""
            if [ -n "$dtok" ] && [ -n "$eff_pkg_w" ]; then
                tok_per_j=$(awk -v t="$dtok" -v p="$eff_pkg_w" -v d="${eff_dram_w:-0}" \
                    'BEGIN{ tot=p+d; if (tot>0) printf "%.4f", t/tot }')
            fi

            if [ "$wrote_header" -eq 0 ]; then
                printf '%s,host,host_cpu,host_threads,omp_threads,governor,wall_s,pkg_j,dram_j,mean_pkg_w,mean_dram_w,timed_pkg_w,timed_dram_w,idle_pkg_w,idle_dram_w,net_pkg_w,tok_per_joule\n' \
                    "$hdr" >> "$CSV"
                wrote_header=1
            fi
            printf '%s,%s,"%s",%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
                "$row" "$(hostname)" "$HOST_CPU" "$NPROC" "$OMP_THREADS" "$GOV" "$wall" \
                "$pkg_j" "$dram_j" "$pkg_w" "$dram_w" \
                "$timed_pkg_w" "$timed_dram_w" \
                "${IDLE_PKG_W:-}" "${IDLE_DRAM_W:-}" "$net_pkg_w" "$tok_per_j" >> "$CSV"

            printf '    decode %s tok/s   timed pkg %s W (whole %s W)   %s tok/J   reps=%s (%.1fs)\n' \
                "${dtok:-?}" "${timed_pkg_w:-n/a}" "${pkg_w:-n/a}" "${tok_per_j:-n/a}" \
                "${r_reps:-?}" "$wall"
        done
    done
done

echo
echo "==> wrote $CSV ($(($(wc -l < "$CSV") - 1)) rows)"
[ -n "$failed" ] && echo "==> FAILED/SKIPPED:$failed"
exit 0
