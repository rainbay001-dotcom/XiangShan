// ============================================================
// coupledL2/src/main/scala/coupledL2/tl2chi/MMIOBridge.scala
// FIXED VERSION — apply all patches from issue-6 discussion
//
// Fixes applied on top of the issue-4 base (user's local version):
//   Fix 1 (CRITICAL): Allocation uses stale `req` for isNCAMO → use io.req.bits
//   Fix 2           : Remove dead first io.resp.valid assignment and unused isWrite
//   Fix 3           : amoand → CHI CLR semantics require data inversion (~req.data)
//   Fix 4           : NC LR/SC use CHI Exclusive Monitor (ReadNoSnp/WriteNoSnpFull + Excl=1)
// ============================================================

package coupledL2.tl2chi

import chisel3._
import chisel3.util._
import utility._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.tilelink.TLMessages._
import coupledL2.HasCoupledL2Parameters
import coupledL2.{MemBackTypeMM, MemPageTypeNC}

class MMIOBridge()(implicit p: Parameters) extends LazyModule
  with HasCoupledL2Parameters
  with HasCHIMsgParameters {

  override def shouldBeInlined: Boolean = false

  val beuRange = AddressSet(0x38010000, 4096 - 1)
  val clintRange = AddressSet(0x38000000L, 0xFFFF)
  val peripheralRange = if (!EnablePrivateClint) {
    AddressSet(0x0, 0xffffffffffffL).subtract(beuRange)
  } else {
    AddressSet(0x0, 0xffffffffffffL).subtract(beuRange).flatMap(_.subtract(clintRange))
  }

  val mmioNode = TLManagerNode(Seq(TLSlavePortParameters.v1(
    managers = Seq(TLSlaveParameters.v1(
      address = peripheralRange,
      regionType = RegionType.UNCACHED,
      supportsGet = TransferSizes(1, 8),
      supportsPutFull = TransferSizes(1, 8),
      supportsPutPartial = TransferSizes(1, 8),
      supportsArithmetic = TransferSizes(1, 8),
      supportsLogical = TransferSizes(1, 8),
      fifoId = None
    )),
    beatBytes = 8,
    requestKeys = Seq(MemBackTypeMM, MemPageTypeNC)
  )))

  lazy val module = new MMIOBridgeImp(this)
}

class MMIOBridgeEntry(edge: TLEdgeIn)(implicit p: Parameters) extends TL2CHIL2Module with HasCHIOpcodes {
  val needRR = true
  val bufferableNC = true

  require(!bufferableNC || needRR, "DO NOT set 'bufferableNC = true' when 'needRR = false'")

  val io = IO(new Bundle() {
    val req = Flipped(DecoupledIO(new TLBundleA(edge.bundle)))
    val resp = DecoupledIO(new TLBundleD(edge.bundle))
    val chi = new DecoupledNoSnpPortIO
    val id = Input(UInt())
    val pCrd = new PCrdQueryBundle
    val waitOnReadReceipt = Option.when(needRR)(Output(Bool()))
  })

  val s_txreq    = RegInit(true.B)
  val s_ncbwrdata = RegInit(true.B)
  val s_resp     = RegInit(true.B)
  val w_comp     = RegInit(true.B)
  val w_dbidresp = RegInit(true.B)
  val w_compdata = RegInit(true.B)
  val w_pcrdgrant = RegInit(true.B)
  val w_readreceipt = Option.when(needRR)(RegInit(true.B))

  val no_schedule = s_txreq && s_ncbwrdata && s_resp
  val no_wait = w_comp && w_dbidresp && w_compdata && w_pcrdgrant && w_readreceipt.getOrElse(true.B)

  val req = RegEnable(io.req.bits, io.req.fire)
  val req_valid = !no_schedule || !no_wait
  val rdata = Reg(UInt(DATA_WIDTH.W))
  val srcID = Reg(UInt(SRCID_WIDTH.W))
  val dbID = Reg(UInt(DBID_WIDTH.W))
  val allowRetry = RegInit(true.B)
  val pCrdType = Reg(UInt(PCRDTYPE_WIDTH.W))
  val denied = Reg(Bool())
  val corrupt = Reg(Bool())
  val traceTag = Reg(Bool())
  // SC Exclusive Monitor result (true = ExOkay → SC success; false = OK → SC fail)
  val sc_exokay = Reg(Bool())

