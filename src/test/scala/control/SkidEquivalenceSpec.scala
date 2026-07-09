package control

import scala.util.Random
import chisel3._
import chisel3.util._
import testutil.SingleThreadVerilator
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

/** Drives a stock `Queue(1, pipe=true, flow=false, hasFlush=true)` and a
  * `ReplicatedPipeSkid` with identical enq/deq/flush, exposing both sides so the
  * test can assert the skid is behaviorally identical (the max_fanout attribute is
  * a physical hint only, so maxFanout=0 here gives the same logic without it). */
class SkidPair(w: Int) extends Module {
  val io = IO(new Bundle {
    val enqValid = Input(Bool())
    val enqBits  = Input(UInt(w.W))
    val deqReady = Input(Bool())
    val flush    = Input(Bool())
    val qReady = Output(Bool()); val qValid = Output(Bool()); val qBits = Output(UInt(w.W))
    val sReady = Output(Bool()); val sValid = Output(Bool()); val sBits = Output(UInt(w.W))
  })
  val q = Module(new Queue(UInt(w.W), entries = 1, pipe = true, flow = false, hasFlush = true))
  val s = Module(new ReplicatedPipeSkid(UInt(w.W), 0))
  q.io.enq.valid := io.enqValid; q.io.enq.bits := io.enqBits
  q.io.deq.ready := io.deqReady; q.io.flush.get := io.flush
  s.io.enq.valid := io.enqValid; s.io.enq.bits := io.enqBits
  s.io.deq.ready := io.deqReady; s.io.flush := io.flush
  io.qReady := q.io.enq.ready; io.qValid := q.io.deq.valid; io.qBits := q.io.deq.bits
  io.sReady := s.io.enq.ready; io.sValid := s.io.deq.valid; io.sBits := s.io.deq.bits
}

class SkidEquivalenceSpec extends AnyFreeSpec with Matchers with SingleThreadVerilator {
  "ReplicatedPipeSkid matches Queue(1,pipe,flow=false,hasFlush) under random stimulus" in {
    simulate(new SkidPair(8)) { dut =>
      val rng = new Random(0x5c1dL)
      dut.reset.poke(true.B); dut.clock.step(2); dut.reset.poke(false.B)
      for (_ <- 0 until 4000) {
        dut.io.enqValid.poke((rng.nextBoolean()).B)
        dut.io.enqBits.poke(rng.nextInt(256).U)
        dut.io.deqReady.poke((rng.nextBoolean()).B)
        dut.io.flush.poke((rng.nextInt(16) == 0).B)   // occasional flush
        // Combinational outputs must agree this cycle.
        dut.io.sReady.peek().litValue must be(dut.io.qReady.peek().litValue)
        dut.io.sValid.peek().litValue must be(dut.io.qValid.peek().litValue)
        if (dut.io.qValid.peek().litToBoolean)
          dut.io.sBits.peek().litValue must be(dut.io.qBits.peek().litValue)
        dut.clock.step()
      }
    }
  }
}
