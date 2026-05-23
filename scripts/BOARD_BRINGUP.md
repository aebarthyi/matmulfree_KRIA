# KRIA board bring-up — first ternary matmul bench

This is the minimum path from a successful `vivado` build of CoreTop (already done — see `build/vivado-ooc/k26_bench/`) to a running bench on a KV260 / KR260 with Ubuntu-on-KRIA. No PetaLinux required.

## 0. Pre-reqs on the board

- Ubuntu certified for KRIA (22.04 LTS or 24.04 LTS, Xilinx image).
- `u-dma-buf` kernel module installed (Xilinx's [u-dma-buf](https://github.com/ikwzm/udmabuf)). Verify with `lsmod | grep u_dma_buf`.
- `xmutil` available (ships with the Xilinx Ubuntu).
- A serial / SSH session to the board.

## 1. Vivado: package `CoreTop` as IP

From this repo on your Vivado host:

```bash
vivado &
```

In Vivado GUI:

1. **Tools → Create and Package New IP → Package a specified directory**.
2. Source directory: `generated/k26_bench/` (the emitted SV).
3. Top module: `CoreTop`.
4. On the **Ports & Interfaces** page, Vivado should auto-infer:
   - `s_axi` → AXI4 Slave (32-bit addr, 128-bit data, 6-bit ID)
   - `s_axis` → AXI4-Stream Slave (**64-bit data** at aWidth=16; was 32-bit at aWidth=8)
   - `m_axis` → AXI4-Stream Master (32-bit data — outAccWidth=28 padded to 32)
   - `aclk` → clock; `aresetn` → active-low reset (associate clock with all three AXI interfaces)
   - `irq` → leave as standalone output (we'll wire to the PS later)
5. **Save & Package**. Note the IP repo path — you'll reference it next.

## 2. Vivado: create the board design

1. New Project → Boards → choose **KV260 / KR260** (the SoM-only board file is fine).
2. IP Integrator → Create Block Design.
3. Add IPs:
   - **Zynq UltraScale+ MPSoC** (PS) → run Block Automation. Defaults are fine; under **PS-PL Configuration → AXI HPM0 LPD** enable the master, **AXI HP0 FPD** enable as slave (the DMA will use this for DRAM access).
   - **CoreTop** (from your IP repo).
   - **AXI Direct Memory Access** (Xilinx AXI DMA, *not* AXI CDMA / VDMA).
     - **Disable** scatter-gather (we drive simple mode).
     - **Enable** Read Channel (MM2S) and Write Channel (S2MM).
     - Memory map data width: 64 bits (or 128 — must match what HP is set to, and ≥ stream width).
     - Stream data width: **MM2S = 64 bits**, **S2MM = 32 bits** (must match CoreTop's `s_axis` / `m_axis` for the aWidth=16 K26_Bench preset). MM2S and S2MM can be configured independently on the AXI DMA IP.
     - Address width: 40 bits (matches AXI HP).
     - **Buffer Length Register Width: 23 bits** (default 14 is too small — caps single transfers at 16 KiB, but the K26_Bench weights buffer reaches 256 KiB at N=M=1024). 23 → 8 MiB, plenty of headroom.
   - **AXI Interconnect** (or **SmartConnect**) to fan out the PS HPM0 master to two AXI4-Lite slaves (CoreTop's `s_axi` + DMA's `S_AXI_LITE`).
   - **AXI Interconnect / SmartConnect** to merge the two AXI HP masters (DMA MM2S read + DMA S2MM write) into the PS HP0 slave port.
   - **Concat** (xlconcat) to bundle DMA `mm2s_introut` + DMA `s2mm_introut` + CoreTop `irq` to the PS `pl_ps_irq0[7:0]`.
4. Wire it up:
   - `PS clk_pl0` → all `aclk` / `s_axi_aclk` / `m_axi_aclk` / DMA `s_axi_lite_aclk`.
   - `PS pl_resetn0` → through a **Processor System Reset** IP → `aresetn` everywhere.
   - DMA `M_AXI_MM2S` and `M_AXI_S2MM` → through the second SmartConnect → PS `S_AXI_HP0_FPD`.
   - DMA `M_AXIS_MM2S` → CoreTop `s_axis`.
   - CoreTop `m_axis` → DMA `S_AXIS_S2MM`.
   - PS `M_AXI_HPM0_LPD` → SmartConnect → CoreTop `s_axi` + DMA `S_AXI_LITE`.
5. **Address Editor**:
   - CoreTop `s_axi`    : range `4K`
   - DMA   `S_AXI_LITE` : range `64K`

   Let Vivado auto-assign the base addresses (or pick your own — Zynq US+ PL apertures live in `0x8000_0000–0xBFFF_FFFF`, so anything Vivado offers there is valid). Write down whatever addresses end up assigned; you'll plug them into the device-tree overlay (§3) and the bench CLI (§4).
6. **Validate Design**. Should be clean.
7. Create HDL wrapper, set as top, **Generate Bitstream**.

Expected resource use (from OOC, will grow slightly with the DMA + interconnect): ~5k LUTs / ~7k FFs / ~10 BRAMs — still well under 10% of a K26.

Export hardware:
```
File → Export → Export Hardware → include bitstream → save as core_bench.xsa
```

## 3. Boot the bitstream on the KRIA

The Xilinx Ubuntu image uses `xmutil` + DT overlays. Both pieces — the bitstream and the overlay — are generated from the `.xsa` by `scripts/gen_overlay.sh`.

### Generate the overlay (one command)

On your Vivado host (after `source /tools/Xilinx/Vivado/<ver>/settings64.sh`):

```bash
./scripts/gen_overlay.sh path/to/system.xsa build/overlay
```

This extracts CoreTop's base address from the `.xsa` via xsct, substitutes it into `scripts/core_bench_overlay.dts.in`, runs `dtc` to compile the `.dtbo`, extracts the bitstream from the `.xsa`, and writes `shell.json`. Output:

```
build/overlay/
├── core_bench.bit      (bitstream, extracted from .xsa)
├── core_bench.dts      (populated overlay source, for inspection)
├── core_bench.dtbo     (compiled overlay)
└── shell.json          (consumed by xmutil)
```

Flags worth knowing:
- `-ip <name>` — override BD cell name if auto-detection misses it (looks for `CoreTop_0`, `mmfree_core_0`, then VLNV match).
- `-addr <hex>` — override the base address (use if hsi auto-detect fails on your Vitis version).
- `-irq <n>` — override the GIC SPI number (defaults to 89; check your block design's Concat → ps_pl_irq wiring).

Sizes encoded in the dtsi template match `K26_Bench` worst-case at aWidth=16:
- activations: maxN × 8 bytes/beat = 8 KiB
- weights: maxN × numColTiles × 8 bytes = 1024 × 32 × 8 = 256 KiB
- outputs: maxM × 4 bytes = 4 KiB

To change those sizes for a different preset, edit `scripts/core_bench_overlay.dts.in` directly — the tcl just substitutes the address / IRQ placeholders, the udmabuf sizes are literal.

### Deploy on the KRIA

```bash
sudo mkdir -p /lib/firmware/xilinx/core_bench
sudo cp build/overlay/core_bench.bit \
        build/overlay/core_bench.dtbo \
        build/overlay/shell.json \
        /lib/firmware/xilinx/core_bench/
sudo xmutil unloadapp || true
sudo xmutil loadapp core_bench
```

After overlay loads:

```bash
ls /dev/udmabuf*     # → /dev/udmabuf-act /dev/udmabuf-wt /dev/udmabuf-out
ls /dev/uio*         # → /dev/uio0  (CoreTop)
cat /sys/class/u-dma-buf/udmabuf-act/phys_addr  # should be a real DDR4 phys addr
```

## 4. Build and run the benchmark

On the board:

```bash
cd ~/matmulfree_KRIA/software
make
sudo ./build/bench  \
    <CORE_PHYS> <DMA_PHYS> \
    /dev/uio0 \
    /dev/udmabuf0 /dev/udmabuf1 /dev/udmabuf2 \
    | tee bench.csv
```

Substitute the addresses Vivado assigned in §2 step 5. For example, if Vivado put CoreTop at `0x80000000` and the DMA at `0x80010000`:

```bash
sudo ./build/bench 0x80000000 0x80010000 \
    /dev/uio0 /dev/udmabuf0 /dev/udmabuf1 /dev/udmabuf2 | tee bench.csv
```

`sudo` is needed because the runtime opens `/dev/mem`. (Long-term, replace `/dev/mem` with a UIO-bound register window — but `/dev/mem` is fastest to get a first number.)

Expected output (CSV header + 25 rows for the 5×5 sweep). The `gops_compute` column is the figure of merit. With xDim=4, batchSize=1, and a ~250 MHz PL clock, peak theoretical is ~16 GOPS — real DRAM-bound runs will land lower.

### Quick smoke without the full sweep

`MMFREE_VERIFY=0` skips the CPU reference check (the verify path is `O(N·M)` on the host and dominates wall-clock for the larger shapes). Set it to 1 (default) on the *first* run to confirm correctness, then `=0` for the perf sweep.

## 5. Troubleshooting

- **`mmap /dev/mem: Operation not permitted`** → run as `sudo` (or set `CAP_SYS_RAWIO`).
- **`open /dev/uio0` fails** → the overlay didn't bind generic-uio. Check `dmesg | grep uio`, double-check the GIC SPI number in the overlay.
- **`COMPUTE err=0x1`** → invalid opcode reported by Core. Almost always a NEON write splitting into two AXI beats — confirm `__aarch64__` is being defined (it is on the KRIA).
- **`COMPUTE` hangs (no IRQ)** → DMA isn't pushing enough beats. Re-check that you sized the weight buffer as `N * numColTiles * 4` bytes and that the DMA is reset between runs (the runtime does this on open).
- **Outputs verify wrong** → the weight buffer layout in `bench.c` must match what `Core` consumes: **col-tile-major** beats (all N rows for tile 0, then all N rows for tile 1, etc.), with lane 0 of each beat at the LSB. Suspect 1 if you swap to a different config: re-derive `BENCH_OUT_LANES_PER_TILE` from `xDim * aWidth/2` and `BENCH_OUT_ACC_WIDTH` from `log2(maxAcc) + aWidth`.
