# NC AMO Support: coupledL2 TL2CHI Bridge Modifications

## Why This Doc Exists

The Claude bot that generates code for XiangShan runs in a GitHub Actions environment where
git submodules (including `coupledL2/`) are **not initialized** — the directory is present
but empty. Therefore, the bot cannot read or directly modify coupledL2 source files.

This document provides **complete, ready-to-apply code changes** for your local coupledL2
checkout (e.g., `/share/.../XiangShan/coupledL2/`).

---

## Background

`UncacheAtomicBuffer` (added in `src/main/scala/xiangshan/cache/dcache/UncacheAtomic.scala`)
routes NC (PBMT=01) atomic instructions through TileLink with `MemPageTypeNC=1`:

| RISC-V instruction | TL-A opcode sent by XiangShan     | Expected CHI from coupledL2 |
|--------------------|-----------------------------------|-----------------------------|
| LR.W/D             | `Get`                             | `ReadNoSnp` ✓ (already works)|
| SC.W/D             | `PutFullData`                     | `WriteNoSnpFull` ✓          |
| AMOSWAP            | `LogicalData(SWAP)` + NC=1        | **`AtomicSwap`** ← needs fix |
| AMOADD/XOR/OR/AND  | `ArithmeticData`/`LogicalData` + NC=1 | **`AtomicLoad`** ← needs fix|
| AMOMAXU/MINU etc.  | `ArithmeticData` + NC=1           | **`AtomicLoad`** ← needs fix |
| AMOCAS.W/D         | `Get` + conditional `PutFullData` | `ReadNoSnp` + `WriteNoSnpFull` (2-phase, not truly atomic at HN-F)|

The current coupledL2 TL2CHI bridge does not convert `ArithmeticData`/`LogicalData` + NC
to `CHI AtomicLoad`/`AtomicSwap`, producing incorrect CHI opcodes (observed as `opcode=0`
in simulation).

---

## CHI Atomic Transaction Reference

### CHI Opcodes (ARM CHI-E spec)

| CHI Transaction | Opcode | When to use |
|-----------------|--------|-------------|
| `AtomicLoad`    | `0x2C` | AMO with result: ADD, XOR, OR, AND, MIN, MAX, MINU, MAXU |
| `AtomicSwap`    | `0x2E` | AMO swap: write new value, return old |
| `AtomicCompare` | `0x2D` | CAS: compare+swap atomically |

### CHI AtomicOp Field (for AtomicLoad only)

| CHI AtomicOp | Value | Corresponding RISC-V / TL param |
|--------------|-------|--------------------------------|
| `ADD`        | `0x0` | AMOADD / TLAtomics.ADD (4) |
| `CLR`        | `0x1` | AMOAND / TLAtomics.AND (2) — operand is **~rs2** |
| `EOR`        | `0x2` | AMOXOR / TLAtomics.XOR (0) |
| `SET`        | `0x3` | AMOOR  / TLAtomics.OR  (1) |
| `SMAX`       | `0x4` | AMOMAX / TLAtomics.MAX (1 in ArithmeticData) |
| `SMIN`       | `0x5` | AMOMIN / TLAtomics.MIN (0 in ArithmeticData) |
| `UMAX`       | `0x6` | AMOMAXU / TLAtomics.MAXU (3) |
| `UMIN`       | `0x7` | AMOMINU / TLAtomics.MINU (2) |

### CHI Atomic Transaction Flow

```
RN-F (core)                  HN-F (HA)
    |--- TXREQ: AtomicLoad/AtomicSwap (addr, size, AtomicOp, ...) --->|
    |--- TXDAT: NonCopyBackWrData (operand data) ---------------------->|
    |                                                                   |
    |                  [HN-F atomically performs RMW]                   |
    |                                                                   |
    |<-- RXRSP: Comp or CompData (original value) ----------------------|
    |<-- RXDAT: CompData (original value) -------------------------------|
```

---

## Files to Modify in coupledL2

### Primary file: `src/main/scala/coupledL2/tl2chi/MSHR.scala`

This is where TL-A requests are converted to CHI TXREQ. Find the section that sets
the NC path CHI request opcode. It will look similar to:

