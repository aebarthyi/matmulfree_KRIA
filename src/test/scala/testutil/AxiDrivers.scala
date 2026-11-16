package testutil

import chisel3._
import chisel3.simulator.PeekPokeAPI._
import bus.{AxiSlaveIO, AxisMasterIO}

/**
  * Cycle-level AXI4 master driving a slave-side [[AxiSlaveIO]] in the Verilator specs.
  * Single-beat transactions only (bursts are what the handler rejects, so `awlen`/`arlen`
  * exist to provoke that). `clock` is the clock the port is sampled on: `dut.clock`, or
  * `dut.aclk` for CoreTop.
  */
class AxiMaster(port: AxiSlaveIO, clock: Clock) {
  private val strbAll = (BigInt(1) << port.strbWidth) - 1

  /** Deassert every master-driven valid/ready and zero the IDs (call before reset). */
  def idle(): Unit = {
    port.awvalid.poke(false.B)
    port.wvalid.poke(false.B)
    port.bready.poke(false.B)
    port.arvalid.poke(false.B)
    port.rready.poke(false.B)
    port.awid.poke(0.U)
    port.arid.poke(0.U)
  }

  /** Issue a single-beat write. Returns the BRESP code. */
  def write(addr: BigInt, data: BigInt, awlen: Int = 0, maxCycles: Int = 50): Int = {
    port.awid.poke(0.U)
    port.awaddr.poke(addr.U)
    port.awlen.poke(awlen.U)
    port.awsize.poke(4.U)   // 16 bytes per beat (log2 128/8)
    port.awburst.poke(1.U)  // INCR
    port.awvalid.poke(true.B)
    port.wdata.poke(data.U)
    port.wstrb.poke(strbAll.U)
    port.wlast.poke(true.B)
    port.wvalid.poke(true.B)
    port.bready.poke(true.B)

    var awPending = true; var wPending = true; var bPending = true
    var resp = 0; var done = false; var cycles = 0
    while (!done && cycles < maxCycles) {
      val awFires = awPending && port.awready.peek().litToBoolean
      val wFires  = wPending  && port.wready.peek().litToBoolean
      val bFires  = bPending  && port.bvalid.peek().litToBoolean
      if (bFires) { resp = port.bresp.peek().litValue.toInt; done = true }
      clock.step()
      if (awFires) { port.awvalid.poke(false.B); awPending = false }
      if (wFires)  { port.wvalid.poke(false.B);  wPending  = false }
      if (bFires)  { port.bready.poke(false.B);  bPending  = false }
      cycles += 1
    }
    require(done, s"axiWrite to 0x${addr.toString(16)} timed out")
    port.awvalid.poke(false.B)
    port.wvalid.poke(false.B)
    port.bready.poke(false.B)
    resp
  }

  /** Issue a single-beat read. Returns (RDATA, RRESP). */
  def read(addr: BigInt, arlen: Int = 0, maxCycles: Int = 50): (BigInt, Int) = {
    port.arid.poke(0.U)
    port.araddr.poke(addr.U)
    port.arlen.poke(arlen.U)
    port.arsize.poke(4.U)
    port.arburst.poke(1.U)
    port.arvalid.poke(true.B)
    port.rready.poke(true.B)

    var arPending = true; var rPending = true
    var data = BigInt(0); var resp = 0; var done = false; var cycles = 0
    while (!done && cycles < maxCycles) {
      val arFires = arPending && port.arready.peek().litToBoolean
      val rFires  = rPending  && port.rvalid.peek().litToBoolean
      if (rFires) {
        data = port.rdata.peek().litValue
        resp = port.rresp.peek().litValue.toInt
        done = true
      }
      clock.step()
      if (arFires) { port.arvalid.poke(false.B); arPending = false }
      if (rFires)  { port.rready.poke(false.B);  rPending  = false }
      cycles += 1
    }
    require(done, s"axiRead from 0x${addr.toString(16)} timed out")
    port.arvalid.poke(false.B)
    port.rready.poke(false.B)
    (data, resp)
  }
}

object AxisSink {
  /** Hold `tready` high and collect `count` beats from an AXI-Stream master port. */
  def pull(port: AxisMasterIO, clock: Clock, count: Int, maxCycles: Int): Seq[BigInt] = {
    port.tready.poke(true.B)
    val beats  = scala.collection.mutable.ArrayBuffer.empty[BigInt]
    var cycles = 0
    while (beats.length < count && cycles < maxCycles) {
      if (port.tvalid.peek().litToBoolean) beats += port.tdata.peek().litValue
      clock.step()
      cycles += 1
    }
    port.tready.poke(false.B)
    require(beats.length == count, s"m_axis pull: got ${beats.length}/$count beats")
    beats.toSeq
  }
}

object SimWait {
  /** Step `clock` until `signal` is high; fail after `maxCycles`. */
  def untilHigh(signal: Bool, clock: Clock, maxCycles: Int, what: String): Unit = {
    var c = 0
    while (!signal.peek().litToBoolean && c < maxCycles) { clock.step(); c += 1 }
    require(signal.peek().litToBoolean, s"$what never asserted within $maxCycles cycles")
  }
}
