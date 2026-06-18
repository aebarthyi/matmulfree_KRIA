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
    signedAct:    Boolean = false // sign-extend activations (real BitLinear / HGRN int16)
) {
    require(aWidth >= 2 && aWidth % 2 == 0, s"aWidth=$aWidth must be even and >= 2")
    require(maxAcc >= 2,                    s"maxAcc=$maxAcc must be >= 2")
    require(xDim >= 1 && batchSize >= 1,    s"xDim=$xDim, batchSize=$batchSize must be >= 1")
    require(maxN >= 1 && maxM >= 1,         s"maxN=$maxN, maxM=$maxM must be >= 1")
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
    val K26_MMFree370M: CoreConfig = CoreConfig(
        aWidth       = 8,
        maxAcc       = 4096,
        xDim         = 64,
        batchSize    = 1,
        maxN         = 4096,
        maxM         = 32000,
        outBeatLanes = 1
    )

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
    val K26_MMFree370M_A16: CoreConfig = CoreConfig(
        aWidth       = 16,
        maxAcc       = 4096,
        xDim         = 32,
        batchSize    = 1,
        maxN         = 4096,
        maxM         = 32000,
        outBeatLanes = 1,
        signedAct    = true
    )

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

    private def ceilTo(x: Int, m: Int): Int = ((x + m - 1) / m) * m
    private def nextPow2(x: Int): Int = {
        var p = 1
        while (p < x) p *= 2
        p
    }
}
