/***************************************************************************************
  * Copyright (c) 2024 Beijing Institute of Open Source Chip (BOSC)
  * Copyright (c) 2024 Institute of Computing Technology, Chinese Academy of Sciences
  *
  * XiangShan is licensed under Mulan PSL v2.
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

package top

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import coupledL2.tl2chi.PortIO

/**
 * OPENLLC_NESTED: A CHI bridge node inserted between L2 caches and OpenLLC.
 *
 * Role:
 *   - Acts as HN (Home Node) to CoreWithL2: L2 caches see NESTED as the LLC/interconnect.
 *   - Acts as RN (Request Node) to OpenLLC: OpenLLC sees NESTED as a single client.
 *
 * Topology:
 *   CoreWithL2[0..N-1] --CHI(as RN)--> OpenLLCNested(HN) --CHI(as RN)--> OpenLLC(HN)
 *                                                                              |
 *                                                                         OpenNCB(SN)
 *                                                                              |
 *                                                                            DRAM
 *
 * When OpenLLC receives a DMA request via OpenNCB (an RN), it issues a snoop to NESTED
 * (since NESTED is the sole registered client/RN). NESTED then forwards this snoop to
 * the appropriate L2 cache(s), collects snoop responses, and returns data to OpenLLC.
 * This "OPENNCB方向的SNP通道" is handled by the snoop forwarding logic below.
 *
 * CHI channel mapping:
 *   HN side (toward L2):
 *     rx.req  <- L2.tx.req   : receive cache requests from L2
 *     rx.dat  <- L2.tx.dat   : receive write-data from L2
 *     tx.rsp  -> L2.rx.rsp   : send responses to L2
 *     tx.dat  -> L2.rx.dat   : send read-data to L2
 *     tx.snp  -> L2.rx.snp   : issue snoops to L2 (forwarded from OpenLLC or OPENNCB)
 *
 *   RN side (toward OpenLLC):
 *     tx.req  -> LLC.rx.req  : forward requests to OpenLLC
 *     tx.dat  -> LLC.rx.dat  : forward write-data to OpenLLC
 *     rx.rsp  <- LLC.tx.rsp  : receive responses from OpenLLC
 *     rx.dat  <- LLC.tx.dat  : receive read-data from OpenLLC
 *     rx.snp  <- LLC.tx.snp  : receive snoops from OpenLLC (for DMA/OPENNCB coherency)
 *
 * NOTE: This file provides the architectural skeleton. Full protocol-correct
 * implementation requires:
 *   1. CHI transaction ID remapping (srcID/txnID translation table)
 *   2. Per-L2 snoop filter / inclusive directory
 *   3. Snoop collection and retry logic
 *   4. Full CHI issue-B/E compliance (ordering, retry, credits, etc.)
 *
 * Parameters:
 *   numCores   - Number of L2 caches (HN ports)
 *   txnEntries - Number of outstanding transaction tracker entries
 */

/** Per-transaction tracker entry: maps a remapped TXNID back to the originating L2. */
class NestedTxnEntry(srcIdBits: Int, txnIdBits: Int) extends Bundle {
  val valid    = Bool()
  val srcPort  = UInt(log2Ceil(64).W)    // which L2 (0..numCores-1) issued this txn
  val origSrc  = UInt(srcIdBits.W)       // original SrcID from L2 REQ
  val origTxn  = UInt(txnIdBits.W)       // original TxnID from L2 REQ
}

