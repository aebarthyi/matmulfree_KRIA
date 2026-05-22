# scripts/ooc_synth.tcl
#
# Out-of-context synthesis check for CoreTop.
#
# Usage:
#   vivado -mode batch -source scripts/ooc_synth.tcl
#   vivado -mode batch -source scripts/ooc_synth.tcl -tclargs <config_name>
#   vivado -mode batch -source scripts/ooc_synth.tcl -tclargs <config_name> <part>
#
# Defaults assume a KRIA K26 SoM (used by KV260 / KR260).
# Looks for emitted SV in generated/<config_name>/; falls back to generated/
# if that subdir doesn't exist (matches the pre-CoreConfig EmitCore layout).
# Reports land in build/vivado-ooc/<config_name>/.

set script_dir [file dirname [info script]]
set proj_root  [file normalize "$script_dir/.."]

# Defaults
set config_name "default"
set part        "xck26-sfvc784-2LV-c"

# Override from -tclargs
if {[llength $argv] >= 1} { set config_name [lindex $argv 0] }
if {[llength $argv] >= 2} { set part        [lindex $argv 1] }

# Resolve generated/ subdir: prefer generated/<name>/, fall back to generated/.
set gen_dir "$proj_root/generated/$config_name"
if {![file isdirectory $gen_dir]} {
    set gen_dir "$proj_root/generated"
    puts "Note: $proj_root/generated/$config_name not found, using $gen_dir"
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

# Add all generated SV files.
foreach f [glob $gen_dir/*.sv] {
    read_verilog -sv $f
    puts "  + $f"
}

set_property top CoreTop [current_fileset]

# Synthesize out of context.
synth_design -mode out_of_context -top CoreTop -part $part

# Quick reports.
report_utilization      -file "$build_dir/coretop_utilization.rpt"
report_timing_summary   -file "$build_dir/coretop_timing_summary.rpt" -warn_on_violation
report_clocks           -file "$build_dir/coretop_clocks.rpt"

write_checkpoint -force "$build_dir/coretop_post_synth.dcp"

puts ""
puts "==== OOC synthesis SUCCEEDED ===="
puts "Reports written to $build_dir/"
