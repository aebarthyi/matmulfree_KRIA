#!/usr/bin/env bash
# clone_preset_hw.sh SRC_PRESET DST_PRESET
#
# Assemble transfer/presets/<DST>/ from an ALREADY-BUILT <SRC> bitstream, with DST's own
# manifest and the overlay's udmabuf-wt nodes re-sized to DST's resident weight set. No Vivado.
# For presets with byte-identical RTL (1.3B and 2.7B share one engine geometry).
#
# Refused unless (1) generated/<SRC> and generated/<DST> hold byte-identical .sv, and (2) their
# preset.env differ only in ALLOWED_KEYS. The patched dtbo is verified by a DTS round-trip diff.
# The clone's timing record is SRC's reports/<SRC>_*.rpt (it is the same bitstream file).
#
# Usage:
#   ./mill matmulfree_KRIA.runMain core.EmitCore <DST> generated/<DST>   # manifest + SV
#   ./scripts/clone_preset_hw.sh <SRC> <DST>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

[ $# -eq 2 ] || { echo "usage: $0 SRC_PRESET DST_PRESET" >&2; exit 1; }
SRC="$1"; DST="$2"
APP="core_bench"

SRC_HW="transfer/presets/$SRC"
DST_HW="transfer/presets/$DST"
SRC_GEN="generated/$SRC"
DST_GEN="generated/$DST"

# Keys allowed to differ: everything else is geometry the bitstream bakes in.
ALLOWED_KEYS="MMFREE_PRESET MMFREE_SHAPES MMFREE_MODEL UDMABUF_WT_SZ"

for f in "$SRC_HW/$APP.bit.bin" "$SRC_HW/$APP.dtbo" "$SRC_HW/shell.json"; do
    [ -f "$f" ] || { echo "ERROR: $f missing — build SRC first (make build PRESET=$SRC)." >&2; exit 1; }
done
for f in "$SRC_GEN/preset.env" "$DST_GEN/preset.env"; do
    [ -f "$f" ] || { echo "ERROR: $f missing — run 'core.EmitCore' for that preset." >&2; exit 1; }
done
command -v dtc >/dev/null || { echo "ERROR: 'dtc' not on PATH (source the Vitis settings)." >&2; exit 1; }

# ─── 1. RTL identity ────────────────────────────────────────────────────────
echo "==> [1/4] Verifying $SRC and $DST generate identical RTL"
sv_hash() { (cd "$1" && ls *.sv 2>/dev/null | sort | xargs cat | sha256sum | cut -d' ' -f1); }
sv_list() { (cd "$1" && ls *.sv 2>/dev/null | sort); }
if [ "$(sv_list "$SRC_GEN")" != "$(sv_list "$DST_GEN")" ]; then
    echo "ERROR: SV file sets differ — different RTL, cannot clone. Build $DST." >&2
    diff <(sv_list "$SRC_GEN") <(sv_list "$DST_GEN") >&2 || true; exit 1
fi
h_src="$(sv_hash "$SRC_GEN")"; h_dst="$(sv_hash "$DST_GEN")"
[ "$h_src" = "$h_dst" ] || {
    echo "ERROR: SV contents differ (${h_src:0:16} vs ${h_dst:0:16}) — different RTL, cannot clone." >&2
    for f in $(sv_list "$SRC_GEN"); do
        cmp -s "$SRC_GEN/$f" "$DST_GEN/$f" || echo "    differs: $f" >&2
    done; exit 1; }
echo "    RTL sha256 ${h_src:0:16} — identical ($(sv_list "$SRC_GEN" | wc -l) SV files)"

# ─── 2. Manifest delta is non-RTL only ──────────────────────────────────────
echo "==> [2/4] Verifying the manifest delta touches only: $ALLOWED_KEYS"
keys_of() { grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$1" | cut -d= -f1 | sort; }
bad=0
# A key only in DST is usually a newer manifest field that SRC's older tree didn't emit: benign
# only at its default (empty or 0). Keys missing from DST are never acceptable (DST's ships).
while read -r k; do
    grep -qE "^$k=" "$SRC_GEN/preset.env" && continue
    vd=$(grep -E "^$k=" "$DST_GEN/preset.env" | head -1 | cut -d= -f2-)
    case "$vd" in
        ''|0) printf "    %-26s (new manifest field, default '%s' — SRC predates it)\n" "$k" "$vd" ;;
        *) echo "    !! $k=$vd is new AND non-default — SRC's bitstream cannot honour it" >&2
           bad=1 ;;
    esac
done < <(keys_of "$DST_GEN/preset.env")
missing=$(comm -23 <(keys_of "$SRC_GEN/preset.env") <(keys_of "$DST_GEN/preset.env"))
[ -z "$missing" ] || { echo "ERROR: keys present in SRC but absent from DST: $missing" >&2; exit 1; }
while read -r k; do
    vs=$(grep -E "^$k=" "$SRC_GEN/preset.env" | head -1 | cut -d= -f2-)
    vd=$(grep -E "^$k=" "$DST_GEN/preset.env" | head -1 | cut -d= -f2-)
    [ "$vs" = "$vd" ] && continue
    case " $ALLOWED_KEYS " in
        *" $k "*) printf "    %-18s %s -> %s\n" "$k" "$vs" "$vd" ;;
        *) echo "    !! DISALLOWED: $k  $vs -> $vd" >&2; bad=1 ;;
    esac
