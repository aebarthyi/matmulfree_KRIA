package core

/**
  * Architecture of an MMfreeLM-style checkpoint: what a model preset is sized from.
  *
  * The per-token BitLinear projections (N inner x M out) are:
  *   i/f/g/o_proj : hidden x hidden         (4 per layer)
  *   gate_up      : hidden x 2*intermediate (fused gate+up, 1 per layer)
  *   down_proj    : intermediate x hidden   (1 per layer)
  *   lm_head      : hidden x vocab          (once)
  *
  * @param hfId the HF checkpoint id (manifest `MMFREE_MODEL`), e.g. "ridger/MMfreeLM-2.7B"
  * @param slug the default bench sweep (manifest `MMFREE_SHAPES`), e.g. "2.7b"
  */
case class ModelArch(
    hfId:         String,
    slug:         String,
    hidden:       Int,
    intermediate: Int,
    vocab:        Int,
    layers:       Int
) {
    require(hidden > 0 && intermediate > 0 && vocab > 0 && layers > 0,
        s"model dims must be positive (hidden=$hidden inter=$intermediate vocab=$vocab layers=$layers)")

    /** One layer's projections, as (inner N, out M). */
    def layerProjections: Seq[(Int, Int)] =
        Seq.fill(4)((hidden, hidden)) :+ ((hidden, 2 * intermediate)) :+ ((intermediate, hidden))

    /** Every projection one decoded token streams: all layers, then lm_head. */
    def projections: Seq[(Int, Int)] =
        Seq.fill(layers)(layerProjections).flatten :+ ((hidden, vocab))

    /** Largest inner dim (N) any projection needs. */
    def maxInnerDim: Int = math.max(hidden, intermediate)

    /** Largest output dim (M) any projection needs. */
    def maxOutDim: Int = math.max(2 * intermediate, vocab)

    /** Ternary weight elements streamed per token. */
    def weightElems: Long = projections.map { case (n, m) => n.toLong * m }.sum
}

object ModelArch {
    val MMfreeLM_370M: ModelArch = ModelArch("ridger/MMfreeLM-370M", "370m",
        hidden = 1024, intermediate = 2816, vocab = 32000, layers = 24)

    val MMfreeLM_1_3B: ModelArch = ModelArch("ridger/MMfreeLM-1.3B", "1.3b",
        hidden = 2048, intermediate = 5632, vocab = 32000, layers = 24)

    val MMfreeLM_2_7B: ModelArch = ModelArch("ridger/MMfreeLM-2.7B", "2.7b",
        hidden = 2560, intermediate = 6912, vocab = 32000, layers = 32)
}
