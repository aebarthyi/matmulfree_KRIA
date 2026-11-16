package core

import chisel3.util.log2Ceil
import packing.TernaryPack

/**
  * Every parameter of [[Core]] / [[CoreTop]], plus the geometry derived from them. The one
  * place widths are computed: the RTL, the preset manifest ([[PresetManifest]]) and the
  * udmabuf sizing all read them from here.
  *
  *   - Constructor fields are the knobs; derived widths are `val`s.
  *   - The buildable presets live in [[Presets]]; `forShape` / `forModel` size a config.
  *
  * {{{
  *   val cfg = CoreConfig.forShape(n = 4096, m = 32000, batchSize = 1)
  *   val top = new CoreTop(cfg)
  *   println(cfg.summary)
  * }}}
  */
case class CoreConfig(
    aWidth:       Int = 8,
    maxAcc:       Int = 4096,
    xDim:         Int = 8,
    batchSize:    Int = 1,        // = yDim: one PE row per batch item
    maxN:         Int = 4096,     // max inner dim (activation length per batch item)
    maxM:         Int = 1024,     // max output cols
    outBeatLanes: Int = 0,        // 0 → auto (= outLanesPerTile, no chunking)
    axiAddrWidth: Int = 32,
    axiDataWidth: Int = 128,
    axiIdWidth:   Int = 6,
    signedAct:    Boolean = false,// sign-extend activations (real BitLinear / HGRN int16)
    dspAccum:     Boolean = false,// map the per-lane PE accumulators onto DSP48E2 (use_dsp) instead
                                  // of LUT/CARRY8; one DSP per lane per PE. Synthesis-only.
    colBankSize:  Int = 0,        // broadcast the activation within banks of this many columns, one
                                  // pipeline register between banks (cuts fanout). Numerically
                                  // identical; +(ceil(xDim/colBankSize)-1) cycles/op. 0 = no banking.
    packedWeights: Boolean = false,// stream weights at 1.6 bits/trit (5 trits/byte, base-3) and unpack
                                  // on-chip (packing.WeightUnpackGearbox); the DDR blob must match.
                                  // false = 2-bit weight beat.
    plClkMhz:     Int = 100,      // PL fabric clock target; manifest-only (deployment policy).
                                  // Must be one of CoreConfig.plClkRungs (see the require below).
    actRamStyle:  String = "",    // activation memory: "" = inferred SyncReadMem; "block"/"ultra" =
                                  // memory.ActMem BlackBox carrying ram_style + cascade_height.
                                  // Identical read semantics and latency either way.
    outBankDepth: Int = 0,        // rows per output-memory bank; 0 = auto = 4096*outCascadeHeight
                                  // (one URAM chain per bank). Lets tests force several banks.
    outCascadeHeight: Int = 4,    // cascade_height on each OutMemUltra bank (shipped presets use 1).
                                  // Part of OutMemUltra's module name, so 1.3B and 2.7B must match
                                  // (scripts/clone_preset_hw.sh hashes the SV).
    actCascadeHeight: Int = 0,    // cascade_height for ActMem: 0 = omit (Vivado chooses), 1 = no
                                  // cascade, 2 = pairs. Lower = less logic delay, more LUT + route.
    model:        Option[ModelArch] = None,
                                  // the checkpoint a model preset targets; None = bench-only. Supplies
                                  // MMFREE_MODEL / MMFREE_SHAPES and sizes the resident weight set
                                  // (residentWtBytes). Never reaches the RTL.
    implDirectives: Map[String, String] = Map.empty
                                  // Vivado impl directives emitted verbatim into preset.env (a
                                  // hand-passed env overrides). Keys are build_project.tcl's env
                                  // names: SYNTH_FLATTEN, PLACE_DIRECTIVE, PHYS_OPT_DIRECTIVE,
                                  // ROUTE_DIRECTIVE, POST_ROUTE_PHYS_OPT_DIRECTIVE, OPT_DIRECTIVE.
) {
    require(aWidth >= 2 && aWidth % 2 == 0, s"aWidth=$aWidth must be even and >= 2")
    require(maxAcc >= 2,                    s"maxAcc=$maxAcc must be >= 2")
    require(xDim >= 1 && batchSize >= 1,    s"xDim=$xDim, batchSize=$batchSize must be >= 1")
    require(maxN >= 1 && maxM >= 1,         s"maxN=$maxN, maxM=$maxM must be >= 1")
    require(plClkMhz >= 1,                  s"plClkMhz=$plClkMhz must be >= 1")
    // The PS IP silently snaps an off-rung request DOWN to the rung below, so the build would
    // be constrained at the rung while labelled otherwise. This field cannot buy margin by
    // over-constraining; use set_clock_uncertainty in build_project.tcl for that.
    require(CoreConfig.plClkRungs.contains(plClkMhz),
        s"plClkMhz=$plClkMhz is not a reachable PL0_REF rung. Valid: " +
        s"${CoreConfig.plClkRungs.toSeq.sorted.mkString(", ")} (labels for the measured rungs " +
        "99.999 / 142.856 / 166.665 / 199.998 / 249.997 / 299.997 MHz). Anything else snaps " +
        "DOWN to the rung below and produces a bitstream wearing the wrong clock label.")
    require(Seq("", "block", "ultra").contains(actRamStyle),
        s"""actRamStyle="$actRamStyle" must be "" (legacy SyncReadMem), "block" or "ultra"""")
    require(actCascadeHeight >= 0,
        s"actCascadeHeight=$actCascadeHeight must be >= 0 (0 = let Vivado choose)")
    require(outBankDepth >= 0, s"outBankDepth=$outBankDepth must be >= 0 (0 = auto)")
    require(outCascadeHeight >= 1,
        s"outCascadeHeight=$outCascadeHeight must be >= 1 (URAM chains cannot be shorter than one)")
    require(actRamStyle.nonEmpty || actCascadeHeight == 0,
        s"actCascadeHeight=$actCascadeHeight has no effect with the legacy actRamStyle=\"\" " +
        "(the attribute needs the ActMem BlackBox) — set actRamStyle to \"block\" or \"ultra\"")
    require(maxN <= maxAcc,
        s"Core requires maxN ($maxN) <= maxAcc ($maxAcc) — no inner-dim tiling yet")

    // ─── Array / tile geometry ────────────────────────────────────────────────
    val yDim:            Int = batchSize      // one PE row per batch item
    val nLanes:          Int = aWidth / 2     // ternary lanes per PE
    val outLanesPerTile: Int = xDim * nLanes  // output columns per array pass
    // Accumulator width = aWidth + log2Ceil(maxAcc) bits of growth headroom.
    val accHeadroom:     Int = log2Ceil(maxAcc)
    val tileAccWidth:    Int = aWidth + accHeadroom
    val numColTilesMax:  Int = (maxM + outLanesPerTile - 1) / outLanesPerTile
    val outAccWidth:     Int = tileAccWidth
    val outLaneWidth:    Int = 1 << log2Ceil(outAccWidth)

    // ─── Output beat chunking ─────────────────────────────────────────────────
    val effOutBeatLanes: Int = if (outBeatLanes == 0) outLanesPerTile else outBeatLanes
    val outSubBeats:     Int = outLanesPerTile / effOutBeatLanes
    // Output memory: one m_axis beat per row, batchSize x numColTilesMax x outSubBeats rows.
    val outBramRows:     Int = batchSize * numColTilesMax * outSubBeats

    // ─── Stream widths ────────────────────────────────────────────────────────
    val axisDataWidth: Int = math.max(xDim, batchSize) * aWidth
    val outBeatWidth:  Int = effOutBeatLanes * outLaneWidth

    // Input-stream port split: one PS HP port carries at most 128 bits, so wider
    // streams are split across N parallel s_axis ports (joined inside CoreTop).
    val numInPorts:  Int = if (axisDataWidth <= 128) 1 else math.min(4, (axisDataWidth + 127) / 128)
    val inPortWidth: Int = axisDataWidth / numInPorts
    val portBytes:   Int = inPortWidth / 8           // s_axis bytes per HP port per beat
    val sAxisBytes:  Int = axisDataWidth / 8         // whole assembled wire beat (= portBytes*numInPorts)
    val outLaneBytes:Int = outLaneWidth / 8          // bytes per m_axis output lane

    require(numInPorts == 1 || axisDataWidth % 128 == 0,
        s"axisDataWidth=$axisDataWidth must be a multiple of 128 when split across $numInPorts ports")
    require(maxM % outLanesPerTile == 0,
        s"maxM=$maxM must be a multiple of outLanesPerTile=$outLanesPerTile (= xDim*nLanes)")
    require(effOutBeatLanes >= 1 && outLanesPerTile % effOutBeatLanes == 0,
        s"outBeatLanes=$outBeatLanes must divide outLanesPerTile=$outLanesPerTile")
    require(!packedWeights || axisDataWidth % 8 == 0,
        s"packedWeights needs a byte-aligned s_axis (axisDataWidth=$axisDataWidth)")

    /** Weight bits one COMPUTE_MM cycle streams (one full weight beat). */
    def weightBitsPerCycle: Int = xDim * aWidth

    /** Per-port weight bytes for one (rows x cols) COMPUTE — the sizing/emit mirror
      * of mmfree_wt_port_bytes (C) / Geometry.port_blob_bytes (Python). Packed: trits
      * packed 5/byte, padded to a whole wire beat so the DMA moves exactly the beats
      * the gearbox consumes (~0.8x the 2-bit size). */
    def wtPortBytes(rows: Long, cols: Int): Long = {
        val tiles = ((cols + outLanesPerTile - 1) / outLanesPerTile).toLong
        if (!packedWeights) rows * tiles * portBytes
        else {
            val trits  = rows * tiles * outLanesPerTile
            val raw    = TernaryPack.packedBytes(trits)
            val padded = ((raw + sAxisBytes - 1L) / sAxisBytes) * sAxisBytes // whole wire beat
            padded / numInPorts
        }
    }

    // ─── Model-derived manifest fields ────────────────────────────────────────
    /** HF checkpoint id (`MMFREE_MODEL`); "" for a bench-only config. */
    def modelName: String = model.fold("")(_.hfId)

    /** Total resident weight bytes (all ports) the full-model runner maps; 0 = bench-only.
      * Derived, so it always tracks `packedWeights`. 2-bit: elements/4 (deployed overlays
      * depend on this exact sizing). Packed: the exact per-projection 1.6-bit blob, each
      * padded to a whole wire beat — the same formula the packer/runtime use. */
    val residentWtBytes: Long = model.fold(0L) { m =>
        if (!packedWeights) m.weightElems * 2L / 8L
        else m.projections.map { case (n, cols) => wtPortBytes(n, cols) * numInPorts }.sum
    }

    /** Default bench sweep. A model preset uses its checkpoint's slug; otherwise the 370M
      * projection set once the geometry spans the LM head (maxM=32000), else the pow2
      * cross sweep. */
    def defaultShapes: String =
        model.map(_.slug).getOrElse(if (maxM >= 32000) "370m" else "pow2")

    // ─── u-dma-buf node sizes ─────────────────────────────────────────────────
    // Bytes, page-rounded, for the worst-case transfer; act/wt are per port:
    //   act = maxN beats x portBytes (LOAD)
    //   wt  = max(one COMPUTE slice, resident full-model set / numInPorts), so one overlay
    //         serves both the bench and the resident runner
    //   out = batch x numColTilesMax x outLanesPerTile x outLaneBytes (STORE)
    private def pageUp(b: Long): Long = ((b + 4095L) / 4096L) * 4096L
    val udmabufActBytes: Long = pageUp(maxN.toLong * portBytes)
    val udmabufWtSliceBytes:    Long = pageUp(wtPortBytes(maxN.toLong, maxM))
    val udmabufWtResidentBytes: Long = if (residentWtBytes > 0) pageUp(residentWtBytes / numInPorts) else 0L
    val udmabufWtBytes:  Long = math.max(udmabufWtSliceBytes, udmabufWtResidentBytes)
    val udmabufOutBytes: Long = pageUp(batchSize.toLong * numColTilesMax * outLanesPerTile * outLaneBytes)

    def summary: String = {
        val outBramBits = batchSize * numColTilesMax * outLanesPerTile * outAccWidth
        val actBramBits = maxN * batchSize * aWidth
        f"""CoreConfig
           |  array          : ${xDim} cols x ${batchSize} batches (yDim=${batchSize}), ${nLanes} lanes/PE, aWidth=${aWidth}b
           |  max matmul     : N (rows) <= ${maxN}, M (cols) <= ${maxM}
           |  col tiles (max): ${numColTilesMax}
           |  s_axis width   : ${axisDataWidth} bits  (weight beat = ${weightBitsPerCycle}b/cycle)
           |  s_axis ports   : ${numInPorts} x ${inPortWidth}b
           |  m_axis width   : ${outBeatWidth} bits (${effOutBeatLanes} lanes x ${outLaneWidth} bits, ${outSubBeats} sub-beat${if (outSubBeats == 1) "" else "s"}/col-tile)
           |  activation BRAM: ${maxN} entries x ${batchSize * aWidth}b = ${actBramBits / 8} bytes
           |  output BRAM    : ${outBramRows} rows x ${effOutBeatLanes * outAccWidth}b = ${outBramBits / 8} bytes
           |""".stripMargin
    }
}

object CoreConfig {
    /**
      * The only PL fabric clocks this part can produce. pl_clk0 comes from PL0_REF, a
      * divided PLL output, so it exists on discrete rungs only and a request between rungs
      * snaps DOWN. Each label is the smallest integer that lands on its own rung
      * (`scripts/probe_pl_clk.tcl` reads the achieved rate back out of the PS IP).
      */
    val plClkRungs: Set[Int] = Set(100, 143, 167, 200, 250, 300)

    // ─── Dev / simulation fixtures ─────────────────────────────────────────
    // Small, fast-to-elaborate geometries for the tests; deliberately not in Presets.

    /** Compact default — single batch, 8x8 array. Also pins the case-class defaults. */
    val Default: CoreConfig = CoreConfig()

    /** Minimal config used by the test suite — fast to simulate. */
    val Tiny: CoreConfig = CoreConfig(
        aWidth = 4, maxAcc = 8, xDim = 2, batchSize = 2,
        maxN = 8, maxM = 16
    )

    // ─── Smart constructors ────────────────────────────────────────────────

    /**
      * Size a CoreConfig to support a single (n × m) matmul with batchSize batches.
      *
      *   - maxN is rounded up to a power of 2 (≥ n) so the activation BRAM has a
      *     clean address width.
      *   - maxM is rounded up to a multiple of `outLanesPerTile = xDim * nLanes`.
      *   - maxAcc is bumped to at least maxN.
      */
    def forShape(
        n:            Int,
        m:            Int,
        aWidth:       Int = 8,
        xDim:         Int = 8,
        batchSize:    Int = 1,
        maxAccHint:   Int = 4096,
        outBeatLanes: Int = 0
    ): CoreConfig = {
        require(n > 0 && m > 0,                 s"n and m must be positive (got n=$n m=$m)")
        require(xDim >= 1 && batchSize >= 1,    s"xDim=$xDim, batchSize=$batchSize must be >= 1")
        require(aWidth >= 2 && aWidth % 2 == 0, s"aWidth=$aWidth must be even and >= 2")

        val outLanesPerTile = xDim * (aWidth / 2)
        val maxN            = nextPow2(n)
        CoreConfig(
            aWidth       = aWidth,
            maxAcc       = math.max(maxAccHint, maxN),
            xDim         = xDim,
            batchSize    = batchSize,
            maxN         = maxN,
            maxM         = ceilTo(m, outLanesPerTile),
            outBeatLanes = outBeatLanes
        )
    }

    /**
      * Size a CoreConfig to run every projection of `arch` (see [[ModelArch]]), on the
      * fixed a16 / 4-HP-port engine geometry by default. The engine must span:
      *
      *   maxN   = nextPow2(arch.maxInnerDim)                — largest inner dim
      *   maxM   = ceilTo(arch.maxOutDim, outLanesPerTile)
      *   maxAcc = max(maxAccHint, maxN)
      *
      * Only maxN/maxAcc grow with model size, so one maxN=8192 bitstream covers 370M,
      * 1.3B and 2.7B. The config carries `arch`, which sizes its resident weight set.
      */
    def forModel(
        arch:          ModelArch,
        aWidth:        Int     = 16,
        xDim:          Int     = 32,
        batchSize:     Int     = 1,
        maxAccHint:    Int     = 4096,
        outBeatLanes:  Int     = 1,
        signedAct:     Boolean = true,
        plClkMhz:      Int     = 250,
        packedWeights: Boolean = false
    ): CoreConfig = {
        val outLanesPerTile = xDim * (aWidth / 2)
        val maxN            = nextPow2(arch.maxInnerDim)
        CoreConfig(
            aWidth        = aWidth,
            maxAcc        = math.max(maxAccHint, maxN),
            xDim          = xDim,
            batchSize     = batchSize,
            maxN          = maxN,
            maxM          = ceilTo(arch.maxOutDim, outLanesPerTile),
            outBeatLanes  = outBeatLanes,
            signedAct     = signedAct,
            plClkMhz      = plClkMhz,
            packedWeights = packedWeights,
            model         = Some(arch)
        )
    }

    private def ceilTo(x: Int, m: Int): Int = ((x + m - 1) / m) * m
    private def nextPow2(x: Int): Int = {
        var p = 1
        while (p < x) p *= 2
        p
    }
}
