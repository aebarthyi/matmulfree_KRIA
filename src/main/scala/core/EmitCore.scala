package core

import circt.stage.ChiselStage
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

/**
  * Emit SystemVerilog for the CoreTop module (the Vivado-facing wrapper).
  *
  *   ./mill matmulfree_KRIA.runMain core.EmitCore                         # mmfree_2_7b
  *   ./mill matmulfree_KRIA.runMain core.EmitCore mmfree_370m             # named preset
  *   ./mill matmulfree_KRIA.runMain core.EmitCore mmfree_370m custom/out  # preset + dir
  *
  *   - arg 0 (optional): preset name from [[Presets.all]].
  *                       Defaults to "mmfree_2_7b", as the Makefiles do.
  *   - arg 1 (optional): target directory. Defaults to `generated/<configName>`.
  *
  * The emitted top exposes `aclk`, `aresetn`, `s_axi_*`, `s_axis_*`, `m_axis_*`,
  * and `irq` — Vivado's IP packager auto-recognizes those as AXI4 / AXI-Stream.
  */
object EmitCore extends App {
    val configName = args.lift(0).getOrElse("mmfree_2_7b")
    val targetDir  = args.lift(1).getOrElse(s"generated/$configName")
    val config     = Presets.byName(configName)

    println(s"Emitting CoreTop for preset '$configName' → $targetDir")
    println(config.summary)

    ChiselStage.emitSystemVerilogFile(
        new CoreTop(config),
        args = Array("--target-dir", targetDir),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info",
            "--lowering-options=disallowLocalVariables,disallowPackedArrays"
        )
    )

    // Preset manifest: single source of truth for every build/run consumer (see PresetManifest).
    Files.createDirectories(Paths.get(targetDir))
    def write(file: String, content: String): Unit = {
        val p = Paths.get(targetDir, file)
        Files.write(p, content.getBytes(StandardCharsets.UTF_8))
        println(s"Wrote $p")
    }
    write("preset.env",  PresetManifest.env(configName, config))
    write("preset.json", PresetManifest.json(configName, config))

    println(s"Done. SV + manifest written to $targetDir/")
}
