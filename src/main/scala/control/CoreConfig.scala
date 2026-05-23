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
    axiIdWidth:   Int = 6
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

    require(maxM % outLanesPerTile == 0,
        s"maxM=$maxM must be a multiple of outLanesPerTile=$outLanesPerTile (= xDim*nLanes)")
    require(effOutBeatLanes >= 1 && outLanesPerTile % effOutBeatLanes == 0,
        s"outBeatLanes=$outBeatLanes must divide outLanesPerTile=$outLanesPerTile")

    def maxMatmulShape: (Int, Int) = (maxN, maxM)

    def buildCore: Core = new Core(
        aWidth, maxAcc, xDim, batchSize, maxN, maxM, outBeatLanes,
        axiAddrWidth, axiDataWidth, axiIdWidth
    )

    def buildTop: CoreTop = new CoreTop(
        aWidth, maxAcc, xDim, batchSize, maxN, maxM, outBeatLanes,
        axiAddrWidth, axiDataWidth, axiIdWidth
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
           |  m_axis width   : ${outBeatWidth} bits (${effOutBeatLanes} lanes x ${outLaneWidth} bits, ${outSubBeats} sub-beat${if (outSubBeats == 1) "" else "s"}/col-tile)
           |  activation BRAM: ${maxN} entries x ${batchSize * aWidth}b = ${actBramBits / 8} bytes
           |  output BRAM    : ${batchSize * numColTilesMax} entries x ${outLanesPerTile * outAccWidth}b = ${outBramBits / 8} bytes
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
