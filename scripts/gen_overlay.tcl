# scripts/gen_overlay.tcl — generate the K26_Bench device-tree overlay (+ bitstream
# + shell.json) from a Vivado-exported .xsa, ready for `xmutil loadapp`.
#
# Flow (mirrors the known-good manual process):
#   1. xsct `createdts` generates pl.dtsi from the .xsa — this brings in the
#      critical fragments a hand-written overlay misses:
#        - &fpga_full  firmware-name        → fpga-manager actually programs the PL
#        - clocking0 (xlnx,fclk)            → PL0_REF claimed, no clk_ignore_unused hack
#        - afi0                             → PS↔PL AXI port-width config
#        - CoreTop placeholder (generic-uio) + axi_dma node, with correct addrs/IRQs
#   2. Patch firmware-name → core_bench.bit.bin.
#   3. Append the u-dma-buf nodes, sized + counted from the preset manifest.
#   4. Compile with dtc.
#
# Usage:
#   xsct gen_overlay.tcl <xsa_file> [<out_dir>]
#
# Environment:
#   DT_BRANCH   device-tree-xlnx git branch for createdts.
#               Default: xlnx_rel_v2024.1 (proven against XSCT 2024.1/2025.x).
#   DT_REPO     local clone of device-tree-xlnx (skips network access).
#
# Produces in <out_dir>:
#   core_bench.bit / .dts / .dtbo / shell.json

if {[llength $argv] < 1} {
    puts stderr "Usage: xsct gen_overlay.tcl <xsa_file> \[<out_dir>\]"
    exit 1
}

set xsa_file [file normalize [lindex $argv 0]]
if {![file exists $xsa_file]} {
    puts stderr "ERROR: .xsa not found: $xsa_file"
    exit 1
}

set out_dir "build/overlay"
if {[llength $argv] > 1 && [string index [lindex $argv 1] 0] ne "-"} {
    set out_dir [lindex $argv 1]
}
set out_dir [file normalize $out_dir]
file mkdir $out_dir

set app "core_bench"

set dt_branch "xlnx_rel_v2024.1"
if {[info exists ::env(DT_BRANCH)]} { set dt_branch $::env(DT_BRANCH) }

# ─── 1. createdts: .xsa → pl.dtsi ──────────────────────────────────────
set dts_work [file join $out_dir dts]
file delete -force $dts_work

set cmd [list createdts -hw $xsa_file -platform-name $app \
             -git-branch $dt_branch -overlay -zocl -out $dts_work]
if {[info exists ::env(DT_REPO)]} {
    lappend cmd -local-repo $::env(DT_REPO)
}
puts ""
puts "==> createdts ($dt_branch)"
eval $cmd

set pl_dtsi [file join $dts_work $app psu_cortexa53_0 device_tree_domain bsp pl.dtsi]
if {![file exists $pl_dtsi]} {
    puts stderr "ERROR: createdts did not produce $pl_dtsi"
    exit 1
}

set fp [open $pl_dtsi r]; set dts [read $fp]; close $fp

# ─── 2. Patch firmware-name to the xmutil app name ─────────────────────
regsub {firmware-name = "[^"]+";} $dts \
    "firmware-name = \"$app.bit.bin\";" dts

