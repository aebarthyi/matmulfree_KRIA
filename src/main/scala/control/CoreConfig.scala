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

    private val b4TimingLevers250 = Map(
        "SYNTH_FLATTEN"                 -> "full",
        "PLACE_DIRECTIVE"               -> "ExtraNetDelay_high",
        "PHYS_OPT_DIRECTIVE"            -> "AggressiveExplore",
        "ROUTE_DIRECTIVE"               -> "AggressiveExplore",
        "POST_ROUTE_PHYS_OPT_DIRECTIVE" -> "AggressiveExplore"
    )

    // ─── Dev / simulation fixtures ─────────────────────────────────────────
    // NOT deployable and deliberately NOT in `presets` below: they exist so the test
    // suite has small, fast-to-elaborate geometries. `make build PRESET=` only offers
    // what is in the registry.

    /** Compact default — single batch, 8x8 array. Also pins the case-class defaults. */
    val Default: CoreConfig = CoreConfig()

    /** Minimal config used by the test suite — fast to simulate. */
    val Tiny: CoreConfig = CoreConfig(
        aWidth = 4, maxAcc = 8, xDim = 2, batchSize = 2,
        maxN = 8, maxM = 16
    )

    // ─── Deployable presets ────────────────────────────────────────────────
    //
    // Exactly three, one per released MMfreeLM checkpoint. Each is the arm that WON the
    // 2026-08-04/05 board sweep for its size, flattened into a single definition so the
    // configuration is readable without chasing a .copy() chain through a dozen ancestors.
    //
    // The ~50 ablation presets these were selected from — batch ladders, accumulator
    // headroom, broadcast fanout, DSP-accum on/off, banking width, clock rungs, and the
    // matched 2-bit controls for every packed arm — live on the `exploration` branch
    // together with the sweep harness that produced the numbers below:
    //
    //     git checkout exploration
    //     make build PRESET=k26_mmfree2_7b_a16_b6_dsp_s8_200
    //
    // ── WHAT ALL THREE SHARE ──────────────────────────────────────────────────
    //
    // batchSize = 8, outBeatLanes = 4
    //   Decode is weight-bandwidth-bound, so the lever that matters is tokens amortized
    //   per weight stream. B6 measured +11.8% over B4; B8 then measured +11.5% (370M) and
    //   +15.4% (2.7B) over B6 at its own best point, and — the result that justified the
    //   promotion — costs NOTHING below b=8 (b=6 lands within 0.7% of the B6 bitstream on
    //   the same software). The "~95% LUT capacity wall" that once capped this at 6 was
    //   measured before dspAccum existed; with the accumulators in DSP48s all three close
    //   250 MHz at 81-83% LUT. See dspAccum below — it is the enabling condition, not a
    //   separate optimization.
    //   outBeatLanes=4 puts m_axis at 128 bits to match the HP port width; at 32 bits the
    //   STORE was the batch bottleneck at ~1 GB/s and did not amortize.
    //   The runtime batch count (COMPUTE flags field -> cmpBatch) makes ONE B8 bitstream
    //   also serve b=1..7 at ~B1 cost, so there is no reason to ship a low-B build.
    //
    // packedWeights = true — 1.6-bit weights, 5 trits/byte
    //   Cuts the binding weight traffic ~20%; measured +4.3% tok/s at 2.7B b=6, growing
    //   with model size and shrinking with batch. Independent of the B6 gain, so the two
    //   compound. This is an RTL parameter (the unpack gearbox only exists when it is
    //   set), so a packed bitstream needs a packed blob — the runner hard-errors on a
    //   mismatch rather than streaming garbage.
    //
    // plClkMhz = 250 — the top reachable PL0_REF rung (see plClkRungs).
    //   Worth only ~5% at b=6, because a bandwidth-bound decode barely notices the fabric
    //   clock. Kept because it is free once the build closes, and all three close.
    //
    // implDirectives = b4TimingLevers250 — SYNTH_FLATTEN=full plus Aggressive
    //   place/phys/route. Every one of these three needs it to make 250.
    //
    // ── WHAT DIFFERS, AND WHY ─────────────────────────────────────────────────
    //
    // 370M is maxN=4096. 1.3B and 2.7B are both maxN=8192 (nextPow2 of the largest inner
    // dim), so they emit BYTE-IDENTICAL RTL and differ only in manifest fields — model,
    // bench shapes, udmabuf-wt sizing. Three presets, TWO bitstreams: build the 2.7B one
    // and assemble 1.3B from it with scripts/clone_preset_hw.sh (seconds, not 50 minutes).
    //
    // The maxN=8192 pair carries three levers that 370M does not need:
    //
    //   dspAccum = true      Maps the lane accumulators onto DSP48E2 instead of LUT/CARRY8,
    //                        worth ~22 pp LUT and ~34 pp CARRY8. Full coverage would want
    //                        xDim*batch*nLanes = 32*8*8 = 2048 DSPs against the K26's 1248,
    //                        so ~800 lanes spill back to fabric; it is still what makes B8
    //                        fit. 370M carries it for the same reason, despite closing 250
    //                        without it at B6.
    //
    //   colBankSize = 4      Halves the activation-broadcast fanout. Targets the route-bound
    //                        path (74% route over 3 levels) that the DSP spill creates by
    //                        smearing the array across the die.
    //
    //   actRamStyle="block"  Caps the 8192x96 activation BRAM's 8-deep RAMB36E2 hard cascade
    //   actCascadeHeight=2   at one hop, resolving the rest in a fabric mux. That cascade was
    //                        3.206 ns of pure logic before a single net routed. The attributes
    //                        ride on the ActMem BlackBox because CIRCT cannot attach them to a
    //                        SyncReadMem — same reason OutMemUltra exists.
    //
    // The last two are a PAIR. The two paths they fix failed 0.005 ns apart, so fixing
    // either one alone moves WNS by 0.005 ns and wastes a 50-minute build; CoreConfigSpec
    // pins that they always travel together.
    //
    // 370M needs only the first two: maxN=4096 is a 4-deep cascade, so it closes 250 without
    // the ActMem cap. It carries dspAccum because that is what lets B8 fit, not for timing —
    // at B6 it closed 250 on banking alone.
    //
    // ── MEASURED — KRIA KV260, decode tok/s, POWER=1, all gates PASS, streams_ok=1 ─
    // Five passes, median, threaded readback, on these B8 bitstreams (2026-08-06).
    //
    //   preset        b=1     b=4     b=6     b=7     b=8   WNS@250  SOM W @b8  tok/J
    //   mmfree_370m  41.31  107.51  123.46  132.03  137.67  +0.011      5.52    24.96
    //   mmfree_1_3b  20.38   54.05   57.89†  68.51   71.53† +0.002*     6.17    11.60
    //   mmfree_2_7b  11.73   33.12   38.63   42.66   44.58  +0.002      6.39     6.97
    //
    //   * 1.3B runs the 2.7B bitstream, so the 2.7B WNS is its timing record.
    //   † 1.3B's host `pack` cost is bimodal at some shapes, so those two cells carry a
    //     wider spread than the other two models. See NEXT_STEPS.md.
    //   Against a Xeon W-2155 at the same weight format the KRIA is slower in raw throughput
    //   and ~an order of magnitude better in tok/J, the energy advantage growing with model
    //   size. The authoritative ratios are reports/final/dataset.csv, not this comment.
    //
    // b=8 is the best point, and b=1..7 come free from the same bitstream (cmpBatch). Prefer
    // b=4 or b=8 over b=5..7: the host's vector ops are omp static over the batch on 4 A53
    // cores, so they cost ceil(b/4) waves and only b=4 and b=8 fill the waves they pay for.

    /** MMfreeLM-370M — hidden 1024, intermediate 2816, vocab 32000, 24 layers.
      * maxN=4096, ~85 MB of weight traffic per token. 137.67 tok/s at b=8.
      */
    val MMFree_370M: CoreConfig =
        forModel(hidden = 1024, intermediate = 2816, vocab = 32000, layers = 24,
                 aWidth = 16, xDim = 32, outBeatLanes = 1, signedAct = true,
                 plClkMhz = 250, shapes = "370m", model = "ridger/MMfreeLM-370M",
                 packedWeights = true)
            .copy(batchSize = 8, outBeatLanes = 4, colBankSize = 4, dspAccum = true,
                  implDirectives = b4TimingLevers250)

    /** MMfreeLM-1.3B — hidden 2048, intermediate 5632, vocab 32000, 24 layers.
      * maxN=8192, ~325 MB of weight traffic per token. 71.53 tok/s at b=8.
      * Same RTL as MMFree_2_7B; assemble its bundle with scripts/clone_preset_hw.sh.
      */
    val MMFree_1_3B: CoreConfig =
        forModel(hidden = 2048, intermediate = 5632, vocab = 32000, layers = 24,
                 shapes = "1.3b", model = "ridger/MMfreeLM-1.3B", packedWeights = true)
            .copy(batchSize = 8, outBeatLanes = 4, plClkMhz = 250, dspAccum = true,
                  colBankSize = 4, actRamStyle = "block", actCascadeHeight = 2,
                  implDirectives = b4TimingLevers250)

    /** MMfreeLM-2.7B — hidden 2560, intermediate 6912, vocab 32000, 32 layers.
      * maxN=8192, ~655 MB of weight traffic per token. 44.58 tok/s at b=8.
      * The largest checkpoint the K26 runs, and the one to build from.
      */
    val MMFree_2_7B: CoreConfig =
        forModel(hidden = 2560, intermediate = 6912, vocab = 32000, layers = 32,
                 shapes = "2.7b", model = "ridger/MMfreeLM-2.7B", packedWeights = true)
            .copy(batchSize = 8, outBeatLanes = 4, plClkMhz = 250, dspAccum = true,
                  colBankSize = 4, actRamStyle = "block", actCascadeHeight = 2,
                  implDirectives = b4TimingLevers250)

    /** The deployable set — what the board bundle ships and what the report quotes.
      * Dev fixtures above are deliberately absent; closed ablations live on `exploration`.
      */
    val deployable: Map[String, CoreConfig] = Map(
        "mmfree_370m" -> MMFree_370M,
        "mmfree_1_3b" -> MMFree_1_3B,
        "mmfree_2_7b" -> MMFree_2_7B
    )

    // ─── Candidates ────────────────────────────────────────────────────────
    // Buildable and benchable, but NOT shipped: configurations being evaluated against the
    // deployable set. Kept separate so `deployable` stays exactly the three the report
    // quotes, while `make build PRESET=` can still reach these. A candidate either wins and
    // is promoted, or loses and moves to `exploration` with its result recorded.
    //
    // ── B8: the one batch that is optimal on BOTH sides ───────────────────────
    // batchSize=8 was written off as a ~95% LUT capacity wall — but that was MEASURED on
    // 370M B8 with fabric accumulators, before dspAccum existed, and moving the lane
    // accumulators into DSP48s is worth ~22 pp of LUT. Extrapolating the two measured
    // 2.7B DSP+packed points (B4 49839 LUT, B6 70338) linearly and correcting for the extra
    // DSP spill puts B8 at ~81% LUT, 47% registers, 20% CARRY8, 49% BRAM, 50% URAM. That
    // fits. dspAccum is not an optimization here, it is the enabling condition — which is
    // why 370M gains it below despite closing 250 without it at B6.
    //
    // WHY B8 IS WORTH A BUILD: it is the only point that helps engine and host at once. The
    // engine amortizes the weight stream over 8 tokens instead of 6 (B4->B6 measured
    // +11.8%). The host's vector ops are omp-static over the batch on 4 A53 cores, so they
    // cost ceil(b/4) waves — b=8 runs two full waves at 100% efficiency where b=6 wastes a
    // quarter of the second wave. b=5..8 all pay two waves and only b=8 amortizes them.
    //
    // THE RISK WAS TIMING, NOT CAPACITY. Full DSP coverage wants xDim*batch*nLanes =
    // 32*8*8 = 2048 against the K26's 1248, so 800 lanes spill to fabric versus 288 at B6 —
    // and that spill is exactly what created the route-bound broadcast path colBankSize=4
    // was added to fix. B6 closed 250 at +0.025 ns with all 30 worst paths clustered in
    // 0.042-0.063 ns on one congestion family, so there was no margin to give away.
    //
    // ── B8 was PROMOTED to deployable 2026-08-06, and the comparison is CLOSED ─
    // The three configs above carry batchSize = 8; the reasoning above is preserved because
    // it is what the promotion rests on. What the builds measured:
    //
    //     preset            WNS@250    LUT      Reg     CARRY8   BRAM     URAM     DSP
    //     mmfree_2_7b       +0.002   83.18%   49.76%   25.92%   49.65%   50.00%   100%
    //     mmfree_370m       +0.011   81.42%   48.30%   25.92%   29.51%   53.13%   100%
    //
    // and what the board measured, five passes on one software build: B8 costs nothing below
    // b=8 (its b=6 lands within 0.7% of the B6 bitstream) and gains +11.5% (370M) / +15.4%
    // (2.7B) at b=8. Best-point to best-point, +10.7% and +15.6%.
    //
    // ⚠️ 2.7B's +0.002 ns is the thinnest margin anything shipped has ever had, and a
    // different placement seed could miss. That risk was accepted on promotion rather than
    // retired: it has NOT been seed-swept, and the 200 MHz rung (~5% slower, far more
    // margin) remains the fallback if the board shows intermittent trouble. The gate is the
    // check that matters here — bit-exactness at temperature, per bitstream, per session.
    //
    // The B6 configurations were retained here as the control while that comparison was open.
    // It is now settled, B8 is the final shipped configuration, and they were removed
    // 2026-08-07 rather than left as a second way to build a bitstream nobody should deploy.
    // `git show d63d9f9:src/main/scala/control/CoreConfig.scala` has them if a B6 build is
    // ever needed again, and reports/final/ retains the B6 measurements themselves.
    val candidates: Map[String, CoreConfig] = Map.empty

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
