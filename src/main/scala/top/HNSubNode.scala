/***************************************************************************************
* Copyright (c) 2024-2025 Beijing Institute of Open Source Chip (BOSC)
* Copyright (c) 2020-2025 Institute of Computing Technology, Chinese Academy of Sciences
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
import chisel3.util._
import coupledL2.tl2chi.PortIO
import org.chipsalliance.cde.config._

/**
 * CHI Hierarchical HN SubNode.
 *
 * Acts as HN-F toward CoreWithL2 (presents as Home Node to L2 cache),
 * and acts as RN toward OpenLLC (presents as Request Node to the LLC).
 * This transparent passthrough enables address-space routing and debug
 * logging to be inserted between L2 and OpenLLC without protocol changes.
 */
class CHIHNSubNode(numCores: Int)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    // HN side: L2 sees this node as an HN-F
    val rnFromL2    = Vec(numCores, Flipped(new PortIO))
    // RN side: OpenLLC sees this node as an RN
    val rnToOpenLLC = Vec(numCores, new PortIO)
  })

  for (i <- 0 until numCores) {
    io.rnToOpenLLC(i) <> io.rnFromL2(i)
  }
}
