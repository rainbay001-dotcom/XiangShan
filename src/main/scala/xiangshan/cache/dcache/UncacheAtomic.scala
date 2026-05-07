/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
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

package xiangshan.cache

import org.chipsalliance.cde.config.Parameters
import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{IdRange, LazyModule, LazyModuleImp}
import freechips.rocketchip.tilelink._
import coupledL2.{MemBackTypeMMField, MemPageTypeNC, MemPageTypeNCField}
import xiangshan._
import utils._
import utility._

/**
 * UncacheAtomicBuffer: routes NC (PBMT=01) atomic instructions through TileLink
 * with MemPageTypeNC=1 so that coupledL2 converts them to non-cached CHI atomic
 * transactions for the external HN-F (true cross-node atomic).
 *
 * === Operation → TL mapping ===
 *
 *   LR.W/D      : TL Get (MemPageTypeNC=1)
 *                   → CHI ReadNoSnp  → HN-F returns value; core sets local reservation
 *
 *   SC.W/D      : TL PutFullData (MemPageTypeNC=1)  [only if reservation valid]
 *                   → CHI WriteNoSnpFull  → HN-F writes rs2; core clears reservation
 *                 If no valid reservation: fail immediately (rd=1), no TL transaction.
 *
 *   AMO *.W/D   : TL ArithmeticData (ADD/MIN/MAX/MINU/MAXU) or
 *                 TL LogicalData    (XOR/OR/AND/SWAP)  with MemPageTypeNC=1
 *                   → CHI AtomicLoad or CHI AtomicSwap
 *                   → HN-F atomically performs RMW, returns original value
 *                 Single round-trip; no core-side computation for the AMO result.
 *
 *   AMOCAS.W/D  : TL Get (read) → compare locally →
 *                 on match: TL PutFullData (write swap value) with MemPageTypeNC=1
 *                   → CHI ReadNoSnp + WriteNoSnpFull
 *                 NOTE: Two-phase, not true atomic at HN-F level.
 *                 For true atomic AMOCAS at HN-F, coupledL2 would need to emit
 *                 CHI AtomicCompare (see docs/nc_amo_coupledl2_patch.md).
 *
 *   AMOCAS.Q    : rejected upstream with storeAccessFault (128-bit, not sent here)
 *
 * === coupledL2 requirement for AMO true atomicity ===
 *   coupledL2's TL2CHI bridge must convert:
 *     TL ArithmeticData + MemPageTypeNC=1  →  CHI AtomicLoad  (EWA=0, non-cached)
 *     TL LogicalData    + MemPageTypeNC=1  →  CHI AtomicLoad (XOR/OR/AND) or
 *                                              CHI AtomicSwap (SWAP)
 *   Without this coupledL2 change, AMO requests will produce incorrect CHI opcodes
 *   and the HN-F will not perform the atomic operation.
 *   See docs/nc_amo_coupledl2_patch.md for the exact changes needed in coupledL2.
 */
class UncacheAtomicBuffer()(implicit p: Parameters) extends LazyModule with HasXSParameter {
  override def shouldBeInlined: Boolean = false

  val clientParameters = TLMasterPortParameters.v1(
    clients = Seq(TLMasterParameters.v1(
      name     = "uncache_amo",
      sourceId = IdRange(0, 1)
    )),
    requestFields = Seq(MemBackTypeMMField(), MemPageTypeNCField())
  )
  val clientNode = TLClientNode(Seq(clientParameters))

  lazy val module = new UncacheAtomicBufferImp(this)
}

