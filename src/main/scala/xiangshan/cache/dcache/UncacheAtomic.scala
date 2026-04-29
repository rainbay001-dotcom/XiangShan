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
 * with MemPageTypeNC=1 so that coupledL2 converts them to non-cached CHI transactions
 * for the external HN-F.
 *
 * All AMO operations use a two-phase Get + PutFull protocol (same as AMOCAS) because
 * coupledL2 in this version does not support converting TL LogicalData/ArithmeticData
 * with MemPageTypeNC=1 to CHI AtomicLoad/AtomicSwap.  The AMO computation (ADD, XOR,
 * SWAP, MIN, MAX, ...) is performed in the core between the Get response and the Put.
 *
 * Operations:
 *   LR.W/D      : TL Get → return loaded value, set reservation
 *   SC.W/D      : reservation check; if hit → TL PutFull (write rs2), return rd=0
 *   AMO *.W/D   : TL Get (read) → compute new_val → TL PutFull (write new_val), return loaded
 *   AMOCAS.W/D  : TL Get (read) → compare → if match: TL PutFull (write swap), return loaded
 *   AMOCAS.Q    : rejected upstream with storeAccessFault (128-bit, not sent here)
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
  //   s_idle  : accept request
  //   s_req   : send first TL-A (Get for LR/AMO; PutFull for SC)
  //   s_resp  : wait for first TL-D (AccessAckData for Get; AccessAck for Put)
  //   s_put   : send second TL-A PutFull (AMO / AMOCAS-match write phase)
  //   s_ack   : wait for second TL-D AccessAck (AMO write)
  //--------------------------------------------------------------------------
  val s_idle :: s_req :: s_resp :: s_put :: s_ack :: Nil = Enum(5)
  val state  = RegInit(s_idle)
  val req    = RegEnable(io.req.bits, io.req.fire)
  // Original value read from memory (LR result / AMO return value)
  val loaded = Reg(UInt(XLEN.W))

  io.req.ready := state === s_idle

  //--------------------------------------------------------------------------
  // Identify operation type (uses registered req — stable from s_req onward)
  //--------------------------------------------------------------------------
  val isLr     = req.cmd === M_XLR
  val isSc     = req.cmd === M_XSC
  val isCASW   = req.cmd === M_XA_CASW
  val isCASd   = req.cmd === M_XA_CASD
  val isAMOCAS = isCASW || isCASd
  // All non-LR/SC operations use two-phase Get+Put (including AMOCAS)
  val isAMO    = !isLr && !isSc

  // W (32-bit) vs D (64-bit) — determined from byte mask
  val is_word = req.mask =/= 0xff.U

  // lgSize for TL transactions
  val lgSize = Mux(is_word, 2.U(4.W), 3.U(4.W))

  //--------------------------------------------------------------------------
  // CAS compare (against bus.d.bits.data, evaluated in s_resp cycle)
  // Use bus.d.bits.data (not `loaded`) because `loaded` is updated in the same cycle
  //--------------------------------------------------------------------------
  val resp_word   = Mux(req.addr(2), bus.d.bits.data(63, 32), bus.d.bits.data(31, 0))
  val cas_match_w = resp_word === req.cmp_data(31, 0)
  val cas_match_d = bus.d.bits.data(63, 0) === req.cmp_data(63, 0)
  val cas_match   = Mux(isCASd, cas_match_d, cas_match_w)

  //--------------------------------------------------------------------------
  // AMO new-value computation (combinational, uses registered `loaded` and `req`)
  // Evaluated in s_put cycle — `loaded` holds the Get response data by then.
  //--------------------------------------------------------------------------
  // Extract the 32-bit word from `loaded` based on address alignment
  val loaded_word = Mux(req.addr(2), loaded(63, 32), loaded(31, 0))
  val rs2_word    = req.data(31, 0)   // rs2 value (same in both 32-bit halves)
  val rs2_d       = req.data(63, 0)   // rs2 for doubleword

  // 32-bit AMO result
  val amo_word = Wire(UInt(32.W))
  amo_word := MuxLookup(req.cmd, rs2_word)(Seq(
    M_XA_ADD  -> (loaded_word +& rs2_word)(31, 0),
    M_XA_XOR  -> (loaded_word ^ rs2_word),
    M_XA_OR   -> (loaded_word | rs2_word),
    M_XA_AND  -> (loaded_word & rs2_word),
    M_XA_SWAP -> rs2_word,
    M_XA_MIN  -> Mux(loaded_word.asSInt < rs2_word.asSInt, loaded_word, rs2_word),
    M_XA_MAX  -> Mux(loaded_word.asSInt > rs2_word.asSInt, loaded_word, rs2_word),
    M_XA_MINU -> Mux(loaded_word < rs2_word, loaded_word, rs2_word),
    M_XA_MAXU -> Mux(loaded_word > rs2_word, loaded_word, rs2_word),
    // AMOCAS.W: write the swap value (req.data[31:0]) if compare matched
    M_XA_CASW -> req.data(31, 0)
  ))

  // 64-bit AMO result
  val amo_d = Wire(UInt(64.W))
  amo_d := MuxLookup(req.cmd, rs2_d)(Seq(
    M_XA_ADD  -> (loaded +& rs2_d)(63, 0),
    M_XA_XOR  -> (loaded ^ rs2_d),
    M_XA_OR   -> (loaded | rs2_d),
    M_XA_AND  -> (loaded & rs2_d),
    M_XA_SWAP -> rs2_d,
    M_XA_MIN  -> Mux(loaded.asSInt < rs2_d.asSInt, loaded, rs2_d),
    M_XA_MAX  -> Mux(loaded.asSInt > rs2_d.asSInt, loaded, rs2_d),
    M_XA_MINU -> Mux(loaded < rs2_d, loaded, rs2_d),
    M_XA_MAXU -> Mux(loaded > rs2_d, loaded, rs2_d),
    // AMOCAS.D: write the swap value (req.data[63:0]) if compare matched
    M_XA_CASD -> req.data(63, 0)
  ))

  // Data to write in s_put (W: result replicated in both 32-bit lanes, masked by req.mask)
  val put_data = Mux(is_word, Cat(amo_word, amo_word), amo_d)

  //--------------------------------------------------------------------------
  // FSM transitions
  //--------------------------------------------------------------------------
  when (state === s_idle && io.req.fire) { state := s_req }
  when (state === s_req  && bus.a.fire)  { state := s_resp }
  when (state === s_put  && bus.a.fire)  { state := s_ack }

  when (state === s_resp && bus.d.valid) {
    loaded := bus.d.bits.data
    when (isLr || isSc) {
      // LR: return loaded; SC: return ACK (no data needed)
      state := s_idle
    }.elsewhen (isAMOCAS) {
      // AMOCAS: write only if compare matches
      state := Mux(cas_match, s_put, s_idle)
    }.otherwise {
      // Regular AMO: always write the computed new value
      state := s_put
    }
  }

  when (state === s_ack && bus.d.valid) { state := s_idle }

  //--------------------------------------------------------------------------
  // TL-A channel: build beat manually to avoid diplomacy supportsXxx assertions
  //--------------------------------------------------------------------------
  val a_bits = Wire(chiselTypeOf(bus.a.bits))
  a_bits         := DontCare
  a_bits.source  := 0.U
  a_bits.address := req.addr
  a_bits.corrupt := false.B

  when (state === s_req) {
    // LR / AMO phase-1: Get (read current value)
    // SC: PutFull (write rs2 value to memory)
    a_bits.opcode := Mux(isSc, TLMessages.PutFullData, TLMessages.Get)
    a_bits.param  := 0.U
    a_bits.size   := lgSize
    // req.mask encodes the active byte lanes (genWmaskAMO); use it for both Get and Put
    a_bits.mask   := req.mask
    a_bits.data   := req.data   // only used for SC
  }.otherwise {
    // s_put: AMO / AMOCAS write phase — PutFull with computed new value
    a_bits.opcode := TLMessages.PutFullData
    a_bits.param  := 0.U
    a_bits.size   := lgSize
    a_bits.mask   := req.mask
    a_bits.data   := put_data
  }

  bus.a.valid := (state === s_req) || (state === s_put)
  bus.a.bits  := a_bits
  // Stamp NC flag so coupledL2 routes these as non-cached CHI transactions
  bus.a.bits.user.lift(MemPageTypeNC).foreach(_ := true.B)

  //--------------------------------------------------------------------------
  // TL-D channel
  //--------------------------------------------------------------------------
  bus.d.ready := (state === s_resp) || (state === s_ack)

  //--------------------------------------------------------------------------
  // Response to AtomicsUnit
  //
  //   LR         : fire when Get TL-D arrives (s_resp)  → data = loaded
  //   SC         : fire when Put TL-D ACK arrives (s_resp, no data)  → AtomicsUnit uses 0
  //   AMO        : fire when Put TL-D ACK arrives (s_ack)  → data = loaded (original value)
  //   AMOCAS hit : fire when Put TL-D ACK arrives (s_ack)  → data = loaded
  //   AMOCAS miss: fire when Get TL-D arrives (s_resp, cas_match=false)  → data = loaded
  //--------------------------------------------------------------------------
  io.resp.valid :=
    (state === s_resp && bus.d.valid && (isLr || isSc)) ||            // LR / SC
    (state === s_resp && bus.d.valid && isAMOCAS && !cas_match) ||    // AMOCAS no-match
    (state === s_ack  && bus.d.valid)                                 // AMO / AMOCAS-match

  // Provide the original memory value for AMO return; SC result is overridden in AtomicsUnit
  io.resp.bits.data  := Mux(state === s_ack, loaded,
                         Mux(state === s_resp, bus.d.bits.data, 0.U))
  io.resp.bits.nderr := bus.d.bits.denied
  io.resp.bits.derr  := bus.d.bits.corrupt && !bus.d.bits.denied
}
