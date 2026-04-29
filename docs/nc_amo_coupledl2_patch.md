# NC AMO Support: Required coupledL2 TL2CHI Bridge Changes

## Background

XiangShan's `UncacheAtomicBuffer` (added in this branch) routes non-cacheable (PBMT=NC)
atomic instructions through TileLink with `MemPageTypeNC=1`. For regular AMOs
(amoadd/amoxor/amoswap/etc.), it emits TL ArithmeticData or TL LogicalData requests.

For **true cross-node atomic** execution, `coupledL2` must convert these TL requests
to CHI non-cached atomic transactions (EWA=0) so the external HN-F atomically performs
the read-modify-write and returns the original value.

## Required coupledL2 Changes

In `coupledL2`'s TL2CHI bridge (typically `TL2CHI.scala` or the NC path handler in
`MainPipe.scala`/`MSHR.scala`), add a conversion rule for the NC path:

### TL ArithmeticData + MemPageTypeNC=1  →  CHI AtomicLoad

```
TL-A.opcode     = ArithmeticData (2)
TL-A.param      = ADD(4) / MIN(0) / MAX(1) / MINU(2) / MAXU(3)
TL-A.user[NC]   = 1
   ↓ coupledL2 converts to ↓
CHI TXREQ.Opcode  = AtomicLoad (0x2C)
CHI TXREQ.AtomicOp = ADD(0x0) / CLR(NC) / SET / SMAX / UMAX / SMIN / UMIN
                     (TL MIN  → CHI SMIN, TL MAX → CHI SMAX, etc.)
CHI TXREQ.MemAttr.EWA = 0  (non-cacheable)
CHI TXREQ.MemAttr.Device = 0 (normal memory)
CHI TXREQ.SnpAttr = 0  (no snooping)
```

### TL LogicalData (XOR/OR/AND) + MemPageTypeNC=1  →  CHI AtomicLoad

```
TL-A.opcode     = LogicalData (3)
TL-A.param      = XOR(0) / OR(1) / AND(2)
TL-A.user[NC]   = 1
   ↓ coupledL2 converts to ↓
CHI TXREQ.Opcode  = AtomicLoad (0x2C)
CHI TXREQ.AtomicOp = EOR(XOR) / SET(OR) / CLR(AND)
CHI TXREQ.MemAttr.EWA = 0
```

### TL LogicalData (SWAP) + MemPageTypeNC=1  →  CHI AtomicSwap

```
TL-A.opcode     = LogicalData (3)
TL-A.param      = SWAP(3)
TL-A.user[NC]   = 1
   ↓ coupledL2 converts to ↓
CHI TXREQ.Opcode  = AtomicSwap (0x2E)
CHI TXREQ.MemAttr.EWA = 0
```

### TL-D Response Mapping (CHI CompData → TL AccessAckData)

The HN-F returns the **original** memory value (pre-operation) in CHI CompData.
coupledL2 maps this back to TL-D AccessAckData with the original value in the
data field. UncacheAtomicBuffer returns this to AtomicsUnit as `rd`.

## Current Behavior Without This Patch

Without this coupledL2 change, TL ArithmeticData/LogicalData with MemPageTypeNC=1
is not handled by the NC path, and the resulting CHI opcode at the L2 output is
incorrect (observed as opcode=0 in simulation), causing the HN-F to reject or
misprocess the request and returning wrong data to the core.

## LR/SC and AMOCAS

LR/SC use TL Get/PutFull + MemPageTypeNC=1, which coupledL2 already converts
to CHI ReadNoSnp / WriteNoSnpFull correctly. No coupledL2 changes needed for LR/SC.

AMOCAS.W/D uses TL Get + conditional PutFull (two-phase, not truly atomic at HN-F).
For true atomic AMOCAS at HN-F, coupledL2 would need to emit CHI AtomicCompare (0x2D)
with the compare+swap values packed together — this is a separate future enhancement.
