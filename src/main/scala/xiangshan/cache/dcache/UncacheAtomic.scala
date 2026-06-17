/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
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

// NC AMO multi-outstanding buffer.
// Routes NC regular AMO (amoswap/add/xor/and/or/min/max/minu/maxu) through
// TileLink Arithmetic/Logical → coupledL2 → CHI AtomicLoad/AtomicSwap → HN-F.
// LR, SC, AMOCAS are serialised to 1 outstanding (isExclusive=true).

package xiangshan.cache

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import utils._
import utility._
import xiangshan._
import xiangshan.backend.Bundles.{DynInst, MemWriteBack}
import xiangshan.backend.exu.ExeUnitParams
import xiangshan.mem._
import xiangshan.mem.HasMemBlockParameters
import xscache.coupledL2.{MemBackTypeMM, MemBackTypeMMField, MemPageTypeNC, MemPageTypeNCField}

// Request bundle: AtomicsUnit → UncacheAtomicBuffer
// isExclusive = false → regular AMO (up to N concurrent)
// isExclusive = true  → LR / SC / AMOCAS (at most 1 in-flight)
class UncacheAtomicWordReq(implicit p: Parameters) extends DCacheBundle {
  val cmd         = UInt(M_SZ.W)
  val addr        = UInt(PAddrBits.W)
  val vaddr       = UInt(VAddrBits.W)
  val data        = UInt(XLEN.W)       // rs2 (write-val for SC/CAS, operand for AMO)
  val cmp_data    = UInt(XLEN.W)       // rd  (compare-val for AMOCAS; unused otherwise)
  val mask        = UInt(DataBytes.W)
  val uop         = new DynInst
  val pdest       = UInt(PhyRegIdxWidth.W)
  val isExclusive = Bool()
}

// ─────────────────────────────────────────────────────────────────────────────
// LazyModule shell
// ─────────────────────────────────────────────────────────────────────────────
class UncacheAtomicBuffer()(implicit p: Parameters)
    extends LazyModule with HasXSParameter with HasMemBlockParameters {
  override def shouldBeInlined: Boolean = false

  val clientParameters = TLMasterPortParameters.v1(
    clients = Seq(TLMasterParameters.v1(
      "uncache_atomic",
      sourceId           = IdRange(0, UncacheAtomicBufferSize),
      supportsArithmetic = TransferSizes(1, 8),
      supportsLogical    = TransferSizes(1, 8),
      supportsGet        = TransferSizes(1, 8),
      supportsPutFull    = TransferSizes(1, 8)
    )),
    requestFields = Seq(MemBackTypeMMField(), MemPageTypeNCField())
  )
  val clientNode = TLClientNode(Seq(clientParameters))
  lazy val module = new UncacheAtomicBufferImp(this)
}

