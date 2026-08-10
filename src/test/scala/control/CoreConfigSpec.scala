package control

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CoreConfigSpec extends AnyFreeSpec with Matchers {

  // The three deployable presets, by the name the board flow uses.
  private val m370 = CoreConfig.MMFree_370M
  private val m1_3 = CoreConfig.MMFree_1_3B
  private val m2_7 = CoreConfig.MMFree_2_7B

  "CoreConfig.forShape" - {
    "rounds maxN up to the next power of 2" in {
      val c = CoreConfig.forShape(n = 100, m = 64, xDim = 8)
      c.maxN mustBe 128
    }

    "rounds maxM up to a multiple of outLanesPerTile" in {
      val c = CoreConfig.forShape(n = 32, m = 50, xDim = 4, aWidth = 8)
      c.outLanesPerTile mustBe 16
      c.maxM mustBe 64
    }

    "preserves shapes that are already aligned" in {
      val c = CoreConfig.forShape(n = 128, m = 256, xDim = 8, aWidth = 8)
      c.maxN mustBe 128
      c.maxM mustBe 256
    }

    "bumps maxAcc up to maxN" in {
      val c = CoreConfig.forShape(n = 8192, m = 64, xDim = 8, maxAccHint = 256)
      c.maxAcc must be >= c.maxN
    }

    "rejects non-positive shapes" in {
      assertThrows[IllegalArgumentException] { CoreConfig.forShape(n = 0, m = 1) }
      assertThrows[IllegalArgumentException] { CoreConfig.forShape(n = 1, m = 0) }
    }
  }

  "CoreConfig invariants" - {
    "default config has the expected shape" in {
      val c = CoreConfig.Default
      c.aWidth    mustBe 8
      c.xDim      mustBe 8
      c.batchSize mustBe 1
      c.maxN      mustBe 4096
      c.maxM      mustBe 1024
    }

    "Tiny test config matches CoreSpec parameters" in {
      val c = CoreConfig.Tiny
      c.aWidth          mustBe 4
      c.xDim            mustBe 2
      c.batchSize       mustBe 2
      c.maxN            mustBe 8
      c.maxM            mustBe 16
      c.outLanesPerTile mustBe 4
    }

    "the deployable set is exactly the three released checkpoints" in {
      // `deployable` is what the board bundle and the report quote, and that must not grow
      // by accident.
      CoreConfig.deployable.keySet mustBe Set("mmfree_370m", "mmfree_1_3b", "mmfree_2_7b")
    }

    // `candidates` was asserted EMPTY from the B6 retirement (2026-08-07) until the
    // true-systolic experiment opened (2026-08-08). What the empty check was really
    // protecting is asserted directly instead, and holds whether or not a candidate is
    // open: a candidate may never shadow or silently join the deployable set, and every
    // buildable name must resolve to exactly the config it is registered under. When the
    // systolic arm closes — promoted or moved to `exploration` — restore `candidates
    // mustBe empty` here.
    "candidates stay disjoint from the deployable set and every buildable name resolves" in {
      CoreConfig.candidates.keySet.intersect(CoreConfig.deployable.keySet) mustBe empty
      CoreConfig.presets mustBe (CoreConfig.deployable ++ CoreConfig.candidates)
      (CoreConfig.deployable.keys ++ CoreConfig.candidates.keys)
        .foreach { n => CoreConfig.byName(n) mustBe CoreConfig.presets(n) }
    }

    // The open experiment, pinned so the arms stay comparable. Each candidate must differ
    // from the shipped 2.7B in EXACTLY the lever under test and nothing else — a second
    // difference makes the WNS delta unattributable, which is the way an A/B like this
    // usually goes wrong. See the CoreConfig comment above `candidates`.
    //
    // The banked output memory is no longer a lever here: it WON at 250 (+0.020 vs the
    // shipped +0.002) and is carried by every deployable preset, so the arms inherit it and
    // the only differences left are the dataflow and the clock rung.
    "the 300 MHz arms differ from shipped 2.7B only in the lever under test" in {
      val base = CoreConfig.deployable("mmfree_2_7b")
      CoreConfig.candidates("mmfree_2_7b_300") mustBe base.copy(plClkMhz = 300)
      CoreConfig.candidates("mmfree_2_7b_sys") mustBe
        base.copy(colBankSize = 0, trueSystolic = true)
      CoreConfig.candidates("mmfree_2_7b_sys_300") mustBe
        base.copy(colBankSize = 0, trueSystolic = true, plClkMhz = 300)
      // The maximal arm is the deliberate exception to the one-lever rule: it stacks the
      // capture-enable fix and the systolic dataflow to cover 98% of the failing endpoint
      // population, because the open question is whether 300 is reachable AT ALL. Pinned
      // exactly so "stacked on purpose" cannot quietly become "drifted".
      CoreConfig.candidates("mmfree_2_7b_max300") mustBe
        base.copy(colBankSize = 0, trueSystolic = true, plClkMhz = 300, captureMaxFanout = 128)
      // Every arm rides the shipped memory, or the comparison is against a different wall.
      CoreConfig.candidates.foreach { case (n, c) =>
        withClue(n) {
          c.outPipeStyle     mustBe "banked"
          c.outCascadeHeight mustBe 1
        }
      }
    }

    // outPipeStyle changes OutMemUltra's module NAME, which is what keeps two variants from
    // colliding in one emitted directory. Since 2026-08-09 the shipped style is "banked":
    // MEASURED, Vivado absorbed ZERO of the legacy body's four read stages ("FF absorbed: 0"),
    // so the legacy read was a combinational URAM cascade and banking is what actually placed
    // the registers. Pin the shipped value and that the validator still rejects nonsense.
    "outPipeStyle is validated and the shipped style is the banked memory" in {
      CoreConfig.deployable.foreach { case (n, c) =>
        withClue(n) { c.outPipeStyle mustBe "banked" }
      }
      val base = CoreConfig.deployable("mmfree_2_7b")
      an[IllegalArgumentException] must be thrownBy base.copy(outPipeStyle = "pipelined")
      noException must be thrownBy base.copy(outPipeStyle = "legacy")
    }

    // With "banked" there is no hard cascade left to bound — one URAM chain per bank,
    // cascade_height=1 — and the value is baked into OutMemUltra's module name, so the two
    // maxN=8192 presets must agree or scripts/clone_preset_hw.sh refuses the 1.3B clone.
    "every deployable preset keeps the shipped output-memory cascade" in {
      CoreConfig.deployable.foreach { case (n, c) =>
        withClue(n) { c.outCascadeHeight mustBe 1 }
      }
      CoreConfig.deployable("mmfree_1_3b").outCascadeHeight mustBe
        CoreConfig.deployable("mmfree_2_7b").outCascadeHeight
    }

    // captureMaxFanout is an OPEN lever, not a shipped one: MEASURED 2026-08-08 the tile
    // capture enable is 74.2% of what fails 300 MHz, but the fix is a placement/fanout
    // effect that only a full build can price, and nothing has been built with it yet. It
    // must therefore stay 0 on every deployable preset — at 0 the extra cCapture state is
    // not generated at all and the emitted SV is byte-identical to what the shipped
    // bitstreams were built from, which is what scripts/clone_preset_hw.sh hashes.
    "the capture-fanout lever is off on everything that ships" in {
      CoreConfig.deployable.foreach { case (n, c) =>
        withClue(n) { c.captureMaxFanout mustBe 0 }
      }
      val base = CoreConfig.deployable("mmfree_2_7b")
      an[IllegalArgumentException] must be thrownBy base.copy(captureMaxFanout = -1)
      noException must be thrownBy base.copy(captureMaxFanout = 32)
    }

    // trueSystolic and colBankSize are two solutions to the same activation-broadcast
    // fanout; a preset carrying both is a configuration mistake. Pin that the guard FIRES,
    // and — the half that is easy to forget — that it RELEASES on each lever alone.
    "trueSystolic and colBankSize are mutually exclusive, and each is legal alone" in {
      val base = CoreConfig.deployable("mmfree_2_7b")
      an[IllegalArgumentException] must be thrownBy base.copy(trueSystolic = true)  // keeps colBankSize=4
      noException must be thrownBy base.copy(colBankSize = 0, trueSystolic = true)
      noException must be thrownBy base.copy(colBankSize = 4, trueSystolic = false)
    }

    // B8 fits only because the lane accumulators move into DSP48s (~22 pp of LUT at B6);
    // without dspAccum the same array was a ~95% LUT capacity wall. B8 is now DEPLOYABLE, so
    // pin that condition on the shipped set — a shipped preset must not be buildable in the
    // configuration already measured not to fit.
    "every deployable preset carries dspAccum, the condition that makes B8 fit at all" in {
      CoreConfig.deployable.foreach { case (n, c) =>
        withClue(n) {
          c.batchSize   mustBe 8
          c.dspAccum    mustBe true
          c.colBankSize mustBe 4     // 800 spilled lanes need the shortest broadcast domains
          c.packedWeights mustBe true
        }
      }
    }

    // These four fields ARE the conclusion of the optimization program: batching amortizes
    // the weight stream (B4->B6 measured +11.8%, B6->B8 +11.5-15.4% at b=8 and free below it),
    // 1.6-bit packing
    // cuts the binding traffic ~20% (+4.3% at 2.7B b=6), 128-bit m_axis stops STORE being the
    // batch bottleneck, and 250 is the top reachable rung. Any preset that loses one of them
    // is a regression, not a variant — an ablation belongs on the `exploration` branch.
    "every deployable preset carries the full optimization stack" in {
      CoreConfig.deployable.foreach { case (name, c) =>
        withClue(s"preset '$name'") {
          c.batchSize      mustBe 8
          c.outBeatLanes   mustBe 4
          c.packedWeights  mustBe true
          c.plClkMhz       mustBe 250
          c.implDirectives must not be empty      // the aggressive impl flow, needed for 250
        }
      }
    }

    "the a16 / 4-HP-port engine geometry is identical across all three sizes" in {
      CoreConfig.deployable.foreach { case (name, c) =>
        withClue(s"preset '$name'") {
          c.aWidth          mustBe 16
          c.xDim            mustBe 32
          c.signedAct       mustBe true
          c.numInPorts      mustBe 4          // all 4 HP ports
          c.inPortWidth     mustBe 128
          c.axisDataWidth   mustBe 512
          c.outLanesPerTile mustBe 256
          c.outLaneWidth    mustBe 32         // nextPow2(aWidth + log2(maxAcc))
          c.maxM            mustBe 32000      // vocab dominates gate_up at every size
        }
      }
    }

    "each preset spans every projection of its own checkpoint, tile-aligned" in {
      // (hidden, intermediate, vocab) per ridger/MMfreeLM checkpoint.
      val arch = Map(
        m370 -> (1024, 2816, 32000),
        m1_3 -> (2048, 5632, 32000),
        m2_7 -> (2560, 6912, 32000)
      )
      arch.foreach { case (c, (h, inter, vocab)) =>
        withClue(s"preset shapes=${c.shapesHint}") {
          val projs = Seq(
            (h, h),            // i/f/g/o_proj
            (h, 2 * inter),    // fused gate_up
            (inter, h),        // down_proj
            (h, vocab)         // lm_head
          )
          projs.foreach { case (n, m) =>
            withClue(s"projection ${n}x$m") {
              n must be <= c.maxN
              n must be <= c.maxAcc
              m must be <= c.maxM
              m % c.outLanesPerTile mustBe 0   // no padding lanes anywhere
            }
          }
        }
      }
    }

    "maxN is nextPow2 of the largest inner dim, and only it scales with model size" in {
      m370.maxN mustBe 4096          // nextPow2(2816)
      m1_3.maxN mustBe 8192          // nextPow2(5632)
      m2_7.maxN mustBe 8192          // nextPow2(6912)
      Seq(m370, m1_3, m2_7).foreach(c => c.maxAcc mustBe c.maxN)
    }

    // This is what lets scripts/clone_preset_hw.sh assemble the 1.3B bundle from the 2.7B
    // bitstream in seconds instead of a second 50-minute Vivado run. If it ever stops
    // holding, that script must stop being used for this pair.
    "1.3B and 2.7B emit identical RTL and differ only in deployment metadata" in {
      // shapesHint / modelName / residentWtBytes are manifest fields (bench sweep, target
      // checkpoint, udmabuf-wt sizing) and never reach the Core — normalize them out.
      m1_3.copy(shapesHint = "", modelName = "", residentWtBytes = 0) mustBe
        m2_7.copy(shapesHint = "", modelName = "", residentWtBytes = 0)
      m1_3.shapesHint mustBe "1.3b"
      m2_7.shapesHint mustBe "2.7b"
      m1_3.modelName  mustBe "ridger/MMfreeLM-1.3B"
      m2_7.modelName  mustBe "ridger/MMfreeLM-2.7B"
      // …but the resident weight set (-> UDMABUF_WT_SZ) DOES scale with model size, so the
      // same bitstream needs a bigger wt buffer for 2.7B than for 1.3B.
      m2_7.residentWtBytes must be > m1_3.residentWtBytes
      m2_7.udmabufWtBytes  must be > m1_3.udmabufWtBytes
      // …and that maxN=8192 engine still covers the 370M projections.
      Seq((1024, 1024), (1024, 5632), (2816, 1024), (1024, 32000)).foreach { case (n, m) =>
        withClue(s"370M ${n}x$m on the large engine") {
          n must be <= m1_3.maxN; m must be <= m1_3.maxM
          (m % m1_3.outLanesPerTile) mustBe 0
        }
      }
    }

    "each preset carries its default bench sweep in the manifest" in {
      def shapes(c: CoreConfig, n: String) =
        c.presetEnv(n).linesIterator.find(_.startsWith("MMFREE_SHAPES=")).get
      shapes(m370, "mmfree_370m") mustBe "MMFREE_SHAPES=370m"
      shapes(m1_3, "mmfree_1_3b") mustBe "MMFREE_SHAPES=1.3b"
      shapes(m2_7, "mmfree_2_7b") mustBe "MMFREE_SHAPES=2.7b"
    }

    "rejects maxN > maxAcc" in {
      assertThrows[IllegalArgumentException] {
        CoreConfig(maxAcc = 16, maxN = 32)
      }
    }

    "rejects maxM not a multiple of outLanesPerTile" in {
      assertThrows[IllegalArgumentException] {
        // outLanesPerTile = 8*4 = 32; 100 % 32 != 0
        CoreConfig(aWidth = 8, xDim = 8, batchSize = 1, maxN = 32, maxM = 100, maxAcc = 32)
      }
    }

    // PL0_REF only exists on discrete rungs and the PS IP snaps anything else DOWN, so an
    // off-rung plClkMhz produces a bitstream constrained at the rung while every report,
    // manifest and CSV carries the request. That went unnoticed for weeks: every "_230"
    // preset was really a 199.998 MHz build, and two presets 5 MHz apart came out
    // byte-identical. These two cases are the guard that makes it a build failure instead.
    "rejects a plClkMhz that is not a reachable PL0_REF rung" in {
      Seq(215, 220, 225, 230, 240, 275, 295).foreach { mhz =>
        withClue(s"plClkMhz=$mhz snaps down to a rung and mislabels the build") {
          val ex = intercept[IllegalArgumentException] { m2_7.copy(plClkMhz = mhz) }
          ex.getMessage must include("not a reachable PL0_REF rung")
        }
      }
    }

    "every named preset targets a reachable PL0_REF rung" in {
      CoreConfig.presets.foreach { case (name, c) =>
        withClue(s"$name asks for ${c.plClkMhz} MHz") {
          CoreConfig.plClkRungs must contain(c.plClkMhz)
        }
      }
    }

    "actCascadeHeight requires an explicit actRamStyle to have any effect" in {
      // The attribute rides on the ActMem BlackBox; with the inferred SyncReadMem there is
      // nowhere to put it, so asking for one silently does nothing. Fail instead.
      // m370 is the one default that does NOT set actRamStyle, so it is the valid base here.
      m370.actRamStyle mustBe ""
      assertThrows[IllegalArgumentException] { m370.copy(actCascadeHeight = 2) }
      assertThrows[IllegalArgumentException] { m370.copy(actRamStyle = "lutram") }
    }

    // The two paths these fix failed 0.005 ns apart at maxN=8192, so a build carrying only
    // one of them moves WNS by 0.005 ns and wastes 50 minutes of Vivado. Pin the pairing.
    "the maxN=8192 presets carry both 250 MHz timing levers, not one" in {
      Seq(m1_3, m2_7).foreach { c =>
        withClue(s"${c.shapesHint}") {
          c.maxN             mustBe 8192
          c.dspAccum         mustBe true     // frees ~22 pp LUT / ~34 pp CARRY8
          c.colBankSize      mustBe 4        // path 2: the route-bound broadcast net
          c.actRamStyle      mustBe "block"  // path 1: the 8-deep RAMB36E2 cascade
          c.actCascadeHeight mustBe 2
        }
      }
      // 370M is maxN=4096, so it still carries NEITHER cascade lever — a 4-deep RAMB36E2
      // chain is not the path that fails there. It does now carry dspAccum and the short
      // broadcast domains, which is not a change of mind about its timing: at B6 it closed
      // 250 on banking alone (dspAccum = false, colBankSize = 8), but B8 spills 800 lanes to
      // fabric instead of 288, and dspAccum is the condition that makes that fit at all.
      m370.maxN             mustBe 4096
      m370.dspAccum         mustBe true
      m370.colBankSize      mustBe 4
      m370.actRamStyle      mustBe ""      // no cascade cap: maxN=4096 does not need it
      m370.actCascadeHeight mustBe 0
    }
  }

  "Derived widths" - {
    "m_axis beat width is a power of two for every preset" in {
      CoreConfig.presets.foreach { case (name, c) =>
        val ok = (c.outBeatWidth & (c.outBeatWidth - 1)) == 0
        withClue(s"preset '$name' has non-pow2 outBeatWidth=${c.outBeatWidth}") {
          ok mustBe true
        }
      }
    }

    "outLaneWidth is the next pow2 >= outAccWidth" in {
      val c = CoreConfig.Default
      c.outLaneWidth must be >= c.outAccWidth
      c.outLaneWidth must be < (2 * c.outAccWidth)
    }
  }

  "Preset manifest (preset.env / preset.json)" - {
    // Parse the flat KEY=VAL env into a map for assertions.
    def envMap(c: CoreConfig, name: String): Map[String, String] =
      c.presetEnv(name).linesIterator
        .filterNot(l => l.trim.isEmpty || l.trim.startsWith("#"))
        .map { l => val i = l.indexOf('='); l.substring(0, i) -> l.substring(i + 1) }
        .toMap

    "carries the geometry and batch the board flow expects" in {
      val e = envMap(m370, "mmfree_370m")
      e("MMFREE_AWIDTH")          mustBe "16"
      e("MMFREE_XDIM")            mustBe "32"
      e("MMFREE_SIGNED")          mustBe "1"
      e("MMFREE_SHAPES")          mustBe "370m"
      e("MMFREE_BATCH")           mustBe "8"
      e("MMFREE_PACKED_WEIGHTS")  mustBe "1"
      e("NUM_DMA")                mustBe "4"
      e("MM2S_WIDTH")             mustBe "128"
      e("S2MM_WIDTH")             mustBe "128"       // outBeatLanes=4 * 32-bit lane
      e("PL_CLK_MHZ")             mustBe "250"
      e("UDMABUF_ACT_SZ")         mustBe "0x00010000" // maxN*portBytes = 4096*16 = 64 KiB
      // OUT = batch*numColTilesMax*outLanesPerTile*outLaneBytes = 8*125*256*4, page-up.
      // This grew with the B8 promotion (B6 was 0x000bc000) and the DT overlay carries it,
      // so a B8 bitstream on a B6 overlay is refused at mmfree_lib_open rather than silently
      // drained into a buffer 254 KiB too small.
      e("UDMABUF_OUT_SZ")         mustBe "0x000fa000"
    }

    "env udmabuf sizes equal the JSON byte counts for every preset" in {
      CoreConfig.presets.foreach { case (name, c) =>
        val e = envMap(c, name)
        withClue(s"preset '$name'") {
          java.lang.Long.decode(e("UDMABUF_ACT_SZ")) mustBe c.udmabufActBytes
          java.lang.Long.decode(e("UDMABUF_WT_SZ"))  mustBe c.udmabufWtBytes
          java.lang.Long.decode(e("UDMABUF_OUT_SZ")) mustBe c.udmabufOutBytes
        }
      }
    }

    "udmabuf defaults are never smaller than the runtime's worst-case transfer" in {
      CoreConfig.presets.foreach { case (name, c) =>
        withClue(s"preset '$name'") {
          // act: maxN beats * portBytes; wt: that * col tiles; out: batch drain.
          c.udmabufActBytes must be >= c.maxN.toLong * c.portBytes
          c.udmabufWtBytes  must be >= c.maxN.toLong * c.numColTilesMax * c.portBytes
          c.udmabufOutBytes must be >= c.batchSize.toLong * c.numColTilesMax * c.outLanesPerTile * c.outLaneBytes
        }
      }
    }
  }

  "byName" - {
    "is case-insensitive" in {
      CoreConfig.byName("MMFree_2_7B") mustBe m2_7
      CoreConfig.byName("mmfree_370m") mustBe m370
    }

    "throws on unknown names with a helpful message" in {
      val ex = intercept[IllegalArgumentException] {
        CoreConfig.byName("does-not-exist")
      }
      ex.getMessage must include("does-not-exist")
      ex.getMessage must include("mmfree_2_7b")
    }
  }
}