```scala
// EXISTING code (approximate, find the actual location in your file):
val ncCHIOpcode = MuxCase(CHIOpcode.REQOpcodes.ReadNoSnp, Seq(
  (req.opcode === TLMessages.PutFullData    -> CHIOpcode.REQOpcodes.WriteNoSnpFull),
  (req.opcode === TLMessages.PutPartialData -> CHIOpcode.REQOpcodes.WriteNoSnpPtl),
))
```

**Replace with (add AtomicLoad / AtomicSwap cases):**

```scala
// Detect NC atomic operations (from UncacheAtomicBuffer in XiangShan)
val ncIsSwap     = req.opcode === TLMessages.LogicalData && req.param === TLAtomics.SWAP
val ncIsAtomicLoad = (req.opcode === TLMessages.ArithmeticData) ||
                     (req.opcode === TLMessages.LogicalData && req.param =/= TLAtomics.SWAP)

val ncCHIOpcode = MuxCase(CHIOpcode.REQOpcodes.ReadNoSnp, Seq(
  (req.opcode === TLMessages.PutFullData    -> CHIOpcode.REQOpcodes.WriteNoSnpFull),
  (req.opcode === TLMessages.PutPartialData -> CHIOpcode.REQOpcodes.WriteNoSnpPtl),
  // ──── NEW: NC AMO → CHI Atomic ────────────────────────────────────────────
  (ncIsSwap       -> CHIOpcode.REQOpcodes.AtomicSwap),  // 0x2E
  (ncIsAtomicLoad -> CHIOpcode.REQOpcodes.AtomicLoad),  // 0x2C
  // ──────────────────────────────────────────────────────────────────────────
))
```

**Also add CHI opcode constants to `package.scala` (or wherever CHIOpcode is defined):**

```scala
// In CHIOpcode.REQOpcodes or equivalent object:
val AtomicLoad    = "b0101100".U(7.W)   // 0x2C
val AtomicSwap    = "b0101110".U(7.W)   // 0x2E
val AtomicCompare = "b0101101".U(7.W)   // 0x2D (for future AMOCAS use)
```

---

### Step 2: Set AtomicOp field in TXREQ

Find where `txreq.atomicOp` is set (or where the full `txreq` bundle is constructed).
Add the TL param → CHI AtomicOp mapping:

```scala
// TL ArithmeticData params: ADD=4, MIN=0, MAX=1, MINU=2, MAXU=3
// TL LogicalData    params: XOR=0, OR=1,  AND=2, SWAP=3
val chi_atomic_op = MuxLookup(req.param, 0.U(4.W))(Seq(
  // ArithmeticData → AtomicLoad
  TLAtomics.ADD  -> "b0000".U,   // CHI ADD  = 0
  TLAtomics.MIN  -> "b0101".U,   // CHI SMIN = 5  (TL param=0)
  TLAtomics.MAX  -> "b0100".U,   // CHI SMAX = 4  (TL param=1)
  TLAtomics.MINU -> "b0111".U,   // CHI UMIN = 7  (TL param=2)
  TLAtomics.MAXU -> "b0110".U,   // CHI UMAX = 6  (TL param=3)
  // LogicalData → AtomicLoad (XOR/OR/AND only; SWAP uses AtomicSwap)
  TLAtomics.XOR  -> "b0010".U,   // CHI EOR  = 2  (TL param=0)
  TLAtomics.OR   -> "b0011".U,   // CHI SET  = 3  (TL param=1)
  TLAtomics.AND  -> "b0001".U,   // CHI CLR  = 1  (TL param=2; XiangShan sends ~rs2)
))

// Wire to txreq (only meaningful for AtomicLoad; set 0 otherwise)
txreq.atomicOp := Mux(ncIsAtomicLoad, chi_atomic_op, 0.U)
```

---

### Step 3: MemAttr for NC Atomic Operations

For CHI AtomicLoad/AtomicSwap on non-cacheable memory, `MemAttr.EWA` must be 0
(non-cacheable) and `Device` must be 0 (normal memory, not device):

```scala
// In the section where txreq.memAttr is set for the NC path:
when (isNC) {
  txreq.memAttr.allocate  := false.B
  txreq.memAttr.cacheable := false.B
  txreq.memAttr.device    := false.B   // NC main memory (not device/MMIO)
  txreq.memAttr.ewa       := false.B   // REQUIRED for non-cacheable: EWA=0
}
```

