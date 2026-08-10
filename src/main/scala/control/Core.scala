package control

import chisel3._
import chisel3.util._
import chisel3.experimental.annotate
import firrtl.AttributeAnnotation
import systolic.{SystolicArray, TrueSystolicArray}
import systolic.WeightUnpackGearbox

/** AXI4-Stream slave (input side). Flat bundle so Vivado IP packager infers `s_axis_*`. */
class AxisSlaveIO(val dataWidth: Int) extends Bundle {
    val tdata  = Input(UInt(dataWidth.W))
    val tvalid = Input(Bool())
    val tready = Output(Bool())
    val tlast  = Input(Bool())
}

/** AXI4-Stream master (output side). Names map to `m_axis_*`. */
class AxisMasterIO(val dataWidth: Int) extends Bundle {
    val tdata  = Output(UInt(dataWidth.W))
    val tvalid = Output(Bool())
    val tready = Input(Bool())
    val tlast  = Output(Bool())
}

/**
  * Top-level ternary matmul Core.
  *
  *   - `s_axi`   : AXI4 slave for instruction MMIO (→ [[AxiInstructionHandler]])
  *   - `s_axis`  : AXI4-S slave; carries activations (LOAD_ACT) or weights (COMPUTE_MM)
  *   - `m_axis`  : AXI4-S master; carries outputs (STORE_OUT). Chunked into beats
  *                 of `outBeatLanes` lanes each so the data width can be sized to a
  *                 standard AXI-S value (64/128/256/512/1024) independent of the
  *                 systolic array's natural per-tile output width.
  *   - `irq`     : level-high interrupt on instruction completion.
  *
  * **Compute model.** The SystolicArray streams both weights (broadcast across all
  * yDim batch rows, no delay chain) and activations (one per cycle, one per batch
  * row) through PEs that accumulate `sum_n W[n][col] * A[batch][n]`. yDim is the
  * batch dimension; xDim is the output-cols-per-pass dimension. Per COMPUTE_MM:
  *
  *   - rows = N = inner dim (≤ maxN ≤ maxAcc — no inner-dim tiling yet).
  *   - cols = M, tiled in chunks of `outLanesPerTile = xDim * nLanes`.
  *   - Per col tile: stream activations from BRAM + weights from s_axis for N
  *     cycles, then drain the captured tile into outBram one beat-wide row per
  *     cycle, overlapped with the next tile's stream (see drain engine below).
  *
  * **Storage.**
  *   - `actBram`: depth = maxN, width = batchSize · aWidth. Each entry holds the
  *      n-th inner-dim slice across all batch items.
  *   - `outBram`: depth = batchSize · numColTilesMax · outSubBeats, width =
  *      effOutBeatLanes · outAccWidth — exactly one m_axis beat per row, so the
  *      store path reads a row and ships it with no lane mux (the 256:1 dynamic
  *      lane mux on the old wide-row layout was the critical path at 250 MHz).
  *      Row address = (batchIdx · numColTiles + colTileIdx) · outSubBeats +
  *      subBeatIdx.
  *
  * **PE counter quirk.** PE.scala leaves accumCounter/accumReg dirty on done→idle;
  * Core pulses a local reset to the array between col-tile passes (see [[CoreTop]]).
  *
  * Requires `maxN <= maxAcc` (one array pass covers the full inner dim).
  */