  val isRead = req.opcode === Get
  val isBackTypeMM = req.user.lift(MemBackTypeMM).getOrElse(false.B)
  val isPageTypeNC = req.user.lift(MemPageTypeNC).getOrElse(false.B)

  // NC AMO detection
  val isArithmetic = req.opcode === ArithmeticData
  val isLogical    = req.opcode === LogicalData
  val isNCAMO      = (isArithmetic || isLogical) && isPageTypeNC

  // NC LR/SC detection
  // LR: TL Get + MemPageTypeNC=1
  // SC: TL PutFullData + MemPageTypeNC=1
  val isNCLR = req.opcode === Get && isPageTypeNC
  val isNCSC = (req.opcode === PutFullData || req.opcode === PutPartialData) && isPageTypeNC

  // TileLink param -> CHI atomic opcode (return-old-value variants)
  val amoOpcode = MuxCase(ReadNoSnp, Seq(
    (req.opcode === LogicalData    && req.param === TLAtomics.SWAP)  -> AtomicSwap,
    (req.opcode === LogicalData    && req.param === TLAtomics.XOR)   -> AtomicLoad_EOR,
    (req.opcode === LogicalData    && req.param === TLAtomics.OR)    -> AtomicLoad_SET,
    (req.opcode === LogicalData    && req.param === TLAtomics.AND)   -> AtomicLoad_CLR,
    (req.opcode === ArithmeticData && req.param === TLAtomics.ADD)   -> AtomicLoad_ADD,
    (req.opcode === ArithmeticData && req.param === TLAtomics.MIN)   -> AtomicLoad_SMIN,
    (req.opcode === ArithmeticData && req.param === TLAtomics.MAX)   -> AtomicLoad_SMAX,
    (req.opcode === ArithmeticData && req.param === TLAtomics.MINU)  -> AtomicLoad_UMIN,
    (req.opcode === ArithmeticData && req.param === TLAtomics.MAXU)  -> AtomicLoad_UMAX
  ))

  require(io.req.bits.data.getWidth == wordBits)
  val wordBytes = wordBits / 8
  val words = DATA_WIDTH / wordBits
  val wordIdxBits = log2Ceil(words)
  require(wordBits == 64)
  require(wordIdxBits == 2)
  val reqWordIdx = (req.address >> log2Ceil(wordBytes))(wordIdxBits - 1, 0)

  val txreq = io.chi.tx.req
  val txdat = io.chi.tx.dat
  val rxdat = io.chi.rx.dat
  val rxrsp = io.chi.rx.rsp

