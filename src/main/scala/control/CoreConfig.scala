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
    accHeadroomBits: Int = -1,    // accumulator overflow headroom (bits above aWidth).
                                  // -1 → auto = log2Ceil(maxAcc) (legacy worst-case growth,
                                  // bit-identical to before). A smaller value trims every
                                  // accumulator flop (PE accumReg), the drain shift register
                                  // (tileAccums) and outBram width — the cheap lever for the
                                  // wide-accumulator synthesis cost. As long as the resulting
                                  // outAccWidth stays in (16,32] the m_axis lane stays 32-bit,
                                  // so the external DMA format is UNCHANGED. Overflow-correctness
                                  // is the model's responsibility: only lower it once the real
                                  // activation dynamic range is known (host does mod-2^accWidth).
    actMaxFanout: Int = 0,        // max_fanout attribute on the batched array-feed register.
                                  // 0 → off (exact legacy Queue, byte-identical SV). >0 forces
                                  // synth to replicate the activation-broadcast register per
                                  // column — the route-bound 250 MHz path at B>=6. Tune per
                                  // preset; a full impl build is the only real timing test.
    dspAccum:     Boolean = false,// map the per-lane PE accumulators onto DSP48E2 slices
                                  // (use_dsp="yes") instead of LUT/CARRY8 fabric. Costs one
                                  // DSP per lane per PE (xDim*batchSize*nLanes total); worth
                                  // it only when DSPs are the slack resource. Synthesis-only
                                  // attribute → simulation/tests are byte-identical.
    colBankSize:  Int = 0,        // systolic column banking: broadcast the activation within a
                                  // bank of this many columns, pipeline one register between
                                  // banks to cut the activation-broadcast fanout (xDim→colBankSize)
                                  // that route-bounds the 250 MHz path. Numerically identical to
                                  // the broadcast array (only retimed); adds +(ceil(xDim/colBankSize)
                                  // -1) cycles latency/op. 0 → no banking (broadcast, unchanged).
    packedWeights: Boolean = false,// stream ternary weights DDR-side at 1.6 bits/trit (5 trits
                                  // per byte, base-3) instead of 2, and unpack on-chip through a
                                  // 256-entry ROM (systolic.WeightUnpackGearbox). ~20% fewer weight
                                  // bytes on the HP-port stream that bounds decode. false → the
                                  // legacy 2-bit weight beat (byte-identical SV). See TernaryPack.
                                  // NOTE: config plumbing only so far — Core does not yet consume
                                  // it (the gearbox is unit-tested standalone); wiring is step 2.
    plClkMhz:     Int = 100,      // PL fabric clock the bitstream targets (deployment
                                  // policy, not Chisel hardware — emitted into the
                                  // preset manifest, consumed by build_all.sh / bench).
                                  // MUST be one of CoreConfig.plClkRungs: PL0_REF only
                                  // exists on discrete rungs and the PS IP snaps anything
                                  // else DOWN, so an off-rung value is a MISLABEL, not a
                                  // slower clock. See the require below.
    actRamStyle:  String = "",    // activation-BRAM implementation. "" → the legacy
                                  // SyncReadMem (Vivado picks the mapping; at maxN=8192 it
                                  // picks 1024x36 blocks and chains EIGHT of them through
                                  // the hard CASDOUTB cascade = 3.2 ns of pure logic, the
                                  // worst 250 MHz path on the 2.7B B6 build). "block" or
                                  // "ultra" swap in control.ActMem, a BlackBox carrying
                                  // `ram_style` + `cascade_height` so the chain can be
                                  // bounded. Read semantics and latency are IDENTICAL to
                                  // SyncReadMem (1-cycle sync read), so this is numerically
                                  // transparent — an implementation attribute, not a design
                                  // change. Only worth setting where the cascade binds
                                  // (maxN=8192); maxN=4096 is a 4-deep chain and closes 250.
    actCascadeHeight: Int = 0,    // cascade_height for actRamStyle != "". 0 → omit the
                                  // attribute (let Vivado choose). 1 → no cascade at all
                                  // (every depth block reads out in parallel into a fabric
                                  // mux); 2 → pairs, one hop plus a 4:1 mux at maxN=8192.
                                  // Lower = less logic delay, more LUT + route.
    modelName:    String = "",    // HF checkpoint id this preset targets, e.g.
                                  // "ridger/MMfreeLM-2.7B". Emitted as MMFREE_MODEL —
                                  // the single source of truth for `make model` (which
                                  // provisions/packs into transfer/models/<modelName>/) and
                                  // for the board's gate/run/gen model lookup. "" → a
                                  // bench-only preset that targets no checkpoint. forModel
                                  // sets it; .copy'd batch siblings inherit it.
    shapesHint:   String = "",    // default bench sweep for this preset (MMFREE_SHAPES);
                                  // "" → auto (pow2, or "370m" once maxM spans the LM head).
                                  // Model presets set this so `make bench` defaults to the
                                  // right projection sweep without an explicit env.
    residentWtBytes: Long = 0L,   // total packed ternary weight bytes the resident
                                  // full-model runner (fpga_runner gate/run) holds across
                                  // ALL ports. 0 → bench-only preset (no resident set), so
                                  // UDMABUF_WT_SZ stays the single-slice default. forModel
                                  // sets this from the model shapes so the emitted udmabuf
                                  // fits the runner's working set, not just one weight slice.
    implDirectives: Map[String, String] = Map.empty
                                  // Vivado impl directives for THIS preset, emitted verbatim
                                  // into preset.env so build_all.sh's load_manifest exports
                                  // them and build_project.tcl applies them (a hand-passed
                                  // env still overrides — load_manifest only sets unset keys).
                                  // Razor-thin timing closures (e.g. the maxN=8192 B4 @ 250 MHz)
                                  // carry the levers that close them, so `make build` needs no
                                  // remembered env. Keys are the env names build_project.tcl
                                  // reads: SYNTH_FLATTEN, PLACE_DIRECTIVE, PHYS_OPT_DIRECTIVE,
                                  // ROUTE_DIRECTIVE, POST_ROUTE_PHYS_OPT_DIRECTIVE, OPT_DIRECTIVE.
) {
    require(aWidth >= 2 && aWidth % 2 == 0, s"aWidth=$aWidth must be even and >= 2")
    require(maxAcc >= 2,                    s"maxAcc=$maxAcc must be >= 2")
    require(xDim >= 1 && batchSize >= 1,    s"xDim=$xDim, batchSize=$batchSize must be >= 1")
    require(maxN >= 1 && maxM >= 1,         s"maxN=$maxN, maxM=$maxM must be >= 1")
    require(plClkMhz >= 1,                  s"plClkMhz=$plClkMhz must be >= 1")
    // PL0_REF is a divided PLL output: only the rungs in plClkRungs exist, and the PS IP
    // silently snaps an unreachable request DOWN to the rung below. An off-rung plClkMhz
    // therefore does NOT constrain the design at that rate — it constrains it at the rung
    // and mislabels the build. MEASURED 2026-08-04 (scripts/probe_pl_clk.tcl reads
    // PSU__CRL_APB__PL0_REF_CTRL__ACT_FREQMHZ back out of the IP): 215/220/225/230/240 all
    // land on 199.998 MHz, and 275/285/290/295 all land on 249.997. That is why every
    // "_230" preset here was really a 200 MHz build (its WNS was a number against 5.000 ns,
    // not 4.348), and why the 225 and 230 bitstreams came out byte-identical. It also means
    // you CANNOT over-constrain through this field to buy margin — the 275..295 "ceiling
    // sweep" was four runs of the same 250 MHz constraint. For real extra margin use
    // set_clock_uncertainty in build_project.tcl, not a fictional clock.
    require(CoreConfig.plClkRungs.contains(plClkMhz),
        s"plClkMhz=$plClkMhz is not a reachable PL0_REF rung. Valid: " +
        s"${CoreConfig.plClkRungs.toSeq.sorted.mkString(", ")} (labels for the measured rungs " +
        "99.999 / 142.856 / 166.665 / 199.998 / 249.997 / 299.997 MHz). Anything else snaps " +
        "DOWN to the rung below and produces a bitstream wearing the wrong clock label.")
    require(Seq("", "block", "ultra").contains(actRamStyle),
        s"""actRamStyle="$actRamStyle" must be "" (legacy SyncReadMem), "block" or "ultra"""")
    require(actCascadeHeight >= 0,
        s"actCascadeHeight=$actCascadeHeight must be >= 0 (0 = let Vivado choose)")
    require(actRamStyle.nonEmpty || actCascadeHeight == 0,
        s"actCascadeHeight=$actCascadeHeight has no effect with the legacy actRamStyle=\"\" " +
        "(the attribute needs the ActMem BlackBox) — set actRamStyle to \"block\" or \"ultra\"")
    require(maxN <= maxAcc,
        s"Core requires maxN ($maxN) <= maxAcc ($maxAcc) — no inner-dim tiling yet")
    require(accHeadroomBits == -1 || accHeadroomBits >= 0,
        s"accHeadroomBits=$accHeadroomBits must be -1 (auto) or >= 0")

    val yDim:            Int = batchSize
    val nLanes:          Int = aWidth / 2
    val outLanesPerTile: Int = xDim * nLanes
    // Accumulator data width = aWidth + headroom. Auto (-1) = legacy log2Ceil(maxAcc).
    val accHeadroom:     Int = if (accHeadroomBits < 0) log2Ceil(maxAcc) else accHeadroomBits
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

    // u-dma-buf node sizes (bytes), derived from geometry to match the worst-case
    // transfer the runtime issues, page-rounded.
    //   act = maxN beats x portBytes/port            (LOAD streams maxN activations)
    //   wt  = max(single COMPUTE slice, resident full-model set/port) — see below
    //   out = batch x numColTilesMax x outLanesPerTile x outLaneBytes (STORE drain)
    private def pageUp(b: Long): Long = ((b + 4095L) / 4096L) * 4096L
    val udmabufActBytes: Long = pageUp(maxN.toLong * portBytes)
    // wt: the bench streams one COMPUTE weight slice at a time (maxN x numColTilesMax),
    // but the resident full-model runner keeps the whole per-port working set mapped at
    // once. Size to whichever is larger so ONE overlay serves both — a model preset
    // (residentWtBytes>0) gets the resident size; a bench preset keeps the slice size.
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

    def buildCore: Core = new Core(
        aWidth, maxAcc, xDim, batchSize, maxN, maxM, outBeatLanes,
        axiAddrWidth, axiDataWidth, axiIdWidth, signedAct, accHeadroomBits, actMaxFanout, dspAccum, colBankSize,
        packedWeights, actRamStyle, actCascadeHeight
    )

    def buildTop: CoreTop = new CoreTop(
        aWidth, maxAcc, xDim, batchSize, maxN, maxM, outBeatLanes,
        axiAddrWidth, axiDataWidth, axiIdWidth, signedAct, accHeadroomBits, actMaxFanout, dspAccum, colBankSize,
        packedWeights, actRamStyle, actCascadeHeight
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
      * Flat KEY=VALUE preset manifest. `source`-able in bash (build_all.sh,
      * gen_overlay.tcl, the root Makefile) and trivially parsed in C. This is the
      * single source of truth for every geometry/deployment field that used to be
      * hand-duplicated across the build/run scripts.
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
           |MMFREE_ACT_MAX_FANOUT=$actMaxFanout
           |MMFREE_DSP_ACCUM=${if (dspAccum) 1 else 0}
           |MMFREE_COL_BANK_SIZE=$colBankSize
           |# activation-BRAM mapping: "" = inferred SyncReadMem, else the control.ActMem
           |# BlackBox with this ram_style + cascade_height. Implementation only — the read
           |# semantics and latency are identical, so it never changes results.
           |MMFREE_ACT_RAM_STYLE=$actRamStyle
           |MMFREE_ACT_CASCADE_HEIGHT=$actCascadeHeight
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
           |  "udmabufActBytes": $udmabufActBytes,
           |  "udmabufWtBytes": $udmabufWtBytes,
           |  "udmabufOutBytes": $udmabufOutBytes
           |}
           |""".stripMargin
    }
}

object CoreConfig {
    /**
      * The only PL fabric clocks this part can actually produce.
      *
      * pl_clk0 comes from PL0_REF, a divided PLL output, so it exists on discrete rungs
      * only. MEASURED 2026-08-04 by reading PSU__CRL_APB__PL0_REF_CTRL__ACT_FREQMHZ back
      * out of the configured PS IP (`scripts/probe_pl_clk.tcl`, part xck26-sfvc784-2LV-c,
      * SRCSEL=IOPLL) — the achieved rates are
      *
      *   299.997009  249.997498  199.998001  166.664993  142.855713  99.999001 MHz
      *   (3.3334      4.0000      5.0000      6.0001      7.0001      10.0001  ns)
      *
      * A request between two rungs snaps DOWN, so each label below is the smallest integer
      * that lands on its own rung. The board's fclk0 was measured independently and offers
      * the same set, so one table serves both the STA and the runtime side.
      */
    val plClkRungs: Set[Int] = Set(100, 143, 167, 200, 250, 300)

    // ─── Named presets ─────────────────────────────────────────────────────

    /** Compact default. Single batch, 8x8 array. */
    val Default: CoreConfig = CoreConfig()

    /** Minimal config used by the test suite — fast to simulate. */
    val Tiny: CoreConfig = CoreConfig(
        aWidth = 4, maxAcc = 8, xDim = 2, batchSize = 2,
        maxN = 8, maxM = 16
    )

    // KRIA K26 placeholders. Refine after running OOC at each scale.
    val K26_Small:  CoreConfig = forShape(n = 256,  m = 256,  xDim = 4,  batchSize = 1)
    val K26_Medium: CoreConfig = forShape(n = 512,  m = 512,  xDim = 8,  batchSize = 1)
    val K26_Large:  CoreConfig = forShape(n = 1024, m = 1024, xDim = 16, batchSize = 1)

    /**
      * First-board bring-up bench. xDim=4 / batchSize=1 keeps resources well within
      * a KV260, while maxN=maxM=1024 spans the 64..1024 power-of-two sweep.
      *
      * outBeatLanes=1 makes m_axis 32 bits wide (one lane × 32-bit padded accumulator),
      * matching a 32-bit AXI-Stream DMA S2MM. s_axis is also 32 bits (xDim*aWidth =
      * 4*8) so a single AXI DMA IP configured at 32-bit width covers both directions.
      */
    val K26_Bench: CoreConfig = CoreConfig(
        aWidth       = 16,
        maxAcc       = 4096,
        xDim         = 4,
        batchSize    = 1,
        maxN         = 1024,
        maxM         = 1024,
        outBeatLanes = 1
    )

    /**
      * Scaled-up bench: 16 array columns. aWidth=8 (4 lanes/PE, 64 MACs/cycle)
      * keeps s_axis at 16*8 = 128 bits — exactly the HP0 port width, so the
      * weight stream runs stall-free (aWidth=16 would need a 256-bit stream
      * through the 128-bit port and halve effective throughput).
      *
      * outBeatLanes=1 keeps m_axis at 32 bits (one padded accumulator lane),
      * so the DMA S2MM side is unchanged from K26_Bench. Theoretical peak:
      * 2*16*4 = 128 ops/cycle = 12.8 GOPS at 100 MHz.
      */
    val K26_Bench16: CoreConfig = CoreConfig(
        aWidth       = 8,
        maxAcc       = 4096,
        xDim         = 16,
        batchSize    = 1,
        maxN         = 1024,
        maxM         = 1024,
        outBeatLanes = 1
    )

    /**
      * Multi-HP-port bench: 32 array columns → s_axis = 32*8 = 256 bits, split
      * across N=2 parallel 128-bit ports (HP0+HP1), each fed by its own AXI DMA.
      * Theoretical peak: 2*32*4 = 256 ops/cycle = 25.6 GOPS at 100 MHz.
      *
      * outBeatLanes=1 keeps m_axis at 32 bits — output stays on DMA0's S2MM.
      */
    val K26_Bench32: CoreConfig = CoreConfig(
        aWidth       = 8,
        maxAcc       = 4096,
        xDim         = 32,
        batchSize    = 1,
        maxN         = 1024,
        maxM         = 1024,
        outBeatLanes = 1
    )

    /**
      * Multi-HP-port bench: 64 array columns → s_axis = 64*8 = 512 bits, split
      * across N=4 parallel 128-bit ports (HP0–HP3), one AXI DMA each.
      * Theoretical peak: 2*64*4 = 512 ops/cycle = 51.2 GOPS at 100 MHz.
      */
    val K26_Bench64: CoreConfig = CoreConfig(
        aWidth       = 8,
        maxAcc       = 4096,
        xDim         = 64,
        batchSize    = 1,
        maxN         = 1024,
        maxM         = 1024,
        outBeatLanes = 1
    )

    /**
      * Every projection of the 370M matmulfree LM (MMfreeLM-370M: hidden=1024,
      * GLU intermediate=2816, vocab=32000, 24 layers) on the full 4-HP-port
      * geometry. Distinct single-token matmul shapes (N inner × M out):
      *
      *   i/f/g/o_proj : 1024 × 1024    (4 per layer)
      *   gate_up      : 1024 × 5632    (fused gate+up = 2×2816)
      *   down_proj    : 2816 × 1024
      *   lm_head      : 1024 × 32000
      *
      *   - xDim=64, aWidth=8 → 512-bit s_axis split across 4 × 128-bit HP
      *     ports (the PS ceiling) — same datapath as K26_Bench64.
      *   - maxN=4096 covers down_proj's inner dim 2816 (next pow2); maxAcc
      *     matches, so accWidth stays 20 and m_axis lanes stay 32-bit.
      *   - maxM=32000 covers the LM head. 32000 = 125 col tiles of 256 lanes;
      *     5632 = 22 tiles, 1024 = 4 tiles — every projection is tile-aligned,
      *     so no padding lanes anywhere in the sweep.
      *   - outBeatLanes=1 keeps m_axis at 32 bits: output stays on DMA0's
      *     S2MM, unchanged from the bench presets.
      */
    val K26_MMFree370M: CoreConfig =
        forModel(hidden = 1024, intermediate = 2816, vocab = 32000, layers = 24,
                 aWidth = 8, xDim = 64, outBeatLanes = 1, signedAct = false,
                 plClkMhz = 250, shapes = "370m", model = "ridger/MMfreeLM-370M")

    /**
      * Signed int16-activation sibling of K26_MMFree370M, for real BitLinear
      * inference where activations are signed (and the HGRN carries 16-bit
      * fixed-point). int16 is nearly free on this engine: weights stay 2-bit
      * ternary (same 85 MB/token, same MACs/cycle), activations live in BRAM.
      *
      * Geometry reshapes to keep the 4-HP-port stream: aWidth=16 forces xDim=32
      * (xDim*aWidth = 512 b = 4 x 128), giving outLanesPerTile = 32*8 = 256 —
      * identical to the 64*4 of the int8 preset. accWidth = log2(4096)+16 = 28,
      * but outLaneWidth = nextPow2(28) = 32, so the m_axis output format is
      * UNCHANGED (still one 32-bit lane per beat). signedAct=true sign-extends.
      */
    val K26_MMFree370M_A16: CoreConfig =
        forModel(hidden = 1024, intermediate = 2816, vocab = 32000, layers = 24,
                 aWidth = 16, xDim = 32, outBeatLanes = 1, signedAct = true,
                 plClkMhz = 250, shapes = "370m", model = "ridger/MMfreeLM-370M")

    /**
      * Batched siblings of K26_MMFree370M_A16 for the batching sweep (Phase 0+).
      * batchSize=B processes B activation vectors per single weight stream
      * (one-load, B-results), amortizing the DDR weight bandwidth that bounds
      * decode. Spatial batching: weights broadcast to all B PE rows, each row has
      * its own activations + accumulators.
      *
      *   - B ≤ 8 keeps the LOAD beat (max(xDim,B)*aWidth = 512 b) at 4 × 128-bit
      *     ports, so the bitstream topology is identical to the B=1 a16 preset;
      *     only the yDim PE rows, actBram width, accumulator banks and outBram
      *     depth scale ×B.
      *   - outBeatLanes=4 (128-bit m_axis) — board B=4 showed the STORE is the
      *     batch bottleneck: it doesn't amortize (B*M outputs read out) and was
      *     m_axis-width-bound at ~1 GB/s (32-bit S2MM). 128-bit matches the HP
      *     port → 4 GB/s store. It also shrinks outSubBeats (256→64) so the drain
      *     hides through B=8 AND shallows outBram (shorter URAM cascade).
      */
    val K26_MMFree370M_A16_B2: CoreConfig = K26_MMFree370M_A16.copy(batchSize = 2, outBeatLanes = 4)
    val K26_MMFree370M_A16_B4: CoreConfig = K26_MMFree370M_A16.copy(batchSize = 4, outBeatLanes = 4)
    // B6 is the device sweet spot on K26: B8 overflowed the fabric (95.4% LUT →
    // routing congestion, post-route WNS −0.693 @250 MHz, a capacity wall not a
    // single path). B6 is ~3/4 of that array (~74–77% LUT est.) so it closes at
    // full 250 MHz — no clock drop, no HP-port bandwidth loss — for ~1.5× the
    // B4 stream-bound throughput. No power-of-2 batch requirement: all batch
    // addressing is arithmetic (drainBatchCtr*numColTiles+…). outBram = 6*125*64
    // = 48000-deep URAM (shallower than B8's 64000 → easier write-addr cascade).
    val K26_MMFree370M_A16_B6: CoreConfig = K26_MMFree370M_A16.copy(batchSize = 6, outBeatLanes = 4)
    val K26_MMFree370M_A16_B8: CoreConfig = K26_MMFree370M_A16.copy(batchSize = 8, outBeatLanes = 4)

    // ── accumulator-headroom OOC sweep (drain-cost investigation) ─────────────
    // Siblings of the K26 sweet-spot B6 with a trimmed accumulator. 370M's
    // maxN=nextPow2(2816)=4096, so the default headroom is log2Ceil(4096)=12
    // (accWidth = 16+12 = 28). These lower it to 10 / 6 (accWidth 26 / 22); nextPow2
    // keeps the m_axis lane at 32 bits for any headroom in 1..16, so they differ from
    // B6 ONLY in internal accumulator / tileAccums / outBram width. Build all three
    // OOC (ooc_synth.tcl) and compare LUT/FF/WNS to see if the wide accumulator is the
    // synthesis cost before committing to the shadow-bank drain rewrite. NOT for
    // deployment until the model's real dynamic range is validated (overflow).
    val K26_MMFree370M_A16_B6_H10: CoreConfig = K26_MMFree370M_A16_B6.copy(accHeadroomBits = 10)
    val K26_MMFree370M_A16_B6_H6:  CoreConfig = K26_MMFree370M_A16_B6.copy(accHeadroomBits = 6)

    // ── input-broadcast fanout experiment (target the documented B6@250 path) ──
    // The batched array-feed register broadcasts the activation to all xDim PEs per
    // row; at B6 that is the route-bound ~-0.37 ns path (CoreConfig comment above).
    // actMaxFanout forces synth to replicate that register per column. Two limits to
    // bracket; a full impl build is the only real timing test (OOC can't see the
    // congestion). SV differs from B6 only in the array-feed skid + the attribute.
    val K26_MMFree370M_A16_B6_F32: CoreConfig = K26_MMFree370M_A16_B6.copy(actMaxFanout = 32)
    val K26_MMFree370M_A16_B6_F16: CoreConfig = K26_MMFree370M_A16_B6.copy(actMaxFanout = 16)

    // ── runtime-batch A/B sibling of the 370M B6 sweet spot ─────────────────
    // CONFIG-IDENTICAL to K26_MMFree370M_A16_B6 — it exists only so a rebuild from
    // a tree that HAS the runtime-batch drain (COMPUTE flags → cmpBatch) lands in its
    // own preset dir instead of clobbering the deployed 2026-06-25 bitstream, which
    // predates that RTL. Bench the two side by side: the batch sweep on _B6 shows a
    // b<B call still draining all 6 rows (flat per-step cost), while _B6_RB should
    // show b=1 paying ~B1 cost. Once that A/B is settled, this preset can be deleted
    // and K26_MMFree370M_A16_B6 simply rebuilt in place.
    val K26_MMFree370M_A16_B6_RB: CoreConfig = K26_MMFree370M_A16_B6

    // ── DSP48E2 accumulator experiment ──────────────────────────────────────
    // Map the per-lane PE accumulators onto DSP slices instead of LUT/CARRY8.
    // One DSP/lane if Vivado infers cleanly → xDim*batch*nLanes = 32*batch*8 DSPs.
    // The K26 has 1248 DSP48E2, so the array fits ONLY up to B4 (32*4*8 = 1024);
    // B6 would need 1536 (>1248) and spill back to fabric — so this variant is
    // based on B4, the largest fully-DSP-coverable batch. Payoff: free the
    // LUT/CARRY8 the accumulators used (the batched presets are LUT-bound — see
    // the B8 ~95% capacity wall), possibly buying headroom for clock or a bigger
    // array. SV differs from B4 only in the use_dsp attr on accumReg; behavior is
    // identical. Build both and diff reports/<tag>.rpt for DSP/LUT/CARRY8 + WNS.
    val K26_MMFree370M_A16_B4_DSP: CoreConfig = K26_MMFree370M_A16_B4.copy(dspAccum = true)

    /**
      * Larger MMfreeLM checkpoints on the SAME a16 / 4-HP-port engine as 370M.
      * Architecture (from the released ridger/MMfreeLM checkpoints, vocab=32000):
      *
      *   model  hidden  intermediate  gate_up(2I)  layers   weight traffic/token
      *   370M    1024        2816         5632        24        85.3 MB
      *   1.3B    2048        5632        11264        24       324.7 MB
      *   2.7B    2560        6912        13824        32       654.9 MB
      *
      * Only the inner dim grows past 370M's: max(hidden,intermediate) is 5632
      * (1.3B) / 6912 (2.7B), so maxN = nextPow2 = 8192 and maxAcc = 8192 for both
      * (accWidth = log2(8192)+16 = 29 → 32-bit lane, m_axis format unchanged).
      * maxM stays 32000 (the LM head dominates every gate_up). 1.3B and 2.7B
      * therefore share an IDENTICAL bitstream geometry — they differ only in the
      * default bench sweep — and that maxN=8192 engine also runs 370M.
      */
    val K26_MMFree1_3B_A16: CoreConfig =
        forModel(hidden = 2048, intermediate = 5632, vocab = 32000, layers = 24,
                 shapes = "1.3b", model = "ridger/MMfreeLM-1.3B")
    val K26_MMFree2_7B_A16: CoreConfig =
        forModel(hidden = 2560, intermediate = 6912, vocab = 32000, layers = 32,
                 shapes = "2.7b", model = "ridger/MMfreeLM-2.7B")

    // Batched siblings (outBeatLanes=4 → 128-bit m_axis, as in the 370M batch
    // presets). B6 is the 370M K26 sweet spot; at maxN=8192 the actBram/accum
    // banks are ~2x deeper, so the batch ceiling may land lower — confirm OOC
    // resource/timing on the board before trusting B6 at this scale.
    // B6 missed 250 MHz timing at maxN=8192 (WNS −0.369 ns; the deeper actBram/URAM
    // cascade + 77% LUT congestion lengthens the store path past the 370M B6 that
    // closed). B4 is the fallback — less array logic, shorter cascades.
    // The maxN=8192 B4 @ 250 MHz closure is razor-thin (post-route WNS ~0 ns): the default
    // impl flow misses by ~0.07 ns, but this lever set closes it (1.3B B4 landed +0.004 ns).
    // Baked into the preset so `make build` applies them automatically — no remembered env.
    // (The store/broadcast path is identical for 1.3B and 2.7B B4, so the same set closes both.
    // B6 @ 250 can't be closed by these levers — it drops to 230 MHz instead; see _B6_230.)
    private val b4TimingLevers250 = Map(
        "SYNTH_FLATTEN"                 -> "full",
        "PLACE_DIRECTIVE"               -> "ExtraNetDelay_high",
        "PHYS_OPT_DIRECTIVE"            -> "AggressiveExplore",
        "ROUTE_DIRECTIVE"               -> "AggressiveExplore",
        "POST_ROUTE_PHYS_OPT_DIRECTIVE" -> "AggressiveExplore"
    )
    val K26_MMFree1_3B_A16_B4: CoreConfig = K26_MMFree1_3B_A16.copy(batchSize = 4, outBeatLanes = 4, implDirectives = b4TimingLevers250)
    val K26_MMFree2_7B_A16_B4: CoreConfig = K26_MMFree2_7B_A16.copy(batchSize = 4, outBeatLanes = 4, implDirectives = b4TimingLevers250)
    val K26_MMFree1_3B_A16_B6: CoreConfig = K26_MMFree1_3B_A16.copy(batchSize = 6, outBeatLanes = 4)
    val K26_MMFree2_7B_A16_B6: CoreConfig = K26_MMFree2_7B_A16.copy(batchSize = 6, outBeatLanes = 4)
    // B6 can't close 250 MHz (route-bound arm-RAM→accumulator broadcast, ~−0.37 ns,
    // unmoved by the impl levers). fmax ≈ 228 MHz, so this steps down to the next rung.
    // RENAMED 2026-08-04 from _230: 230 is not a PL0_REF rung, so the "230 MHz" build was
    // always constrained (and, after the deploy snap-down fix, always run) at 199.998 MHz.
    // See the plClkMhz require above. The name now matches the constraint.
    val K26_MMFree1_3B_A16_B6_200: CoreConfig = K26_MMFree1_3B_A16_B6.copy(plClkMhz = 200)

    // ── B6 timing/area levers ported from the 370M B6 sweep to the maxN=8192 engine ──
    // Same two experiments that bracket the 370M B6 (accumulator-headroom trim +
    // input-broadcast fanout), aimed squarely at the route-bound arm-RAM→accumulator
    // broadcast path that keeps 1.3B/2.7B B6 off 250 MHz (see _B6_230). maxN=8192 →
    // auto headroom = log2Ceil(8192) = 13 (accWidth 16+13 = 29), so these trim to 11/7
    // (accWidth 27/23) — 2 and 6 bits below auto, matching the 370M drop of 2/6 from its
    // auto 12. nextPow2 keeps the m_axis lane at 32 bits for any headroom in 1..16, so
    // the H siblings differ from B6 ONLY in accumulator / tileAccums / outBram width, and
    // the F siblings ONLY in the array-feed skid + max_fanout attribute. NOT for
    // deployment until the model's real dynamic range is validated (overflow); a full
    // impl build is the only real timing test (OOC can't see the congestion).
    // 1.3B and 2.7B share one bitstream geometry, so each lever is mirrored across both.
    val K26_MMFree1_3B_A16_B6_H11: CoreConfig = K26_MMFree1_3B_A16_B6.copy(accHeadroomBits = 11)
    val K26_MMFree1_3B_A16_B6_H7:  CoreConfig = K26_MMFree1_3B_A16_B6.copy(accHeadroomBits = 7)
    val K26_MMFree1_3B_A16_B6_F32: CoreConfig = K26_MMFree1_3B_A16_B6.copy(actMaxFanout = 32)
    val K26_MMFree1_3B_A16_B6_F16: CoreConfig = K26_MMFree1_3B_A16_B6.copy(actMaxFanout = 16)
    val K26_MMFree2_7B_A16_B6_H11: CoreConfig = K26_MMFree2_7B_A16_B6.copy(accHeadroomBits = 11)
    val K26_MMFree2_7B_A16_B6_H7:  CoreConfig = K26_MMFree2_7B_A16_B6.copy(accHeadroomBits = 7)
    val K26_MMFree2_7B_A16_B6_F32: CoreConfig = K26_MMFree2_7B_A16_B6.copy(actMaxFanout = 32)
    val K26_MMFree2_7B_A16_B6_F16: CoreConfig = K26_MMFree2_7B_A16_B6.copy(actMaxFanout = 16)

    // DSP48E2-accumulator variant (see K26_MMFree370M_A16_B4_DSP). Same maxN=8192
    // B4 geometry (32*4*8 = 1024 lanes → 1024 DSPs ≤ 1248) + the 250 MHz levers;
    // differs from B4 only in the use_dsp attr on accumReg. accWidth here is 29b
    // (16 + log2Ceil(8192)), still one DSP48E2 P-register per lane. A/B against
    // K26_MMFree2_7B_A16_B4 for DSP/LUT/CARRY8 + WNS.
    val K26_MMFree2_7B_A16_B4_DSP: CoreConfig = K26_MMFree2_7B_A16_B4.copy(dspAccum = true)
    // DSP accumulators + systolic column banking (8 cols/bank → 4 banks at xDim=32):
    // cuts the activation-broadcast fanout 32→8 to buy back margin on the DSP build,
    // which closed 250 MHz with WNS ~0.000 (no room for silicon/temp fluctuation).
    // Numerically identical, +3 cyc latency/op. A/B against K26_MMFree2_7B_A16_B4_DSP.
    val K26_MMFree2_7B_A16_B4_DSP_S8: CoreConfig = K26_MMFree2_7B_A16_B4_DSP.copy(colBankSize = 8)

    // 1.6-bit weight packing (packedWeights): a 256-entry unpack ROM + elastic gearbox
    // on the s_axis→inQ path streams ternary weights at 5 trits/byte, ~20% fewer weight
    // bytes on the DDR/HP-port wire — the binding resource for the bandwidth-bound decode
    // (see [[fclk_runtime_vs_sta_clock]]). forModel(packedWeights=true) also sizes udmabuf-wt
    // to the exact packed resident set (~0.8x). The 1.6-bit weight BLOB format MUST match
    // this bitstream (weight_format=ternary-1p6; the runtime cross-checks MMFREE_PACKED_WEIGHTS).
    //   _PACKED       — B1 base, for correctness bring-up + `make gate` bit-exactness.
    //   _B4_DSP_S8_PACKED — the workhorse on the known-closing B4+DSP+S8 stack, for tok/s.
    // CLOCK: the workhorse runs at 200 MHz, NOT 250. The 2.7B B4_DSP_S8 base closes 250
    // at only +0.001 ns; its critical path is the maxN=8192 activation-BRAM 8-deep
    // RAMB36E2 cascade → inQ (NOT the gearbox — LUT util ~43%, no congestion). Adding the
    // unpack gearbox perturbs placement ~0.1 ns and tips that razor-thin path (packed 250
    // build: WNS −0.097, Est Fmax 244). 250 was never sustainable here with added logic.
    // Dropping a rung is nearly free on tok/s: decode is bandwidth-bound (see
    // [[fclk_runtime_vs_sta_clock]]; the 12-arm sweep measured 250→200 = −20% clock costing
    // only 2–5% at b=6), and packing already cut the binding weight bandwidth 20%.
    // WAS plClkMhz=230 until 2026-08-04 — 230 is not a rung, so the constraint was 199.998
    // all along (WNS +0.096 against 5.000 ns = Fmax 203.9, not the 235 the .rpt claimed).
    // Only the LABEL changes here; the constraint is what it always was.
    val K26_MMFree2_7B_A16_PACKED: CoreConfig =
        forModel(hidden = 2560, intermediate = 6912, vocab = 32000, layers = 32,
                 shapes = "2.7b", model = "ridger/MMfreeLM-2.7B", packedWeights = true)
    val K26_MMFree2_7B_A16_B4_DSP_S8_PACKED: CoreConfig =
        K26_MMFree2_7B_A16_PACKED.copy(batchSize = 4, outBeatLanes = 4, plClkMhz = 200,
            dspAccum = true, colBankSize = 8, implDirectives = b4TimingLevers250)

    // B6 on the same DSP-accum + column-banking (S8) lever stack, targeting 250 MHz.
    // The runtime-batch drain (COMPUTE flags field → cmpBatch) makes this ONE bitstream
    // serve both regimes: b=6 amortizes the 654 MB/token weight stream (the real lever
    // for the bandwidth-bound 2.7B decode), while a b=1 decode drains only row 0 so it
    // pays ~B1's cost (the weight stream is already batch-count-independent).
    // CAVEAT — DSP capacity: full DSP-accum coverage needs xDim*batch*nLanes =
    // 32*6*8 = 1536 DSP48E2 > the K26's 1248, so ~1/4 of the lane accumulators spill
    // back to LUT/CARRY8. Banking (S8) + the aggressive impl flow are what close 250;
    // if the spill-driven congestion misses, fall back to B6_S8 (banking only, no DSP)
    // or the B4_DSP_S8 clean base. A full impl build is the only real timing test.
    val K26_MMFree2_7B_A16_B6_DSP_S8: CoreConfig =
        K26_MMFree2_7B_A16_B6.copy(dspAccum = true, colBankSize = 8, implDirectives = b4TimingLevers250)

    // B6 + DSP + banking at 200 MHz — the throughput-max maxN=8192 build, and the
    // deployable conclusion of the B6@250 hunt. Both 250 attempts missed by a wide,
    // lever-immune margin (DSP_S8 −0.376, S8-only −0.483), which is the same route-bound
    // arm-RAM→accumulator broadcast that sent 1.3B B6 down a rung (see _B6_200). The rung
    // below buys 1.000 ns over the DSP_S8 miss, so it closes comfortably (+0.118).
    // Dropping the clock costs almost nothing here: decode is weight-bandwidth-bound — the
    // 12-arm sweep measured 250→200 (−20% clock) costing only 2–5% at b=6. B6 vs B4 is the
    // lever that matters — 6 tokens per weight stream instead of 4 on a 654 MB/token
    // stream. With the runtime-batch drain this ONE bitstream also serves b=1..5, and the
    // maxN=8192 geometry runs 370M/1.3B/2.7B, so it is the single build a batch sweep needs.
    // RENAMED 2026-08-04 from _230, which was never a real clock: 230 is not a PL0_REF rung
    // and snapped to 199.998, so this bitstream's +0.118 ns is slack against 5.000 ns
    // (Fmax 204.8) — NOT against 4.348 ns as the report used to claim. The build itself is
    // unchanged; only the label was wrong. fclk0 still has to be set explicitly on the
    // board (deploy_kria.sh does it from the manifest) or it keeps the stale rate.
    val K26_MMFree2_7B_A16_B6_DSP_S8_200: CoreConfig =
        K26_MMFree2_7B_A16_B6_DSP_S8.copy(plClkMhz = 200)

    // Banking-only B6 @ 250 — the DSP_S8 fallback. B6_DSP_S8 missed 250 at WNS −0.376 ns
    // with the critical path on actBram/inQ reads: the 1248-DSP + LUT/CARRY8 spill (1536
    // needed, only 1248 available) congested the BRAM read paths. This drops dspAccum so
    // the accumulators go back to LUT/CARRY8 (no spill-driven congestion) while KEEPING
    // colBankSize=8, which cuts the activation-broadcast fanout 32→8 — the exact route-
    // bound arm-RAM→accumulator broadcast that holds plain B6 at ~−0.37 ns / fmax ≈ 228
    // (see _B6_230, which gave up and dropped to 230). Banking targets that path directly
    // and it's untried on B6 without the DSP congestion on top; if it still misses 250,
    // fall back to the clean B4_DSP_S8 base. Full impl build is the only real timing test.
    val K26_MMFree2_7B_A16_B6_S8: CoreConfig =
        K26_MMFree2_7B_A16_B6.copy(colBankSize = 8, implDirectives = b4TimingLevers250)

    // ── combined-lever B6 @ 250: the default candidate ────────────────────────
    // Both levers on the 2.7B B6 at once — the first attempt to actually CLOSE the
    // route-bound arm-RAM→accumulator broadcast that keeps B6 off 250 MHz (see
    // _B6_230), rather than bracketing each lever alone. H11 = conservative 2-bit
    // headroom trim (accWidth 27, safest overflow margin); F16 = aggressive per-column
    // replication of the broadcast register (shortest routes on the critical path).
    // F16 only crosses into the array under SYNTH_FLATTEN=full, and the miss is wide
    // (~−0.37 ns), so this carries the proven b4TimingLevers250 aggressive impl flow
    // (SYNTH_FLATTEN=full + Aggressive place/phys/route) — the full toolbox. If it
    // closes 250 AND validates on-board (errs=0), this + its 1.3B sibling become the
    // default B6 presets. NOT deployment-ready until the model's real dynamic range is
    // validated (H11 overflow); this build is the timing/correctness gate for that.
    val K26_MMFree2_7B_A16_B6_H11_F16: CoreConfig =
        K26_MMFree2_7B_A16_B6.copy(accHeadroomBits = 11, actMaxFanout = 16, implDirectives = b4TimingLevers250)
    val K26_MMFree1_3B_A16_B6_H11_F16: CoreConfig =
        K26_MMFree1_3B_A16_B6.copy(accHeadroomBits = 11, actMaxFanout = 16, implDirectives = b4TimingLevers250)

    // ══ FROZEN SET (2026-07-30): the best packed bitstream per model size ══════
    // Deliberately TWO builds, not three. maxN = nextPow2(max(hidden, intermediate)) and
    // maxM = ceilTo(max(2*intermediate, 32000), 256), so:
    //   370M -> maxN 4096, maxM 32000
    //   1.3B -> maxN 8192, maxM 32000
    //   2.7B -> maxN 8192, maxM 32000
    // 1.3B and 2.7B therefore produce byte-IDENTICAL RTL — they differ only in shapesHint /
    // model / residentWtBytes, which feed the manifest (udmabuf-wt sizing, MODEL, SHAPES) and
    // never reach the Core. So build A below is the 1.3B+2.7B bitstream and 1.3B runs on it
    // via `MODEL=1.3b` with WT sized for the larger 2.7B set (see [[universal_overlay_wt_patch]]).
    //
    // WHY THIS IS "BEST": B6 is the batch ceiling for EVERY size — B8 is a 95.4% LUT capacity
    // wall, a device limit rather than a fixable path. Board-measured 2026-07-30: B6 is worth
    // +11.8% over B4 and 1.6-bit packing +4.2%, and the two are independent, so B6+packed is
    // the combination and it projects ~30.5 tok/s at 2.7B b=6. Clock is the LEAST valuable
    // axis (the 12-arm sweep measured 250→200 = −20% clock costing only 2–5% at b=6, decode
    // being weight-bandwidth-bound; see [[fclk_runtime_vs_sta_clock]]), so each build targets
    // the highest RUNG that CLOSES — and only rungs, since off-rung values snap down.
    //
    // TIMING BUDGET — the unpack gearbox costs ~0.098 ns, measured on 2.7B B4_DSP_S8 (WNS
    // +0.001 unpacked -> -0.097 packed, both at 250). Applied to the known closures:
    //   A: B6_DSP_S8 @200 closed +0.118  ->  packed @200 ~= +0.02 (thin; measured +0.003)
    //   B: 370M B6   @250 closed +0.048  ->  packed @250 ~= -0.05 (misses) unless banking
    //      buys it back (it did: measured +0.030)
    // The "@225" and "@240" fallbacks these notes used to name were never distinct timing
    // points — both snap to the rung their parent already used. Deleted 2026-08-04.

    // Build A — 1.3B + 2.7B. Same DSP+banking lever stack as the measured B6_DSP_S8_200,
    // plus packing. Keeps dspAccum even though 32*6*8 = 1536 DSP > the K26's 1248 (~1/4 of
    // the lane accumulators spill to LUT/CARRY8), because that exact stack is what closed
    // 200 at +0.118 — this changes ONE thing (packing) against a known-good baseline.
    // plClkMhz was 230 until 2026-08-04; the constraint was 199.998 either way, so the
    // measured +0.003 ns is slack against 5.000 ns => Fmax 200.1. Razor-thin AT ITS REAL
    // RATE, which the 230 label had been hiding.
    val K26_MMFree2_7B_A16_B6_DSP_S8_PACKED: CoreConfig =
        K26_MMFree2_7B_A16_PACKED.copy(batchSize = 6, outBeatLanes = 4, plClkMhz = 200,
            dspAccum = true, colBankSize = 8, implDirectives = b4TimingLevers250)

    // ── Build A, 1.3B-named siblings (SAME bitstream, correct manifest) ───────
    // Build A's RTL is byte-identical for 1.3B and 2.7B (see the maxN/maxM arithmetic
    // above), so these exist ONLY to carry a 1.3B-correct manifest: MMFREE_MODEL =
    // ridger/MMfreeLM-1.3B (so `make model` provisions/packs the right checkpoint and the
    // board's gate/run/gen auto-find its blobs), MMFREE_SHAPES = 1.3b (bench sweep), and
    // UDMABUF_WT_SZ sized to 1.3B's resident set instead of 2.7B's — 1.3B then needs
    // ~310 MiB of CMA for 2-bit / ~248 MiB packed rather than 2.7B's 624 MiB.
    // They are NOT separate Vivado runs: assemble their transfer/presets/<p>/ from the
    // corresponding 2.7B bitstream (identical CoreTop.sv / Core.sv) with the dtbo's
    // udmabuf-wt re-sized, and keep the 2.7B WNS as the timing record
    // (+0.118 2-bit / +0.003 packed, both against the 5.000 ns / 199.998 MHz constraint —
    // reports/*.rpt).
    // Running MODEL=1.3b on the 2.7B-named preset stays valid and equivalent; it just
    // over-reserves WT and mislabels the sweep. See [[universal_overlay_wt_patch]].
    val K26_MMFree1_3B_A16_PACKED: CoreConfig =
        forModel(hidden = 2048, intermediate = 5632, vocab = 32000, layers = 24,
                 shapes = "1.3b", model = "ridger/MMfreeLM-1.3B", packedWeights = true)
    val K26_MMFree1_3B_A16_B6_DSP_S8_200: CoreConfig =
        K26_MMFree1_3B_A16_B6.copy(plClkMhz = 200,
            dspAccum = true, colBankSize = 8, implDirectives = b4TimingLevers250)
    val K26_MMFree1_3B_A16_B6_DSP_S8_PACKED: CoreConfig =
        K26_MMFree1_3B_A16_PACKED.copy(batchSize = 6, outBeatLanes = 4, plClkMhz = 200,
            dspAccum = true, colBankSize = 8, implDirectives = b4TimingLevers250)
    // The _225 "fallback" presets are GONE (2026-08-04). NOTE (2026-08-03) had already found
    // that the 2.7B parent built at "225" produced a bitstream BYTE-IDENTICAL to the "230"
    // one (same sha256, same WNS 0.003) from a separate full impl run, and guessed snapping
    // as the cause. That guess is now MEASURED: scripts/probe_pl_clk.tcl reads ACT_FREQMHZ
    // out of the PS IP and 215/220/225/230/240 all land on 199.998 MHz. So _225 was not a
    // safer timing point, it was the same build under a second name — 40 minutes of Vivado
    // per size for nothing. The plClkMhz require above makes that unrepeatable.

    // Build B — 370M. NOT dspAccum: the same 1536 > 1248 DSP spill that build A tolerates at
    // 200 is what sank 2.7B B6_DSP_S8 at 250 (WNS -0.376), and this build is trying to hold
    // 250. Banking only (colBankSize=8) cuts the activation-broadcast fanout 32->8, attacking
    // the route-bound path directly without adding DSP congestion; it is untried at 370M,
    // which closes 250 with no levers at all, so it is the credible way to absorb the gearbox.
    val K26_MMFree370M_A16_PACKED: CoreConfig =
        forModel(hidden = 1024, intermediate = 2816, vocab = 32000, layers = 24,
                 aWidth = 16, xDim = 32, outBeatLanes = 1, signedAct = true,
                 plClkMhz = 250, shapes = "370m", model = "ridger/MMfreeLM-370M",
                 packedWeights = true)
    val K26_MMFree370M_A16_B6_PACKED: CoreConfig =
        K26_MMFree370M_A16_PACKED.copy(batchSize = 6, outBeatLanes = 4,
            colBankSize = 8, implDirectives = b4TimingLevers250)
    // (There was a _240 fallback here for the case where 250 missed. It never meant anything:
    // 240 snaps to 199.998, so it would have been a 20% clock cut dressed up as a 4% one. 250
    // closed anyway at +0.030. Deleted 2026-08-04; the real fallback rung is 200.)

    // Build B's MATCHED 2-BIT CONTROL — the other half of the 370M packing A/B.
    // Until this existed the 370M pair was the only confounded one in the sweep set: its
    // 2-bit arm was K26_MMFree370M_A16_B6_RB (colBankSize = 0), so the pair difference mixed
    // column banking into the packing delta. 1.3B and 2.7B had matched pairs and 370M did not,
    // which is exactly the size where the packing gain should be smallest (85 MB/token of
    // weights vs 655 MB at 2.7B) and therefore the one that can least afford a confound.
    // This is K26_MMFree370M_A16_B6_PACKED with ONE field changed — packedWeights — so the
    // pair isolates the weight encoding and nothing else.
    //
    // Built from the UNPACKED base rather than `.copy(packedWeights = false)` on the packed
    // preset, and that distinction is load-bearing: residentWtBytes is computed INSIDE
    // forModel from packedWeights (2 bits/weight vs the exact 1.6-bit per-projection sum), so
    // copying the packed preset would keep its ~0.8x resident figure while the RTL streamed
    // full 2-bit weights — i.e. a udmabuf-wt sized 20% too small, which surfaces as a
    // truncated weight stream rather than an allocation error.
    //
    // Timing, BUILT 2026-08-03: closed 250 MHz at WNS +0.034, LUT 75.11% (vs the packed
    // build's +0.030 / 81.91%). The area came out as expected (-6.8 pp LUT, the gearbox) but
    // the ~0.098 ns the gearbox costs on 2.7B B4 did NOT come back here — the prediction of
    // ~+0.13 was wrong, so at 370M the gearbox is not on the critical path and the same
    // route-bound path bounds both arms. Convenient for the A/B: the two arms close 250 with
    // near-identical margin, so neither is advantaged by timing slack.
    val K26_MMFree370M_A16_B6_S8: CoreConfig =
        K26_MMFree370M_A16.copy(batchSize = 6, outBeatLanes = 4,
            colBankSize = 8, implDirectives = b4TimingLevers250)

    // ── B8 retry: the capacity wall was measured BEFORE dspAccum existed ──────
    // B8 was written off as "a ~95% LUT capacity wall, not a single path" — but that was on
    // fabric accumulators. Moving them into DSP48s is worth −21.9 pp LUT and −34 pp CARRY8 at
    // B6 (measured: 77.15/45.10 no-DSP vs 55.26/11.06 with), which changes the arithmetic
    // enough to be worth one build. Decomposing measured LUT% into fixed + per-batch array
    // logic + fabric cost of the DSP-spilled lanes (0.0143 pp/lane) predicts:
    //     B8 + DSP + packed  ~80% LUT, ~31% CARRY8, 50% URAM, ~54% BRAM, 100% DSP
    //     B8 WITHOUT dspAccum ~110% LUT  <- i.e. the original rejection was correct
    // so dspAccum is not an optimization here, it is the enabling condition. The model
    // reproduces B4 to 0.26 pp when the B6-measured packing delta is transferred down, but B8
    // is one extrapolation step past the data — treat ~80% as +/- a few points.
    //
    // WHY B8 AND NOT JUST MORE CLOCK: b=8 is optimal on BOTH sides at once, which nothing else
    // on the roadmap is. Engine side, the weight stream amortizes over 8 tokens instead of 6
    // (B4->B6 measured +11.8%). Host side, CPU vector ops cost ceil(b/4) waves on the 4 A53s
    // (parallel_rows is omp schedule(static) over batch rows), so efficiency b/(4*ceil(b/4)) is
    // 0.75 at b=6 but 1.00 at b=8 — b=5..8 all pay the same two waves and only b=8 fully
    // amortizes them (see [[batch_wave_quantization]]).
    //
    // CLOCK 200: B6+packed closed 200 at only +0.003, and B8 triples the DSP spill (288 -> 800
    // lanes) while pushing LUT 60 -> ~80% where congestion starts costing real delay, so there
    // is no headroom to give away. This asked for 215 until 2026-08-04, which is not a rung and
    // snapped to 199.998 — i.e. it was already this build, just mislabelled and believed to
    // carry ~0.30 ns of extra margin it never had. The next rung DOWN is 166.67, a 17% cut that
    // is not worth paying pre-emptively; build at 200 and drop only if it misses.
    // READING A FAILURE: LUT/placement overflow => the wall is real even with DSP, abandon B8.
    // A timing miss with utilization in range => just drop the clock further.
    val K26_MMFree2_7B_A16_B8_DSP_S8_PACKED: CoreConfig =
        K26_MMFree2_7B_A16_PACKED.copy(batchSize = 8, outBeatLanes = 4, plClkMhz = 200,
            dspAccum = true, colBankSize = 8, implDirectives = b4TimingLevers250)

    // ══ THE 250 MHz ATTEMPT ON maxN=8192 B6 (2026-08-04) ══════════════════════
    // Everything before this gave up on 250 for the 1.3B/2.7B B6 engine and stepped down a
    // rung. That retreat was decided from a single WNS number per build; since 2026-08-03
    // build_project.tcl also dumps reports/<tag>.paths.txt, and the picture it shows is the
    // reason to try again: `k26_mmfree2_7b_a16_b6_dsp_s8` @250 (WNS -0.376) fails on TWO
    // structurally different paths only 0.005 ns apart, so every previous single-lever
    // attempt was capped by whichever path it did not touch.
    //
    //   -0.376  logic 3.206 (76%)  7 levels  actBram/Memory_reg_bram_8 -> inQ/ram_ext
    //   -0.371  route 3.189 (74%)  3 levels  arr/REG_11_0_reg[0] -> arr_4_11 DSP ALU_OUT
    //
    // Path 1 is LOGIC-bound and specific to maxN=8192. The full timing report names the
    // mechanism outright: Vivado maps SyncReadMem(8192, Vec(6, UInt(16))) as 1024x36 blocks
    // and chains eight of them through the DSP-style hard cascade —
    //   Prop_RAMB36E2_CLKBWRCLK_CASDOUTB   1.588 ns   (bram_8)
    //   7x Prop_RAMB36E2_CASDINB_CASDOUTB  0.247 ns   (bram_9..15)
    //   Prop_RAMB36E2_CASDINB_DOUTBDOUT    0.136 ns
    // = 3.206 ns of pure logic before a single net is routed. 370M (maxN=4096, 4 deep)
    // closes 250 with no levers at all, which is the control: DEPTH is the variable, not the
    // batch width. actRamStyle="block" + actCascadeHeight=2 caps the chain at one hop and
    // resolves the remaining 4 groups in a fabric mux — ~1.7 ns of the 3.2 should come back,
    // for ~96 LUTs and no latency change (ActMem is read-for-read identical to SyncReadMem).
    //
    // Path 2 is ROUTE-bound at 74% — 2.785 ns on ONE net, a fanout-103 banked broadcast
    // register at SLICE_X33Y144 reaching a LUT3 at SLICE_X5Y55, i.e. most of the way across
    // the die. Pipelining cannot help a single net. The cause is physical: DSPs are 100% used
    // (1248/1248) while B6 wants 32*6*8 = 1536 accumulators, so ~288 lanes spill to fabric
    // and the array smears over the whole device. colBankSize=4 halves the fanout (8 banks
    // instead of 4) and gives the placer twice as many, smaller broadcast domains to keep
    // local. Cost is +7 cycles of latency per op instead of +3 (nAcc is 2048-8192: noise)
    // and ~2500 more flops for the deeper per-bank pipeline, on 35% register utilization.
    //
    // BOTH levers together or neither: fixing one path alone moves WNS by 0.005 ns.
    val K26_MMFree2_7B_A16_B6_DSP_S4_ACB2: CoreConfig =
        K26_MMFree2_7B_A16_B6_DSP_S8.copy(colBankSize = 4,
            actRamStyle = "block", actCascadeHeight = 2)
    // The deployable version of the above: same two levers on the packed B6, which is the
    // arm the 12-arm sweep actually shipped. Only meaningful if the 2-bit attempt closes —
    // the unpack gearbox costs ~0.098 ns on this engine, so this needs the attempt to land
    // with real margin, not at +0.00x.
    val K26_MMFree2_7B_A16_B6_DSP_S4_ACB2_PACKED: CoreConfig =
        K26_MMFree2_7B_A16_B6_DSP_S8_PACKED.copy(plClkMhz = 250, colBankSize = 4,
            actRamStyle = "block", actCascadeHeight = 2)
    // 1.3B-named siblings of the 250 builds — identical RTL (maxN/maxM are the same at 1.3B and
    // 2.7B, see the frozen-set arithmetic above), 1.3B manifest so MMFREE_MODEL/SHAPES and the
    // udmabuf-wt sizing are right. Assemble BOTH from the corresponding 2.7B bitstream with
    // scripts/clone_preset_hw.sh — seconds each, not two more 50-minute Vivado runs. The 2.7B
    // WNS is the timing record for them (+0.042 2-bit / +0.025 packed, both @250.000 MHz).
    val K26_MMFree1_3B_A16_B6_DSP_S4_ACB2: CoreConfig =
        K26_MMFree1_3B_A16_B6_DSP_S8_200.copy(plClkMhz = 250, colBankSize = 4,
            actRamStyle = "block", actCascadeHeight = 2)
    val K26_MMFree1_3B_A16_B6_DSP_S4_ACB2_PACKED: CoreConfig =
        K26_MMFree1_3B_A16_B6_DSP_S8_PACKED.copy(plClkMhz = 250, colBankSize = 4,
            actRamStyle = "block", actCascadeHeight = 2)
    // Attribution build, only worth running if the combined attempt CLOSES: the actBram fix
    // alone. If this also closes then colBankSize=4 was unnecessary and the cheaper preset
    // wins; if it misses by ~0.37 it confirms both paths had to be fixed. Not on the critical
    // path to a deployable bitstream — queue it after.
    val K26_MMFree2_7B_A16_B6_DSP_S8_ACB2: CoreConfig =
        K26_MMFree2_7B_A16_B6_DSP_S8.copy(actRamStyle = "block", actCascadeHeight = 2)

    /**
      * LM head config for matmulfree HGRN: M=32000 output (vocab size), inner dim
      * up to 4096 (covers the 370M-class model with hidden_dim=2731 and headroom).
      *
      *   - xDim = 64 → 512-bit s_axis at the typical 250 MHz PL clock targets
      *     full-DDR4 effective bandwidth (~16 GB/s) on K26.
      *   - batchSize = 1 (LM head is single-token during inference).
      *   - outBeatLanes = 32 → 1024-bit m_axis (standard AXI-DMA width), 8 sub-beats
      *     per col tile (8192/32 = 256 lanes per col tile → 8 m_axis beats).
      */
    val K26_LMHead: CoreConfig = CoreConfig(
        aWidth       = 8,
        maxAcc       = 4096,
        xDim         = 64,
        batchSize    = 1,
        maxN         = 4096,
        maxM         = 32000,
        outBeatLanes = 32
    )

    val presets: Map[String, CoreConfig] = Map(
        "default"     -> Default,
        "tiny"        -> Tiny,
        "k26_small"   -> K26_Small,
        "k26_medium"  -> K26_Medium,
        "k26_large"  -> K26_Large,
        "k26_bench"   -> K26_Bench,
        "k26_bench16" -> K26_Bench16,
        "k26_bench32" -> K26_Bench32,
        "k26_bench64" -> K26_Bench64,
        "k26_mmfree370m" -> K26_MMFree370M,
        "k26_mmfree370m_a16" -> K26_MMFree370M_A16,
        "k26_mmfree370m_a16_b2" -> K26_MMFree370M_A16_B2,
        "k26_mmfree370m_a16_b4" -> K26_MMFree370M_A16_B4,
        "k26_mmfree370m_a16_b6" -> K26_MMFree370M_A16_B6,
        "k26_mmfree370m_a16_b6_h10" -> K26_MMFree370M_A16_B6_H10,
        "k26_mmfree370m_a16_b6_h6"  -> K26_MMFree370M_A16_B6_H6,
        "k26_mmfree370m_a16_b6_f32" -> K26_MMFree370M_A16_B6_F32,
        "k26_mmfree370m_a16_b6_rb"  -> K26_MMFree370M_A16_B6_RB,
        "k26_mmfree370m_a16_b6_f16" -> K26_MMFree370M_A16_B6_F16,
        "k26_mmfree370m_a16_b4_dsp" -> K26_MMFree370M_A16_B4_DSP,
        "k26_mmfree370m_a16_b8" -> K26_MMFree370M_A16_B8,
        "k26_mmfree1_3b_a16" -> K26_MMFree1_3B_A16,
        "k26_mmfree1_3b_a16_b4" -> K26_MMFree1_3B_A16_B4,
        "k26_mmfree1_3b_a16_b6" -> K26_MMFree1_3B_A16_B6,
        "k26_mmfree1_3b_a16_b6_200" -> K26_MMFree1_3B_A16_B6_200,
        "k26_mmfree1_3b_a16_b6_h11" -> K26_MMFree1_3B_A16_B6_H11,
        "k26_mmfree1_3b_a16_b6_h7"  -> K26_MMFree1_3B_A16_B6_H7,
        "k26_mmfree1_3b_a16_b6_f32" -> K26_MMFree1_3B_A16_B6_F32,
        "k26_mmfree1_3b_a16_b6_f16" -> K26_MMFree1_3B_A16_B6_F16,
        "k26_mmfree2_7b_a16" -> K26_MMFree2_7B_A16,
        "k26_mmfree2_7b_a16_b4" -> K26_MMFree2_7B_A16_B4,
        "k26_mmfree2_7b_a16_b4_dsp" -> K26_MMFree2_7B_A16_B4_DSP,
        "k26_mmfree2_7b_a16_b4_dsp_s8" -> K26_MMFree2_7B_A16_B4_DSP_S8,
        "k26_mmfree2_7b_a16_packed" -> K26_MMFree2_7B_A16_PACKED,
        "k26_mmfree2_7b_a16_b4_dsp_s8_packed" -> K26_MMFree2_7B_A16_B4_DSP_S8_PACKED,
        "k26_mmfree2_7b_a16_b6_dsp_s8" -> K26_MMFree2_7B_A16_B6_DSP_S8,
        "k26_mmfree2_7b_a16_b6_dsp_s8_200" -> K26_MMFree2_7B_A16_B6_DSP_S8_200,
        // ── frozen set (2026-07-30): best packed bitstream per model size ──
        "k26_mmfree2_7b_a16_b6_dsp_s8_packed"     -> K26_MMFree2_7B_A16_B6_DSP_S8_PACKED,
        // 1.3B-named siblings of build A — identical RTL, 1.3B manifest (model/shapes/WT)
        "k26_mmfree1_3b_a16_packed"               -> K26_MMFree1_3B_A16_PACKED,
        "k26_mmfree1_3b_a16_b6_dsp_s8_200"        -> K26_MMFree1_3B_A16_B6_DSP_S8_200,
        "k26_mmfree1_3b_a16_b6_dsp_s8_packed"     -> K26_MMFree1_3B_A16_B6_DSP_S8_PACKED,
        "k26_mmfree370m_a16_packed"               -> K26_MMFree370M_A16_PACKED,
        "k26_mmfree370m_a16_b6_packed"            -> K26_MMFree370M_A16_B6_PACKED,
        "k26_mmfree370m_a16_b6_s8"                -> K26_MMFree370M_A16_B6_S8,
        // ── the 250 MHz attempt on maxN=8192 B6 (2026-08-04) ──
        "k26_mmfree2_7b_a16_b6_dsp_s4_acb2"           -> K26_MMFree2_7B_A16_B6_DSP_S4_ACB2,
        "k26_mmfree2_7b_a16_b6_dsp_s4_acb2_packed"    -> K26_MMFree2_7B_A16_B6_DSP_S4_ACB2_PACKED,
        "k26_mmfree1_3b_a16_b6_dsp_s4_acb2"           -> K26_MMFree1_3B_A16_B6_DSP_S4_ACB2,
        "k26_mmfree1_3b_a16_b6_dsp_s4_acb2_packed"    -> K26_MMFree1_3B_A16_B6_DSP_S4_ACB2_PACKED,
        "k26_mmfree2_7b_a16_b6_dsp_s8_acb2"           -> K26_MMFree2_7B_A16_B6_DSP_S8_ACB2,
        "k26_mmfree2_7b_a16_b8_dsp_s8_packed"     -> K26_MMFree2_7B_A16_B8_DSP_S8_PACKED,
        "k26_mmfree2_7b_a16_b6_s8" -> K26_MMFree2_7B_A16_B6_S8,
        "k26_mmfree2_7b_a16_b6" -> K26_MMFree2_7B_A16_B6,
        "k26_mmfree2_7b_a16_b6_h11" -> K26_MMFree2_7B_A16_B6_H11,
        "k26_mmfree2_7b_a16_b6_h7"  -> K26_MMFree2_7B_A16_B6_H7,
        "k26_mmfree2_7b_a16_b6_f32" -> K26_MMFree2_7B_A16_B6_F32,
        "k26_mmfree2_7b_a16_b6_f16" -> K26_MMFree2_7B_A16_B6_F16,
        "k26_mmfree2_7b_a16_b6_h11_f16" -> K26_MMFree2_7B_A16_B6_H11_F16,
        "k26_mmfree1_3b_a16_b6_h11_f16" -> K26_MMFree1_3B_A16_B6_H11_F16,
        "k26_lm_head" -> K26_LMHead
    )

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
      * Only maxN/maxAcc grow with model size; geometry (xDim, aWidth, ports) and
      * the 32-bit m_axis lane format are unchanged. One maxN=8192 bitstream thus
      * covers 370M, 1.3B and 2.7B — the bench just streams a smaller N for the
      * smaller models. `shapes` names the default bench sweep for the preset.
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
        // Total ternary weight elements the runner streams per token, summed over every
        // projection (matches the "weight traffic/token" table above): per layer the 4
        // i/f/g/o_proj (hidden^2), the fused gate_up (hidden x 2*intermediate) and
        // down_proj (intermediate x hidden); plus the one lm_head (hidden x vocab).
        // Ternary weights pack at 2 bits each, so bytes = elements * 2 / 8 = elements/4.
        val perLayerElems   = 4L * hidden * hidden + hidden.toLong * (2 * intermediate) + intermediate.toLong * hidden
        val totalWtElems    = perLayerElems * layers + hidden.toLong * vocab
        // Resident weight bytes (across ports). 2-bit keeps the verified element-count
        // sizing byte-for-byte (deployed overlays depend on it). Packed sums the EXACT
        // per-projection 1.6-bit blob (5 trits/byte, each padded to a whole wire beat),
        // so udmabuf-wt is sized ~0.8x without under-counting the pad — the same formula
        // the packer/runtime use per projection.
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
