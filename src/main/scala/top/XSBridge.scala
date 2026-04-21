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

// Scaffold-phase cross-socket CHI bridge.
//
// Ports per side:
//   sX      : Vec(numCoresPerSocket, Flipped(PortIO))  -- per-core remote-addressed
//             CHI streams coming OUT of socket X. Each element is Flipped, so from
//             the bridge's POV the socket is the transmitter on tx and receiver on rx.
//   sX_inject: PortIO                                  -- bridge's RN-F port driving
//             INTO socket X's OpenLLC (an extra clientCaches slot SocketTop allocates
//             when `hasBridgeInject = true`). Bridge drives tx, receives rx.
//
// In the fully wired cross-socket flow (future PR):
//   socket0 core request targeting socket1 DDR
//     -> socket0 router -> io_chi_remote(i) -> bridge.s0(i)
//     -> bridge.s1_inject -> socket1 OpenLLC (as an extra RN-F)
//     -> socket1 DDR via OpenNCB
//     -> response back along the same path in reverse.
//
// This commit still ties everything off with $fatal guards on tx flits — structural
// plumbing only. Real REQ/RSP/DAT forwarding + TxnID remap is the next PR.
class XSBridge(numCoresPerSocket: Int = 2)(implicit val p: Parameters) extends Module with HasSoCParameter {
  val io = IO(new Bundle {
    val s0         = Vec(numCoresPerSocket, Flipped(new PortIO))
    val s1         = Vec(numCoresPerSocket, Flipped(new PortIO))
    val s0_inject  = new PortIO
    val s1_inject  = new PortIO
    val nodeID     = Input(UInt(soc.NodeIDWidthList(issue).W))
  })

  private def tieOffFlipped(port: PortIO): Unit = {
    // Flipped(PortIO) — socket drives tx, bridge drives rx. Bridge owns tx.lcrdv and
    // all rx.* flit signals + rxsactive + syscoack + rx.linkactivereq + tx.linkactiveack.
    port.tx.req.lcrdv := false.B
    port.tx.rsp.lcrdv := false.B
    port.tx.dat.lcrdv := false.B
    port.rx.snp.flitpend := false.B
    port.rx.snp.flitv    := false.B
    port.rx.snp.flit     := 0.U
    port.rx.rsp.flitpend := false.B
    port.rx.rsp.flitv    := false.B
    port.rx.rsp.flit     := 0.U
    port.rx.dat.flitpend := false.B
    port.rx.dat.flitv    := false.B
    port.rx.dat.flit     := 0.U
    port.tx.linkactiveack := false.B
    port.rx.linkactivereq := false.B
    port.rxsactive := false.B
    port.syscoack := port.syscoreq
  }

  private def tieOffInject(port: PortIO): Unit = {
    // Unflipped PortIO — bridge drives tx, LLC drives rx. Bridge owns tx.flit* signals
    // + rx.*.lcrdv + tx.linkactivereq + rx.linkactiveack + txsactive + syscoreq.
    port.tx.req.flitpend := false.B
    port.tx.req.flitv    := false.B
    port.tx.req.flit     := 0.U
    port.tx.rsp.flitpend := false.B
    port.tx.rsp.flitv    := false.B
    port.tx.rsp.flit     := 0.U
    port.tx.dat.flitpend := false.B
    port.tx.dat.flitv    := false.B
    port.tx.dat.flit     := 0.U
    port.rx.snp.lcrdv := false.B
    port.rx.rsp.lcrdv := false.B
    port.rx.dat.lcrdv := false.B
    port.tx.linkactivereq := false.B
    port.rx.linkactiveack := false.B
    port.txsactive := false.B
    port.syscoreq := false.B
  }

  io.s0.foreach(tieOffFlipped)
  io.s1.foreach(tieOffFlipped)
  tieOffInject(io.s0_inject)
  tieOffInject(io.s1_inject)

  dontTouch(io)

  private def anyTx(ports: Vec[PortIO]): Bool =
    ports.map(p => p.tx.req.flitv || p.tx.rsp.flitv || p.tx.dat.flitv).reduce(_ || _)

  assert(!anyTx(io.s0), "XSBridge: socket 0 emitted cross-socket CHI flit; forwarding not implemented yet")
  assert(!anyTx(io.s1), "XSBridge: socket 1 emitted cross-socket CHI flit; forwarding not implemented yet")
}
