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

// DualSocketTop — Phase-1 scaffold for the two-socket XiangShan system.
//
// Topology: 2 sockets × 2 cores = 4 cores total. Each socket is a full XSTop
// (2× XSTile + shared OpenLLC + OpenNCB + MMIO bridges + DDR AXI), replicating
// the verified `feat/two-core-shared-llc` stack. An XSBridge stub sits between the
// two sockets so downstream work can plug in real cross-socket CHI forwarding.
//
// This module's goal is RTL generation only. To keep the top-level IO surface small
// enough to inspect by hand, every XSTop input is driven to a safe default here and
// outputs are `dontTouch`ed so they survive firtool optimization. The XSBridge ports
// are tied off internally — the bridge only fires assertions if future work wires up
// real CHI flits without first lifting the stub.
//
// See docs/design-two-socket-system.md for the full architecture and
// docs/design-cross-socket-bridge.md for the XSBridge roadmap.
class DualSocketTop()(implicit p: Parameters) extends LazyModule
  with HasSoCParameter
  with BindingScope
{
  override lazy val desiredName: String = "DualSocketTop"

  val socket0 = LazyModule(new XSTop())
  val socket1 = LazyModule(new XSTop())

  lazy val module = new DualSocketTopImp(this)
}

class DualSocketTopImp(wrapper: DualSocketTop) extends LazyRawModuleImp(wrapper) {
  val cpu_clock = IO(Input(Clock()))
  val cpu_reset = IO(Input(AsyncReset()))

  private def tieSocket(sock: XSTop): Unit = {
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
    m.io.traceCoreInterface.foreach { t =>
      t.fromEncoder.enable := false.B
      t.fromEncoder.stall  := false.B
    }
    // Preserve outputs so firtool doesn't fold them away.
    dontTouch(m.io)
    dontTouch(m.memory)
    dontTouch(m.peripheral)
    m.dma.foreach { d => d := DontCare; dontTouch(d) }
  }

  tieSocket(wrapper.socket0)
  tieSocket(wrapper.socket1)

  // Cross-socket CHI bridge (scaffold stub).
  val bridge = withClockAndReset(cpu_clock, cpu_reset) {
    Module(new XSBridge())
  }
  bridge.io.s0 := DontCare
  bridge.io.s1 := DontCare
  bridge.io.nodeID := 512.U
  dontTouch(bridge.io)
}