done < <(keys_of "$SRC_GEN/preset.env")
[ "$bad" -eq 0 ] || { echo "ERROR: geometry differs — $DST needs its own Vivado build." >&2; exit 1; }

# ─── 3. Copy the bitstream, patch the overlay's udmabuf-wt size ─────────────
# u-dma-buf takes one 32-bit 'size' cell per node; NUM_DMA nodes (udmabuf-wtN) carry the
# per-port slice. Patch the raw big-endian cells in place: same width, and the FDT header
# has no checksum, so structure/__fixups__/__symbols__ are untouched by construction.
src_wt=$(grep -E '^UDMABUF_WT_SZ=' "$SRC_GEN/preset.env" | cut -d= -f2)
dst_wt=$(grep -E '^UDMABUF_WT_SZ=' "$DST_GEN/preset.env" | cut -d= -f2)
n_dma=$(grep -E '^NUM_DMA=' "$DST_GEN/preset.env" | cut -d= -f2)
echo "==> [3/4] Staging $DST_HW/ (udmabuf-wt $src_wt -> $dst_wt x $n_dma ports)"
mkdir -p "$DST_HW"
cp "$SRC_HW/$APP.bit.bin" "$DST_HW/"
cp "$SRC_HW/shell.json"   "$DST_HW/"
cp "$DST_GEN/preset.env"  "$DST_HW/"
cp "$DST_GEN/preset.json" "$DST_HW/" 2>/dev/null || true

if [ "$src_wt" = "$dst_wt" ]; then
    cp "$SRC_HW/$APP.dtbo" "$DST_HW/"
    echo "    WT unchanged — dtbo copied verbatim"
else
    python3 - "$SRC_HW/$APP.dtbo" "$DST_HW/$APP.dtbo" "$src_wt" "$dst_wt" "$n_dma" <<'PY'
import sys
src, dst, old, new, n = sys.argv[1], sys.argv[2], int(sys.argv[3], 0), int(sys.argv[4], 0), int(sys.argv[5])
blob = open(src, 'rb').read()
ob, nb = old.to_bytes(4, 'big'), new.to_bytes(4, 'big')
hits = blob.count(ob)
if hits != n:
    sys.exit(f"ERROR: expected {n} udmabuf-wt size cells ({hex(old)}), found {hits} — "
             "refusing to patch (the value may be aliased elsewhere in the blob).")
if new > old:
    print(f"    WARNING: growing WT {hex(old)} -> {hex(new)}; CMA must cover {n} x that.")
open(dst, 'wb').write(blob.replace(ob, nb))
print(f"    patched {hits} size cells {hex(old)} -> {hex(new)}")
PY
fi

# ─── 4. Verify the patched overlay ──────────────────────────────────────────
# Re-decompile both and diff: the ONLY permitted change is the wt size lines.
echo "==> [4/4] Verifying the patched overlay (dtc round-trip diff)"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
dtc -I dtb -O dts -o "$tmp/src.dts" "$SRC_HW/$APP.dtbo" 2>/dev/null
dtc -I dtb -O dts -o "$tmp/dst.dts" "$DST_HW/$APP.dtbo" 2>/dev/null
unexpected=$(diff "$tmp/src.dts" "$tmp/dst.dts" | grep -E '^[<>]' | grep -vcE '^[<>][[:space:]]+size = <' || true)
changed=$(diff "$tmp/src.dts" "$tmp/dst.dts" | grep -cE '^>[[:space:]]+size = <' || true)
if [ "$unexpected" -ne 0 ]; then
    echo "ERROR: overlay diff touched more than the wt size cells:" >&2
    diff "$tmp/src.dts" "$tmp/dst.dts" >&2 || true; exit 1
fi
[ "$src_wt" = "$dst_wt" ] || [ "$changed" -eq "$n_dma" ] || {
    echo "ERROR: $changed size lines changed, expected $n_dma." >&2; exit 1; }
grep -c "size = <$(printf '0x%x' $((dst_wt)))>" "$tmp/dst.dts" >/dev/null \
    && echo "    $changed of $changed wt size cells now $(printf '0x%x' $((dst_wt))); nothing else changed"

cat <<EOF

# $DST_HW/ ready (bitstream shared with $SRC — same file, so $SRC's
# reports/${SRC}_*.rpt WNS is its timing record).
# Next:
#   make model  PRESET=$DST     # provision + pack $(grep -E '^MMFREE_MODEL=' "$DST_GEN/preset.env" | cut -d= -f2)
#   make deploy BOARD=...
#   on the board:  sudo make deploy-hw PRESET=$DST && make gate PRESET=$DST
EOF
