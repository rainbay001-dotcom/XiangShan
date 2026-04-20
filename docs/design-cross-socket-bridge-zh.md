# 跨 Socket CHI 桥：详细微架构

**状态：** 详细设计  
**依赖于：** `design-two-socket-system.md`（方案 B：每 Socket LLC 配跨 Socket 监听）  
**参考来源：** OpenLLC 源码分析（Directory.scala, MainPipe.scala, Slice.scala, CHIXbar.scala）

---

## 目录

1. [设计背景](#1-设计背景)
2. [OpenLLC 内部结构摘要](#2-openllc-内部结构摘要)
3. [桥架构概览](#3-桥架构概览)
4. [远程目录扩展](#4-远程目录扩展)
5. [跨 Socket 桥（XSBridge）微架构](#5-跨-socket-桥xsbridge微架构)
6. [事务流程](#6-事务流程)
7. [事务 ID 管理](#7-事务-id-管理)
8. [流控与排序](#8-流控与排序)
9. [串行化链路层](#9-串行化链路层)
10. [与 DualSocketTop 的集成](#10-与-dualsockettop-的集成)
11. [边界情况与冒险](#11-边界情况与冒险)
12. [参数与可配置性](#12-参数与可配置性)
13. [验证计划](#13-验证计划)

---

## 1. 设计背景

### 1.1 问题所在

在双 Socket XiangShan 系统（来自系统设计文档的方案 B）中，每个 Socket 具有：
- N 个带私有 L1/L2 缓存的核心（L2 = CHI RN-F）
- 一个 OpenLLC 实例（CHI HN-F），充当 L3 + 监听过滤器
- 一个 OpenNCB 桥（CHI SN-F → AXI4），用于 DDR 访问

问题在于：当 Socket 0 上的 Core A 访问某个位于 Socket 1 的 DDR 范围中的地址时，没有路径能使该请求到达 Socket 1 的 OpenLLC。类似地，当 Socket 1 写入一条 Socket 0 已缓存的行时，没有机制去使 Socket 0 的副本失效。

### 1.2 桥必须完成的任务

跨 Socket 桥位于两个 Socket 的 CHI 互连之间，提供：

1. **请求转发：** 将地址映射到远端 Socket 内存范围的 CHI 请求路由到远端 OpenLLC（HN-F）
2. **监听转发：** 当本地 HN-F 需要监听一条可能被远端 Socket 缓存的行时，跨链路转发该监听
3. **响应返回：** 将 CompData、SnpResp 和 CompAck 返回给发起方
4. **排序保证：** 在 Socket 边界上维持 CHI 排序规则
5. **流控：** 在远端 Socket 或链路饱和时施加反压

### 1.3 设计思路

我们采用**两层**设计：

- **第 1 层 —— OpenLLC 中的远程目录扩展：** 每个目录项增加 1 bit "remote present" 标志，告知本地 HN-F 某一行可能被远端 Socket 缓存。这避免了不必要的跨 Socket 监听。
- **第 2 层 —— XSBridge 模块：** 一个 CHI 到 CHI 的桥，作为附加的 RN-F 端口连接到每个 OpenLLC，跨链路转发事务。

---

## 2. OpenLLC 内部结构摘要

以下是阅读实际源码后的发现。这些细节直接影响桥的设计。

### 2.1 目录结构

**来源：** `openLLC/src/main/scala/openLLC/Directory.scala`

OpenLLC 维护两个并行目录：

| 目录 | 模块 | 追踪对象 | 项类型 |
|-----------|--------|--------|------------|
| **Self directory（自身目录）** | `selfDir` (SubDirectory[SelfMetaEntry]) | LLC 自身缓存了什么 | `{valid: Bool, dirty: Bool}` |
| **Client directory（客户端目录，监听过滤器）** | `clientDir` (SubDirectory[Vec[ClientMetaEntry]]) | 哪些 RN-F 持有副本 | `Vec(numRNs, {valid: Bool})` |

Client directory 实际上是**每行的共享者位掩码**——每个 RN-F 一个 valid bit。RN-F 数量 `numRNs = cacheParams.clientCaches.size`（`LLCParam.scala:89`）。

**关键观察：** client directory 以 `numRNs` 为参数。要追踪远端 Socket 存在性，我们可以：
- (a) 将桥作为附加的 RN-F（将 `numRNs` 加 1），或
- (b) 在 client directory 之外单独添加一个 `remotePresent` bit

方案 (a) 更清晰——对 OpenLLC 而言，桥只是另一个 RN-F。当桥的位在 `snpVec` 中被置上时，监听会被发送到桥端口，再由其跨 Socket 链路转发。

### 2.2 监听生成（MainPipe 第 4 级）

**来源：** `openLLC/src/main/scala/openLLC/MainPipe.scala:281-344`

第 4 级的监听判定逻辑：

```
clients_valids_vec_s4 = Vec of each RN-F's valid bit from client directory
peerRNs_valids_vec_s4 = same, but with the requesting RN-F masked out

need_snoop = replace_snoop OR request_snoop

replace_snoop: client dir miss but other RN-Fs hold the evicted line
request_snoop: exclusive/invalidate req + peers hold it
               OR shared read + LLC miss (need data from an RN-F)
               OR clean req + exactly one peer RN-F holds it uniquely

snpVec := replace_snoop ? all_sharers : peer_sharers_only
```

**关键观察：** `snpVec` 是 `Vec(numRNs, Bool())`——它选择哪些 RN-F 端口接收监听。若桥是 RN-F 索引 `numRNs-1`，那么当 `snpVec(numRNs-1)` 被置位时，监听发往桥端口，桥再转发到远端 Socket。

### 2.3 监听操作码选择

来自 MainPipe.scala:321-336 的映射：

| 请求类型 | 监听操作码 |
|-------------|--------------|
| 替换驱逐 | SnpUnique（始终） |
| ReadUnique | SnpUnique |
| ReadNotSharedDirty | SnpNotSharedDirty |
| MakeUnique / MakeInvalid | SnpMakeInvalid |
| CleanInvalid | SnpCleanInvalid |
| CleanShared | SnpCleanShared |

### 2.4 响应收集

**来源：** `openLLC/src/main/scala/openLLC/ResponseUnit.scala:27-55`

每个响应项追踪：
```
ResponseState:
  s_comp       — need to send Comp
  s_urgentRead — need urgent memory read
  w_datRsp     — waiting for data response
  w_snpRsp     — waiting for snoop response
  w_compack    — waiting for CompAck
  w_comp       — waiting for completion
```

监听响应通过 `txnID` 匹配。ResponseUnit 从 `rnRxrsp`/`rnRxdat` 端口收集 SnpResp/SnpRespData 并更新对应项。

**关键观察：** ResponseUnit 已经处理等待来自多个 RN-F 的监听响应。将桥作为另一个 RN-F 后，跨 Socket 监听响应会通过同一收集逻辑流经——ResponseUnit 无需改动。

### 2.5 OpenLLC 顶层 IO

**来源：** `openLLC/src/main/scala/openLLC/OpenLLC.scala:37-46`

```scala
val io = IO(new Bundle {
  val rn = Vec(numRNs, Flipped(new PortIO))    // RN-F ports (one per client)
  val sn = new NoSnpPortIO                      // SN-F port (to memory)
  val nodeID = Input(UInt())
  ...
})
```

OpenLLC 通过 `Vec(numRNs, ...)` 已经支持多个 RN-F 端口。将桥作为 RN-F 端口 `numRNs`（通过向 `clientCaches` 再添加一个 `L2Param`）是最小侵入的集成路径。

### 2.6 Slice 流水线

**来源：** `openLLC/src/main/scala/openLLC/Slice.scala:27-151`

```
RXREQ → RequestBuffer → RequestArb → MainPipe (6 stages)
                                        ├── Directory read (stage 1-3)
                                        ├── Snoop decision (stage 4) → SnoopUnit → TXSNP
                                        ├── Refill/Memory (stage 4)  → RefillUnit / MemUnit
                                        └── Response (stage 4/6)     → ResponseUnit → TXRSP/TXDAT
```

TXSNP 输出经过 `SnoopUnit` → `TXSNP` 模块，驱动 `txUp.snp` 通道。`RNXbar`（`CHIXbar.scala`）使用 `snpMask`（即从 MainPipe 通过 SnoopUnit 传递的 `snpVec`）将监听解复用到正确的 RN-F 端口。

---

## 3. 桥架构概览

### 3.1 高层拓扑

```
Socket 0                                                    Socket 1
┌──────────────────────┐                              ┌──────────────────────┐
│  Core 0  Core 1  ... │                              │  Core N  Core N+1 ...│
│  (RN-F)  (RN-F)      │                              │  (RN-F)  (RN-F)      │
│      │      │         │                              │      │      │        │
│  ┌───┴──────┴───┐     │                              │  ┌───┴──────┴───┐    │
│  │              │     │                              │  │              │    │
│  │   OpenLLC 0  │     │                              │  │   OpenLLC 1  │    │
│  │   (HN-F)    │     │                              │  │   (HN-F)    │    │
│  │              │     │                              │  │              │    │
│  │  rn(0..N-1) │     │                              │  │  rn(0..N-1) │    │
│  │  rn(N) ─────┼──┐  │                              │  │  rn(N) ─────┼─┐  │
│  └──────────────┘  │  │                              │  └──────────────┘ │  │
│                    │  │                              │                   │  │
│  ┌─────────────────┴──┼──────────────────────────────┼───────────────────┴┐ │
│  │              XSBridge                             │                    │ │
│  │                    │                              │                    │ │
│  │  ┌──────────────┐  │    Serial / Parallel Link    │  ┌──────────────┐  │ │
│  │  │ BridgeAgent 0│  │◄════════════════════════════►│  │ BridgeAgent 1│  │ │
│  │  │ (CHI PortIO) │  │                              │  │ (CHI PortIO) │  │ │
│  │  └──────────────┘  │                              │  └──────────────┘  │ │
│  └────────────────────┘                              └────────────────────┘ │
└──────────────────────┘                              └──────────────────────┘
```

### 3.2 桥作为 RN-F

每个 `BridgeAgent` 作为 RN-F 端口索引 `N`（其中 N = 本地核心数）连接到其本地 OpenLLC。从 OpenLLC 的角度看，桥就是另一个可以持有缓存行副本、需要被监听的 L2 缓存。

桥还作为**代理请求者**——当远端 Socket 发起针对本地地址的 ReadShared/ReadUnique 时，桥将该请求注入到本地 OpenLLC 的 RXREQ 通道，就如同来自本地 RN-F 一样。

### 3.3 桥作为代理 HN-F

从远端 Socket 的角度看，桥作为 HN-F 服务针对本地 Socket 内存范围的请求。桥接收来自远端 Socket 路由层的 CHI 请求并转发到本地 OpenLLC。

---

## 4. 远程目录扩展

### 4.1 思路：桥作为附加的 RN-F

与其修改 OpenLLC 内部的 `ClientMetaEntry` 结构，不如将桥作为第 `(numRNs)` 个 RN-F 端口添加。这意味着：

1. 在 `clientCaches` 参数中追加一个代表桥的合成 `L2Param`
2. client directory 的共享者向量自动增长 1 bit
3. 当某一行被远端 Socket 通过桥取到时，桥的 RN-F 索引在 client directory 中被标记为 "valid"
4. 当本地 HN-F 需要使该行失效时，`snpVec(bridge_idx)` 被置位，向桥端口发送监听

**配置修改：**

```scala
// In DualSocketTop, when configuring OpenLLC params:
val bridgeL2Param = L2Param(
  ways = 1,           // Minimal — bridge doesn't have a real cache
  sets = 1,           // Minimal
  clientCaches = Nil  // No sub-clients
)

// Append to existing clientCaches
val llcParams = baseOpenLLCParam.copy(
  clientCaches = baseOpenLLCParam.clientCaches :+ bridgeL2Param
)
```

client directory（监听过滤器）维度由 `clientCaches` 中**最大的** L2 派生。桥的合成项不会显著改变监听过滤器维度——它只是为每一项增加一个 valid bit。

### 4.2 跨 Socket 操作时发生的事

**远端读（Socket 1 读 Socket 0 内存）：**
1. Socket 1 的路由层将 ReadShared 发送到 XSBridge
2. XSBridge 将 ReadShared 注入 Socket 0 OpenLLC 的 `rn(bridge_idx)` RXREQ
3. Socket 0 OpenLLC 像处理任何其他 RN-F 请求一样处理它
4. 若其他本地 L2 持有该行，OpenLLC 正常监听它们
5. OpenLLC 将 CompData 返回给桥的 RN-F 端口
6. OpenLLC 标记 `clientDir[bridge_idx].valid = true`（该行现由 "远端 Socket 缓存"）
7. XSBridge 将 CompData 转发回 Socket 1

**随后的本地写（Socket 0 核心写相同行）：**
1. Socket 0 核心向 Socket 0 OpenLLC 发起 MakeUnique
2. OpenLLC 看到 `clientDir[bridge_idx].valid = true`（远端有副本）
3. OpenLLC 向桥端口发送 SnpMakeInvalid（`snpVec(bridge_idx) = true`）
4. XSBridge 将 SnpMakeInvalid 转发到 Socket 1
5. Socket 1 处理监听（可能需要为脏数据监听自己的 L2）
6. Socket 1 通过桥返回 SnpResp(I) 或 SnpRespData(I)
7. OpenLLC 清除 `clientDir[bridge_idx].valid = false`
8. OpenLLC 授权 MakeUnique 给请求核心

### 4.3 为何无需修改 OpenLLC 源码即可实现

现有 OpenLLC 代码对 `numRNs` 完全泛化：
- `clientDir` 是 `SubDirectory[Vec[ClientMetaEntry]]`，使用 `Vec.fill(numRNs)(...)`——增加一个 RN 只是为向量多加一位
- MainPipe 中的 `snpVec` 是 `Vec(numRNs, Bool())`——自动包含桥索引
- `RNXbar` 基于 `snpMask` 将监听路由到 RN 端口——桥在其掩码位被置位时接收监听
- 流水线中任何位置都没有对 numRNs 的硬编码假设

唯一的变化在于**精化时**传给 OpenLLC 的**配置**。

---

## 5. 跨 Socket 桥（XSBridge）微架构

### 5.1 模块层次结构

```
XSBridge
├── BridgeAgent (×2, one per socket side)
│   ├── RequestTracker           — Track outstanding cross-socket requests
│   │   └── entries: Vec(N, RequestEntry)
│   ├── SnoopTracker             — Track outstanding cross-socket snoops
│   │   └── entries: Vec(M, SnoopEntry)
│   ├── ResponseAssembler        — Collect multi-beat data responses
│   │   └── entries: Vec(N, DataBuffer)
│   ├── TxnIDMapper              — Remap txnID across socket boundary
│   └── FlowController           — L-Credit management for bridge port
├── LinkLayer                    — Serialization / clock domain crossing
│   ├── TxQueue (per channel)    — Outbound FIFO + serializer
│   ├── RxQueue (per channel)    — Inbound deserializer + FIFO
│   └── LinkStateCtrl            — Link activation FSM
└── ConfigRegs                   — MMIO-mapped bridge status/config
```

### 5.2 BridgeAgent 细节

每个 BridgeAgent 有两个接口：

- **本地 CHI 端口** —— 作为 RN-F 连接到本地 OpenLLC（PortIO，相对于常规方向翻转）
- **远端通道** —— 到 LinkLayer 的内部接口（请求/响应 FIFO）

```
BridgeAgent (Socket 0 side)
                                                    To/from LinkLayer
                                                    (→ remote socket)
Local OpenLLC rn(N) port                            
    ┌──────────────┐                               
    │              │    ┌──────────────────┐         ┌────────────┐
    │ rx.snp ──────┼───►│ SnoopTracker     │────────►│ TX: SNP    │──►
    │              │    └──────────────────┘         └────────────┘
    │              │                                  ┌────────────┐
    │ rx.rsp ──────┼────────────────────────────────►│ TX: RSP    │──►
    │              │                                  └────────────┘
    │              │                                  ┌────────────┐
    │ rx.dat ──────┼────────────────────────────────►│ TX: DAT    │──►
    │              │                                  └────────────┘
    │              │                                 
    │              │    ┌──────────────────┐         ┌────────────┐
    │ tx.req ◄─────┼────│ RequestTracker   │◄────────│ RX: REQ    │◄──
    │              │    └──────────────────┘         └────────────┘
    │              │                                  ┌────────────┐
    │ tx.rsp ◄─────┼────────────────────────────────│ RX: RSP    │◄──
    │              │                                  └────────────┘
    │              │    ┌──────────────────┐         ┌────────────┐
    │ tx.dat ◄─────┼────│ ResponseAssembler│◄────────│ RX: DAT    │◄──
    │              │    └──────────────────┘         └────────────┘
    └──────────────┘
```

**远端读（来自远端 Socket 的请求）数据流：**
1. 远端 Socket 发送 ReadShared → 到达 RX: REQ
2. RequestTracker 分配表项，重映射 txnID，通过 `tx.req` 注入本地 OpenLLC
3. 本地 OpenLLC 处理，在 `rx.dat` 上返回 CompData
4. ResponseAssembler 收集数据节拍，将 txnID 重映射回原值
5. 通过 TX: DAT 将数据发送到远端 Socket

**对被远端缓存行进行本地监听的数据流：**
1. 本地 OpenLLC 在 `rx.snp` 上发送 SnpUnique（因为桥的 clientDir 位被置位）
2. SnoopTracker 分配表项，通过 TX: SNP 将监听转发到远端 Socket
3. 远端 Socket 处理监听，通过 RX: RSP/DAT 返回 SnpResp/SnpRespData
4. BridgeAgent 通过 `tx.rsp`/`tx.dat` 将响应转发到本地 OpenLLC

### 5.3 RequestTracker

```
RequestEntry:
  valid       : Bool
  state       : RequestState
  localTxnID  : UInt        — txnID used toward local OpenLLC
  remoteTxnID : UInt        — original txnID from remote socket
  remoteSrcID : UInt        — original srcID from remote socket
  addr        : UInt(48.W)  — request address (for conflict detection)
  opcode      : UInt        — CHI request opcode
  beatValids  : Vec(2, Bool)  — for 64B line / 32B beats
  data        : DSBlock     — assembled response data (if needed)

RequestState:
  s_issue     : Bool        — need to issue request to local OpenLLC
  w_comp      : Bool        — waiting for Comp/CompData from local OpenLLC
  w_data      : Bool        — waiting for data beats
  s_forward   : Bool        — need to forward response to remote socket
  w_compack   : Bool        — waiting for CompAck from remote (if applicable)
```

**容量：** 16 项（匹配 OpenLLC 的 `mshrs.response = 16`）

**冲突检测：** 分配新表项前，检查是否已有另一项具有相同地址。若有，阻塞新请求（防止相同行上的乱序冒险）。

### 5.4 SnoopTracker

```
SnoopEntry:
  valid       : Bool
  localTxnID  : UInt        — txnID from local OpenLLC's snoop
  remoteTxnID : UInt        — txnID used toward remote socket
  addr        : UInt(45.W)  — snoop address (addr[MPA-1:3])
  opcode      : UInt        — snoop opcode
  retToSrc    : Bool        — whether data return is expected
  w_snpResp   : Bool        — waiting for snoop response from remote
```

**容量：** 16 项（匹配 OpenLLC 的 `mshrs.snoop = 16`）

当本地 OpenLLC 向桥的 RN-F 端口发送监听时，SnoopTracker 捕获它，分配表项，并跨链路转发监听。远端 Socket 的桥侧将监听注入远端 OpenLLC，后者再监听远端的 L2 缓存。监听响应链路通过桥返回。

### 5.5 TxnIDMapper

CHI 要求 `txnID` 在每个源内唯一。桥在 Socket 间转发时必须重映射事务 ID，原因是：

- 远端 Socket 的原始 txnID 可能与本地 txnID 冲突
- 桥在向本地 OpenLLC 侧使用自己的 txnID 空间

**实现：** 按本地 txnID 索引的简单查找表 → (remoteTxnID, remoteSrcID)。RequestTracker 和 SnoopTracker 表项兼作映射表。

```
On request injection to local OpenLLC:
  tx.req.srcID := bridge_nodeID        (bridge's own NodeID)
  tx.req.txnID := requestTracker.allocIdx  (local txnID = entry index)

On response forwarding to remote socket:
  entry = requestTracker(resp.txnID)   (look up by local txnID)
  tx_to_remote.dat.tgtID := entry.remoteSrcID
  tx_to_remote.dat.txnID := entry.remoteTxnID
```

---

## 6. 事务流程

### 6.1 远端读：Socket 1 核心读 Socket 0 内存

```
S1 Core    S1 OpenLLC    S1 BridgeAgent    Link    S0 BridgeAgent    S0 OpenLLC    S0 L2s
  │            │               │             │            │               │           │
  │─ReadShared►│               │             │            │               │           │
  │            │(addr maps to  │             │            │               │           │
  │            │ remote S0)    │             │            │               │           │
  │            │──ReadShared──►│             │            │               │           │
  │            │               │══REQ flit══►│            │               │           │
  │            │               │             │ (remap txnID, inject req)  │           │
  │            │               │             │            │──ReadShared──►│           │
  │            │               │             │            │               │(dir lookup)│
  │            │               │             │            │               │─SnpShared─►│
  │            │               │             │            │               │           │
  │            │               │             │            │               │◄SnpResp───│
  │            │               │             │            │◄──CompData────│           │
  │            │               │             │◄══DAT flit═│               │           │
  │            │               │(remap txnID back)        │               │           │
  │            │◄──CompData────│             │            │               │           │
  │◄CompData───│               │             │            │               │           │
  │─CompAck───►│               │             │            │               │           │
  │            │──CompAck─────►│             │            │               │           │
  │            │               │══RSP flit══►│            │               │           │
  │            │               │             │            │──CompAck─────►│           │
  │            │               │             │            │               │(update dir)│
```

**之后的目录状态：** Socket 0 OpenLLC 为该行标记 `clientDir[bridge_idx].valid = true`。未来的本地写会监听桥。

### 6.2 跨 Socket 失效：Socket 0 核心写被 Socket 1 缓存的行

```
S0 Core    S0 OpenLLC    S0 BridgeAgent    Link    S1 BridgeAgent    S1 OpenLLC    S1 L2s
  │            │               │             │            │               │           │
  │─MakeUnique►│               │             │            │               │           │
  │            │(dir shows     │             │            │               │           │
  │            │ bridge has it)│             │            │               │           │
  │            │─SnpMakeInvld─►│             │            │               │           │
  │            │               │══SNP flit══►│            │               │           │
  │            │               │             │(inject snoop into S1 LLC)  │           │
  │            │               │             │            │─SnpMakeInvld─►│           │
  │            │               │             │            │               │─SnpMkInv─►│
  │            │               │             │            │               │           │
  │            │               │             │            │               │◄SnpResp(I)│
  │            │               │             │            │◄─SnpResp(I)───│           │
  │            │               │             │◄══RSP flit═│               │           │
  │            │◄──SnpResp(I)──│             │            │               │           │
  │            │(clear bridge  │             │            │               │           │
  │            │ dir bit)      │             │            │               │           │
  │◄──Comp─────│               │             │            │               │           │
```

### 6.3 带数据返回的跨 Socket 监听

当远端缓存持有脏数据时：

```
S0 Core    S0 OpenLLC    S0 BridgeAgent    Link    S1 BridgeAgent    S1 OpenLLC    S1 L2
  │            │               │             │            │               │          │
  │─ReadUnique►│               │             │            │               │          │
  │            │(dir: bridge   │             │            │               │          │
  │            │ has line, UD) │             │            │               │          │
  │            │──SnpUnique───►│             │            │               │          │
  │            │ (retToSrc=1)  │══SNP flit══►│            │               │          │
  │            │               │             │            │──SnpUnique───►│          │
  │            │               │             │            │ (retToSrc=1)  │─SnpUnq──►│
  │            │               │             │            │               │          │
  │            │               │             │            │               │◄SnpRspDt─│
  │            │               │             │            │               │ (UD→I)   │
  │            │               │             │            │◄─SnpRespData──│          │
  │            │               │             │◄══DAT flit═│               │          │
  │            │◄─SnpRespData──│             │            │               │          │
  │            │               │             │            │               │          │
  │            │(has dirty data│             │            │               │          │
  │            │ from remote)  │             │            │               │          │
  │◄─CompData──│               │             │            │               │          │
  │ (UD)       │               │             │            │               │          │
```

### 6.4 背靠背跨 Socket 所有权乒乓

这是最坏的性能场景——两个不同 Socket 上的核心交替写入相同缓存行（伪共享或真共享）。

桥必须处理以下情形：
1. Socket 0 持有该行 UD，Socket 1 发起请求
2. Socket 1 的请求仍在传输中时，Socket 0 又要回它

**处理方式：** RequestTracker 中的基于地址的冲突检测。第二个请求阻塞直到第一个完成，防止对同一地址的事务重叠。

---

## 7. 事务 ID 管理

### 7.1 TxnID 空间

存在三个独立的 txnID 空间：

| 空间 | 所有者 | 宽度 | 用途 |
|-------|-------|-------|-------|
| 本地 L2 txnID | 每个 L2 缓存（RN-F） | 12 bits (E.b) | L2 → 本地 OpenLLC |
| 桥本地 txnID | BridgeAgent | 4 bits (16 项) | 桥 → 本地 OpenLLC |
| 远端转发 txnID | 远端 BridgeAgent | 4 bits | 桥 → 远端 OpenLLC |

### 7.2 重映射规则

**请求转发（远端 → 本地 OpenLLC）：**
```
Incoming from remote:  srcID=<remote_core>, txnID=<remote_txn>
Injected to local LLC: srcID=<bridge_nodeID>, txnID=<tracker_entry_idx>
Mapping stored in:     RequestTracker[entry_idx].{remoteSrcID, remoteTxnID}
```

**响应转发（本地 OpenLLC → 远端）：**
```
Received from local:   tgtID=<bridge_nodeID>, txnID=<tracker_entry_idx>
Forwarded to remote:   tgtID=<original_remote_srcID>, txnID=<original_remote_txnID>
Looked up from:        RequestTracker[resp.txnID].{remoteSrcID, remoteTxnID}
```

**监听转发（本地 OpenLLC → 远端）：**
```
Received from local:   srcID=<local_llc_nodeID>, txnID=<llc_snp_txnID>
Forwarded to remote:   srcID=<bridge_nodeID>, txnID=<snoop_tracker_entry_idx>
Mapping stored in:     SnoopTracker[entry_idx].localTxnID
```

### 7.3 代为监听（Snoop-on-Behalf-Of）

当桥从本地 OpenLLC 收到监听（因为 `clientDir[bridge_idx]` 被置位）时，桥必须判断哪些远端 L2 缓存实际持有该行。有两个选项：

**方案 A —— 将监听转发到远端 OpenLLC（推荐）：**
桥向远端 OpenLLC 发送一个 CHI 请求（ReadNoSnp 或自定义监听转发），后者拥有精确的 client directory，知道该监听其哪个 L2。

**方案 B —— 直接监听远端 L2：**
桥需要拥有自己的远端监听过滤器副本。这既复杂又冗余——远端 OpenLLC 已经拥有这些信息。

我们选择**方案 A**。桥将监听注入远端侧的 OpenLLC，由它通过自己的 MainPipe 处理并监听相应的本地 L2 缓存。这意味着桥在远端侧也作为一个 CHI RN-F。

**实现细节：** 桥侧的监听作为一个特殊请求注入，由远端 OpenLLC 处理。由于 OpenLLC 的 RXREQ 只处理标准 CHI 请求操作码，我们采用两步法：

1. 桥从本地 OpenLLC 接收 SnpUnique(addr=X)
2. 桥向远端 OpenLLC 发送 CleanInvalid(addr=X) 或自定义的反向失效请求
3. 远端 OpenLLC 处理它，监听其本地 L2，返回响应
4. 桥将 SnpResp/SnpRespData 返回给本地 OpenLLC

对于 `retToSrc=1` 的 `SnpUnique`（需要数据返回），桥在远端侧使用 `ReadUnique` 以强制远端 OpenLLC 从其 L2 取回数据并返回。

**监听到请求的映射：**

| 本地监听 | 远端请求 | 原因 |
|------------|----------------|-----------|
| SnpUnique (retToSrc=0) | MakeInvalid | 失效远端副本，不需数据 |
| SnpUnique (retToSrc=1) | ReadUnique | 获取数据 + 失效 |
| SnpMakeInvalid | MakeInvalid | 失效，不需数据 |
| SnpCleanInvalid | CleanInvalid | 写回脏数据，失效 |
| SnpCleanShared | CleanShared | 写回脏数据，保留共享 |
| SnpNotSharedDirty | ReadNotSharedDirty | 降级，返回数据 |
| SnpShared | ReadShared | 读共享副本 |

---

## 8. 流控与排序

### 8.1 L-Credit 流控

XiangShan 的 CHI 实现使用 **L-Credit（Link Credit）** 流控，而非基于 RetryAck 的流控。

**来源：** `coupledL2/src/main/scala/coupledL2/tl2chi/chi/LinkLayer.scala:133-324`

每个通道都有一个 credit 池（默认 4，最大 15）。接收方在有缓冲空间时向发送方发放 L-Credit。发送方只有在持有 credit 时才能发送 flit。

桥的本地 CHI 端口使用相同的 L-Credit 机制：
- 桥 → OpenLLC (tx.req/rsp/dat)：桥从 OpenLLC 的 link monitor 消费 credit
- OpenLLC → 桥 (rx.snp/rsp/dat)：OpenLLC 从桥的 link monitor 消费 credit

**桥 credit 分配：**
- TX REQ credits：4（匹配 L2 默认值）
- TX RSP credits：4
- TX DAT credits：4
- RX SNP credits：16（更高——桥可能接收到监听突发）
- RX RSP credits：4
- RX DAT credits：4

### 8.2 跨链路流控

Socket 之间的链路有自己的流控层。选项：

**并行链路（同一芯片 / FPGA）：** 使用 CHI 异步桥模式（来自 `coupledL2/tl2chi/chi/AsyncBridge.scala` 的 `CHIAsyncBridgeSource/Sink`），搭配影子缓冲（深度 16）+ 异步队列（深度 4，3 级同步器）。

**串行链路（芯片到芯片）：** 实现基于包的协议，包括：
- 包级流控（每种包类型的 credit）
- CRC 错误时的包重传（对 SerDes 链路）
- REQ、RSP、DAT、SNP 的独立虚拟通道以防止协议死锁

### 8.3 排序保证

CHI 要求特定的排序保证：

1. **请求排序：** 来自同一源、到同一地址的请求必须按序处理。RequestTracker 通过地址冲突检测强制执行。

2. **监听先于响应：** 对地址 X 的监听必须在针对 X 的请求响应发送之前处理。桥通过按 FIFO 顺序处理每个地址的监听来保留这一特性。

3. **CompAck 排序：** CompAck 必须在 CompData 之后到达。桥只有在转发了对应的 CompData 之后才转发 CompAck。

4. **无死锁：** 桥不得在 REQ 和 SNP 通道之间造成循环依赖。通过为请求和监听提供独立的缓冲资源，并允许监听旁路被阻塞的请求来实现。

### 8.4 死锁预防

经典的 NUMA 一致性死锁发生于：
- Socket 0 向 Socket 1 发送请求（消耗请求缓冲）
- Socket 1 为服务该请求需要监听 Socket 0（发回监听）
- 由于自身请求被阻塞，Socket 0 的监听缓冲已满

**方案：** 请求和监听使用独立的、尺寸独立的缓冲，监听相对请求在链路带宽上具有严格更高优先级。即使 RequestTracker 已满，SnoopTracker 也始终能够取得进展。

**实现：**
- TX 链路为 REQ 和 SNP 设置独立的虚拟通道
- SNP 通道拥有不能被 REQ 消耗的保留 credit
- 监听响应（RSP/DAT）使用共享通道但带有优先仲裁

---

## 9. 串行化链路层

### 9.1 链路选项

链路层抽象于一个简单的 FIFO 接口之后。桥核心并不关心链路是：

| 链路类型 | 延迟 | 带宽 | 用例 |
|-----------|---------|-----------|----------|
| 直连线（并行 CHI） | 1-2 周期 | 全 CHI 带宽 | 同封装 / FPGA |
| 异步桥（仅 CDC） | 3-5 周期 | 全 CHI 带宽 | 多时钟 FPGA |
| SerDes（CXL / 自定义） | 50-200 ns | 链路受限 | 芯片到芯片 |
| UCIe（chiplet） | 2-5 ns | 非常高 | 2.5D 集成 |

### 9.2 直连线接口（FPGA 原型）

对于 FPGA 原型，使用直接并行连线并可选地加异步桥：

```scala
class XSBridgeLinkIO(implicit p: Parameters) extends Bundle {
  // Socket 0 → Socket 1
  val tx_req = DecoupledIO(new CHIREQ())
  val tx_rsp = DecoupledIO(new CHIRSP())
  val tx_dat = DecoupledIO(new CHIDAT())
  val tx_snp = DecoupledIO(new CHISNP())

  // Socket 1 → Socket 0
  val rx_req = Flipped(DecoupledIO(new CHIREQ()))
  val rx_rsp = Flipped(DecoupledIO(new CHIRSP()))
  val rx_dat = Flipped(DecoupledIO(new CHIDAT()))
  val rx_snp = Flipped(DecoupledIO(new CHISNP()))
}
```

若 Socket 处于不同时钟域，为每个通道包装 `CHIAsyncBridgeSource`/`CHIAsyncBridgeSink`（在 XiangShan 的核 ↔ NoC 时钟域穿越中已验证）。

### 9.3 串行链路的 Flit 打包

对于串行链路，CHI flit 被打包进链路层包：

```
Packet format:
┌──────┬──────┬────────┬───────────┬─────┐
│ Type │ VCid │ Length │  Payload  │ CRC │
│ 2b   │ 2b   │ 8b    │ variable  │ 32b │
└──────┴──────┴────────┴───────────┴─────┘

Type: 00=REQ, 01=RSP, 10=DAT, 11=SNP
VCid: Virtual channel ID (for multi-VC links)
```

Flit 宽度（CHI E.b）：
- REQ：约 120 位
- RSP：约 50 位
- SNP：约 70 位
- DAT：约 330 位（含 256 位数据）

在 16 GB/s 链路带宽（128 位并行或 128 Gbps SerDes）下：
- REQ flit：约 1 ns
- DAT flit：约 2.6 ns（或 64B 行 2 个节拍）

---

## 10. 与 DualSocketTop 的集成

### 10.1 更新的 DualSocketTop 结构

```scala
class DualSocketTop(implicit p: Parameters) extends RawModule {
  val N = p(NumCoresKey)  // cores per socket

  // Bridge L2Param (synthetic entry for client directory)
  val bridgeL2Param = L2Param(ways = 1, sets = 1, clientCaches = Nil)

  // Socket 0 LLC params: N real L2s + 1 bridge
  val llcParams0 = baseLLCParams.copy(
    clientCaches = (0 until N).map(_ => realL2Param) :+ bridgeL2Param
  )

  // Socket 1 LLC params: same structure
  val llcParams1 = llcParams0.copy()

  // Instantiate
  val tiles0   = (0 until N).map(i => Module(new XSNoCTop()(p0(i))))
  val tiles1   = (0 until N).map(i => Module(new XSNoCTop()(p1(i))))
  val llc0     = Module(new OpenLLC()(withLLC(llcParams0)))
  val llc1     = Module(new OpenLLC()(withLLC(llcParams1)))
  val bridge   = Module(new XSBridge())

  // Connect local L2s to LLC
  for (i <- 0 until N) {
    llc0.io.rn(i) <> tiles0(i).io_chi
    llc1.io.rn(i) <> tiles1(i).io_chi
  }

  // Connect bridge to LLC port N (the extra RN-F port)
  llc0.io.rn(N) <> bridge.io.local(0)
  llc1.io.rn(N) <> bridge.io.local(1)

  // Bridge link (direct wire for FPGA, or through SerDes for ASIC)
  bridge.io.link <> ... // internal, or expose for external PHY

  // Memory
  val ncb0 = Module(new OpenNCB())
  val ncb1 = Module(new OpenNCB())
  ncb0.io.chi <> llc0.io.sn
  ncb1.io.chi <> llc1.io.sn
}
```

### 10.2 地址路由

每个 Socket 的 CHI 路由层（在 `Top.scala` 风格的 `route()` 中）必须包含跨 Socket 地址范围。对于 Socket 0：

```scala
route(tile.io_chi, Map(
  // MMIO: 0x0 - 0x7FFFFFFF → MMIO bridge
  (AddressSet(0x0L, 0x7fffffffL), mmioNodeID),
  // Local DDR: 0x80000000 - 0x3FFFFFFFFF → Local OpenLLC
  (AddressSet(0x80000000L, 0x3f7fffffffL), llcNodeID),
  // Remote DDR: 0x4000000000 - 0x7FFFFFFFFF → Bridge (appears as another HN-F target)
  (AddressSet(0x4000000000L, 0x3fffffffffL), bridgeNodeID)
))
```

但稍等——当前设计中桥是作为 **RN-F** 连接到 OpenLLC，而非作为一个独立于 L2 的路由目标。有两种集成方式：

**方式 A —— 桥作为路由目标（L2 → 桥 → 远端 LLC）：**
当地址映射到远端 Socket 时，L2 直接向桥发送请求。桥再转发到远端 OpenLLC。这要求桥对本地 L2 呈现为 HN-F。

**方式 B —— 桥作为两个 LLC 上的 RN-F（L2 → 本地 LLC → 桥 → 远端 LLC）：**
所有 L2 请求先进入本地 OpenLLC。OpenLLC 识别远端地址（经由 SAM）并转发到桥端口。桥再转发到远端 OpenLLC。

**我们选择方式 B**，原因：
- OpenLLC 已经具有 SAM（`sam: Seq[(AddressSet, Int)]` 见 `LLCParam.scala:52`）
- 本地 OpenLLC 可以在其自身目录中缓存远端数据（充当远端缓存）
- 监听过滤器自然追踪哪些本地 L2 拥有远端数据
- L2 配置更简单——L2 仅看到一个 HN-F（本地 OpenLLC）

然而，这意味着 OpenLLC 必须增强，以将非本地地址的请求转发到桥端口，而不是 SN-F（内存）端口。这通过扩展 SAM 将远端地址路由到桥 RN-F 的响应路径来完成。

**替代方案（更简单的初始方式）：** 使用现有的内存（SN）路径。桥作为与 OpenNCB 并列的另一个 SN-F 连接。OpenLLC 的 MemUnit 将远端地址请求发送到桥而非 DDR。这使用现有的 `NoSnpPortIO`，仅需 SAM 配置，无需 OpenLLC 代码改动。

### 10.3 桥作为 SN-F（更简单的集成）

```
OpenLLC
├── rn(0..N-1)  ← local L2 caches
├── sn          → SNXbar
                  ├── sn(0): OpenNCB → local DDR  (local address range)
                  └── sn(1): XSBridge → remote socket (remote address range)
```

此方式利用了 OpenLLC 中已有的 `SNXbar`。桥作为 SN-F（从节点）提供远端内存范围服务。SAM 中的地址路由将远端地址的 ReadNoSnp/WriteNoSnpFull 导向桥而非 DDR。

**优点：** 无需修改 OpenLLC 代码。桥处理 CHI SN 协议（ReadNoSnp、WriteNoSnpFull、CompData、CompDBIDResp），比完整 RN-F 协议更简单。

**缺点：** 桥无法以此方式直接接收监听。对于 "远端存在" 目录位，我们仍然需要桥作为 RN-F。

### 10.4 混合方式（推荐）

通过**两条**路径将桥连接到 OpenLLC：

1. **作为 RN-F（端口 N）：** 用于接收监听并代表远端 Socket 注入代理请求
2. **作为 SN-F（经由 SAM）：** 用于本地 L2/LLC 针对远端内存地址的请求，通过现有的 MemUnit 路径路由

这以最小的 OpenLLC 修改提供完整功能：
- SAM 配置将远端地址路由到桥 SN 端口
- 桥 RN-F 端口处理监听转发与代理请求
- OpenLLC 的 client directory 通过桥的 RN-F 索引追踪远端存在性

---

## 11. 边界情况与冒险

### 11.1 请求-监听竞争

**场景：** Socket 0 L2 向远端内存发起 ReadShared(X)。请求通过桥传输过程中，Socket 1 核心写 X 并需要监听 Socket 0。

**时间线：**
1. S0：L2 → LLC → 桥：ReadShared(X)（传输中）
2. S1：核心写 X → LLC 向 S1 的桥端口发送 SnpUnique → 转发到 S0
3. S0：监听到达 S0 OpenLLC 并针对桥的 RN-F 端口

**解决方式：** S0 OpenLLC 正确处理此情况——监听目标是桥的 RN-F 端口，桥的 SnoopTracker 处理它。如果桥尚未缓存该行（ReadShared 尚未完成），它立即返回 SnpResp(I)（无可失效）。该 ReadShared 最终到达 S1 的 OpenLLC 时将看到更新后的数据。

### 11.2 同时发起针对相同行的跨 Socket 请求

**场景：** Socket 0 和 Socket 1 同时请求 ReadUnique(X)，其中 X 位于 Socket 0 的内存范围。

**解决方式：** 两个请求都到达 Socket 0 的 OpenLLC（一个直接来自 S0 L2，另一个通过桥）。OpenLLC 的 RequestArb 对它们串行化。第一个获得该行；第二个触发对第一个的监听以转移所有权。

### 11.3 桥饥饿

**场景：** 本地 L2 请求淹没 OpenLLC，使桥注入的请求得不到服务。

**解决方式：** 桥作为 RN-F 端口 N 连接，在 `RNXbar` 仲裁中具有相同优先级。为了公平性，`RNXbar` 使用 `FastArbiter`，提供类似轮询的仲裁。无需特殊优先级——桥公平竞争。

若饥饿成为问题，可在桥的 CHI 请求中配置 QoS 位以表明跨 Socket 流量具有更高优先级。

### 11.4 存在未完成跨 Socket 事务时的断电

**场景：** Socket 1 发起断电（SysCo 退出），而此时有跨 Socket 事务在传输中。

**解决方式：** SysCo 退出前：
1. 桥排空两个方向上所有未完成的请求/监听
2. 桥对远端 Socket client directory 中所有标记为 "bridge-present" 的行发送 CleanInvalid
3. 排空后，桥在其 RN-F 端口上撤销 `syscoreq`
4. 本地 OpenLLC 完成 SysCo 退出握手
5. Socket 可安全断电

---

## 12. 参数与可配置性

```scala
case class XSBridgeParams(
  // Tracker sizes
  requestEntries: Int = 16,       // Outstanding cross-socket requests
  snoopEntries: Int = 16,         // Outstanding cross-socket snoops
  dataBufferEntries: Int = 8,     // Data assembly buffers

  // Link configuration
  linkType: String = "parallel",  // "parallel", "async", "serial"
  linkWidthBits: Int = 256,       // For serial: link data width
  asyncDepth: Int = 4,            // Async queue depth (if async)
  asyncSync: Int = 3,             // CDC synchronizer stages

  // L-Credit config
  txReqCredits: Int = 4,
  txRspCredits: Int = 4,
  txDatCredits: Int = 4,
  rxSnpCredits: Int = 16,
  rxRspCredits: Int = 4,
  rxDatCredits: Int = 4,

  // Timeouts
  requestTimeoutCycles: Int = 20000,
  snoopTimeoutCycles: Int = 10000,

  // Debug
  enablePerfCounters: Boolean = true,
  enableCHILog: Boolean = true
)
```

---

## 13. 验证计划

### 13.1 单元测试（BridgeAgent）

| 测试 | 描述 |
|------|-------------|
| basic_read | 通过桥完成单次 ReadShared，验证数据正确性 |
| basic_write | 通过桥完成 WriteUnique，验证数据已写入远端 DDR |
| txnid_remap | 验证 txnID 重映射来回（无 ID 泄漏） |
| credit_flow | 填满全部 credit，验证反压，排空后恢复 |
| timeout | 注入一个不响应的请求，验证超时触发 |

### 13.2 集成测试（DualSocketTop）

| 测试 | 描述 |
|------|-------------|
| remote_read | S0 核心读 S1 内存，验证数据正确 |
| remote_write | S0 核心写 S1 内存，S1 核心读回 |
| cross_snoop | S0 缓存 S1 行，S1 写之，验证 S0 已失效 |
| dirty_transfer | S0 在 S1 范围内持有 UD 行，S1 读之，验证脏数据传输 |
| false_sharing | 不同 Socket 上的两个核心写入同一行相邻字节 |
| ping_pong | 在 Socket 间交替持有某行所有权（性能测试） |
| atomic_cross | LR/SC 对，其中行在 LR 和 SC 之间跨越 Socket |
| multicore_cross | 4 个核心（每 Socket 2 个）共同访问一个共享数组 |

### 13.3 压力测试

| 测试 | 描述 |
|------|-------------|
| bandwidth | STREAM 基准，测量本地 vs. 远端带宽 |
| latency | 通过远端内存的指针追逐，测量延迟 |
| deadlock | 两个方向同时最大压力 |
| power_cycle | 当 Socket 0 有未完成的远端请求时将 Socket 1 断电 |

### 13.4 协议合规性

- 所有桥端口上的 CHI 协议检查器（验证合法的操作码序列）
- 无孤立事务（每个请求均得到响应）
- 未完成期间无 txnID 重用
- credit 守恒（发放的 credit = 消耗的 credit + 持有的 credit）

---

## 附录：引用的关键源文件

| 文件 | 我们学到了什么 |
|------|-----------------|
| `openLLC/Directory.scala:38-70` | ClientMetaEntry 在每个 RN-F 上就只是 `{valid: Bool}` |
| `openLLC/Directory.scala:312-341` | clientDir 以 `numRNs` 为参数——添加 RN-F 仅需配置 |
| `openLLC/MainPipe.scala:281-344` | 监听判定使用 `snpVec(numRNs)`——桥索引自动包含在内 |
| `openLLC/OpenLLC.scala:37-46` | `io.rn = Vec(numRNs, Flipped(new PortIO))`——桥作为 rn(N) 连接 |
| `openLLC/LLCParam.scala:30-53` | `clientCaches: Seq[L2Param]` 决定 numRNs；`sam` 按地址路由 |
| `openLLC/Slice.scala:27-151` | 完整 Slice 流水线：ReqBuf→ReqArb→MainPipe→{Snp,Resp,Mem,Refill}Unit |
| `openLLC/Common.scala:37-118` | Task bundle：所有 CHI 字段 + snpVec + reqID 用于内部追踪 |
| `openLLC/ResponseUnit.scala:27-55` | ResponseState 追踪 w_snpRsp——自然处理桥的监听响应 |
| `openLLC/utils/CHIXbar.scala:26-115` | RNXbar 按 snpMask 解复用监听——掩码位置位时桥收到监听 |
| `openLLC/utils/TargetBinder.scala:29-90` | `route()` + `bind()`：基于 SAM 的 CHI 地址路由 |
| `openLLC/chi/LinkLayer.scala:58-225` | RNLinkMonitor：L-Credit 流控，srcID/tgtID 改写 |
| `coupledL2/tl2chi/chi/AsyncBridge.scala:157-310` | 影子缓冲 + 异步队列模式用于 CDC |
| `coupledL2/tl2chi/chi/LinkLayer.scala:133-324` | L-Credit 池管理（LCredit2Decoupled、Decoupled2LCredit） |
| `coupledL2/tl2chi/chi/Message.scala:426-583` | 完整的 CHI flit 定义（CHIREQ、CHISNP、CHIDAT、CHIRSP） |
