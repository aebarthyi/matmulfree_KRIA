package core

import chisel3._
import chisel3.util._
import bus.{AxiSlaveIO, AxisMasterIO, AxisSlaveIO}
import compute.MacArray
import control.AxiInstructionHandler
import memory.{ActMem, OutMemUltra}
import packing.WeightUnpackGearbox

/**
  * Top-level ternary matmul Core, parameterized entirely by a [[CoreConfig]] (every width
  * used below is derived there).
  *
  *   - `s_axi`   : AXI4 slave for instruction MMIO (→ [[AxiInstructionHandler]])
  *   - `s_axis`  : AXI4-S slave; carries activations (LOAD_ACT) or weights (COMPUTE_MM)
  *   - `m_axis`  : AXI4-S master; carries outputs (STORE_OUT) in beats of
  *                 `outBeatLanes` lanes, independent of the per-tile output width.
  *   - `irq`     : level-high interrupt on instruction completion.
  *
  * **Compute model.** Weights are broadcast to all yDim (= batch) rows of the
  * [[MacArray]]; each row streams its own activation, and PEs accumulate
  * `sum_n W[n][col] * A[batch][n]`. Per COMPUTE_MM: rows = N = inner dim
  * (≤ maxN ≤ maxAcc, no inner-dim tiling); cols = M, tiled by
  * `outLanesPerTile = xDim * nLanes`. Each col tile streams N cycles, then drains into
  * outBram overlapped with the next tile's stream.
  *
  * **Storage.**
  *   - `actBram`: maxN entries x (batchSize · aWidth); entry n = inner-dim slice n
  *      across all batch items.
  *   - `outBram`: batchSize · numColTilesMax · outSubBeats rows, each exactly one
  *      m_axis beat (no lane mux on the store path). Row address =
  *      (batchIdx · numColTiles + colTileIdx) · outSubBeats + subBeatIdx.
  *
  * The array is hard-reset (arrLocalReset) between col-tile passes.
  *
  * Internal signal names (`arr`, `inQ`, `armSlice`, ...) are what the emitted SV and the
  * Vivado timing reports call these paths; renaming one changes the shipped RTL.
  */
class Core(val cfg: CoreConfig) extends Module {
    import cfg._

    // ─── IO ───────────────────────────────────────────────────────────────────
    val s_axi  = IO(new AxiSlaveIO(axiAddrWidth, axiDataWidth, axiIdWidth))
    val s_axis = IO(new AxisSlaveIO(axisDataWidth))
    val m_axis = IO(new AxisMasterIO(outBeatWidth))
    val irq    = IO(Output(Bool()))

    // ─── Instruction handler ──────────────────────────────────────────────────
    val handler = Module(new AxiInstructionHandler(axiAddrWidth, axiDataWidth, axiIdWidth))
    handler.s_axi <> s_axi
    irq := handler.irq

    // ─── MAC array, with a Core-local reset pulsed between col tiles ─────────
    val arrLocalReset = WireDefault(false.B)
    val arr = withReset(this.reset.asBool || arrLocalReset) {
        Module(new MacArray(aWidth, maxAcc, xDim, yDim, signedAct, dspAccum, colBankSize))
    }
    val arrInput  = arr.input
    val arrOutput = arr.output

    // ─── On-chip storage ──────────────────────────────────────────────────────
    // actBram: maxN entries x (batchSize * aWidth) bits, 1-cycle synchronous read.
    // actRamStyle "" = inferred SyncReadMem; "block"/"ultra" = the ActMem BlackBox, which
    // has identical cycle behaviour and only adds the cascade-bounding attributes.
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

    // Output-memory port wires (implementation chosen by `outRamStyle` below). Both forms
    // present `outRdData` delayed by `outReadLat`, advanced by `outRdEn`; the drain
    // engine drives the write port.
    val outRdAddr = Wire(UInt(log2Ceil(outBramRows).W)); outRdAddr := 0.U
    val outRdEn   = WireDefault(false.B)
    val outWrEn   = WireDefault(false.B)
    val outWrAddr = WireDefault(0.U(log2Ceil(outBramRows).W))
    val outWrData = Wire(Vec(effOutBeatLanes, SInt(outAccWidth.W)))
    outWrData := VecInit(Seq.fill(effOutBeatLanes)(0.S(outAccWidth.W)))
    val outRdData = Wire(Vec(effOutBeatLanes, SInt(outAccWidth.W)))

    // ─── 250 MHz pipeline stages ──────────────────────────────────────────────
    // inQ / outQ are 2-deep queues so the actBram / outBram reads terminate at a
    // register before the wide array fanout / the DMA side.
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

