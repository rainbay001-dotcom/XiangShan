/***************************************************************************************
* Copyright (c) 2024 Beijing Institute of Open Source Chip (BOSC)
* Copyright (c) 2020-2024 Institute of Computing Technology, Chinese Academy of Sciences
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

// NOTE: This file requires the openLLC and coupledL2 submodules to be initialized.
//       Run: git submodule update --init openLLC coupledL2

package openllcnested

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import coupledL2.tl2chi.PortIO
import coupledL2.L2Param
import openLLC.OpenLLCParam
import system.HasSoCParameter

// Configuration key for OpenLLCNested parameters (no default; must be set before instantiation)
case object OpenLLCNestedParamKey extends Field[OpenLLCParam]

// OpenLLCNested: coherent intermediate cache between Core L2s and OpenLLC.
//
// Topology:
//   Core L2 (RN) ──[CHILogger]──> OpenLLCNested.io.rn[i]  (NESTED acts as HN)
//                                  OpenLLCNested.io.sn      (NESTED acts as RN)
//                 <──[CHILogger]── OpenLLC.io.rn[0]         (OpenLLC acts as HN)
//
// CHI channel summary:
//   io.rn[i] (HN-facing, toward Core L2):
//     REQ  ← in   (receive from Core L2)
//     SNP  → out  (send coherence probes to Core L2)
//     RSP  ↔      (bidirectional completions)
//     DAT  ↔      (bidirectional data)
//
//   io.sn (RN-facing, toward OpenLLC):
//     REQ  → out  (issue to OpenLLC on miss)
//     SNP  ← in   (receive coherence probes from OpenLLC; requires SNP on OpenLLC rn port)
//     RSP  ↔      (bidirectional completions)
//     DAT  ↔      (bidirectional data)
//
// Key requirement: OpenLLC must expose SNP on its io.rn port when NESTED is connected
// as an RN, so that OpenLLC can probe NESTED's cached state.
class OpenLLCNested()(implicit p: Parameters) extends Module with HasSoCParameter {

  val nestedParams = p(OpenLLCNestedParamKey)
  val numRNs       = nestedParams.clientCaches.length
  val nodeIDWidth  = soc.NodeIDWidthList(issue)

  val io = IO(new Bundle {
    // HN-facing ports: accept CHI REQ from Core L2 RNs (same type as OpenLLC.io.rn)
    val rn = Vec(numRNs, Flipped(new PortIO))
    // RN-facing port: issue CHI REQ to OpenLLC HN (same type as Core L2's chi port)
    val sn = new PortIO
    // This node's CHI node ID (set by Top.scala)
    val nodeID = Input(UInt(nodeIDWidth.W))
    // Debug top-down probing (mirrors OpenLLC's interface)
    val debugTopDown = new Bundle {
      val robHeadPaddr = Input(Vec(numRNs, Valid(UInt(soc.PAddrBits.W))))
      val addrMatch    = Output(Vec(numRNs, Bool()))
    }
    // L3 miss indicator fed back to tiles for performance counters
    val l3Miss = Output(Bool())
  })

  // =========================================================================
  // Internal state (to be implemented)
  // =========================================================================
  // A complete implementation requires:
  //   1. Directory: tracks which Core L2s hold copies of each cache line
  //   2. MSHRs: manages outstanding requests (miss handling, SNP forwarding)
  //   3. Data arrays: stores cache lines cached by NESTED itself
  //   4. Request pipeline: HN-side (processes Core L2 REQs, issues SNPs to Core L2s)
  //   5. Miss pipeline: RN-side (issues REQs to OpenLLC, handles SNPs from OpenLLC)
  //
  // SNP forwarding (OpenLLC → Core L2) flow:
  //   a. Receive SNP from OpenLLC on io.sn.snp (requires SNP channel on OpenLLC rn port)
  //   b. Lookup directory: which Core L2 caches the block?
  //   c. Forward SNP to relevant Core L2(s) via io.rn[i].snp
  //   d. Collect SnpResp/SnpRespData, update directory, send CompAck to OpenLLC
  //
  // REQ handling (Core L2 → NESTED) flow:
  //   a. Receive REQ from Core L2 on io.rn[i].req
  //   b. Lookup directory: hit or miss?
  //   c. Hit: send RSP/DAT directly from NESTED data array
  //   d. Miss: allocate MSHR, send REQ to OpenLLC via io.sn.req
  //            wait for DAT from OpenLLC, fill NESTED, respond to Core L2
  //   e. On ownership change: send SNP to sharer Core L2s, collect responses

  // =========================================================================
  // Placeholder wiring
  // =========================================================================
  // The following ties off all IOs to safe defaults.
  // Replace with actual cache coherence logic.

  io.rn.foreach { rnPort =>
    rnPort := DontCare
  }
  io.sn := DontCare

  io.l3Miss := false.B
  io.debugTopDown.addrMatch.foreach(_ := false.B)
}
