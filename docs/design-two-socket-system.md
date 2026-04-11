# Building a Two-Socket Cache-Coherent System with XiangShan

**Status:** Reference Design Guide  
**Baseline:** XiangShan with CHI-enabled XSNoCTop configuration  
**Target:** Dual-socket system with full hardware cache coherence

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Single-Socket Anatomy (XSNoCTop)](#2-single-socket-anatomy-xsnoctop)
3. [CHI Protocol Interface](#3-chi-protocol-interface)
4. [Two-Socket Interconnect Architecture](#4-two-socket-interconnect-architecture)
5. [NodeID Assignment and Address Routing](#5-nodeid-assignment-and-address-routing)
6. [System Address Map (SAM)](#6-system-address-map-sam)
7. [Clock Domains and Async Bridges](#7-clock-domains-and-async-bridges)
8. [Cache Coherence: Directory and Snoop Flow](#8-cache-coherence-directory-and-snoop-flow)
9. [Interrupt and Timer Architecture](#9-interrupt-and-timer-architecture)
10. [Power Management Across Sockets](#10-power-management-across-sockets)
11. [RTL Generation and Build Flow](#11-rtl-generation-and-build-flow)
12. [Integration Module: DualSocketTop](#12-integration-module-dualsockettop)
13. [Verification Strategy](#13-verification-strategy)
14. [Physical Design Considerations](#14-physical-design-considerations)
15. [Appendix: Signal Reference](#appendix-signal-reference)

---

## 1. Architecture Overview

### 1.1 System Topology

```
┌─────────────────────────────────┐     ┌─────────────────────────────────┐
│          Socket 0               │     │          Socket 1               │
│                                 │     │                                 │
│  ┌───────┐ ┌───────┐           │     │           ┌───────┐ ┌───────┐  │
│  │Core 0 │ │Core 1 │  ...      │     │      ...  │Core N │ │Core N+1│ │
│  │  L1   │ │  L1   │           │     │           │  L1   │ │  L1   │  │
│  └───┬───┘ └───┬───┘           │     │           └───┬───┘ └───┬───┘  │
│      │         │                │     │                │         │      │
│  ┌───┴───┐ ┌───┴───┐           │     │           ┌───┴───┐ ┌───┴───┐  │
│  │  L2   │ │  L2   │           │     │           │  L2   │ │  L2   │  │
│  │(CHI RN)│ │(CHI RN)│          │     │          │(CHI RN)│ │(CHI RN)│ │
│  └───┬───┘ └───┬───┘           │     │           └───┬───┘ └───┬───┘  │
│      │         │                │     │                │         │      │
│      └────┬────┘                │     │                └────┬────┘      │
│           │ CHI                 │     │                     │ CHI       │
│      ┌────┴────┐                │     │                ┌────┴────┐      │
│      │ OpenLLC │                │     │                │ OpenLLC │      │
│      │ (HN-F)  │                │     │                │ (HN-F)  │      │
│      └────┬────┘                │     │                └────┬────┘      │
│           │ CHI SN              │     │                     │ CHI SN    │
│      ┌────┴────┐                │     │                ┌────┴────┐      │
│      │ OpenNCB │                │     │                │ OpenNCB │      │
│      │(CHI→AXI)│                │     │                │(CHI→AXI)│      │
│      └────┬────┘                │     │                └────┬────┘      │
│           │ AXI4                │     │                     │ AXI4      │
└───────────┼─────────────────────┘     └─────────────────────┼──────────┘
            │                                                  │
            ▼                                                  ▼
     ┌──────────┐                                       ┌──────────┐
     │  DDR MC  │                                       │  DDR MC  │
     │ (Local)  │                                       │ (Local)  │
     └──────────┘                                       └──────────┘

                    Cross-Socket CHI Link
            ┌─────────────────────────────────┐
            │                                 │
     Socket 0                           Socket 1
     L2 (CHI RN) ◄════ CHI Fabric ════► L2 (CHI RN)
            │      (External NoC /      │
            │       CXL / Custom)       │
            └─────────────────────────────────┘
```

### 1.2 What XiangShan Provides vs. What Must Be Built

| Component | Provided by XiangShan | Must be built/integrated |
|-----------|:--------------------:|:------------------------:|
| CPU cores with L1 caches | Yes | — |
| L2 cache with CHI RN-F port | Yes | — |
| OpenLLC (L3, CHI HN-F) | Yes | — |
| OpenNCB (CHI→AXI bridge) | Yes | — |
| CHI async clock domain bridge | Yes | — |
| Per-tile CHI `PortIO` interface | Yes | — |
| Low-power state machine (SysCo) | Yes | — |
| Cross-socket CHI fabric / NoC | — | Yes |
| Cross-socket snoop directory | — | Yes (or in NoC) |
| Chip-to-chip PHY (SerDes/CXL) | — | Yes |
| Global interrupt controller | — | Yes |
| Shared MMIO routing | — | Yes |
| Boot ROM / firmware | — | Yes |

---

## 2. Single-Socket Anatomy (XSNoCTop)

Each socket is instantiated as an `XSNoCTop` module. This is the recommended top-level
module for multi-socket integration (as opposed to `XSTop`, which includes the LLC and
memory bridges internally).

Source: `src/main/scala/top/XSNoCTop.scala`

### 2.1 XSNoCTop Module Hierarchy

```
XSNoCTop
├── BaseXSSocImp (clock, reset, DFT)
├── HasAsyncClockImp (noc_clock, noc_reset, soc_clock, clint_clock)
├── HasXSTile
│   └── XSTileWrap
│       ├── XSTile
│       │   ├── XSCore (CPU pipeline)
│       │   └── L2Top
│       │       └── TL2CHICoupledL2 (L2 cache with CHI port)
│       ├── CHIAsyncBridgeSource (optional)
│       └── Private CLINT (optional)
├── HasXSTileCHIImp → io_chi (CHI PortIO)
├── HasCoreLowPowerImp (power state machine)
└── HasSeperatedBusImp (optional separate TL/AXI bus)
```

### 2.2 XSNoCTop IO Ports

Source: `XSNoCTop.scala:50-84, 230-238, 266`

```
XSNoCTop IO:
├── clock                    : Input  Clock          — Core clock domain
├── reset                    : Input  AsyncReset     — Core reset
├── noc_clock                : Input  Clock          — NoC clock domain (optional, for async bridge)
├── noc_reset                : Input  AsyncReset     — NoC reset
├── soc_clock                : Input  Clock          — SoC peripheral clock
├── soc_reset                : Input  AsyncReset     — SoC peripheral reset
├── clint_clock              : Input  Clock          — Timer clock
├── clint_reset              : Input  AsyncReset     — Timer reset
├── io_chi                   : Output PortIO         — CHI interface (all channels)
├── io.dft                   : Input  SramBroadcastBundle (optional)
├── io.dft_reset             : Input  DFTResetSignals    (optional)
├── io.lp                    :        LowPowerIO         (optional)
│   ├── i_cpu_sw_rst_n       : Input  Bool           — Software reset (active low)
│   ├── i_cpu_iso_en         : Input  Bool           — Isolation enable
│   ├── i_cpu_pwrdown_req_n  : Input  Bool           — Power-down request
│   ├── o_cpu_pwrdown_ack_n  : Output Bool           — Power-down acknowledge
│   └── o_cpu_no_op          : Output Bool           — CPU idle (safe to power off)
├── tileio.hartId            : Input  UInt           — Hardware thread ID
├── tileio.nodeID            : Input  UInt           — CHI Node ID
├── tileio.riscv_rst_vec     : Input  UInt(48.W)     — Reset vector address
├── tileio.riscv_halt        : Output Bool           — CPU halted
├── tileio.riscv_critical_error : Output Bool        — Critical error
├── tileio.hartResetReq      : Input  Bool           — Per-hart reset request
└── tileio.hartIsInReset     : Output Bool           — Hart is in reset
```

**Key point:** `XSNoCTop` exposes a raw CHI `PortIO` — it does NOT include the LLC,
memory bridges, or peripheral controllers. Those must be instantiated at the socket or
system level.

### 2.3 Contrast: XSNoCTop vs XSTop

| Feature | XSTop | XSNoCTop |
|---------|-------|----------|
| Cores | Multi-core (NUM_CORES) | **Single tile** |
| L3 / OpenLLC | Instantiated internally | **Not included** |
| OpenNCB bridges | Instantiated internally | **Not included** |
| CHI routing | Internal (route/bind) | **External** (raw io_chi) |
| PLIC / Timer | Included | **Not included** (private CLINT optional) |
| Memory ports | AXI4 to DDR MC | **None** — via CHI |
| Multi-socket use | Not designed for it | **Designed for it** |

For a two-socket system, you use `XSNoCTop` as the per-tile building block and build
the LLC, bridges, interrupt controllers, and cross-socket fabric externally.

---

## 3. CHI Protocol Interface

### 3.1 PortIO Structure

Source: `coupledL2.tl2chi.PortIO` (imported at `XSNoCTop.scala:35`)

The CHI PortIO follows the AMBA CHI specification with these channels:

```
PortIO
├── tx (Transmit — from this node to the interconnect)
│   ├── req : DecoupledIO[CHIBundleREQ]    — Request channel (ReadNoSnp, WriteNoSnpFull, etc.)
│   ├── rsp : DecoupledIO[CHIBundleRSP]    — Response channel (CompAck, SnpResp, etc.)
│   └── dat : DecoupledIO[CHIBundleDAT]    — Data channel (CompData, SnpRespData, etc.)
├── rx (Receive — from the interconnect to this node)
│   ├── snp : DecoupledIO[CHIBundleSNP]    — Snoop channel (SnpShared, SnpClean, SnpUnique, etc.)
│   │   └── flitpend : Bool               — Flit pending hint (for clock gating)
│   ├── rsp : DecoupledIO[CHIBundleRSP]    — Response channel (RetryAck, Comp, etc.)
│   │   └── flitpend : Bool               — Flit pending hint
│   └── dat : DecoupledIO[CHIBundleDAT]    — Data channel (CompData, etc.)
│       └── flitpend : Bool               — Flit pending hint
├── syscoreq : Output Bool                 — System coherency request
└── syscoack : Input  Bool                 — System coherency acknowledge
```

### 3.2 CHI Issue Versions

Source: `SoC.scala:90-94`, `Makefile:52`

| CHI Issue | NodeID Width | Max Nodes | Default |
|-----------|-------------|-----------|---------|
| B | 7 bits | 128 | — |
| C | 9 bits | 512 | — |
| **E.b** | **11 bits** | **2048** | **Yes** |

For a two-socket system, CHI Issue E.b (default) is recommended — 11-bit NodeID provides
ample address space for all nodes across both sockets.

### 3.3 CHI Data Parameters

| Parameter | Value | Source |
|-----------|-------|--------|
| Address width | 48 bits | `SoC.scala:56` (`PAddrBits`) |
| Data width | 256 bits | `SoC.scala:141` (`L3OuterBusWidth`) |
| Cache line | 64 bytes | `SoC.scala:139` (`L3BlockSize`) |
| Data check | Odd parity | `Top.scala:127` |
| QoS | 4 bits | Per CHI spec |

### 3.4 CHI Node Types in the System

| Node | Type | Role | XiangShan Module |
|------|------|------|-----------------|
| L2 cache (per core) | RN-F | Fully coherent requestor | `TL2CHICoupledL2` |
| OpenLLC (L3) | HN-F | Fully coherent home node, directory | `OpenLLC` |
| Memory bridge | SN-F | Subordinate node to DDR | `OpenNCB` |
| MMIO bridge | SN-I | I/O subordinate node | `OpenNCB` |

---

## 4. Two-Socket Interconnect Architecture

### 4.1 Architecture Options

There are three main approaches for the cross-socket coherent interconnect:

#### Option A: Shared CHI Fabric (Recommended for FPGA / Small Systems)

```
         ┌──────────────────────────────────────────────────┐
         │              Shared CHI Interconnect              │
         │    (e.g., ARM CMN-700 or custom CHI crossbar)    │
         │                                                  │
         │  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐ │
         │  │ HN-F 0 │  │ HN-F 1 │  │ HN-F 2 │  │ HN-F 3 │ │  ← Directory/LLC banks
         │  └────────┘  └────────┘  └────────┘  └────────┘ │
         │                                                  │
         └──┬──┬──┬──┬──────────────────────┬──┬──┬──┬─────┘
            │  │  │  │                      │  │  │  │
         Socket 0 RN-Fs                  Socket 1 RN-Fs
```

A single, flat CHI fabric spans both sockets. The OpenLLC instances act as HN-F nodes in
the shared fabric. This is simple but requires a CHI fabric IP.

#### Option B: Per-Socket LLC with Cross-Socket Snoop (Recommended for ASIC)

```
  Socket 0                                           Socket 1
  ┌────────────────┐                        ┌────────────────┐
  │ L2s → OpenLLC  │                        │ L2s → OpenLLC  │
  │   (local HN-F) │                        │   (local HN-F) │
  └───────┬────────┘                        └───────┬────────┘
          │ CHI SN                                  │ CHI SN
          ▼                                         ▼
  ┌───────────────┐    Cross-Socket Link    ┌───────────────┐
  │   CXL / CHI   │◄══════════════════════►│   CXL / CHI   │
  │  Bridge + Dir  │    (chip-to-chip)      │  Bridge + Dir  │
  └───────┬───────┘                        └───────┬───────┘
          │ AXI4                                   │ AXI4
          ▼                                        ▼
     Local DDR MC                             Local DDR MC
```

Each socket has its own OpenLLC (L3). A cross-socket bridge handles remote cache line
requests by issuing snoops through the CHI fabric to the remote socket's HN-F. This
is the NUMA-style approach used in most multi-socket server processors.

#### Option C: Directory-Based Coherence at System Level

A system-level directory sits between the two sockets, tracking which socket owns each
cache line. This is the cleanest but requires building a custom directory controller.

### 4.2 Recommended Architecture (Option B)

For a practical two-socket XiangShan system, Option B is recommended because:

1. Each socket is self-contained — XiangShan already provides the full per-socket stack
   (cores → L2 → OpenLLC → OpenNCB → AXI4 memory)
2. Cross-socket traffic only occurs on remote cache misses
3. Per-socket OpenLLC serves as the local HN-F/directory
4. The cross-socket link can be any coherent transport (CXL, custom CHI bridge)

### 4.3 Required Cross-Socket Bridge

The cross-socket bridge must implement:

1. **Remote read**: Socket 0 L2 misses in Socket 0 OpenLLC → bridge forwards CHI ReadShared
   to Socket 1 OpenLLC → Socket 1 snoops its local L2s → returns data
2. **Remote write**: Similar flow with WriteUnique/MakeUnique and invalidation snoops
3. **Snoop forwarding**: When Socket 1 writes a line owned by Socket 0, the bridge sends
   SnpUnique to Socket 0
4. **Directory state**: Track which lines are cached remotely (coarse-grain is sufficient
   for a two-socket system — e.g., per-line "remote-present" bit in each OpenLLC)

---

## 5. NodeID Assignment and Address Routing

### 5.1 NodeID Scheme

Each CHI node in the system needs a unique NodeID. XiangShan uses the convention from
`Top.scala:374-387`:

**Within a single XSTop (existing convention):**
```
Core MMIO bridges:  NodeID = NumCores + i        (per-core MMIO)
LLC aggregate:      NodeID = NumCores * 2         (shared LLC)
```

**Proposed two-socket NodeID scheme (CHI E.b, 11-bit NodeID):**

```
Socket 0:
  Core 0 (RN-F):    NodeID = 0
  Core 1 (RN-F):    NodeID = 1
  ...
  Core N-1 (RN-F):  NodeID = N-1
  MMIO bridge 0:    NodeID = N
  MMIO bridge 1:    NodeID = N+1
  ...
  OpenLLC 0 (HN-F): NodeID = 2N
  Memory SN 0:      NodeID = 2N+1

Socket 1:
  Core N (RN-F):    NodeID = 256      (use upper bits to distinguish sockets)
  Core N+1 (RN-F):  NodeID = 257
  ...
  Core 2N-1 (RN-F): NodeID = 256+N-1
  MMIO bridge N:    NodeID = 256+N
  ...
  OpenLLC 1 (HN-F): NodeID = 256+2N
  Memory SN 1:      NodeID = 256+2N+1

Cross-Socket:
  X-Socket bridge:  NodeID = 512
```

Using bit 8 (value 256) to distinguish Socket 1 from Socket 0 simplifies address
decoding — the interconnect can route based on the upper NodeID bit.

### 5.2 NodeID Propagation in XiangShan

The NodeID flows from the top-level input down to the L2 cache:

```
XSNoCTop.tileio.nodeID                     ← SET BY INTEGRATOR
  → XSTileWrap.module.io.nodeID
    → XSTile.module.io.nodeID
      → L2Top.module.io.nodeID
        → TL2CHICoupledL2.io_nodeID         ← USED IN CHI TRANSACTIONS
```

Source: `XSNoCTop.scala:250`, `L2Top.scala:359`

Each tile's `nodeID` input must be driven by the integrator (your DualSocketTop module)
with the correct unique value.

### 5.3 Address-Based CHI Routing

Within XSTop, CHI traffic is routed by address:

Source: `Top.scala:372-376`

```scala
route(
  core.module.io.chi.get,
  Map(
    (AddressSet(0x0L, 0x00007fffffffL), NumCores + i)      // MMIO: 0x0 - 0x7FFFFFFF
  ) ++ AddressSet(0x0L, 0xffffffffffffL)
    .subtract(AddressSet(0x0L, 0x00007fffffffL))
    .map(addr => (addr, NumCores * 2)).toMap                // LLC: everything else
)
```

**Translation:** Addresses in `0x0 - 0x7FFFFFFF` are routed to the per-core MMIO bridge.
All other addresses (primarily `0x80000000+`) are routed to the shared OpenLLC.

For a two-socket system, the cross-socket bridge must participate in this routing:

```
Address                    → Target
0x00000000 - 0x7FFFFFFF    → Local MMIO bridge (per-core)
0x80000000 - 0x3FFFFFFFFFF → Local OpenLLC (if address maps to local DDR)
0x80000000 - 0x3FFFFFFFFFF → Cross-socket bridge (if address maps to remote DDR)
```

---

## 6. System Address Map (SAM)

### 6.1 Default XiangShan Address Map

Source: `SoC.scala:53-73`

```
0x00000000 - 0x0FFFFFFF   Peripherals (PMA: I/O, non-cacheable)
  0x00000000 - 0x0FFFFFFF   Reserved
  0x10000000 - 0x1FFFFFFF   Flash / Boot ROM (R, W)
  0x20000000 - 0x2FFFFFFF   Executable I/O region (R, W, X)
  0x30010000 - 0x3004FFFF   Device MMIO (R, W)
  0x30050000 - 0x30FFFFFF   GPU space (R, W, may be cacheable)
  0x38000000 - 0x3803FFFF   TIMER (per-SoC or per-socket)
  0x38010000 - 0x38010FFF   BEU (Bus Error Unit)
  0x38020000 - 0x38021FFF   Debug Module (R, W / R, W, X)
  0x38022000 - 0x38FFFFFF   Extended debug (R, W)
  0x39000000 - 0x39001FFF   Additional I/O (R, W)
  0x39002000 - 0x39FFFFFF   Extended I/O (R, W)
  0x3A000000 - 0x3A000FFF   PLL control
  0x3A800000                IMSIC (M-mode)
  0x3B000000                IMSIC (S/G-mode)
  0x3C000000 - 0x3FFFFFFF   PLIC
  0x40600000 - 0x4060003F   UART

0x80000000 - 0x7FFFFFFFFFF  Main Memory (cacheable, atomic, R, W, X)
  ← This is where DDR lives (PmemRanges)
```

### 6.2 Proposed Two-Socket Address Map

For two sockets with independent DDR controllers, split the memory space:

```
Shared Peripherals:
  0x00000000 - 0x7FFFFFFF    Same as single-socket (MMIO, boot ROM, PLIC, etc.)

Socket 0 Local DDR:
  0x00_8000_0000 - 0x3F_FFFF_FFFF    Socket 0 memory (up to 255 GB)

Socket 1 Local DDR:
  0x40_0000_0000 - 0x7F_FFFF_FFFF    Socket 1 memory (up to 255 GB)

  (Above 0x80_0000_0000: reserved or extended)
```

**Routing rule:** Each OpenLLC/HN-F owns a contiguous address range. When an L2 (RN-F)
issues a request:

- If the address falls in the local socket's range → handled locally by the local HN-F
- If the address falls in the remote socket's range → forwarded via the cross-socket bridge
  to the remote HN-F

This is a NUMA (Non-Uniform Memory Access) topology — local memory access is faster than
remote.

### 6.3 PMA Configuration for Two Sockets

The PMA (Physical Memory Attributes) table in `SoC.scala:58-72` must be extended to cover
the remote socket's memory range as cacheable:

```scala
PMAConfigs: Seq[PMAConfigEntry] = Seq(
  // ... existing entries ...
  // Socket 0 local DDR
  PMAConfigEntry(0x80000000L, c = true, atomic = true, a = 1, x = true, w = true, r = true),
  // Socket 1 remote DDR (same attributes — cacheable, atomic)
  PMAConfigEntry(0x4000000000L, c = true, atomic = true, a = 1, x = true, w = true, r = true),
)
```

---

## 7. Clock Domains and Async Bridges

### 7.1 Clock Domain Architecture

Source: `XSNoCTop.scala:76-87`

Each XSNoCTop tile has **four independent clock domains**:

```
┌─────────────────────────────────────────────────────┐
│  XSNoCTop                                           │
│                                                     │
│  ┌─────────────────────┐    ┌───────────────────┐  │
│  │   CPU Core + L2     │    │   CHI Async       │  │
│  │   (clock domain)    │───►│   Bridge          │  │
│  │                     │    │   (noc_clock)      │  │
│  └─────────────────────┘    └────────┬──────────┘  │
│                                      │ io_chi      │
│  ┌─────────────────────┐             │             │
│  │   SoC Peripherals   │             │             │
│  │   (soc_clock)       │             │             │
│  └─────────────────────┘             │             │
│                                      │             │
│  ┌─────────────────────┐             │             │
│  │   Timer / CLINT     │             │             │
│  │   (clint_clock)     │             │             │
│  └─────────────────────┘             │             │
└──────────────────────────────────────┼─────────────┘
                                       ▼
                              CHI fabric (noc_clock domain)
```

### 7.2 Async Bridge Configuration

Source: `SoC.scala:116-118`

```scala
EnableCHIAsyncBridge:    Some(AsyncQueueParams(depth = 16, sync = 3, safe = false))
EnableClintAsyncBridge:  Some(AsyncQueueParams(depth = 8,  sync = 3, safe = false))
SeperateBusAsyncBridge:  Some(AsyncQueueParams(depth = 1,  sync = 3, safe = false))
```

- **depth**: Number of entries in the async FIFO (16 for CHI — handles burst traffic)
- **sync**: Synchronizer stages (3 — standard for metastability hardening)
- **safe**: Whether to include reset synchronization (false — handled separately)

### 7.3 Two-Socket Clock Strategy

```
                    ┌──────────────────┐
                    │  Reference Clock  │
                    │    (100 MHz)      │
                    └───────┬──────────┘
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
         ┌────────┐   ┌────────┐   ┌────────┐
         │PLL Sock0│   │PLL NoC │   │PLL Sock1│
         │ 2 GHz  │   │ 1 GHz  │   │ 2 GHz  │
         └───┬────┘   └───┬────┘   └───┬────┘
             │             │             │
        Socket 0       Shared NoC    Socket 1
        cpu_clock      noc_clock     cpu_clock
```

**Recommended:** Each socket runs its own core clock (can be different frequencies for
power/thermal management). The CHI NoC fabric runs on a shared `noc_clock`. The async
bridges in XSNoCTop handle the domain crossing.

For the cross-socket link (chip-to-chip), an additional PHY-level clock recovery is needed
(SerDes PLL), which is outside the digital logic.

---

## 8. Cache Coherence: Directory and Snoop Flow

### 8.1 Coherence Protocol

XiangShan uses CHI's directory-based coherence. The OpenLLC acts as HN-F (Home Node -
Fully coherent) and maintains a directory of cache line states.

**Cache states (CHI):**

| State | Meaning |
|-------|---------|
| I (Invalid) | Not cached |
| SC (Shared Clean) | Cached, read-only, matches memory |
| UC (Unique Clean) | Cached exclusively, matches memory |
| UD (Unique Dirty) | Cached exclusively, modified |
| SD (Shared Dirty) | Cached shared, but this copy is dirty (CHI E.b) |

### 8.2 Single-Socket Snoop Flow (Existing)

```
Core A (RN-F)          OpenLLC (HN-F)          Core B (RN-F)
    │                       │                       │
    │── ReadShared ────────►│                       │
    │                       │── SnpShared ─────────►│
    │                       │                       │
    │                       │◄── SnpRespData ───────│
    │                       │                       │
    │◄── CompData ──────────│                       │
    │                       │                       │
    │── CompAck ───────────►│                       │
```

### 8.3 Cross-Socket Snoop Flow (New — Must Be Implemented)

When Core A on Socket 0 reads a line owned by Core B on Socket 1:

```
Socket 0                    Cross-Socket              Socket 1
Core A    OpenLLC 0         Bridge           OpenLLC 1    Core B
  │           │                │                 │           │
  │─ReadShared►│               │                 │           │
  │           │ (miss, addr    │                 │           │
  │           │  maps to S1)   │                 │           │
  │           │──CHI Req──────►│                 │           │
  │           │                │──CHI ReadShared►│           │
  │           │                │                 │─SnpShared►│
  │           │                │                 │           │
  │           │                │                 │◄SnpResp───│
  │           │                │◄──CompData──────│           │
  │           │◄──CompData─────│                 │           │
  │◄CompData──│                │                 │           │
  │─CompAck──►│                │                 │           │
```

**The cross-socket bridge must:**
1. Intercept CHI requests from the local HN-F whose addresses map to remote memory
2. Forward them across the chip-to-chip link to the remote HN-F
3. Return responses back to the originating HN-F
4. Handle snoop forwarding in both directions

### 8.4 Directory Extension for Two Sockets

Each OpenLLC directory entry must be extended with a "remote presence" bit:

```
Directory Entry (extended):
  ┌──────────┬───────────┬──────────────────┬────────────────┐
  │  Tag     │  State    │  Local Sharers   │  Remote Present│
  │          │ (I/SC/UC/ │  (bitmask of     │  (1 bit: is    │
  │          │  UD/SD)   │  local RN-Fs)    │  cached on     │
  │          │           │                  │  other socket) │
  └──────────┴───────────┴──────────────────┴────────────────┘
```

When "Remote Present" is set, the HN-F must send a cross-socket snoop before granting
exclusive access. This can be implemented as an additional snoop target in the OpenLLC's
snoop filter.

---

## 9. Interrupt and Timer Architecture

### 9.1 Current Interrupt Topology (Single Socket)

Source: `Top.scala:162-167`, `SoC.scala:503-508`

```
External IRQs ──► PLIC ──► Core 0..N-1
Debug Module  ──► Core 0..N-1  (debug interrupt)
Timer/CLINT   ──► Core 0..N-1  (mtip, msip)
NMI           ──► Core 0..N-1  (non-maskable)
IMSIC (AIA)   ──► Core 0..N-1  (MSI-based, S/G mode)
```

### 9.2 Two-Socket Interrupt Options

#### Option A: Per-Socket PLIC + Global Interrupt Router

```
                     ┌────────────────┐
                     │  Global IRQ    │
External IRQs ──────►│  Router        │
                     └───┬────────┬───┘
                         │        │
                    ┌────▼──┐  ┌──▼────┐
                    │PLIC S0│  │PLIC S1│
                    └───┬───┘  └───┬───┘
                        │          │
                  Socket 0      Socket 1
                  cores         cores
```

Each socket has its own PLIC. The global router distributes external interrupts to the
appropriate socket's PLIC based on affinity configuration.

#### Option B: Shared PLIC (Simpler)

A single PLIC instance serves all cores across both sockets. PLIC MMIO access from Socket 1
cores routes through the cross-socket MMIO bridge. This adds latency to interrupt
claim/complete operations on the remote socket.

#### Option C: IMSIC / AIA (Recommended for Scalability)

XiangShan supports RISC-V AIA (Advanced Interrupt Architecture) with IMSIC. In AIA:
- Interrupts are delivered as MSIs (Message-Signaled Interrupts)
- Each hart has a local IMSIC receiver
- Interrupt routing is done by writing to memory-mapped IMSIC registers
- No central PLIC needed for inter-socket interrupts

Source: `SoC.scala:106-114`

```scala
IMSICParams: aia.IMSICParams = aia.IMSICParams(
  imsicIntSrcWidth = 9,      // Up to 512 interrupt sources
  mAddr = 0x3A800000,        // M-mode IMSIC address
  sgAddr = 0x3B000000,       // S/G-mode IMSIC address
  geilen = 7,                // Guest external interrupt lines
  vgeinWidth = 6,            // Virtual guest EI width
  iselectWidth = 12,
  EnableImsicAsyncBridge = true,
  HasTEEIMSIC = false
)
```

### 9.3 Timer / CLINT

Source: `SoC.scala:119-121`

```scala
UsePrivateClint: Boolean = false   // When true, each tile has its own CLINT
```

**For two-socket systems:** Set `UsePrivateClint = true`. Each tile gets a private CLINT
timer, eliminating cross-socket timer access latency. The `clint_clock` input drives the
reference time base.

The `mtime` register must be synchronized across sockets — either by driving both sockets'
`clint_clock` from the same source, or by using a shared time counter broadcast.

---

## 10. Power Management Across Sockets

### 10.1 Per-Socket Power State Machine

Source: `XSNoCTop.scala:89-121`

XSNoCTop includes a low-power state machine with these states:

```
sIDLE → sL2FLUSH → sWAITWFI → sEXITCO → sWAITQ → sQREQ → sPOFFREQ
```

**State transitions:**
1. **sIDLE**: Normal operation
2. **sL2FLUSH**: L2 cache flush initiated (CSR write triggers `l2_flush_en`)
3. **sWAITWFI**: Wait for CPU to execute WFI instruction
4. **sEXITCO**: Exit coherency — deassert `syscoreq`, wait for `syscoack` to deassert
5. **sWAITQ**: Wait for Q-channel handshake
6. **sQREQ**: Q-channel request phase
7. **sPOFFREQ**: Power-off request — `o_cpu_no_op` asserted, safe to gate/remove power

### 10.2 CHI System Coherency (SysCo) Signals

Source: `XSNoCTop.scala:106, 117`

```
syscoreq (output): Asserted when this node is part of the coherence domain
syscoack (input):  Asserted by the interconnect to acknowledge coherency participation
```

**For two-socket power management:**
- Before powering down Socket 1, its tiles must exit coherency:
  1. Flush L2 caches (all dirty lines written back)
  2. Deassert `syscoreq` on all Socket 1 tiles
  3. Wait for `syscoack` to deassert (interconnect acknowledges removal)
  4. Socket 1's lines are no longer tracked in the coherence domain
  5. Safe to power-gate Socket 1

### 10.3 WFI Clock Gating

Source: `XSNoCTop.scala:124-162`

When `WFIClockGate = true`, the tile automatically gates its core clock on WFI. The clock
is re-enabled when:
- An interrupt arrives (msip, mtip, meip, seip, NMI, debug)
- A CHI snoop arrives (`flitpend` signal from any RX channel)
- MSI info becomes valid (`msi_info_vld`)

For two-socket: The `flitpend` wake-up path is critical — cross-socket snoops must be
able to wake a WFI-sleeping core on the remote socket. Ensure the chip-to-chip PHY
maintains `flitpend` signaling even when the core is clock-gated.

---

## 11. RTL Generation and Build Flow

### 11.1 Building a Single XSNoCTop Tile

```bash
cd /path/to/XiangShan

# Generate RTL for a single XSNoCTop tile (CHI E.b)
make verilog CONFIG=XSNoCTopConfig NUM_CORES=1 ISSUE=E.b

# The generated Verilog will be at:
#   build/rtl/XSNoCTopXSTop.sv    (or similar)
```

Source: `Makefile:36, 50-52`

```makefile
TOP = $(XSTOP_PREFIX)XSTop      # With XSNoCTop prefix: "XSNoCTopXSTop"
CONFIG ?= TLConfig              # Override with XSNoCTopConfig
NUM_CORES ?= 1                  # Cores per tile (typically 1 for XSNoCTop)
ISSUE ?= E.b                    # CHI issue version
```

### 11.2 Building with Custom Parameters

For a two-socket config, you may want to customize parameters via YAML:

```yaml
# two_socket_config.yml
XSTopPrefix: "Socket"
CHIIssue: "E.b"
EnableCHIAsyncBridge: true
UsePrivateClint: true
WFIClockGate: true
EnablePowerDown: true
```

```bash
make verilog CONFIG=XSNoCTopConfig YAML_CONFIG=two_socket_config.yml
```

### 11.3 Generating Both Sockets

Since both sockets use the same tile RTL (differentiated only by `nodeID` and `hartId`
inputs), you generate the XSNoCTop tile once and instantiate it twice in your top-level
integration wrapper.

```bash
# Step 1: Generate the tile RTL
make verilog CONFIG=XSNoCTopConfig NUM_CORES=1 ISSUE=E.b

# Step 2: Write DualSocketTop wrapper (SystemVerilog / Chisel)
#         that instantiates 2x XSNoCTop + LLC + bridges + fabric
```

---

## 12. Integration Module: DualSocketTop

### 12.1 Module Architecture

Below is the recommended structure for the DualSocketTop integration module. This would
be written as a new Chisel module or SystemVerilog wrapper.

```
DualSocketTop
├── Socket 0: XSNoCTop (tile 0..N-1)
│   ├── io_chi ──► CHI Router 0
│   └── tileio.nodeID := 0.U
├── Socket 1: XSNoCTop (tile N..2N-1)
│   ├── io_chi ──► CHI Router 1
│   └── tileio.nodeID := 256.U
├── OpenLLC 0 (local to Socket 0)
│   ├── rn(0) ◄── CHI Router 0 (LLC port)
│   ├── sn ──► OpenNCB 0 ──► AXI4 Memory 0
│   └── nodeID := (2*N).U
├── OpenLLC 1 (local to Socket 1)
│   ├── rn(0) ◄── CHI Router 1 (LLC port)
│   ├── sn ──► OpenNCB 1 ──► AXI4 Memory 1
│   └── nodeID := (256 + 2*N).U
├── MMIO Bridge 0 ◄── CHI Router 0 (MMIO port)
│   └── ──► AXI4 Peripheral Bus
├── MMIO Bridge 1 ◄── CHI Router 1 (MMIO port)
│   └── ──► AXI4 Peripheral Bus
├── Cross-Socket Bridge
│   ├── port_0 ◄──► CHI Router 0
│   └── port_1 ◄──► CHI Router 1
├── Shared PLIC / per-socket IMSIC
├── Debug Module
└── Clock / Reset Generation
```

### 12.2 CHI Router (Per-Socket)

Each socket needs a CHI router that directs traffic from the tile's `io_chi` to the
correct destination based on address:

```
CHI Router (per-socket):
  Input:  io_chi from XSNoCTop tile
  Output: 3 destinations
    ├── MMIO:           Addresses 0x0 - 0x7FFFFFFF          → MMIO Bridge
    ├── Local LLC:      Addresses in local DDR range         → Local OpenLLC
    └── Cross-Socket:   Addresses in remote DDR range        → X-Socket Bridge
```

This is equivalent to the `route()` + `bind()` logic in `Top.scala:372-378`, but with
an additional cross-socket destination.

### 12.3 Pseudo-Code (Chisel-Style)

```scala
class DualSocketTop(implicit p: Parameters) extends RawModule {
  val N = 1  // cores per socket

  // ── Clock / Reset ──
  val cpu_clock_0  = IO(Input(Clock()))
  val cpu_clock_1  = IO(Input(Clock()))
  val noc_clock    = IO(Input(Clock()))
  val cpu_reset    = IO(Input(AsyncReset()))
  val noc_reset    = IO(Input(AsyncReset()))

  // ── Memory Ports ──
  val mem_axi_0    = IO(new AXI4Bundle(...))   // Socket 0 DDR
  val mem_axi_1    = IO(new AXI4Bundle(...))   // Socket 1 DDR

  // ── Socket 0 ──
  val socket0 = Module(new XSNoCTop())
  socket0.clock        := cpu_clock_0
  socket0.reset        := cpu_reset
  socket0.noc_clock.get := noc_clock
  socket0.noc_reset.get := noc_reset
  socket0.tileio.hartId     := 0.U
  socket0.tileio.nodeID     := 0.U
  socket0.tileio.riscv_rst_vec := "h80000000".U

  // ── Socket 1 ──
  val socket1 = Module(new XSNoCTop())
  socket1.clock        := cpu_clock_1
  socket1.reset        := cpu_reset
  socket1.noc_clock.get := noc_clock
  socket1.noc_reset.get := noc_reset
  socket1.tileio.hartId     := 1.U
  socket1.tileio.nodeID     := 256.U
  socket1.tileio.riscv_rst_vec := "h80000000".U

  // ── OpenLLC 0 (Socket 0 L3) ──
  val llc0 = Module(new OpenLLC())
  llc0.io.nodeID := (2 * N).U

  // ── OpenLLC 1 (Socket 1 L3) ──
  val llc1 = Module(new OpenLLC())
  llc1.io.nodeID := (256 + 2 * N).U

  // ── CHI Routing ──
  // Socket 0: route io_chi to (MMIO bridge, local LLC, cross-socket)
  val router0 = Module(new CHIRouter(
    mmioRange  = AddressSet(0x0L, 0x7fffffffL),
    localRange = AddressSet(0x80000000L, 0x3f7fffffffL),   // Socket 0 DDR
    remoteRange = AddressSet(0x4000000000L, 0x3fffffffffL)  // Socket 1 DDR
  ))
  router0.io.in <> socket0.io_chi

  // Connect router outputs
  mmioBridge0.io.chi  <> router0.io.mmio
  llc0.io.rn(0)       <> router0.io.local
  xSocketBridge.io.s0 <> router0.io.remote

  // (Similar for Socket 1)

  // ── Memory ──
  val ncb0 = Module(new OpenNCB())  // LLC 0 → DDR 0
  ncb0.io.chi <> llc0.io.sn
  mem_axi_0   <> ncb0.io.axi4

  val ncb1 = Module(new OpenNCB())  // LLC 1 → DDR 1
  ncb1.io.chi <> llc1.io.sn
  mem_axi_1   <> ncb1.io.axi4
}
```

### 12.4 Multi-Core Per Socket

For multiple cores per socket, use `XSTop` (not `XSNoCTop`) with `NUM_CORES > 1` and
`CONFIG=CHIConfig`. Each `XSTop` already includes internal CHI routing, OpenLLC, and
OpenNCB. The cross-socket bridge then connects between two `XSTop` instances' memory ports.

Alternatively, instantiate multiple `XSNoCTop` tiles per socket and build the intra-socket
CHI fabric yourself for maximum control.

---

## 13. Verification Strategy

### 13.1 Single-Socket Verification

XiangShan already provides:

- **Difftest**: Co-simulation against NEMU/Spike reference model
- **CHILogger**: Protocol-level trace logging (`Top.scala:369-386`)
- **ISA tests**: rv64ui/um/uf/ud/ua compliance

```bash
# Build simulation binary with difftest
make emu CONFIG=XSNoCDiffTopConfig NUM_CORES=1 ISSUE=E.b

# Run ISA tests
./build/emu -i ready-to-run/rv64ui-p-add.bin
```

### 13.2 Two-Socket Verification Plan

| Phase | Focus | Method |
|-------|-------|--------|
| 1 | Single tile CHI compliance | CHI protocol checker on io_chi |
| 2 | Two tiles, shared LLC | Instantiate 2 XSNoCTop + 1 OpenLLC, run MP tests |
| 3 | Two tiles, two LLCs, no cross-socket | Each tile accesses only local memory |
| 4 | Cross-socket coherence | MOESI stress tests: producer-consumer, false sharing |
| 5 | Cross-socket atomics | LR/SC, AMO across socket boundary |
| 6 | Power management | SysCo exit/entry while remote socket is active |
| 7 | Performance | STREAM, GUPS, memory-latency benchmarks (local vs remote) |

### 13.3 Key Test Scenarios for Cross-Socket Coherence

1. **Basic shared read**: Core A (S0) writes, Core B (S1) reads — data must be visible
2. **Exclusive ownership transfer**: Core B (S1) writes after Core A (S0) held exclusive
3. **False sharing**: Adjacent bytes in same cache line modified by different sockets
4. **Atomic across sockets**: LR on S0, SC on S0, where line was last written by S1
5. **Back-invalidation**: S0 LLC evicts a line cached by S1 — must invalidate S1's copy
6. **SysCo exit under load**: S1 exits coherency while S0 is actively accessing shared data

---

## 14. Physical Design Considerations

### 14.1 Chip-to-Chip Link Options

| Technology | Bandwidth | Latency | Pins | Use Case |
|------------|-----------|---------|------|----------|
| CXL 2.0 (PCIe 5.0) | 64 GB/s | ~100-200 ns | PCIe lanes | Industry standard |
| CXL 3.0 (PCIe 6.0) | 128 GB/s | ~50-100 ns | PCIe lanes | Next-gen servers |
| Custom CHI-over-SerDes | Configurable | ~50-150 ns | Custom | Full control |
| UCIe (die-to-die) | 256+ GB/s | ~2-5 ns | Bump array | Chiplet / 2.5D |
| Direct parallel CHI | Highest | ~1-2 ns | 500+ wires | Same-package MCM |

### 14.2 Latency Budget

```
Local memory access (same socket):
  L1 hit:     1-3 cycles
  L2 hit:     8-15 cycles
  L3 hit:     20-40 cycles
  DDR:        100-200 cycles

Remote memory access (cross-socket):
  L1 hit:     1-3 cycles (if cached locally)
  L2 hit:     8-15 cycles (if cached locally)
  Remote L3:  L3 latency + link latency (60-100 cycles additional)
  Remote DDR: DDR latency + link latency (150-400 cycles total)
```

### 14.3 Bandwidth Requirements

For a two-socket system with 4 cores per socket at 2 GHz:
- Per-socket L2→L3 bandwidth: ~64 GB/s (4 cores × 1 cache line/cycle × 64B)
- Cross-socket bandwidth demand: ~10-20% of total (for shared workloads) = 6-13 GB/s
- Minimum cross-socket link: 16 GB/s bidirectional recommended

### 14.4 NUMA-Awareness in Software

The OS and runtime must be NUMA-aware:
- Linux: Configure NUMA nodes via device tree / ACPI SRAT
- Memory allocation: Prefer local socket's DDR
- Thread scheduling: Pin threads to cores near their data
- Page migration: Move frequently-accessed remote pages to local DDR

---

## Appendix: Signal Reference

### A.1 XSNoCTop Complete IO List

```
IO Signal                    Direction  Width        Description
─────────────────────────────────────────────────────────────────
clock                        Input      1            Core clock
reset                        Input      1 (async)    Core reset
noc_clock                    Input      1            NoC clock domain
noc_reset                    Input      1 (async)    NoC reset
soc_clock                    Input      1            SoC peripheral clock
soc_reset                    Input      1 (async)    SoC peripheral reset
clint_clock                  Input      1            Timer reference clock
clint_reset                  Input      1 (async)    Timer reset
io_chi.tx.req.*              Output     (bundle)     CHI TX Request channel
io_chi.tx.rsp.*              Output     (bundle)     CHI TX Response channel
io_chi.tx.dat.*              Output     (bundle)     CHI TX Data channel
io_chi.rx.snp.*              Input      (bundle)     CHI RX Snoop channel
io_chi.rx.snp.flitpend       Input      1            Snoop flit pending
io_chi.rx.rsp.*              Input      (bundle)     CHI RX Response channel
io_chi.rx.rsp.flitpend       Input      1            Response flit pending
io_chi.rx.dat.*              Input      (bundle)     CHI RX Data channel
io_chi.rx.dat.flitpend       Input      1            Data flit pending
io_chi.syscoreq              Output     1            System coherency request
io_chi.syscoack              Input      1            System coherency ack
tileio.hartId                Input      varies       Hardware thread ID
tileio.nodeID                Input      7/9/11       CHI Node ID
tileio.riscv_rst_vec         Input      48           Reset vector address
tileio.riscv_halt            Output     1            CPU halted
tileio.riscv_critical_error  Output     1            Critical error
tileio.hartResetReq          Input      1            Per-hart reset request
tileio.hartIsInReset         Output     1            Hart in reset state
io.lp.i_cpu_sw_rst_n         Input      1            Software reset (active low)
io.lp.i_cpu_iso_en           Input      1            Isolation enable
io.lp.i_cpu_pwrdown_req_n    Input      1            Power-down request
io.lp.o_cpu_pwrdown_ack_n    Output     1            Power-down ack
io.lp.o_cpu_no_op            Output     1            CPU idle / no-op
io.dft                       Input      (bundle)     DFT SRAM broadcast (optional)
io.dft_reset                 Input      (bundle)     DFT reset (optional)
```

### A.2 Key Source Files

| File | Purpose |
|------|---------|
| `top/XSNoCTop.scala` | Per-tile top module with CHI port |
| `top/Top.scala` | Multi-core single-socket top (reference for routing) |
| `top/Configs.scala` | All named configurations |
| `system/SoC.scala` | SoC parameters, address map, CHI settings |
| `xiangshan/L2Top.scala` | L2 cache with TL-to-CHI conversion |
| `xiangshan/XSTileWrap.scala` | Tile wrapper with async bridges |
| `xiangshan/Parameters.scala` | Core parameters, NodeIDWidth |
| `Makefile` | Build targets and CONFIG/ISSUE flags |

### A.3 Build Commands Quick Reference

```bash
# Single XSNoCTop tile (CHI E.b)
make verilog CONFIG=XSNoCTopConfig ISSUE=E.b

# Single XSNoCTop tile with difftest
make emu CONFIG=XSNoCDiffTopConfig ISSUE=E.b

# Multi-core single-socket with CHI (for reference)
make verilog CONFIG=CHIConfig NUM_CORES=4 ISSUE=E.b

# Minimal CHI config (smaller, faster compile)
make verilog CONFIG=CHIMinimalConfig ISSUE=E.b

# Custom CHI address width
make verilog CONFIG=XSNoCTopConfig ISSUE=E.b CHI_ADDR_WIDTH=48
```
