package core

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CoreConfigSpec extends AnyFreeSpec with Matchers {

  // The three deployable presets, by the name the board flow uses.
  private val m370 = Presets.MMFree_370M
  private val m1_3 = Presets.MMFree_1_3B
  private val m2_7 = Presets.MMFree_2_7B

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
      Presets.deployable.keySet mustBe Set("mmfree_370m", "mmfree_1_3b", "mmfree_2_7b")
    }

    // Candidates must never shadow a shipped preset, and every name must reach byName.
    "the buildable registry is the deployable set plus the open candidates" in {
      Presets.candidates.keySet.intersect(Presets.deployable.keySet) mustBe empty
      Presets.all mustBe (Presets.deployable ++ Presets.candidates)
      (Presets.deployable.keys ++ Presets.candidates.keys)
        .foreach { n => Presets.byName(n) mustBe Presets.all(n) }
    }

    // A 2-bit control must isolate the weight format; any other drift is a confound.
    "each 2-bit candidate differs from its shipped twin in the weight format alone" in {
      Seq("mmfree_370m", "mmfree_1_3b", "mmfree_2_7b").foreach { base =>
        val shipped = Presets.deployable(base)
        val control = Presets.candidates(s"${base}_2bit")
        withClue(s"$base: ") {
          shipped.packedWeights mustBe true
          control.packedWeights mustBe false
          // residentWtBytes is derived from packedWeights, so the twin is an exact copy.
          control mustBe shipped.copy(packedWeights = false)
        }
      }
    }

    // 2 bits/trit vs 1.6 => udmabuf-wt must grow ~1.25x. Guards residentWtBytes tracking
    // packedWeights: a stale packed size here under-allocates the 2-bit overlay.
    "the 2-bit candidates size udmabuf-wt 1.25x their packed twins" in {
      Seq("mmfree_370m", "mmfree_1_3b", "mmfree_2_7b").foreach { base =>
        val packedWt  = Presets.deployable(base).udmabufWtBytes.toDouble
        val controlWt = Presets.candidates(s"${base}_2bit").udmabufWtBytes.toDouble
        withClue(s"$base: packed=$packedWt control=$controlWt ratio=${controlWt / packedWt} ") {
          (controlWt / packedWt) must be >= 1.20
          (controlWt / packedWt) must be <= 1.30
        }
      }
    }

    // outCascadeHeight is part of OutMemUltra's module name, so 1.3B and 2.7B must agree
    // or scripts/clone_preset_hw.sh refuses the 1.3B clone.
    "every deployable preset keeps the shipped output-memory cascade" in {
      Presets.deployable.foreach { case (n, c) =>
        withClue(n) { c.outCascadeHeight mustBe 1 }
      }
      Presets.deployable("mmfree_1_3b").outCascadeHeight mustBe
        Presets.deployable("mmfree_2_7b").outCascadeHeight
    }

    // B8 fits only with the lane accumulators in DSP48s; without dspAccum it hits the LUT wall.
    "every deployable preset carries dspAccum, the condition that makes B8 fit at all" in {
      Presets.deployable.foreach { case (n, c) =>
        withClue(n) {
          c.batchSize   mustBe 8
          c.dspAccum    mustBe true
          c.colBankSize mustBe 4
          c.packedWeights mustBe true
        }
      }
    }

    // Losing any of these on a shipped preset is a regression, not a variant.
    "every deployable preset carries the full optimization stack" in {
      Presets.deployable.foreach { case (name, c) =>
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
      Presets.deployable.foreach { case (name, c) =>
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
        withClue(s"preset shapes=${c.defaultShapes}") {
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

    // What lets scripts/clone_preset_hw.sh build the 1.3B bundle from the 2.7B bitstream.
    "1.3B and 2.7B emit identical RTL and differ only in deployment metadata" in {
      // `model` only feeds manifest fields (bench sweep, target checkpoint, udmabuf-wt
      // sizing) and never reaches the Core — normalize it out.
      m1_3.copy(model = None) mustBe m2_7.copy(model = None)
      m1_3.defaultShapes mustBe "1.3b"
      m2_7.defaultShapes mustBe "2.7b"
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
        PresetManifest.env(n, c).linesIterator.find(_.startsWith("MMFREE_SHAPES=")).get
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

    // An off-rung request snaps DOWN to a rung and would mislabel the build.
    "rejects a plClkMhz that is not a reachable PL0_REF rung" in {
      Seq(215, 220, 225, 230, 240, 275, 295).foreach { mhz =>
        withClue(s"plClkMhz=$mhz snaps down to a rung and mislabels the build") {
          val ex = intercept[IllegalArgumentException] { m2_7.copy(plClkMhz = mhz) }
          ex.getMessage must include("not a reachable PL0_REF rung")
        }
      }
    }

    "every named preset targets a reachable PL0_REF rung" in {
      Presets.all.foreach { case (name, c) =>
        withClue(s"$name asks for ${c.plClkMhz} MHz") {
          CoreConfig.plClkRungs must contain(c.plClkMhz)
        }
      }
    }

    "actCascadeHeight requires an explicit actRamStyle to have any effect" in {
      // Without the ActMem BlackBox the attribute would silently do nothing. m370 is the
      // shipped preset without actRamStyle, so it is the valid base here.
      m370.actRamStyle mustBe ""
      assertThrows[IllegalArgumentException] { m370.copy(actCascadeHeight = 2) }
      assertThrows[IllegalArgumentException] { m370.copy(actRamStyle = "lutram") }
    }

    // The two paths these fix fail by nearly the same margin at maxN=8192; one alone gains nothing.
    "the maxN=8192 presets carry both 250 MHz timing levers, not one" in {
      Seq(m1_3, m2_7).foreach { c =>
        withClue(s"${c.defaultShapes}") {
          c.maxN             mustBe 8192
          c.dspAccum         mustBe true
          c.colBankSize      mustBe 4        // path 2: the route-bound broadcast net
          c.actRamStyle      mustBe "block"  // path 1: the 8-deep RAMB36E2 cascade
          c.actCascadeHeight mustBe 2
        }
      }
      // 370M (maxN=4096, a 4-deep cascade) needs no ActMem cap; it carries dspAccum and
      // colBankSize=4 because B8 needs them to fit.
      m370.maxN             mustBe 4096
      m370.dspAccum         mustBe true
      m370.colBankSize      mustBe 4
      m370.actRamStyle      mustBe ""      // no cascade cap: maxN=4096 does not need it
      m370.actCascadeHeight mustBe 0
    }
  }

  "ModelArch" - {
    // Independent closed form of the per-token weight count (4 square projections, the
    // fused gate_up and down_proj per layer, plus lm_head).
    "projections cover every layer plus lm_head" in {
      Seq(ModelArch.MMfreeLM_370M, ModelArch.MMfreeLM_1_3B, ModelArch.MMfreeLM_2_7B).foreach { a =>
        withClue(a.hfId) {
          a.projections.length mustBe 6 * a.layers + 1
          val h = a.hidden.toLong; val i = a.intermediate.toLong
          a.weightElems mustBe (4 * h * h + h * 2 * i + i * h) * a.layers + h * a.vocab
        }
      }
    }

    "a bench-only config carries no model and no resident weight set" in {
      CoreConfig.Tiny.model mustBe None
      CoreConfig.Tiny.modelName mustBe ""
      CoreConfig.Tiny.residentWtBytes mustBe 0L
    }
  }

  "Derived widths" - {
    "m_axis beat width is a power of two for every preset" in {
      Presets.all.foreach { case (name, c) =>
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
      PresetManifest.env(name, c).linesIterator
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
      e("UDMABUF_OUT_SZ")         mustBe "0x000fa000"
    }

    "env udmabuf sizes equal the JSON byte counts for every preset" in {
      Presets.all.foreach { case (name, c) =>
        val e = envMap(c, name)
        withClue(s"preset '$name'") {
          java.lang.Long.decode(e("UDMABUF_ACT_SZ")) mustBe c.udmabufActBytes
          java.lang.Long.decode(e("UDMABUF_WT_SZ"))  mustBe c.udmabufWtBytes
          java.lang.Long.decode(e("UDMABUF_OUT_SZ")) mustBe c.udmabufOutBytes
        }
      }
    }

    "udmabuf defaults are never smaller than the runtime's worst-case transfer" in {
      Presets.all.foreach { case (name, c) =>
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
      Presets.byName("MMFree_2_7B") mustBe m2_7
      Presets.byName("mmfree_370m") mustBe m370
    }

    "throws on unknown names with a helpful message" in {
      val ex = intercept[IllegalArgumentException] {
        Presets.byName("does-not-exist")
      }
      ex.getMessage must include("does-not-exist")
      ex.getMessage must include("mmfree_2_7b")
    }
  }
}