class Core(
    val aWidth:       Int = 8,
    val maxAcc:       Int = 4096,
    val xDim:         Int = 8,
    val batchSize:    Int = 1,    // yDim = batchSize; 1 PE row per batch item
    val maxN:         Int = 4096, // max inner dim (activation length per batch)
    val maxM:         Int = 1024, // max output cols
    val outBeatLanes: Int = 0,    // 0 → auto (= outLanesPerTile, no chunking)
    val axiAddrWidth: Int = 32,
    val axiDataWidth: Int = 128,
    val axiIdWidth:   Int = 6,
    val signedAct:    Boolean = false, // sign-extend activations (real BitLinear / HGRN int16)
    val accHeadroomBits: Int = -1,     // accumulator overflow headroom; -1 = auto (log2Ceil(maxAcc))
    val actMaxFanout:    Int = 0,      // max_fanout attr on the batched array-feed register
                                       // (0 = off, exact legacy Queue). Forces synth to
                                       // replicate the activation-broadcast reg near each
                                       // column — the route-bound 250 MHz path at B>=6.
    val dspAccum:        Boolean = false, // map the per-lane PE accumulators onto DSP48E2
                                         // slices (use_dsp attr) instead of LUT/CARRY8.
    val colBankSize:     Int = 0,        // systolic column banking: broadcast within a bank
                                         // of this many columns, pipeline between banks to
                                         // cut activation fanout. 0 = no banking (broadcast).
    val packedWeights:   Boolean = false, // stream ternary weights at 1.6 bits/trit (5 trits/
                                         // byte) and unpack them on-chip through a regroup
                                         // gearbox (WeightUnpackGearbox) into the native 2-bit
                                         // weight beat. ~20% fewer weight bytes on the HP-port
                                         // stream — fewer, still-full wire beats, so no port/
                                         // geometry change. false = legacy 2-bit slice (SV
                                         // byte-identical). The DDR blob must be packed to
                                         // match (base-3, LSB-first lanes); see TernaryPack.
    val actRamStyle:     String = "",    // "" = inferred SyncReadMem for actBram; "block"/
                                         // "ultra" = the control.ActMem BlackBox carrying
                                         // ram_style + cascade_height. Identical read
                                         // semantics and latency — a mapping choice, not a
                                         // design change. See ActMem for why maxN=8192 needs it.
    val actCascadeHeight: Int = 0,       // cascade_height for ActMem; 0 = omit the attribute.
    val outPipeStyle: String = "legacy", // OutMemUltra read-pipeline coding style. "legacy" =
                                         // the shipped body (separate read/write always blocks,
                                         // shreg_extract="no" on the stages), which Vivado maps
                                         // with "FF provided for Pipeline: 0" — i.e. NONE of its
                                         // read stages reach the URAM cascade, leaving a fully
                                         // combinational CAS chain that is the worst 300 MHz
                                         // path. "absorb" = the UG901 template (one always
                                         // block, explicit output stage, no shreg_extract) so
                                         // the stages can be absorbed. Identical read semantics
                                         // and latency either way; changing it changes
                                         // OutMemUltra's module name, so shipped presets keep
                                         // "legacy" to stay SV-identical.
    val outBankDepth: Int = 0,           // rows per explicit bank when outPipeStyle="banked".
                                         // 0 = auto = 4096*outCascadeHeight (one URAM cascade
                                         // chain per bank). Overridable so a simulation-sized
                                         // memory can still be forced into several banks — at
                                         // the default every test config is one bank and the
                                         // registered mux, the whole point of the style, is
                                         // never exercised.
    val outCascadeHeight: Int = 4,       // cascade_height for the OutMemUltra URAM output memory.
                                         // The read side is a hard URAM288 CAS chain of this depth
                                         // plus a fabric mux over the chain groups: shorter chain =
                                         // less logic delay, more LUT and route. 4 is the shipped
                                         // value and closes 250; MEASURED 2026-08-08, it is the
                                         // path that fails 300 MHz (-0.315 ns post-synth, 82%
                                         // LOGIC), identically for every array dataflow. Changing
                                         // it changes OutMemUltra's module name, so existing
                                         // presets must keep 4 to stay SV-identical.
    val trueSystolic:    Boolean = false // swap the broadcast MAC array for the nearest-
                                         // neighbour systolic.TrueSystolicArray: activations
                                         // march right and weights march down through one
                                         // register per PE, so no data net in the array has
                                         // fanout > 1. Numerically identical (same output-
                                         // stationary accumulation, drop-in IO); costs
                                         // +(xDim-1)+(yDim-1) cycles of drain per op instead
                                         // of colBankSize's +(numBanks-1). Mutually exclusive
                                         // with colBankSize, which is the same problem solved
                                         // from outside the array.
    ,
    val captureMaxFanout: Int = 0        // 0 = off (the shipped RTL, byte-identical SV). >0 =
                                         // pipeline the tile capture one cycle through a dedicated
                                         // cCapture state so its enable is a plain REGISTER, and
                                         // annotate that register and drainBusy — the two CE
                                         // drivers of the 59 392-bit tileAccums bank — with
                                         // max_fanout = N so synthesis replicates each driver near
                                         // its own slice. MEASURED 2026-08-08: this one enable net
                                         // is 74.2% of everything that fails 300 MHz. Numerically
                                         // identical (the array holds its accumulators until
                                         // `ready`, so reading them a cycle later reads the same
                                         // bits); costs +1 cycle per column tile.
) extends Module {
    require(aWidth >= 2 && aWidth % 2 == 0,        "aWidth must be even and >= 2")
    require(maxAcc >= 2,                           "maxAcc must be >= 2")
    require(xDim >= 1 && batchSize >= 1,           "xDim and batchSize must be >= 1")
    require(maxN <= maxAcc,                        s"maxN=$maxN must be <= maxAcc=$maxAcc (no inner-dim tiling yet)")
    require(maxN >= 1,                             "maxN must be >= 1")
    require(maxM >= 1,                             "maxM must be >= 1")
    require(accHeadroomBits == -1 || accHeadroomBits >= 0,
        s"accHeadroomBits=$accHeadroomBits must be -1 (auto) or >= 0")
    require(!(trueSystolic && colBankSize > 0),
        s"trueSystolic and colBankSize=$colBankSize both attack the activation-broadcast " +
        "fanout and are mutually exclusive: banking pipelines the broadcast from outside " +
        "the array, the systolic dataflow removes it from inside. Pick one.")

    val yDim            = batchSize
    val nLanes          = aWidth / 2
    val outLanesPerTile = xDim * nLanes
    // Accumulator data width = aWidth + headroom. Auto (-1) = legacy log2Ceil(maxAcc)
    // headroom (worst-case growth); a smaller headroom trims every accumulator flop,
    // the drain shift register (tileAccums) and outBram — see accHeadroomBits.
    val accHeadroom     = if (accHeadroomBits < 0) log2Ceil(maxAcc) else accHeadroomBits
    val tileAccWidth    = aWidth + accHeadroom
    val numColTilesMax  = (maxM + outLanesPerTile - 1) / outLanesPerTile
    val outAccWidth     = tileAccWidth
    val outLaneWidth    = 1 << log2Ceil(outAccWidth)

    require(maxM % outLanesPerTile == 0,
        s"maxM=$maxM must be a multiple of outLanesPerTile=$outLanesPerTile (= xDim*nLanes)")

    // Output chunking. outBeatLanes=0 → no chunking (one beat per col tile).
    val effOutBeatLanes = if (outBeatLanes == 0) outLanesPerTile else outBeatLanes
    require(effOutBeatLanes >= 1 && outLanesPerTile % effOutBeatLanes == 0,
        s"outBeatLanes=$outBeatLanes must divide outLanesPerTile=$outLanesPerTile")
    val outSubBeats = outLanesPerTile / effOutBeatLanes

    val axisDataWidth = math.max(xDim, batchSize) * aWidth
    val outBeatWidth  = effOutBeatLanes * outLaneWidth

    val outBramDepth = batchSize * numColTilesMax
    val outBramRows  = outBramDepth * outSubBeats

    // ─── IO ───────────────────────────────────────────────────────────────────
    val s_axi  = IO(new AxiSlaveIO(axiAddrWidth, axiDataWidth, axiIdWidth))
    val s_axis = IO(new AxisSlaveIO(axisDataWidth))
    val m_axis = IO(new AxisMasterIO(outBeatWidth))
    val irq    = IO(Output(Bool()))

    // ─── Instruction handler ──────────────────────────────────────────────────
    val handler = Module(new AxiInstructionHandler(axiAddrWidth, axiDataWidth, axiIdWidth))
    Core.connectAxiSlave(outer = s_axi, inner = handler.s_axi)
    irq := handler.irq

    // ─── Systolic array with Core-local reset (PE counter quirk workaround) ──
    val arrLocalReset = WireDefault(false.B)
    // Two dataflows behind one IO. They share the bundle types but deliberately have no
    // common supertype: SystolicArray is NOT touched, because the shipped bitstreams'
    // RTL must stay byte-identical for scripts/clone_preset_hw.sh to accept a clone.
    // suggestName("arr") keeps the instance name (and hence the emitted SV) unchanged
    // for every existing preset.
    private val arrReset = this.reset.asBool || arrLocalReset
    val arrBroadcast =
        if (trueSystolic) None
        else Some(withReset(arrReset) {
            Module(new SystolicArray(aWidth, maxAcc, xDim, yDim, signedAct, accHeadroomBits,
                                     dspAccum, colBankSize)).suggestName("arr")
        })
    val arrSystolic =
        if (!trueSystolic) None
        else Some(withReset(arrReset) {
            Module(new TrueSystolicArray(aWidth, maxAcc, xDim, yDim, signedAct, accHeadroomBits,
                                         dspAccum)).suggestName("arr")
        })
    val arrInput  = arrBroadcast.map(_.input).getOrElse(arrSystolic.get.input)
    val arrOutput = arrBroadcast.map(_.output).getOrElse(arrSystolic.get.output)

    // ─── On-chip storage ──────────────────────────────────────────────────────
    // actBram: maxN entries x (batchSize * aWidth) bits, 1-cycle synchronous read.
    // Two interchangeable implementations, selected by actRamStyle:
    //   ""              → SyncReadMem, letting Vivado infer the mapping (the legacy path;
    //                     every preset before 2026-08-04 uses this).
    //   "block"/"ultra" → control.ActMem, a BlackBox carrying ram_style + cascade_height.
    // Same cycle behaviour either way; the BlackBox exists only to bound the RAMB36E2 depth
    // cascade, which at maxN=8192 is 3.2 ns of logic and the worst 250 MHz path (see ActMem).
    val actRdAddr = Wire(UInt(log2Ceil(maxN).W))
    actRdAddr := 0.U
    val actBram =
        if (actRamStyle.isEmpty) Some(SyncReadMem(maxN, Vec(batchSize, UInt(aWidth.W))).suggestName("actBram"))
        else None
    val actMem = if (actRamStyle.isEmpty) None else Some {
        val m = Module(new ActMem(maxN, batchSize * aWidth, actRamStyle, actCascadeHeight))
        m.suggestName("actMem")
        m.io.clk   := clock
        m.io.raddr := actRdAddr
        m.io.wen   := false.B          // overridden in sLoad
        m.io.waddr := 0.U
        m.io.wdata := 0.U
        m
    }
    val actRdData: Vec[UInt] = actBram
        .map(_.read(actRdAddr))
        .getOrElse(actMem.get.io.rdata.asTypeOf(Vec(batchSize, UInt(aWidth.W))))

    // Output-memory port wires. The implementation (BRAM SyncReadMem vs URAM
    // BlackBox) is selected by `outRamStyle` in the STORE section below; both
    // present `outRdData` delayed by `outReadLat`, advanced/gated by `outRdEn`.
    // The drain engine drives the write port.
    val outRdAddr = Wire(UInt(log2Ceil(outBramRows).W)); outRdAddr := 0.U
    val outRdEn   = WireDefault(false.B)
    val outWrEn   = WireDefault(false.B)
    val outWrAddr = WireDefault(0.U(log2Ceil(outBramRows).W))
    val outWrData = Wire(Vec(effOutBeatLanes, SInt(outAccWidth.W)))
    outWrData := VecInit(Seq.fill(effOutBeatLanes)(0.S(outAccWidth.W)))
    val outRdData = Wire(Vec(effOutBeatLanes, SInt(outAccWidth.W)))

    // ─── 250 MHz pipeline stages ──────────────────────────────────────────────
    // Both failing paths at 250 MHz started at a BRAM read and ended across the
    // module: actBram -> 64-col activation broadcast -> PE accumReg, and
    // outBram -> DMA S2MM skid buffer. Each gets a 2-deep Decoupled queue so
    // the BRAM read terminates at a register and the wide fanout starts from
    // one. outBram rows are one m_axis beat wide, so the read data feeds outQ
    // directly (lane select is folded into the row address — see drain engine).
    //
    // `inQ` carries (weight beat, activation) PAIRS: the enqueue side owns the
    // actBram row counter (wrapping at cmpRows once per col tile, independent
    // of the array FSM), so pairing survives queue buffering across tile
    // boundaries. Flushed in sIdle so a host that streams extra weight beats
    // can't poison the next COMPUTE.
    class StreamBeat extends Bundle {
        val weights = Vec(outLanesPerTile, UInt(2.W))
        val act     = Vec(batchSize, UInt(aWidth.W))
    }
    val inQ = Module(new Queue(new StreamBeat, entries = 2, hasFlush = true))
    inQ.io.enq.valid := false.B
    inQ.io.enq.bits  := DontCare
    inQ.io.deq.ready := false.B

    // Optional 1.6-bit weight unpacker (see packedWeights). Sits between s_axis and
    // the inQ enqueue during COMPUTE streaming: it consumes densely packed bytes off
    // the beat and regroups them into native outLanesPerTile-wide 2-bit weight beats,
    // so each still-full wire beat carries ~1.25 weight beats' worth of trits (fewer
    // wire beats → ~20% fewer weight bytes) with no change to the beat width or the
    // enqueue handshake. flush/defaults are set with the other defaults below.
    require(!packedWeights || axisDataWidth % 8 == 0,
        s"packedWeights needs a byte-aligned s_axis (axisDataWidth=$axisDataWidth)")
    val wtGearbox =
        if (packedWeights) Some(Module(new WeightUnpackGearbox(axisDataWidth, outLanesPerTile)))
        else None

    // Array-feed source. Batched cores (yDim=B) double the PE grid, lengthening
    // the inQ-read -> PE-accumulate path (the -0.39 ns path at B=2). A 1-deep
    // registered slice (pipe=true keeps full throughput) terminates the inQ
    // distributed-RAM read at a register so the accumulate starts fresh. B=1
    // keeps the direct path (validated, closes timing) untouched. The uniform
    // 1-cycle delay on every beat is functionally transparent (nAcc unchanged).
    // Flushed with inQ (below) so stray streamed beats can't cross into the next op.
    // actMaxFanout>0 swaps the Queue for a functionally-identical ReplicatedPipeSkid
    // whose data register carries max_fanout, so synth replicates the activation
    // broadcast per column (the route-bound 250 MHz path at B>=6). actMaxFanout==0
    // keeps the exact legacy Queue → existing presets emit byte-identical SV. The
    // Option holds (deq port, flush-setter) so the two module types share the wiring.
    val armSlice: Option[(DecoupledIO[StreamBeat], Bool => Unit)] =
      if (batchSize <= 1) None
      else if (actMaxFanout > 0) {
        val s = Module(new ReplicatedPipeSkid(new StreamBeat, actMaxFanout))
        s.io.enq <> inQ.io.deq
        s.io.deq.ready := false.B
        Some((s.io.deq, (b: Bool) => s.io.flush := b))
      } else {
        val s = Module(new Queue(new StreamBeat, entries = 1, pipe = true, flow = false, hasFlush = true))
        s.io.enq <> inQ.io.deq
        s.io.deq.ready := false.B
        Some((s.io.deq, (b: Bool) => s.io.flush.get := b))
      }
    val armSrc = armSlice.map(_._1).getOrElse(inQ.io.deq)

    // `outQ` decouples the store path; it keeps draining to m_axis after the
    // FSM returns to sIdle (the runtime waits for S2MM idle, not just the
    // IRQ, so the in-flight tail beats are covered). Never flushed.
    class OutBeat extends Bundle {
        val tdata = UInt(outBeatWidth.W)
        val tlast = Bool()
    }
    val outQ = Module(new Queue(new OutBeat, entries = 2))
    outQ.io.enq.valid := false.B
    outQ.io.enq.bits  := DontCare

    // ─── Top-level FSM ────────────────────────────────────────────────────────
    val sIdle :: sLoad :: sCompute :: sStore :: Nil = Enum(4)
    val state = RegInit(sIdle)

    // captureMaxFanout > 0 inserts cCapture between cStream and cReset: the cycle in which
    // tileAccums is loaded, so its enable is a decode of REGISTERED state rather than a
    // combinational term off the FSM plus arrOutput.valid. Generated conditionally so the
    // shipped (0) configuration keeps a 4-state, 2-bit encoding and byte-identical SV.
    val pipelinedCapture = captureMaxFanout > 0
    val cStates  = Enum(if (pipelinedCapture) 5 else 4)
    val cInit    = cStates(0)
    val cStream  = cStates(1)
    val cReset   = cStates(2)
    val cDone    = cStates(3)
    val cCapture = cStates(if (pipelinedCapture) 4 else 0)   // unreachable when off
    val cState = RegInit(cInit)

    // Defaults
    handler.load.ready    := false.B
    handler.compute.ready := false.B
    handler.store.ready   := false.B
    handler.loadDone      := false.B
    handler.computeDone   := false.B
    handler.storeDone     := false.B

    s_axis.tready := false.B
    m_axis.tdata  := outQ.io.deq.bits.tdata
    m_axis.tvalid := outQ.io.deq.valid
    m_axis.tlast  := outQ.io.deq.bits.tlast
    outQ.io.deq.ready := m_axis.tready

    inQ.io.flush.get := (state === sIdle)
    armSlice.foreach { _._2(state === sIdle) }

    // Gearbox defaults: idle unless the COMPUTE enqueue block drives it, and flushed
    // whenever idle so residual padding trits never carry into the next matmul.
    wtGearbox.foreach { gb =>
        gb.io.in.valid  := false.B
        gb.io.in.bits   := 0.U
        gb.io.out.ready := false.B
        gb.io.flush     := (state === sIdle)
    }

    arrInput.weights_i.valid    := false.B
    arrInput.weights_i.bits     := VecInit(Seq.fill(outLanesPerTile)(0.U(2.W)))
    arrInput.activation_i.valid := false.B
    arrInput.activation_i.bits  := VecInit(Seq.fill(batchSize)(0.U(aWidth.W)))
    arrInput.nAcc.valid         := false.B
    arrInput.nAcc.bits          := 0.U
    arrOutput.ready             := false.B

    // ─── Latched command fields & counters ───────────────────────────────────
    // LOAD
    val loadTotalBeats = Reg(UInt(log2Ceil(maxN + 1).W))
    val loadBeatCtr    = RegInit(0.U(log2Ceil(maxN + 1).W))

    // COMPUTE
    val cmpRows         = Reg(UInt(log2Ceil(maxN + 1).W))         // N
    val cmpCols         = Reg(UInt(log2Ceil(maxM + 1).W))         // M
    val numColTiles     = Reg(UInt(log2Ceil(numColTilesMax + 1).W))
    val colTileCtr      = RegInit(0.U(log2Ceil(numColTilesMax + 1).W))
    val activeIdx       = RegInit(0.U(log2Ceil(maxN + 1).W))      // enq-side stream row (wraps at cmpRows per col tile)
    // cmpRows-1 and a registered "activeIdx is the last row" flag, kept as the
    // invariant activeIsLast == (activeIdx === cmpRowsM1). The old live compare
    // `activeIdx === (cmpRows-1)` put cmpRows (and a wide subtract+compare) in the
    // combinational cone that feeds the actBram/inQ read-address ENABLE — the
    // route-dominated 6-LUT path that walls the 250 MHz builds. Precomputing both
    // pulls cmpRows out of that cone: the remaining `(activeIdx+1)===cmpRowsM1`
    // compare feeds only the activeIsLast flop, not the BRAM enable, so the enqueue
    // address path shortens to reg→mux→BRAM.
    val cmpRowsM1       = Reg(UInt(log2Ceil(maxN + 1).W))         // cmpRows - 1 (precomputed)
    val activeIsLast    = RegInit(false.B)                       // activeIdx === cmpRowsM1
    // Runtime active batch count for THIS compute — how many of the built `batchSize`
    // PE rows the drain actually writes out. Latched from the instruction's batch
    // field (0 or >batchSize → full batchSize, so a legacy flags=0 host is unchanged).
    // Lets a high-B bitstream serve a low-batch call: the weight stream is already
    // batch-count-independent, and this stops the drain from writing (batchSize-b)
    // dead rows — which is what makes the drain hide at small N and touches only b/B
    // of outBram. batchSize==1 makes it a constant no-op.
    val cmpBatch        = Reg(UInt(log2Ceil(batchSize + 1).W))

    // Captured tile, held flat (batch-major) so the drain engine can shift it
    // down by effOutBeatLanes lanes per cycle instead of dynamically indexing.
    val totalTileLanes  = batchSize * outLanesPerTile
    val tileAccums      = Reg(Vec(totalTileLanes, UInt(tileAccWidth.W)))

    // The two CE drivers of tileAccums: capturePend loads it (parallel), drainBusy shifts
    // it. Between them they enable all batchSize*outLanesPerTile*tileAccWidth bits, which is
    // the 74.2% of endpoints that fail 300 MHz — so when captureMaxFanout is set BOTH carry
    // the attribute, not just the capture. Replicating one and leaving the other is how a
    // fanout fix reads as no change at all.
    // Declared only when the lever is on, so the shipped configuration emits the SV it
    // always did (verified by diffing the emitted Core.sv, which clone_preset_hw.sh hashes).
    val capturePend: Bool = if (pipelinedCapture) RegInit(false.B) else false.B

    // Drain engine (tileAccums -> outBram), runs in parallel with cStream.
    val drainBusy       = RegInit(false.B)
    val drainSubCtr     = RegInit(0.U(log2Ceil(outSubBeats + 1).W))
    val drainBatchCtr   = RegInit(0.U(log2Ceil(batchSize + 1).W))
    val drainColTile    = Reg(UInt(log2Ceil(numColTilesMax + 1).W))
    // Counts down the OutMemUltra write-pipeline flush after drainBusy falls, so
    // completion (cDone) waits for the last write to land in the URAM. 0-effect
    // when outWriteLat==0 (B=1 / BRAM path). See outWriteLat below.
    val drainFlush      = RegInit(0.U(log2Ceil(2).W))

    // STORE — output memory + read pipeline.
    // The deep outBram maps to a memory read cascade (~7 hops at 370M sizes) that
    // eats the 4 ns budget. `outReadLat` registered read stages let the cascade
    // pipeline so STORE closes 250 MHz; the store latency is hidden (drained while
    // the next op streams), so it is free. Batched cores deepen outBram
    // (B*tiles*subBeats) and use a URAM BlackBox (ram_style="ultra"): its internal
    // pipeline packs into the URAM cascade AND it frees the BRAMs (needed for B>=4).
    // B=1 keeps the BRAM SyncReadMem path (validated) untouched.
    val outRamStyle = if (batchSize > 1) "ultra" else "block"
    val outReadLat  = if (batchSize > 1) 4 else 2
    require(outReadLat >= 2, s"outReadLat=$outReadLat must be >= 2")
    // Extra registered write stages inside OutMemUltra (ultra path only). The
    // deep batched outBram's write-address arithmetic → URAM is the 250 MHz
    // critical path at B>=6; pipelining the write port terminates it at a flop
    // adjacent to the URAM. The drain writes during COMPUTE and reads happen in a
    // later STORE, so the latency is hidden — but the drain-completion gate
    // (cDone) must wait these extra cycles so a STORE off the IRQ sees it landed.
    val outWriteLat = if (outRamStyle == "ultra") 1 else 0
    require(outWriteLat <= 1, s"outWriteLat=$outWriteLat: widen drainFlush before raising this")
    val storeTotalBeats   = Reg(UInt(log2Ceil(outBramRows + 1).W))
    val storeBeatCtr      = RegInit(0.U(log2Ceil(outBramRows + 1).W))
    val rdPtr             = RegInit(0.U(log2Ceil(outBramRows + outReadLat + 1).W))
    val primeCtr          = RegInit(0.U(log2Ceil(outReadLat + 1).W))

    // Output-memory implementation. Both forms expose the wires declared above:
    // a write port (drain) and an `outReadLat`-deep, `outRdEn`-gated read.
    if (outRamStyle == "ultra") {
        // URAM BlackBox — pipeline registers live inside so they pack into the
        // URAM cascade (external regs would SRL-optimize and never pipeline it).
        val mem = Module(new OutMemUltra(outBramRows, effOutBeatLanes * outAccWidth, outReadLat,
                                         writeLat = outWriteLat.max(1), cascadeHeight = outCascadeHeight,
                                         pipeStyle = outPipeStyle, bankDepth = outBankDepth))
        mem.io.clk   := clock
        mem.io.wen   := outWrEn
        mem.io.waddr := outWrAddr
        mem.io.wdata := outWrData.asUInt
        mem.io.ren   := outRdEn
        mem.io.raddr := outRdAddr
        outRdData := mem.io.rdata.asTypeOf(outRdData)
    } else {
        // BRAM SyncReadMem + an external register pipeline (outReadLat-1 stages
        // after the 1-cycle synchronous read), gated by outRdEn.
        val outBram = SyncReadMem(outBramRows, Vec(effOutBeatLanes, SInt(outAccWidth.W)))
        when(outWrEn) { outBram.write(outWrAddr, outWrData) }
        val raw  = outBram.read(outRdAddr, outRdEn)
        val pipe = Reg(Vec(outReadLat - 1, Vec(effOutBeatLanes, SInt(outAccWidth.W))))
        when(outRdEn) {
            pipe(0) := raw
            for (i <- 1 until outReadLat - 1) pipe(i) := pipe(i - 1)
        }
        outRdData := pipe(outReadLat - 2)
    }

    // ─── sIdle: accept the next command ──────────────────────────────────────
    when(state === sIdle) {
        handler.load.ready    := true.B
        handler.compute.ready := true.B
        handler.store.ready   := true.B

        when(handler.load.fire) {
            loadTotalBeats := handler.load.bits.len   // dim0 = N inner-dim positions; 1 beat per position
            loadBeatCtr    := 0.U
            state          := sLoad
        }.elsewhen(handler.compute.fire) {
            cmpRows     := handler.compute.bits.rows
            cmpRowsM1   := handler.compute.bits.rows - 1.U
            cmpCols     := handler.compute.bits.cols
            numColTiles := (handler.compute.bits.cols + (outLanesPerTile - 1).U) / outLanesPerTile.U
            // Clamp the requested batch to [1, batchSize]; 0 (legacy) → full batchSize.
            val reqBatch = handler.compute.bits.batch
            val effBatch = Mux(reqBatch === 0.U || reqBatch > batchSize.U, batchSize.U, reqBatch)
            cmpBatch    := effBatch(log2Ceil(batchSize + 1) - 1, 0)
            colTileCtr  := 0.U
            activeIdx   := 0.U
            activeIsLast := handler.compute.bits.rows === 1.U   // activeIdx=0 is last iff N==1
            cState      := cInit
            state       := sCompute
        }.elsewhen(handler.store.fire) {
            storeTotalBeats   := handler.store.bits.len / effOutBeatLanes.U
            storeBeatCtr      := 0.U
            rdPtr             := 0.U
            primeCtr          := outReadLat.U   // fill the read pipeline before emitting
            state             := sStore
        }
    }

    // ─── sLoad: drain s_axis into activation BRAM ────────────────────────────
    when(state === sLoad) {
        s_axis.tready := true.B
        when(s_axis.tvalid) {
            // Pack the low batchSize*aWidth bits of the beat into one BRAM entry.
            val unpacked = VecInit(Seq.tabulate(batchSize) { i =>
                s_axis.tdata((i + 1) * aWidth - 1, i * aWidth)
            })
            actBram.foreach(_.write(loadBeatCtr(log2Ceil(maxN) - 1, 0), unpacked))
            actMem.foreach { m =>
                m.io.wen   := true.B
                m.io.waddr := loadBeatCtr(log2Ceil(maxN) - 1, 0)
                m.io.wdata := unpacked.asUInt
            }
            val next = loadBeatCtr + 1.U
            loadBeatCtr := next
            when(next === loadTotalBeats) {
                handler.loadDone := true.B
                state            := sIdle
            }
        }
    }

    // ─── sCompute: col tiling, one array pass per tile ───────────────────────
    //
    // Enqueue side (s_axis + actBram -> inQ): runs through cStream/cReset
    // so the stream never has to stall for the inter-tile merge. `activeIdx` is
    // the STREAM's row position — it wraps at cmpRows once per col tile and is
    // only reset when a new COMPUTE is accepted, because inQ may legitimately
    // hold the first beats of tile t+1 while the array is still merging tile t.
    when(state === sCompute && cState =/= cInit && cState =/= cDone) {
        // Weight-beat source. Both branches present a Decoupled of outLanesPerTile
        // 2-bit lanes; the inQ enqueue handshake below is identical either way.
        val (wtValid, wtBits) = wtGearbox match {
            case Some(gb) =>
                // Packed: s_axis (dense bytes) feeds the gearbox; its regrouped
                // output feeds inQ. s_axis is throttled by gearbox room, decoupling
                // wire beats from weight beats.
                gb.io.in.valid  := s_axis.tvalid
                gb.io.in.bits   := s_axis.tdata
                s_axis.tready   := gb.io.in.ready
                gb.io.out.ready := inQ.io.enq.ready
                (gb.io.out.valid, gb.io.out.bits)
            case None =>
                // Legacy: 2-bit lanes sliced straight off the beat (byte-identical SV).
                val weightVec = VecInit(Seq.tabulate(outLanesPerTile) { i =>
                    s_axis.tdata((i + 1) * 2 - 1, i * 2)
                })
                s_axis.tready := inQ.io.enq.ready
                (s_axis.tvalid, weightVec)
        }
        inQ.io.enq.valid        := wtValid
        inQ.io.enq.bits.weights := wtBits
        inQ.io.enq.bits.act     := actRdData

        val lastRow = activeIsLast                       // registered; == activeIdx === cmpRowsM1
        val nextRow = Mux(lastRow, 0.U, activeIdx + 1.U)
        actRdAddr := Mux(inQ.io.enq.fire, nextRow, activeIdx)(log2Ceil(maxN) - 1, 0)
        when(inQ.io.enq.fire) {
            activeIdx := nextRow
            // Maintain the invariant for the NEXT activeIdx (= nextRow). The
            // compare ends at this flop, off the BRAM-enable path.
            activeIsLast := Mux(lastRow, cmpRowsM1 === 0.U, (activeIdx + 1.U) === cmpRowsM1)
        }
    }

    when(state === sCompute) {
        switch(cState) {
            is(cInit) {
                // Drive read addr for inner-dim 0 so the enqueue side sees
                // mem[0] next cycle.
                actRdAddr    := 0.U
                activeIdx    := 0.U
                activeIsLast := cmpRowsM1 === 0.U                // activeIdx=0 is last iff N==1
                cState       := cStream
            }

            is(cStream) {
                // Dequeue side (inQ -> array). While the array output is
                // pending the handshake is shut off so no spurious beat is
                // lost — the queue absorbs the stall instead of
                // back-pressuring the DMA. The capture itself additionally
                // waits for the previous tile's drain to release tileAccums
                // (only possible when cmpRows < batchSize*outSubBeats; real
                // shapes hide the drain entirely under the next tile's pass).
                val outPending = arrOutput.valid
                val capture    = outPending && !drainBusy

                arrInput.weights_i.valid    := armSrc.valid && !outPending
                arrInput.weights_i.bits     := armSrc.bits.weights
                arrInput.activation_i.valid := armSrc.valid && !outPending
                arrInput.activation_i.bits  := armSrc.bits.act
                arrInput.nAcc.valid         := !outPending
                arrInput.nAcc.bits          := cmpRows
                armSrc.ready                 := arrInput.weights_i.ready && !outPending

                if (!pipelinedCapture) {
                    when(capture) {
                        tileAccums       := arrOutput.bits.accum.asTypeOf(tileAccums)
                        arrOutput.ready := true.B
                        drainBusy        := true.B
                        drainSubCtr      := 0.U
                        drainBatchCtr    := 0.U
                        drainColTile     := colTileCtr
                        cState           := cReset
                    }
                } else {
                    // Decide here, load next cycle. Nothing is consumed yet: arrOutput.ready
                    // stays low, so the array holds valid and its accumulators, and the same
                    // bits are still there in cCapture. See cCapture below.
                    when(capture) {
                        capturePend := true.B
                        cState      := cCapture
                    }
                }
            }

            is(cReset) {
                // Hard-reset the array so the next col tile starts clean.
                // activeIdx / actRdAddr are NOT touched here — they belong to
                // the enqueue side, which already wrapped at the tile boundary
                // (and may have the next tile's first beats queued).
                arrLocalReset := true.B

                val nextCol = colTileCtr + 1.U
                when(nextCol === numColTiles) {
                    cState := cDone
                }.otherwise {
                    colTileCtr := nextCol
                    cState     := cStream
                }
            }

            is(cDone) {
                // Hold the completion until the last tile's drain has landed
                // in outBram — a host could issue STORE right off the IRQ.
                // drainFlush covers the OutMemUltra write-pipeline depth.
                when(!drainBusy && drainFlush === 0.U) {
                    handler.computeDone := true.B
                    state               := sIdle
                }
            }
        }

        // cCapture — the load cycle, and deliberately NOT an `is` arm: Chisel's switch macro
        // rejects any block inside it that does not begin with is(), so a conditionally
        // generated arm cannot live there. A sibling when() on the same registered state is
        // the identical hardware, because the switch above has no arm for this encoding and
        // nothing else drives these signals while cState holds it.
        //
        // capturePend (a plain register, replicated by the max_fanout attribute below) is
        // what enables tileAccums; the FSM only has to release the array and start the drain,
        // exactly as the un-pipelined path did in the same cycle as the load.
        if (pipelinedCapture) {
            when(cState === cCapture) {
                capturePend     := false.B
                arrOutput.ready := true.B
                drainBusy       := true.B
                drainSubCtr     := 0.U
                drainBatchCtr   := 0.U
                drainColTile    := colTileCtr
                cState          := cReset
            }
        }
    }

    // ─── Tile capture, pipelined (captureMaxFanout > 0) ──────────────────────
    // The parallel load, moved out of the FSM so its enable is one register instead of a
    // combinational term off state/cState/arrOutput.valid/drainBusy. max_fanout then lets
    // synthesis replicate that register — and drainBusy, the shift enable — so each replica
    // drives a local slice of the 59 392-bit bank rather than one net crossing the die.
    // Placed BEFORE the drain block so a drain shift still wins on any overlap; the two are
    // mutually exclusive anyway (capture requires !drainBusy one cycle earlier).
    if (pipelinedCapture) {
        when(capturePend) {
            tileAccums := arrOutput.bits.accum.asTypeOf(tileAccums)
        }
        annotate(Seq(capturePend))(Seq(
            AttributeAnnotation(capturePend.toNamed, s"max_fanout = $captureMaxFanout")))
        annotate(Seq(drainBusy))(Seq(
            AttributeAnnotation(drainBusy.toNamed, s"max_fanout = $captureMaxFanout")))
    }

    // ─── Drain engine: tileAccums -> outBram, one beat-wide row per cycle ────
    // Kicked off by the cStream capture and runs in parallel with the next col
    // tile's stream (batchSize*outSubBeats cycles, hidden under the >= cmpRows
    // cycles of the next array pass). tileAccums shifts down by effOutBeatLanes
    // lanes per cycle so the write data is always lanes [0, effOutBeatLanes) —
    // the wide dynamic lane mux that failed timing at 250 MHz never exists.
    when(drainBusy) {
        val writeVec = Wire(Vec(effOutBeatLanes, SInt(outAccWidth.W)))
        for (k <- 0 until effOutBeatLanes) {
            writeVec(k) := tileAccums(k).asSInt
        }
        val entryIdx = drainBatchCtr * numColTiles + drainColTile
        val rowAddr  = (entryIdx * outSubBeats.U + drainSubCtr)(log2Ceil(outBramRows) - 1, 0)
        outWrEn   := true.B
        outWrAddr := rowAddr
        outWrData := writeVec

        for (i <- 0 until totalTileLanes) {
            tileAccums(i) := (if (i + effOutBeatLanes < totalTileLanes)
                tileAccums(i + effOutBeatLanes) else 0.U)
        }

        val subNext = drainSubCtr + 1.U
        when(subNext === outSubBeats.U) {
            drainSubCtr := 0.U
            val batchNext = drainBatchCtr + 1.U
            when(batchNext === cmpBatch) {
                drainBatchCtr := 0.U
                drainBusy     := false.B
            }.otherwise {
                drainBatchCtr := batchNext
            }
        }.otherwise {
            drainSubCtr := subNext
        }
    }

    // While draining, keep the flush counter primed; once drainBusy falls, count
    // down the OutMemUltra write-pipeline depth so the last write has landed
    // before cDone signals completion (the URAM write port is `outWriteLat`-deep).
    when(drainBusy) {
        drainFlush := outWriteLat.U
    }.elsewhen(drainFlush =/= 0.U) {
        drainFlush := drainFlush - 1.U
    }

    // ─── sStore: stream outBram rows (one per m_axis beat) via outQ ──────────
    // Rows are exactly one beat wide and laid out in stream order, so the walk is
    // a flat row counter — no sub-beat lane mux. The read is `outReadLat`-deep
    // (inside the memory block above) so the tool pipelines the deep read cascade.
    // `rdPtr` issues addresses, leading the emitted beat by `outReadLat`;
    // `primeCtr` counts down the fill cycles before the first beat is valid;
    // `outRdEn` advances/gates the read pipeline (freezes the whole read on a
    // stall). m_axis drains outQ unconditionally — including the tail beats in
    // flight after storeDone fires.
    when(state === sStore) {
        val primed = primeCtr === 0.U
        val fire   = outQ.io.enq.ready && primed
        val adv    = !primed || fire          // advance the read pipeline this cycle

        outRdAddr := rdPtr(log2Ceil(outBramRows) - 1, 0)
        outRdEn   := adv
        when(adv) {
            rdPtr := rdPtr + 1.U
            when(!primed) { primeCtr := primeCtr - 1.U }
        }

        // Each lane is sign-extended from outAccWidth to outLaneWidth so the
        // beat width hits a clean power of two.
        val laneBits = VecInit(outRdData.map(_.pad(outLaneWidth).asUInt))
        outQ.io.enq.valid      := primed
        outQ.io.enq.bits.tdata := Cat(laneBits.reverse)
        outQ.io.enq.bits.tlast := (storeBeatCtr + 1.U) === storeTotalBeats

        when(fire) {
            val nextBeat = storeBeatCtr + 1.U
            storeBeatCtr := nextBeat
            when(nextBeat === storeTotalBeats) {
                handler.storeDone := true.B
                state             := sIdle
            }
        }
    }
}

