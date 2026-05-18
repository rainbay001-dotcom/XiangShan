# CHIHNSubNode 调研报告

> 基于 XiangShan commit `f9daf7c15bcb61e1332359a9c0410067f96f2ce4`  
> CHI Issue E.b · mill 0.12.3 · firtool 1.146.0  
> 日期：2025-05

---

## 目录

1. [背景与动机](#1-背景与动机)
2. [CHI 协议节点角色分析](#2-chi-协议节点角色分析)
3. [原版架构](#3-原版架构)
4. [CHIHNSubNode 设计方案](#4-chihnsubnode-设计方案)
5. [Top 层集成](#5-top-层集成)
6. [验证策略](#6-验证策略)
7. [构建与仿真命令](#7-构建与仿真命令)
8. [已知限制与后续工作](#8-已知限制与后续工作)

---

## 1. 背景与动机

XiangShan 南湖（Nanhu）及后续核采用 **CHI E.b** 作为 L2 Cache 与 LLC（OpenLLC）之间的互联协议。原版实现中，`CoreWithL2` 的 CHI 端口经过一个 `CHILogger` 后直接连接到 `OpenLLC.io.rn`，没有中间层次节点。

需要插入一个 **CHIHNSubNode** 的原因：

| 需求 | 说明 |
|------|------|
| 层次化调试 | 在 L2↔LLC 链路上插入可独立控制的观测点 |
| 地址路由扩展 | 为多 HN 划分地址段预留接口 |
| 协议转换扩展点 | 将来可在此实现 CHI Issue F/G 降级适配 |
| 仿真可见性 | 通过 CHILogger 分别记录 "L2侧" 和 "LLC侧" 流量 |

---

## 2. CHI 协议节点角色分析

CHI（Coherent Hub Interface，AMBA 5）定义三种节点角色：

```
RN（Request Node）    → 发起事务（L2 Cache 扮演此角色）
HN（Home Node）       → 仲裁、目录查询、snoop 分发（OpenLLC 扮演此角色）
SN（Slave Node）      → 被动响应内存读写（OpenNCB/DDR 控制器扮演此角色）
```

`CHIHNSubNode` 的双重身份：

```
CoreWithL2 ──[CHI RN]──▶ CHIHNSubNode ──[CHI RN]──▶ OpenLLC
                         ↑ 对上层呈现为 HN-F              ↑ 对下层呈现为 RN
```

这是一个 **透明直通（pass-through）** 实现：不改变任何 CHI 事务语义，仅提供隔离边界和日志插入点。

---

## 3. 原版架构

```
Core[0]           Core[1]
  │                 │
L2 Cache           L2 Cache
  │  (CHI RN)        │  (CHI RN)
CHILogger          CHILogger
L2[0]_LLC          L2[1]_LLC
  │                 │
  └────────┬────────┘
           ▼
       OpenLLC (HN-F)
           │  (CHI SN)
       OpenNCB (SN)
           │  (AXI4)
         DRAM
```

---

## 4. CHIHNSubNode 设计方案

### 4.1 模块接口

```scala
class CHIHNSubNode(numCores: Int)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val rnFromL2    = Vec(numCores, Flipped(new PortIO))  // HN 侧：L2 连接
    val rnToOpenLLC = Vec(numCores, new PortIO)            // RN 侧：OpenLLC 连接
  })

  for (i <- 0 until numCores) {
    io.rnToOpenLLC(i) <> io.rnFromL2(i)  // 直通连接
  }
}
```

`PortIO`（来自 `coupledL2.tl2chi`）包含以下通道：

| 方向 | 通道 | 用途 |
|------|------|------|
| TX | REQ | L2 发送请求（ReadShared/ReadUnique/WriteBack 等） |
| TX | RSP | L2 发送响应（CompAck 等） |
| TX | DAT | L2 发送数据（WriteData/CopyBack 等） |
| RX | SNP | 接收来自 HN 的 snoop（Snoop/SnpOnce 等） |
| RX | RSP | 接收来自 HN 的响应（Comp/RespSepData 等） |
| RX | DAT | 接收来自 HN 的数据（CompData 等） |

### 4.2 架构图（插入后）

```
Core[0]           Core[1]
  │                 │
L2 Cache           L2 Cache
  │                 │
CHILogger          CHILogger
L2[0]_LLC          L2[1]_LLC
  │                 │
  └────────┬────────┘
           ▼
    ┌──────────────────┐
    │  CHIHNSubNode    │ ← 新增
    │  (HN-F↑ / RN↓)  │
    └────────┬─────────┘
    ┌────────┴─────────┐
    │    CHILogger     │
    │  HN[i]_LLC       │
    └────────┬─────────┘
             ▼
         OpenLLC (HN-F)
             │
         OpenNCB (SN)
             │
           DRAM
```

---

## 5. Top 层集成

修改文件：`src/main/scala/top/Top.scala`

```scala
// 原版：llcLogger.io.down 直接连到 OpenLLC
// chi_openllc_opt.get.io.rn(i) <> llcLogger.io.down

// 插入后：
val hnLogger = CHILogger(s"HN[${i}]_LLC", true)
chi_hnSubNode_opt.get.io.rnFromL2(i) <> llcLogger.io.down
hnLogger.io.up <> chi_hnSubNode_opt.get.io.rnToOpenLLC(i)
chi_openllc_opt.get.io.rn(i) <> hnLogger.io.down
```

---

## 6. 验证策略

### 6.1 仿真测试程序（tests/memrw）

涵盖 9 类内存访问模式：

| 测试 | 覆盖场景 |
|------|---------|
| Byte R/W | CHI ReadUnique + WriteBackFull（字节） |
| Half-word R/W | 16位对齐访问 |
| Word R/W | 32位对齐访问 |
| Double-word R/W | 64位对齐访问 |
| Cache-line R/W | 整行 64B 读写，触发 Comp+Data |
| Stride access | 步长 256B，覆盖 prefetch |
| Block copy | 跨 Cache Set 批量复制 |
| RMW | Read-Modify-Write，测试 MSHR hit-under-miss |
| Write-back eviction | 超过 L2 容量，触发 dirty eviction 路径 |

地址空间划分：
- `0x00000000–0x7FFFFFFF`：MMIO 区域（→ OpenNCB → AXI4）
- `0x80000000+`：主存区域（→ CHIHNSubNode → OpenLLC → OpenNCB → DRAM）

### 6.2 CHILogger 波形验证

```
CHILogger L2[i]_LLC  ← 观察 L2 发出的 REQ/DAT/RSP
CHILogger HN[i]_LLC  ← 观察经过 CHIHNSubNode 后到达 OpenLLC 的流量
```

两侧信号应完全一致（直通模块不修改任何字段）。

---

## 7. 构建与仿真命令

### 7.1 RTL 生成

```bash
make verilog CONFIG=CHIConfig NUM_CORES=2 ISSUE=E.b -j$(nproc)
```

### 7.2 VCS 编译

```bash
make simv CONFIG=CHIConfig NUM_CORES=2 ISSUE=E.b \
  CONSIDER_FSDB=1 \
  -j$(nproc) 2>&1 | tee compile.log
```

### 7.3 运行 hello 验证

```bash
./build/simv \
  +workload=$AM_HOME/../nexus-am/apps/hello/build/hello-riscv64-xs.bin \
  +no-diff \
  +max-cycles=10000000
# 预期输出：HIT GOOD TRAP
```

### 7.4 运行 memrw 测试

```bash
# 编译测试程序
cd tests/memrw
make ARCH=riscv64-xs

# 运行仿真
cd $NOOP_HOME
./build/simv \
  +workload=tests/memrw/build/memrw-riscv64-xs.bin \
  +no-diff \
  +max-cycles=100000000
```

### 7.5 一键脚本

```bash
./scripts/chi-sim.sh tests/memrw/build/memrw-riscv64-xs.bin
```

---

## 8. 已知限制与后续工作

| 项目 | 说明 |
|------|------|
| 直通无仲裁 | 当前 CHIHNSubNode 不做 snoop 合并/地址仲裁 |
| NodeID 未分配 | 未分配独立的 CHI Node ID，复用 L2 的 RN ID |
| difftest 兼容性 | `+no-diff` 模式下无法用 NEMU 验证执行正确性，需重新编译 CHI 版 NEMU |
| KDB/Verdi | `make simv CONSIDER_FSDB=1` 需在 difftest/vcs.mk 中添加 `-kdb`，否则 Verdi 无法加载 KDB 数据库 |
| 多核 snoop 路径 | 当 NUM_CORES > 1 时，L2[0]↔L2[1] 的 snoop 路径需经过 OpenLLC 的 SnoopUnit，CHIHNSubNode 对此路径透明 |
