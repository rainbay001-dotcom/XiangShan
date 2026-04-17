/***************************************************************************************
* Copyright (c) 2026 Institute of Computing Technology, Chinese Academy of Sciences
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
import coupledL2.tl2chi.PortIO
import org.chipsalliance.cde.config.Parameters
import system.HasSoCParameter

// Scaffold-only stub for the cross-socket CHI bridge.
//
// Real forwarding logic (REQ/RSP/DAT/SNP routing, TxnID remapping, remote-present
// directory extension in OpenLLC) lives in future PRs per
// docs/design-cross-socket-bridge.md. This module exists so DualSocketTop elaborates
// with the intended topology, and so any accidental cross-socket traffic during
// development trips a loud assertion instead of silently succeeding.
//
// Each io.s{0,1} port is Flipped, so from this module's perspective the socket is the
// initiator on tx and receiver on rx. PortIO channels use CHI flit/L-credit signaling
// (ChannelIO: flitpend/flitv/flit are tx-driven, lcrdv is rx-driven).
class XSBridge()(implicit val p: Parameters) extends Module with HasSoCParameter {
  val io = IO(new Bundle {
    val s0 = Flipped(new PortIO)
    val s1 = Flipped(new PortIO)
    val nodeID = Input(UInt(soc.NodeIDWidthList(issue).W))
  })

  private def tieOff(port: PortIO): Unit = {
    // tx side: socket drives flit{pend,v,data}; we drive lcrdv. Issue zero credits so
    // the socket cannot send anything.
    port.tx.req.lcrdv := false.B
    port.tx.rsp.lcrdv := false.B
    port.tx.dat.lcrdv := false.B

    // rx side: we drive flit{pend,v,data}; socket drives lcrdv. Emit nothing.
    port.rx.snp.flitpend := false.B
    port.rx.snp.flitv    := false.B
    port.rx.snp.flit     := 0.U
    port.rx.rsp.flitpend := false.B
    port.rx.rsp.flitv    := false.B
    port.rx.rsp.flit     := 0.U
    port.rx.dat.flitpend := false.B
    port.rx.dat.flitv    := false.B
    port.rx.dat.flit     := 0.U

    // Link activation handshake: stay inactive on both halves.
    port.tx.linkactiveack := false.B
    port.rx.linkactivereq := false.B

    // Port switch: accept that the socket is active, keep our side inactive.
    port.rxsactive := false.B

    // System coherency: echo syscoreq back as syscoack so the per-socket power FSM
    // sees a clean handshake. This is safe because no coherence domain spans the
    // bridge yet.
    port.syscoack := port.syscoreq
  }

  tieOff(io.s0)
  tieOff(io.s1)

  dontTouch(io)

  val s0_any_tx = io.s0.tx.req.flitv || io.s0.tx.rsp.flitv || io.s0.tx.dat.flitv
  val s1_any_tx = io.s1.tx.req.flitv || io.s1.tx.rsp.flitv || io.s1.tx.dat.flitv
  assert(!s0_any_tx, "XSBridge: socket 0 emitted cross-socket CHI flit; bridge is scaffold-only")
  assert(!s1_any_tx, "XSBridge: socket 1 emitted cross-socket CHI flit; bridge is scaffold-only")
}
