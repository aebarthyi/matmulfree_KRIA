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