// ─────────────────────────────────────────────────────────────────────────────
// Implementation: N independent slot FSMs
// ─────────────────────────────────────────────────────────────────────────────
class UncacheAtomicBufferImp(outer: UncacheAtomicBuffer)
    extends LazyModuleImp(outer)
    with HasXSParameter
    with HasDCacheParameters
    with HasMemBlockParameters
    with MemoryOpConstants {

  val N = UncacheAtomicBufferSize

  // io.out uses ldaParams.head so it is compatible with the AtomicWBPort MemWriteBack type
  val io = IO(new Bundle {
    val req     = Flipped(Decoupled(new UncacheAtomicWordReq))
    val out     = new MemWriteBack(ldaParams.head)
    // MemBlock drives this LOW for cycles that AtomicsUnit owns the writeback port.
    val wbReady = Input(Bool())
  })

  val (bus, edge) = outer.clientNode.out.head
  bus.b.ready := false.B
  bus.c.valid := false.B; bus.c.bits := DontCare
  bus.e.valid := false.B; bus.e.bits := DontCare

  // ── Per-slot registers ──────────────────────────────────────────────────
  val reqBuf      = Reg(Vec(N, new UncacheAtomicWordReq))
  val respData    = Reg(Vec(N, UInt(XLEN.W)))
  val casReadData = Reg(Vec(N, UInt(XLEN.W))) // AMOCAS: value read in Get phase

  // slot states
  // regular AMO / LR / SC: s_idle → s_req → s_resp → s_writeback
  // AMOCAS:                 s_idle → s_req → s_resp → s_cas_put → s_cas_ack → s_writeback
  val s_idle :: s_req :: s_resp :: s_cas_put :: s_cas_ack :: s_writeback :: Nil = Enum(6)
  val slotState  = RegInit(VecInit(Seq.fill(N)(s_idle)))
  val slotIsExcl = RegInit(VecInit(Seq.fill(N)(false.B)))

  // ── Concurrency control: at most 1 exclusive slot active ────────────────
  val exclusiveActive: Bool =
    (slotIsExcl zip slotState).map { case (excl, st) => excl && (st =/= s_idle) }
                               .reduce(_ || _)

  // ── Slot allocation ─────────────────────────────────────────────────────
  val freeVec  = slotState.map(_ === s_idle)
  val hasFree  = freeVec.reduce(_ || _)
  val freeSlot = PriorityEncoder(VecInit(freeVec))

  val inIsExcl = io.req.bits.isExclusive
  val canAlloc = hasFree && (!inIsExcl || !exclusiveActive)
  io.req.ready := canAlloc

  when (io.req.fire) {
    reqBuf(freeSlot)     := io.req.bits
    slotIsExcl(freeSlot) := inIsExcl
    slotState(freeSlot)  := s_req
  }

  // ── lgSize from mask popcount ───────────────────────────────────────────
  def maskToLgSize(mask: UInt): UInt = {
    MuxCase(0.U, Seq(
      (PopCount(mask) === 2.U) -> 1.U,
      (PopCount(mask) === 4.U) -> 2.U,
      (PopCount(mask) === 8.U) -> 3.U
    ))
  }

  // ── Extract and sign-extend the relevant word from a 64-bit response ────
  def extractWord(raw: UInt, mask: UInt, addr: UInt): UInt = {
    val isW = PopCount(mask) <= 4.U
    Mux(isW,
      SignExt(Mux(addr(2), raw(63, 32), raw(31, 0)), XLEN),
      raw(XLEN - 1, 0))
  }

  // ── TL-A: AMOCAS write phase (s_cas_put) has highest priority ──────────
  val casVec  = slotState.map(_ === s_cas_put)
  val hasCas  = casVec.reduce(_ || _)
  val casSlot = PriorityEncoder(VecInit(casVec))
  val casCur  = reqBuf(casSlot)
  val casLgSz = maskToLgSize(casCur.mask)
  val (_, casPut) = edge.Put(casSlot, casCur.addr, casLgSz, casCur.data, casCur.mask)

  // ── TL-A: initial requests (s_req) ─────────────────────────────────────
  val reqVec  = slotState.map(_ === s_req)
  val hasReq  = reqVec.reduce(_ || _)
  val reqSlot = PriorityEncoder(VecInit(reqVec))
  val reqCur  = reqBuf(reqSlot)
  val reqLgSz = maskToLgSize(reqCur.mask)

  // TL Arithmetic param: MIN=0, MAX=1, MINU=2, MAXU=3, ADD=4
  val arithParam = MuxCase(4.U /* ADD */, Seq(
    (reqCur.cmd === M_XA_MIN)  -> 0.U,
    (reqCur.cmd === M_XA_MAX)  -> 1.U,
    (reqCur.cmd === M_XA_MINU) -> 2.U,
    (reqCur.cmd === M_XA_MAXU) -> 3.U,
    (reqCur.cmd === M_XA_ADD)  -> 4.U
  ))
  // TL Logical param: XOR=0, OR=1, AND=2, SWAP=3
  val logicParam = MuxCase(3.U /* SWAP */, Seq(
    (reqCur.cmd === M_XA_XOR)  -> 0.U,
    (reqCur.cmd === M_XA_OR)   -> 1.U,
    (reqCur.cmd === M_XA_AND)  -> 2.U,
    (reqCur.cmd === M_XA_SWAP) -> 3.U
  ))

  val (_, tlArith) = edge.Arithmetic(reqSlot, reqCur.addr, reqLgSz, reqCur.data, arithParam)
  val (_, tlLogic) = edge.Logical   (reqSlot, reqCur.addr, reqLgSz, reqCur.data, logicParam)
  val (_, tlGet)   = edge.Get(reqSlot, reqCur.addr, reqLgSz)
  val (_, tlScPut) = edge.Put(reqSlot, reqCur.addr, reqLgSz, reqCur.data, reqCur.mask)

  val reqMsg = MuxCase(tlLogic, Seq(
    (reqCur.cmd === M_XLR)        -> tlGet,
    (reqCur.cmd === M_XSC)        -> tlScPut,
    (isAMOCAS(reqCur.cmd))        -> tlGet,    // phase-1: read current value
    (isAMOArithmetic(reqCur.cmd)) -> tlArith
    // else: logical AMO (swap/xor/and/or) → tlLogic (default)
  ))

  // s_cas_put takes priority on the A channel
  bus.a.valid := Mux(hasCas, true.B, hasReq)
  bus.a.bits  := Mux(hasCas, casPut, reqMsg)
  bus.a.bits.user.lift(MemBackTypeMM).foreach(_ := true.B)
  bus.a.bits.user.lift(MemPageTypeNC).foreach(_ := true.B)

  when (bus.a.fire) {
    when (hasCas) {
      slotState(casSlot) := s_cas_ack
    }.otherwise {
      slotState(reqSlot) := s_resp
    }
  }

  // ── TL-D: receive response ──────────────────────────────────────────────
  bus.d.ready := true.B
  when (bus.d.fire) {
    val id        = bus.d.bits.source
    val raw       = bus.d.bits.data
    val extracted = extractWord(raw, reqBuf(id).mask, reqBuf(id).addr)
    val cmd       = reqBuf(id).cmd

    when (slotState(id) === s_resp) {
      when (slotIsExcl(id)) {
        when (cmd === M_XLR) {
          // LR: return loaded value
          respData(id)  := extracted
          slotState(id) := s_writeback
        }.elsewhen (cmd === M_XSC) {
          // SC: L2/HN-F encodes success/fail in data[0] (0=success, 1=fail)
          respData(id)  := raw(0)
          slotState(id) := s_writeback
        }.otherwise {
          // AMOCAS phase-1: check compare
          casReadData(id) := extracted
          when (extracted === reqBuf(id).cmp_data) {
            slotState(id) := s_cas_put    // match → issue write
          }.otherwise {
            respData(id)  := extracted    // no match → return current, skip write
            slotState(id) := s_writeback
          }
        }
      }.otherwise {
        // Regular AMO: L2/HN-F returns old value
        respData(id)  := extracted
        slotState(id) := s_writeback
      }
    }.elsewhen (slotState(id) === s_cas_ack) {
      // AMOCAS phase-2 ACK: RISC-V returns the value read in phase-1
      respData(id)  := casReadData(id)
      slotState(id) := s_writeback
    }
  }

  // ── Writeback ───────────────────────────────────────────────────────────
  val wbVec   = slotState.map(_ === s_writeback)
  val hasWb   = wbVec.reduce(_ || _)
  val wbSlot  = PriorityEncoder(VecInit(wbVec))
  val wbEntry = reqBuf(wbSlot)
  val wbFire  = hasWb && io.wbReady

  io.out.toRob.valid                                    := wbFire
  io.out.toRob.bits                                     := DontCare
  io.out.toRob.bits.robIdx                              := wbEntry.uop.robIdx
  io.out.toRob.bits.exceptionVec.foreach(_ := false.B)
  io.out.toRob.bits.trigger.foreach(_       := TriggerAction.None)
  io.out.toRob.bits.isRVC.foreach(_         := wbEntry.uop.isRVC)
  io.out.toRob.bits.lqIdx.foreach(_         := wbEntry.uop.lqIdx)
  io.out.toRob.bits.debugInfo.isNCIO.foreach(_       := true.B)
  io.out.toRob.bits.debugInfo.isPerfCnt.foreach(_    := DontCare)
  io.out.toRob.bits.debugInfo.isMMIO.foreach(_       := false.B)
  io.out.toRob.bits.debugInfo.paddr.foreach(_        := wbEntry.addr)
  io.out.toRob.bits.debugInfo.vaddr.foreach(_        := wbEntry.vaddr)
  io.out.toRob.bits.debugInfo.debug_seqNum.foreach(_ := wbEntry.uop.debug_seqNum)
  io.out.toRob.bits.debugInfo.perfDebugInfo.foreach(_ := wbEntry.uop.perfDebugInfo)

  io.out.toIntRf.foreach { port =>
    port.valid                        := wbFire
    port.bits.pdest                   := wbEntry.pdest
    port.bits.data                    := respData(wbSlot)
    port.bits.isFromLoadUnit.foreach(_ := false.B)
  }
  io.out.toFpRf.foreach { port =>
    port.valid      := false.B
    port.bits.pdest := wbEntry.pdest
    port.bits.data  := respData(wbSlot)
  }

  when (wbFire) {
    slotState(wbSlot)  := s_idle
    slotIsExcl(wbSlot) := false.B
  }
}
