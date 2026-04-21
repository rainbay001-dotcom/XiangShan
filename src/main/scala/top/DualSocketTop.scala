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
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import system.HasSoCParameter

// DualSocketTop — Option 2 scaffold. Each socket is a SocketTop (XSTop-equivalent per-socket
// stack owned in our source tree). Each SocketTop routes its cores' CHI stream three ways:
// local MMIO -> per-core bridge, local DDR -> its own OpenLLC, remote DDR -> io_chi_remote.
// The remote ports (Vec(N) per socket) land on XSBridge, which is still a scaffold stub — it
// accepts no flits and $fatals if the stub path ever fires.
//
// Address map (fixed for the 48-bit PAddrBits space):
//   MMIO            0x0000_0000 .. 0x7FFF_FFFF    (per-socket, replicated)
//   Socket 0 DDR    0x80_0000_0000 .. 0x3F_FFFF_FFFF
//   Socket 1 DDR    0x40_00_0000_0000 .. 0x7F_FF_FFFF_FFFF
//
// Real cross-socket forwarding (REQ/RSP/DAT + TxnID remap, and later SNP + remote-present
// directory bit in OpenLLC) lives in follow-up PRs.
class DualSocketTop()(implicit p: Parameters) extends LazyModule
  with HasSoCParameter
  with BindingScope
{
  override lazy val desiredName: String = "DualSocketTop"

  val socket0Local  = AddressSet(0x80000000L,      0x3f7fffffffL)   // 0x80000000 .. 0x3f_ffffffff
  val socket1Local  = AddressSet(0x4000000000L,    0x3fffffffffL)   // 0x40_00000000 .. 0x7f_ffffffff

  val socket0 = LazyModule(new SocketTop(
    socketId         = 0,
    localRange       = socket0Local,
    remoteRange      = Some(socket1Local),
    hasBridgeInject  = true
  ))
  val socket1 = LazyModule(new SocketTop(
    socketId         = 1,
    localRange       = socket1Local,
    remoteRange      = Some(socket0Local),
    hasBridgeInject  = true
  ))

  lazy val module = new DualSocketTopImp(this)
}

class DualSocketTopImp(wrapper: DualSocketTop) extends LazyRawModuleImp(wrapper) {
  val cpu_clock = IO(Input(Clock()))
  val cpu_reset = IO(Input(AsyncReset()))

  private def tieSocket(sock: SocketTop, hartIdBase: Int, nodeIdBase: Int): Unit = {
    val m = sock.module
    m.io.clock := cpu_clock
    m.io.reset := cpu_reset
    m.io.sram_config := 0.U
    m.io.extIntrs := 0.U
    m.io.pll0_lock := true.B
    m.io.systemjtag.jtag.TCK := cpu_clock
    m.io.systemjtag.jtag.TMS := false.B
    m.io.systemjtag.jtag.TDI := false.B
    m.io.systemjtag.reset := cpu_reset
    m.io.systemjtag.mfr_id := 0.U
    m.io.systemjtag.part_number := 0.U
    m.io.systemjtag.version := 0.U
    m.io.rtc_clock := cpu_clock
    m.io.cacheable_check := DontCare
    m.io.riscv_rst_vec.foreach(_ := "h80000000".U)
    m.io.hartId_base := hartIdBase.U
    m.io.nodeId_base := nodeIdBase.U
    m.io.traceCoreInterface.foreach { t =>
      t.fromEncoder.enable := false.B
      t.fromEncoder.stall  := false.B
    }
    dontTouch(m.io)
    dontTouch(m.memory)
    dontTouch(m.peripheral)
    m.dma.foreach { d => d := DontCare; dontTouch(d) }
  }

  // Socket 0: hartId 0..N-1, NodeID base 0 (tile nodeIDs 0..N-1).
  // Socket 1: hartId N..2N-1, NodeID base 256 (bit 8 separates sockets per design doc).
  tieSocket(wrapper.socket0, hartIdBase = 0,   nodeIdBase = 0)
  tieSocket(wrapper.socket1, hartIdBase = 2,   nodeIdBase = 256)

  val bridge = withClockAndReset(cpu_clock, cpu_reset) {
    Module(new XSBridge(numCoresPerSocket = 2))
  }
  // Wire each socket's per-core remote CHI stream to the bridge (outgoing side).
  wrapper.socket0.module.io_chi_remote.foreach { vec =>
    (bridge.io.s0 zip vec).foreach { case (b, v) => b <> v }
  }
  wrapper.socket1.module.io_chi_remote.foreach { vec =>
    (bridge.io.s1 zip vec).foreach { case (b, v) => b <> v }
  }
  // Wire the bridge's inject ports into each socket's LLC (as an extra RN-F).
  // bridge.sX_inject carries requests that originated on the OTHER socket.
  wrapper.socket0.module.io_chi_bridge_in.foreach { port =>
    bridge.io.s0_inject <> port
  }
  wrapper.socket1.module.io_chi_bridge_in.foreach { port =>
    bridge.io.s1_inject <> port
  }
  bridge.io.nodeID := 512.U
  dontTouch(bridge.io)
}
