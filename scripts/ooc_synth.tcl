# scripts/ooc_synth.tcl — out-of-context synthesis check for CoreTop.
#
#   vivado -mode batch -source scripts/ooc_synth.tcl [-tclargs <config_name> [<part>]]
#
# Reads the emitted SV from generated/<config_name>/ (run core.EmitCore for that preset
# first); the default part is the KRIA K26 SoM. Reports land in build/vivado-ooc/<config_name>/.

set script_dir [file dirname [info script]]
set proj_root  [file normalize "$script_dir/.."]

set config_name "mmfree_2_7b"
set part        "xck26-sfvc784-2LV-c"

if {[llength $argv] >= 1} { set config_name [lindex $argv 0] }
if {[llength $argv] >= 2} { set part        [lindex $argv 1] }

set gen_dir "$proj_root/generated/$config_name"
if {![file isdirectory $gen_dir]} {
    puts "ERROR: $gen_dir not found — emit the preset first:"
    puts "  ./mill matmulfree_KRIA.runMain core.EmitCore $config_name"
    exit 1
}
set build_dir "$proj_root/build/vivado-ooc/$config_name"

file mkdir $build_dir
cd $build_dir

puts "==== OOC synthesis for CoreTop ===="
puts "  config    : $config_name"
puts "  part      : $part"
puts "  gen_dir   : $gen_dir"
puts "  build_dir : $build_dir"

create_project -in_memory -part $part

foreach f [glob $gen_dir/*.sv] {
    read_verilog -sv $f
    puts "  + $f"
}

set_property top CoreTop [current_fileset]

synth_design -mode out_of_context -top CoreTop -part $part

# Constrain aclk so the timing summary is meaningful (default 100 MHz; CLK_MHZ= overrides).
set clk_mhz 100
if {[info exists ::env(CLK_MHZ)]} { set clk_mhz $::env(CLK_MHZ) }
create_clock -period [expr {1000.0 / $clk_mhz}] -name aclk [get_ports aclk]
puts "  aclk constrained to $clk_mhz MHz"

report_utilization      -file "$build_dir/coretop_utilization.rpt"
report_timing_summary   -file "$build_dir/coretop_timing_summary.rpt" -warn_on_violation
report_clocks           -file "$build_dir/coretop_clocks.rpt"

write_checkpoint -force "$build_dir/coretop_post_synth.dcp"

puts ""
puts "==== OOC synthesis SUCCEEDED ===="
puts "Reports written to $build_dir/"