object Core {
    /** Field-by-field AXI4 slave passthrough (both bundles describe slave-side view). */
    def connectAxiSlave(outer: AxiSlaveIO, inner: AxiSlaveIO): Unit = {
        inner.awid    := outer.awid
        inner.awaddr  := outer.awaddr
        inner.awlen   := outer.awlen
        inner.awsize  := outer.awsize
        inner.awburst := outer.awburst
        inner.awvalid := outer.awvalid
        inner.wdata   := outer.wdata
        inner.wstrb   := outer.wstrb
        inner.wlast   := outer.wlast
        inner.wvalid  := outer.wvalid
        inner.bready  := outer.bready
        inner.arid    := outer.arid
        inner.araddr  := outer.araddr
        inner.arlen   := outer.arlen
        inner.arsize  := outer.arsize
        inner.arburst := outer.arburst
        inner.arvalid := outer.arvalid
        inner.rready  := outer.rready
        outer.awready := inner.awready
        outer.wready  := inner.wready
        outer.bid     := inner.bid
        outer.bresp   := inner.bresp
        outer.bvalid  := inner.bvalid
        outer.arready := inner.arready
        outer.rid     := inner.rid
        outer.rdata   := inner.rdata
        outer.rresp   := inner.rresp
        outer.rlast   := inner.rlast
        outer.rvalid  := inner.rvalid
    }
}

