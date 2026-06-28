package control

import circt.stage.ChiselStage
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

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

    // Preset manifest: single source of truth for every build/run consumer.
    // preset.env (KEY=VAL, source-able in bash + parsed in C) + preset.json (Python).
    Files.createDirectories(Paths.get(targetDir))
    def write(file: String, content: String): Unit = {
        val p = Paths.get(targetDir, file)
        Files.write(p, content.getBytes(StandardCharsets.UTF_8))
        println(s"Wrote $p")
    }
    write("preset.env",  config.presetEnv(configName))
    write("preset.json", config.presetJson(configName))

    println(s"Done. SV + manifest written to $targetDir/")
}
