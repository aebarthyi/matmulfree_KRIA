package control

import circt.stage.ChiselStage

/**
  * Emit SystemVerilog for the AxiInstructionHandler module.
  *
  * Run with: `./mill matmulfree_KRIA.runMain control.EmitAxiInstructionHandler`
  *
  * Output lands in `generated/AxiInstructionHandler.sv` (plus support files).
  */
object EmitAxiInstructionHandler extends App {
    val targetDir = args.headOption.getOrElse("generated")

    ChiselStage.emitSystemVerilogFile(
        new AxiInstructionHandler(addrWidth = 32, dataWidth = 128, idWidth = 6),
        args = Array("--target-dir", targetDir),
        firtoolOpts = Array(
            "-disable-all-randomization",
            "-strip-debug-info",
            "--lowering-options=disallowLocalVariables,disallowPackedArrays"
        )
    )
}