/**
  * 1-deep pipelined skid buffer — functionally identical to
  * `Queue(entries=1, pipe=true, flow=false, hasFlush=true)` — whose single data
  * register carries a `max_fanout` synthesis attribute.
  *
  * Used for the batched array-feed register, which broadcasts the activation to
  * every column's PEs (fanout ≈ xDim·nLanes). At B>=6 that broadcast is the
  * route-bound path that keeps the design off 250 MHz; forcing synth to physically
  * replicate the register places a copy near each column and shortens the routes.
  * (With SYNTH_FLATTEN=full — already set for the batched presets — the replication
  * crosses into the array where the fanout expands.)
  */
class ReplicatedPipeSkid[T <: Data](gen: T, maxFanout: Int) extends Module {
  val io = IO(new Bundle {
    val enq   = Flipped(Decoupled(gen))
    val deq   = Decoupled(gen)
    val flush = Input(Bool())
  })
  val full = RegInit(false.B)
  val data = Reg(gen)
  if (maxFanout > 0)
    annotate(Seq(data))(Seq(AttributeAnnotation(data.toNamed, s"max_fanout = $maxFanout")))
  io.enq.ready := !full || io.deq.ready   // pipe=true: accept when empty, or while draining
  io.deq.valid := full
  io.deq.bits  := data
  when(io.enq.fire) { data := io.enq.bits }
  when(io.flush)         { full := false.B }   // flush wins (== hasFlush)
    .elsewhen(io.enq.fire) { full := true.B }
    .elsewhen(io.deq.fire) { full := false.B }
}
