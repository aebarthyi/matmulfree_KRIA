# Architecture notes + hybrid CPU/FPGA analysis

*Written 2026-07-21. Grounded in the Kria KR260 `4Port` builds (D=1024 / MMfreeLM-370M).*

> **Historical — read the numbers as of that date.** The conclusion still holds (the design
> is bandwidth-bound, so spending area on compute buys nothing), but the figures predate
> three things that shipped afterwards: 1.6-bit weight packing (so "~77 MB of weights per
> token" is the 2-bit figure, ~20% high), batch=8, and threaded readback. Current per-token
> traffic and tok/s live in `CLAUDE.md` and `NEXT_STEPS.md`.

The question this note answers: **if we dedicated an FPGA to the matmul only and
beefed it up with the extra space, could a hybrid CPU+FPGA setup reach similar
tok/s?** Short answer up front, then the architecture and the reasoning.

> **TL;DR.** The design is *bandwidth-bound, not compute-bound*: ~99.6% of the
> arithmetic and ~100% of the DDR traffic is the ternary matmul, which streams
> **~77 MB of weights per token** from DDR. "Beefing up the matmul with extra
> space" buys **nothing** — the compute is already idle. On the Kria the FPGA is
> the right choice because the ARM PS is too weak to feed the matmul; but the
> FPGA is *not* winning on compute density, it's winning because it can turn idle
> fabric into **batch lanes (weight reuse)** almost for free. The correct way to
> spend extra FPGA space is **more batch lanes and more memory bandwidth**, not a
> wider matmul, and not offloading the cheap vector ops to the CPU.

---

## 1. What the system actually is

**Model.** MMfreeLM-370M — an HGRN-bit ternary *recurrent* transformer (BitNet-1.58
style), not classic attention. `D = 1024`, **24 layers**, vocab 32000, context 2048.
Weights are ternary {−1, 0, +1}, packed **2 bits/weight (4 per byte)**; the fp
source is bf16 with a per-matrix scale α = mean(|W|).

**The accelerator is not a CPU.** `ternip_core` is a single-issue, in-order,
*blocking* dispatcher driven by a **pre-decoded instruction stream** — no fetch,
no PC, no scalar register file. Its only architectural state is an 8-entry vector
register file. It decodes one variable-length instruction, routes it to exactly
one functional unit, lets that unit run to completion, then decodes the next.

**Five functional units**, all sharing the vector register file:

| FU | Job |
|----|-----|
| `ternip_tmatmul` | ternary matrix × vector — **the workload** |
| `ternip_loadstore` | DMA between DDR and vector registers (LDV/SV) |
| `ternip_rowwise_operation` | elementwise + activations (ADD/SUB/MUL, SIG/CSIG/SILU) |
| `ternip_rms` | RMSNorm (own sqrt/reciprocal datapath) |
| `STALL` | host-visible completion/interrupt |

**The ternary matmul.** A "multiply" is a **sign-select** (add / subtract / skip),
accumulated through an adder tree — *no DSP multipliers* (DSP util 4.8%, and even
that isn't the matmul). Crucially, **every `GO` re-streams the entire matrix from
DDR**; on-chip memory holds only the length-D input and output vectors. There is
**no weight cache** — BRAM 1.7%, URAM 1.6% sit idle.

**Multi-bank striping** (`NumDdrBanksPerTmatmul = 4`). One matmul splits its rows
across N=4 DDR banks/ports; each bank streams `MatrixBytesPerBank = MatrixSize/N`
and has its own accumulator lane, all sharing one broadcast input vector. Per core:
**1 loadstore master + N tmatmul read-only masters** → N HP ports.

**Batching** (`BatchSize`). `BatchSize` identical cores run the *same program in
lockstep on different data*. The tmatmul weight stream is **fetched once and
fan-out-duplicated to every lane** — one 77 MB weight read serves N tokens. This
is a pure *weight-reuse* lever; measured **2.00× at batch=2**.

**System partition on the Kria.** The **entire 24-layer transformer runs on the PL
soft-core.** The ARM PS (Cortex-A53) does only: embedding lookup, the final
lm_head, and DMA/register control. Instructions are DMA'd in as an AXI-Stream.
So this is *already* an FPGA-does-everything design — the PS is a controller.

---

## 2. The one number that governs everything: bytes/token

Per layer there are **7 ternary projections** (f/i/g/o + gate×2 + down):

```
per layer = 4·(1024²) + 2·(2816·1024) + (1024·2816) = 12.85M weights
per layer bytes = 12.85M / 4  = 3.06 MiB
× 24 layers      = 73.5 MiB  ≈ 77 MB of weights streamed per token
```

Everything else — ~30 elementwise ops + 7 RMSNorms per layer — is **< 0.5% of the
arithmetic** and touches only a few KB. So:

> **~99.6% of the compute AND ~100% of the DDR traffic is the ternary matmul.**
> The whole system's throughput is `effective_DDR_bandwidth / 77 MB-per-token`.

---

## 3. Where the bottleneck actually is

| Path | Bandwidth |
|------|-----------|
| K26 DDR4-2400, 64-bit | **19.2 GB/s** (hardware peak) |
| PL fabric, 128b × 100 MHz × 4 HP ports | **6.4 GB/s** (nominal) |
| **Measured** (100.9 weight-passes/s × 77 MB) | **≈ 7.8 GB/s effective** |

The fabric sinks only **~1/3 of DDR peak**. The wall is on the PL side: the
platform gives a single fixed ~100 MHz PL clock, so 128b × 100 MHz × 4 ports caps
the feed. (Measured ~7.8 GB/s runs slightly above the 6.4 nominal — the deployed
PL clock / burst efficiency is a bit better than the round-number estimate; either
way it's pinned at the fabric ceiling.) Compute sits idle behind it — **proven by
the "Wide" build, which doubled compute for 0× throughput.**

---

## 4. The hybrid question, decomposed

The proposal has three separable claims. They pull in different directions.

### (a) "Beef up the matmul with the extra FPGA space" → **no benefit.**
Compute is already idle. More MAC lanes / a wider tmatmul = more idle silicon,
because the bottleneck is *feeding weights from DDR*, not multiplying them. Already
demonstrated (Wide: 2× compute, 0× tok/s). Extra LUTs buy throughput **only if they
buy bandwidth or reuse.** The two useful ways to spend space:
- **more batch lanes** (weight reuse — cheap, compute idle, proven 2× at batch=2), or
- **on-chip weight cache** — but 73.5 MiB won't fit the Kria's ~5 MiB of BRAM+URAM,
  so caching is off the table on *this* part (would need heavy reuse or a much
  bigger die; no FPGA holds a 73 MiB working set on-chip).

### (b) "Dedicate the FPGA to matmul, move the rest to the CPU" → **likely slower.**
The vector/norm/gating ops a CPU would take over are < 0.5% of compute but are
**tightly interleaved** with the matmuls in the recurrent dataflow (~30 vector ops
+ 7 RMSNorms + 7 matmuls per layer, all data-dependent). Splitting them off adds a
**PS↔PL round-trip per op** — hundreds of handshakes per token — for work that
currently costs essentially nothing on-chip. You'd trade ~free on-fabric ops for
synchronization latency. Cutting the cheap, tightly-coupled ops away from the
expensive one is the wrong seam.

### (c) "Could a CPU (hybrid) match the FPGA's tok/s?" → **depends on which CPU.**
This is the real question, and the bandwidth-bound framing makes it sharp.

Pure bandwidth math: `19.2 GB/s ÷ 77 MB ≈ 250 tok/s` *if* you could saturate DDR
from the CPU side. That's above our current ~101. So in principle the memory is not
the limit for a CPU — the compute is.

- **On the Kria itself (ARM A53 PS):** the FPGA wins clearly. The A53 (4×~1.5 GHz,
  128-bit NEON) is *bad* at 2-bit unpack + conditional accumulate; it would be
  compute-bound on the unpacking long before it reached DDR speed — realistically
  tens of tok/s, not 250. A "hybrid" that ran the matmul on the A53 would be
  **slower**, not similar. The FPGA's win here isn't compute density — it's that
  it can saturate a throttled-but-decent 7.8 GB/s with near-zero compute cost,
  which the weak embedded CPU cannot.
- **On a server host + PCIe FPGA (a different deployment):** a modern x86 (50–100+
  GB/s DDR, AVX-512 with shuffle/popcount ternary tricks) *could* rival or beat a
  batch-1 FPGA — because there the FPGA's compute edge is wasted and, on Kria-class
  fabric, its bandwidth is throttled. At batch-1, "similar tok/s from the CPU" is
  plausible on that class of host.

### (d) Where a dedicated FPGA decisively wins: **batching + bandwidth.**
The FPGA's true edge in a ternary/bandwidth-bound regime is that **adding a batch
lane is nearly free** — the sign-select compute is cheap and the fabric is idle, so
one 77 MB weight fetch serves N tokens (`effective tok/s = raw × N`). A CPU can
batch too, but each extra lane costs *real* ALU work; on the FPGA it costs *idle
LUTs*. Combine that with a **bandwidth-rich part** (faster PL clock, wider ports, or
an HBM device like the au250) and the throttle disappears — that's the regime where
a dedicated FPGA leaves any CPU behind.

---

## 5. Bottom line & recommendations

1. **On the Kria, keep it on the FPGA.** The embedded A53 is too weak to feed the
   ternary matmul; a hybrid that offloads the matmul (or the cheap vector ops) to
   it would be slower, not similar.
2. **A CPU only matches the FPGA at batch-1 on a *beefy* host** (server-class x86
   with high DDR bandwidth) — because at batch-1 the FPGA isn't winning on merit,
   it's bandwidth-throttled by the 100 MHz platform. That's a different deployment
   than the Kria.
3. **Spend extra FPGA space on batch lanes, not a bigger matmul.** Weight reuse is
   the lever that turns idle fabric into throughput and is where the FPGA pulls
   ahead of any CPU. (This is exactly what the batch=2 → batch=4 sweep is testing.)
4. **To beat the ceiling itself**, raise memory bandwidth: a faster PL clock
   (~120–150 MHz), wider ports, or a bandwidth-rich (HBM) part. Do *not* add MAC
   width, and do *not* split the tightly-coupled vector/norm ops off to the CPU.

**Net:** "beef up the matmul compute" is the one thing that provably does nothing
here. The FPGA's value is cheap parallel compute layered on top of scarce
bandwidth — so the win comes from **reusing each expensive weight fetch across many
tokens (batching)** and from **getting more bandwidth**, not from more matmul.
