# NC AMO Support: coupledL2 TL2CHI Bridge Modifications

## Why This Doc Exists

The Claude bot that generates code for XiangShan runs in a GitHub Actions environment where
git submodules (including `coupledL2/`) are **not initialized** — the directory is present
but empty. Therefore, the bot cannot read or directly modify coupledL2 source files.

This document provides **complete, ready-to-apply code changes** for your local coupledL2
checkout (e.g., `/share/.../XiangShan/coupledL2/`).

The CoupledL2 commit this analysis is based on:
`1e2017a303a686d17774c277129e0b04f6daa4e8` (as provided by the user)

---

## Background

`UncacheAtomicBuffer` (added in `src/main/scala/xiangshan/cache/dcache/UncacheAtomic.scala`)
routes NC (PBMT=01) atomic instructions through TileLink with `MemPageTypeNC=1`:

| RISC-V instruction | TL-A opcode sent by XiangShan         | Expected CHI from coupledL2    |
|--------------------|---------------------------------------|-------------------------------|
| LR.W/D             | `Get`                                 | `ReadNoSnp` ✓ (already works)  |
| SC.W/D             | `PutFullData`                         | `WriteNoSnpFull` ✓             |
| AMOSWAP            | `LogicalData(SWAP, param=3)` + NC=1   | **`AtomicSwap`** ← needs fix   |
| AMOADD             | `ArithmeticData(ADD, param=4)` + NC=1 | **`AtomicLoad(ADD)`** ← needs fix |
| AMOXOR/OR/AND      | `LogicalData(XOR/OR/AND)` + NC=1     | **`AtomicLoad(EOR/SET/CLR)`** ← needs fix |
| AMOMIN/MAX/MINU/MAXU | `ArithmeticData` + NC=1           | **`AtomicLoad`** ← needs fix   |
| AMOCAS.W/D         | `Get` + conditional `PutFullData`     | `ReadNoSnp` + `WriteNoSnpFull` (2-phase) |

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
| `AtomicCompare` | `0x2D` | CAS: compare+swap atomically (for future AMOCAS at HN-F) |

### CHI AtomicOp Field (for AtomicLoad only)

| CHI AtomicOp | Value | Corresponding RISC-V / TL param |
|--------------|-------|--------------------------------|
| `ADD`        | `0x0` | AMOADD  / TLAtomics.ADD (4)   |
| `CLR`        | `0x1` | AMOAND  / TLAtomics.AND (2) — XiangShan already sends **~rs2** |
| `EOR`        | `0x2` | AMOXOR  / TLAtomics.XOR (0)   |
| `SET`        | `0x3` | AMOOR   / TLAtomics.OR  (1)   |
| `SMAX`       | `0x4` | AMOMAX  / TLAtomics.MAX (1 in ArithmeticData) |
| `SMIN`       | `0x5` | AMOMIN  / TLAtomics.MIN (0 in ArithmeticData) |
| `UMAX`       | `0x6` | AMOMAXU / TLAtomics.MAXU (3)  |
| `UMIN`       | `0x7` | AMOMINU / TLAtomics.MINU (2)  |

### CHI Atomic Transaction Flow

```
RN-F (XiangShan core)             HN-F (external HA)
    |--- TXREQ: AtomicLoad/AtomicSwap ------------------>|
    |    (addr, size, AtomicOp, MemAttr.EWA=0, ...)      |
    |--- TXDAT: NonCopyBackWrData (operand rs2) -------->|
    |                                                    |
    |                [HN-F atomically performs RMW]       |
    |                                                    |
    |<-- RXDAT: CompData (original value before op) -----|
```

---

## TL param → CHI AtomicOp Mapping Table

