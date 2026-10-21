# scripts/package_ip.tcl — (re)package the emitted CoreTop SystemVerilog as a Vivado IP
# (VLNV user.org:user:CoreTop:1.0, which vivado/bd.tcl expects).
#
# build_all.sh runs this when generated/<preset> has no component.xml, or with REPACKAGE=1.
# Re-package whenever the Chisel interface or the SV file set changes (re-emit the SV first).
#
#   vivado -mode batch -source scripts/package_ip.tcl -tclargs <sv_dir> <top>
#     sv_dir   directory of emitted *.sv      (default: generated/mmfree_2_7b)
#     top      top module name                (default: CoreTop)
#
# Packages in place into <sv_dir> (overwrites component.xml / xgui there).

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file dirname $script_dir]

set part   "xck26-sfvc784-2LV-c"
set sv_dir [expr {[llength $argv] > 0 ? [lindex $argv 0] : [file join $repo_root generated mmfree_2_7b]}]
set top    [expr {[llength $argv] > 1 ? [lindex $argv 1] : "CoreTop"}]

if {![file isdirectory $sv_dir]} {
    puts stderr "ERROR: SV dir not found: $sv_dir"
    exit 1
}
set sv_files [glob -nocomplain [file join $sv_dir *.sv]]
if {[llength $sv_files] == 0} {
    puts stderr "ERROR: no .sv files in $sv_dir (run mill EmitCore first)."
    exit 1
}

puts "==> packaging $top from [llength $sv_files] SV files in $sv_dir"

create_project -in_memory -part $part
add_files -norecurse $sv_files
set_property top $top [current_fileset]
update_compile_order -fileset sources_1

# Auto-infers s_axi (AXI4 slave), s_axis / m_axis (AXI4-Stream), aclk, aresetn
# from their standard port names.
ipx::package_project -root_dir $sv_dir -vendor user.org -library user \
    -taxonomy /UserIP -import_files -set_current true -force

set core [ipx::current_core]
set_property vendor       user.org $core
set_property library      user     $core
set_property name         $top     $core
set_property version      1.0      $core
set_property display_name "$top"   $core
set_property vendor_display_name "user.org" $core
set_property supported_families {zynquplus Production} $core

# Tie aclk to all three AXI interfaces (so IPI connection automation works).
catch {
    set bifs [ipx::get_bus_interfaces -of_objects $core]
    foreach name {s_axi s_axis m_axis} {
        if {[ipx::get_bus_interfaces $name -of_objects $core] ne ""} {
            ipx::associate_bus_interfaces -busif $name -clock aclk $core
        }
    }
}

ipx::create_xgui_files $core
ipx::update_checksums  $core
ipx::check_integrity   $core
ipx::save_core         $core
puts "==> packaged IP written to $sv_dir/component.xml"
