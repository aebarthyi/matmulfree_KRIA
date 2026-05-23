# scripts/gen_overlay.tcl — generate a K26_Bench device-tree overlay (+ bitstream
# + shell.json) from a Vivado-exported .xsa, ready for `xmutil loadapp`.
#
# Usage:
#   xsct gen_overlay.tcl <xsa_file> [<out_dir>] [-ip <name>] [-addr <hex>] [-irq <n>]
#
#   xsa_file       Path to the .xsa exported from Vivado (`File → Export → Hardware`).
#   out_dir        Output directory (default: build/overlay).
#   -ip <name>     Override the BD cell name for CoreTop (default: auto-detect).
#   -addr <hex>    Override the CoreTop base address (e.g. 0xa0010000).
#                  Useful if hsi auto-detect fails on your Vitis version.
#   -irq <n>       Override the GIC SPI number (default: 89).
#                  See `cat /proc/interrupts` on a working board, or look at
#                  the Concat → ps_pl_irq wiring in your block design.
#
# What it produces in <out_dir>:
#   core_bench.bit    — bitstream extracted from the .xsa
#   core_bench.dts    — populated overlay source (for inspection)
#   core_bench.dtbo   — compiled overlay (consumed by xmutil)
#   shell.json        — required by the Xilinx xmutil loader
#
# Deployment on the KRIA:
#   sudo mkdir -p /lib/firmware/xilinx/core_bench
#   sudo cp <out_dir>/{core_bench.bit,core_bench.dtbo,shell.json} \
#           /lib/firmware/xilinx/core_bench/
#   sudo xmutil unloadapp           # if anything is currently loaded
#   sudo xmutil loadapp core_bench

if {[llength $argv] < 1} {
    puts stderr "Usage: xsct gen_overlay.tcl <xsa_file> \[<out_dir>\] \[-ip <name>\] \[-addr <hex>\] \[-irq <n>\]"
    exit 1
}

set xsa_file [file normalize [lindex $argv 0]]
if {![file exists $xsa_file]} {
    puts stderr "ERROR: .xsa not found: $xsa_file"
    exit 1
}

# Defaults
set out_dir   "build/overlay"
set ip_name   ""
set core_addr ""
set core_irq  89

# Parse remaining args. First positional (if it doesn't start with '-') is out_dir.
set i 1
if {[llength $argv] > 1} {
    set next [lindex $argv 1]
    if {[string index $next 0] ne "-"} { set out_dir $next; incr i }
}
while {$i < [llength $argv]} {
    set flag [lindex $argv $i]
    set val  [lindex $argv [expr {$i + 1}]]
    switch -- $flag {
        "-ip"   { set ip_name   $val }
        "-addr" { set core_addr $val }
        "-irq"  { set core_irq  $val }
        default { puts stderr "Unknown flag: $flag"; exit 1 }
    }
    incr i 2
}

set out_dir [file normalize $out_dir]
file mkdir $out_dir

set script_dir [file dirname [file normalize [info script]]]
set template_path [file join $script_dir core_bench_overlay.dts.in]
if {![file exists $template_path]} {
    puts stderr "ERROR: template not found: $template_path"
    exit 1
}

puts ""
puts "==> Opening .xsa: $xsa_file"
set hw_design [hsi::open_hw_design $xsa_file]

# ─── Locate CoreTop cell ───────────────────────────────────────────────
if {$ip_name ne ""} {
    set core_cell [hsi::get_cells $ip_name]
    if {$core_cell eq ""} {
        puts stderr "ERROR: cell '$ip_name' not found in design"
        exit 1
    }
} else {
    # Auto-detect by common names, then by VLNV substring.
    set candidates [list "CoreTop_0" "mmfree_core_0" "CoreTop" "mmfree_core"]
    set core_cell ""
    foreach name $candidates {
        set c [hsi::get_cells $name]
        if {$c ne ""} { set core_cell $c; break }
    }
    if {$core_cell eq ""} {
        foreach c [hsi::get_cells] {
            if {[catch { set vlnv [common::get_property VLNV $c] }]} { continue }
            if {[string match "*:CoreTop:*" $vlnv] ||
                [string match "*:mmfree_core:*" $vlnv]} {
                set core_cell $c; break
            }
        }
    }
    if {$core_cell eq ""} {
        puts stderr "ERROR: could not auto-detect CoreTop in .xsa. Pass -ip <name>."
        puts stderr "       Available cells:"
        foreach c [hsi::get_cells] { puts stderr "         $c" }
        exit 1
    }
}
puts "    Core IP cell:  $core_cell"

# ─── Extract base address ──────────────────────────────────────────────
if {$core_addr eq ""} {
    # Walk the A53's memory map; find the segment that points at our cell.
    set found ""
    if {[catch {
        set cpu  [hsi::get_cells -hier "psu_cortexa53_0"]
        set segs [hsi::get_mem_ranges -of_objects $cpu]
        foreach s $segs {
            set inst [common::get_property INSTANCE $s]
            if {$inst eq $core_cell} {
                set found [common::get_property BASE_VALUE $s]
                break
            }
        }
    } err]} {
        puts "    (mem_range lookup failed: $err)"
    }
    if {$found eq ""} {
        # Fallback: try the C_BASEADDR property
        if {[catch { set found [common::get_property CONFIG.C_BASEADDR $core_cell] }]} {
            set found ""
        }
    }
    if {$found eq ""} {
        puts stderr "ERROR: could not extract base address. Re-run with -addr <hex>."
        exit 1
    }
    set core_addr $found
}

# Normalize address representations. Accept "0xa0010000", "a0010000", or decimal.
if {[string match "0x*" $core_addr] || [string match "0X*" $core_addr]} {
    scan [string range $core_addr 2 end] "%x" core_addr_int
} elseif {[regexp {^[0-9]+$} $core_addr]} {
    set core_addr_int $core_addr
} else {
    scan $core_addr "%x" core_addr_int
}
set addr_full [format "0x%x" $core_addr_int]
set addr_hex  [format "%x"   $core_addr_int]
puts "    Base address:  $addr_full"
puts "    IRQ (SPI):     $core_irq"

# ─── Substitute template ───────────────────────────────────────────────
set fp [open $template_path r]; set tmpl [read $fp]; close $fp
set out_dts_text [string map [list \
    "@CORE_ADDR@"     $addr_full \
    "@CORE_ADDR_HEX@" $addr_hex  \
    "@CORE_IRQ@"      $core_irq  \
] $tmpl]

set out_dts [file join $out_dir core_bench.dts]
set fp [open $out_dts w]; puts -nonewline $fp $out_dts_text; close $fp
puts "==> Wrote $out_dts"

# ─── Compile .dts → .dtbo via dtc ──────────────────────────────────────
set out_dtbo [file join $out_dir core_bench.dtbo]
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
        file copy -force [lindex $bits 0] [file join $out_dir core_bench.bit]
        puts "==> Extracted core_bench.bit"
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
puts "Done. Deploy on the KRIA with:"
puts "    sudo mkdir -p /lib/firmware/xilinx/core_bench"
puts "    sudo cp $out_dir/core_bench.bit \\"
puts "            $out_dir/core_bench.dtbo \\"
puts "            $out_dir/shell.json \\"
puts "            /lib/firmware/xilinx/core_bench/"
puts "    sudo xmutil unloadapp || true"
puts "    sudo xmutil loadapp core_bench"
puts ""

catch { hsi::close_hw_design $hw_design }
