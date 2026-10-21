# scripts/probe_pl_clk.tcl — what does PL_CLK_MHZ actually CONSTRAIN?
#
# PL0_REF only exists on discrete rungs: the zynq_ultra_ps_e IP recomputes ACT_FREQMHZ and every
# setup path is timed against that, not the request. This builds the real block design once and
# reads ACT_FREQMHZ back per candidate -- the whole rung table in minutes, no implementation run.
#
#   vivado -mode batch -nojournal -notrace -source scripts/probe_pl_clk.tcl \
#          -tclargs <ip_repo> [mhz ...]
#
#     ip_repo   a packaged CoreTop IP dir (generated/<preset>); any preset will do
#     mhz ...   candidate requests (default: a spread of values from 100 to 300)
#
# STA side only: the board's achievable fclk0 rates can differ (deploy_kria.sh checks those).

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file dirname $script_dir]

set part       "xck26-sfvc784-2LV-c"
set board_part "xilinx.com:kr260_som:part0:1.1"

set ip_repo [expr {[llength $argv] > 0 ? [lindex $argv 0] \
                                       : [file join $repo_root generated mmfree_2_7b]}]
set cands [lrange $argv 1 end]
if {[llength $cands] == 0} {
    set cands [list 100 150 166 167 175 187 188 200 210 214 215 220 225 230 240 250 275 295 300]
}

if {![file exists [file join $ip_repo component.xml]]} {
    puts stderr "ERROR: $ip_repo is not a packaged IP (no component.xml)."
    puts stderr "       Pick any generated/<preset> that has been built once."
    exit 1
}

set proj_dir [file join $repo_root build pl_clk_probe]
create_project probe $proj_dir -part $part -force
catch { set_property board_part $board_part [current_project] }
set_property ip_repo_paths [list $ip_repo] [current_project]
update_ip_catalog -rebuild

# Source the real bd.tcl so the PS (hence the rung table) is configured exactly as in a build.
if {[info exists ::env(PL_CLK_MHZ)]} { unset ::env(PL_CLK_MHZ) }
puts "==> building block design (once) from vivado/bd.tcl"
source [file join $repo_root vivado bd.tcl]

set ps [get_bd_cells -hierarchical -filter {VLNV =~ "*zynq_ultra_ps_e*"}]
if {$ps eq ""} { puts stderr "ERROR: no zynq_ultra_ps_e in the block design."; exit 1 }

set src [get_property CONFIG.PSU__CRL_APB__PL0_REF_CTRL__SRCSEL $ps]
puts ""
puts "############################################################"
puts "# PL0_REF rung table as Vivado computes it"
puts "#   part $part   SRCSEL $src"
puts "#   'constrained' is what clk_pl_0's period is derived from —"
puts "#   the WNS of a build labelled <requested> is a number against <constrained>."
puts "############################################################"
puts [format "%12s  %12s  %12s  %s" "requested" "constrained" "period(ns)" "note"]

set rows {}
foreach mhz $cands {
    set_property CONFIG.PSU__CRL_APB__PL0_REF_CTRL__FREQMHZ $mhz $ps
    set act [get_property CONFIG.PSU__CRL_APB__PL0_REF_CTRL__ACT_FREQMHZ $ps]
    set note [expr {abs($act - $mhz) > 0.5 ? "SNAPPED" : ""}]
    puts [format "%12s  %12s  %12.4f  %s" $mhz $act [expr {1000.0 / $act}] $note]
    lappend rows [list $mhz $act]
}

# Distinct rungs, so the usable set is stated outright rather than left to be read
# off the table. These are the only requests for which label == constraint.
puts ""
array set seen {}
foreach r $rows { set seen([lindex $r 1]) 1 }
set rungs [lsort -real -decreasing [array names seen]]
puts "==> distinct rungs reachable: $rungs"
puts "==> a PL_CLK_MHZ that is NOT one of these is a mislabel, not a slower clock."

close_project
puts "==> probe done (no synthesis run)"
