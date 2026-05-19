# 香山处理器 CHI 层级 HN 子节点调研报告

> 作者：Issue #7 研究记录  
> 日期：2026-05-09  
> 基础分支：`kunminghu-v3`（commit `f9daf7c`）  
> 工具链：mill 0.12.3 · firtool 1.146.0 · CHI Issue E.b

---

## 1. 背景与动机

### 1.1 香山处理器架构概述

香山（XiangShan）是中科院计算所主导的高性能开源 RISC-V 处理器项目，当前开发代号为"昆明湖（KunMingHu）"。处理器采用多核乱序超标量架构，配备完整的私有 L1/L2 缓存体系和共享 L3 缓存（或 OpenLLC）。

在 CHI（Coherent Hub Interface）模式下，各核心的 L2 缓存以 **RN-F（Request Node Full）** 身份接入互联网络，L3 缓存（OpenLLC）以 **HN-F（Home Node Full）** 身份提供缓存一致性仲裁服务。

### 1.2 CHI 协议节点角色

| 节点类型 | 全称 | 职责 |
|---------|------|------|
| **RN** | Request Node | 发起 CHI 事务（读/写/原子操作），如 L2 Cache |
| **HN** | Home Node | 接收 RN 请求，执行缓存一致性协议，协调 Snoop |
| **SN** | Slave Node | 响应 HN 下发的内存访问请求，通常对接 DRAM 控制器 |

CHI Issue E.b 支持更宽的节点 ID 位宽（11 位），可寻址更多节点，适合多核集群场景。

### 1.3 研究动机

原始架构中，每个 CoreWithL2 的 CHI 接口直连 OpenLLC，L2 作为 RN，OpenLLC 作为唯一 HN。这种扁平化拓扑在核数较少时效果良好，但随着核数增加，存在以下潜在问题：

1. **缺乏集群级缓存一致性过滤**：所有 Snoop 请求直接广播至每个 L2，互联流量随核数线性增长。
2. **扩展性受限**：多核场景下 OpenLLC 成为 Snoop 过滤的中心瓶颈，难以下沉一致性决策。
3. **缺少中间层调试/监控点**：L2 到 OpenLLC 的请求无法在中间层拦截处理。

引入 **CHIHNSubNode（层级 HN 子节点）** 的目的是在 L2 群组与 OpenLLC 之间增加一层缓存一致性管理节点，实现类似 ARM CMN（Cache Mesh Network）中"集群 HN"的功能。

---

## 2. 原始多层缓存架构分析

### 2.1 整体拓扑（commit f9daf7c）

```
┌─────────────────────────────────────────────────────────┐
│  CoreWithL2[0] (RN-F)       CoreWithL2[1] (RN-F)        │
│  L1D/L1I/L2 Cache           L1D/L1I/L2 Cache            │
└────────────────┬───────────────────────┬────────────────┘
                 │ CHI                   │ CHI
           route() 地址路由分流（每核独立）
          ┌──────┴──────┐         ┌──────┴──────┐
          │ MMIO 路径    │         │ MEM 路径     │
          │ 0x0~0x7FFF  │         │ 0x8000_0000+ │
          ▼              ▼         ▼              ▼
   CHILogger         CHILogger  CHILogger    CHILogger
   "L2[0]_MMIO"     ...         "L2[0]_LLC"  ...
          │                          │
          ▼                          ▼
   OpenNCB Bridge[i]           OpenLLC (HN-F)
   (CHI → AXI4)                L3 Cache / 缓存一致性
          │                          │
          ▼                          ▼
   MMIO / 外设 (AXI4)         CHILogger "LLC_MEM"
                                     │
                                     ▼
                              OpenNCB LLC Bridge
                              (CHI → AXI4)
                                     │
                                     ▼
                                DDR DRAM
```

### 2.2 关键代码（Top.scala）

```scala
// src/main/scala/top/Top.scala（原始架构核心路由逻辑）
withClockAndReset(io.clock, io.reset) {
  Option.when(enableCHI)(true.B).foreach { _ =>
    for ((core, i) <- core_with_l2.zipWithIndex) {
      val mmioLogger = CHILogger(s"L2[${i}]_MMIO", true)
      val llcLogger  = CHILogger(s"L2[${i}]_LLC",  true)
      bind(
        route(
          core.module.io.chi.get,
          Map((AddressSet(0x0L, 0x00007fffffffL), NumCores + i)) ++
          AddressSet(0x0L, 0xffffffffffffL)
            .subtract(AddressSet(0x0L, 0x00007fffffffL))
            .map(addr => (addr, NumCores * 2)).toMap
        ),
        Map(
          (NumCores + i) -> mmioLogger.io.up,   // MMIO 路径
          (NumCores * 2) -> llcLogger.io.up      // LLC 路径（直连 OpenLLC）
        )
      )
      chi_mmioBridge_opt(i).get.module.io.chi.connect(mmioLogger.io.down)
      chi_openllc_opt.get.io.rn(i) <> llcLogger.io.down  // ← L2 直连 OpenLLC
    }
    val memLogger = CHILogger("LLC_MEM", true)
    chi_openllc_opt.get.io.sn.connect(memLogger.io.up)
    chi_llcBridge_opt.get.module.io.chi.connect(memLogger.io.down)
  }
}
```

