package control

import chisel3._
import chisel3.util._

/**
  * URAM output memory (1W/1R) as a BlackBox carrying `ram_style = "ultra"`, which
  * CIRCT cannot attach to a SyncReadMem.
  *
  * Read: `rdata` = mem[raddr] delayed `latency` cycles, advanced only while `ren` is
  * high (so STORE can stall without corrupting the pipeline). Write: synchronous on
  * `wen`, behind `writeLat` registered stages that terminate the drain's write-address
  * arithmetic at flops next to the URAM. Core extends its drain-completion gate by
  * `writeLat` so a STORE issued off the IRQ still sees the last write.
  *
  * The module name encodes geometry + latencies so distinct instances get distinct
  * inline SV (BlackBoxInline keys on the file/module name).
  */
class OutMemUltra(depth: Int, width: Int, latency: Int, writeLat: Int = 1, cascadeHeight: Int = 4,
                  bankDepth: Int = 0)
    extends BlackBox with HasBlackBoxInline {
  require(depth > 0 && width > 0 && latency >= 1 && writeLat >= 1 && cascadeHeight >= 1)
  require(bankDepth >= 0, s"bankDepth=$bankDepth must be >= 0 (0 = auto)")
  private val aw = log2Ceil(depth)

  // ── Banking geometry ──────────────────────────────────────────────────────
  // A URAM288 is 4096 deep, so a 4096*cascadeHeight-row bank is exactly one cascade
  // chain. bankDepth is overridable so tests can force several banks (and the mux).
  private val effBankDepth = if (bankDepth > 0) bankDepth else 4096 * cascadeHeight
  private val nBanks       = (depth + effBankDepth - 1) / effBankDepth
  // A single bank is sized to the real depth, not rounded up to effBankDepth.
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

  // The `_pbanked` tag is load-bearing: shipped bitstreams were built from SV with this
  // exact module name, and scripts/clone_preset_hw.sh hashes the SV. Do not remove it.
  private val styleTag = s"_pbanked${nBanks}x$effBankDepth"
  override val desiredName =
    s"OutMemUltra_${depth}x${width}_l${latency}_w${writeLat}_c${cascadeHeight}$styleTag"

  // ── The banked read ───────────────────────────────────────────────────────
  // Vivado does not absorb read-pipeline registers into the URAM cascade (Synth 8-6057,
  // "FF provided for Pipeline: 0") whatever the coding style, so they are placed
  // structurally: the address space is split into `nBanks` sub-arrays, each one cascade
  // chain deep, and the read is
  //
  //     cycle 1   every bank registers its own output (dout_q); bank select registers (sel_q)
  //     cycle 2   the mux over the REGISTERED bank outputs registers (mux_q)
  //     cycle 3.. the remaining latency-2 stages
  //
  // Total read latency is still `latency` (the two structural stages replace two
  // pass-through ones), so Core's outReadLat/primeCtr accounting is unchanged.
  // shreg_extract="no" keeps every stage a flop rather than an SRL.
  private val bankedBody = {
    val wl      = writeLat - 1
    val tail    = latency - (if (nBanks <= 1) 1 else 2)
    val muxSlots = if (selW == 0) 1 else 1 << selW

    // bankAw == 0 (one row per bank): the whole address is bank select and the array is
    // mem[0:0]; emitting raddr[-1:0] would be a malformed slice.
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

    // Sized to the full 2^selW so a non-power-of-two bank count cannot produce an
    // out-of-range part-select (X in simulation).
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

  // Every character emitted below (comments included) is load-bearing: clone_preset_hw.sh
  // hashes the SV, and shipped bitstreams were built from it. Change it only with a rebuild.
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
