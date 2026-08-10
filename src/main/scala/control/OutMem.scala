package control

import chisel3._
import chisel3.util._

/**
  * URAM-backed output memory (1 write port, 1 read port) with an internal read
  * pipeline, emitted as a BlackBox so we can attach the `(* ram_style = "ultra" *)`
  * attribute that forces Vivado to infer UltraRAM — something CIRCT cannot put on
  * a `SyncReadMem` (its `AttributeAnnotation` only targets modules/wires/regs).
  *
  * Why a BlackBox instead of post-processing the generated SV: the deep output
  * memory (batchSize * numColTilesMax * outSubBeats rows) maps to a long read
  * cascade. URAM has a hardware-pipelined cascade, but the pipeline registers must
  * live *inside* the memory module to pack into the cascade — external registers
  * (in Core) get SRL-optimized and never pipeline the cascade. Owning the module
  * lets us put `latency` registered stages right on the read path.
  *
  * Read semantics: `rdata` is the value at `raddr` delayed by `latency` cycles,
  * advanced only when `ren` is high (so the store FSM can stall on backpressure
  * without corrupting the pipeline). Write is synchronous on `wen`, delayed by
  * `writeLat` extra registered stages (see below).
  *
  * Why the write port is also pipelined (`writeLat`): the drain engine feeds a
  * combinational write address (`drainBatchCtr * numColTiles + …`, Core.scala)
  * straight into the URAM. As the output memory deepens with batchSize (B=6 →
  * 48000 rows), that address arithmetic + the route to the first URAM is the
  * 250 MHz critical path (post-route WNS −0.74 ns at B=6/B=8, congestion-free).
  * `writeLat` registered stages on waddr/wen/wdata — like `rpipe` for reads —
  * terminate the arithmetic at flops adjacent to the URAM so the deep cascade's
  * CAS_IN_ADDR starts from a register, not combinational logic routed across the
  * die. The drain writes during COMPUTE and the matching read happens in a later
  * STORE instruction, so the extra write latency is hidden (Core extends the
  * drain-completion gate by `writeLat` so a STORE issued off the IRQ still sees
  * the last write landed).
  *
  * The module name encodes the geometry + latencies so distinct instantiations
  * get distinct inline SV (BlackBoxInline keys on the file/module name).
  */