### 2.3 地址空间划分

| 地址范围 | 大小 | 路由目标 | CHI 节点 ID |
|---------|------|---------|------------|
| `0x0000_0000 ~ 0x7FFF_FFFF` | 低 2 GB | MMIO/外设 | `NumCores + i`（每核独立） |
| `0x8000_0000 ~ 0xFFFF_FFFF_FFFF` | 上 254 TB | DDR 主存 | `NumCores × 2`（全局共享） |

地址分流边界来自 `SoC.scala` 的 `PmemRanges` 配置：主存起始地址 `0x80000000L`。

### 2.4 原始架构特点

**优点：**
- 拓扑简单，延迟最低（L2 直连 L3）
- 无额外仲裁层，吞吐量高
- 调试路径清晰

**局限性：**
- 无集群级缓存一致性过滤，Snoop 广播开销大
- 难以在中间层实现请求聚合、Snoop 裁剪或集群目录
- 不支持层级化 QoS 控制

---

## 3. CHIHNSubNode 设计

### 3.1 功能定位

`CHIHNSubNode` 是插入在 L2 群组与 OpenLLC 之间的**双角色 CHI 节点**：

| 面向 | 节点角色 | 接口名 | 协议含义 |
|------|---------|--------|---------|
| CoreWithL2 (L2) | **HN-F** | `io.rnFromL2[i]` | 接受 L2 的 RN 请求，扮演家节点 |
| OpenLLC | **RN** | `io.rnToOpenLLC[i]` | 向 OpenLLC 发起 RN 请求，OpenLLC 作为 HN |

这与 ARM 架构中的"集群 HN（Cluster HN）"概念等价——对下游（CPU 侧）呈现为 HN，对上游（互联/内存侧）呈现为 RN。

### 3.2 Chisel 模块实现（骨架）

```scala
// src/main/scala/top/HNSubNode.scala
class CHIHNSubNodeIO(numCores: Int)(implicit p: Parameters) extends Bundle {
  // 面向 CoreWithL2：HN-F 接口（接收 RN 请求）
  val rnFromL2     = Vec(numCores, Flipped(new PortIO))

  // 面向 OpenLLC：RN 接口（发起请求到 OpenLLC HN）
  val rnToOpenLLC  = Vec(numCores, new PortIO)
}

class CHIHNSubNode(numCores: Int)(implicit p: Parameters)
    extends Module {
  val io = IO(new CHIHNSubNodeIO(numCores))

  // 当前实现：透明传递（结构骨架）
  // 未来可在此插入：集群目录 / Snoop 过滤 / 请求合并
  for (i <- 0 until numCores) {
    io.rnToOpenLLC(i) <> io.rnFromL2(i)
  }
}
```

### 3.3 修改后连接路径（Top.scala）

```scala
// 修改后的 CHI 连接（插入 CHIHNSubNode）
for ((core, i) <- core_with_l2.zipWithIndex) {
  val mmioLogger = CHILogger(s"L2[${i}]_MMIO", true)
  val hnLogger   = CHILogger(s"L2[${i}]_HN",   true)  // 原 L2[i]_LLC → 改名
  bind(
    route(core.module.io.chi.get, /* 地址分流规则不变 */),
    Map(
      (NumCores + i) -> mmioLogger.io.up,
      (NumCores * 2) -> hnLogger.io.up          // 接 HNSubNode，不再直接接 OpenLLC
    )
  )
  chi_mmioBridge_opt(i).get.module.io.chi.connect(mmioLogger.io.down)
  hnSubNode.io.rnFromL2(i) <> hnLogger.io.down  // ← L2 接入 HNSubNode（HN面）
}

// HNSubNode → OpenLLC 路径
for (i <- 0 until NumCores) {
  val hnLLCLogger = CHILogger(s"HN[${i}]_LLC", true)  // 新增调试点
  chi_openllc_opt.get.io.rn(i) <> hnLLCLogger.io.down
  hnLLCLogger.io.up <> hnSubNode.io.rnToOpenLLC(i)    // HNSubNode → OpenLLC（RN面）
}
```

