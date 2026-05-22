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
   - `s_axis` → AXI4-Stream Slave (32-bit data)
   - `m_axis` → AXI4-Stream Master (32-bit data)
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
     - Memory map data width: 32 bits (or 64/128 — must match what HP is set to).
     - Stream data width: 32 bits **on both MM2S and S2MM** (these must match CoreTop's `s_axis` / `m_axis` = 32b).
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

The Xilinx Ubuntu image uses `xmutil` + DT overlays. Easiest path:

1. Copy `core_bench.bit` (extract from the `.xsa` — it's a zip) and a tiny device-tree overlay `core_bench.dtbo` (see below) to the board.
2. `sudo cp core_bench.bit /lib/firmware/xilinx/core_bench/core_bench.bit`
3. `sudo cp core_bench.dtbo /lib/firmware/xilinx/core_bench/core_bench.dtbo`
4. Add a `shell.json`:
   ```json
   { "shell_type" : "XRT_FLAT", "num_slots": "1" }
   ```
   under the same dir.
5. `sudo xmutil unloadapp` (if anything is loaded)
6. `sudo xmutil loadapp core_bench`

### The minimal overlay (`core_bench.dts` → compile with `dtc`)

```dts
/dts-v1/;
/plugin/;
&{/} {
    #address-cells = <2>;
    #size-cells    = <2>;

    /* CoreTop's IRQ goes through PS pl_ps_irq0[0]. Adjust the GIC interrupt
     * number if you wired it to a different bit. The number 89 below is
     * GIC SPI 89 = irq[0] on Zynq US+ — check your block design.
     *
     * The reg = <…> address MUST match the CoreTop s_axi address Vivado
     * assigned in §2 step 5. Update both the node label (@xxxxxxxx) and the
     * reg field together. */
    mmfree_core: mmfree_core@a0010000 {
        compatible    = "generic-uio";
        reg           = <0x0 0xa0010000 0x0 0x1000>;
        interrupts    = <0 89 4>;     /* SPI, num, level-high (Core irq is held until ack) */
        interrupt-parent = <&gic>;
    };

    /* udmabufs for activation / weight / output buffers. */
    udmabuf_act: udmabuf-act { compatible = "ikwzm,u-dma-buf"; size = <0x00001000>; };  /*  4 KiB */
    udmabuf_wt:  udmabuf-wt  { compatible = "ikwzm,u-dma-buf"; size = <0x00040000>; };  /* 256 KiB */
    udmabuf_out: udmabuf-out { compatible = "ikwzm,u-dma-buf"; size = <0x00001000>; };  /*  4 KiB */
};
```

Sizes match `K26_Bench` worst-case (maxN=1024 bytes activations, 1024×64×4 = 256 KiB weights, 1024×4 = 4 KiB outputs).

After overlay loads:

```bash
ls /dev/udmabuf*     # → /dev/udmabuf0 /dev/udmabuf1 /dev/udmabuf2
ls /dev/uio*         # → /dev/uio0  (CoreTop)
cat /sys/class/u-dma-buf/udmabuf0/phys_addr  # should be a real DDR4 phys addr
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
- **Outputs verify wrong** → the weight packing in `bench.c` must match what `SystolicArray` expects (col-tile-major). If you swap to a different config, re-derive `BENCH_OUT_LANES_PER_TILE`.