  // =========================================================================
  // Entry allocation
  // FIX 1 (CRITICAL): Use io.req.bits (current-cycle Wire) NOT req (RegEnable,
  // updated at end of fire cycle). Using req here caused isNCAMO to evaluate
  // stale data from the previous transaction, leaving w_compdata=true and
  // causing the response to fire immediately with uninitialized rdata.
  // =========================================================================
  when (io.req.fire) {
    s_txreq     := false.B
    s_resp      := false.B
    allowRetry  := true.B
    denied      := false.B
    corrupt     := false.B
    traceTag    := false.B
    sc_exokay   := false.B

    // Compute isNCAMO from io.req.bits (current input, not the registered req)
    val curIsNCAMO = (io.req.bits.opcode === ArithmeticData ||
                      io.req.bits.opcode === LogicalData) &&
                     io.req.bits.user.lift(MemPageTypeNC).getOrElse(false.B)
    val curIsNCLR  = io.req.bits.opcode === Get &&
                     io.req.bits.user.lift(MemPageTypeNC).getOrElse(false.B)
    val curIsNCSC  = (io.req.bits.opcode === PutFullData ||
                      io.req.bits.opcode === PutPartialData) &&
                     io.req.bits.user.lift(MemPageTypeNC).getOrElse(false.B)

    when (io.req.bits.opcode === Get && !curIsNCLR) {
      // Plain MMIO read (non-NC)
      w_compdata := false.B
      w_readreceipt.foreach(_ := false.B)
    }.elsewhen (curIsNCLR) {
      // NC LR: ReadNoSnp + Excl=1 → wait for CompData + ReadReceipt
      w_compdata := false.B
      w_readreceipt.foreach(_ := false.B)
    }.elsewhen (io.req.bits.opcode === PutFullData ||
                io.req.bits.opcode === PutPartialData) {
      when (!curIsNCSC) {
        // Plain MMIO write
        w_comp     := false.B
        w_dbidresp := false.B
        s_ncbwrdata := false.B
      }.otherwise {
        // NC SC: WriteNoSnpFull + Excl=1 → wait for Comp(ExOkay/OK) + DBIDResp
        w_comp      := false.B
        w_dbidresp  := false.B
        s_ncbwrdata := false.B
      }
    }.elsewhen (curIsNCAMO) {
      // NC AMO: AtomicLoad/AtomicSwap → wait for CompData + DBIDResp (for NCBWrData)
      // Note: NC AMO does NOT wait for ReadReceipt (Order=None for atomics)
      w_compdata  := false.B
      w_dbidresp  := false.B
      s_ncbwrdata := false.B
    }
  }

  // =========================================================================
  // State flags recover
  // =========================================================================
  when (txreq.fire) { s_txreq := true.B }

  when (rxdat.fire) {
    w_compdata := true.B
    rdata := rxdat.bits.data
    val nderr = rxdat.bits.respErr === RespErrEncodings.NDERR
    val derr  = rxdat.bits.respErr === RespErrEncodings.DERR
    val dataCheck = if (enableDataCheck) {
      dataCheckMethod match {
        case 1 => (0 until DATACHECK_WIDTH).map(i =>
          rxdat.bits.dataCheck.get(i) ^ rxdat.bits.data(8 * (i + 1) - 1, 8 * i).xorR ^ true.B).reduce(_ | _)
        case 2 =>
          val code = new SECDEDCode
          (0 until DATACHECK_WIDTH).map(i =>
            code.decode(Cat(rxdat.bits.dataCheck.get(i) ^ rxdat.bits.data(8 * (i + 1) - 1, 8 * i))).error).reduce(_ | _)
        case _ => false.B
      }
    } else { false.B }
    val poison = rxdat.bits.poison.getOrElse(false.B).orR
    assert(!dataCheck, "UC should not have DataCheck error")
    denied  := denied  || nderr
    corrupt := corrupt || derr || nderr || dataCheck || poison
  }

  when (io.resp.fire) { s_resp := true.B }

  when (rxrsp.fire) {
    when (rxrsp.bits.opcode === CompDBIDResp || rxrsp.bits.opcode === Comp) {
      w_comp := true.B
      // FIX 4: capture ExOkay for NC SC exclusive response
      sc_exokay := rxrsp.bits.respErr === RespErrEncodings.ExOkay
    }
    when (rxrsp.bits.opcode === CompDBIDResp || rxrsp.bits.opcode === DBIDResp ||
          afterIssueEbOrElse(rxrsp.bits.opcode === DBIDRespOrd, false.B)) {
      w_dbidresp := true.B
      srcID      := rxrsp.bits.srcID
      dbID       := rxrsp.bits.dbID
      traceTag   := rxrsp.bits.traceTag
    }
    when (rxrsp.bits.opcode === CompDBIDResp || rxrsp.bits.opcode === Comp) {
      denied := denied || rxrsp.bits.respErr === RespErrEncodings.NDERR ||
                           rxrsp.bits.respErr === RespErrEncodings.DERR
    }
    when (rxrsp.bits.opcode === RetryAck) {
      s_txreq     := false.B
      w_pcrdgrant := false.B
      allowRetry  := false.B
      pCrdType    := rxrsp.bits.pCrdType
      srcID       := rxrsp.bits.srcID
    }
    when (rxrsp.bits.opcode === ReadReceipt) {
      w_readreceipt.foreach(_ := true.B)
    }
  }