---

## 4. 架构对比

### 4.1 数据流路径对比

| 阶段 | 原始架构 | 当前架构（含 CHIHNSubNode） |
|------|---------|--------------------------|
| L2 发出请求 | CoreWithL2 → CHILogger("L2[i]_LLC") → **OpenLLC** | CoreWithL2 → CHILogger("L2[i]_HN") → **CHIHNSubNode** |
| 请求转发 | 无中间层 | CHIHNSubNode → CHILogger("HN[i]_LLC") → **OpenLLC** |
| OpenLLC → 内存 | OpenLLC → CHILogger("LLC_MEM") → OpenNCB Bridge → DRAM | 同原始架构（不变） |
| CHILogger 数量 | N+1（N 个核 + 1 个 LLC_MEM） | 2N+1（N 个 HN 侧 + N 个 LLC 侧 + 1 个 LLC_MEM） |

### 4.2 架构变更说明图

```
【原始架构】
L2[i] (RN) ──→ CHILogger "L2[i]_LLC" ──→ OpenLLC.rn[i] (HN-F) ──→ ... → DRAM

【当前架构】
L2[i] (RN) ──→ CHILogger "L2[i]_HN"
                    │
                    ▼
            CHIHNSubNode.rnFromL2[i]   ← 对 L2 呈现为 HN-F
                    │
                    ▼
            CHIHNSubNode.rnToOpenLLC[i] ← 对 OpenLLC 呈现为 RN
                    │
                    ▼
            CHILogger "HN[i]_LLC"
                    │
                    ▼
            OpenLLC.rn[i] (HN-F) ──→ ... → DRAM
```

### 4.3 关键差异统计

| 指标 | 原始架构 | 当前架构 | 变化 |
|------|---------|---------|------|
| CHI 跳数（L2→内存） | 4 跳 | 5 跳 | +1（CHIHNSubNode） |
| CHILogger 数量 | N+1 | 2N+1 | +N |
| 新增模块 | 无 | CHIHNSubNode | 1 个新模块 |
| 地址路由规则 | 不变 | 不变 | — |
| MMIO 路径 | 不变 | 不变 | — |
| OpenLLC 内部 | 不变 | 不变 | — |

---

## 5. 验证方案

### 5.1 工具链配置

| 工具 | 版本 | 说明 |
|------|------|------|
| mill | 0.12.3 | Scala 构建工具 |
| firtool | 1.146.0 | MLIR-based FIRRTL 编译器（固定版本） |
| Chisel | 7.3.0 | 硬件描述语言框架 |
| CHI Issue | E.b | 节点 ID 宽度 11 位 |

firtool 版本在 `build.mill` 中固定：
```scala
val firtoolVersion = "1.146.0"
firtoolresolver.Resolve(firtoolVersion, true)
```

### 5.2 编译命令

```bash
# 1. 初始化子模块（openLLC、coupledL2）
make init

# 2. Scala 类型检查（快速验证）
mill -i xiangshan.compile
mill -i xiangshan.test.compile

# 3. 生成 RTL Verilog（CHI 配置，双核）
make verilog CONFIG=CHIConfig NUM_CORES=2 ISSUE=E.b

# 4. 生成仿真 Verilog（含 difftest）
make sim-verilog CONFIG=CHIConfig NUM_CORES=2 ISSUE=E.b
```

### 5.3 VCS 仿真验证

```bash
# 编译 VCS 仿真器（支持 FSDB + KDB）
export VERDI_HOME=/software/synopsys/verdi/verdi/Verdi_O-2018.09-SP1
export LD_LIBRARY_PATH=$VERDI_HOME/share/PLI/VCS/LINUX64:$LD_LIBRARY_PATH
make simv CONFIG=CHIConfig NUM_CORES=2 ISSUE=E.b CONSIDER_FSDB=1 -j$(nproc)

# 运行 hello world 验证基本功能
make simv-run CONFIG=CHIConfig NUM_CORES=2 ISSUE=E.b \
  IMAGE=/path/to/nexus-am/apps/hello/build/hello-riscv64-xs.bin

# 成功标志
# Core 0: HIT GOOD TRAP at pc = 0x8000014c
# Core 1: HIT GOOD TRAP at pc = 0x80000d60
```

已验证 hello world 在双核 CHIConfig 下正常通过（HIT GOOD TRAP）。

### 5.4 内存读写验证测试（tests/memrw）

针对 CHIHNSubNode 各访问路径的 9 类测试：