| TL opcode        | TL param | CHI opcode  | CHI AtomicOp |
|------------------|----------|-------------|--------------|
| ArithmeticData   | ADD=4    | AtomicLoad  | ADD=0        |
| ArithmeticData   | MIN=0    | AtomicLoad  | SMIN=5       |
| ArithmeticData   | MAX=1    | AtomicLoad  | SMAX=4       |
| ArithmeticData   | MINU=2   | AtomicLoad  | UMIN=7       |
| ArithmeticData   | MAXU=3   | AtomicLoad  | UMAX=6       |
| LogicalData      | XOR=0    | AtomicLoad  | EOR=2        |
| LogicalData      | OR=1     | AtomicLoad  | SET=3        |
| LogicalData      | AND=2    | AtomicLoad  | CLR=1        |
| LogicalData      | SWAP=3   | AtomicSwap  | N/A          |

---

## Files to Modify in coupledL2

### Step 1: Add CHI opcode constants

**File: `src/main/scala/coupledL2/tl2chi/package.scala`** (or wherever CHI opcodes are defined)

Find the object that defines CHI REQ opcodes (e.g., `CHIOpcode`, `REQOpcodes`, or similar).
Add the following constants:

```scala
// In the CHI REQ opcode object/companion:
val AtomicLoad    = "b0101100".U(7.W)   // 0x2C — AtomicLoad (ADD/XOR/OR/AND/MIN/MAX/MINU/MAXU)
val AtomicSwap    = "b0101110".U(7.W)   // 0x2E — AtomicSwap
val AtomicCompare = "b0101101".U(7.W)   // 0x2D — AtomicCompare (for future AMOCAS use)
```

### Step 2: Add AtomicLoad/AtomicSwap to NC opcode selection in MSHR.scala

**File: `src/main/scala/coupledL2/tl2chi/MSHR.scala`**

Find the section that selects the CHI opcode for NC (non-cacheable) requests.
It will look similar to one of these patterns:

**Pattern A** (MuxCase style):
```scala
// EXISTING — look for something like:
val ncCHIOpcode = MuxCase(CHIOpcode.REQOpcodes.ReadNoSnp, Seq(
  (req.opcode === TLMessages.PutFullData    -> CHIOpcode.REQOpcodes.WriteNoSnpFull),
  (req.opcode === TLMessages.PutPartialData -> CHIOpcode.REQOpcodes.WriteNoSnpPtl),
))

// REPLACE WITH:
val ncIsSwap       = req.opcode === TLMessages.LogicalData && req.param === TLAtomics.SWAP
val ncIsAtomicLoad = (req.opcode === TLMessages.ArithmeticData) ||
                     (req.opcode === TLMessages.LogicalData && req.param =/= TLAtomics.SWAP)

val ncCHIOpcode = MuxCase(CHIOpcode.REQOpcodes.ReadNoSnp, Seq(
  (req.opcode === TLMessages.PutFullData    -> CHIOpcode.REQOpcodes.WriteNoSnpFull),
  (req.opcode === TLMessages.PutPartialData -> CHIOpcode.REQOpcodes.WriteNoSnpPtl),
  // NEW: NC AMO → CHI Atomic (true atomic at HN-F, no core-side RMW)
  (ncIsSwap       -> CHIOpcode.REQOpcodes.AtomicSwap),   // 0x2E
  (ncIsAtomicLoad -> CHIOpcode.REQOpcodes.AtomicLoad),   // 0x2C
))
```

**Pattern B** (if the opcode is set inside a `when` block):
```scala
// Find the nc path opcode assignment and add:
when (isNC) {
  when (req.opcode === TLMessages.Get) {
    txreq.opcode := ReadNoSnp
  }.elsewhen (req.opcode === TLMessages.PutFullData) {
    txreq.opcode := WriteNoSnpFull
  }.elsewhen (req.opcode === TLMessages.PutPartialData) {
    txreq.opcode := WriteNoSnpPtl
  // ADD THESE:
  }.elsewhen (req.opcode === TLMessages.LogicalData && req.param === TLAtomics.SWAP) {
    txreq.opcode := AtomicSwap        // 0x2E
  }.elsewhen (req.opcode === TLMessages.ArithmeticData ||
              req.opcode === TLMessages.LogicalData) {
    txreq.opcode := AtomicLoad        // 0x2C
  }.otherwise {
    txreq.opcode := ReadNoSnp
  }
}
```

