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
    plClkMhz:     Int = 100,      // PL fabric clock the bitstream targets (deployment
                                  // policy, not Chisel hardware — emitted into the
                                  // preset manifest, consumed by build_all.sh / bench).
    shapesHint:   String = "",    // default bench sweep for this preset (MMFREE_SHAPES);
                                  // "" → auto (pow2, or "370m" once maxM spans the LM head).
                                  // Model presets set this so `make bench` defaults to the
                                  // right projection sweep without an explicit env.
    residentWtBytes: Long = 0L    // total packed ternary weight bytes the resident
                                  // full-model runner (fpga_runner gate/run) holds across
                                  // ALL ports. 0 → bench-only preset (no resident set), so
                                  // UDMABUF_WT_SZ stays the single-slice default. forModel
                                  // sets this from the model shapes so the emitted udmabuf
                                  // fits the runner's working set, not just one weight slice.
) {
    require(aWidth >= 2 && aWidth % 2 == 0, s"aWidth=$aWidth must be even and >= 2")
    require(maxAcc >= 2,                    s"maxAcc=$maxAcc must be >= 2")
    require(xDim >= 1 && batchSize >= 1,    s"xDim=$xDim, batchSize=$batchSize must be >= 1")
    require(maxN >= 1 && maxM >= 1,         s"maxN=$maxN, maxM=$maxM must be >= 1")
    require(plClkMhz >= 1,                  s"plClkMhz=$plClkMhz must be >= 1")
    require(maxN <= maxAcc,
        s"Core requires maxN ($maxN) <= maxAcc ($maxAcc) — no inner-dim tiling yet")

    val yDim:            Int = batchSize
    val nLanes:          Int = aWidth / 2
    val outLanesPerTile: Int = xDim * nLanes
    val tileAccWidth:    Int = log2Ceil(maxAcc) + aWidth
    val numColTilesMax:  Int = (maxM + outLanesPerTile - 1) / outLanesPerTile
    val outAccWidth:     Int = tileAccWidth
    val outLaneWidth:    Int = 1 << log2Ceil(outAccWidth)

    val effOutBeatLanes: Int = if (outBeatLanes == 0) outLanesPerTile else outBeatLanes
    val outSubBeats:     Int = outLanesPerTile / effOutBeatLanes

    val axisDataWidth: Int = math.max(xDim, batchSize) * aWidth
    val outBeatWidth:  Int = effOutBeatLanes * outLaneWidth

    // Input-stream port split: one PS HP port carries at most 128 bits, so wider
    // streams are split across N parallel s_axis ports (joined inside CoreTop).
    val numInPorts:  Int = if (axisDataWidth <= 128) 1 else math.min(4, (axisDataWidth + 127) / 128)
    val inPortWidth: Int = axisDataWidth / numInPorts
    val portBytes:   Int = inPortWidth / 8           // s_axis bytes per HP port per beat
    val outLaneBytes:Int = outLaneWidth / 8          // bytes per m_axis output lane

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
    val udmabufWtSliceBytes:    Long = pageUp(maxN.toLong * numColTilesMax * portBytes)
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
        axiAddrWidth, axiDataWidth, axiIdWidth, signedAct
    )

    def buildTop: CoreTop = new CoreTop(
        aWidth, maxAcc, xDim, batchSize, maxN, maxM, outBeatLanes,
        axiAddrWidth, axiDataWidth, axiIdWidth, signedAct
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
           |# --- derived geometry ---
           |MMFREE_NLANES=$nLanes
           |MMFREE_OUT_LANES_PER_TILE=$outLanesPerTile
           |MMFREE_ACC_WIDTH=$outAccWidth
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
           |# --- u-dma-buf node sizes ---
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
           |  "nLanes": $nLanes,
           |  "outLanesPerTile": $outLanesPerTile,
           |  "accWidth": $outAccWidth,
           |  "outLaneWidth": $outLaneWidth,
           |  "outLaneBytes": $outLaneBytes,
           |  "outBeatLanes": $effOutBeatLanes,
           |  "numColTilesMax": $numColTilesMax,
           |  "axisDataWidth": $axisDataWidth,
           |  "numPorts": $numInPorts,
           |  "mm2sWidth": $inPortWidth,
           |  "portBytes": $portBytes,
           |  "s2mmWidth": $outBeatWidth,
           |  "plClkMhz": $plClkMhz,
           |  "udmabufActBytes": $udmabufActBytes,
           |  "udmabufWtBytes": $udmabufWtBytes,
           |  "udmabufOutBytes": $udmabufOutBytes
           |}
           |""".stripMargin
    }
}

object CoreConfig {
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
                 plClkMhz = 250, shapes = "370m")

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
                 plClkMhz = 250, shapes = "370m")

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
        forModel(hidden = 2048, intermediate = 5632, vocab = 32000, layers = 24, shapes = "1.3b")
    val K26_MMFree2_7B_A16: CoreConfig =
        forModel(hidden = 2560, intermediate = 6912, vocab = 32000, layers = 32, shapes = "2.7b")

    // Batched siblings (outBeatLanes=4 → 128-bit m_axis, as in the 370M batch
    // presets). B6 is the 370M K26 sweet spot; at maxN=8192 the actBram/accum
    // banks are ~2x deeper, so the batch ceiling may land lower — confirm OOC
    // resource/timing on the board before trusting B6 at this scale.
    // B6 missed 250 MHz timing at maxN=8192 (WNS −0.369 ns; the deeper actBram/URAM
    // cascade + 77% LUT congestion lengthens the store path past the 370M B6 that
    // closed). B4 is the fallback — less array logic, shorter cascades.
    val K26_MMFree1_3B_A16_B4: CoreConfig = K26_MMFree1_3B_A16.copy(batchSize = 4, outBeatLanes = 4)
    val K26_MMFree2_7B_A16_B4: CoreConfig = K26_MMFree2_7B_A16.copy(batchSize = 4, outBeatLanes = 4)
    val K26_MMFree1_3B_A16_B6: CoreConfig = K26_MMFree1_3B_A16.copy(batchSize = 6, outBeatLanes = 4)
    val K26_MMFree2_7B_A16_B6: CoreConfig = K26_MMFree2_7B_A16.copy(batchSize = 6, outBeatLanes = 4)
    // B6 can't close 250 MHz (route-bound arm-RAM→accumulator broadcast, ~−0.37 ns,
    // unmoved by the impl levers). fmax ≈ 228 MHz, so this 230 MHz variant is the
    // throughput-max B6: 6 tokens/pass × 230 still beats B4×250 by ~38%. The
    // manifest carries 230 so the bench bandwidth peak + PS PL0 clock agree.
    val K26_MMFree1_3B_A16_B6_230: CoreConfig = K26_MMFree1_3B_A16_B6.copy(plClkMhz = 230)

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
        "k26_mmfree370m_a16_b8" -> K26_MMFree370M_A16_B8,
        "k26_mmfree1_3b_a16" -> K26_MMFree1_3B_A16,
        "k26_mmfree1_3b_a16_b4" -> K26_MMFree1_3B_A16_B4,
        "k26_mmfree1_3b_a16_b6" -> K26_MMFree1_3B_A16_B6,
        "k26_mmfree1_3b_a16_b6_230" -> K26_MMFree1_3B_A16_B6_230,
        "k26_mmfree2_7b_a16" -> K26_MMFree2_7B_A16,
        "k26_mmfree2_7b_a16_b4" -> K26_MMFree2_7B_A16_B4,
        "k26_mmfree2_7b_a16_b6" -> K26_MMFree2_7B_A16_B6,
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
        shapes:       String  = ""
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
        val totalWtBytes    = totalWtElems * 2L / 8L
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
            residentWtBytes = totalWtBytes
        )
    }

    private def ceilTo(x: Int, m: Int): Int = ((x + m - 1) / m) * m
    private def nextPow2(x: Int): Int = {
        var p = 1
        while (p < x) p *= 2
        p
    }
}
