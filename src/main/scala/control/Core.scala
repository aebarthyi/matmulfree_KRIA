package control

import chisel3._
import chisel3.util._
import systolic.SystolicArray

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
    val signedAct:    Boolean = false  // sign-extend activations (real BitLinear / HGRN int16)
) extends Module {
    require(aWidth >= 2 && aWidth % 2 == 0,        "aWidth must be even and >= 2")
    require(maxAcc >= 2,                           "maxAcc must be >= 2")
    require(xDim >= 1 && batchSize >= 1,           "xDim and batchSize must be >= 1")
    require(maxN <= maxAcc,                        s"maxN=$maxN must be <= maxAcc=$maxAcc (no inner-dim tiling yet)")
    require(maxN >= 1,                             "maxN must be >= 1")
    require(maxM >= 1,                             "maxM must be >= 1")

    val yDim            = batchSize
    val nLanes          = aWidth / 2
    val outLanesPerTile = xDim * nLanes
    val tileAccWidth    = log2Ceil(maxAcc) + aWidth
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
    val arr = withReset(this.reset.asBool || arrLocalReset) {
        Module(new SystolicArray(aWidth, maxAcc, xDim, yDim, signedAct))
    }

    // ─── On-chip storage ──────────────────────────────────────────────────────
    val actBram = SyncReadMem(maxN, Vec(batchSize, UInt(aWidth.W)))
    val actRdAddr = Wire(UInt(log2Ceil(maxN).W))
    actRdAddr := 0.U
    val actRdData = actBram.read(actRdAddr)

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

    // Array-feed source. Batched cores (yDim=B) double the PE grid, lengthening
    // the inQ-read -> PE-accumulate path (the -0.39 ns path at B=2). A 1-deep
    // registered slice (pipe=true keeps full throughput) terminates the inQ
    // distributed-RAM read at a register so the accumulate starts fresh. B=1
    // keeps the direct path (validated, closes timing) untouched. The uniform
    // 1-cycle delay on every beat is functionally transparent (nAcc unchanged).
    // Flushed with inQ (below) so stray streamed beats can't cross into the next op.
    val armSlice = if (batchSize > 1)
      Some(Module(new Queue(new StreamBeat, entries = 1, pipe = true, flow = false, hasFlush = true)))
    else None
    armSlice.foreach { s => s.io.enq <> inQ.io.deq; s.io.deq.ready := false.B }
    val armSrc = armSlice.map(_.io.deq).getOrElse(inQ.io.deq)

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

    val cInit :: cStream :: cReset :: cDone :: Nil = Enum(4)
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
    armSlice.foreach { _.io.flush.get := (state === sIdle) }

    arr.input.weights_i.valid    := false.B
    arr.input.weights_i.bits     := VecInit(Seq.fill(outLanesPerTile)(0.U(2.W)))
    arr.input.activation_i.valid := false.B
    arr.input.activation_i.bits  := VecInit(Seq.fill(batchSize)(0.U(aWidth.W)))
    arr.input.nAcc.valid         := false.B
    arr.input.nAcc.bits          := 0.U
    arr.output.ready             := false.B

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

    // Captured tile, held flat (batch-major) so the drain engine can shift it
    // down by effOutBeatLanes lanes per cycle instead of dynamically indexing.
    val totalTileLanes  = batchSize * outLanesPerTile
    val tileAccums      = Reg(Vec(totalTileLanes, UInt(tileAccWidth.W)))

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
        val mem = Module(new OutMemUltra(outBramRows, effOutBeatLanes * outAccWidth, outReadLat, writeLat = outWriteLat.max(1)))
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
            cmpCols     := handler.compute.bits.cols
            numColTiles := (handler.compute.bits.cols + (outLanesPerTile - 1).U) / outLanesPerTile.U
            colTileCtr  := 0.U
            activeIdx   := 0.U
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
            actBram.write(loadBeatCtr(log2Ceil(maxN) - 1, 0), unpacked)
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
        val weightVec = VecInit(Seq.tabulate(outLanesPerTile) { i =>
            s_axis.tdata((i + 1) * 2 - 1, i * 2)
        })
        inQ.io.enq.valid        := s_axis.tvalid
        inQ.io.enq.bits.weights := weightVec
        inQ.io.enq.bits.act     := actRdData
        s_axis.tready           := inQ.io.enq.ready

        val lastRow = activeIdx === (cmpRows - 1.U)
        val nextRow = Mux(lastRow, 0.U, activeIdx + 1.U)
        actRdAddr := Mux(inQ.io.enq.fire, nextRow, activeIdx)(log2Ceil(maxN) - 1, 0)
        when(inQ.io.enq.fire) {
            activeIdx := nextRow
        }
    }

    when(state === sCompute) {
        switch(cState) {
            is(cInit) {
                // Drive read addr for inner-dim 0 so the enqueue side sees
                // mem[0] next cycle.
                actRdAddr  := 0.U
                activeIdx  := 0.U
                cState     := cStream
            }

            is(cStream) {
                // Dequeue side (inQ -> array). While the array output is
                // pending the handshake is shut off so no spurious beat is
                // lost — the queue absorbs the stall instead of
                // back-pressuring the DMA. The capture itself additionally
                // waits for the previous tile's drain to release tileAccums
                // (only possible when cmpRows < batchSize*outSubBeats; real
                // shapes hide the drain entirely under the next tile's pass).
                val outPending = arr.output.valid
                val capture    = outPending && !drainBusy

                arr.input.weights_i.valid    := armSrc.valid && !outPending
                arr.input.weights_i.bits     := armSrc.bits.weights
                arr.input.activation_i.valid := armSrc.valid && !outPending
                arr.input.activation_i.bits  := armSrc.bits.act
                arr.input.nAcc.valid         := !outPending
                arr.input.nAcc.bits          := cmpRows
                armSrc.ready                 := arr.input.weights_i.ready && !outPending

                when(capture) {
                    tileAccums       := arr.output.bits.accum.asTypeOf(tileAccums)
                    arr.output.ready := true.B
                    drainBusy        := true.B
                    drainSubCtr      := 0.U
                    drainBatchCtr    := 0.U
                    drainColTile     := colTileCtr
                    cState           := cReset
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
            when(batchNext === batchSize.U) {
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
