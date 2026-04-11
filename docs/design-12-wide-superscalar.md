# XiangShan 12-Wide Superscalar Widening: Design Document

**Status:** Proposal  
**Scope:** Architectural redesign from 8-wide to 12-wide superscalar pipeline  
**Baseline:** XiangShan default config (8-wide decode/rename/commit)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Current Baseline Architecture](#2-current-baseline-architecture)
3. [Design Goals and Non-Goals](#3-design-goals-and-non-goals)
4. [Hard Constraints and Invariants](#4-hard-constraints-and-invariants)
5. [Frontend Widening](#5-frontend-widening)
6. [Backend Pipeline Widening](#6-backend-pipeline-widening)
7. [Execution Resource Scaling](#7-execution-resource-scaling)
8. [Register File and Bypass Network](#8-register-file-and-bypass-network)
9. [Memory Subsystem Scaling](#9-memory-subsystem-scaling)
10. [Wakeup Network Redesign](#10-wakeup-network-redesign)
11. [Parameter Change Summary](#11-parameter-change-summary)
12. [Risk Analysis](#12-risk-analysis)
13. [Implementation Phases](#13-implementation-phases)
14. [Appendix: File Reference](#appendix-file-reference)

---

## 1. Executive Summary

This document proposes widening XiangShan's superscalar pipeline from the current 8-wide
(decode/rename/commit) to 12-wide. The change affects every major subsystem: frontend
instruction delivery, backend decode/rename/dispatch, execution resource count and mix,
register file port scaling, memory hierarchy bandwidth, and the inter-unit wakeup network.

The widening is **not a parameter-only change**. While XiangShan is well-parameterized, a
50% width increase requires proportional growth in functional units, memory ports, and
queue depths, plus superlinear growth in the bypass/wakeup network. Without these
accompanying changes, simply setting `DecodeWidth = 12` would create a wider frontend
feeding the same backend bottlenecks, yielding negligible IPC improvement.

**Expected outcome:** Up to 30-40% throughput improvement on instruction-parallel workloads
(integer-heavy server, multi-threaded), with diminishing returns on branch-heavy or
memory-bound codes. Area increase estimated at 50-80% for the core (excluding caches).

---

## 2. Current Baseline Architecture

### 2.1 Pipeline Width Parameters

Source: `Parameters.scala:80-84`

| Parameter | Default | V2 Config | Purpose |
|-----------|---------|-----------|---------|
| `DecodeWidth` | 8 | 6 | Instructions decoded per cycle |
| `RenameWidth` | 8 | 6 | Instructions renamed per cycle |
| `CommitWidth` | 8 | 6 | Commit tracking width |
| `RobCommitWidth` | 8 | 6 | ROB retirement width per cycle |
| `RabCommitWidth` | 8 | 6 | RAB retirement width per cycle |

### 2.2 Current Functional Unit Mix

Source: `Parameters.scala:326-459`

**Integer Scheduler (13 execution units across 13 issue queues):**

| Unit | FU Types | Issue Queue | Entries | numEnq |
|------|----------|-------------|---------|--------|
| ALU0 | ALU, CSR, Fence | IQ0 (shared w/ BJU0) | 18 | 2 |
| BJU0 | Branch, Jump | IQ0 | — | — |
| ALU1 | ALU, Div | IQ1 (shared w/ BJU1) | 18 | 2 |
| BJU1 | Branch, Jump | IQ1 | — | — |
| ALU2 | ALU, I2F, VSet, I2V | IQ2 (shared w/ BJU2) | 18 | 2 |
| BJU2 | Branch, Jump | IQ2 | — | — |
| ALU3 | ALU, BKU | IQ3 | 20 | 2 |
| ALU4 | ALU, MUL | IQ4 | 20 | 2 |
| ALU5 | ALU, MUL | IQ5 | 20 | 2 |
| LDU0 | Load | IQ6 | 20 | 2 |
| LDU1 | Load | IQ7 | 20 | 2 |
| LDU2 | Load | IQ8 | 20 | 2 |
| STA0 | Store Addr, MOU | IQ9 | 16 | 2 |
| STA1 | Store Addr, MOU | IQ10 | 16 | 2 |
| STD0 | Store Data | IQ11 | 16 | 2 |
| STD1 | Store Data | IQ12 | 16 | 2 |

**FP Scheduler (4 execution units):**

| Unit | FU Types | Entries | numEnq |
|------|----------|---------|--------|
| FEX0 | FALU, FMA, FCVT, FCMP, F2V | 20 | 2 |
| FEX1 | FALU, FMA, FDIV | 20 | 2 |
| FEX2 | FALU, FMA, FDIV | 20 | 2 |
| FEX3 | FALU, FMA | 20 | 2 |

**Vector Scheduler (4 execution units):**

| Unit | FU Types | Entries | numEnq |
|------|----------|---------|--------|
| VFEX0 | VIALU, VFALU, VFMA, VIMAC, VPPU, VIPU, VFCVT, VSet, VMove | 16 | 2 |
| VFEX1 | VIALU, VFALU, VFMA, VFDIV, VIDIV | 16 | 2 |
| VLSU0 | VLoad, VStore, VSegLoad, VSegStore | 16 | 2 |
| VLSU1 | VLoad, VStore | 16 | 2 |

### 2.3 Memory Subsystem

| Component | Current Size/Width |
|-----------|--------------------|
| DCache load ports | 3 (`LoadPipelineWidth`) |
| DCache store ports | 2 (`StorePipelineWidth`) |
| TLB ports (BTLB) | 5 (3 load + 2 store) |
| Virtual Load Queue | 72 entries |
| Store Queue | 56 entries |
| Store Buffer | 16 entries, 2-wide enqueue |
| ROB | 352 entries |
| RAB | 352 entries |

### 2.4 Register Files

Source: `Parameters.scala:117-146`

| Register File | Entries | Banks | Read Ports | Write Ports |
|---------------|---------|-------|------------|-------------|
| IntPreg | 224 | 4 | ~12 (derived) | ~9 (derived) |
| FpPreg | 256 | 1 | ~12 | ~7 |
| VfPreg | 128 | 1 | ~12 | ~6 |
| V0Preg | 22 | 1 | ~4 | ~4 |
| VlPreg | 32 | 1 | ~4 | ~4 |

---

## 3. Design Goals and Non-Goals

### Goals

1. **12-wide decode/rename/commit** — sustain 12 instructions/cycle through the pipeline
2. **Proportional FU scaling** — enough execution bandwidth to avoid starving the wider pipeline
3. **Balanced memory bandwidth** — enough load/store ports to match typical instruction mix
4. **Maintain clock frequency** — no critical-path regressions from wider muxes/arbiters
5. **Parameterized design** — all changes via XSCoreParameters, enabling easy fallback to 8-wide

### Non-Goals

- Multi-cluster or NUMA-style partitioning (out of scope for this phase)
- SMT (simultaneous multithreading) — orthogonal to width
- L2/L3 cache redesign — assume existing cache hierarchy with higher bandwidth demand
- Vector unit widening beyond proportional scaling

---

## 4. Hard Constraints and Invariants

These `require()` assertions must be satisfied. Any widening must respect them:

| Constraint | File | Line | Implication |
|------------|------|------|-------------|
| `DecodeWidth == RenameWidth` | `StoreSet.scala:67`, `WaitTable.scala:43` | Both widths must change together |
| `RenameWidth <= CommitWidth` | `Rob.scala:722` | CommitWidth ≥ 12 |
| `NumReadBank >= DecodeWidth` | `IBuffer.scala:56` | IBuffer needs ≥ 12 read banks |
| `Size % NumReadBank == 0` | `ibuffer/Parameters.scala:27` | IBuffer size must be divisible by NumReadBank |
| `Size % NumWriteBank == 0` | `ibuffer/Parameters.scala:28` | IBuffer size must be divisible by NumWriteBank |
| `LoadPipelineWidth == LdExuCnt` | `Parameters.scala:753` | DCache load ports must match load unit count |
| `StorePipelineWidth == StaCnt` | `Parameters.scala:754` | DCache store ports must match store addr unit count |
| `EnsbufferWidth <= StorePipelineWidth` | `Sbuffer.scala:530` | Store buffer enqueue ≤ store pipeline width |
| `DeqPtrMoveStride == CommitWidth` | `VirtualLoadQueue.scala:135` | Load queue dequeue stride = commit width |

---

## 5. Frontend Widening

### 5.1 Current Frontend Bandwidth

The frontend is already **wider than the backend**:

- **ICache**: 64B fetch block → 32 instructions (RVC) per fetch → 2 ports
- **BPU**: 1 prediction per cycle, covering up to 32 instructions per block
- **IBuffer**: 48 entries, 8 read banks, dequeue width = DecodeWidth (8)
- **FTQ**: 64 entries, single-entry prediction input

**The frontend fetch bandwidth (32 instr/cycle) already exceeds 12-wide demand.** The
bottleneck is the IBuffer dequeue path and decode width.

### 5.2 Required Frontend Changes

| Component | Current | Proposed 12-wide | Rationale |
|-----------|---------|------------------|-----------|
| IBuffer `Size` | 48 | 96 | Must be divisible by 12; larger buffer absorbs fetch bubbles |
| IBuffer `NumReadBank` | 8 | 12 | Must be ≥ DecodeWidth (12) |
| IBuffer `NumWriteBank` | 4 | 4 or 6 | 96 % 4 = 0 (OK), or 6 for better write bandwidth |
| `MaxBypassNum` | 12 (8+4) | 16 (12+4) or 18 (12+6) | Derived: DecodeWidth + NumWriteBank |
| ICache | No change | No change | Already delivers 32 instr/cycle |
| BPU | No change | No change | Already predicts full fetch block |
| FTQ | No change | Consider 80 entries | Wider decode drains FTQ faster |

### 5.3 IBuffer Bypass Path

The IBuffer bypass allows freshly fetched instructions to skip the buffer when it's empty.
With `MaxBypassNum = DecodeWidth + NumWriteBank`, the bypass MUX width grows from 12 to
16-18. This is a modest combinational cost increase and unlikely to affect timing.

### 5.4 Frontend Risk Assessment

**Low risk.** The frontend is already overprovisioned. The only real change is widening the
IBuffer dequeue path from 8 to 12 — essentially adding 4 more bank-read ports and wider
output MUX.

---

## 6. Backend Pipeline Widening

### 6.1 Decode Stage

Source: `backend/decode/DecodeStage.scala`

**Current:** 8 `DecodeUnit` instances + 1 shared `DecodeUnitComp` (complex decoder).

**Proposed 12-wide:**
- Instantiate **12 `DecodeUnit`** modules (line 116: `Seq.fill(DecodeWidth)(...)`)
- `DecodeUnitComp` remains single — it handles multi-uop instructions sequentially
- Fusion decoder: `Vec(DecodeWidth - 1, ...)` → grows from 7 to 11 fusion checks per cycle
- RAT read port vectors grow: `Vec(RenameWidth, Vec(numPorts, ...))` for each register type

**Area impact:** Each `DecodeUnit` is ~74KB of source. 4 additional decoders represent
significant area. The decode logic itself is purely combinational (lookup tables), so timing
should remain acceptable.

### 6.2 Rename Stage

Source: `backend/rename/Rename.scala`, `RenameTable.scala`, `BusyTable.scala`

**Rename table port scaling:**

| Register Type | Current Read Ports | Proposed 12-wide | Formula |
|---------------|-------------------|------------------|---------|
| Int RAT | 2 × 8 = 16 | 2 × 12 = 24 | 2 src operands × RenameWidth |
| FP RAT | 3 × 8 = 24 | 3 × 12 = 36 | 3 src operands × RenameWidth |
| Vec RAT | 3 × 8 = 24 | 3 × 12 = 36 | 3 src operands × RenameWidth |
| V0 RAT | 1 × 8 = 8 | 1 × 12 = 12 | 1 src × RenameWidth |
| VL RAT | 1 × 8 = 8 | 1 × 12 = 12 | 1 src × RenameWidth |

**Write ports:** `RabCommitWidth` (8 → 12) for both speculative and architectural tables.

**Free list scaling:**
- Must allocate 12 physical registers per cycle (up from 8)
- `MEFreeList` (integer) needs wider allocation port
- `StdFreeList` (FP, Vec, V0, VL) similarly needs wider dequeue

**Physical register file sizes** — may need modest increase to avoid stalls with more
in-flight instructions:

| Preg | Current | Proposed | Reasoning |
|------|---------|----------|-----------|
| IntPreg | 224 | 288 | 50% more in-flight instr → need ~50% more physical regs |
| FpPreg | 256 | 320 | Same ratio |
| VfPreg | 128 | 160 | Same ratio |
| V0Preg | 22 | 32 | Modest increase |
| VlPreg | 32 | 40 | Modest increase |

### 6.3 Dispatch Stage

Source: `backend/dispatch/Dispatch.scala`

Dispatch accepts `RenameWidth` (8) instructions and routes them to issue queues based on
FuType. With 12-wide rename, dispatch must handle 12 instructions per cycle.

**Key concern:** Dispatch must distribute 12 instructions across ~20+ issue queues. The
current dispatch uses a priority-encoder-style mapping. At 12-wide, the combinational
depth of the dispatch crossbar grows but remains manageable (each instruction independently
checks FuType → IQ mapping).

### 6.4 ROB and RAB

Source: `backend/rob/Rob.scala`

| Parameter | Current | Proposed | Reasoning |
|-----------|---------|----------|-----------|
| `RobSize` | 352 | 512 | 12-wide needs deeper ROB to tolerate same latency |
| `RabSize` | 352 | 512 | Matches ROB depth |
| `RobCommitWidth` | 8 | 12 | Match pipeline width |
| `RabCommitWidth` | 8 | 12 | Match pipeline width |
| `VTypeBufferSize` | 64 | 96 | Proportional scaling |

**ROB entry width** does not change — each entry holds the same metadata. Only the number
of entries and the commit/enqueue port count grow.

**Walk width:** ROB walk (on misprediction/exception) implicitly uses commit-width resources.
At 12-wide, recovery is 50% faster in cycles but touches 50% more state per cycle.

---

## 7. Execution Resource Scaling

### 7.1 Design Principle

A 12-wide pipeline that cannot issue 12 instructions per cycle is wasted. The execution
backend must be scaled to match. The target is **sustained IPC of 6-8** (realistic for
server workloads), which requires sufficient FUs to handle the instruction mix without
excessive stalls.

Typical SPEC-like instruction mix: ~40% ALU, ~15% branch, ~25% load, ~10% store, ~10% FP/other.

For 12-wide dispatch:
- ~5 ALU ops/cycle → need 8 ALU units (headroom for bursts)
- ~2 branch ops/cycle → need 4 branch units
- ~3 load ops/cycle → need 4 load units
- ~1.2 store ops/cycle → need 3 store addr + 3 store data units
- ~1.2 FP ops/cycle → need 4-6 FP units (already have 4)

### 7.2 Proposed Integer Scheduler (12-wide)

```
IQ0:  ALU0 (ALU, CSR, Fence) + BJU0 (Branch, Jump)   | 24 entries, numEnq=3
IQ1:  ALU1 (ALU, Div)        + BJU1 (Branch, Jump)    | 24 entries, numEnq=3
IQ2:  ALU2 (ALU, I2F, VSet)  + BJU2 (Branch, Jump)    | 24 entries, numEnq=3
IQ3:  ALU3 (ALU, BKU)        + BJU3 (Branch, Jump)    | 24 entries, numEnq=3
IQ4:  ALU4 (ALU, MUL)                                  | 24 entries, numEnq=2
IQ5:  ALU5 (ALU, MUL)                                  | 24 entries, numEnq=2
IQ6:  ALU6 (ALU, MUL)                                  | 24 entries, numEnq=2  [NEW]
IQ7:  ALU7 (ALU, BKU)                                  | 24 entries, numEnq=2  [NEW]
IQ8:  LDU0 (Load)                                      | 24 entries, numEnq=2
IQ9:  LDU1 (Load)                                      | 24 entries, numEnq=2
IQ10: LDU2 (Load)                                      | 24 entries, numEnq=2
IQ11: LDU3 (Load)                                      | 24 entries, numEnq=2  [NEW]
IQ12: STA0 (Store Addr, MOU)                            | 20 entries, numEnq=2
IQ13: STA1 (Store Addr, MOU)                            | 20 entries, numEnq=2
IQ14: STA2 (Store Addr, MOU)                            | 20 entries, numEnq=2  [NEW]
IQ15: STD0 (Store Data)                                 | 20 entries, numEnq=2
IQ16: STD1 (Store Data)                                 | 20 entries, numEnq=2
IQ17: STD2 (Store Data)                                 | 20 entries, numEnq=2  [NEW]
```

**Changes from baseline:**
- +2 ALU units (ALU6, ALU7) → 8 total ALUs
- +1 branch unit (BJU3) → 4 total branches
- +1 load unit (LDU3) → 4 total loads
- +1 store addr (STA2) + 1 store data (STD2) → 3 total each
- Issue queue entries increased from 16-20 to 20-24
- Some IQs bumped from numEnq=2 to numEnq=3

### 7.3 Proposed FP Scheduler (12-wide)

```
FEX0: FALU, FMA, FCVT, FCMP, F2V    | 24 entries, numEnq=2
FEX1: FALU, FMA, FDIV                | 24 entries, numEnq=2
FEX2: FALU, FMA, FDIV                | 24 entries, numEnq=2
FEX3: FALU, FMA                      | 24 entries, numEnq=2
FEX4: FALU, FMA                      | 24 entries, numEnq=2  [NEW]
FEX5: FALU, FMA, FDIV                | 24 entries, numEnq=2  [NEW]
```

**Changes:** +2 FP units (FEX4, FEX5) → 6 total. Adds a third FDIV for better
throughput on FP-heavy workloads. Entries increased to 24.

### 7.4 Proposed Vector Scheduler (12-wide)

```
VFEX0: VIALU, VFALU, VFMA, VIMAC, VPPU, VIPU, VFCVT, VSet, VMove  | 20 entries, numEnq=2
VFEX1: VIALU, VFALU, VFMA, VFDIV, VIDIV                            | 20 entries, numEnq=2
VFEX2: VIALU, VFALU, VFMA                                          | 20 entries, numEnq=2  [NEW]
VLSU0: VLoad, VStore, VSegLoad, VSegStore                           | 20 entries, numEnq=2
VLSU1: VLoad, VStore                                                | 20 entries, numEnq=2
```

**Changes:** +1 vector arithmetic unit (VFEX2). Vector workloads are typically
register-file-bandwidth-limited, so modest scaling suffices.

### 7.5 FU Count Summary

| FU Type | Current (8-wide) | Proposed (12-wide) | Delta |
|---------|-------------------|--------------------|-------|
| ALU | 6 | 8 | +2 |
| MUL | 2 | 3 | +1 |
| DIV | 1 | 1 | — |
| Branch | 3 | 4 | +1 |
| Jump | 3 | 4 | +1 |
| Load | 3 | 4 | +1 |
| Store Addr | 2 | 3 | +1 |
| Store Data | 2 | 3 | +1 |
| FALU | 4 | 6 | +2 |
| FMA | 4 | 6 | +2 |
| FDIV | 2 | 3 | +1 |
| FCVT/FCMP | 1/1 | 1/1 | — |
| Vec Int | 2 | 3 | +1 |
| Vec FP | 2 | 3 | +1 |
| Vec LSU | 2 | 2 | — |
| **Total ExeUnits** | **~21** | **~30** | **+9** |

---

## 8. Register File and Bypass Network

### 8.1 Register File Port Scaling

This is the **most critical challenge** of the widening. Register file read ports scale
with execution unit count, and write ports scale with writeback bandwidth.

**Proposed integer register file:**

| Metric | Current | Proposed 12-wide |
|--------|---------|------------------|
| Entries | 224 | 288 |
| Banks | 4 | 8 |
| Read ports | ~12 | ~18 |
| Write ports | ~9 | ~12 |

**Banked register file design:** With 8 banks, each bank has ~2-3 read ports and ~1-2
write ports. Bank conflicts are resolved by the `RFReadArbiter` (`datapath/RFReadArbiter.scala`).
Increasing banks from 4 to 8 halves the per-bank port count, keeping timing manageable.

**Proposed FP register file:**

| Metric | Current | Proposed |
|--------|---------|----------|
| Entries | 256 | 320 |
| Banks | 1 | 2 |
| Read ports | ~12 | ~18 |
| Write ports | ~7 | ~10 |

FP register file currently uses a single bank. At 18 read ports, this becomes a timing
concern. Splitting to 2 banks is recommended.

### 8.2 Bypass Network

Source: `backend/datapath/BypassNetwork.scala`, `DataPath.scala`

The bypass network allows results from producing execution units to forward directly to
consuming units without going through the register file. This is **the** critical path
in wide superscalar designs.

**Current bypass matrix:** ~15 sources × ~15 sinks × up to 5 operands = ~75+ MUX paths

**Proposed bypass matrix:** ~22 sources × ~22 sinks × up to 5 operands = ~110+ MUX paths

The bypass network scales **quadratically** with the number of execution units. At 12-wide:

- Each bypass MUX selects from ~22 sources (up from ~15) — adds ~2 gate delays
- Total wire count increases ~2x
- Power consumption of bypass network increases ~2.2x

**Mitigation strategies:**

1. **Clustered bypass:** Partition execution units into 2-3 clusters (e.g., INT-A, INT-B,
   MEM). Full bypass within clusters, 1-cycle-delayed bypass between clusters. Trades IPC
   for timing closure.

2. **Selective bypass:** Not all FUs need to bypass to all others. The wakeup config
   (`iqWakeUpParams` at `Parameters.scala:463-484`) already encodes which sources wake
   which sinks. Use the same topology for bypass to avoid unnecessary paths.

3. **Register cache:** XiangShan already has `IntRegCacheSize = 24` and
   `MemRegCacheSize = 12` (`Parameters.scala:147-148`). Increasing these to 32/16
   reduces bypass pressure by serving recently-written values from a small, fast cache.

### 8.3 Register Cache Scaling

| Parameter | Current | Proposed |
|-----------|---------|----------|
| `IntRegCacheSize` | 24 | 36 |
| `MemRegCacheSize` | 12 | 18 |
| Total `RegCacheSize` | 36 | 54 |

---

## 9. Memory Subsystem Scaling

### 9.1 DCache Port Widening

Source: `Parameters.scala:153-156`, `cache/dcache/DCacheWrapper.scala`

| Component | Current | Proposed | Constraint |
|-----------|---------|----------|------------|
| `LoadPipelineWidth` | 3 | 4 | Must == LdExuCnt |
| `StorePipelineWidth` | 2 | 3 | Must == StaCnt |
| DCache meta read ports | 4 (load+1) | 5 (load+1) | +1 port |
| DCache data banks | 8 | 8 | Sufficient for 4 loads |
| TLB ports (BTLB) | 5 (3+2) | 7 (4+3) | Match load+store width |

**DCache complexity:** Adding a 4th load pipe and 3rd store pipe requires:
- Additional tag comparison logic per way
- Wider MSHR allocation (more concurrent misses)
- Higher L2 cache bandwidth demand

**TLB scaling:** 7 TLB ports is aggressive. Consider a 2-level micro-TLB:
- L0 micro-TLB: 4-entry fully-associative per load/store pipe (7 copies)
- L1 BTLB: shared 5-port, serves L0 misses

### 9.2 Load/Store Queue Scaling

| Component | Current | Proposed | Reasoning |
|-----------|---------|----------|-----------|
| `VirtualLoadQueueSize` | 72 | 112 | ~50% more in-flight loads |
| `LoadQueueRARSize` | 72 | 112 | Matches VLQ |
| `LoadQueueRAWSize` | 32 | 64 | Must be power of 2, next step up |
| `LoadQueueReplaySize` | 72 | 112 | Matches VLQ |
| `StoreQueueSize` | 56 | 84 | ~50% more in-flight stores |
| `LoadQueueNWriteBanks` | 8 | 8 | 112 % 8 = 0 (OK) |
| `StoreQueueNWriteBanks` | 8 | 12 | 84 % 12 = 0 (OK) |
| `StoreBufferSize` | 16 | 24 | Absorb higher store rate |
| `EnsbufferWidth` | 2 | 3 | Match StorePipelineWidth (3) |

### 9.3 Store Buffer

The constraint `EnsbufferWidth <= StorePipelineWidth` means at 3 store pipes, we can
increase sbuffer enqueue width to 3. The merge network widens from 2→16 to 3→24
priority encoding — modest overhead.

### 9.4 Prefetcher

With 4 load units, the prefetch training width increases from 3 to 4. The L1 prefetch
request port remains 1 (prefetch is opportunistic, not on critical path).

### 9.5 Vector Memory

| Component | Current | Proposed |
|-----------|---------|----------|
| `VecLoadPipelineWidth` | 2 | 2 |
| `VecStorePipelineWidth` | 2 | 2 |

Vector memory is unchanged — vector workloads are typically limited by vector register
file bandwidth, not scalar pipeline width.

---

## 10. Wakeup Network Redesign

### 10.1 Current Wakeup Topology

Source: `Parameters.scala:463-484`

The wakeup network defines which producers can speculatively wake up consumers:

```
Group 1: {ALU0-5, LDU0-2} → {ALU0-5, LDU0-2, STA0-1, STD0-1, BJU0-2}
Group 2: {FEX0-3} → {FEX0-3}
Group 3: {LDU0-2} → {FEX0-3}           (load→FP, 1 extra cycle)
Group 4: {FEX0-3} → {STD0-1}           (FP→store data, 1 extra cycle)
```

### 10.2 Proposed Wakeup Topology (12-wide)

```
Group 1: {ALU0-7, LDU0-3} → {ALU0-7, LDU0-3, STA0-2, STD0-2, BJU0-3}
Group 2: {FEX0-5} → {FEX0-5}
Group 3: {LDU0-3} → {FEX0-5}
Group 4: {FEX0-5} → {STD0-2}
```

**Wakeup broadcast wire count:**
- Group 1: 12 sources × 22 sinks = 264 wires (up from 9 × 16 = 144)
- Group 2: 6 sources × 6 sinks = 36 (up from 4 × 4 = 16)
- Group 3: 4 sources × 6 sinks = 24 (up from 3 × 4 = 12)
- Group 4: 6 sources × 3 sinks = 18 (up from 4 × 2 = 8)
- **Total: 342 wakeup paths** (up from 180) — **~1.9x increase**

### 10.3 Wakeup Timing Mitigation

At 342 paths, the wakeup comparator fanout becomes a timing concern. Options:

1. **Pipelined wakeup:** Split wakeup comparison into 2 stages (tag compare in cycle N,
   grant in cycle N+1). Costs 1 cycle of wakeup latency.

2. **Clustered wakeup:** Only broadcast within scheduler domains. Cross-domain wakeup
   uses writeback (1 extra cycle). Already partially done in the current design.

3. **Wakeup filtering:** Each IQ only listens to its relevant wakeup sources (already
   the case via `iqWakeUpParams`). Ensure the filtering is physical, not just logical.

---

## 11. Parameter Change Summary

### 11.1 Core Pipeline Parameters (`Parameters.scala`)

```scala
// Pipeline width
DecodeWidth:      8  → 12
RenameWidth:      8  → 12
CommitWidth:      8  → 12
RobCommitWidth:   8  → 12
RabCommitWidth:   8  → 12

// Structures
RobSize:          352 → 512
RabSize:          352 → 512
VTypeBufferSize:  64  → 96
IssueQueueSize:   20  → 24
IssueQueueCompEntrySize: 12 → 16

// Register files
intPreg.numEntries: 224 → 288
intPreg.numBank:    4   → 8
fpPreg.numEntries:  256 → 320
fpPreg.numBank:     1   → 2
vfPreg.numEntries:  128 → 160
v0Preg.numEntries:  22  → 32
vlPreg.numEntries:  32  → 40

// Register cache
IntRegCacheSize: 24 → 36
MemRegCacheSize: 12 → 18

// Memory
LoadPipelineWidth:  3  → 4
StorePipelineWidth: 2  → 3
VirtualLoadQueueSize: 72 → 112
LoadQueueRARSize:     72 → 112
LoadQueueRAWSize:     32 → 64
LoadQueueReplaySize:  72 → 112
StoreQueueSize:       56 → 84
StoreQueueNWriteBanks: 8 → 12
StoreBufferSize:      16 → 24
EnsbufferWidth:        2 → 3
```

### 11.2 Frontend Parameters (`ibuffer/Parameters.scala`)

```scala
Size:          48 → 96
NumReadBank:    8 → 12
NumWriteBank:   4 → 4  (or 6)
```

### 11.3 Execution Unit Changes

See Section 7 for full proposed scheduler configuration. Summary:
- +2 ALU, +1 MUL, +1 Branch, +1 Load, +1 StoreAddr, +1 StoreData
- +2 FP (FALU+FMA), +1 FDIV
- +1 Vector arithmetic
- New wakeup topology (Section 10)

---

## 12. Risk Analysis

### 12.1 Timing Closure (HIGH RISK)

**Concern:** Wider bypass network, larger register file, and more wakeup paths all add
gate delays to critical paths.

**Mitigation:**
- Banked register file (8 banks for int, 2 for FP)
- Clustered bypass with 1-cycle penalty between clusters
- Pipelined wakeup comparison
- Target: ≤5% frequency degradation from 8-wide baseline

### 12.2 Area and Power (MEDIUM RISK)

**Estimated area impact:**
- Execution units: +9 units → ~+40% backend area
- Register files: ~+30% (more entries + more banks)
- ROB/RAB: 352→512 entries → ~+45%
- Bypass network: ~+90% (quadratic scaling)
- Frontend: ~+10% (IBuffer only)
- **Total core area: ~+50-80%** (excluding L1 caches)

**Power:** Roughly proportional to area increase. Dynamic power scales with activity,
and a wider pipeline may have lower utilization per lane on branch-heavy code.

### 12.3 Diminishing IPC Returns (MEDIUM RISK)

**Concern:** Going from 8-wide to 12-wide will not yield 50% IPC improvement.

**Expected IPC scaling by workload type:**
- High-ILP integer (server, database): +25-35%
- Moderate-ILP (SPEC CPU2017 int): +15-25%
- FP-heavy (SPEC CPU2017 fp): +20-30%
- Branch-heavy (JavaScript engines): +5-15%
- Memory-bound: +0-10%

The BPU predicts one block per cycle. If basic blocks average 4-6 instructions,
sustaining 12 IPC requires multi-block fetch or very long basic blocks.

### 12.4 Verification Effort (HIGH RISK)

**Concern:** Wider pipeline creates more corner cases in dispatch, issue, and commit.

**Mitigation:**
- Maintain parameterization — 8-wide and 12-wide share the same RTL
- Run existing test suite at both widths
- Focus verification on dispatch stalls, wakeup corner cases, and ROB walk

### 12.5 L2/L3 Cache Bandwidth (MEDIUM RISK)

With 4 load + 3 store ports, L1 DCache miss traffic increases. The L2 cache (huancun/coupledL2)
may need:
- Wider request queue
- Higher MSHR count
- Increased NoC bandwidth to L3

This is **out of scope** for this document but should be addressed in a follow-up design.

---

## 13. Implementation Phases

### Phase 1: Parameter Widening and Compile Fix (2-3 weeks)

**Goal:** Set all width parameters to 12 and fix compilation errors.

1. Update `Parameters.scala`: all width params, queue sizes, preg sizes
2. Update `ibuffer/Parameters.scala`: Size, NumReadBank
3. Add new execution units to `intSchdParams`, `fpSchdParams`, `vecSchdParams`
4. Update `iqWakeUpParams` for new unit names
5. Update register file read/write port assignments for new units
6. Fix all `require()` assertion failures
7. Compile clean (no functional correctness yet)

### Phase 2: Functional Correctness (4-6 weeks)

**Goal:** Pass ISA compliance tests and basic benchmarks.

1. Run RISC-V ISA tests (rv64ui, rv64um, rv64uf, rv64ud, rv64ua)
2. Run difftest co-simulation against NEMU/Spike reference
3. Debug dispatch/issue corner cases with 12-wide
4. Verify ROB walk/recovery at 12-wide commit
5. Verify load/store queue with 4 load + 3 store ports
6. Run coremark, dhrystone as basic IPC sanity checks

### Phase 3: Performance Tuning (4-8 weeks)

**Goal:** Achieve target IPC improvement on representative workloads.

1. Profile dispatch stalls — tune IQ sizes and numEnq
2. Profile register file stalls — tune bank count and preg sizes
3. Profile memory stalls — tune LSQ sizes and DCache MSHR count
4. Evaluate bypass clustering tradeoffs
5. Run SPEC CPU2017 (int + fp) for IPC comparison vs 8-wide
6. Iterate on FU mix based on profiling data

### Phase 4: Timing and Physical Design (6-12 weeks)

**Goal:** Achieve timing closure at target frequency.

1. Synthesize 12-wide design, identify critical paths
2. Implement register file banking and bypass clustering as needed
3. Evaluate pipelined wakeup if timing is tight
4. Gate-level simulation for power estimation
5. Compare PPA (performance/power/area) vs 8-wide

---

## Appendix: File Reference

### Core Parameter Files

| File | Key Lines | Content |
|------|-----------|---------|
| `src/main/scala/xiangshan/Parameters.scala` | 80-84 | Pipeline widths |
| | 98-113 | Queue sizes, ROB/RAB |
| | 117-146 | Physical register file params |
| | 147-148 | Register cache sizes |
| | 153-165 | Memory pipeline widths, store buffer |
| | 326-459 | Scheduler and FU configuration |
| | 463-484 | Wakeup network topology |
| | 753-754 | Load/Store width constraints |

### Frontend Files

| File | Content |
|------|---------|
| `frontend/FrontendParameters.scala` | Fetch block size, IBuffer enqueue width |
| `frontend/ibuffer/Parameters.scala` | IBuffer size, bank counts, constraints |
| `frontend/ibuffer/IBuffer.scala:56-57` | NumReadBank >= DecodeWidth constraint |

### Backend Files

| File | Content |
|------|---------|
| `backend/decode/DecodeStage.scala:116` | DecodeUnit instantiation |
| `backend/rename/Rename.scala` | Rename width, free list, snapshot |
| `backend/rename/RenameTable.scala` | RAT port definitions |
| `backend/rename/BusyTable.scala` | Busy table allocation ports |
| `backend/dispatch/Dispatch.scala` | Dispatch → IQ routing |
| `backend/issue/IssueQueue.scala` | Issue queue structure |
| `backend/rob/Rob.scala:722` | RenameWidth <= CommitWidth constraint |
| `backend/datapath/DataPath.scala` | Data path orchestration |
| `backend/datapath/BypassNetwork.scala` | Bypass path structure |

### Memory Files

| File | Content |
|------|---------|
| `cache/dcache/DCacheWrapper.scala` | DCache port configuration |
| `cache/mmu/TLB.scala` | TLB port width |
| `mem/lsqueue/VirtualLoadQueue.scala:135` | DeqPtrMoveStride == CommitWidth |
| `mem/sbuffer/Sbuffer.scala:530` | EnsbufferWidth <= StorePipelineWidth |
| `mem/mdp/StoreSet.scala:67` | DecodeWidth == RenameWidth |
| `mem/mdp/WaitTable.scala:43` | DecodeWidth == RenameWidth |

### Configuration Files

| File | Content |
|------|---------|
| `top/Configs.scala:81-289` | TLMinimalConfig (8-wide) |
| `top/Configs.scala:500-548` | CHIBackendV2Config (6-wide) |
