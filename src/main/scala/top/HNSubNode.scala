/***************************************************************************************
* Copyright (c) 2024 Beijing Institute of Open Source Chip (BOSC)
* Copyright (c) 2024 Institute of Computing Technology, Chinese Academy of Sciences
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
import org.chipsalliance.cde.config._
import coupledL2.tl2chi.PortIO

/**
 * CHI 层级 HN 子节点 (Hierarchical HN Sub-Node)
 *
 * 在 CoreWithL2 (L2 缓存) 和 OpenLLC 之间插入的中间层节点：
 *
 *   CoreWithL2 (L2/RN) → CHIHNSubNode (HN to L2 / RN to OpenLLC) → OpenLLC (全局 HN)
 *
 * - 对 CoreWithL2 呈现为 HN (Home Node)：L2 缓存作为 RN 向该节点发送请求
 * - 对 OpenLLC 呈现为 RN (Request Node)：该节点接入 OpenLLC 的 RN 接口，
 *   在 OpenLLC 视角下充当其上游请求节点，由 OpenLLC 作为全局 HN 处理一致性
 *
 * 当前实现为透明传递（结构骨架），后续可扩展以下功能：
 *   1. 集群级目录 (Cluster-level Directory)
 *   2. 集群内 L2 间的 Snoop 过滤
 *   3. 向 OpenLLC 转发前的请求合并
 *   4. 集群级数据缓存 (Cluster Cache)
 *
 * 实现工具链: mill 0.12.3, firtool 1.62.1
 */
class CHIHNSubNode(val numCores: Int)(implicit p: Parameters) extends Module {

  val io = IO(new Bundle {
    // HN 接口 (面向 L2 的 HN 侧)：L2 缓存作为 Request Node 接入此处
    val rnFromL2     = Vec(numCores, Flipped(new PortIO))
    // RN 接口 (面向 OpenLLC 的 RN 侧)：该节点以 RN 身份接入 OpenLLC 的 rn 端口
    // OpenLLC 是全局 HN，该节点充当其 RN (Request Node) 发起上层请求
    val rnToOpenLLC  = Vec(numCores, new PortIO)
  })

  // 透明传递：将每个 L2 的 CHI 流量以 RN 身份转发至 OpenLLC
  // 扩展点：在此处添加集群 HN 逻辑（目录查找、Snoop 处理、请求合并等）
  for (i <- 0 until numCores) {
    io.rnToOpenLLC(i) <> io.rnFromL2(i)
  }
}