# Bind CoreTop to the generic-uio driver (createdts emits a placeholder
# compatible like "xlnx,CoreTop-1.0").
if {![regsub {compatible = "xlnx,CoreTop[^"]*";} $dts \
        {compatible = "generic-uio";} dts]} {
    puts stderr "ERROR: CoreTop placeholder node not found in generated pl.dtsi"
    exit 1
}

# ─── 3. Append u-dma-buf nodes (sizes + port count from the preset manifest) ──
# Single source of truth: generated/<preset>/preset.env (CoreConfig → EmitCore).
# build_all.sh exports NUM_DMA + UDMABUF_ACT/WT/OUT_SZ into the environment; a
# standalone run can instead point MMFREE_MANIFEST at the preset.env file. Values
# already in the environment win, so documented overrides (e.g. a bigger
# UDMABUF_WT_SZ for the resident full-model runner) still hold. The hand-tuned
# case tables / static udmabuf.dtsi.in fragment that used to live here (and
# drifted from CoreConfig) are retired.
proc load_manifest {path} {
    if {![file exists $path]} { return 0 }
    set fp [open $path r]
    foreach line [split [read $fp] "\n"] {
        set line [string trim $line]
        if {$line eq "" || [string index $line 0] eq "#"} continue
        set eq [string first "=" $line]
        if {$eq < 0} continue
        set key [string range $line 0 [expr {$eq - 1}]]
        set val [string range $line [expr {$eq + 1}] end]
        if {![info exists ::env($key)]} { set ::env($key) $val }
    }
    close $fp
    return 1
}
if {[info exists ::env(MMFREE_MANIFEST)]} { load_manifest $::env(MMFREE_MANIFEST) }

# NUM_DMA = HP-port count: one act + one wt buffer per port. Sizes default to
# the legacy single-port values only when neither the manifest nor the env set
# them (a truly standalone invocation).
set num_dma 1
if {[info exists ::env(NUM_DMA)]} { set num_dma $::env(NUM_DMA) }
set act_sz 0x00004000
set wt_sz  0x00040000
set out_sz 0x00001000
if {[info exists ::env(UDMABUF_ACT_SZ)]} { set act_sz $::env(UDMABUF_ACT_SZ) }
if {[info exists ::env(UDMABUF_WT_SZ)]}  { set wt_sz  $::env(UDMABUF_WT_SZ)  }
if {[info exists ::env(UDMABUF_OUT_SZ)]} { set out_sz $::env(UDMABUF_OUT_SZ) }
set act_kib [expr {$act_sz / 1024}]
set wt_kib  [expr {$wt_sz  / 1024}]
set out_kib [expr {$out_sz / 1024}]

# A helper so single- and multi-port nodes are emitted identically. NUM_DMA==1
# uses the bare legacy names (udmabuf-act/wt/out) the board driver + bench
# expect; NUM_DMA>1 uses suffixed act{i}/wt{i} + one shared udmabuf-out (output
# stays on DMA0's S2MM). Port i's act buffer for i>=1 carries only zero padding.
proc udmabuf_node {label name sz kib} {
    return "    $label: $name {\n        compatible = \"ikwzm,u-dma-buf\";\n        size       = <[format 0x%08x $sz]>;   /* $kib KiB */\n    };\n\n"
}
set frag "/* u-dma-buf nodes generated by gen_overlay.tcl (NUM_DMA=$num_dma, from preset manifest) */\n"
append frag "&{/} {\n"
if {$num_dma == 1} {
    append frag [udmabuf_node udmabuf_act udmabuf-act $act_sz $act_kib]
    append frag [udmabuf_node udmabuf_wt  udmabuf-wt  $wt_sz  $wt_kib]
} else {
    for {set i 0} {$i < $num_dma} {incr i} {
        append frag [udmabuf_node udmabuf_act$i udmabuf-act$i $act_sz $act_kib]
        append frag [udmabuf_node udmabuf_wt$i  udmabuf-wt$i  $wt_sz  $wt_kib]
    }
}
append frag [udmabuf_node udmabuf_out udmabuf-out $out_sz $out_kib]
append frag "};\n"
append dts "\n" $frag

set out_dts [file join $out_dir $app.dts]
set fp [open $out_dts w]; puts -nonewline $fp $dts; close $fp
puts "==> Wrote $out_dts"

# ─── 4. Compile .dts → .dtbo via dtc ───────────────────────────────────
set out_dtbo [file join $out_dir $app.dtbo]
if {[catch { exec dtc -@ -I dts -O dtb -o $out_dtbo $out_dts 2>@1 } dtc_msg]} {
    puts stderr "ERROR: dtc failed:\n$dtc_msg"
    exit 1
}
puts "==> Compiled $out_dtbo"

# ─── Extract .bit from the .xsa (it's a zip) ───────────────────────────
set extract_dir [file join $out_dir _xsa]
file mkdir $extract_dir
if {[catch { exec unzip -o $xsa_file -d $extract_dir 2>@1 } unz_msg]} {
    puts stderr "WARN: unzip failed: $unz_msg — extract .bit manually."
} else {
    set bits [glob -nocomplain [file join $extract_dir "*.bit"]]
    if {[llength $bits] > 0} {
        file copy -force [lindex $bits 0] [file join $out_dir $app.bit]
        puts "==> Extracted $app.bit"
    } else {
        puts "WARN: no .bit found inside .xsa; you may need bootgen to produce .bit.bin"
    }
}

# ─── shell.json ────────────────────────────────────────────────────────
set fp [open [file join $out_dir shell.json] w]
puts $fp "{"
puts $fp "    \"shell_type\" : \"XRT_FLAT\","
puts $fp "    \"num_slots\"  : \"1\""
puts $fp "}"
close $fp
puts "==> Wrote shell.json"

puts ""
puts "Done. Deploy on the KRIA with transfer/deploy_kria.sh"
