#!/usr/bin/env bash
# cma_boot_raise.sh — raise the kernel's CMA reservation persistently, by rebuilding boot.scr.
#
#   sudo ./cma_boot_raise.sh 1536M        # set it (takes effect on the NEXT boot)
#   sudo ./cma_boot_raise.sh --show       # print the current value, change nothing
#
# Prefer cma_reclaim.sh (frees PYNQ/XRT's CMA, no boot changes) unless you need more than the
# boot reservation. This board sets cma unconditionally inside the U-Boot script image (no
# cmdline.txt, no usable U-Boot env), so the script text itself is edited: the first cma= after
# the `test -n $kria` arm of the ANCHOR block. cma="1000M" also appears in another arm, so never
# substitute globally.
# Image layout: [64-byte uImage header][4-byte payload length][4-byte 0][script text]. Slicing at
# the wrong offset double-wraps the script and makes it unbootable, hence the round-trip check.
# A bigger CMA only leaves less page cache for the mmapped model blob (slower CPU-side paths).
# Recovery: a timestamped backup is written beside the original; restore it from a rescue boot
# or by mounting the FAT boot partition on another machine.
set -uo pipefail

SCR=/boot/firmware/boot.scr.uimg
HDR=64                                  # uImage header
PFX=8                                   # multi-file length + terminator
OFF=$((HDR + PFX))                      # script text begins here (0-based)

[ -f "$SCR" ] || { echo "ERROR: $SCR not found — is /boot/firmware mounted?" >&2; exit 1; }

payload() { tail -c +$((OFF + 1)) "$1"; }

ANCHOR='Configuring the cma value based on the board type'

show() {
    echo "# cma selection block in $SCR (this board takes the 'elif  test -n \$kria' arm):"
    # Scope to the block (anchor .. where cma is appended); `test -n $kria` alone appears 4x.
    payload "$SCR" | grep -n '' | sed -n "/$ANCHOR/,/if test -n \$cma/p" |
        grep -E 'cma="[0-9]+[MG]"|test -n \$kria|test \$kria' | sed 's/^/    /'
    echo "# kernel actually booted with:"
    tr -d '\000' < /proc/device-tree/chosen/bootargs | tr ' ' '\n' | grep -E '^cma=' | sed 's/^/    /'
}

[ "${1:-}" = "--show" ] && { show; exit 0; }

SIZE=${1:-}
# Insist on the U-Boot form: a malformed value boots with no CMA at all.
[[ "$SIZE" =~ ^[0-9]+[MG]$ ]] || {
    echo "usage: sudo $0 <size>   e.g. 1536M   (or --show)" >&2
    echo "  size must be digits followed by M or G, matching the script's existing form." >&2
    exit 2
}
[ "$(id -u)" -eq 0 ] || { echo "ERROR: needs root to write $SCR: sudo $0 $SIZE" >&2; exit 1; }
command -v mkimage >/dev/null || { echo "ERROR: mkimage missing — apt-get install u-boot-tools" >&2; exit 1; }

show
echo

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
payload "$SCR" > "$TMP/old.cmd"

# Sanity-check the slice really is the script text and nothing but it.
head -c 2 "$TMP/old.cmd" | grep -q $'\n#' || {
    echo "ERROR: payload at offset $OFF does not start like the stock script (expected a comment)." >&2
    echo "       The image layout differs from what this tool verified — inspect by hand." >&2
    exit 1
}
# The anchor comment is unique and immediately precedes the selection block.
[ "$(grep -cF "$ANCHOR" "$TMP/old.cmd")" -eq 1 ] || {
    echo "ERROR: expected exactly one '$ANCHOR' line to anchor on." >&2
    echo "       The script differs from what this tool verified — edit by hand." >&2; exit 1; }

# Edit only the FIRST cma= at/after the Kria arm (the 102/111 arm also says 1000M).
awk -v size="$SIZE" -v anchor="$ANCHOR" '
    index($0, anchor)                   { inblock = 1 }
    inblock && /test -n \$kria/         { arm = 1 }
    arm && !done && /cma="[0-9]+[MG]"/  { sub(/cma="[0-9]+[MG]"/, "cma=\"" size "\""); done = 1 }
    { print }
    END { if (!done) exit 3 }
' "$TMP/old.cmd" > "$TMP/new.cmd" || {
    echo "ERROR: found no cma= assignment after the Kria arm — nothing changed." >&2; exit 1; }

# Exactly one line may differ, and it must be the cma one.
diffcount=$(diff <(cat "$TMP/old.cmd") <(cat "$TMP/new.cmd") | grep -c '^[<>]' || true)
[ "$diffcount" -eq 2 ] || {
    echo "ERROR: edit touched $((diffcount / 2)) lines, expected exactly 1 — refusing." >&2; exit 1; }
echo "# edit:"; diff "$TMP/old.cmd" "$TMP/new.cmd" | grep '^[<>]' | sed 's/^/    /'

# -A arm (NOT arm64) and -n "boot script" reproduce the stock header; only the header CRC,
# timestamp and data CRC differ.
mkimage -A arm -T script -C none -n "boot script" -d "$TMP/new.cmd" "$TMP/new.uimg" >/dev/null || {
    echo "ERROR: mkimage failed — $SCR left UNCHANGED." >&2; exit 1; }

# The os/arch/type/comp quad and the size field must be identical to the running image.
cmp -s <(dd if="$SCR" bs=1 skip=12 count=4 2>/dev/null) <(dd if="$TMP/new.uimg" bs=1 skip=12 count=4 2>/dev/null) &&
cmp -s <(dd if="$SCR" bs=1 skip=28 count=4 2>/dev/null) <(dd if="$TMP/new.uimg" bs=1 skip=28 count=4 2>/dev/null) || {
    echo "ERROR: rebuilt header's size or os/arch/type/comp differs from stock — $SCR UNCHANGED." >&2; exit 1; }

# Decisive check: the rebuilt payload must be byte-identical to what we fed in (catches a
# wrong offset / double-wrap).
payload "$TMP/new.uimg" > "$TMP/roundtrip.cmd"
cmp -s "$TMP/new.cmd" "$TMP/roundtrip.cmd" || {
    echo "ERROR: rebuilt payload is not byte-identical to the edited script (offset/wrap bug)." >&2
    echo "       $SCR left UNCHANGED." >&2
    ls -l "$TMP/new.cmd" "$TMP/roundtrip.cmd" >&2
    exit 1; }

BAK="$SCR.$(date +%Y%m%d-%H%M%S).bak"
cp -a "$SCR" "$BAK"
echo "# backed up -> $BAK"
cp "$TMP/new.uimg" "$SCR"
sync
echo "# wrote $SCR"
echo
show
echo
echo "# Reboot to apply, then confirm:"
echo "#   tr -d '\\000' < /proc/device-tree/chosen/bootargs | tr ' ' '\\n' | grep ^cma="
echo "# If the board does not come back, restore $BAK from a rescue boot / external mount."
