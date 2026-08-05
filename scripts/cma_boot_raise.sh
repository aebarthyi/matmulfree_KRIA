#!/usr/bin/env bash
# cma_boot_raise.sh — raise the kernel's CMA reservation persistently, by rebuilding boot.scr.
#
# PREFER cma_reclaim.sh. That frees ~477 MiB held by PYNQ/XRT with no boot changes and is
# reversible by rebooting. This script exists for when you need MORE than the stock 1000 MiB
# total (e.g. a future preset whose resident weight set exceeds it), not merely to clear the
# ~100 MiB shortfall the 2-bit 2.7B presets currently hit.
#
# HOW CMA IS SET ON THIS BOARD. There is no cmdline.txt and no usable U-Boot env
# (/etc/fw_env.config is absent — fw_printenv reports "Configuration file wrong or corrupted").
# /boot/firmware/boot.scr.uimg is a U-Boot *script image* whose payload is plain text; it builds
# bootargs, then picks cma by board type:
#     if test $kria = "KD"; then cma="512M"
#     elif  test -n $kria;  then cma="1000M"     <-- this board (K26); the arm we edit
#   ... other arms for 104/106/102/111/vck190 ...
#   ... and finally:  if test -n $cma; then setenv bootargs "${bootargs} cma=${cma}"
# The assignment is unconditional, so a saved env var cannot override it — the script itself has
# to change. NOTE cma="1000M" appears TWICE (the Kria arm and the 102/111 arm), so this edits by
# position relative to the `test -n $kria` line, never by a blind global substitution.
#
# IMAGE LAYOUT (verified on this board, 2026-07-29). A uImage script is NOT just header+text:
#     [64-byte uImage header][4-byte payload length][4-byte 0 terminator][script text]
# For the stock file: header data-size 0x20a0 = 8352, payload length 0x2098 = 8344, file 8416
# bytes = 64 + 8 + 8344. So the text starts at byte 73 and mkimage re-adds BOTH prefixes. Slicing
# at 65 instead of 73 silently embeds the old length prefix into the new payload and yields a
# double-wrapped, unbootable script — hence PFX below and the byte-exact round-trip check.
#
#   sudo ./cma_boot_raise.sh 1536M        # set it
#   sudo ./cma_boot_raise.sh --show       # print the current value, change nothing
#
# COST OF RAISING IT. CMA is carved out of the same 4 GB as everything else. model.mmfree is
# mmap'd read-only and viewed in place (weights.cpp:200, MAP_PRIVATE/PROT_READ), so it is
# evictable page cache, not resident allocation — a bigger CMA will NOT OOM the 2.95 GB blob, it
# just leaves less cache for it. The visible effect is a slower CPU-side path (the gate reads
# every weight through that mmap); the FPGA decode path streams from udmabuf and barely cares.
#
# Takes effect on the NEXT boot. RECOVERY: a timestamped backup is written beside the original,
# and the stock boot.scr.uimg.bak is left untouched — restore either from a rescue boot or by
# mounting the FAT boot partition on another machine.
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
    # Scope to the block: from the unique anchor to where cma is appended to bootargs. `test -n
    # $kria` on its own appears 4x in the wider script, so it is NOT a usable anchor.
    payload "$SCR" | grep -n '' | sed -n "/$ANCHOR/,/if test -n \$cma/p" |
        grep -E 'cma="[0-9]+[MG]"|test -n \$kria|test \$kria' | sed 's/^/    /'
    echo "# kernel actually booted with:"
    tr -d '\000' < /proc/device-tree/chosen/bootargs | tr ' ' '\n' | grep -E '^cma=' | sed 's/^/    /'
}

[ "${1:-}" = "--show" ] && { show; exit 0; }

SIZE=${1:-}
# Insist on the U-Boot form (digits + M/G). A malformed value becomes an unparsable bootarg and
# surfaces as a board that boots with no CMA at all.
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
# `test -n $kria` appears 4x across the script, so it cannot anchor the edit on its own. The
# "Configuring the cma value" comment is unique and immediately precedes the selection block.
[ "$(grep -cF "$ANCHOR" "$TMP/old.cmd")" -eq 1 ] || {
    echo "ERROR: expected exactly one '$ANCHOR' line to anchor on." >&2
    echo "       The script differs from what this tool verified — edit by hand." >&2; exit 1; }

# Inside the block, take the FIRST cma= assignment at/after the Kria arm. The 102/111 arm also
# says 1000M and must be left alone, so a global sed is wrong here.
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

# -A arm (NOT arm64) and -n "boot script" reproduce the stock header exactly: with these, the
# only bytes that differ from the original are ih_hcrc (0x04), ih_time (0x08) and ih_dcrc (0x18),
# all of which must differ. -A arm64 would flip ih_arch 0x02 -> 0x16; U-Boot most likely ignores
# arch for a script image, but matching stock removes the question entirely.
mkimage -A arm -T script -C none -n "boot script" -d "$TMP/new.cmd" "$TMP/new.uimg" >/dev/null || {
    echo "ERROR: mkimage failed — $SCR left UNCHANGED." >&2; exit 1; }

# The os/arch/type/comp quad and the size field must be identical to the running image.
cmp -s <(dd if="$SCR" bs=1 skip=12 count=4 2>/dev/null) <(dd if="$TMP/new.uimg" bs=1 skip=12 count=4 2>/dev/null) &&
cmp -s <(dd if="$SCR" bs=1 skip=28 count=4 2>/dev/null) <(dd if="$TMP/new.uimg" bs=1 skip=28 count=4 2>/dev/null) || {
    echo "ERROR: rebuilt header's size or os/arch/type/comp differs from stock — $SCR UNCHANGED." >&2; exit 1; }

# THE decisive check: the rebuilt image's payload must be byte-identical to what we fed in. This
# is what catches a wrong offset / double-wrap, which a mere grep for the new value would not.
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
