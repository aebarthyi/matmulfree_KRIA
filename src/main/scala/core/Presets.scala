package core

/**
  * The buildable preset registry: everything `make build PRESET=` / [[EmitCore]] can reach.
  * `deployable` is what the board bundle ships (CoreConfigSpec pins it to exactly the three
  * released checkpoints); `candidates` are buildable but not shipped.
  */
object Presets {

    /** The Vivado impl flow that closes 250 MHz on the B8 engine (build_project.tcl env names). */
    val TimingLevers250: Map[String, String] = Map(
        "SYNTH_FLATTEN"                 -> "full",
        "PLACE_DIRECTIVE"               -> "ExtraNetDelay_high",
        "PHYS_OPT_DIRECTIVE"            -> "AggressiveExplore",
        "ROUTE_DIRECTIVE"               -> "AggressiveExplore",
        "POST_ROUTE_PHYS_OPT_DIRECTIVE" -> "AggressiveExplore"
    )

    /**
      * The shipped optimization stack on the a16 / 4-HP-port engine sized for `arch`:
      *
      *   - batchSize=8: the weight stream is amortized over the batch; the runtime batch
      *     count (COMPUTE flags -> cmpBatch) lets one B8 bitstream serve b=1..8.
      *   - dspAccum: moves the lane accumulators into DSP48E2s, the condition that makes B8
      *     fit in LUT at all. colBankSize=4 cuts the activation broadcast fanout.
      *   - outBeatLanes=4 (128-bit m_axis = the HP port width), packedWeights, 250 MHz with
      *     [[TimingLevers250]], outCascadeHeight=1.
      *   - maxN=8192 additionally bounds the 8-deep RAMB36E2 cascade of the activation memory
      *     (actRamStyle="block" + actCascadeHeight=2, see memory.ActMem). That path and the
      *     broadcast path colBankSize fixes fail by nearly the same margin, so the two levers
      *     only help together. maxN=4096 is a 4-deep cascade and needs no cap.
      */
    def shippedStack(arch: ModelArch): CoreConfig = {
        val c = CoreConfig.forModel(arch, packedWeights = true)
            .copy(batchSize = 8, outBeatLanes = 4, colBankSize = 4, dspAccum = true,
                  outCascadeHeight = 1, implDirectives = TimingLevers250)
        if (c.maxN > 4096) c.copy(actRamStyle = "block", actCascadeHeight = 2) else c
    }

    // ─── Deployable presets ────────────────────────────────────────────────
    // 1.3B and 2.7B are both maxN=8192 and emit byte-identical RTL; they differ only in
    // manifest fields (model, bench shapes, udmabuf-wt size). Build 2.7B and assemble 1.3B
    // with scripts/clone_preset_hw.sh.

    /** MMfreeLM-370M — maxN=4096. */
    val MMFree_370M: CoreConfig = shippedStack(ModelArch.MMfreeLM_370M)

    /** MMfreeLM-1.3B — maxN=8192. Same RTL as MMFree_2_7B; clone its bitstream, do not build. */
    val MMFree_1_3B: CoreConfig = shippedStack(ModelArch.MMfreeLM_1_3B)

    /** MMfreeLM-2.7B — maxN=8192. The one to build from for the maxN=8192 bitstream. */
    val MMFree_2_7B: CoreConfig = shippedStack(ModelArch.MMfreeLM_2_7B)

    val deployable: Map[String, CoreConfig] = Map(
        "mmfree_370m" -> MMFree_370M,
        "mmfree_1_3b" -> MMFree_1_3B,
        "mmfree_2_7b" -> MMFree_2_7B
    )

    // ─── Candidates ────────────────────────────────────────────────────────
    // 2-bit controls: each is its deployable twin with packedWeights=false and nothing else
    // changed (CoreConfigSpec asserts this), isolating the weight format. A plain .copy is
    // safe because residentWtBytes is derived from packedWeights. udmabuf-wt grows 1.25x
    // without packing; the overlay carries the size, so a cloned or reused packed overlay is
    // too small. 1.3B shares 2.7B's RTL here too: clone it, do not build it.

    val MMFree_370M_2bit: CoreConfig = MMFree_370M.copy(packedWeights = false)
    val MMFree_1_3B_2bit: CoreConfig = MMFree_1_3B.copy(packedWeights = false)
    val MMFree_2_7B_2bit: CoreConfig = MMFree_2_7B.copy(packedWeights = false)

    val candidates: Map[String, CoreConfig] = Map(
        "mmfree_370m_2bit" -> MMFree_370M_2bit,
        "mmfree_1_3b_2bit" -> MMFree_1_3B_2bit,
        "mmfree_2_7b_2bit" -> MMFree_2_7B_2bit
    )

    /** Everything `make build PRESET=` / EmitCore can reach. */
    val all: Map[String, CoreConfig] = deployable ++ candidates

    def byName(name: String): CoreConfig =
        all.getOrElse(name.toLowerCase, throw new IllegalArgumentException(
            s"Unknown preset '$name'. Valid names: ${all.keys.toSeq.sorted.mkString(", ")}"))
}
