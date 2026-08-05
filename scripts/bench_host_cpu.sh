#!/usr/bin/env bash
# scripts/bench_host_cpu.sh — CPU-only decode sweep on the BUILD SERVER (x86), with power.
#
# The point: the 12-arm matrix compares the KRIA's FPGA engine against the KRIA's own four
# A53s. That answers "is the offload worth it on this board" but says nothing about how the
# whole thing lands against a normal machine. This runs the SAME driver, the SAME model blobs
# and the SAME weight encodings on the build server's Xeon, so the rows are directly
# comparable — and samples RAPL so the interesting axis (tokens per joule) is available too.
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
# NO sudo and NO device: for any cpu* backend the runner installs the CPU backend before it
# ever opens /dev/mem or a udmabuf (main_fpga.cpp), so the positional device arguments below
# are placeholders that are never dereferenced. This is why the board harness
# (bench_batch.sh -> transfer.mk `run`) cannot be reused here: its `runwrap` hardcodes sudo.
#
# WHY THE LOAD GUARD: a Vivado build uses every core, and a CPU sweep run next to one measures
# the contention, not the model — a 370M b=1 arm read 0.24 tok/s against a concurrent build
# where it should read tens. Silent, plausible, and completely wrong, so it is a hard refusal
# rather than a warning.
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

# THREADS: physical cores, NOT nproc. This host is 10 cores x 2 SMT threads and the default
# OpenMP behaviour (one thread per LOGICAL cpu = 20) is badly wrong for this workload — the
# ternary dot is vector/issue-bound, so two hyperthread siblings on one core contend for the
# same execution units instead of adding throughput.
#
# MEASURED (370M cpu-2bit, ms per batch row, lower is better; replicated with the thread
# counts INTERLEAVED so ordering could not masquerade as the effect):
#     threads     b2     b3     b4     b5     b6
#         20   10.04   9.08   9.02   5.61   7.92
#         15    5.88   6.32   6.21   5.53   5.60
#         10    5.08   5.62   5.47   4.87   4.79
# 1.77x at b=4 for HALVING the thread count, and the curve goes flat.
#
# This also explains the "b=5 is anomalously fast" outlier in every 20-thread sweep: b=5 was
# the one batch size partly escaping the contention. At 10 threads the 370M curve is monotonic
# (184 -> 209 tok/s over b=1..6) and the outlier is GONE.
#
# ⚠️ THE OPTIMUM IS MODEL-DEPENDENT — this default is right for 370M and WRONG for the larger
# models. Full-sweep 20 -> 10 threads at b=6:
#     370M  2-bit 1.75x   1.6-bit 1.71x     <- big win
#     1.3B  2-bit 1.03x   1.6-bit 0.88x     <- no gain / 12% LOSS
#     2.7B  2-bit 0.98x   1.6-bit 0.88x     <- no gain / 12% LOSS
# 370M fits in the 13.75 MB L3 and is issue-bound, so SMT siblings only contend; 1.3B/2.7B are
# memory-bound, where extra threads hide DRAM latency and SMT pays for itself. Sweep BOTH and
# take the per-model best (OMP_THREADS=20 ./scripts/bench_host_cpu.sh) rather than trusting
# either default — the first version of this comment generalized a 370M-only measurement to
# all three models, which was wrong.
#
# Physical cores = distinct core_id values in /proc/cpuinfo (robust to SMT and to multi-socket
# via the "physical id, core id" pair). Falls back to nproc if that cannot be determined.
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
# energy_uj is 0400 on stock Ubuntu (the PLATYPUS side-channel mitigation), so power needs a
# one-time `sudo chmod a+r`. Degrade gracefully: the tok/s half of the sweep is still valid
# without it, and the power columns are left EMPTY rather than filled with a guess.
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

# The per-arm RAPL sampler is a background subshell. Killing THIS script does not kill it —
# it gets reparented to init and keeps looping forever. One such orphan was found spinning at
# 200 ms intervals for 85 minutes after an interrupted run, which also means it was still
# appending to a .pwr file nobody was reading. Trap so the sampler always dies with us.
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

    # Pull the blob into page cache ONCE per model, before any timed arm. weights.cpp mmaps
    # the blob and views it in place, so on a cold file the pages fault in lazily — and the
    # very first arm of the sweep paid ~27 s of that inside its measured window (370M 2-bit
    # b=1 read 12.31 tok/s with an 81 ms step, the same step time as its OWN b=6, while the
    # warm 1.6-bit b=1 did 10.8 ms). --bench's warmup rep now absorbs it too, but doing it
    # here as well keeps the FIRST arm's power/energy from being diluted by an I/O-bound
    # window, which the warmup alone would not fix.
    echo "--> pre-touching $(du -h "$blob" | cut -f1) blob into page cache"
    cat "$blob" > /dev/null 2>&1 || true

    for backend in $BACKENDS; do
        for b in $BATCHES; do
            tag="${slug}_${backend}_b${b}"
            log="$OUT/logs/$tag.log"
            pwr="$OUT/power/$tag.pwr"
            echo "=== [$(date -Is)] $slug $backend b=$b -> $log"

            # Raw RAPL trace for exactly the workload window, kept as ground truth so the
            # summary below can be re-derived or re-windowed later (power_bench.sh precedent).
            :  # sampler_pid is declared at top level (trap cleanup)
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
            # MMFREE_PRESET/CLK are what the runner stamps into the CSV's preset/clk_mhz
            # columns; unset they default to "?" and a host row becomes indistinguishable
            # from a board row. Name the host explicitly instead.
            # --bench IS REQUIRED, not decoration: main_fpga.cpp gates BOTH the warmup and
            # the rep count on it — `for (i=0; i < (bench ? warmup : 0); ++i)` and
            # `const int rr = bench ? reps : 1`. Without it --warmup/--reps are silently
            # ignored, every row is a single un-warmed sample, and the first arm of a model
            # measures the blob faulting in. --profile adds the op_*/bl_* breakdown columns
            # the board's rows carry, so the schemas match. The board gets both from
            # transfer.mk's `run` target, which is why bench_batch.sh never passes them.
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

            # TIMED-WINDOW power. Whole-process mean is diluted by model load + the untimed
            # warmup rep, which are not what we want to charge tokens against. The process
            # ENDS with the timed reps, so the trailing (reps * total_s) seconds of the raw
            # trace is exactly the measured portion — integrate the counters over that window.
            # Falls back to the whole-process mean when the trace is too short to window.
            timed_pkg_w=""; timed_dram_w=""
            if [ "$HAVE_POWER" = 1 ] && [ -s "$pwr" ] && [ -n "$r_total" ] && [ -n "$r_reps" ]; then
                read -r timed_pkg_w timed_dram_w <<<"$(awk \
                    -v win="$(awk -v t="$r_total" -v n="$r_reps" 'BEGIN{printf "%.3f", t*n}')" \
                    -v pm="$(rapl_max "$RAPL_PKG")" -v dm="$(rapl_max "${RAPL_DRAM:-$RAPL_PKG}")" '
                    /^#/ {next}
                    { t[++n]=$1; p[n]=$2; d[n]=$3 }
                    END{
                      if (n < 3) exit
                      # UNWRAP first. The package counter range is ~262 kJ, which at ~100 W
                      # wraps about every 44 minutes, so a multi-arm sweep hits it routinely —
                      # an earlier version just bailed on the negative delta and silently fell
                      # back to whole-process power for those arms.
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
