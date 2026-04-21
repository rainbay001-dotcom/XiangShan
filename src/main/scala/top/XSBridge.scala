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

// Cross-socket CHI bridge — first functional forwarding.
//
// Ports (per side X in {0,1}):
//   io.sX           : Vec(numCoresPerSocket, Flipped(PortIO))
//                     Per-core remote-addressed CHI streams coming OUT of socket X.
//                     Flipped, so from the bridge's POV the socket is the tx side.
//   io.sX_inject    : PortIO
//                     Bridge's RN-F port INTO socket X's OpenLLC (an extra clientCaches
//                     slot SocketTop allocates when hasBridgeInject=true). Bridge drives
//                     tx, receives rx.
//
// Forwarding path (each direction symmetric):
//   socket 0 core 0 remote request
//     -> io.s0(0)              (Flipped PortIO, bridge receives)
//     <> io.s1_inject           (PortIO, bridge drives)
//     -> socket 1 OpenLLC as an extra RN-F
//     -> socket 1 DDR via OpenNCB
//     <- response back along the same wires in reverse.
//
// This commit forwards ONLY core 0 of each socket. Multi-core (N-to-1 arbitration +
// TxnID remap table + L-credit bookkeeping across arbiter) is the next PR. Cores >= 1
// remain tied off with $fatal guards so accidental traffic is caught loudly.
//
// Chisel's `<>` operator is direction-aware: connecting Flipped(PortIO) <> PortIO
// wires tx.flit* one direction (socket->LLC) and tx.lcrdv the other (LLC->socket),
// plus the link-active handshake, port-switch, and sys-coherency signals in their
// natural directions. No explicit per-signal driving needed for the forwarded pair.
//
// Known non-functional aspects (explicit):
//   - No coherence across sockets — remote-present bit in OpenLLC directory comes
//     in a later PR. A line cached by socket 0 is invisible to socket 1's writers.
//   - No snoop forwarding logic beyond the bulk pass-through; the rx.snp channel is
//     wired but no directory-driven snoop generation exists yet.
//   - syscoreq/syscoack cross the socket boundary via the pass-through. This may
//     confuse per-socket power FSMs if they ever disagree; acceptable for scaffold.
class XSBridge(numCoresPerSocket: Int = 2)(implicit val p: Parameters) extends Module with HasSoCParameter {
  val io = IO(new Bundle {
    val s0         = Vec(numCoresPerSocket, Flipped(new PortIO))
    val s1         = Vec(numCoresPerSocket, Flipped(new PortIO))
    val s0_inject  = new PortIO
    val s1_inject  = new PortIO
    val nodeID     = Input(UInt(soc.NodeIDWidthList(issue).W))
  })

  // Core-0 cross-socket bulk connect. Chisel's <> pairs each sub-field in the
  // correct direction given the Flipped on io.sX.
  io.s1_inject <> io.s0(0)
  io.s0_inject <> io.s1(0)

  // Cores >= 1 are unused in this PR. Drive safe defaults so nothing ships.
  private def tieOffFlipped(port: PortIO): Unit = {
    // tx side: socket drives flit*, bridge drives lcrdv. Issue zero credits.
    port.tx.req.lcrdv := false.B
    port.tx.rsp.lcrdv := false.B
    port.tx.dat.lcrdv := false.B
    // rx side: bridge drives flit*, socket drives lcrdv. Emit nothing.
    port.rx.snp.flitpend := false.B
    port.rx.snp.flitv    := false.B
    port.rx.snp.flit     := 0.U
    port.rx.rsp.flitpend := false.B
    port.rx.rsp.flitv    := false.B
    port.rx.rsp.flit     := 0.U
    port.rx.dat.flitpend := false.B
    port.rx.dat.flitv    := false.B
    port.rx.dat.flit     := 0.U
    // Link / port switch: stay inactive.
    port.tx.linkactiveack := false.B
    port.rx.linkactivereq := false.B
    port.rxsactive := false.B
    // System coherency: echo locally.
    port.syscoack := port.syscoreq
  }
  for (i <- 1 until numCoresPerSocket) {
    tieOffFlipped(io.s0(i))
    tieOffFlipped(io.s1(i))
  }

  dontTouch(io)

  // Loud failure if unused cores ever try to transmit.
  for (i <- 1 until numCoresPerSocket) {
    val s0i_tx = io.s0(i).tx.req.flitv || io.s0(i).tx.rsp.flitv || io.s0(i).tx.dat.flitv
    val s1i_tx = io.s1(i).tx.req.flitv || io.s1(i).tx.rsp.flitv || io.s1(i).tx.dat.flitv
    assert(!s0i_tx, s"XSBridge: socket 0 core $i emitted cross-socket flit; multi-core forwarding is a later PR")
    assert(!s1i_tx, s"XSBridge: socket 1 core $i emitted cross-socket flit; multi-core forwarding is a later PR")
  }
}