class UncacheAtomicBufferImp(outer: UncacheAtomicBuffer)
    extends LazyModuleImp(outer)
    with HasXSParameter
    with HasDCacheParameters
    with MemoryOpConstants
{
  val io = IO(Flipped(new UncacheAtomicWordIO))
  val (bus, edge) = outer.clientNode.out.head

  bus.b.ready := false.B
  bus.c.valid := false.B; bus.c.bits := DontCare
  bus.e.valid := false.B; bus.e.bits := DontCare

  //--------------------------------------------------------------------------
  // FSM
  //   s_idle    : accept request from AtomicsUnit
  //   s_req     : send first TL-A beat
  //   s_resp    : wait for first TL-D  (Get/AMO → AccessAckData; Put → AccessAck)
  //   s_cas_put : send second TL-A PutFull for AMOCAS compare-match write
  //   s_cas_ack : wait for second TL-D AccessAck (AMOCAS write)
  //--------------------------------------------------------------------------
  val s_idle :: s_req :: s_resp :: s_cas_put :: s_cas_ack :: Nil = Enum(5)
  val state  = RegInit(s_idle)
  val req    = RegEnable(io.req.bits, io.req.fire)
  // Original value read from memory (returned to AtomicsUnit as rd result)
  val loaded = Reg(UInt(XLEN.W))

  io.req.ready := state === s_idle

  //--------------------------------------------------------------------------
  // Identify operation type (combinational on registered `req`)
  //--------------------------------------------------------------------------
  val isLr     = req.cmd === M_XLR
  val isSc     = req.cmd === M_XSC
  val isCASW   = req.cmd === M_XA_CASW
  val isCASd   = req.cmd === M_XA_CASD
  val isAMOCAS = isCASW || isCASd
  // Regular AMO: not LR, not SC, not AMOCAS — use TL ArithmeticData/LogicalData
  val isRegAMO = !isLr && !isSc && !isAMOCAS

  // 32-bit (W) vs 64-bit (D) determined by byte mask
  val is_word = req.mask =/= "hff".U

  // TL transaction size
  val lgSize = Mux(is_word, 2.U(4.W), 3.U(4.W))

  //--------------------------------------------------------------------------
  // TL opcode / param for regular AMOs
  //   TL ArithmeticData (opcode=2): ADD, MIN, MAX, MINU, MAXU
  //   TL LogicalData    (opcode=3): XOR, OR, AND, SWAP
  //--------------------------------------------------------------------------
  val isArith = req.cmd === M_XA_ADD || req.cmd === M_XA_MIN ||
                req.cmd === M_XA_MAX || req.cmd === M_XA_MINU || req.cmd === M_XA_MAXU

  val amo_opcode = Mux(isArith, TLMessages.ArithmeticData, TLMessages.LogicalData)

  val amo_param = MuxLookup(req.cmd, TLAtomics.ADD)(Seq(
    M_XA_ADD  -> TLAtomics.ADD,
    M_XA_MIN  -> TLAtomics.MIN,
    M_XA_MAX  -> TLAtomics.MAX,
    M_XA_MINU -> TLAtomics.MINU,
    M_XA_MAXU -> TLAtomics.MAXU,
    M_XA_XOR  -> TLAtomics.XOR,
    M_XA_OR   -> TLAtomics.OR,
    M_XA_AND  -> TLAtomics.AND,
    M_XA_SWAP -> TLAtomics.SWAP
  ))

  //--------------------------------------------------------------------------
  // AMOCAS compare (evaluated in s_resp — bus.d.bits.data not yet in `loaded`)
  //--------------------------------------------------------------------------
  val resp_word   = Mux(req.addr(2), bus.d.bits.data(63, 32), bus.d.bits.data(31, 0))
  val cas_match_w = resp_word === req.cmp_data(31, 0)
  val cas_match_d = bus.d.bits.data(63, 0) === req.cmp_data(63, 0)
  val cas_match   = Mux(isCASd, cas_match_d, cas_match_w)

  //--------------------------------------------------------------------------
  // FSM transitions
  //--------------------------------------------------------------------------
  when (state === s_idle    && io.req.fire) { state := s_req     }
  when (state === s_req     && bus.a.fire)  { state := s_resp    }
  when (state === s_cas_put && bus.a.fire)  { state := s_cas_ack }

  when (state === s_resp && bus.d.valid) {
    loaded := bus.d.bits.data
    when (isRegAMO) {
      // Single round-trip: HN-F atomically performed RMW, AccessAckData has original value
      state := s_idle
    }.elsewhen (isAMOCAS) {
      state := Mux(cas_match, s_cas_put, s_idle)
    }.otherwise {
      // LR or SC: done
      state := s_idle
    }
  }

  when (state === s_cas_ack && bus.d.valid) { state := s_idle }

  //--------------------------------------------------------------------------
  // TL-A channel: manually construct beat to avoid diplomacy supportsXxx checks
  // (edge.Arithmetic/Logical would throw "No managers support arithmetic AMOs")
  //--------------------------------------------------------------------------
  val a_bits = Wire(chiselTypeOf(bus.a.bits))
  a_bits         := DontCare
  a_bits.source  := 0.U
  a_bits.address := req.addr
  a_bits.corrupt := false.B
  a_bits.size    := lgSize
  a_bits.mask    := req.mask

  when (state === s_req) {
    when (isSc) {
      // SC: write rs2 to memory
      a_bits.opcode := TLMessages.PutFullData
      a_bits.param  := 0.U
      a_bits.data   := req.data
    }.elsewhen (isRegAMO) {
      // Regular AMO: TL ArithmeticData or LogicalData
      // coupledL2 converts to CHI AtomicLoad/AtomicSwap (HN-F does atomic RMW)
      a_bits.opcode := amo_opcode
      a_bits.param  := amo_param
      a_bits.data   := req.data   // rs2 operand (already byte-lane-replicated for W ops)
    }.otherwise {
      // LR or AMOCAS phase-1: read current value
      a_bits.opcode := TLMessages.Get
      a_bits.param  := 0.U
      a_bits.data   := 0.U
    }
  }.otherwise {
    // s_cas_put: AMOCAS write phase
    a_bits.opcode := TLMessages.PutFullData
    a_bits.param  := 0.U
    a_bits.data   := req.data   // swap value (already byte-lane-replicated for W ops)
  }

  bus.a.valid := (state === s_req) || (state === s_cas_put)
  bus.a.bits  := a_bits
  // Mark as non-cached: coupledL2 routes to CHI non-cached path (EWA=0)
  bus.a.bits.user.lift(MemPageTypeNC).foreach(_ := true.B)

  //--------------------------------------------------------------------------
  // TL-D channel
  //--------------------------------------------------------------------------
  bus.d.ready := (state === s_resp) || (state === s_cas_ack)

  //--------------------------------------------------------------------------
  // Response to AtomicsUnit
  //
  //   LR             : TL-D AccessAckData → data = read value
  //   SC             : TL-D AccessAck (no data) → AtomicsUnit drives rd=0 (success)
  //   Regular AMO    : TL-D AccessAckData → data = original memory value (pre-RMW)
  //   AMOCAS match   : TL-D AccessAck (write done) → data = `loaded` (Get response)
  //   AMOCAS no-match: TL-D AccessAckData (Get done, no write) → data = read value
  //--------------------------------------------------------------------------
  io.resp.valid :=
    (state === s_resp    && bus.d.valid && (isLr || isSc || isRegAMO)) || // LR/SC/AMO
    (state === s_resp    && bus.d.valid && isAMOCAS && !cas_match)      || // AMOCAS no-match
    (state === s_cas_ack && bus.d.valid)                                   // AMOCAS match write

  // For AMO/LR: return the data from TL-D (original memory value)
  // For AMOCAS match: return the `loaded` register (value read by the Get)
  io.resp.bits.data  := Mux(state === s_cas_ack, loaded, bus.d.bits.data)
  io.resp.bits.nderr := bus.d.bits.denied
  io.resp.bits.derr  := bus.d.bits.corrupt && !bus.d.bits.denied
}
