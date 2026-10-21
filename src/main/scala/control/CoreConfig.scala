package control

import chisel3.util.log2Ceil

/**
  * Parameter holder + factory for [[Core]] / [[CoreTop]].
  *
  *   - All Core constructor params live here as case-class fields.
  *   - Derived widths are exposed as `val`s.
  *   - Named presets and `forShape(...)` smart constructor live on the companion.
  *
  * Build a Core/CoreTop:
  * {{{
  *   val cfg = CoreConfig.forShape(n = 4096, m = 32000, batchSize = 1)
  *   val mod = cfg.buildTop
  *   println(cfg.summary)
  * }}}
  */
case class CoreConfig(
    aWidth:       Int = 8,
    maxAcc:       Int = 4096,
    xDim:         Int = 8,
    batchSize:    Int = 1,
    maxN:         Int = 4096,
    maxM:         Int = 1024,
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
                                  // on-chip (systolic.WeightUnpackGearbox); the DDR blob must match.
                                  // false = 2-bit weight beat.
    plClkMhz:     Int = 100,      // PL fabric clock target; manifest-only (deployment policy).
                                  // Must be one of CoreConfig.plClkRungs (see the require below).
    actRamStyle:  String = "",    // activation memory: "" = inferred SyncReadMem; "block"/"ultra" =
                                  // control.ActMem BlackBox carrying ram_style + cascade_height.
                                  // Identical read semantics and latency either way.
    outBankDepth: Int = 0,        // rows per output-memory bank; 0 = auto = 4096*outCascadeHeight
                                  // (one URAM chain per bank). Lets tests force several banks.
    outCascadeHeight: Int = 4,    // cascade_height on each OutMemUltra bank (shipped presets use 1).
                                  // Part of OutMemUltra's module name, so 1.3B and 2.7B must match
                                  // (scripts/clone_preset_hw.sh hashes the SV).
    actCascadeHeight: Int = 0,    // cascade_height for ActMem: 0 = omit (Vivado chooses), 1 = no
                                  // cascade, 2 = pairs. Lower = less logic delay, more LUT + route.
    modelName:    String = "",    // HF checkpoint id (MMFREE_MODEL), e.g. "ridger/MMfreeLM-2.7B";
                                  // "" = bench-only preset.
    shapesHint:   String = "",    // default bench sweep (MMFREE_SHAPES); "" = auto, see defaultShapes.
    residentWtBytes: Long = 0L,   // total weight bytes (all ports) the resident full-model runner
                                  // maps; sizes udmabuf-wt. 0 = bench-only. Derived by forModel.
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
    val yDim:            Int = batchSize
    val nLanes:          Int = aWidth / 2
    val outLanesPerTile: Int = xDim * nLanes
    // Accumulator width = aWidth + log2Ceil(maxAcc) bits of growth headroom.
    val accHeadroom:     Int = log2Ceil(maxAcc)
    val tileAccWidth:    Int = aWidth + accHeadroom
    val numColTilesMax:  Int = (maxM + outLanesPerTile - 1) / outLanesPerTile
    val outAccWidth:     Int = tileAccWidth
    val outLaneWidth:    Int = 1 << log2Ceil(outAccWidth)

    val effOutBeatLanes: Int = if (outBeatLanes == 0) outLanesPerTile else outBeatLanes
    val outSubBeats:     Int = outLanesPerTile / effOutBeatLanes

    val axisDataWidth: Int = math.max(xDim, batchSize) * aWidth
    val outBeatWidth:  Int = effOutBeatLanes * outLaneWidth

    // 1.6-bit weight packing (see packedWeights): the outLanesPerTile trits of one
    // weight beat cost this many DDR bytes at 5 trits/byte, vs outLanesPerTile/4 at
    // 2 bits/trit. Ratio is the fraction of weight bytes the packed stream moves.
    val weightPackedBeatBytes:   Int    = (outLanesPerTile + 4) / 5
    val weightUnpackedBeatBytes: Int    = (outLanesPerTile * 2 + 7) / 8
    val weightPackRatio:         Double = weightPackedBeatBytes.toDouble / weightUnpackedBeatBytes

    // Input-stream port split: one PS HP port carries at most 128 bits, so wider
    // streams are split across N parallel s_axis ports (joined inside CoreTop).
    val numInPorts:  Int = if (axisDataWidth <= 128) 1 else math.min(4, (axisDataWidth + 127) / 128)
    val inPortWidth: Int = axisDataWidth / numInPorts
    val portBytes:   Int = inPortWidth / 8           // s_axis bytes per HP port per beat
    val sAxisBytes:  Int = axisDataWidth / 8         // whole assembled wire beat (= portBytes*numInPorts)
    val outLaneBytes:Int = outLaneWidth / 8          // bytes per m_axis output lane

    /** Per-port weight bytes for one (rows x cols) COMPUTE — the sizing/emit mirror
      * of mmfree_wt_port_bytes (C) / Geometry.port_blob_bytes (Python). Packed: trits
      * packed 5/byte, padded to a whole wire beat so the DMA moves exactly the beats
      * the gearbox consumes (~0.8x the 2-bit size). */
    def wtPortBytes(rows: Long, cols: Int): Long = {
        val tiles = ((cols + outLanesPerTile - 1) / outLanesPerTile).toLong
        if (!packedWeights) rows * tiles * portBytes
        else {
            val trits  = rows * tiles * outLanesPerTile
            val raw    = (trits + 4L) / 5L                                  // ceil(trits/5)
            val padded = ((raw + sAxisBytes - 1L) / sAxisBytes) * sAxisBytes // whole wire beat
            padded / numInPorts
        }
    }

    // u-dma-buf node sizes (bytes, page-rounded) for the worst-case transfer; act/wt per port:
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

    require(numInPorts == 1 || axisDataWidth % 128 == 0,
        s"axisDataWidth=$axisDataWidth must be a multiple of 128 when split across $numInPorts ports")

    require(maxM % outLanesPerTile == 0,
        s"maxM=$maxM must be a multiple of outLanesPerTile=$outLanesPerTile (= xDim*nLanes)")
    require(effOutBeatLanes >= 1 && outLanesPerTile % effOutBeatLanes == 0,
        s"outBeatLanes=$outBeatLanes must divide outLanesPerTile=$outLanesPerTile")

    def maxMatmulShape: (Int, Int) = (maxN, maxM)

    // Named, not positional: Core and CoreTop parameter orders differ.
    def buildCore: Core = new Core(
        aWidth = aWidth, maxAcc = maxAcc, xDim = xDim, batchSize = batchSize,
        maxN = maxN, maxM = maxM, outBeatLanes = outBeatLanes,
        axiAddrWidth = axiAddrWidth, axiDataWidth = axiDataWidth, axiIdWidth = axiIdWidth,
        signedAct = signedAct,
        dspAccum = dspAccum, colBankSize = colBankSize, packedWeights = packedWeights,
        actRamStyle = actRamStyle, actCascadeHeight = actCascadeHeight,
        outCascadeHeight = outCascadeHeight, outBankDepth = outBankDepth
    )

    def buildTop: CoreTop = new CoreTop(
        aWidth = aWidth, maxAcc = maxAcc, xDim = xDim, batchSize = batchSize,
        maxN = maxN, maxM = maxM, outBeatLanes = outBeatLanes,
        axiAddrWidth = axiAddrWidth, axiDataWidth = axiDataWidth, axiIdWidth = axiIdWidth,
        signedAct = signedAct,
        dspAccum = dspAccum, colBankSize = colBankSize, packedWeights = packedWeights,
        actRamStyle = actRamStyle, actCascadeHeight = actCascadeHeight,
        outCascadeHeight = outCascadeHeight, outBankDepth = outBankDepth
    )

    /** Estimated bandwidth consumed by one cycle of COMPUTE_MM weight streaming. */
    def weightBitsPerCycle: Int = xDim * aWidth

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
           |  output BRAM    : ${batchSize * numColTilesMax * outSubBeats} rows x ${effOutBeatLanes * outAccWidth}b = ${outBramBits / 8} bytes
           |""".stripMargin
    }

    /** Default bench sweep for this preset. An explicit `shapesHint` (set by the
      * model presets) wins; otherwise the 370M projection set once the geometry
      * spans the LM head (maxM=32000), else the pow2 cross sweep. */
    def defaultShapes: String =
        if (shapesHint.nonEmpty) shapesHint
        else if (maxM >= 32000) "370m"
        else "pow2"

    /**
      * Flat KEY=VALUE preset manifest (bash-sourceable, parsed in C): the single source of
      * truth for geometry/deployment fields across the build and run scripts.
      *
      * @param name the CoreConfig preset name (CoreConfig does not store its own).
      */
    def presetEnv(name: String): String = {
        val actHex = "0x%08x".format(udmabufActBytes)
        val wtHex  = "0x%08x".format(udmabufWtBytes)
        val outHex = "0x%08x".format(udmabufOutBytes)
        val implEnvBlock =
            if (implDirectives.isEmpty) ""
            else "# --- impl directives (Vivado build_project.tcl; a hand-passed env overrides) ---\n" +
                 implDirectives.toSeq.sortBy(_._1).map { case (k, v) => s"$k=$v" }.mkString("", "\n", "\n")
        s"""# Generated by control.EmitCore — DO NOT EDIT.
           |# Single source of truth: CoreConfig preset '$name'. Every build/run
           |# consumer sources this instead of re-deriving geometry.
           |MMFREE_PRESET=$name
           |# --- core geometry (== bitstream CoreConfig) ---
           |MMFREE_AWIDTH=$aWidth
           |MMFREE_XDIM=$xDim
           |MMFREE_BATCH=$batchSize
           |MMFREE_MAXACC=$maxAcc
           |MMFREE_MAXN=$maxN
           |MMFREE_MAXM=$maxM
           |MMFREE_SIGNED=${if (signedAct) 1 else 0}
           |MMFREE_SHAPES=$defaultShapes
           |# HF checkpoint this preset targets ("" = bench-only preset, no model).
           |# `make model` provisions+packs it into transfer/models/$$MMFREE_MODEL/.
           |MMFREE_MODEL=$modelName
           |# --- derived geometry ---
           |MMFREE_NLANES=$nLanes
           |MMFREE_OUT_LANES_PER_TILE=$outLanesPerTile
           |MMFREE_ACC_WIDTH=$outAccWidth
           |MMFREE_ACC_HEADROOM=$accHeadroom
           |MMFREE_DSP_ACCUM=${if (dspAccum) 1 else 0}
           |MMFREE_COL_BANK_SIZE=$colBankSize
           |# activation-BRAM mapping: "" = inferred SyncReadMem, else the control.ActMem
           |# BlackBox with this ram_style + cascade_height. Implementation only — the read
           |# semantics and latency are identical, so it never changes results.
           |MMFREE_ACT_RAM_STYLE=$actRamStyle
           |MMFREE_ACT_CASCADE_HEIGHT=$actCascadeHeight
           |MMFREE_OUT_CASCADE_HEIGHT=$outCascadeHeight
           |MMFREE_OUT_BANK_DEPTH=$outBankDepth
           |# 1 => weights stream 1.6-bit (5 trits/byte); the packed blob format MUST match.
           |MMFREE_PACKED_WEIGHTS=${if (packedWeights) 1 else 0}
           |MMFREE_OUT_LANE_WIDTH=$outLaneWidth
           |MMFREE_OUT_LANE_BYTES=$outLaneBytes
           |MMFREE_OUT_BEAT_LANES=$effOutBeatLanes
           |MMFREE_NUM_COL_TILES_MAX=$numColTilesMax
           |# --- stream / HP-port split (build_all.sh, vivado/bd.tcl) ---
           |MMFREE_AXIS_DATA_WIDTH=$axisDataWidth
           |NUM_DMA=$numInPorts
           |MM2S_WIDTH=$inPortWidth
           |MMFREE_PORT_BYTES=$portBytes
           |S2MM_WIDTH=$outBeatWidth
           |# --- deployment policy ---
           |PL_CLK_MHZ=$plClkMhz
           |MMFREE_CLK_MHZ=$plClkMhz
           |$implEnvBlock# --- u-dma-buf node sizes ---
           |# WT is sized for the resident full-model working set on model presets
           |# (max of the bench single-slice need and totalWtBytes/NUM_DMA), so the
           |# fpga_runner (gate/run) maps its whole per-port weight set; bench presets
           |# keep the single-slice size. Override at build time with UDMABUF_WT_SZ=...
           |UDMABUF_ACT_SZ=$actHex
           |UDMABUF_WT_SZ=$wtHex
           |UDMABUF_OUT_SZ=$outHex
           |""".stripMargin
    }

    /** Parallel JSON manifest for the Python consumer (mmfree_bridge). Same fields
      * as [[presetEnv]]; hand-rendered so CoreConfig pulls in no JSON dependency. */
    def presetJson(name: String): String = {
        f"""{
           |  "preset": "$name",
           |  "aWidth": $aWidth,
           |  "xDim": $xDim,
           |  "batch": $batchSize,
           |  "maxAcc": $maxAcc,
           |  "maxN": $maxN,
           |  "maxM": $maxM,
           |  "signed": $signedAct,
           |  "shapes": "$defaultShapes",
           |  "model": "$modelName",
           |  "nLanes": $nLanes,
           |  "outLanesPerTile": $outLanesPerTile,
           |  "accWidth": $outAccWidth,
           |  "accHeadroom": $accHeadroom,
           |  "outLaneWidth": $outLaneWidth,
           |  "outLaneBytes": $outLaneBytes,
           |  "outBeatLanes": $effOutBeatLanes,
           |  "numColTilesMax": $numColTilesMax,
           |  "packedWeights": $packedWeights,
           |  "axisDataWidth": $axisDataWidth,
           |  "numPorts": $numInPorts,
           |  "mm2sWidth": $inPortWidth,
           |  "portBytes": $portBytes,
           |  "s2mmWidth": $outBeatWidth,
           |  "plClkMhz": $plClkMhz,
           |  "actRamStyle": "$actRamStyle",
           |  "actCascadeHeight": $actCascadeHeight,
           |  "outCascadeHeight": $outCascadeHeight,
           |  "outBankDepth": $outBankDepth,
           |  "udmabufActBytes": $udmabufActBytes,
           |  "udmabufWtBytes": $udmabufWtBytes,
           |  "udmabufOutBytes": $udmabufOutBytes
           |}
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

    private val b4TimingLevers250 = Map(
        "SYNTH_FLATTEN"                 -> "full",
        "PLACE_DIRECTIVE"               -> "ExtraNetDelay_high",
        "PHYS_OPT_DIRECTIVE"            -> "AggressiveExplore",
        "ROUTE_DIRECTIVE"               -> "AggressiveExplore",
        "POST_ROUTE_PHYS_OPT_DIRECTIVE" -> "AggressiveExplore"
    )

    // ─── Dev / simulation fixtures ─────────────────────────────────────────
    // Small, fast-to-elaborate geometries for the tests; deliberately not in `presets`.

    /** Compact default — single batch, 8x8 array. Also pins the case-class defaults. */
    val Default: CoreConfig = CoreConfig()

    /** Minimal config used by the test suite — fast to simulate. */
    val Tiny: CoreConfig = CoreConfig(
        aWidth = 4, maxAcc = 8, xDim = 2, batchSize = 2,
        maxN = 8, maxM = 16
    )

    // ─── Deployable presets ────────────────────────────────────────────────
    // Exactly three, one per released MMfreeLM checkpoint (CoreConfigSpec pins the set).
    //
    // Shared by all three: batchSize=8 (the weight stream is amortized over the batch; the
    // runtime batch count, COMPUTE flags -> cmpBatch, lets one B8 bitstream serve b=1..8),
    // outBeatLanes=4 (128-bit m_axis = the HP port width), packedWeights, 250 MHz with
    // b4TimingLevers250, colBankSize=4, and dspAccum, which moves the lane accumulators into
    // DSP48E2s and is the condition that makes B8 fit in LUT at all.
    //
    // 1.3B and 2.7B are both maxN=8192 and emit byte-identical RTL; they differ only in
    // manifest fields (model, bench shapes, udmabuf-wt size). Build 2.7B and assemble 1.3B
    // with scripts/clone_preset_hw.sh. maxN=8192 additionally needs actRamStyle="block" +
    // actCascadeHeight=2 to bound the 8-deep RAMB36E2 cascade (see ActMem); that path and the
    // broadcast path colBankSize fixes fail by nearly the same margin, so the two levers only
    // help together (CoreConfigSpec pins the pairing).

    /** MMfreeLM-370M — hidden 1024, intermediate 2816, vocab 32000, 24 layers. maxN=4096. */
    val MMFree_370M: CoreConfig =
        forModel(hidden = 1024, intermediate = 2816, vocab = 32000, layers = 24,
                 aWidth = 16, xDim = 32, outBeatLanes = 1, signedAct = true,
                 plClkMhz = 250, shapes = "370m", model = "ridger/MMfreeLM-370M",
                 packedWeights = true)
            .copy(batchSize = 8, outBeatLanes = 4, colBankSize = 4, dspAccum = true,
                  outCascadeHeight = 1,
                  implDirectives = b4TimingLevers250)

    /** MMfreeLM-1.3B — hidden 2048, intermediate 5632, vocab 32000, 24 layers. maxN=8192.
      * Same RTL as MMFree_2_7B; assemble its bundle with scripts/clone_preset_hw.sh.
      */
    val MMFree_1_3B: CoreConfig =
        forModel(hidden = 2048, intermediate = 5632, vocab = 32000, layers = 24,
                 shapes = "1.3b", model = "ridger/MMfreeLM-1.3B", packedWeights = true)
            .copy(batchSize = 8, outBeatLanes = 4, plClkMhz = 250, dspAccum = true,
                  colBankSize = 4, actRamStyle = "block", actCascadeHeight = 2,
                  outCascadeHeight = 1,
                  implDirectives = b4TimingLevers250)

    /** MMfreeLM-2.7B — hidden 2560, intermediate 6912, vocab 32000, 32 layers. maxN=8192.
      * The one to build from for the maxN=8192 bitstream.
      */
    val MMFree_2_7B: CoreConfig =
        forModel(hidden = 2560, intermediate = 6912, vocab = 32000, layers = 32,
                 shapes = "2.7b", model = "ridger/MMfreeLM-2.7B", packedWeights = true)
            .copy(batchSize = 8, outBeatLanes = 4, plClkMhz = 250, dspAccum = true,
                  colBankSize = 4, actRamStyle = "block", actCascadeHeight = 2,
                  outCascadeHeight = 1,
                  implDirectives = b4TimingLevers250)

    /** The deployable set — what the board bundle ships. */
    val deployable: Map[String, CoreConfig] = Map(
        "mmfree_370m" -> MMFree_370M,
        "mmfree_1_3b" -> MMFree_1_3B,
        "mmfree_2_7b" -> MMFree_2_7B
    )

    // ─── Candidates ────────────────────────────────────────────────────────
    // Buildable via `make build PRESET=` but not shipped; kept separate so `deployable`
    // stays exactly the shipped three.
    //
    // 2-bit controls: each is its deployable twin with packedWeights=false and nothing else
    // changed (CoreConfigSpec asserts this), isolating the weight format. udmabuf-wt grows
    // 1.25x without packing; the overlay carries the size, so a cloned or reused packed
    // overlay is too small, and 2.7B exceeds the older cma=1000M boot arg.
    //
    // Build these through forModel, never `MMFree_*.copy(packedWeights = false)`:
    // residentWtBytes is a constructor field forModel derives from the packing, and .copy
    // leaves it at the PACKED value, emitting an overlay that under-allocates udmabuf-wt.

    /** MMFree_370M with 2-bit weights — the matched control for the packed 370M. */
    val MMFree_370M_2bit: CoreConfig =
        forModel(hidden = 1024, intermediate = 2816, vocab = 32000, layers = 24,
                 aWidth = 16, xDim = 32, outBeatLanes = 1, signedAct = true,
                 plClkMhz = 250, shapes = "370m", model = "ridger/MMfreeLM-370M",
                 packedWeights = false)
            .copy(batchSize = 8, outBeatLanes = 4, colBankSize = 4, dspAccum = true,
                  outCascadeHeight = 1,
                  implDirectives = b4TimingLevers250)

    /** MMFree_1_3B with 2-bit weights — same RTL as MMFree_2_7B_2bit; clone, do not build. */
    val MMFree_1_3B_2bit: CoreConfig =
        forModel(hidden = 2048, intermediate = 5632, vocab = 32000, layers = 24,
                 shapes = "1.3b", model = "ridger/MMfreeLM-1.3B", packedWeights = false)
            .copy(batchSize = 8, outBeatLanes = 4, plClkMhz = 250, dspAccum = true,
                  colBankSize = 4, actRamStyle = "block", actCascadeHeight = 2,
                  outCascadeHeight = 1,
                  implDirectives = b4TimingLevers250)

    /** MMFree_2_7B with 2-bit weights — the matched control, and 1.3B's bitstream too. */
    val MMFree_2_7B_2bit: CoreConfig =
        forModel(hidden = 2560, intermediate = 6912, vocab = 32000, layers = 32,
                 shapes = "2.7b", model = "ridger/MMfreeLM-2.7B", packedWeights = false)
            .copy(batchSize = 8, outBeatLanes = 4, plClkMhz = 250, dspAccum = true,
                  colBankSize = 4, actRamStyle = "block", actCascadeHeight = 2,
                  outCascadeHeight = 1,
                  implDirectives = b4TimingLevers250)

    val candidates: Map[String, CoreConfig] = Map(
        "mmfree_370m_2bit" -> MMFree_370M_2bit,
        "mmfree_1_3b_2bit" -> MMFree_1_3B_2bit,
        "mmfree_2_7b_2bit" -> MMFree_2_7B_2bit
    )


    /** Everything `make build PRESET=` / EmitCore can reach. */
    val presets: Map[String, CoreConfig] = deployable ++ candidates

    def byName(name: String): CoreConfig = {
        val key = name.toLowerCase
        presets.getOrElse(key, throw new IllegalArgumentException(
            s"Unknown CoreConfig name '$name'. Valid names: ${presets.keys.toSeq.sorted.mkString(", ")}"
        ))
    }

    // ─── Smart constructor ─────────────────────────────────────────────────

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

        val nLanes          = aWidth / 2
        val outLanesPerTile = xDim * nLanes
        val mUp             = ceilTo(m, outLanesPerTile)
        val nUp             = nextPow2(n)
        val maxAcc          = math.max(maxAccHint, nUp)

        CoreConfig(
            aWidth       = aWidth,
            maxAcc       = maxAcc,
            xDim         = xDim,
            batchSize    = batchSize,
            maxN         = nUp,
            maxM         = mUp,
            outBeatLanes = outBeatLanes
        )
    }

    /**
      * Size a CoreConfig to run every projection of an MMfreeLM-style model with
      * the given architecture, on the fixed a16 / 4-HP-port engine geometry.
      *
      * The per-token projection shapes (N inner × M out) are:
      *   i/f/g/o_proj : hidden × hidden        (4 per layer)
      *   gate_up      : hidden × 2*intermediate (fused gate+up)
      *   down_proj    : intermediate × hidden
      *   lm_head      : hidden × vocab
      *
      * so the engine must span:
      *   maxN  = nextPow2(max(hidden, intermediate))   — largest inner dim
      *   maxM  = ceilTo(max(2*intermediate, vocab), outLanesPerTile)
      *   maxAcc = max(maxAccHint, maxN)
      *
      * Only maxN/maxAcc grow with model size, so one maxN=8192 bitstream covers 370M,
      * 1.3B and 2.7B. `shapes` names the default bench sweep for the preset.
      */
    def forModel(
        hidden:       Int,
        intermediate: Int,
        vocab:        Int,
        layers:       Int,
        aWidth:       Int     = 16,
        xDim:         Int     = 32,
        batchSize:    Int     = 1,
        maxAccHint:   Int     = 4096,
        outBeatLanes: Int     = 1,
        signedAct:    Boolean = true,
        plClkMhz:     Int     = 250,
        shapes:       String  = "",
        model:        String  = "",
        packedWeights: Boolean = false
    ): CoreConfig = {
        require(hidden > 0 && intermediate > 0 && vocab > 0 && layers > 0,
            s"model dims must be positive (hidden=$hidden inter=$intermediate vocab=$vocab layers=$layers)")
        val nLanes          = aWidth / 2
        val outLanesPerTile = xDim * nLanes
        val maxN            = nextPow2(math.max(hidden, intermediate))
        val maxM            = ceilTo(math.max(2 * intermediate, vocab), outLanesPerTile)
        val maxAcc          = math.max(maxAccHint, maxN)
        // Weight elements streamed per token: per layer 4x i/f/g/o_proj (hidden^2), gate_up
        // and down_proj; plus lm_head.
        val perLayerElems   = 4L * hidden * hidden + hidden.toLong * (2 * intermediate) + intermediate.toLong * hidden
        val totalWtElems    = perLayerElems * layers + hidden.toLong * vocab
        // Resident weight bytes across ports. 2-bit: elements/4 (deployed overlays depend on
        // this exact sizing). Packed: the exact per-projection 1.6-bit blob, each padded to a
        // whole wire beat — the same formula the packer/runtime use.
        val totalWtBytes =
            if (!packedWeights) totalWtElems * 2L / 8L
            else {
                val sAxisBytes = xDim * aWidth / 8
                val projs = (0 until layers).flatMap(_ =>
                        Seq.fill(4)((hidden, hidden)) :+
                        (hidden, 2 * intermediate) :+ (intermediate, hidden)) :+
                    (hidden, vocab)
                projs.map { case (n, m) =>
                    val tiles  = ((m + outLanesPerTile - 1) / outLanesPerTile).toLong
                    val trits  = tiles * n * outLanesPerTile
                    val raw    = (trits + 4L) / 5L
                    ((raw + sAxisBytes - 1L) / sAxisBytes) * sAxisBytes
                }.sum
            }
        CoreConfig(
            aWidth          = aWidth,
            maxAcc          = maxAcc,
            xDim            = xDim,
            batchSize       = batchSize,
            maxN            = maxN,
            maxM            = maxM,
            outBeatLanes    = outBeatLanes,
            signedAct       = signedAct,
            plClkMhz        = plClkMhz,
            shapesHint      = shapes,
            modelName       = model,
            residentWtBytes = totalWtBytes,
            packedWeights   = packedWeights
        )
    }

    private def ceilTo(x: Int, m: Int): Int = ((x + m - 1) / m) * m
    private def nextPow2(x: Int): Int = {
        var p = 1
        while (p < x) p *= 2
        p
    }
}
