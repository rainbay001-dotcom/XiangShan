# Cross-Socket CHI Bridge: Detailed Microarchitecture

**Status:** Detailed Design  
**Depends on:** `design-two-socket-system.md` (Option B: Per-Socket LLC with Cross-Socket Snoop)  
**Informed by:** OpenLLC source analysis (Directory.scala, MainPipe.scala, Slice.scala, CHIXbar.scala)

---

## Table of Contents

1. [Design Context](#1-design-context)
2. [OpenLLC Internals Summary](#2-openllc-internals-summary)
3. [Bridge Architecture Overview](#3-bridge-architecture-overview)
4. [Remote Directory Extension](#4-remote-directory-extension)
5. [Cross-Socket Bridge (XSBridge) Microarchitecture](#5-cross-socket-bridge-xsbridge-microarchitecture)
6. [Transaction Flows](#6-transaction-flows)
7. [Transaction ID Management](#7-transaction-id-management)
8. [Flow Control and Ordering](#8-flow-control-and-ordering)
9. [Serialization Link Layer](#9-serialization-link-layer)
10. [Integration with DualSocketTop](#10-integration-with-dualsockettop)
11. [Corner Cases and Hazards](#11-corner-cases-and-hazards)
12. [Parameters and Configurability](#12-parameters-and-configurability)
13. [Verification Plan](#13-verification-plan)

---

## 1. Design Context

### 1.1 The Problem

In the two-socket XiangShan system (Option B from the system design doc), each socket has:
- N cores with private L1/L2 caches (L2 = CHI RN-F)
- An OpenLLC instance (CHI HN-F) serving as L3 + snoop filter
- An OpenNCB bridge (CHI SN-F → AXI4) for DDR access

The problem: when Core A on Socket 0 accesses an address that lives in Socket 1's
DDR range, there is no path for that request to reach Socket 1's OpenLLC. Similarly,
when Socket 1 writes to a line that Socket 0 has cached, there is no mechanism to
invalidate Socket 0's copy.

### 1.2 What the Bridge Must Do

The cross-socket bridge sits between the two sockets' CHI fabrics and provides:

1. **Request forwarding:** Route CHI requests whose addresses map to the remote socket's
   memory range to the remote OpenLLC (HN-F)
2. **Snoop forwarding:** When a local HN-F needs to snoop a line that may be cached on
   the remote socket, forward the snoop across the link
3. **Response return:** Carry CompData, SnpResp, and CompAck back to the originator
4. **Ordering guarantees:** Maintain CHI ordering rules across the socket boundary
5. **Flow control:** Back-pressure when the remote socket or the link is saturated

### 1.3 Design Approach

We use a **two-level** design:

- **Level 1 — Remote directory extension in OpenLLC:** A 1-bit "remote present" flag per
  directory entry, telling the local HN-F that the line may be cached on the remote socket.
  This avoids unnecessary cross-socket snoops.
- **Level 2 — XSBridge module:** A CHI-to-CHI bridge that connects as an additional RN-F
  port on each OpenLLC, forwarding transactions across the link.

---

## 2. OpenLLC Internals Summary

Findings from reading the actual source code. These details directly inform the bridge design.

### 2.1 Directory Structure

**Source:** `openLLC/src/main/scala/openLLC/Directory.scala`

OpenLLC maintains two parallel directories:

| Directory | Module | Tracks | Entry Type |
|-----------|--------|--------|------------|
| **Self directory** | `selfDir` (SubDirectory[SelfMetaEntry]) | What the LLC itself caches | `{valid: Bool, dirty: Bool}` |
| **Client directory** (snoop filter) | `clientDir` (SubDirectory[Vec[ClientMetaEntry]]) | Which RN-Fs have copies | `Vec(numRNs, {valid: Bool})` |

The client directory is effectively a **per-line sharer bitmask** — one valid bit per RN-F.
The number of RN-Fs is `numRNs = cacheParams.clientCaches.size` (`LLCParam.scala:89`).

**Key observation:** The client directory is parameterized by `numRNs`. To track remote
socket presence, we can either:
- (a) Add the bridge as an additional RN-F (increment `numRNs` by 1), or
- (b) Add a separate `remotePresent` bit outside the client directory

Option (a) is cleaner — the bridge appears as just another RN-F to the OpenLLC. When the
bridge's bit is set in `snpVec`, the snoop gets sent to the bridge port, which forwards
it across the socket link.

### 2.2 Snoop Generation (MainPipe Stage 4)

**Source:** `openLLC/src/main/scala/openLLC/MainPipe.scala:281-344`

The snoop decision logic in stage 4:

```
clients_valids_vec_s4 = Vec of each RN-F's valid bit from client directory
peerRNs_valids_vec_s4 = same, but with the requesting RN-F masked out

need_snoop = replace_snoop OR request_snoop

replace_snoop: client dir miss but other RN-Fs hold the evicted line
request_snoop: exclusive/invalidate req + peers hold it
               OR shared read + LLC miss (need data from an RN-F)
               OR clean req + exactly one peer RN-F holds it uniquely

snpVec := replace_snoop ? all_sharers : peer_sharers_only
```

**Key observation:** `snpVec` is a `Vec(numRNs, Bool())` — it selects which RN-F ports
receive the snoop. If the bridge is RN-F index `numRNs-1`, then when `snpVec(numRNs-1)`
is asserted, the snoop goes to the bridge, which forwards it to the remote socket.

### 2.3 Snoop Opcode Selection

From MainPipe.scala:321-336, the mapping:

| Request Type | Snoop Opcode |
|-------------|--------------|
| Replacement eviction | SnpUnique (always) |
| ReadUnique | SnpUnique |
| ReadNotSharedDirty | SnpNotSharedDirty |
| MakeUnique / MakeInvalid | SnpMakeInvalid |
| CleanInvalid | SnpCleanInvalid |
| CleanShared | SnpCleanShared |

### 2.4 Response Collection

**Source:** `openLLC/src/main/scala/openLLC/ResponseUnit.scala:27-55`

Each response entry tracks:
```
ResponseState:
  s_comp       — need to send Comp
  s_urgentRead — need urgent memory read
  w_datRsp     — waiting for data response
  w_snpRsp     — waiting for snoop response
  w_compack    — waiting for CompAck
  w_comp       — waiting for completion
```

Snoop responses are matched by `txnID`. The ResponseUnit collects SnpResp/SnpRespData
from the `rnRxrsp`/`rnRxdat` ports and updates the corresponding entry.

**Key observation:** The ResponseUnit already handles waiting for snoop responses from
multiple RN-Fs. Adding the bridge as another RN-F means cross-socket snoop responses
flow through the same collection logic — no changes needed in ResponseUnit.

### 2.5 OpenLLC Top-Level IO

**Source:** `openLLC/src/main/scala/openLLC/OpenLLC.scala:37-46`

```scala
val io = IO(new Bundle {
  val rn = Vec(numRNs, Flipped(new PortIO))    // RN-F ports (one per client)
  val sn = new NoSnpPortIO                      // SN-F port (to memory)
  val nodeID = Input(UInt())
  ...
})
```

The OpenLLC already supports multiple RN-F ports via `Vec(numRNs, ...)`. Adding the
bridge as RN-F port `numRNs` (by adding one more `L2Param` to `clientCaches`) is the
minimal-invasive integration path.

### 2.6 Slice Pipeline

**Source:** `openLLC/src/main/scala/openLLC/Slice.scala:27-151`

```
RXREQ → RequestBuffer → RequestArb → MainPipe (6 stages)
                                        ├── Directory read (stage 1-3)
                                        ├── Snoop decision (stage 4) → SnoopUnit → TXSNP
                                        ├── Refill/Memory (stage 4)  → RefillUnit / MemUnit
                                        └── Response (stage 4/6)     → ResponseUnit → TXRSP/TXDAT
```

The TXSNP output goes through `SnoopUnit` → `TXSNP` module, which drives the `txUp.snp`
channel. The `RNXbar` (`CHIXbar.scala`) demuxes snoops to the correct RN-F port using
the `snpMask` (which is `snpVec` propagated from MainPipe through SnoopUnit).

---

## 3. Bridge Architecture Overview

### 3.1 High-Level Topology

```
Socket 0                                                    Socket 1
┌──────────────────────┐                              ┌──────────────────────┐
│  Core 0  Core 1  ... │                              │  Core N  Core N+1 ...│
│  (RN-F)  (RN-F)      │                              │  (RN-F)  (RN-F)      │
│      │      │         │                              │      │      │        │
│  ┌───┴──────┴───┐     │                              │  ┌───┴──────┴───┐    │
│  │              │     │                              │  │              │    │
│  │   OpenLLC 0  │     │                              │  │   OpenLLC 1  │    │
│  │   (HN-F)    │     │                              │  │   (HN-F)    │    │
│  │              │     │                              │  │              │    │
│  │  rn(0..N-1) │     │                              │  │  rn(0..N-1) │    │
│  │  rn(N) ─────┼──┐  │                              │  │  rn(N) ─────┼─┐  │
│  └──────────────┘  │  │                              │  └──────────────┘ │  │
│                    │  │                              │                   │  │
│  ┌─────────────────┴──┼──────────────────────────────┼───────────────────┴┐ │
│  │              XSBridge                             │                    │ │
│  │                    │                              │                    │ │
│  │  ┌──────────────┐  │    Serial / Parallel Link    │  ┌──────────────┐  │ │
│  │  │ BridgeAgent 0│  │◄════════════════════════════►│  │ BridgeAgent 1│  │ │
│  │  │ (CHI PortIO) │  │                              │  │ (CHI PortIO) │  │ │
│  │  └──────────────┘  │                              │  └──────────────┘  │ │
│  └────────────────────┘                              └────────────────────┘ │
└──────────────────────┘                              └──────────────────────┘
```

### 3.2 Bridge as RN-F

Each `BridgeAgent` connects to its local OpenLLC as RN-F port index `N` (where N = number
of local cores). From the OpenLLC's perspective, the bridge is just another L2 cache that
can hold copies of cache lines and must be snooped.

The bridge also acts as a **proxy requester** — when the remote socket issues a
ReadShared/ReadUnique for a local address, the bridge injects that request into the local
OpenLLC's RXREQ channel as if it came from a local RN-F.

### 3.3 Bridge as Proxy HN-F

From the remote socket's perspective, the bridge acts as an HN-F that can service
requests for addresses in the local socket's memory range. The bridge receives CHI
requests from the remote socket's routing layer and forwards them to the local OpenLLC.

---

## 4. Remote Directory Extension

### 4.1 Approach: Bridge as Additional RN-F

Rather than modifying the OpenLLC's internal `ClientMetaEntry` structure, we add the
bridge as the `(numRNs)`-th RN-F port. This means:

1. In `clientCaches` parameter, append a synthetic `L2Param` representing the bridge
2. The client directory's sharer vector automatically grows by 1 bit
3. When a line is fetched by the remote socket through the bridge, the bridge's RN-F
   index gets marked as "valid" in the client directory
4. When the local HN-F needs to invalidate that line, `snpVec(bridge_idx)` is set,
   sending a snoop to the bridge port

**Configuration change:**

```scala
// In DualSocketTop, when configuring OpenLLC params:
val bridgeL2Param = L2Param(
  ways = 1,           // Minimal — bridge doesn't have a real cache
  sets = 1,           // Minimal
  clientCaches = Nil  // No sub-clients
)

// Append to existing clientCaches
val llcParams = baseOpenLLCParam.copy(
  clientCaches = baseOpenLLCParam.clientCaches :+ bridgeL2Param
)
```

The client directory (snoop filter) dimensions are derived from the **largest** L2 in
`clientCaches`. The bridge's synthetic entry won't change the snoop filter dimensions
meaningfully — it just adds one more valid bit per entry.

### 4.2 What Happens on Cross-Socket Operations

**Remote Read (Socket 1 reads Socket 0 memory):**
1. Socket 1's routing layer sends ReadShared to XSBridge
2. XSBridge injects ReadShared into Socket 0 OpenLLC's `rn(bridge_idx)` RXREQ
3. Socket 0 OpenLLC processes it like any other RN-F request
4. If other local L2s hold the line, OpenLLC snoops them normally
5. OpenLLC returns CompData to the bridge's RN-F port
6. OpenLLC marks `clientDir[bridge_idx].valid = true` (line now "cached by remote socket")
7. XSBridge forwards CompData back to Socket 1

**Subsequent Local Write (Socket 0 core writes same line):**
1. Socket 0 core issues MakeUnique to Socket 0 OpenLLC
2. OpenLLC sees `clientDir[bridge_idx].valid = true` (remote has a copy)
3. OpenLLC sends SnpMakeInvalid to bridge port (`snpVec(bridge_idx) = true`)
4. XSBridge forwards SnpMakeInvalid to Socket 1
5. Socket 1 processes snoop (may need to snoop its own L2s for dirty data)
6. Socket 1 returns SnpResp(I) or SnpRespData(I) through the bridge
7. OpenLLC clears `clientDir[bridge_idx].valid = false`
8. OpenLLC grants MakeUnique to the requesting core

### 4.3 Why This Works Without Modifying OpenLLC Source

The existing OpenLLC code is fully generic over `numRNs`:
- `clientDir` is `SubDirectory[Vec[ClientMetaEntry]]` with `Vec.fill(numRNs)(...)` — adding
  an RN just adds one more bit to the vector
- `snpVec` in MainPipe is `Vec(numRNs, Bool())` — automatically includes the bridge index
- `RNXbar` routes snoops to RN ports based on `snpMask` — the bridge gets snoops when its
  mask bit is set
- No hardcoded assumptions about numRNs anywhere in the pipeline

The only change is in the **configuration** passed to OpenLLC at elaboration time.

---

## 5. Cross-Socket Bridge (XSBridge) Microarchitecture

### 5.1 Module Hierarchy

```
XSBridge
├── BridgeAgent (×2, one per socket side)
│   ├── RequestTracker           — Track outstanding cross-socket requests
│   │   └── entries: Vec(N, RequestEntry)
│   ├── SnoopTracker             — Track outstanding cross-socket snoops
│   │   └── entries: Vec(M, SnoopEntry)
│   ├── ResponseAssembler        — Collect multi-beat data responses
│   │   └── entries: Vec(N, DataBuffer)
│   ├── TxnIDMapper              — Remap txnID across socket boundary
│   └── FlowController           — L-Credit management for bridge port
├── LinkLayer                    — Serialization / clock domain crossing
│   ├── TxQueue (per channel)    — Outbound FIFO + serializer
│   ├── RxQueue (per channel)    — Inbound deserializer + FIFO
│   └── LinkStateCtrl            — Link activation FSM
└── ConfigRegs                   — MMIO-mapped bridge status/config
```

### 5.2 BridgeAgent Detail

Each BridgeAgent has two interfaces:

- **Local CHI port** — connects to local OpenLLC as RN-F (PortIO, flipped from normal)
- **Remote channel** — internal interface to LinkLayer (request/response FIFOs)

```
BridgeAgent (Socket 0 side)
                                                    To/from LinkLayer
                                                    (→ remote socket)
Local OpenLLC rn(N) port                            
    ┌──────────────┐                               
    │              │    ┌──────────────────┐         ┌────────────┐
    │ rx.snp ──────┼───►│ SnoopTracker     │────────►│ TX: SNP    │──►
    │              │    └──────────────────┘         └────────────┘
    │              │                                  ┌────────────┐
    │ rx.rsp ──────┼────────────────────────────────►│ TX: RSP    │──►
    │              │                                  └────────────┘
    │              │                                  ┌────────────┐
    │ rx.dat ──────┼────────────────────────────────►│ TX: DAT    │──►
    │              │                                  └────────────┘
    │              │                                 
    │              │    ┌──────────────────┐         ┌────────────┐
    │ tx.req ◄─────┼────│ RequestTracker   │◄────────│ RX: REQ    │◄──
    │              │    └──────────────────┘         └────────────┘
    │              │                                  ┌────────────┐
    │ tx.rsp ◄─────┼────────────────────────────────│ RX: RSP    │◄──
    │              │                                  └────────────┘
    │              │    ┌──────────────────┐         ┌────────────┐
    │ tx.dat ◄─────┼────│ ResponseAssembler│◄────────│ RX: DAT    │◄──
    │              │    └──────────────────┘         └────────────┘
    └──────────────┘
```

**Data flow for remote read (request from remote socket):**
1. Remote socket sends ReadShared → arrives at RX: REQ
2. RequestTracker allocates entry, remaps txnID, injects into local OpenLLC via `tx.req`
3. Local OpenLLC processes, returns CompData on `rx.dat`
4. ResponseAssembler collects data beats, remaps txnID back to original
5. Sends data via TX: DAT to remote socket

**Data flow for local snoop of remote-cached line:**
1. Local OpenLLC sends SnpUnique on `rx.snp` (because bridge's clientDir bit is set)
2. SnoopTracker allocates entry, forwards snoop via TX: SNP to remote socket
3. Remote socket processes snoop, returns SnpResp/SnpRespData via RX: RSP/DAT
4. BridgeAgent forwards response to local OpenLLC via `tx.rsp`/`tx.dat`

### 5.3 RequestTracker

```
RequestEntry:
  valid       : Bool
  state       : RequestState
  localTxnID  : UInt        — txnID used toward local OpenLLC
  remoteTxnID : UInt        — original txnID from remote socket
  remoteSrcID : UInt        — original srcID from remote socket
  addr        : UInt(48.W)  — request address (for conflict detection)
  opcode      : UInt        — CHI request opcode
  beatValids  : Vec(2, Bool)  — for 64B line / 32B beats
  data        : DSBlock     — assembled response data (if needed)

RequestState:
  s_issue     : Bool        — need to issue request to local OpenLLC
  w_comp      : Bool        — waiting for Comp/CompData from local OpenLLC
  w_data      : Bool        — waiting for data beats
  s_forward   : Bool        — need to forward response to remote socket
  w_compack   : Bool        — waiting for CompAck from remote (if applicable)
```

**Capacity:** 16 entries (matches OpenLLC's `mshrs.response = 16`)

**Conflict detection:** Before allocating a new entry, check if another entry has the
same address. If so, stall the new request (prevents out-of-order hazards on same line).

### 5.4 SnoopTracker

```
SnoopEntry:
  valid       : Bool
  localTxnID  : UInt        — txnID from local OpenLLC's snoop
  remoteTxnID : UInt        — txnID used toward remote socket
  addr        : UInt(45.W)  — snoop address (addr[MPA-1:3])
  opcode      : UInt        — snoop opcode
  retToSrc    : Bool        — whether data return is expected
  w_snpResp   : Bool        — waiting for snoop response from remote
```

**Capacity:** 16 entries (matches OpenLLC's `mshrs.snoop = 16`)

When the local OpenLLC sends a snoop to the bridge's RN-F port, the SnoopTracker
captures it, allocates an entry, and forwards the snoop across the link. The remote
socket's bridge side injects the snoop into the remote OpenLLC, which in turn snoops
the remote L2 caches. The snoop response chain returns through the bridge.

### 5.5 TxnIDMapper

CHI requires `txnID` to be unique per source. The bridge must remap transaction IDs
when forwarding between sockets because:

- Remote socket's original txnID may collide with local txnIDs
- The bridge uses its own txnID space toward the local OpenLLC

**Implementation:** Simple lookup table indexed by local txnID → (remoteTxnID, remoteSrcID).
The RequestTracker and SnoopTracker entries serve double duty as the mapping table.

```
On request injection to local OpenLLC:
  tx.req.srcID := bridge_nodeID        (bridge's own NodeID)
  tx.req.txnID := requestTracker.allocIdx  (local txnID = entry index)

On response forwarding to remote socket:
  entry = requestTracker(resp.txnID)   (look up by local txnID)
  tx_to_remote.dat.tgtID := entry.remoteSrcID
  tx_to_remote.dat.txnID := entry.remoteTxnID
```

---

## 6. Transaction Flows

### 6.1 Remote Read: Socket 1 Core Reads Socket 0 Memory

```
S1 Core    S1 OpenLLC    S1 BridgeAgent    Link    S0 BridgeAgent    S0 OpenLLC    S0 L2s
  │            │               │             │            │               │           │
  │─ReadShared►│               │             │            │               │           │
  │            │(addr maps to  │             │            │               │           │
  │            │ remote S0)    │             │            │               │           │
  │            │──ReadShared──►│             │            │               │           │
  │            │               │══REQ flit══►│            │               │           │
  │            │               │             │ (remap txnID, inject req)  │           │
  │            │               │             │            │──ReadShared──►│           │
  │            │               │             │            │               │(dir lookup)│
  │            │               │             │            │               │─SnpShared─►│
  │            │               │             │            │               │           │
  │            │               │             │            │               │◄SnpResp───│
  │            │               │             │            │◄──CompData────│           │
  │            │               │             │◄══DAT flit═│               │           │
  │            │               │(remap txnID back)        │               │           │
  │            │◄──CompData────│             │            │               │           │
  │◄CompData───│               │             │            │               │           │
  │─CompAck───►│               │             │            │               │           │
  │            │──CompAck─────►│             │            │               │           │
  │            │               │══RSP flit══►│            │               │           │
  │            │               │             │            │──CompAck─────►│           │
  │            │               │             │            │               │(update dir)│
```

**Directory state after:** Socket 0 OpenLLC marks `clientDir[bridge_idx].valid = true`
for this line. Future local writes will snoop the bridge.

### 6.2 Cross-Socket Invalidation: Socket 0 Core Writes Line Cached by Socket 1

```
S0 Core    S0 OpenLLC    S0 BridgeAgent    Link    S1 BridgeAgent    S1 OpenLLC    S1 L2s
  │            │               │             │            │               │           │
  │─MakeUnique►│               │             │            │               │           │
  │            │(dir shows     │             │            │               │           │
  │            │ bridge has it)│             │            │               │           │
  │            │─SnpMakeInvld─►│             │            │               │           │
  │            │               │══SNP flit══►│            │               │           │
  │            │               │             │(inject snoop into S1 LLC)  │           │
  │            │               │             │            │─SnpMakeInvld─►│           │
  │            │               │             │            │               │─SnpMkInv─►│
  │            │               │             │            │               │           │
  │            │               │             │            │               │◄SnpResp(I)│
  │            │               │             │            │◄─SnpResp(I)───│           │
  │            │               │             │◄══RSP flit═│               │           │
  │            │◄──SnpResp(I)──│             │            │               │           │
  │            │(clear bridge  │             │            │               │           │
  │            │ dir bit)      │             │            │               │           │
  │◄──Comp─────│               │             │            │               │           │
```

### 6.3 Cross-Socket Snoop with Data Return

When the remote cache has dirty data:

```
S0 Core    S0 OpenLLC    S0 BridgeAgent    Link    S1 BridgeAgent    S1 OpenLLC    S1 L2
  │            │               │             │            │               │          │
  │─ReadUnique►│               │             │            │               │          │
  │            │(dir: bridge   │             │            │               │          │
  │            │ has line, UD) │             │            │               │          │
  │            │──SnpUnique───►│             │            │               │          │
  │            │ (retToSrc=1)  │══SNP flit══►│            │               │          │
  │            │               │             │            │──SnpUnique───►│          │
  │            │               │             │            │ (retToSrc=1)  │─SnpUnq──►│
  │            │               │             │            │               │          │
  │            │               │             │            │               │◄SnpRspDt─│
  │            │               │             │            │               │ (UD→I)   │
  │            │               │             │            │◄─SnpRespData──│          │
  │            │               │             │◄══DAT flit═│               │          │
  │            │◄─SnpRespData──│             │            │               │          │
  │            │               │             │            │               │          │
  │            │(has dirty data│             │            │               │          │
  │            │ from remote)  │             │            │               │          │
  │◄─CompData──│               │             │            │               │          │
  │ (UD)       │               │             │            │               │          │
```

### 6.4 Back-to-Back Cross-Socket Ownership Ping-Pong

This is the worst case for performance — two cores on different sockets alternately
writing the same cache line (false sharing or true sharing).

The bridge must handle the case where:
1. Socket 0 has the line UD, Socket 1 requests it
2. While Socket 1's request is in flight, Socket 0 requests it back

**Handled by:** Address-based conflict detection in RequestTracker. The second request
stalls until the first completes, preventing overlapping transactions to the same address.

---

## 7. Transaction ID Management

### 7.1 TxnID Spaces

There are three independent txnID spaces:

| Space | Owner | Width | Usage |
|-------|-------|-------|-------|
| Local L2 txnIDs | Each L2 cache (RN-F) | 12 bits (E.b) | L2 → local OpenLLC |
| Bridge local txnIDs | BridgeAgent | 4 bits (16 entries) | Bridge → local OpenLLC |
| Remote forwarded txnIDs | Remote BridgeAgent | 4 bits | Bridge → remote OpenLLC |

### 7.2 Remapping Rules

**Request forwarding (remote → local OpenLLC):**
```
Incoming from remote:  srcID=<remote_core>, txnID=<remote_txn>
Injected to local LLC: srcID=<bridge_nodeID>, txnID=<tracker_entry_idx>
Mapping stored in:     RequestTracker[entry_idx].{remoteSrcID, remoteTxnID}
```

**Response forwarding (local OpenLLC → remote):**
```
Received from local:   tgtID=<bridge_nodeID>, txnID=<tracker_entry_idx>
Forwarded to remote:   tgtID=<original_remote_srcID>, txnID=<original_remote_txnID>
Looked up from:        RequestTracker[resp.txnID].{remoteSrcID, remoteTxnID}
```

**Snoop forwarding (local OpenLLC → remote):**
```
Received from local:   srcID=<local_llc_nodeID>, txnID=<llc_snp_txnID>
Forwarded to remote:   srcID=<bridge_nodeID>, txnID=<snoop_tracker_entry_idx>
Mapping stored in:     SnoopTracker[entry_idx].localTxnID
```

### 7.3 Snoop-on-Behalf-Of

When the bridge receives a snoop from the local OpenLLC (because `clientDir[bridge_idx]`
is set), the bridge must figure out which remote L2 caches actually hold the line. Two options:

**Option A — Forward snoop to remote OpenLLC (recommended):**
The bridge sends a CHI request (ReadNoSnp or custom snoop-forward) to the remote OpenLLC,
which has the precise client directory and knows exactly which of its L2s to snoop.

**Option B — Direct snoop to remote L2s:**
The bridge would need its own copy of the remote snoop filter. This is complex and
redundant — the remote OpenLLC already has this information.

We choose **Option A**. The bridge injects the snoop into the remote side's OpenLLC, which
processes it through its own MainPipe and snoops the appropriate local L2 caches. This
means the bridge acts as a CHI RN-F on the remote side too.

**Implementation detail:** The bridge-side snoop is injected as a special request that the
remote OpenLLC handles. Since OpenLLC's RXREQ only handles standard CHI request opcodes,
we use a two-step approach:

1. Bridge receives SnpUnique(addr=X) from local OpenLLC
2. Bridge sends CleanInvalid(addr=X) or custom back-invalidation request to remote OpenLLC
3. Remote OpenLLC processes it, snoops its local L2s, returns the response
4. Bridge returns SnpResp/SnpRespData to local OpenLLC

For `SnpUnique` with `retToSrc=1` (data return needed), the bridge uses `ReadUnique` on
the remote side to force the remote OpenLLC to fetch data from its L2s and return it.

**Snoop-to-Request mapping:**

| Local Snoop | Remote Request | Rationale |
|------------|----------------|-----------|
| SnpUnique (retToSrc=0) | MakeInvalid | Invalidate remote copies, no data needed |
| SnpUnique (retToSrc=1) | ReadUnique | Get data + invalidate |
| SnpMakeInvalid | MakeInvalid | Invalidate, no data |
| SnpCleanInvalid | CleanInvalid | Write-back dirty, invalidate |
| SnpCleanShared | CleanShared | Write-back dirty, keep shared |
| SnpNotSharedDirty | ReadNotSharedDirty | Downgrade, return data |
| SnpShared | ReadShared | Read shared copy |

---

## 8. Flow Control and Ordering

### 8.1 L-Credit Flow Control

XiangShan's CHI implementation uses **L-Credit (Link Credit)** flow control, not
RetryAck-based flow control.

**Source:** `coupledL2/src/main/scala/coupledL2/tl2chi/chi/LinkLayer.scala:133-324`

Each channel has a credit pool (default 4, max 15). The receiver issues L-Credits to the
sender when it has buffer space. The sender can only transmit a flit when it holds a credit.

The bridge's local CHI port uses the same L-Credit mechanism:
- Bridge → OpenLLC (tx.req/rsp/dat): Bridge consumes credits from OpenLLC's link monitor
- OpenLLC → Bridge (rx.snp/rsp/dat): OpenLLC consumes credits from bridge's link monitor

**Bridge credit allocation:**
- TX REQ credits: 4 (matches L2 defaults)
- TX RSP credits: 4
- TX DAT credits: 4
- RX SNP credits: 16 (higher — bridge may receive bursts of snoops)
- RX RSP credits: 4
- RX DAT credits: 4

### 8.2 Cross-Link Flow Control

The link between sockets has its own flow control layer. Options:

**For parallel link (same chip / FPGA):** Use CHI async bridge pattern
(`CHIAsyncBridgeSource/Sink` from `coupledL2/tl2chi/chi/AsyncBridge.scala`) with shadow
buffers (depth 16) + async queues (depth 4, 3-stage synchronizers).

**For serial link (chip-to-chip):** Implement a packet-based protocol with:
- Packet-level flow control (credits per packet type)
- Packet retransmission on CRC error (for SerDes links)
- Separate virtual channels for REQ, RSP, DAT, SNP to prevent protocol deadlock

### 8.3 Ordering Guarantees

CHI requires certain ordering guarantees:

1. **Request ordering:** Requests to the same address from the same source must be
   processed in order. The RequestTracker enforces this via address conflict detection.

2. **Snoop-before-response:** A snoop for address X must be processed before a response
   for a request to address X is sent. The bridge preserves this by processing snoops in
   FIFO order per address.

3. **CompAck ordering:** CompAck must arrive after CompData. The bridge forwards CompAck
   only after it has forwarded the corresponding CompData.

4. **No deadlock:** The bridge must not create circular dependencies between the REQ and
   SNP channels. This is achieved by having separate buffer resources for requests and
   snoops, and by allowing snoops to bypass stalled requests.

### 8.4 Deadlock Prevention

The classic NUMA coherence deadlock occurs when:
- Socket 0 sends a request to Socket 1 (consumes request buffer)
- Socket 1 needs to snoop Socket 0 to service it (sends snoop back)
- Socket 0's snoop buffer is full because its own request is blocking

**Solution:** Separate, independently-sized buffers for requests and snoops, with snoops
having strictly higher priority than requests for link bandwidth. The SnoopTracker can
always make progress even when the RequestTracker is full.

**Implementation:**
- TX link has separate virtual channels for REQ and SNP
- SNP channel has reserved credits that cannot be consumed by REQ
- Snoop responses (RSP/DAT) use a shared channel but with priority arbitration

---

## 9. Serialization Link Layer

### 9.1 Link Options

The link layer is abstracted behind a simple FIFO interface. The bridge core doesn't
care whether the link is:

| Link Type | Latency | Bandwidth | Use Case |
|-----------|---------|-----------|----------|
| Direct wires (parallel CHI) | 1-2 cycles | Full CHI BW | Same-package / FPGA |
| Async bridge (CDC only) | 3-5 cycles | Full CHI BW | Multi-clock FPGA |
| SerDes (CXL / custom) | 50-200 ns | Link-limited | Chip-to-chip |
| UCIe (chiplet) | 2-5 ns | Very high | 2.5D integration |

### 9.2 Direct Wire Interface (FPGA Prototype)

For FPGA prototyping, use direct parallel wires with optional async bridging:

```scala
class XSBridgeLinkIO(implicit p: Parameters) extends Bundle {
  // Socket 0 → Socket 1
  val tx_req = DecoupledIO(new CHIREQ())
  val tx_rsp = DecoupledIO(new CHIRSP())
  val tx_dat = DecoupledIO(new CHIDAT())
  val tx_snp = DecoupledIO(new CHISNP())

  // Socket 1 → Socket 0
  val rx_req = Flipped(DecoupledIO(new CHIREQ()))
  val rx_rsp = Flipped(DecoupledIO(new CHIRSP()))
  val rx_dat = Flipped(DecoupledIO(new CHIDAT()))
  val rx_snp = Flipped(DecoupledIO(new CHISNP()))
}
```

If sockets are in different clock domains, wrap each channel with
`CHIAsyncBridgeSource`/`CHIAsyncBridgeSink` (already proven in XiangShan for the
core↔NoC clock domain crossing).

### 9.3 Flit Packing for Serial Links

For a serial link, CHI flits are packed into link-layer packets:

```
Packet format:
┌──────┬──────┬────────┬───────────┬─────┐
│ Type │ VCid │ Length │  Payload  │ CRC │
│ 2b   │ 2b   │ 8b    │ variable  │ 32b │
└──────┴──────┴────────┴───────────┴─────┘

Type: 00=REQ, 01=RSP, 10=DAT, 11=SNP
VCid: Virtual channel ID (for multi-VC links)
```

Flit widths (CHI E.b):
- REQ: ~120 bits
- RSP: ~50 bits
- SNP: ~70 bits
- DAT: ~330 bits (including 256-bit data)

At 16 GB/s link bandwidth (128-bit parallel or 128 Gbps SerDes):
- REQ flit: ~1 ns
- DAT flit: ~2.6 ns (or 2 beats for 64B line)

---

## 10. Integration with DualSocketTop

### 10.1 Updated DualSocketTop Structure

```scala
class DualSocketTop(implicit p: Parameters) extends RawModule {
  val N = p(NumCoresKey)  // cores per socket

  // Bridge L2Param (synthetic entry for client directory)
  val bridgeL2Param = L2Param(ways = 1, sets = 1, clientCaches = Nil)

  // Socket 0 LLC params: N real L2s + 1 bridge
  val llcParams0 = baseLLCParams.copy(
    clientCaches = (0 until N).map(_ => realL2Param) :+ bridgeL2Param
  )

  // Socket 1 LLC params: same structure
  val llcParams1 = llcParams0.copy()

  // Instantiate
  val tiles0   = (0 until N).map(i => Module(new XSNoCTop()(p0(i))))
  val tiles1   = (0 until N).map(i => Module(new XSNoCTop()(p1(i))))
  val llc0     = Module(new OpenLLC()(withLLC(llcParams0)))
  val llc1     = Module(new OpenLLC()(withLLC(llcParams1)))
  val bridge   = Module(new XSBridge())

  // Connect local L2s to LLC
  for (i <- 0 until N) {
    llc0.io.rn(i) <> tiles0(i).io_chi
    llc1.io.rn(i) <> tiles1(i).io_chi
  }

  // Connect bridge to LLC port N (the extra RN-F port)
  llc0.io.rn(N) <> bridge.io.local(0)
  llc1.io.rn(N) <> bridge.io.local(1)

  // Bridge link (direct wire for FPGA, or through SerDes for ASIC)
  bridge.io.link <> ... // internal, or expose for external PHY

  // Memory
  val ncb0 = Module(new OpenNCB())
  val ncb1 = Module(new OpenNCB())
  ncb0.io.chi <> llc0.io.sn
  ncb1.io.chi <> llc1.io.sn
}
```

### 10.2 Address Routing

Each socket's CHI routing layer (in `Top.scala`-style `route()`) must include the
cross-socket address range. For Socket 0:

```scala
route(tile.io_chi, Map(
  // MMIO: 0x0 - 0x7FFFFFFF → MMIO bridge
  (AddressSet(0x0L, 0x7fffffffL), mmioNodeID),
  // Local DDR: 0x80000000 - 0x3FFFFFFFFF → Local OpenLLC
  (AddressSet(0x80000000L, 0x3f7fffffffL), llcNodeID),
  // Remote DDR: 0x4000000000 - 0x7FFFFFFFFF → Bridge (appears as another HN-F target)
  (AddressSet(0x4000000000L, 0x3fffffffffL), bridgeNodeID)
))
```

But wait — the current design has the bridge connecting as an **RN-F** to the OpenLLC,
not as a separate routing target from the L2. There are two integration approaches:

**Approach A — Bridge as routing target (L2 → Bridge → Remote LLC):**
The L2 sends requests directly to the bridge when the address maps to the remote socket.
The bridge then forwards to the remote OpenLLC. This requires the bridge to appear as
an HN-F to local L2s.

**Approach B — Bridge as RN-F on both LLCs (L2 → Local LLC → Bridge → Remote LLC):**
All L2 requests go to the local OpenLLC first. The OpenLLC recognizes remote addresses
(via SAM) and forwards them to the bridge port. The bridge forwards to the remote OpenLLC.

**We choose Approach B** because:
- OpenLLC already has a SAM (`sam: Seq[(AddressSet, Int)]` in `LLCParam.scala:52`)
- The local OpenLLC can cache remote data in its self-directory (acting as a remote cache)
- Snoop filter naturally tracks which local L2s have remote data
- Simpler L2 configuration — L2s only see one HN-F (local OpenLLC)

However, this means the OpenLLC must be enhanced to forward requests for non-local
addresses to the bridge port instead of to the SN-F (memory) port. This is done by
extending the SAM to route remote addresses to the bridge RN-F's response path.

**Alternative (simpler initial approach):** Use the existing memory (SN) path. The bridge
connects as an additional SN-F alongside OpenNCB. Remote-address requests from OpenLLC's
MemUnit go to the bridge instead of to DDR. This uses the existing `NoSnpPortIO` and
requires only SAM configuration, no OpenLLC code changes.

### 10.3 Bridge as SN-F (Simpler Integration)

```
OpenLLC
├── rn(0..N-1)  ← local L2 caches
├── sn          → SNXbar
                  ├── sn(0): OpenNCB → local DDR  (local address range)
                  └── sn(1): XSBridge → remote socket (remote address range)
```

This approach leverages the `SNXbar` already in OpenLLC. The bridge appears as an SN-F
(subordinate node) for the remote memory range. Address-based routing in the SAM directs
remote-address ReadNoSnp/WriteNoSnpFull to the bridge instead of to DDR.

**Advantage:** Zero OpenLLC code changes. The bridge handles CHI SN protocol (ReadNoSnp,
WriteNoSnpFull, CompData, CompDBIDResp) which is simpler than full RN-F protocol.

**Disadvantage:** The bridge can't directly receive snoops this way. For the "remote
present" directory bit, we still need the bridge as an RN-F.

### 10.4 Hybrid Approach (Recommended)

Connect the bridge to OpenLLC via **both** paths:

1. **As RN-F (port N):** For receiving snoops and injecting proxy requests on behalf of
   the remote socket
2. **As SN-F (via SAM):** For local L2/LLC requests targeting remote memory addresses,
   routed through the existing MemUnit path

This provides full functionality with minimal OpenLLC modification:
- SAM configuration routes remote addresses to bridge SN port
- Bridge RN-F port handles snoop forwarding and proxy requests
- OpenLLC's client directory tracks remote presence via the bridge's RN-F index

---

## 11. Corner Cases and Hazards

### 11.1 Request-Snoop Race

**Scenario:** Socket 0 L2 requests ReadShared(X) to remote memory. While the request is
in flight through the bridge, Socket 1 core writes to X and needs to snoop Socket 0.

**Timeline:**
1. S0: L2 → LLC → Bridge: ReadShared(X) (in flight)
2. S1: Core writes X → LLC sends SnpUnique to S1's bridge port → forwards to S0
3. S0: Snoop arrives at S0 OpenLLC for bridge's RN-F port

**Resolution:** The S0 OpenLLC handles this correctly — the snoop targets the bridge's
RN-F port, and the bridge's SnoopTracker processes it. If the bridge hasn't yet cached
the line (the ReadShared hasn't completed), it returns SnpResp(I) immediately (nothing
to invalidate). The ReadShared will see the updated data when it eventually reaches S1's
OpenLLC.

### 11.2 Simultaneous Cross-Socket Requests to Same Line

**Scenario:** Socket 0 and Socket 1 both request ReadUnique(X) at the same time, where X
is in Socket 0's memory range.

**Resolution:** Both requests arrive at Socket 0's OpenLLC (one directly from S0 L2, one
through the bridge). OpenLLC's RequestArb serializes them. The first one gets the line;
the second triggers a snoop of the first to transfer ownership.

### 11.3 Bridge Starvation

**Scenario:** Local L2 requests flood the OpenLLC, starving the bridge's injected requests.

**Resolution:** The bridge connects as RN-F port N, which has equal priority in the
`RNXbar` arbitration. For fairness, the `RNXbar` uses `FastArbiter` which provides
round-robin-like arbitration. No special priority needed — the bridge competes fairly.

If starvation becomes a problem, configure QoS bits in the bridge's CHI requests to
signal higher priority for cross-socket traffic.

### 11.4 Power-Down with Outstanding Cross-Socket Transactions

**Scenario:** Socket 1 initiates power-down (SysCo exit) while cross-socket transactions
are in flight.

**Resolution:** Before SysCo exit:
1. Bridge drains all outstanding requests/snoops (both directions)
2. Bridge sends CleanInvalid for all lines marked as "bridge-present" in remote
   socket's client directory
3. Once drained, bridge deasserts `syscoreq` on its RN-F port
4. Local OpenLLC completes SysCo exit handshake
5. Socket can safely power down

---

## 12. Parameters and Configurability

```scala
case class XSBridgeParams(
  // Tracker sizes
  requestEntries: Int = 16,       // Outstanding cross-socket requests
  snoopEntries: Int = 16,         // Outstanding cross-socket snoops
  dataBufferEntries: Int = 8,     // Data assembly buffers

  // Link configuration
  linkType: String = "parallel",  // "parallel", "async", "serial"
  linkWidthBits: Int = 256,       // For serial: link data width
  asyncDepth: Int = 4,            // Async queue depth (if async)
  asyncSync: Int = 3,             // CDC synchronizer stages

  // L-Credit config
  txReqCredits: Int = 4,
  txRspCredits: Int = 4,
  txDatCredits: Int = 4,
  rxSnpCredits: Int = 16,
  rxRspCredits: Int = 4,
  rxDatCredits: Int = 4,

  // Timeouts
  requestTimeoutCycles: Int = 20000,
  snoopTimeoutCycles: Int = 10000,

  // Debug
  enablePerfCounters: Boolean = true,
  enableCHILog: Boolean = true
)
```

---

## 13. Verification Plan

### 13.1 Unit Tests (BridgeAgent)

| Test | Description |
|------|-------------|
| basic_read | Single ReadShared through bridge, verify data correctness |
| basic_write | WriteUnique through bridge, verify data written to remote DDR |
| txnid_remap | Verify txnID remapping round-trip (no ID leaks) |
| credit_flow | Fill all credits, verify back-pressure, drain and resume |
| timeout | Inject a non-responding request, verify timeout fires |

### 13.2 Integration Tests (DualSocketTop)

| Test | Description |
|------|-------------|
| remote_read | S0 core reads S1 memory, verify correct data |
| remote_write | S0 core writes S1 memory, S1 core reads it back |
| cross_snoop | S0 caches S1 line, S1 writes it, verify S0 invalidated |
| dirty_transfer | S0 has UD line in S1 range, S1 reads it, verify dirty data transfer |
| false_sharing | Two cores on different sockets write adjacent bytes in same line |
| ping_pong | Alternate ownership of one line between sockets (perf test) |
| atomic_cross | LR/SC pair where line crosses sockets between LR and SC |
| multicore_cross | 4 cores (2 per socket) all accessing shared array |

### 13.3 Stress Tests

| Test | Description |
|------|-------------|
| bandwidth | STREAM benchmark, measure local vs. remote bandwidth |
| latency | Pointer chase through remote memory, measure latency |
| deadlock | Max-pressure both directions simultaneously |
| power_cycle | Power down Socket 1 while Socket 0 has outstanding remote requests |

### 13.4 Protocol Compliance

- CHI protocol checker on all bridge ports (verify legal opcode sequences)
- No orphaned transactions (every request gets a response)
- No txnID reuse while outstanding
- Credit conservation (credits issued = credits consumed + credits held)

---

## Appendix: Key Source Files Referenced

| File | What We Learned |
|------|-----------------|
| `openLLC/Directory.scala:38-70` | ClientMetaEntry is just `{valid: Bool}` per RN-F |
| `openLLC/Directory.scala:312-341` | clientDir is parameterized by `numRNs` — adding an RN-F is config-only |
| `openLLC/MainPipe.scala:281-344` | Snoop decision uses `snpVec(numRNs)` — bridge index included automatically |
| `openLLC/OpenLLC.scala:37-46` | `io.rn = Vec(numRNs, Flipped(new PortIO))` — bridge connects as rn(N) |
| `openLLC/LLCParam.scala:30-53` | `clientCaches: Seq[L2Param]` determines numRNs; `sam` routes by address |
| `openLLC/Slice.scala:27-151` | Full slice pipeline: ReqBuf→ReqArb→MainPipe→{Snp,Resp,Mem,Refill}Unit |
| `openLLC/Common.scala:37-118` | Task bundle: all CHI fields + snpVec + reqID for internal tracking |
| `openLLC/ResponseUnit.scala:27-55` | ResponseState tracks w_snpRsp — handles bridge snoop responses naturally |
| `openLLC/utils/CHIXbar.scala:26-115` | RNXbar demuxes snoops by snpMask — bridge gets snoops when mask bit set |
| `openLLC/utils/TargetBinder.scala:29-90` | `route()` + `bind()`: SAM-based address routing for CHI |
| `openLLC/chi/LinkLayer.scala:58-225` | RNLinkMonitor: L-Credit flow control, srcID/tgtID rewriting |
| `coupledL2/tl2chi/chi/AsyncBridge.scala:157-310` | Shadow buffer + async queue pattern for CDC |
| `coupledL2/tl2chi/chi/LinkLayer.scala:133-324` | L-Credit pool management (LCredit2Decoupled, Decoupled2LCredit) |
| `coupledL2/tl2chi/chi/Message.scala:426-583` | Full CHI flit definitions (CHIREQ, CHISNP, CHIDAT, CHIRSP) |