---

### Step 4: TXDAT — Send Operand Data for Atomic Operations

**This is the most important structural change.** CHI AtomicLoad and AtomicSwap
require the operand to be sent on the **TXDAT channel** (as `NonCopyBackWrData`)
separate from the TXREQ.

Find where the MSHR sends write data on TXDAT for NC PutFull operations. There should
already be a state (e.g., `s_nc_write_data` or `s_issue_wdata`) that sends TXDAT for
NC stores. For NC atomics, **reuse this same TXDAT path** with the TL-A `data` field
(the operand, `req.data`).

The TXDAT beat for a NonCopyBackWrData (operand for atomic) has:
```
txdat.opcode  = NonCopyBackWrData (e.g., "b011".U or the constant for that opcode)
txdat.tgtID   = <HN-F node ID, same as for writes>
txdat.srcID   = <this node's ID>
txdat.txnID   = <same TxnID as the TXREQ>
txdat.data    = req.data   // operand from TL-A (XiangShan puts rs2 here)
txdat.be      = req.mask   // byte enable
txdat.dataID  = 0.U        // for 64-bit data, use 0
```

**Modification in MSHR state machine:** When the NC path issues an AtomicLoad or
AtomicSwap TXREQ, also schedule a TXDAT beat with the operand:

```scala
// Conceptual addition to MSHR state machine:
// After s_issue_req (send TXREQ for NC AMO), before waiting for response,
// add a state to send TXDAT with the atomic operand:

val ncNeedsWrData = isNC && (ncIsAtomicLoad || ncIsSwap)

when (state === s_issue_txreq && txreq_fire && ncNeedsWrData) {
  state := s_issue_nc_amo_wdata
}

when (state === s_issue_nc_amo_wdata) {
  txdat.valid     := true.B
  txdat.bits.opcode := NonCopyBackWrData
  txdat.bits.data   := req.data
  txdat.bits.be     := req.mask
  when (txdat.fire) {
    state := s_wait_nc_amo_resp
  }
}

// In s_wait_nc_amo_resp: HN-F returns CompData with original value
when (state === s_wait_nc_amo_resp) {
  when (rxdat.valid && rxdat.bits.opcode === CompData) {
    resp_data := rxdat.bits.data   // original value, returned to TL-D as AccessAckData
    state     := s_send_nc_resp
  }
}
```

---

### Step 5: TL-D Response — AccessAckData with Original Value

After CHI CompData is received, the MSHR must send a TL-D `AccessAckData` response
with the original memory value (pre-operation). This is the same handling as for
NC `ReadNoSnp` (which also returns data via CompData), so if the existing NC read
response path builds `AccessAckData`, just ensure it also applies to the AMO case.

---

## Summary of Changes

| File | Change |
|------|--------|
| `tl2chi/MSHR.scala` | Add `AtomicLoad`/`AtomicSwap` to NC opcode selection MuxCase |
| `tl2chi/MSHR.scala` | Add `chi_atomic_op` MuxLookup (TL param → CHI AtomicOp) |
| `tl2chi/MSHR.scala` | Ensure `MemAttr.ewa=0, device=0` for NC AMO path |
| `tl2chi/MSHR.scala` | Add TXDAT state for sending atomic operand data |
| `tl2chi/MSHR.scala` | Handle CompData response → TL-D AccessAckData |
| `tl2chi/package.scala` | Add `AtomicLoad=0x2C`, `AtomicSwap=0x2E` opcode constants |

## How to Test

After applying these changes and rebuilding:

```
NC AMOSWAP → L2 CHI TXREQ should have opcode=0x2E (AtomicSwap)
NC AMOADD  → L2 CHI TXREQ should have opcode=0x2C (AtomicLoad), AtomicOp=0x0 (ADD)
NC AMOXOR  → L2 CHI TXREQ should have opcode=0x2C (AtomicLoad), AtomicOp=0x2 (EOR)
NC LR/SC   → L2 CHI should still have ReadNoSnp / WriteNoSnpFull (unchanged)
```

Check AtomicsUnit state machine: for NC AMO, it should go through states:
`0→1→2→s_nc_req→s_nc_resp→7→0` and `io.uncache.resp.bits.data` should contain the
original memory value returned by HN-F.