class OutMemUltra(depth: Int, width: Int, latency: Int, writeLat: Int = 1, cascadeHeight: Int = 4,
                  bankDepth: Int = 0)
    extends BlackBox with HasBlackBoxInline {
  require(depth > 0 && width > 0 && latency >= 1 && writeLat >= 1 && cascadeHeight >= 1)
  require(bankDepth >= 0, s"bankDepth=$bankDepth must be >= 0 (0 = auto)")
  private val aw = log2Ceil(depth)

  // ── Banking geometry ──────────────────────────────────────────────────────
  // One URAM288 is 4096 deep, so a bank of 4096*cascadeHeight rows is exactly one
  // hard cascade chain and no chain ever spans banks. bankDepth is overridable so a
  // simulation-sized memory can still be forced into several banks — otherwise every
  // test config lands in one bank and the mux, which is the whole point of this
  // style, is never exercised.
  private val effBankDepth = if (bankDepth > 0) bankDepth else 4096 * cascadeHeight
  private val nBanks       = (depth + effBankDepth - 1) / effBankDepth
  // A single bank is sized to the real depth, not rounded up to effBankDepth — otherwise
  // a small (simulation) memory declares a full 4096-row array and infers a URAM for
  // eight rows.
  private val declBankDepth = if (nBanks <= 1) depth else effBankDepth
  private val bankAw        = log2Ceil(declBankDepth)
  private val selW         = if (nBanks <= 1) 0 else log2Ceil(nBanks)
  require(nBanks <= 1 || latency >= 2,
    s"banking needs latency >= 2 (one stage for the bank output, one for the mux); got $latency")

  val io = IO(new Bundle {
    val clk   = Input(Clock())
    val waddr = Input(UInt(aw.W))
    val wen   = Input(Bool())
    val wdata = Input(UInt(width.W))
    val raddr = Input(UInt(aw.W))
    val ren   = Input(Bool())
    val rdata = Output(UInt(width.W))
  })

  // The `_pbanked` tag is retained from when this module had several selectable read
  // bodies. It is load-bearing, not vestigial: the shipped bitstreams were built from
  // SV carrying this exact module name, and scripts/clone_preset_hw.sh accepts the 1.3B
  // clone only if the emitted SV still hashes the same. Do not "tidy" it away.
  private val styleTag = s"_pbanked${nBanks}x$effBankDepth"
  override val desiredName =
    s"OutMemUltra_${depth}x${width}_l${latency}_w${writeLat}_c${cascadeHeight}$styleTag"

  private val nbPipe = latency - 1

  // ── WHY THE READ IS BANKED, and why it is not just a pipelined cascade ────
  // MEASURED 2026-08-08. The obvious implementation — one deep URAM array with
  // `latency` read-pipeline stages after it — is what this module used to be, and
  // Vivado mapped it with
  //   WARNING [Synth 8-6057] Memory "mem_reg" ... has no pipeline registers.
  //   FF provided for Pipeline: 0      FF absorbed: 0
  // i.e. NONE of the read stages were taken into the URAM matrix, leaving the read a
  // fully combinational CAS_IN_DOUT -> CAS_OUT_DOUT run across the cascade plus the
  // group mux. That was the design's worst path at 300 MHz (-0.315 ns, 82% logic),
  // and the module comment claiming those registers packed into the cascade was
  // simply wrong for as long as it existed.
  //
  // Two fixes were tried and REJECTED, both worth knowing before re-litigating this:
  //   * Shortening the cascade (cascadeHeight 4 -> 2) bought +0.028 ns and made TNS
  //     worse. URAM288 CLK->DOUT is ~1.837 ns before any cascade, so the chain was
  //     never where the time went.
  //   * Rewriting to the UG901 single-always-block template changed absorption not at
  //     all (still `FF provided: 0`) and cost 0.3 ns, because dropping
  //     `shreg_extract="no"` let the stages become SRL16Es. That attribute is
  //     load-bearing exactly as its original comment said.
  // The conclusion those two produced is the reason for the code below: VIVADO WILL
  // NOT PUT THESE REGISTERS IN THE CASCADE, whatever you write. So stop asking, and
  // place them structurally instead.
  //
  // Worth +1.068 ns post-synth / +0.018 ns post-route at 250 on the maxN=8192
  // geometry, for +240 LUT, +4 registers, the same URAM count and NO latency change.
  // On maxN=4096 (370M) it is worth ~nothing — that geometry's limiter is the array,
  // not this memory — so it is carried there for uniformity only.

  // ── The banked read ───────────────────────────────────────────────────────
  // Place the registers by construction instead of asking for them. The address
  // space is split into `nBanks` explicit sub-arrays, each exactly one cascade chain
  // deep, and the read is:
  //
  //     cycle 1   every bank registers its own output      (dout_q, one per bank)
  //               the bank select registers alongside it   (sel_q, same enable)
  //     cycle 2   the mux over REGISTERED bank outputs registers   (mux_q)
  //     cycle 3.. the remaining latency-2 stages, unchanged
  //
  // No cascade hop, no mux, and no cross-die route shares a cycle with the URAM
  // access any more. The arithmetic from the path this replaced: URAM CLK->DOUT is
  // 1.837 ns on its own — 55% of a 3.333 ns period — and the unbanked path added
  // 0.833 ns of cascade, 0.246 ns of mux and 0.653 ns of route on top, for 3.569 ns.
  // Banking leaves the 1.837 ns alone with its route to a local flop (~2.2 ns), and
  // gives the mux its own cycle.
  //
  // Total read latency is UNCHANGED at `latency` — the two structural stages replace
  // two of the existing pass-through stages rather than adding to them — so Core's
  // outReadLat accounting, primeCtr and drain flush are untouched. The tail stages
  // keep shreg_extract="no"; the UG901 experiment above proved it is load-bearing.
  private val bankedBody = {
    val wl      = writeLat - 1
    val tail    = latency - (if (nBanks <= 1) 1 else 2)
    val muxSlots = if (selW == 0) 1 else 1 << selW

    // Address split. bankAw == 0 is the degenerate one-row-per-bank case: there is no
    // row index at all, the whole address is bank select, and the array is mem[0:0] —
    // emitting raddr[-1:0] there is a malformed slice, not a narrow one.
    def bankSel(a: String) = if (nBanks <= 1) ""  else s"$a[${aw - 1}:$bankAw]"
    def rowSel(a: String)  =
      if (nBanks <= 1)   a
      else if (bankAw == 0) "1'b0"
      else s"$a[${bankAw - 1}:0]"
    val wAddr = s"waddr_q[$wl]"

    val banks =
      s"""  genvar g;
         |  generate
         |    for (g = 0; g < $nBanks; g = g + 1) begin : bank
         |      (* ram_style = "ultra", cascade_height = $cascadeHeight *) reg [${width - 1}:0] mem [0:${declBankDepth - 1}];
         |      reg [${width - 1}:0] dout_q;
         |      always @(posedge clk) begin
         |        if (wen_q[$wl]${if (nBanks <= 1) "" else s" && (${bankSel(wAddr)} == g)"})
         |          mem[${rowSel(wAddr)}] <= wdata_q[$wl];
         |        if (ren) dout_q <= mem[${rowSel("raddr")}];
         |      end
         |    end
         |  endgenerate
         |""".stripMargin

    // Flatten to a packed vector sized to the full 2^selW so a non-power-of-two bank
    // count cannot produce an out-of-range part-select (which reads X in simulation
    // and would show up as sporadic garbage rather than a clean failure).
    val mux =
      if (nBanks <= 1)
        s"""  wire [${width - 1}:0] stage1 = bank[0].dout_q;
           |""".stripMargin
      else
        s"""  wire [${muxSlots * width - 1}:0] bank_dout;
           |  generate
           |    for (g = 0; g < $muxSlots; g = g + 1) begin : fan
           |      if (g < $nBanks) assign bank_dout[g*$width +: $width] = bank[g].dout_q;
           |      else             assign bank_dout[g*$width +: $width] = ${width}'d0;
           |    end
           |  endgenerate
           |
           |  (* shreg_extract = "no" *) reg [${selW - 1}:0] sel_q;
           |  (* shreg_extract = "no" *) reg [${width - 1}:0] mux_q;
           |  always @(posedge clk) if (ren) begin
           |    sel_q <= ${bankSel("raddr")};
           |    mux_q <= bank_dout[sel_q*$width +: $width];
           |  end
           |  wire [${width - 1}:0] stage1 = mux_q;
           |""".stripMargin

    val tailStages =
      if (tail <= 0) s"  assign rdata = stage1;\n"
      else
        s"""  (* shreg_extract = "no" *) reg [${width - 1}:0] rpipe [0:${tail - 1}];
           |  integer i;
           |  always @(posedge clk) if (ren) begin
           |    rpipe[0] <= stage1;
           |    for (i = 1; i < $tail; i = i + 1) rpipe[i] <= rpipe[i - 1];
           |  end
           |  assign rdata = rpipe[${tail - 1}];
           |""".stripMargin

    banks + "\n" + mux + "\n" + tailStages
  }

  // ⚠️ EVERY character emitted below is load-bearing. scripts/clone_preset_hw.sh hashes
  // the emitted SV to decide whether the 1.3B clone may reuse the 2.7B bitstream, so even
  // an added comment line inside these strings makes it refuse a legitimate clone — and a
  // shipped bitstream stops matching the source it was built from. Change the Scala
  // comments freely; change the generated text only with a rebuild.
  setInline(
    s"$desiredName.sv",
    s"""// Generated by control.OutMemUltra — URAM output memory, read latency=$latency.
       |module $desiredName(
       |  input                clk,
       |  input  [${aw - 1}:0] waddr,
       |  input                wen,
       |  input  [${width - 1}:0] wdata,
       |  input  [${aw - 1}:0] raddr,
       |  input                ren,
       |  output [${width - 1}:0] rdata
       |);
       |  // cascade_height bounds the URAM chain length so the read-data AND
       |  // write-address cascades stay short enough for 250 MHz (a single deep
       |  // chain over all ${depth / 4096 + 1}~ URAMs is a 7-deep combinational cascade that fails).
       |  // (banked: the storage is declared per bank in the generate block below)
       |
       |  // `writeLat` registered write stages. shreg_extract="no" keeps them as
       |  // flops (not SRLs) so the placer puts them adjacent to the URAM write
       |  // port — the external address arithmetic (drainBatchCtr*numColTiles+…)
       |  // ends at a flop here, so the URAM cascade's CAS_IN_ADDR no longer starts
       |  // from combinational logic routed across the die (the B>=6 critical path).
       |  (* shreg_extract = "no" *) reg [${aw - 1}:0]    waddr_q [0:${writeLat - 1}];
       |  (* shreg_extract = "no" *) reg                  wen_q   [0:${writeLat - 1}];
       |  (* shreg_extract = "no" *) reg [${width - 1}:0] wdata_q [0:${writeLat - 1}];
       |  integer j;
       |  always @(posedge clk) begin
       |    waddr_q[0] <= waddr; wen_q[0] <= wen; wdata_q[0] <= wdata;
       |    for (j = 1; j < $writeLat; j = j + 1) begin
       |      waddr_q[j] <= waddr_q[j - 1]; wen_q[j] <= wen_q[j - 1]; wdata_q[j] <= wdata_q[j - 1];
       |    end
       |  end
       |${bankedBody}endmodule
       |""".stripMargin
  )
}
