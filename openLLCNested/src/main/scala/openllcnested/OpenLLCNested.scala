/***************************************************************************************
* Copyright (c) 2024 Beijing Institute of Open Source Chip (BOSC)
* Copyright (c) 2020-2024 Institute of Computing Technology, Chinese Academy of Sciences
*
* OpenLLCNested is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

// NOTE: This file requires the openLLC and coupledL2 submodules to be initialized.
//       Run: git submodule update --init openLLC coupledL2

package openllcnested

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import coupledL2.tl2chi.PortIO
import coupledL2.L2Param
import openLLC.OpenLLCParam
import system.HasSoCParameter

case object OpenLLCNestedParamKey extends Field[OpenLLCParam]

// ============================================================================
// MSHR Transaction type encoding
// ============================================================================
// UP_MISS: A Core L2 (RN) requested a cache line that NESTED missed.
//          NESTED must issue REQ to OpenLLC (acting as RN) to fetch the line.
// UP_SNP:  NESTED must send SNP to Core L2 sharer(s) for coherence
//          (triggered by a write/upgrade request from another Core L2).
// DN_SNP:  OpenLLC snooped NESTED (NESTED cached the line as an RN).
//          NESTED must forward the SNP to the Core L2(s) that have copies.
object MSHRTxnType {
  val width = 2
  val UP_MISS = 0.U(width.W)
  val UP_SNP  = 1.U(width.W)
  val DN_SNP  = 2.U(width.W)
}

// ============================================================================
// MSHR State encoding
// ============================================================================
// State transitions per transaction type:
//
//  UP_MISS (Core L2 miss → OpenLLC fetch):
//    IDLE
//      → (optional) WAIT_SNP_RESP   if existing sharers must be invalidated first
//      → WAIT_SN_DAT                send REQ to OpenLLC, wait for CompData/DataSepResp
//      → WAIT_COMP_ACK              send CompData to Core L2, wait for CompAck (if needed)
//      → IDLE                       deallocate
//
//  UP_SNP (Core L2 write hit, invalidate other sharers):
//    IDLE
//      → WAIT_SNP_RESP              send SNP to sharer Core L2s, wait for all SnpResp
//      → IDLE                       send Comp to requester, deallocate
//
//  DN_SNP (OpenLLC snoops NESTED):
//    IDLE
//      → WAIT_SNP_RESP              forward SNP to Core L2 sharer(s), wait for SnpResp(Data)
//      → IDLE                       send SnpResp(Data) to OpenLLC, deallocate
object MSHRState {
  val width = 3
  val IDLE          = 0.U(width.W)
  val WAIT_SNP_RESP = 1.U(width.W) // waiting for SnpResp(Data) from Core L2(s)
  val WAIT_SN_DAT   = 2.U(width.W) // waiting for CompData from OpenLLC
  val WAIT_COMP_ACK = 3.U(width.W) // sent data to requester, waiting for CompAck
  val SEND_RESP     = 4.U(width.W) // constructing response (completes next cycle)
}

// ============================================================================
// MSHR Entry
// ============================================================================
// One entry per in-flight CHI transaction.  All field widths use the CHI
// Issue B/E.b parameter space; adjust if targeting a different CHI issue.
//
//  +----------+--------+----------------------------------------------------+
//  | Field    | Bits   | Description                                        |
//  +----------+--------+----------------------------------------------------+
//  | valid    |  1     | Entry is allocated                                 |
//  | txnType  |  2     | MSHRTxnType: UP_MISS / UP_SNP / DN_SNP             |
//  | state    |  3     | MSHRState: current progress in the protocol FSM    |
//  | srcID    | 12     | CHI SrcID of the initiating node                   |
//  | txnID    | 12     | CHI TxnID from the initiating node                 |
//  | snTxnID  | 12     | TxnID allocated for the downstream (SN) side REQ  |
//  | opcode   |  7     | CHI REQ or SNP opcode                              |
//  | addr     | 48     | Full physical address of the target cache line     |
//  | snpPend  | N      | Bitmask: 1 = Core L2[i] has not yet replied to SNP |
//  | gotDirty |  1     | At least one SnpRespData was dirty                 |
//  | hasData  |  1     | dataBuf holds valid data                           |
//  | dataBuf  | 512    | 64-byte data buffer (snoop response or fill data)  |
//  +----------+--------+----------------------------------------------------+
class MSHREntry(numClients: Int)(implicit p: Parameters) extends Bundle {
  val valid      = Bool()
  val txnType    = UInt(MSHRTxnType.width.W)
  val state      = UInt(MSHRState.width.W)
  // CHI addressing
  val srcID      = UInt(12.W)
  val txnID      = UInt(12.W)
  val snTxnID    = UInt(12.W)
  val opcode     = UInt(7.W)
  val addr       = UInt(48.W)
  // SNP response tracking
  val snpPending = UInt(numClients.W)
  val gotDirty   = Bool()
  // Data buffer
  val hasData    = Bool()
  val dataBuf    = UInt(512.W) // 64 bytes = 512 bits
}

// ============================================================================
// OpenLLCNested: coherent intermediate cache between Core L2s and OpenLLC
// ============================================================================
//
// Block Diagram:
//
//   Core L2[0] ─── Core L2[N-1]   (RN nodes)
//         │               │
//         └──── CHILogger ┘  (one per Core L2, in Top.scala)
//                   │
//        ┌──────────┴──────────────────────────┐
//        │          OpenLLCNested  (HN↑ / RN↓) │
//        │                                     │
//        │  io.rn[0..N-1]   ← REQ  from Core L2│
//        │                  → SNP  to   Core L2│
//        │                  ↔ RSP              │
//        │                  ↔ DAT              │
//        │                                     │
//        │  ┌─────────────────────────────┐    │
//        │  │  Directory (per-line state) │    │
//        │  │    tag | state | sharer vec │    │
//        │  └────────────────┬────────────┘    │
//        │                   │ hit/miss        │
//        │  ┌────────────────▼────────────┐    │
//        │  │  MSHR (miss status holding) │    │
//        │  │  ┌─────────────────────┐   │    │
//        │  │  │ Entry 0             │   │    │
//        │  │  │  type / state       │   │    │
//        │  │  │  addr / txnID       │   │    │
//        │  │  │  snpPending bitmask │   │    │
//        │  │  │  dataBuf (512b)     │   │    │
//        │  │  ├─────────────────────┤   │    │
//        │  │  │ Entry 1 .. Entry M  │   │    │
//        │  │  └─────────────────────┘   │    │
//        │  └────────────────┬────────────┘    │
//        │                   │                 │
//        │  ┌────────────────▼────────────┐    │
//        │  │  Data Array (SRAM)          │    │
//        │  │    cached lines for NESTED  │    │
//        │  └─────────────────────────────┘    │
//        │                                     │
//        │  io.sn  → REQ  to   OpenLLC         │
//        │        ← SNP  from  OpenLLC  (NEW)  │
//        │        ↔ RSP                        │
//        │        ↔ DAT                        │
//        └─────────────────────────────────────┘
//                   │
//             CHILogger (in Top.scala)
//                   │
//           OpenLLC.io.rn[0]   (HN node)
//                   │
//           OpenLLC.io.sn
//                   │
//             CHILogger
//                   │
//             OpenNCB → AXI4 memory
//
// CHI channel matrix:
//   Face           │ REQ │ SNP │ RSP │ DAT
//   ───────────────┼─────┼─────┼─────┼─────
//   io.rn[i] (up)  │  ←  │  →  │  ↔  │  ↔
//   io.sn    (down)│  →  │  ←  │  ↔  │  ↔
//
// The SNP channel on io.sn (↑ direction) is the key addition described in
// the issue: OpenLLC must expose SNP on its io.rn port so that it can send
// coherence probes to NESTED when NESTED's cached lines need invalidation.
//
// MSHR Transaction Flows:
//
//   [UP_MISS] Core L2 miss → fetch from OpenLLC:
//     1. Core L2 sends REQ on io.rn[i].req
//     2. Directory lookup → miss  (or hit-but-need-invalidate)
//     3. MSHR allocated: txnType=UP_MISS, state=WAIT_SNP_RESP (if sharers exist)
//                        or state=WAIT_SN_DAT (if no sharers)
//     4. (If sharers) SNP sent to sharer Core L2s via io.rn[j].snp
//     5. Collect SnpResp(Data) from sharers; snpPending decrements to 0
//     6. Issue ReadNoSnp/ReadUnique REQ to OpenLLC via io.sn.req
//        state → WAIT_SN_DAT
//     7. Receive CompData from OpenLLC via io.sn.dat
//     8. Fill NESTED data array; send DAT to requesting Core L2 via io.rn[i].dat
//        state → WAIT_COMP_ACK (if CompAck required)
//     9. Receive CompAck; MSHR deallocated
//
//   [DN_SNP] OpenLLC snoops NESTED's cached line:
//     1. OpenLLC sends SNP on io.sn.snp  (requires SNP channel on OpenLLC.io.rn)
//     2. Directory lookup → which Core L2(s) hold a copy?
//     3. MSHR allocated: txnType=DN_SNP, state=WAIT_SNP_RESP
//     4. SNP forwarded to relevant Core L2(s) via io.rn[j].snp
//     5. Collect SnpResp(Data); snpPending decrements to 0
//     6. Send SnpResp(Data) to OpenLLC via io.sn.rsp / io.sn.dat
//     7. MSHR deallocated; directory updated

// Number of MSHR entries (configurable; 16 is a reasonable default)
case object OpenLLCNestedMSHRNum extends Field[Int](16)

class OpenLLCNested()(implicit p: Parameters) extends Module with HasSoCParameter {

  val nestedParams = p(OpenLLCNestedParamKey)
  val numRNs       = nestedParams.clientCaches.length
  val mshrNum      = p.lift(OpenLLCNestedMSHRNum).getOrElse(16)
  val nodeIDWidth  = soc.NodeIDWidthList(issue)

  val io = IO(new Bundle {
    // HN-facing ports: accept CHI traffic from Core L2 RNs
    val rn = Vec(numRNs, Flipped(new PortIO))
    // RN-facing port: issue CHI traffic to OpenLLC HN; receives SNP from OpenLLC
    val sn = new PortIO
    val nodeID = Input(UInt(nodeIDWidth.W))
    val debugTopDown = new Bundle {
      val robHeadPaddr = Input(Vec(numRNs, Valid(UInt(soc.PAddrBits.W))))
      val addrMatch    = Output(Vec(numRNs, Bool()))
    }
    val l3Miss = Output(Bool())
  })

  // =========================================================================
  // MSHR array
  // =========================================================================
  // mshr(i) is the i-th MSHR entry.  Allocation policy: first-free.
  // Deallocation: when state returns to IDLE (or explicitly cleared).
  val mshr = RegInit(VecInit(Seq.fill(mshrNum)(0.U.asTypeOf(new MSHREntry(numRNs)))))

  // Free-entry pointer: one-hot encoding of the first free MSHR slot.
  val mshrFree     = mshr.map(!_.valid)
  val mshrFreeOH   = PriorityEncoderOH(mshrFree)
  val mshrFreeIdx  = OHToUInt(mshrFreeOH)
  val mshrAnyFree  = mshrFree.reduce(_ || _)

  // =========================================================================
  // Directory (per-line coherence state)
  // =========================================================================
  // A full directory implementation needs:
  //   - Tag array: physical address tag per set/way
  //   - State array: per-line coherence state (Invalid/Shared/Exclusive/Modified)
  //   - Sharer vector: per-line bitmask of Core L2s holding a copy
  // These are SRAM-backed in a real implementation.

  // =========================================================================
  // Data array
  // =========================================================================
  // Stores cache lines cached by NESTED itself.
  // On UP_MISS: filled from OpenLLC CompData.
  // On DN_SNP with RetToSrc: data sent to OpenLLC then possibly kept.

  // =========================================================================
  // Placeholder wiring (replace with actual pipeline logic)
  // =========================================================================
  // The sections above define the data structures.  The request/snoop pipelines
  // that drive these structures are the main engineering work:
  //
  //   req_pipeline:  arbitrates io.rn[*].req → directory lookup → MSHR alloc
  //                  → SNP issue on io.rn[*].snp or REQ issue on io.sn.req
  //   snp_pipeline:  handles io.sn.snp (DN_SNP) → directory lookup → MSHR alloc
  //                  → SNP forwarding on io.rn[*].snp
  //   resp_pipeline: collects RSP/DAT from both faces → MSHR update → completion

  io.rn.foreach { rnPort =>
    rnPort := DontCare
  }
  io.sn := DontCare

  io.l3Miss := mshr.map(e => e.valid && e.txnType === MSHRTxnType.UP_MISS).reduce(_ || _)
  io.debugTopDown.addrMatch.foreach(_ := false.B)

  // Expose MSHR occupancy for debug
  dontTouch(mshr)
}