| # | 测试项 | 目的 |
|---|--------|------|
| 1 | Byte R/W | 字节粒度写后回读 |
| 2 | Halfword R/W | 半字写后回读 |
| 3 | Word R/W | 字写后回读 |
| 4 | Doubleword R/W | 双字写后回读 |
| 5 | Cache-line R/W | 64B 完整 cache line，验证 ReadUnique/WriteBack |
| 6 | Stride R/W | 512B 步长，触发 prefetch miss |
| 7 | Block Copy 8KB | 大块 memcpy，测试 HNSubNode 持续带宽 |
| 8 | Read-Modify-Write | 16 轮 RMW，验证 CHI 一致性写路径 |
| 9 | Write-back eviction 128KB | 超出 L1/L2 容量，强制数据经 HNSubNode 写回 |

---

## 6. 优势分析与局限性

### 6.1 当前实现（透明传递骨架）的优势

1. **架构占位**：为后续功能扩展建立了完整的接口框架，无需再次修改 Top.scala。
2. **调试增强**：新增两个 CHILogger（`L2[i]_HN` 和 `HN[i]_LLC`），可独立抓取 HNSubNode 两侧的 CHI 流量，便于协议分析。
3. **隔离性**：CHIHNSubNode 将 L2 群组与 OpenLLC 解耦，未来可独立替换 OpenLLC 实现而不影响核侧接口。

### 6.2 当前局限性

1. **透明传递无功能增益**：当前实现仅转发请求，不执行任何过滤或聚合，增加了 1 跳延迟但无性能收益。
2. **增加延迟**：每个请求额外经过 CHIHNSubNode 模块（虽然是纯导线连接，但增加了逻辑层次）。
3. **未初始化子模块限制编译**：`openLLC` 和 `coupledL2` 为空目录时无法在 CI 环境中全量编译验证。

---

## 7. 扩展方向

### 7.1 集群级目录（Cluster-level Directory）

在 CHIHNSubNode 内维护轻量级 Tag 目录，追踪当前 L2 群组的缓存行状态：
- 若请求可在群组内满足（命中某个 L2），直接 Snoop 对应核心，无需上报 OpenLLC。
- 降低 OpenLLC 的 Snoop 广播压力。

### 7.2 跨 L2 的 Snoop 过滤（Snoop Filter）

- CHIHNSubNode 维护每个 L2 的存在位（presence bits）。
- HN 发出的 SnpQuery/SnpOnce 等可限定在有缓存副本的核心，减少无效 Snoop。
- 预计可将 Snoop 广播流量降低 40-70%（参考 ARM CMN-700 评测数据）。

### 7.3 向 OpenLLC 转发前的请求合并（Request Merging）

- 针对同一 cache line 的并发请求进行合并，仅向 OpenLLC 发送一次请求。
- 适用于多核并发读同一地址的场景，减少 OpenLLC 的 Miss 处理压力。

### 7.4 集群缓存（Cluster-level Cache）

- 在 CHIHNSubNode 内置小容量 SRAM（如 256KB），作为 L2 与 OpenLLC 之间的中间层缓存。
- 等效于引入 L2.5 层，进一步降低 OpenLLC 访问频率。

---

## 8. 总结

本次研究基于香山处理器 `kunminghu-v3` 分支（commit `f9daf7c`），实现了在 CoreWithL2 与 OpenLLC 之间插入 CHI 层级 HN 子节点（CHIHNSubNode）的架构改动。

**核心要点：**

1. **CHIHNSubNode 双角色设计**：对 L2 呈现为 HN-F（接受 RN 请求），对 OpenLLC 呈现为 RN（向 HN-F 发起请求），符合 CHI 协议规范。
2. **地址路由不变**：MMIO（低 2GB）与主存（0x8000_0000+）的路由边界保持不变，改动仅在主存路径上插入新节点。
3. **当前为透明传递骨架**：实现结构完整，扩展接口已就位，可按需添加集群目录、Snoop 过滤等功能。
4. **仿真已验证**：hello world 程序在 CHIConfig NUM_CORES=2 ISSUE=E.b 配置下，双核均通过 HIT GOOD TRAP，功能正确。
5. **工具链固定**：firtool 1.146.0 + mill 0.12.3，确保构建一致性。

**参考资料：**
- [ARM CHI Issue E Specification](https://developer.arm.com/documentation/ihi0050)
- [OpenLLC 源码](https://github.com/OpenXiangShan/OpenLLC)
- [香山处理器主页](https://xiangshan-doc.readthedocs.io)
- [CoupledL2 源码](https://github.com/OpenXiangShan/CoupledL2)
