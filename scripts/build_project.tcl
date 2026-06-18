# scripts/build_project.tcl — headless Vivado project build for the t_matmul
# bench design (CoreTop + AXI DMA + Zynq US+ PS on KR260).
#
# Run from the repo root via build_all.sh, or directly:
#   vivado -mode batch -source scripts/build_project.tcl
#
# Optional -tclargs (positional):
#   vivado -mode batch -source scripts/build_project.tcl -tclargs <proj_dir> <ip_repo> <jobs>
#     proj_dir   Vivado project directory   (default: build/t_matmul)
#     ip_repo    CoreTop IP repository       (default: generated/k26_bench)
#     jobs       parallel jobs for synth/impl (default: 8)
#
# Produces:
#   <proj_dir>/t_matmul.xpr           — the project
#   build/t_matmul.xsa                — fixed hardware platform (bitstream included)
#
# Assumes Vivado 2025.2 (matches vivado/bd.tcl's version guard and IP pins) with
# the KR260 board files installed.

# ─── Locate repo root (this script lives in <repo>/scripts) ─────────────────
set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file dirname $script_dir]

# ─── Tunables ───────────────────────────────────────────────────────────────
set part        "xck26-sfvc784-2LV-c"
set board_part  "xilinx.com:kr260_som:part0:1.1"
set design_name "t_matmul"

set proj_dir [expr {[llength $argv] > 0 ? [lindex $argv 0] : [file join $repo_root build $design_name]}]
set ip_repo  [expr {[llength $argv] > 1 ? [lindex $argv 1] : [file join $repo_root generated k26_bench]}]
set jobs     [expr {[llength $argv] > 2 ? [lindex $argv 2] : 8}]

set bd_tcl  [file join $repo_root vivado bd.tcl]
set xsa_out [file join $repo_root build "$design_name.xsa"]

foreach p [list $bd_tcl $ip_repo] {
    if {![file exists $p]} {
        puts stderr "ERROR: required input not found: $p"
        exit 1
    }
}
if {![file exists [file join $ip_repo component.xml]]} {
    puts stderr "ERROR: $ip_repo is not a packaged IP (no component.xml)."
    puts stderr "       Run scripts/package_ip.tcl first, or point at the packaged dir."
    exit 1
}

puts "==> repo_root : $repo_root"
puts "==> project   : $proj_dir"
puts "==> ip_repo   : $ip_repo"
puts "==> part/board: $part  /  $board_part"

# ─── Create the project ─────────────────────────────────────────────────────
create_project $design_name $proj_dir -part $part -force
catch { set_property board_part $board_part [current_project] }

# ─── Register the CoreTop IP repository ─────────────────────────────────────
set_property ip_repo_paths [list $ip_repo] [current_project]
update_ip_catalog -rebuild
if {[get_ipdefs -all "user.org:user:CoreTop:1.0"] eq ""} {
    puts stderr "ERROR: user.org:user:CoreTop:1.0 not found after adding repo $ip_repo."
    exit 1
}

# ─── Build the block design by sourcing the exported bd.tcl ─────────────────
# bd.tcl detects the already-open project and builds the BD into it (it only
# creates a project of its own when none is open). It also validate+saves.
puts "==> Sourcing block design: $bd_tcl"
source $bd_tcl

set bd_file [get_files "$design_name.bd"]
if {$bd_file eq ""} {
    puts stderr "ERROR: block design $design_name.bd was not created by bd.tcl."
    exit 1
}

# ─── Generate output products + HDL wrapper, set as top ─────────────────────
generate_target all [get_files $bd_file]
make_wrapper -files [get_files $bd_file] -top -import
set_property top "${design_name}_wrapper" [current_fileset]
update_compile_order -fileset sources_1

# ─── Synthesis → implementation → bitstream ─────────────────────────────────
# Implementation strategy: default Vivado flow for the proven 100 MHz presets;
# build_all.sh exports IMPL_STRATEGY=Performance_ExplorePostRoutePhysOpt for
# 250 MHz builds (the store-path BRAM->lane-mux->S2MM route misses 4 ns by
# ~90 ps under the default strategy — post-route phys_opt recovers that).
if {[info exists ::env(IMPL_STRATEGY)]} {
    set_property strategy $::env(IMPL_STRATEGY) [get_runs impl_1]
    puts "==> impl_1 strategy: $::env(IMPL_STRATEGY)"
}
puts "==> launch_runs impl_1 -to_step write_bitstream -jobs $jobs"
launch_runs impl_1 -to_step write_bitstream -jobs $jobs
wait_on_run impl_1

if {[get_property PROGRESS [get_runs impl_1]] ne "100%"} {
    puts stderr "ERROR: implementation did not complete. See run logs in $proj_dir."
    exit 1
}
set status [get_property STATUS [get_runs impl_1]]
puts "==> impl_1 status: $status"

# Confirm the bitstream actually landed.
open_run impl_1

# Timing gate: a failing-WNS bitstream still gets written by Vivado, which was
# harmless at 100 MHz but real risk at the 250 MHz PL_CLK_MHZ builds — fail
# loudly instead of deploying a flaky bitstream.
set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
puts "==> post-route WNS: $wns ns"
if {$wns < 0} {
    puts stderr "ERROR: timing FAILED (WNS=$wns ns). Lower PL_CLK_MHZ or fix the critical path."
    exit 1
}
set bit_in_run [glob -nocomplain [file join $proj_dir "$design_name.runs" impl_1 "*.bit"]]
if {[llength $bit_in_run] == 0} {
    puts stderr "ERROR: no bitstream produced by impl_1."
    exit 1
}
puts "==> bitstream : [lindex $bit_in_run 0]"

# ─── Export fixed hardware platform (XSA) with the bitstream ────────────────
file mkdir [file dirname $xsa_out]
write_hw_platform -fixed -include_bit -force $xsa_out
puts "==> wrote XSA : $xsa_out"

puts ""
puts "BUILD OK. Next: scripts/gen_overlay.tcl (xsct) + bootgen → transfer/"