  when (txdat.fire) { s_ncbwrdata := true.B }
  when (io.pCrd.grant) { w_pcrdgrant := true.B }

  // =========================================================================
  // IO Assignment
  // =========================================================================
  io.req.ready := no_schedule && no_wait

  txreq.valid := !s_txreq && w_pcrdgrant
  txreq.bits := 0.U.asTypeOf(txreq.bits.cloneType)
  txreq.bits.qos     := Fill(QOS_WIDTH, 1.U(1.W)) - 1.U
  txreq.bits.tgtID   := SAM(sam).lookup(txreq.bits.addr)
  txreq.bits.txnID   := io.id
  txreq.bits.opcode  := ParallelLookUp(req.opcode, Seq(
    Get             -> Mux(isNCLR, ReadNoSnp, ReadNoSnp),  // NC LR uses Excl=1 below
    PutFullData     -> WriteNoSnpPtl,
    PutPartialData  -> WriteNoSnpPtl,
    ArithmeticData  -> amoOpcode,
    LogicalData     -> amoOpcode
  ))
  txreq.bits.size    := req.size
  txreq.bits.addr    := req.address
  txreq.bits.ns      := enableNS.B
  txreq.bits.allowRetry := allowRetry
  txreq.bits.pCrdType   := Mux(allowRetry, 0.U, pCrdType)
  txreq.bits.expCompAck := false.B

  // FIX 4: Set Excl=1 for NC LR and NC SC requests
  txreq.bits.excl := isNCLR || isNCSC

  txreq.bits.order := {
    if (needRR)
      Mux(isNCAMO,
        OrderEncodings.None,  // NC AMO: no ordering needed (SW fence handles it)
        Mux(!isBackTypeMM, OrderEncodings.EndpointOrder, OrderEncodings.RequestOrder))
    else
      OrderEncodings.None
  }
  txreq.bits.memAttr := MemAttr(
    allocate  = false.B,
    cacheable = false.B,
    device    = !isBackTypeMM,
    ewa       = if (bufferableNC) (isPageTypeNC || isBackTypeMM) else false.B
  )
  txreq.bits.stashNIDValid := false.B
  txreq.bits.snoopMe      := false.B
  txreq.bits.mpam.foreach(_ := MPAM(txreq.bits.ns))

  // FIX 2: Remove duplicate io.resp.valid assignment (keep only the correct one below)
  // FIX 4: NC SC response: encode SC success/fail in data bit[0]
  //         0 = ExOkay (SC success), 1 = OK (SC fail — matches rd=1 convention)
  io.resp.valid := !s_resp && Mux(isRead || isNCAMO, w_compdata,
                                  Mux(isNCSC, w_comp && w_dbidresp && s_ncbwrdata,
                                              w_comp && w_dbidresp && s_ncbwrdata))

  io.resp.bits.opcode := Mux(isRead || isNCAMO || isNCLR, AccessAckData, AccessAck)
  io.resp.bits.param  := 0.U
  io.resp.bits.size   := req.size
  io.resp.bits.source := req.source
  io.resp.bits.sink   := 0.U
  io.resp.bits.denied := denied
  io.resp.bits.corrupt := (isRead || isNCAMO || isNCLR) && corrupt

  // FIX 4: For NC SC, encode exclusive result in bit[0] of the response data
  //         AtomicsUnit s_nc_resp reads resp.bits.data(0): 0=success, 1=fail
  //         (matches RISC-V SC convention: rd=0 on success, rd=1 on fail)
  val sc_fail_bit = !sc_exokay  // true (1) = SC fail
  io.resp.bits.data := Mux(isNCSC,
    sc_fail_bit.asUInt,  // bit[0]: 0=success, 1=fail
    ParallelLookUp(reqWordIdx,
      List.tabulate(words)(i => i.U -> rdata((i + 1) * wordBits - 1, i * wordBits)))
  )