    // Optional 1.6-bit weight unpacker between s_axis and the inQ enqueue: regroups densely
    // packed bytes into outLanesPerTile-wide 2-bit weight beats.
    val wtGearbox =
        if (packedWeights) Some(Module(new WeightUnpackGearbox(axisDataWidth, outLanesPerTile)))
        else None

    // Array-feed source. For B>1 a 1-deep registered slice (pipe=true, full throughput)
    // terminates the inQ read at a register before the larger PE grid; the uniform 1-cycle
    // delay is functionally transparent. Flushed with inQ. B=1 feeds from inQ directly.
    // The Option holds (deq port, flush-setter).
    val armSlice: Option[(DecoupledIO[StreamBeat], Bool => Unit)] =
      if (batchSize <= 1) None
      else {
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
    val numColTiles     = Reg(UInt(log2Ceil(numColTilesMax + 1).W))
    val colTileCtr      = RegInit(0.U(log2Ceil(numColTilesMax + 1).W))
    val activeIdx       = RegInit(0.U(log2Ceil(maxN + 1).W))      // enq-side stream row (wraps at cmpRows per col tile)
    // Invariant: activeIsLast == (activeIdx === cmpRowsM1). Registered so cmpRows and the
    // wide compare stay out of the actBram read-address cone (a 250 MHz critical path);
    // the enqueue address path is reg -> mux -> BRAM.
    val cmpRowsM1       = Reg(UInt(log2Ceil(maxN + 1).W))         // cmpRows - 1 (precomputed)
    val activeIsLast    = RegInit(false.B)                       // activeIdx === cmpRowsM1
    // Runtime batch count for this COMPUTE: the drain writes only this many of the
    // batchSize rows. From the instruction flags; 0 or > batchSize = full batchSize.
    val cmpBatch        = Reg(UInt(log2Ceil(batchSize + 1).W))

    // Captured tile, held flat (batch-major) so the drain engine can shift it
    // down by effOutBeatLanes lanes per cycle instead of dynamically indexing.
    val totalTileLanes  = batchSize * outLanesPerTile
    val tileAccums      = Reg(Vec(totalTileLanes, UInt(tileAccWidth.W)))

    // Drain engine (tileAccums -> outBram), runs in parallel with cStream.
    val drainBusy       = RegInit(false.B)
    val drainSubCtr     = RegInit(0.U(log2Ceil(outSubBeats + 1).W))
    val drainBatchCtr   = RegInit(0.U(log2Ceil(batchSize + 1).W))
    val drainColTile    = Reg(UInt(log2Ceil(numColTilesMax + 1).W))
    // Counts down the OutMemUltra write pipeline after drainBusy falls so cDone waits
    // for the last write to land. No effect when outWriteLat == 0.
    val drainFlush      = RegInit(0.U(log2Ceil(2).W))

    // STORE — output memory + read pipeline. `outReadLat` registered read stages let the
    // deep memory close 250 MHz; the latency is hidden behind the next op. B>1 uses the
    // URAM BlackBox (OutMemUltra, also frees BRAM); B=1 uses a BRAM SyncReadMem.
    val outRamStyle = if (batchSize > 1) "ultra" else "block"
    val outReadLat  = if (batchSize > 1) 4 else 2
    require(outReadLat >= 2, s"outReadLat=$outReadLat must be >= 2")
    // Registered write stages inside OutMemUltra (ultra only): terminate the drain's
    // write-address arithmetic at a flop next to the URAM. cDone waits them out
    // (drainFlush) so a STORE issued off the IRQ sees the last write.
    val outWriteLat = if (outRamStyle == "ultra") 1 else 0
    require(outWriteLat <= 1, s"outWriteLat=$outWriteLat: widen drainFlush before raising this")
    val storeTotalBeats   = Reg(UInt(log2Ceil(outBramRows + 1).W))
    val storeBeatCtr      = RegInit(0.U(log2Ceil(outBramRows + 1).W))
    val rdPtr             = RegInit(0.U(log2Ceil(outBramRows + outReadLat + 1).W))
    val primeCtr          = RegInit(0.U(log2Ceil(outReadLat + 1).W))

    if (outRamStyle == "ultra") {
        // URAM BlackBox; the read pipeline registers live inside it (see OutMemUltra).
        val mem = Module(new OutMemUltra(outBramRows, effOutBeatLanes * outAccWidth, outReadLat,
                                         writeLat = outWriteLat.max(1), cascadeHeight = outCascadeHeight,
                                         bankDepth = outBankDepth))
        mem.io.clk   := clock
        mem.io.wen   := outWrEn
        mem.io.waddr := outWrAddr
        mem.io.wdata := outWrData.asUInt
        mem.io.ren   := outRdEn
        mem.io.raddr := outRdAddr
        outRdData := mem.io.rdata.asTypeOf(outRdData)
    } else {
        // BRAM SyncReadMem + outReadLat-1 external stages after the 1-cycle read.
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
    // Enqueue side (s_axis + actBram -> inQ): runs through cStream/cReset so the
    // stream never stalls for the inter-tile merge. `activeIdx` is the STREAM's row
    // position: it wraps at cmpRows per col tile and is reset only on a new COMPUTE,
    // because inQ may hold tile t+1's first beats while the array finishes tile t.
    when(state === sCompute && cState =/= cInit && cState =/= cDone) {
        val (wtValid, wtBits) = wtGearbox match {
            case Some(gb) =>
                // Packed: s_axis -> gearbox -> inQ; s_axis is throttled by gearbox room.
                gb.io.in.valid  := s_axis.tvalid
                gb.io.in.bits   := s_axis.tdata
                s_axis.tready   := gb.io.in.ready
                gb.io.out.ready := inQ.io.enq.ready
                (gb.io.out.valid, gb.io.out.bits)
            case None =>
                // 2-bit lanes sliced straight off the beat.
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
            // Maintain the invariant for the next activeIdx (= nextRow).
            activeIsLast := Mux(lastRow, cmpRowsM1 === 0.U, (activeIdx + 1.U) === cmpRowsM1)
        }
    }

    when(state === sCompute) {
        switch(cState) {
            is(cInit) {
                // Prefetch row 0 so the enqueue side sees mem[0] next cycle.
                actRdAddr    := 0.U
                activeIdx    := 0.U
                activeIsLast := cmpRowsM1 === 0.U                // activeIdx=0 is last iff N==1
                cState       := cStream
            }

            is(cStream) {
                // Dequeue side (inQ -> array). While the array output is pending
                // (the capture cycle) the input handshake MUST be off, or a beat is
                // silently consumed and lost; inQ absorbs the stall. Capture also waits
                // for the previous tile's drain to release tileAccums (only when
                // cmpRows < batchSize*outSubBeats).
                val outPending = arrOutput.valid
                val capture    = outPending && !drainBusy

                arrInput.weights_i.valid    := armSrc.valid && !outPending
                arrInput.weights_i.bits     := armSrc.bits.weights
                arrInput.activation_i.valid := armSrc.valid && !outPending
                arrInput.activation_i.bits  := armSrc.bits.act
                arrInput.nAcc.valid         := !outPending
                arrInput.nAcc.bits          := cmpRows
                armSrc.ready                 := arrInput.weights_i.ready && !outPending

                when(capture) {
                    tileAccums       := arrOutput.bits.accum.asTypeOf(tileAccums)
                    arrOutput.ready  := true.B
                    drainBusy        := true.B
                    drainSubCtr      := 0.U
                    drainBatchCtr    := 0.U
                    drainColTile     := colTileCtr
                    cState           := cReset
                }
            }

            is(cReset) {
                // Hard-reset the array for the next col tile. activeIdx / actRdAddr
                // belong to the enqueue side (already wrapped) and are not touched.
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
                // Complete only once the last drain has landed (incl. the OutMemUltra
                // write pipeline) — a host may issue STORE right off the IRQ.
                when(!drainBusy && drainFlush === 0.U) {
                    handler.computeDone := true.B
                    state               := sIdle
                }
            }
        }
    }

    // ─── Drain engine: tileAccums -> outBram, one beat-wide row per cycle ────
    // Runs in parallel with the next col tile's stream (cmpBatch*outSubBeats cycles).
    // tileAccums shifts down by effOutBeatLanes lanes per cycle so the write data is
    // always lanes [0, effOutBeatLanes) — no wide dynamic lane mux.
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

    // Primed while draining; counts down outWriteLat cycles once drainBusy falls.
    when(drainBusy) {
        drainFlush := outWriteLat.U
    }.elsewhen(drainFlush =/= 0.U) {
        drainFlush := drainFlush - 1.U
    }

    // ─── sStore: stream outBram rows (one per m_axis beat) via outQ ──────────
    // Rows are laid out in stream order, so the walk is a flat row counter. `rdPtr`
    // leads the emitted beat by `outReadLat`; `primeCtr` counts the fill cycles before
    // the first valid beat; `outRdEn` freezes the whole read pipeline on a stall.
    // m_axis drains outQ unconditionally, including tail beats after storeDone.
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
