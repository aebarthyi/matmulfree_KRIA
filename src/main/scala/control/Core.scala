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
  *     cycles, then write batchSize result entries (one per row) into outBram.
  *
  * **Storage.**
  *   - `actBram`: depth = maxN, width = batchSize · aWidth. Each entry holds the
  *      n-th inner-dim slice across all batch items.
  *   - `outBram`: depth = batchSize · numColTilesMax, width = outLanesPerTile ·
  *      outAccWidth. Linear address = batchIdx · numColTiles + colTileIdx.
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
    val axiIdWidth:   Int = 6
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
        Module(new SystolicArray(aWidth, maxAcc, xDim, yDim))
    }

    // ─── On-chip storage ──────────────────────────────────────────────────────
    val actBram = SyncReadMem(maxN, Vec(batchSize, UInt(aWidth.W)))
    val outBram = SyncReadMem(outBramDepth, Vec(outLanesPerTile, SInt(outAccWidth.W)))

    val actRdAddr = Wire(UInt(log2Ceil(maxN).W))
    val outRdAddr = Wire(UInt(log2Ceil(outBramDepth).W))
    actRdAddr := 0.U
    outRdAddr := 0.U
    val actRdData = actBram.read(actRdAddr)
    val outRdData = outBram.read(outRdAddr)

    // ─── Top-level FSM ────────────────────────────────────────────────────────
    val sIdle :: sLoad :: sCompute :: sStore :: Nil = Enum(4)
    val state = RegInit(sIdle)

    val cInit :: cStream :: cWrite :: cReset :: cDone :: Nil = Enum(5)
    val cState = RegInit(cInit)

    // Defaults
    handler.load.ready    := false.B
    handler.compute.ready := false.B
    handler.store.ready   := false.B
    handler.loadDone      := false.B
    handler.computeDone   := false.B
    handler.storeDone     := false.B

    s_axis.tready := false.B
    m_axis.tdata  := 0.U
    m_axis.tvalid := false.B
    m_axis.tlast  := false.B

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
    val activeIdx       = RegInit(0.U(log2Ceil(maxN + 1).W))      // inner-dim cycle index
    val tileAccums      = Reg(Vec(batchSize, Vec(outLanesPerTile, UInt(tileAccWidth.W))))
    val mergeBatchCtr   = RegInit(0.U(log2Ceil(batchSize + 1).W))

    // STORE
    val storeTotalBeats   = Reg(UInt(log2Ceil(batchSize * numColTilesMax * outSubBeats + 1).W))
    val storeBeatCtr      = RegInit(0.U(log2Ceil(batchSize * numColTilesMax * outSubBeats + 1).W))
    val storeSubBeatCtr   = RegInit(0.U(log2Ceil(outSubBeats + 1).W))
    val storeColCtr       = RegInit(0.U(log2Ceil(numColTilesMax + 1).W))
    val storeBatchCtr     = RegInit(0.U(log2Ceil(batchSize + 1).W))

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
            storeSubBeatCtr   := 0.U
            storeColCtr       := 0.U
            storeBatchCtr     := 0.U
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
    when(state === sCompute) {
        switch(cState) {
            is(cInit) {
                // Drive read addr for inner-dim 0 so cStream sees mem[0] next cycle.
                actRdAddr  := 0.U
                activeIdx  := 0.U
                cState     := cStream
            }

            is(cStream) {
                // Combinational gating: on the capture cycle we shut both
                // handshakes off so no spurious activation / weight beat is lost.
                val capture = arr.output.valid

                val weightVec = VecInit(Seq.tabulate(outLanesPerTile) { i =>
                    s_axis.tdata((i + 1) * 2 - 1, i * 2)
                })
                arr.input.weights_i.valid    := s_axis.tvalid && !capture
                arr.input.weights_i.bits     := weightVec
                arr.input.activation_i.valid := !capture
                arr.input.activation_i.bits  := actRdData
                arr.input.nAcc.valid         := !capture
                arr.input.nAcc.bits          := cmpRows
                s_axis.tready                := arr.input.weights_i.ready && !capture

                val canStep = arr.input.weights_i.fire
                actRdAddr := Mux(canStep, activeIdx + 1.U, activeIdx)(log2Ceil(maxN) - 1, 0)
                when(canStep) {
                    activeIdx := activeIdx + 1.U
                }

                when(capture) {
                    tileAccums       := arr.output.bits.accum
                    arr.output.ready := true.B
                    mergeBatchCtr    := 0.U
                    cState           := cWrite
                }
            }

            is(cWrite) {
                // Sequentially write one outBram entry per batch row.
                val writeVec = Wire(Vec(outLanesPerTile, SInt(outAccWidth.W)))
                for (i <- 0 until outLanesPerTile) {
                    writeVec(i) := tileAccums(mergeBatchCtr)(i).asSInt
                }
                val addr = (mergeBatchCtr * numColTiles + colTileCtr)(log2Ceil(outBramDepth) - 1, 0)
                outBram.write(addr, writeVec)

                val next = mergeBatchCtr + 1.U
                when(next === batchSize.U) {
                    mergeBatchCtr := 0.U
                    cState        := cReset
                }.otherwise {
                    mergeBatchCtr := next
                }
            }

            is(cReset) {
                // Hard-reset the array so the next col tile starts clean.
                arrLocalReset := true.B

                val nextCol = colTileCtr + 1.U
                when(nextCol === numColTiles) {
                    cState := cDone
                }.otherwise {
                    colTileCtr := nextCol
                    activeIdx  := 0.U
                    actRdAddr  := 0.U          // prefetch addr for cStream first cycle
                    cState     := cStream
                }
            }

            is(cDone) {
                handler.computeDone := true.B
                state               := sIdle
            }
        }
    }

    // ─── sStore: walk outBram and emit on m_axis, chunked by sub-beats ───────
    when(state === sStore) {
        val currentEntryIdx = (storeBatchCtr * numColTiles + storeColCtr)(log2Ceil(outBramDepth) - 1, 0)
        val isLastSubBeat   = storeSubBeatCtr === (outSubBeats - 1).U
        val advanceEntry    = m_axis.tready && isLastSubBeat
        val nextEntryIdx    = (currentEntryIdx + 1.U)(log2Ceil(outBramDepth) - 1, 0)

        // Prefetch BRAM addr like sStore in the prior Core: stay if stalled, advance on entry fire.
        outRdAddr := Mux(advanceEntry, nextEntryIdx, currentEntryIdx)

        // Slice the current entry into the active sub-beat. Each lane is sign-extended
        // from outAccWidth to outLaneWidth so the beat width hits a clean power of two.
        val baseIdx = (storeSubBeatCtr * effOutBeatLanes.U)(log2Ceil(outLanesPerTile + 1) - 1, 0)
        val laneBits = VecInit((0 until effOutBeatLanes).map { lane =>
            outRdData(baseIdx + lane.U).pad(outLaneWidth).asUInt
        })
        m_axis.tdata  := Cat(laneBits.reverse)
        m_axis.tvalid := true.B
        m_axis.tlast  := (storeBeatCtr + 1.U) === storeTotalBeats

        when(m_axis.tready) {
            val nextBeat = storeBeatCtr + 1.U
            storeBeatCtr := nextBeat

            when(isLastSubBeat) {
                storeSubBeatCtr := 0.U
                val nextCol = storeColCtr + 1.U
                when(nextCol === numColTiles) {
                    storeColCtr := 0.U
                    storeBatchCtr := storeBatchCtr + 1.U
                }.otherwise {
                    storeColCtr := nextCol
                }
            }.otherwise {
                storeSubBeatCtr := storeSubBeatCtr + 1.U
            }

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
