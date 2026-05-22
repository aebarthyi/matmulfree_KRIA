package control

import circt.stage.ChiselStage

/**
  * Emit SystemVerilog for the CoreTop module (the Vivado-facing wrapper).
  *
  *   ./mill matmulfree_KRIA.runMain control.EmitCore                       # default
  *   ./mill matmulfree_KRIA.runMain control.EmitCore k26_medium            # named preset
  *   ./mill matmulfree_KRIA.runMain control.EmitCore default custom/out    # preset + dir
  *
  *   - arg 0 (optional): CoreConfig preset name from [[CoreConfig.presets]].
  *                       Defaults to "default".
  *   - arg 1 (optional): target directory. Defaults to `generated/<configName>`.
  *
  * The emitted top exposes `aclk`, `aresetn`, `s_axi_*`, `s_axis_*`, `m_axis_*`,
  * and `irq` — Vivado's IP packager auto-recognizes those as AXI4 / AXI-Stream.
  */
object EmitCore extends App {
    val configName = args.lift(0).getOrElse("default")
    val targetDir  = args.lift(1).getOrElse(s"generated/$configName")
    val config     = CoreConfig.byName(configName)

    println(s"Emitting CoreTop for preset '$configName' → $targetDir")
    println(config.summary)

    ChiselStage.emitSystemVerilogFile(
        config.buildTop,
        args = Array("--target-dir", targetDir),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info",
            "--lowering-options=disallowLocalVariables,disallowPackedArrays"
        )
    )

    println(s"Done. SV written to $targetDir/")
}
