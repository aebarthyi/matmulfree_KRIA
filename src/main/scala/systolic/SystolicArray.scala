package systolic

import chisel3._
import chisel3.util._

class SystolicArrayInputBundle(val aWidth: Int, val maxAcc: Int, val xDim: Int, val yDim: Int) extends Bundle {
    // One weight beat per cycle: xDim columns × nLanes lanes of ternary weights.
    val weights_i    = Flipped(Decoupled(Vec((aWidth / 2) * xDim, UInt(2.W))))
    // One activation beat per cycle: yDim activations (one per row / batch item).
    val activation_i = Flipped(Decoupled(Vec(yDim, UInt(aWidth.W))))
    // One-shot rendezvous: number of accumulation cycles for this operation.
    val nAcc         = Flipped(Decoupled(UInt(log2Ceil(maxAcc).W)))
}

class SystolicArrayOutputBundle(val aWidth: Int, val xDim: Int, val yDim: Int, val accWidth: Int) extends Bundle {
    // Per row, one accumulator per (col, lane). All yDim rows are exposed.
    val accum = Vec(yDim, Vec((aWidth / 2) * xDim, UInt(accWidth.W)))
}

/**
  * 2D systolic array of ternary-MAC PEs.
  *
  *   - yDim rows, each row holding one streaming activation per cycle. The row
  *     index doubles as the batch dimension: row k accumulates a separate output
  *     for batch item k.
  *   - xDim columns; per cycle, the array consumes one full weight beat
  *     (xDim·nLanes ternary weights). For each (row, col, lane), PE(row, col)'s
  *     lane-l accumulator computes
  *         sum_{t=0..nAcc-1} sign(weight_t[col][lane]) * activation_t[row]
  *     which is exactly the batched matrix-vector product
  *         O[row][col·nLanes + lane] = sum_t W[t][col·nLanes + lane] * A[row][t]
  *
  *   - Both `weights_i` and `activation_i` use a Decoupled handshake; in
  *     `running_state` they must both be valid each cycle for the array to step.
  *     `nAcc` is consumed once during the initial three-way rendezvous in idle.
  *
  * ── Column banking (`colBankSize`) ──────────────────────────────────────────
  * The activation broadcasts to every column each cycle: act[row] drives xDim
  * columns × nLanes PE inputs (≈256 loads at xDim=32, nLanes=8) — the route-bound,
  * fanout-heavy net that dominates the 250 MHz critical path. `colBankSize` splits
  * the columns into banks of that many
  * columns and inserts ONE pipeline register between banks: bank `b` sees the
  * whole input beat (weights + activation + valid + nAcc) delayed by `b` cycles.
  *   - Within a bank the beat is still broadcast, so the activation fanout drops
  *     from xDim to colBankSize columns (e.g. 32→8 = 4×).
  *   - Delaying the WHOLE beat (not just the activation) keeps each bank's weights
  *     aligned with the activation that reached it, so the accumulated result is
  *     numerically IDENTICAL to the unbanked array — only retimed.
  *   - Because every PE is an independent accumulator that HOLDS its result in
  *     done_state, no output de-skew buffer is needed: bank b finishes at
  *     nAcc+b, earlier banks simply hold, and the array raises `output.valid`
  *     when the DEEPEST bank finishes. Cost: +(numBanks-1) cycles of latency per
  *     op (negligible vs. nAcc) and a small per-bank beat-wide pipeline register.
  *   - colBankSize <= 0 or >= xDim ⇒ a single bank = the original broadcast array
  *     (no pipeline, bit-identical), which is the default so existing presets are
  *     unchanged.
  *
  *   - Back-to-back: PE.scala leaves accumCounter/accumReg dirty on done→idle,
  *     so callers must hard-reset between successive operations.
  */
