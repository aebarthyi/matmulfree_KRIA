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
                  pipeStyle: String = "legacy", bankDepth: Int = 0)
    extends BlackBox with HasBlackBoxInline {
  require(depth > 0 && width > 0 && latency >= 1 && writeLat >= 1 && cascadeHeight >= 1)
  require(Seq("legacy", "absorb", "banked").contains(pipeStyle),
    s"""pipeStyle="$pipeStyle" must be "legacy", "absorb" or "banked"""")
  require(bankDepth >= 0, s"bankDepth=$bankDepth must be >= 0 (0 = auto)")
  private val aw = log2Ceil(depth)

  // ── Banking geometry (pipeStyle = "banked") ───────────────────────────────
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
  require(pipeStyle != "banked" || nBanks <= 1 || latency >= 2,
    s"banked needs latency >= 2 (one stage for the bank output, one for the mux); got $latency")

  val io = IO(new Bundle {
    val clk   = Input(Clock())
    val waddr = Input(UInt(aw.W))
    val wen   = Input(Bool())
    val wdata = Input(UInt(width.W))
    val raddr = Input(UInt(aw.W))
    val ren   = Input(Bool())
    val rdata = Output(UInt(width.W))
  })

  private val styleTag =
    if (pipeStyle == "legacy") ""
    else if (pipeStyle == "banked") s"_pbanked${nBanks}x$effBankDepth"
    else s"_p$pipeStyle"
  override val desiredName =
    s"OutMemUltra_${depth}x${width}_l${latency}_w${writeLat}_c${cascadeHeight}$styleTag"

  // ── pipeStyle = "absorb" ──────────────────────────────────────────────────
  // MEASURED 2026-08-08: the "legacy" body below gets `FF provided for Pipeline: 0,
  // FF absorbed: 0` out of Vivado's URAM mapping report, alongside
  //   WARNING [Synth 8-6057] Memory "mem_reg" ... has no pipeline registers.
  // i.e. NONE of its `latency` read stages are offered to the URAM matrix, and the
  // read path is a fully combinational CAS_IN_DOUT -> CAS_OUT_DOUT chain across the
  // cascade. That chain is the design's worst 300 MHz path (-0.315 ns, 82% logic).
  // The module comment above has always claimed those registers pack into the
  // cascade; the report says they never did.
  //
  // Two differences from the Xilinx UG901 UltraRAM template are candidates, and this
  // variant removes both at once because they are cheap to test together and the
  // mapping report says unambiguously which way it went:
  //   1. read and write live in SEPARATE always blocks here; the template puts them
  //      in ONE, which is how the tool recognises the SDP-with-pipeline structure.
  //   2. `shreg_extract = "no"` sits on the pipeline registers. It was added to stop
  //      SRL inference (an SRL after the cascade pipelines nothing), but it also
  //      blocks the absorption it was meant to enable.
  // The template's own defence against SRL extraction is the explicit `mem_data`
  // output stage plus indexed pipeline stages fed from it, so dropping the attribute
  // is not simply reopening the SRL hole.
  //
  // Read semantics are IDENTICAL either way: rdata is mem[raddr] delayed by
  // `latency` cycles, advanced only while `ren` is high. mem_data is stage 0 and the
  // pipe holds the remaining latency-1, so Core's outReadLat accounting is unchanged
  // — which is what lets CoreSpec run the two bodies against the same reference.
  private val nbPipe = latency - 1

  private val absorbBody =
    s"""  // Single always block, Xilinx UG901 UltraRAM template: write, the RAM output
       |  // register (mem_data), and the cascade pipeline stages together, so synthesis
       |  // recognises the structure and offers the stages to the URAM matrix. Check it
       |  // took them: the synth log's "Ultra RAM: Final Mapping Report" must show a
       |  // non-zero "FF absorbed" for this instance.
       |  reg [${width - 1}:0] mem_data;
       |${if (nbPipe > 0) s"  reg [${width - 1}:0] rpipe [0:${nbPipe - 1}];\n  integer i;\n" else ""}
       |  always @(posedge clk) begin
       |    if (wen_q[${writeLat - 1}]) mem[waddr_q[${writeLat - 1}]] <= wdata_q[${writeLat - 1}];
       |    if (ren) begin
       |      mem_data <= mem[raddr];
       |${if (nbPipe > 0) s"""      for (i = 0; i < $nbPipe; i = i + 1)
       |        rpipe[i] <= (i == 0) ? mem_data : rpipe[i - 1];
       |""" else ""}    end
       |  end
       |  assign rdata = ${if (nbPipe > 0) s"rpipe[${nbPipe - 1}]" else "mem_data"};
       |""".stripMargin

  // ── pipeStyle = "banked" — the fix that does not depend on inference ──────
  // Both "legacy" and "absorb" are mapped with `FF provided for Pipeline: 0`: Vivado
  // takes NONE of the read stages into the URAM matrix regardless of coding style, so
  // the read stays one combinational run from a URAM's CLK through the whole
  // CAS_IN_DOUT -> CAS_OUT_DOUT chain and the group mux. Chasing that with
  // cascadeHeight is treating the symptom (4 -> 2 bought +0.028 ns), and "absorb"
  // made it worse by reopening SRL extraction on the pipeline.
  //
  // So place the registers by construction instead of asking for them. The address
  // space is split into `nBanks` explicit sub-arrays, each exactly one cascade chain
  // deep, and the read is:
  //
  //     cycle 1   every bank registers its own output      (dout_q, one per bank)
  //               the bank select registers alongside it   (sel_q, same enable)
  //     cycle 2   the mux over REGISTERED bank outputs registers   (mux_q)
  //     cycle 3.. the remaining latency-2 stages, unchanged
  //
  // No cascade hop, no mux, and no cross-die route shares a cycle with the URAM
  // access any more. The arithmetic from the failing path: URAM CLK->DOUT is 1.837 ns
  // on its own — 55% of a 3.333 ns period — and the legacy path adds 0.833 ns of
  // cascade, 0.246 ns of mux and 0.653 ns of route on top, for 3.569 ns. Banking
  // leaves the 1.837 ns alone with its route to a local flop (~2.2 ns), and gives the
  // mux its own cycle. That is the trade: read latency for fabric clock.
  //
  // Total read latency is UNCHANGED at `latency` — the two structural stages replace
  // two of the existing pass-through stages rather than adding to them — so Core's
  // outReadLat accounting, primeCtr and drain flush are untouched. The tail stages
  // keep shreg_extract="no", which the "absorb" result proved is load-bearing.
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

  // ⚠️ The legacy body must stay BYTE-IDENTICAL: scripts/clone_preset_hw.sh hashes the
  // emitted SV, so even an added comment line here makes it refuse a legitimate clone.
  // Its inline claim about packing registers into the cascade is wrong (see above); the
  // correction lives here in the Scala, not in the generated file.
  private val legacyBody =
    s"""  always @(posedge clk) if (wen_q[${writeLat - 1}]) mem[waddr_q[${writeLat - 1}]] <= wdata_q[${writeLat - 1}];
       |
       |  // `latency` registered read stages. shreg_extract="no" keeps them as
       |  // flops (not SRLs) so the tool packs them into the URAM cascade pipeline
       |  // registers — an SRL would sit after the whole cascade and pipeline
       |  // nothing. Gated by `ren` so the store FSM can stall without data loss.
       |  (* shreg_extract = "no" *) reg [${width - 1}:0] rpipe [0:${latency - 1}];
       |  integer i;
       |  always @(posedge clk) if (ren) begin
       |    rpipe[0] <= mem[raddr];
       |    for (i = 1; i < $latency; i = i + 1) rpipe[i] <= rpipe[i - 1];
       |  end
       |  assign rdata = rpipe[${latency - 1}];
       |""".stripMargin

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
       |${if (pipeStyle == "banked") "  // (banked: the storage is declared per bank in the generate block below)"
          else s"""  (* ram_style = "ultra", cascade_height = $cascadeHeight *) reg [${width - 1}:0] mem [0:${depth - 1}];"""}
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
       |${pipeStyle match {
         case "legacy" => legacyBody
         case "banked" => bankedBody
         case _        => absorbBody
       }}endmodule
       |""".stripMargin
  )
}