  // FIX 3: amoand → CHI CLR semantics: CLR does mem &= ~src, so invert rs2 data
  //   RISC-V amoand: mem &= rs2          (want mem & rs2)
  //   CHI CLR:       mem &= ~src         (does mem & ~src)
  //   → send src = ~rs2 so HN-F computes mem & ~(~rs2) = mem & rs2 ✓
  val isAndOp = req.opcode === LogicalData && req.param === TLAtomics.AND
  val effectiveData = Mux(isAndOp, ~req.data, req.data)

  txdat.valid := !s_ncbwrdata && w_dbidresp
  txdat.bits  := 0.U.asTypeOf(txdat.bits.cloneType)
  txdat.bits.tgtID  := srcID
  txdat.bits.txnID  := dbID
  txdat.bits.opcode := NonCopyBackWrData
  txdat.bits.ccID   := req.address(log2Ceil(beatBytes), log2Ceil(beatBytes) - CCID_WIDTH + 1)
  txdat.bits.dataID := Cat(req.address(log2Ceil(beatBytes)), 0.U(1.W))
  txdat.bits.be := ParallelLookUp(reqWordIdx,
    List.tabulate(words)(i => i.U -> (ZeroExt(req.mask, BE_WIDTH) << (i * wordBytes))))
  // FIX 3: Use effectiveData (inverted for AND/CLR)
  txdat.bits.data := Fill(words, effectiveData) & FillInterleaved(8, txdat.bits.be)
  txdat.bits.traceTag := traceTag

  val txdata = txdat.bits.data
  val dataCheck = if (enableDataCheck) {
    dataCheckMethod match {
      case 1 => VecInit((0 until DATACHECK_WIDTH).map(i =>
        txdata(8 * (i + 1) - 1, 8 * i).xorR ^ true.B)).asUInt
      case 2 =>
        val code = new SECDEDCode
        VecInit((0 until DATACHECK_WIDTH).map(i =>
          code.encode(txdata(8 * (i + 1) - 1, 8 * i)))).asUInt
      case _ => 0.U(DATACHECK_WIDTH.W)
    }
  } else { DontCare }
  txdat.bits.respErr := Mux(req.corrupt, RespErrEncodings.DERR, RespErrEncodings.OK)
  txdat.bits.dataCheck match { case Some(x) => x := dataCheck; case None => }
  txdat.bits.poison  match { case Some(x) => x := Fill(POISON_WIDTH, req.corrupt); case None => }

  rxrsp.ready := (!w_comp || !w_dbidresp || !w_readreceipt.getOrElse(true.B)) && s_txreq
  rxdat.ready := !w_compdata && s_txreq

  io.pCrd.query.valid         := !w_pcrdgrant
  io.pCrd.query.bits.pCrdType := pCrdType
  io.pCrd.query.bits.srcID    := srcID

  io.waitOnReadReceipt.foreach(_ := !w_readreceipt.get && s_txreq)

  XSPerfAccumulate("mmio_get",   io.req.fire && io.req.bits.opcode === Get && !isNCLR)
  XSPerfAccumulate("mmio_put",   io.req.fire && (io.req.bits.opcode === PutFullData || io.req.bits.opcode === PutPartialData) && !isNCSC)
  XSPerfAccumulate("mmio_nc_lr", io.req.fire && isNCLR)
  XSPerfAccumulate("mmio_nc_sc", io.req.fire && isNCSC)
  XSPerfAccumulate("mmio_amo",   io.req.fire && isNCAMO)
}

// NOTE: AtomicsUnit.scala s_nc_resp must be updated to read SC result from resp.bits.data(0):
//
//   when (state === s_nc_resp) {
//     when (io.uncache.resp.valid) {
//       val nc_data = io.uncache.resp.bits.data
//       resp_data := Mux(isSc,
//         nc_data(0),   // 0=SC success (rd=0), 1=SC fail (rd=1) — from MMIOBridge Excl result
//         ...           // LR/AMO: sign-extend actual memory value
//       )
//       when (isSc) { success := nc_data(0) === 0.U }
//       ...
//     }
//   }
