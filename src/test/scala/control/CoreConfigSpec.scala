package control

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CoreConfigSpec extends AnyFreeSpec with Matchers {

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

    "K26_LMHead config matches the LM-head bandwidth target" in {
      val c = CoreConfig.K26_LMHead
      c.xDim         mustBe 64
      c.batchSize    mustBe 1
      c.maxN         mustBe 4096
      c.maxM         mustBe 32000
      c.outBeatLanes mustBe 32
      // 512-bit s_axis, 1024-bit m_axis after chunking.
      c.axisDataWidth mustBe 512
      c.outBeatWidth  mustBe 1024
    }

    "K26_MMFree370M covers every MMfreeLM-370M projection shape" in {
      val c = CoreConfig.K26_MMFree370M
      c.xDim          mustBe 64
      c.batchSize     mustBe 1
      c.numInPorts    mustBe 4      // all 4 HP ports
      c.inPortWidth   mustBe 128
      c.axisDataWidth mustBe 512
      c.outBeatWidth  mustBe 32     // output stays on DMA0's 32-bit S2MM
      // Distinct (N inner, M out) projections of the 370M model — each must
      // fit the config and land tile-aligned (no padding lanes).
      val shapes = Seq(
        (1024, 1024),   // i/f/g/o_proj
        (1024, 5632),   // fused gate_up
        (2816, 1024),   // down_proj
        (1024, 32000)   // lm_head
      )
      shapes.foreach { case (n, m) =>
        withClue(s"projection ${n}x$m") {
          n must be <= c.maxN
          n must be <= c.maxAcc
          m must be <= c.maxM
          m % c.outLanesPerTile mustBe 0
        }
      }
    }

    "K26_MMFree370M_A16 is the signed int16 sibling, same output geometry" in {
      val a16 = CoreConfig.K26_MMFree370M_A16
      val a8  = CoreConfig.K26_MMFree370M
      a16.aWidth        mustBe 16
      a16.xDim          mustBe 32
      a16.signedAct     mustBe true
      a16.numInPorts    mustBe 4          // still all 4 HP ports
      a16.axisDataWidth mustBe 512
      // identical compute width + output format to the int8 preset
      a16.outLanesPerTile mustBe a8.outLanesPerTile   // 256 either way
      a16.outBeatWidth    mustBe a8.outBeatWidth       // 32-bit m_axis unchanged
      a16.outLaneWidth    mustBe 32                     // nextPow2(28)
      // every projection still fits + tile-aligned
      Seq((1024,1024),(1024,5632),(2816,1024),(1024,32000)).foreach { case (n,m) =>
        withClue(s"${n}x$m") { (m % a16.outLanesPerTile) mustBe 0; n must be <= a16.maxN }
      }
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

    "K26_LMHead chunking yields 8 sub-beats per col tile" in {
      val c = CoreConfig.K26_LMHead
      c.outSubBeats mustBe 8
    }
  }

  "Preset manifest (preset.env / preset.json)" - {
    // Parse the flat KEY=VAL env into a map for assertions.
    def envMap(c: CoreConfig, name: String): Map[String, String] =
      c.presetEnv(name).linesIterator
        .filterNot(l => l.trim.isEmpty || l.trim.startsWith("#"))
        .map { l => val i = l.indexOf('='); l.substring(0, i) -> l.substring(i + 1) }
        .toMap

    "carries the a16 geometry the board flow expects" in {
      val e = envMap(CoreConfig.K26_MMFree370M_A16, "k26_mmfree370m_a16")
      e("MMFREE_AWIDTH")    mustBe "16"
      e("MMFREE_XDIM")      mustBe "32"
      e("MMFREE_SIGNED")    mustBe "1"
      e("MMFREE_SHAPES")    mustBe "370m"
      e("NUM_DMA")          mustBe "4"
      e("MM2S_WIDTH")       mustBe "128"
      e("S2MM_WIDTH")       mustBe "32"
      e("PL_CLK_MHZ")       mustBe "250"
      e("UDMABUF_ACT_SZ")   mustBe "0x00010000"   // maxN*portBytes = 4096*16 = 64 KiB
      e("UDMABUF_OUT_SZ")   mustBe "0x00020000"   // 125*256*4, page-up = 128 KiB
    }

    "scales S2MM width and the output udmabuf with batch" in {
      val e = envMap(CoreConfig.K26_MMFree370M_A16_B6, "k26_mmfree370m_a16_b6")
      e("MMFREE_BATCH")     mustBe "6"
      e("S2MM_WIDTH")       mustBe "128"           // outBeatLanes=4 * 32-bit lane
      // OUT = batch*numColTilesMax*outLanesPerTile*outLaneBytes = 6*125*256*4, page-up
      e("UDMABUF_OUT_SZ")   mustBe "0x000bc000"
    }

    "legacy bench preset keeps single-port 100 MHz geometry" in {
      val e = envMap(CoreConfig.K26_Bench, "k26_bench")
      e("NUM_DMA")     mustBe "1"
      e("MM2S_WIDTH")  mustBe "64"
      e("PL_CLK_MHZ")  mustBe "100"
      e("MMFREE_SHAPES") mustBe "pow2"
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
      CoreConfig.byName("DEFAULT")     mustBe CoreConfig.Default
      CoreConfig.byName("k26_LM_Head") mustBe CoreConfig.K26_LMHead
    }

    "throws on unknown names with a helpful message" in {
      val ex = intercept[IllegalArgumentException] {
        CoreConfig.byName("does-not-exist")
      }
      ex.getMessage must include("does-not-exist")
      ex.getMessage must include("default")
    }
  }
}