class OpenLLCNested(
  numCores   : Int,
  txnEntries : Int = 128,
  srcIdBits  : Int = 12,
  txnIdBits  : Int = 12
)(implicit p: Parameters) extends Module {

  require(numCores >= 1, "OpenLLCNested requires at least one core")
  require(isPow2(txnEntries), "txnEntries must be a power of 2")

  val io = IO(new Bundle {
    /** HN ports: L2[i] connects here; NESTED appears as HN/ICN to each L2. */
    val hn      = Vec(numCores, Flipped(new PortIO))
    /** RN port: NESTED connects to OpenLLC; NESTED appears as RN to OpenLLC. */
    val rn      = new PortIO
    /** This node's CHI NodeID (used to rewrite SrcID in forwarded requests). */
    val nodeID  = Input(UInt(srcIdBits.W))
  })

  // =========================================================================
  // Transaction tracker
  // =========================================================================
  // When NESTED forwards a REQ from L2[i] to OpenLLC, it:
  //   1. Replaces SrcID with io.nodeID (so OpenLLC responds to NESTED).
  //   2. Allocates a local TXNID and saves {srcPort, origSrc, origTxn}.
  //   3. On receiving the RSP/DAT from OpenLLC (with the local TXNID),
  //      looks up the tracker and routes the response back to L2[origSrc].
  val txnTable = RegInit(VecInit(Seq.fill(txnEntries)(0.U.asTypeOf(new NestedTxnEntry(srcIdBits, txnIdBits)))))

  // Free-list of local TXNIDs
  val txnFreeList = RegInit(VecInit((0 until txnEntries).map(_.U(log2Ceil(txnEntries).W))))
  val freeHead    = RegInit(0.U(log2Ceil(txnEntries).W))
  val freeTail    = RegInit(0.U(log2Ceil(txnEntries).W))
  val freeCount   = RegInit(txnEntries.U(log2Ceil(txnEntries + 1).W))

  def txnAlloc(srcPort: UInt, origSrc: UInt, origTxn: UInt): (Bool, UInt) = {
    val canAlloc = freeCount > 0.U
    val allocId  = txnFreeList(freeHead)
    when(canAlloc) {
      txnTable(allocId).valid   := true.B
      txnTable(allocId).srcPort := srcPort
      txnTable(allocId).origSrc := origSrc
      txnTable(allocId).origTxn := origTxn
      freeHead   := freeHead + 1.U
      freeCount  := freeCount - 1.U
    }
    (canAlloc, allocId)
  }

  def txnFree(id: UInt): Unit = {
    txnTable(id).valid     := false.B
    txnFreeList(freeTail)  := id
    freeTail               := freeTail + 1.U
    freeCount              := freeCount + 1.U
  }

  // =========================================================================
  // REQ arbiter: N L2 inputs -> 1 OpenLLC output
  // =========================================================================
  // Round-robin arbitration across all L2 HN ports.
  // On winning: remap SrcID/TxnID, allocate tracker entry, forward to LLC.
  //
  // NOTE: CHI REQ carries: SrcID, TxnID, TgtID, Opcode, Addr, ...
  //       We must rewrite SrcID := io.nodeID and TxnID := allocated local ID.
  //       The `bits` type is PortIO-internal; actual field access depends on
  //       the coupledL2 submodule's CHI bundle definition.

  val reqInputs = io.hn.map(_.tx.req)
  val reqArb    = Module(new RRArbiter(chiselTypeOf(reqInputs.head.bits), numCores))
  for (i <- 0 until numCores) {
    reqArb.io.in(i).valid := reqInputs(i).valid
    reqArb.io.in(i).bits  := reqInputs(i).bits
    reqInputs(i).ready    := reqArb.io.in(i).ready
  }
  val winPort    = reqArb.io.chosen
  val winReqBits = reqArb.io.out.bits
  val winReqVal  = reqArb.io.out.valid

  // TODO: Extract SrcID and TxnID from winReqBits (depends on CHI bundle layout).
  // Placeholder wires — replace with actual field extraction from the CHI bundle:
  val origSrc = WireInit(0.U(srcIdBits.W))  // TODO: origSrc := winReqBits.srcID
  val origTxn = WireInit(0.U(txnIdBits.W))  // TODO: origTxn := winReqBits.txnID

  val (canAlloc, localTxn) = txnAlloc(winPort, origSrc, origTxn)

  io.rn.tx.req.valid := winReqVal && canAlloc
  io.rn.tx.req.bits  := winReqBits
  // TODO: overwrite SrcID and TxnID in the forwarded flit:
  //   io.rn.tx.req.bits.srcID := io.nodeID
  //   io.rn.tx.req.bits.txnID := localTxn
  reqArb.io.out.ready := io.rn.tx.req.ready && canAlloc

  // =========================================================================
  // Write-DAT arbiter: N L2 DAT inputs -> OpenLLC
  // =========================================================================
  // Write data must follow the corresponding REQ; CHI allows DAT to be sent
  // concurrently with or after the REQ.  Simplest policy: same RR arbiter.
  val datInputs = io.hn.map(_.tx.dat)
  val datArb    = Module(new RRArbiter(chiselTypeOf(datInputs.head.bits), numCores))
  for (i <- 0 until numCores) {
    datArb.io.in(i).valid := datInputs(i).valid
    datArb.io.in(i).bits  := datInputs(i).bits
    datInputs(i).ready    := datArb.io.in(i).ready
  }
  io.rn.tx.dat          <> datArb.io.out
  // TODO: rewrite SrcID in DAT flit similarly to REQ.

  // =========================================================================
  // RSP router: OpenLLC RSP -> correct L2
  // =========================================================================
  // CHI RSP from OpenLLC carries TxnID = the local TXNID we allocated.
  // Look up the tracker to find which L2 to forward to, then restore
  // the original {SrcID, TxnID} in the RSP flit.
  //
  // TODO: Extract TxnID from io.rn.rx.rsp.bits to index txnTable.
  val rspTxnId = WireInit(0.U(log2Ceil(txnEntries).W))  // TODO: rspTxnId := io.rn.rx.rsp.bits.txnID
  val rspEntry = txnTable(rspTxnId)

  for (i <- 0 until numCores) {
    io.hn(i).rx.rsp.valid := false.B
    io.hn(i).rx.rsp.bits  := io.rn.rx.rsp.bits
    // TODO: restore origSrc/origTxn in bits when routing
  }
  io.rn.rx.rsp.ready := false.B

  when(io.rn.rx.rsp.valid) {
    val targetPort = rspEntry.srcPort
    for (i <- 0 until numCores) {
      when(targetPort === i.U) {
        io.hn(i).rx.rsp.valid := true.B
        // TODO: io.hn(i).rx.rsp.bits.tgtID := rspEntry.origSrc
        // TODO: io.hn(i).rx.rsp.bits.txnID := rspEntry.origTxn
      }
    }
    // accept RSP once the target L2 accepts it; consume tracker on completion
    io.rn.rx.rsp.ready := io.hn(rspEntry.srcPort).rx.rsp.ready
    // TODO: free tracker when transaction is fully complete (may be on DAT, not RSP)
  }

  // =========================================================================
  // Read-DAT router: OpenLLC DAT -> correct L2
  // =========================================================================
  // Similar to RSP routing.
  val datRxTxnId = WireInit(0.U(log2Ceil(txnEntries).W))  // TODO: datRxTxnId := io.rn.rx.dat.bits.txnID
  val datRxEntry = txnTable(datRxTxnId)

  for (i <- 0 until numCores) {
    io.hn(i).rx.dat.valid := false.B
    io.hn(i).rx.dat.bits  := io.rn.rx.dat.bits
  }
  io.rn.rx.dat.ready := false.B

  when(io.rn.rx.dat.valid) {
    val targetPort = datRxEntry.srcPort
    for (i <- 0 until numCores) {
      when(targetPort === i.U) {
        io.hn(i).rx.dat.valid := true.B
      }
    }
    io.rn.rx.dat.ready := io.hn(datRxEntry.srcPort).rx.dat.ready
    // TODO: free tracker entry after last DAT beat
    when(io.rn.rx.dat.fire) {
      txnFree(datRxTxnId)
    }
  }

  // =========================================================================
  // SNP forwarder: OpenLLC SNP -> L2 (OPENNCB-direction coherency)
  // =========================================================================
  // When OpenLLC (acting as HN) needs to snoop NESTED (as RN) for coherency
  // — triggered by DMA/OPENNCB RN requests — it sends a CHI SNP to NESTED.
  //
  // NESTED must:
  //   1. Decode the snoop address.
  //   2. Consult its snoop filter (directory) to find which L2(s) may have
  //      the cache line (Invalid in NESTED's directory → respond immediately).
  //   3. Forward the SNP to the L2(s) that have the line.
  //   4. Collect SnpResp / SnpRespData from each snooped L2.
  //   5. Return SnpResp (+ optional data) back to OpenLLC via tx.rsp / tx.dat.
  //
  // This forms the "OPENNCB方向的SNP通道" (SNP channel from OPENNCB direction):
  //   OPENNCB → (DMA REQ) → OpenLLC → (SNP) → NESTED → (SNP) → L2[i]
  //                                                    ← (SnpResp/Data) ←
  //                       ← (CompData / SnpResp) ←
  //
  // TODO: Implement snoop filter (directory) to avoid broadcasting every snoop.
  //       For correctness without a directory, broadcast to ALL L2 caches and
  //       wait for all SnpResp; this is expensive but functionally correct.

  // Snoop broadcast state machine (simplified: broadcast to all L2 caches)
  val sNoSnoop :: sBroadcast :: sCollect :: sRespond :: Nil = Enum(4)
  val snpState   = RegInit(sNoSnoop)
  val snpPending = RegInit(VecInit(Seq.fill(numCores)(false.B)))  // which L2s need to respond
  val snpBits    = RegInit(0.U.asTypeOf(io.rn.rx.snp.bits.cloneType))
  val snpHasData = RegInit(false.B)
  val snpDataBuf = RegInit(0.U.asTypeOf(io.rn.tx.dat.bits.cloneType))

  // Default: stall OpenLLC SNP until we can accept
  io.rn.rx.snp.ready := false.B
  for (i <- 0 until numCores) {
    io.hn(i).rx.snp.valid := false.B
    io.hn(i).rx.snp.bits  := snpBits
  }
  // SNP responses from L2 go via the tx.rsp / tx.dat paths (reuse DAT arbiter above)
  // TODO: arbitrate between normal-path DAT (write from L2) and snoop-response DAT.

  switch(snpState) {
    is(sNoSnoop) {
      io.rn.rx.snp.ready := true.B
      when(io.rn.rx.snp.valid) {
        snpBits    := io.rn.rx.snp.bits
        snpHasData := false.B
        // TODO: If snoop filter says no L2 has line, jump directly to sRespond.
        snpPending := VecInit(Seq.fill(numCores)(true.B))  // broadcast
        snpState   := sBroadcast
      }
    }
    is(sBroadcast) {
      // Issue SNP to each pending L2 in round-robin; move to sCollect when all issued.
      for (i <- 0 until numCores) {
        when(snpPending(i)) {
          io.hn(i).rx.snp.valid := true.B
          when(io.hn(i).rx.snp.ready) {
            snpPending(i) := false.B
          }
        }
      }
      when(!snpPending.asUInt.orR) {
        snpPending := VecInit(Seq.fill(numCores)(true.B))  // reuse to track outstanding responses
        snpState   := sCollect
      }
    }
    is(sCollect) {
      // Wait for SnpResp from each L2.  SnpResp comes on tx.rsp of the HN port.
      // SnpRespData additionally arrives on tx.dat.
      // TODO: collect via io.hn(i).tx.rsp and io.hn(i).tx.dat (need separate
      //       wiring from the normal REQ/DAT arbiter paths).
      //       Mark snpPending(i) := false when SnpResp received from L2[i].
      when(!snpPending.asUInt.orR) {
        snpState := sRespond
      }
    }
    is(sRespond) {
      // Send SnpResp (+ optional data) back to OpenLLC via io.rn.tx.rsp / tx.dat.
      // TODO: drive io.rn.tx.rsp with combined SnpResp, and io.rn.tx.dat if data.
      snpState := sNoSnoop
    }
  }

  // =========================================================================
  // System coherency handshake (pass-through)
  // =========================================================================
  io.rn.syscoreq      := io.hn.map(_.syscoreq).reduce(_ || _)
  for (i <- 0 until numCores) {
    io.hn(i).syscoack := io.rn.syscoack
  }
}
