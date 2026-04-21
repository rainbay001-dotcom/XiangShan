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

// Scaffold stub for the cross-socket CHI bridge.
//
// Each socket exposes one remote CHI port per core (Vec(numCoresPerSocket, PortIO)). The
// bridge accepts them as Flipped — from the bridge's view, the socket is the initiator on
// tx and the receiver on rx. Real forwarding (REQ/RSP/DAT + TxnID remap, then snoop path +
// remote-present directory bit per design-cross-socket-bridge.md) lives in future PRs.
//
// Tie-offs issue zero L-credits so sockets cannot send; rx channels emit nothing; link +
// port switches stay inactive; syscoack echoes syscoreq so per-socket power FSMs don't hang.
// Any tx flit that sneaks through trips a `$fatal` via assert.
class XSBridge(numCoresPerSocket: Int = 2)(implicit val p: Parameters) extends Module with HasSoCParameter {
  val io = IO(new Bundle {
    val s0 = Vec(numCoresPerSocket, Flipped(new PortIO))
    val s1 = Vec(numCoresPerSocket, Flipped(new PortIO))
    val nodeID = Input(UInt(soc.NodeIDWidthList(issue).W))
  })

  private def tieOff(port: PortIO): Unit = {
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

  io.s0.foreach(tieOff)
  io.s1.foreach(tieOff)

  dontTouch(io)

  private def anyTx(ports: Vec[PortIO]): Bool =
    ports.map(p => p.tx.req.flitv || p.tx.rsp.flitv || p.tx.dat.flitv).reduce(_ || _)

  assert(!anyTx(io.s0), "XSBridge: socket 0 emitted cross-socket CHI flit; bridge is scaffold-only")
  assert(!anyTx(io.s1), "XSBridge: socket 1 emitted cross-socket CHI flit; bridge is scaffold-only")
}