### Step 3: Set AtomicOp field in TXREQ

Add after the opcode selection (still in MSHR.scala):

```scala
// TL param → CHI AtomicOp (only used when txreq.opcode === AtomicLoad)
val chi_atomic_op = MuxLookup(req.param, 0.U(4.W))(Seq(
  // ArithmeticData params: ADD=4, MIN=0, MAX=1, MINU=2, MAXU=3
  TLAtomics.ADD  -> "b0000".U,   // CHI ADD  = 0
  TLAtomics.MIN  -> "b0101".U,   // CHI SMIN = 5
  TLAtomics.MAX  -> "b0100".U,   // CHI SMAX = 4
  TLAtomics.MINU -> "b0111".U,   // CHI UMIN = 7
  TLAtomics.MAXU -> "b0110".U,   // CHI UMAX = 6
  // LogicalData params: XOR=0, OR=1, AND=2 (SWAP=3 → AtomicSwap, not AtomicLoad)
  TLAtomics.XOR  -> "b0010".U,   // CHI EOR  = 2
  TLAtomics.OR   -> "b0011".U,   // CHI SET  = 3
  TLAtomics.AND  -> "b0001".U,   // CHI CLR  = 1 (XiangShan already sends ~rs2 for AND)
))

// Wire AtomicOp to TXREQ (only meaningful for AtomicLoad; 0 for AtomicSwap)
txreq.atomicOp := Mux(ncIsAtomicLoad, chi_atomic_op, 0.U)
```

### Step 4: MemAttr — Ensure EWA=0 and device=0 for NC Atomic

For CHI AtomicLoad/AtomicSwap on non-cacheable memory, `MemAttr.EWA` must be 0
(non-cacheable, non-early-write-ack) and `device` must be 0 (normal memory, not device):

```scala
// Find where txreq.memAttr is set for the NC path and ensure:
when (isNC) {
  txreq.memAttr.allocate  := false.B
  txreq.memAttr.cacheable := false.B
  txreq.memAttr.device    := false.B   // NC main memory (not device/MMIO)
  txreq.memAttr.ewa       := false.B   // REQUIRED for non-cacheable: EWA=0
}
```

### Step 5: TXDAT — Send Operand Data for Atomic Transactions

**This is the most structurally significant change.**

CHI AtomicLoad and AtomicSwap require the operand to be sent on the **TXDAT channel**
as `NonCopyBackWrData`, separate from TXREQ.

The NC PutFull path already sends TXDAT for write data. For NC atomics, reuse that
same TXDAT path but also trigger it for AtomicLoad/AtomicSwap.

Look for the MSHR state that sends TXDAT for NC writes (e.g., a state like
`s_nc_wdata`, `s_wr_data`, or similar). Extend its trigger condition:

```scala
// EXISTING (approximate):
val ncNeedsWrData = isNC && (req.opcode === TLMessages.PutFullData ||
                              req.opcode === TLMessages.PutPartialData)

// REPLACE WITH:
val ncIsSwap_req       = req.opcode === TLMessages.LogicalData && req.param === TLAtomics.SWAP
val ncIsAtomicLoad_req = (req.opcode === TLMessages.ArithmeticData) ||
                          (req.opcode === TLMessages.LogicalData && req.param =/= TLAtomics.SWAP)

val ncNeedsWrData = isNC && (
  req.opcode === TLMessages.PutFullData    ||
  req.opcode === TLMessages.PutPartialData ||
  ncIsSwap_req                             ||  // NEW: AtomicSwap needs operand in TXDAT
  ncIsAtomicLoad_req                           // NEW: AtomicLoad needs operand in TXDAT
)
```