class SystolicArray(val aWidth: Int, val maxAcc: Int, val xDim: Int, val yDim: Int,
                    val signedAct: Boolean = false,
                    val useDspAccum: Boolean = false, val colBankSize: Int = 0) extends Module {
    require(aWidth >= 2 && aWidth % 2 == 0, "aWidth must be even and >= 2")
    require(maxAcc >= 2, "maxAcc must be >= 2")
    require(xDim >= 1, "xDim must be >= 1")
    require(yDim >= 1, "yDim must be >= 1")
    require(colBankSize >= 0, s"colBankSize=$colBankSize must be >= 0 (0 = no banking)")

    // Accumulator width = aWidth + headroom (see PE.scala). Auto (-1) = legacy log2Ceil(maxAcc).
    val accWidth = aWidth + log2Ceil(maxAcc)

    val input  = IO(new SystolicArrayInputBundle(aWidth, maxAcc, xDim, yDim))
    val output = IO(Decoupled(new SystolicArrayOutputBundle(aWidth, xDim, yDim, accWidth)))

    val nLanes = aWidth / 2

    // Column banking: number of banks and each column's bank index (= its pipeline
    // delay). A single bank (default) reproduces the original broadcast array.
    val numBanks: Int = if (colBankSize <= 0 || colBankSize >= xDim) 1
                        else (xDim + colBankSize - 1) / colBankSize
    def bankOf(col: Int): Int = if (numBanks == 1) 0 else math.min(col / colBankSize, numBanks - 1)
    // Deepest bank finishes last; any of its PEs signals array completion. Column
    // xDim-1 is always in the deepest bank; keep col 0 for the unbanked case so the
    // emitted SV (and thus existing bitstreams) is unchanged.
    private val doneCol = if (numBanks == 1) 0 else xDim - 1

    val arr = Seq.tabulate(yDim, xDim) { (_, _) => Module(new PE(aWidth, maxAcc, signedAct, useDspAccum)) }

    val nAccReg = Reg(UInt(log2Ceil(maxAcc).W))

    // idle → running (accepting input) → draining (deep banks finishing, NO new
    // input) → done. draining is entered only when banked (numBanks>1); the
    // unbanked array goes running→done directly on bank 0's completion, exactly as
    // before. The split matters because the input side (bank 0) finishes
    // numBanks-1 cycles before the deepest bank: if the array kept accepting during
    // that window, a streaming caller (Core) would feed the NEXT op's beats into
    // the drain and lose them. running gates input-accept; done gates completion.
    //
    // Unbanked keeps a 3-state enum (no draining) so its FSM encoding — and hence
    // existing bitstreams — is byte-identical to before.
    val idle_state :: running_state :: fsmRest = Enum(if (numBanks == 1) 3 else 4)
    val done_state      = fsmRest.last
    val draining_state  = if (numBanks == 1) done_state else fsmRest.head
    val stateReg = RegInit(idle_state)

    // Repack flat external weights as [xDim][nLanes].
    val externalWeightsByCol = VecInit(Seq.tabulate(xDim)(col =>
        VecInit(Seq.tabulate(nLanes)(lane =>
            input.weights_i.bits(col * nLanes + lane)
        ))
    ))

    val startable  = input.weights_i.valid && input.activation_i.valid && input.nAcc.valid
    val isLatching = (stateReg === idle_state) && startable
    val canStep    = input.weights_i.valid && input.activation_i.valid

    // Bank 0 (col 0) is the input consumer: the moment it finishes it has swallowed
    // all nAcc beats, so the array must STOP accepting that same cycle — otherwise
    // (banked only) it eats one more beat before the registered running→draining
    // transition lands, stealing the next op's first beat from a streaming caller.
    // inputDone is combinational (bank 0's PE output.valid). Unbanked keeps the
    // plain `running` gate so its SV is byte-identical (there Core's !outPending
    // already suppresses the extra beat, since outPending == bank-0-done).
    val inputDone     = arr(0)(0).output.valid
    val stillAccepting = if (numBanks == 1) (stateReg === running_state)
                         else (stateReg === running_state) && !inputDone

    // External handshakes. In running, weights and activations gate each other so
    // both fire together per cycle. nAcc rendezvous fires only at start. Banking
    // does not change the boundary handshake — only the internal propagation.
    input.nAcc.ready         := isLatching
    input.activation_i.ready := isLatching ||
                                (stillAccepting && input.weights_i.valid)
    input.weights_i.ready    := isLatching ||
                                (stillAccepting && input.activation_i.valid)

    // ── Per-bank input pipeline ──────────────────────────────────────────────
    // Delay index d = bank index. d==0 is the live boundary beat (bank 0); d>=1 is
    // a free-running register of d-1, so a beat injected at cycle t reaches bank d
    // at t+d. valid resets to false (via the array's reset, incl. arrLocalReset)
    // so the pipeline flushes between tiles; the wide data regs need no reset (the
    // valid bit gates each PE's accumulation).
    val stage0Valid   = isLatching || (stillAccepting && canStep)
    val stage0NAcc    = Mux(isLatching, input.nAcc.bits, nAccReg)

    val validPipe   = Wire(Vec(numBanks, Bool()))
    val nAccPipe    = Wire(Vec(numBanks, UInt(log2Ceil(maxAcc).W)))
    val weightsPipe = Wire(Vec(numBanks, Vec(xDim, Vec(nLanes, UInt(2.W)))))
    val actPipe     = Wire(Vec(numBanks, Vec(yDim, UInt(aWidth.W))))

    validPipe(0)   := stage0Valid
    nAccPipe(0)    := stage0NAcc
    weightsPipe(0) := externalWeightsByCol
    actPipe(0)     := input.activation_i.bits
    for (d <- 1 until numBanks) {
        validPipe(d)   := RegNext(validPipe(d - 1), false.B)
        nAccPipe(d)    := RegNext(nAccPipe(d - 1))
        weightsPipe(d) := RegNext(weightsPipe(d - 1))
        actPipe(d)     := RegNext(actPipe(d - 1))
    }

    // PE wiring: each PE reads its bank's (delayed) slice of the beat.
    for (row <- 0 until yDim; col <- 0 until xDim) {
        val pe = arr(row)(col)
        val d  = bankOf(col)
        pe.input.bits.weights_i    := weightsPipe(d)(col)
        pe.input.bits.activation_i := actPipe(d)(row)
        pe.input.bits.nAcc         := nAccPipe(d)
        pe.input.valid             := validPipe(d)
        pe.output.ready            := output.ready
    }

    // All yDim rows exposed at the boundary.
    for (row <- 0 until yDim; col <- 0 until xDim; lane <- 0 until nLanes) {
        output.bits.accum(row)(col * nLanes + lane) := arr(row)(col).output.bits.accum(lane)
    }
    // Banks run in lockstep internally; the deepest bank finishes last, so it
    // gates completion. Earlier banks sit in done_state holding their results.
    // The deepest bank finishes last, so it gates completion. (inputDone, bank 0's
    // completion, is defined above where it also gates the input-accept phase.)
    val allDone   = arr(0)(doneCol).output.valid
    output.valid := allDone

    switch(stateReg) {
        is(idle_state) {
            when(startable) {
                nAccReg  := input.nAcc.bits
                stateReg := running_state
            }
        }
        is(running_state) {
            // Bank 0 done ⇒ all input consumed. Unbanked: bank 0 IS the deepest,
            // so go straight to done (byte-identical to the original FSM). Banked:
            // stop accepting input and let the deeper banks drain.
            when(inputDone) {
                stateReg := (if (numBanks == 1) done_state else draining_state)
            }
        }
        is(done_state) {
            when(output.ready) {
                stateReg := idle_state
            }
        }
    }
    // draining → done once the deepest bank finishes. Kept out of the switch (and
    // absent entirely when unbanked) so the 3-state FSM stays byte-identical; the
    // switch simply holds stateReg in draining until this fires.
    if (numBanks > 1) {
        when(stateReg === draining_state && allDone) {
            stateReg := done_state
        }
    }
}