The TXDAT beat for a NonCopyBackWrData (operand for atomic):
```scala
txdat.opcode  := NonCopyBackWrData
txdat.tgtID   := <HN-F node ID>
txdat.srcID   := <this node's ID>
txdat.txnID   := <same TxnID as TXREQ>
txdat.data    := req.data   // operand from TL-A (XiangShan puts rs2 here)
txdat.be      := req.mask   // byte enable
txdat.dataID  := 0.U        // for 64-bit data
```

### Step 6: TL-D Response — AccessAckData with Original Value

After CHI CompData is received (HN-F response to AtomicLoad/AtomicSwap), the MSHR
must send a TL-D `AccessAckData` response with the original memory value.

The NC `ReadNoSnp` response path likely already builds TL-D `AccessAckData` from
CHI `CompData`. Ensure the AtomicLoad/AtomicSwap response uses the same path:

```scala
// In the state that handles CHI responses for NC operations:
// The existing ReadNoSnp → AccessAckData path should already work for AtomicLoad/AtomicSwap
// since all three return CHI CompData with data.
// Verify: when (rxdat.valid && rxdat.bits.opcode === CompData && isNC) { ... }
// If the condition uses req.opcode === TLMessages.Get, expand it:
when (rxdat.valid && rxdat.bits.opcode === CompData && isNC &&
      (req.opcode === TLMessages.Get          ||
       req.opcode === TLMessages.ArithmeticData ||
       req.opcode === TLMessages.LogicalData)) {
  // build TL-D AccessAckData with rxdat.bits.data
  resp_data := rxdat.bits.data
  // transition to response state
}
```

---

## Summary of Changes

| File | Change |
|------|--------|
| `tl2chi/package.scala` | Add `AtomicLoad=0x2C`, `AtomicSwap=0x2E` opcode constants |
| `tl2chi/MSHR.scala`    | Add `AtomicLoad`/`AtomicSwap` to NC opcode selection MuxCase |
| `tl2chi/MSHR.scala`    | Add `chi_atomic_op` MuxLookup (TL param → CHI AtomicOp) |
| `tl2chi/MSHR.scala`    | Set `txreq.atomicOp` for AtomicLoad path |
| `tl2chi/MSHR.scala`    | Ensure `MemAttr.ewa=0, device=0` for NC AMO path |
| `tl2chi/MSHR.scala`    | Extend `ncNeedsWrData` to trigger TXDAT for AtomicLoad/AtomicSwap |
| `tl2chi/MSHR.scala`    | Extend CompData response handling to include AtomicLoad/AtomicSwap |

---

## How to Verify After Applying Changes

Build and simulate, then check wave signals:

```
NC AMOSWAP  → L2 CHI TXREQ.opcode = 0x2E (AtomicSwap)
NC AMOADD   → L2 CHI TXREQ.opcode = 0x2C (AtomicLoad), TXREQ.AtomicOp = 0x0 (ADD)
NC AMOXOR   → L2 CHI TXREQ.opcode = 0x2C (AtomicLoad), TXREQ.AtomicOp = 0x2 (EOR)
NC AMOAND   → L2 CHI TXREQ.opcode = 0x2C (AtomicLoad), TXREQ.AtomicOp = 0x1 (CLR)
NC AMOOR    → L2 CHI TXREQ.opcode = 0x2C (AtomicLoad), TXREQ.AtomicOp = 0x3 (SET)
NC LR/SC    → L2 CHI should still have ReadNoSnp / WriteNoSnpFull (unchanged)
```

AtomicsUnit state machine for NC AMO should go through:
`s_invalid(0)→s_tlb(1)→s_pm(2)→s_nc_req(11)→s_nc_resp(12)→s_finish(7)→s_invalid(0)`

`io.uncache.resp.bits.data` should contain the original memory value returned by HN-F
via RXDAT CompData.
