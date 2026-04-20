# 基于 XiangShan 构建双插槽缓存一致系统

**状态：** 参考设计指南  
**基线：** 启用 CHI 的 XSNoCTop 配置的 XiangShan  
**目标：** 具备完整硬件缓存一致性的双插槽系统

---

## 目录

1. [架构概述](#1-架构概述)
2. [单插槽剖析（XSNoCTop）](#2-单插槽剖析xsnoctop)
3. [CHI 协议接口](#3-chi-协议接口)
4. [双插槽互连架构](#4-双插槽互连架构)
5. [NodeID 分配与地址路由](#5-nodeid-分配与地址路由)
6. [系统地址映射（SAM）](#6-系统地址映射sam)
7. [时钟域与异步桥](#7-时钟域与异步桥)
8. [缓存一致性：目录与监听流程](#8-缓存一致性目录与监听流程)
9. [中断与定时器架构](#9-中断与定时器架构)
10. [跨插槽电源管理](#10-跨插槽电源管理)
11. [RTL 生成与构建流程](#11-rtl-生成与构建流程)
12. [集成模块：DualSocketTop](#12-集成模块dualsockettop)
13. [验证策略](#13-验证策略)
14. [物理设计考量](#14-物理设计考量)
15. [附录：信号参考](#附录信号参考)

---

## 1. 架构概述

### 1.1 系统拓扑

```
┌─────────────────────────────────┐     ┌─────────────────────────────────┐
│          Socket 0               │     │          Socket 1               │
│                                 │     │                                 │
│  ┌───────┐ ┌───────┐           │     │           ┌───────┐ ┌───────┐  │
│  │Core 0 │ │Core 1 │  ...      │     │      ...  │Core N │ │Core N+1│ │
│  │  L1   │ │  L1   │           │     │           │  L1   │ │  L1   │  │
│  └───┬───┘ └───┬───┘           │     │           └───┬───┘ └───┬───┘  │
│      │         │                │     │                │         │      │
│  ┌───┴───┐ ┌───┴───┐           │     │           ┌───┴───┐ ┌───┴───┐  │
│  │  L2   │ │  L2   │           │     │           │  L2   │ │  L2   │  │
│  │(CHI RN)│ │(CHI RN)│          │     │          │(CHI RN)│ │(CHI RN)│ │
│  └───┬───┘ └───┬───┘           │     │           └───┬───┘ └───┬───┘  │
│      │         │                │     │                │         │      │
│      └────┬────┘                │     │                └────┬────┘      │
│           │ CHI                 │     │                     │ CHI       │
│      ┌────┴────┐                │     │                ┌────┴────┐      │
│      │ OpenLLC │                │     │                │ OpenLLC │      │
│      │ (HN-F)  │                │     │                │ (HN-F)  │      │
│      └────┬────┘                │     │                └────┬────┘      │
│           │ CHI SN              │     │                     │ CHI SN    │
│      ┌────┴────┐                │     │                ┌────┴────┐      │
│      │ OpenNCB │                │     │                │ OpenNCB │      │
│      │(CHI→AXI)│                │     │                │(CHI→AXI)│      │
│      └────┬────┘                │     │                └────┬────┘      │
│           │ AXI4                │     │                     │ AXI4      │
└───────────┼─────────────────────┘     └─────────────────────┼──────────┘
            │                                                  │
            ▼                                                  ▼
     ┌──────────┐                                       ┌──────────┐
     │  DDR MC  │                                       │  DDR MC  │
     │ (Local)  │                                       │ (Local)  │
     └──────────┘                                       └──────────┘

                    Cross-Socket CHI Link
            ┌─────────────────────────────────┐
            │                                 │
     Socket 0                           Socket 1
     L2 (CHI RN) ◄════ CHI Fabric ════► L2 (CHI RN)
            │      (External NoC /      │
            │       CXL / Custom)       │
            └─────────────────────────────────┘
```

### 1.2 XiangShan 提供的内容 vs. 必须自行构建的内容

| 组件 | XiangShan 提供 | 必须自行构建/集成 |
|-----------|:--------------------:|:------------------------:|
| 带 L1 缓存的 CPU 核 | 是 | — |
| 带 CHI RN-F 端口的 L2 缓存 | 是 | — |
| OpenLLC（L3，CHI HN-F） | 是 | — |
| OpenNCB（CHI→AXI 桥） | 是 | — |
| CHI 异步时钟域桥 | 是 | — |
| 每 tile 的 CHI `PortIO` 接口 | 是 | — |
| 低功耗状态机（SysCo） | 是 | — |
| 跨插槽 CHI fabric / NoC | — | 是 |
| 跨插槽监听目录 | — | 是（或置于 NoC 内） |
| 芯片间 PHY（SerDes/CXL） | — | 是 |
| 全局中断控制器 | — | 是 |
| 共享 MMIO 路由 | — | 是 |
| Boot ROM / 固件 | — | 是 |

---

## 2. 单插槽剖析（XSNoCTop）

每个插槽实例化为一个 `XSNoCTop` 模块。这是用于多插槽集成的推荐顶层模块
（与 `XSTop` 相对——后者内部包含 LLC 和内存桥）。

源文件：`src/main/scala/top/XSNoCTop.scala`

### 2.1 XSNoCTop 模块层次

```
XSNoCTop
├── BaseXSSocImp (clock, reset, DFT)
├── HasAsyncClockImp (noc_clock, noc_reset, soc_clock, clint_clock)
├── HasXSTile
│   └── XSTileWrap
│       ├── XSTile
│       │   ├── XSCore (CPU pipeline)
│       │   └── L2Top
│       │       └── TL2CHICoupledL2 (L2 cache with CHI port)
│       ├── CHIAsyncBridgeSource (optional)
│       └── Private CLINT (optional)
├── HasXSTileCHIImp → io_chi (CHI PortIO)
├── HasCoreLowPowerImp (power state machine)
└── HasSeperatedBusImp (optional separate TL/AXI bus)
```

### 2.2 XSNoCTop IO 端口

源文件：`XSNoCTop.scala:50-84, 230-238, 266`

```
XSNoCTop IO:
├── clock                    : Input  Clock          — Core clock domain
├── reset                    : Input  AsyncReset     — Core reset
├── noc_clock                : Input  Clock          — NoC clock domain (optional, for async bridge)
├── noc_reset                : Input  AsyncReset     — NoC reset
├── soc_clock                : Input  Clock          — SoC peripheral clock
├── soc_reset                : Input  AsyncReset     — SoC peripheral reset
├── clint_clock              : Input  Clock          — Timer clock
├── clint_reset              : Input  AsyncReset     — Timer reset
├── io_chi                   : Output PortIO         — CHI interface (all channels)
├── io.dft                   : Input  SramBroadcastBundle (optional)
├── io.dft_reset             : Input  DFTResetSignals    (optional)
├── io.lp                    :        LowPowerIO         (optional)
│   ├── i_cpu_sw_rst_n       : Input  Bool           — Software reset (active low)
│   ├── i_cpu_iso_en         : Input  Bool           — Isolation enable
│   ├── i_cpu_pwrdown_req_n  : Input  Bool           — Power-down request
│   ├── o_cpu_pwrdown_ack_n  : Output Bool           — Power-down acknowledge
│   └── o_cpu_no_op          : Output Bool           — CPU idle (safe to power off)
├── tileio.hartId            : Input  UInt           — Hardware thread ID
├── tileio.nodeID            : Input  UInt           — CHI Node ID
├── tileio.riscv_rst_vec     : Input  UInt(48.W)     — Reset vector address
├── tileio.riscv_halt        : Output Bool           — CPU halted
├── tileio.riscv_critical_error : Output Bool        — Critical error
├── tileio.hartResetReq      : Input  Bool           — Per-hart reset request
└── tileio.hartIsInReset     : Output Bool           — Hart is in reset
```

**要点：** `XSNoCTop` 暴露一个原始 CHI `PortIO`——它**不**包含 LLC、
内存桥或外设控制器。这些必须在插槽级或系统级实例化。

### 2.3 对比：XSNoCTop vs XSTop

| 特性 | XSTop | XSNoCTop |
|---------|-------|----------|
| 核数 | 多核（NUM_CORES） | **单 tile** |
| L3 / OpenLLC | 内部实例化 | **不包含** |
| OpenNCB 桥 | 内部实例化 | **不包含** |
| CHI 路由 | 内部（route/bind） | **外部**（原始 io_chi） |
| PLIC / Timer | 包含 | **不包含**（可选私有 CLINT） |
| 内存端口 | 至 DDR MC 的 AXI4 | **无**——通过 CHI |
| 多插槽用途 | 非为此设计 | **专为此设计** |

对于双插槽系统，使用 `XSNoCTop` 作为每 tile 的构建块，并在外部构建
LLC、桥、中断控制器和跨插槽 fabric。

---

## 3. CHI 协议接口

### 3.1 PortIO 结构

源文件：`coupledL2.tl2chi.PortIO`（在 `XSNoCTop.scala:35` 导入）

CHI PortIO 遵循 AMBA CHI 规范，包含以下通道：

```
PortIO
├── tx (Transmit — from this node to the interconnect)
│   ├── req : DecoupledIO[CHIBundleREQ]    — Request channel (ReadNoSnp, WriteNoSnpFull, etc.)
│   ├── rsp : DecoupledIO[CHIBundleRSP]    — Response channel (CompAck, SnpResp, etc.)
│   └── dat : DecoupledIO[CHIBundleDAT]    — Data channel (CompData, SnpRespData, etc.)
├── rx (Receive — from the interconnect to this node)
│   ├── snp : DecoupledIO[CHIBundleSNP]    — Snoop channel (SnpShared, SnpClean, SnpUnique, etc.)
│   │   └── flitpend : Bool               — Flit pending hint (for clock gating)
│   ├── rsp : DecoupledIO[CHIBundleRSP]    — Response channel (RetryAck, Comp, etc.)
│   │   └── flitpend : Bool               — Flit pending hint
│   └── dat : DecoupledIO[CHIBundleDAT]    — Data channel (CompData, etc.)
│       └── flitpend : Bool               — Flit pending hint
├── syscoreq : Output Bool                 — System coherency request
└── syscoack : Input  Bool                 — System coherency acknowledge
```

### 3.2 CHI Issue 版本

源文件：`SoC.scala:90-94`、`Makefile:52`

| CHI Issue | NodeID 位宽 | 最大节点数 | 默认 |
|-----------|-------------|-----------|---------|
| B | 7 bits | 128 | — |
| C | 9 bits | 512 | — |
| **E.b** | **11 bits** | **2048** | **是** |

对于双插槽系统，推荐使用 CHI Issue E.b（默认）——11 位 NodeID 为两个
插槽中所有节点提供了充足的地址空间。

### 3.3 CHI 数据参数

| 参数 | 值 | 源文件 |
|-----------|-------|--------|
| 地址位宽 | 48 bits | `SoC.scala:56` (`PAddrBits`) |
| 数据位宽 | 256 bits | `SoC.scala:141` (`L3OuterBusWidth`) |
| 缓存行 | 64 bytes | `SoC.scala:139` (`L3BlockSize`) |
| 数据校验 | 奇校验 | `Top.scala:127` |
| QoS | 4 bits | 按 CHI 规范 |

### 3.4 系统中的 CHI 节点类型

| 节点 | 类型 | 角色 | XiangShan 模块 |
|------|------|------|-----------------|
| L2 缓存（每核） | RN-F | 全一致性请求者 | `TL2CHICoupledL2` |
| OpenLLC（L3） | HN-F | 全一致性 home 节点、目录 | `OpenLLC` |
| 内存桥 | SN-F | DDR 的从节点 | `OpenNCB` |
| MMIO 桥 | SN-I | I/O 从节点 | `OpenNCB` |

---

## 4. 双插槽互连架构

### 4.1 架构方案

跨插槽一致性互连主要有三种方案：

#### 方案 A：共享 CHI Fabric（推荐用于 FPGA / 小型系统）

```
         ┌──────────────────────────────────────────────────┐
         │              Shared CHI Interconnect              │
         │    (e.g., ARM CMN-700 or custom CHI crossbar)    │
         │                                                  │
         │  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐ │
         │  │ HN-F 0 │  │ HN-F 1 │  │ HN-F 2 │  │ HN-F 3 │ │  ← Directory/LLC banks
         │  └────────┘  └────────┘  └────────┘  └────────┘ │
         │                                                  │
         └──┬──┬──┬──┬──────────────────────┬──┬──┬──┬─────┘
            │  │  │  │                      │  │  │  │
         Socket 0 RN-Fs                  Socket 1 RN-Fs
```

单一扁平的 CHI fabric 跨越两个插槽。OpenLLC 实例在共享 fabric 中作为
HN-F 节点。此方案简单但需要一个 CHI fabric IP。

#### 方案 B：每插槽 LLC + 跨插槽监听（推荐用于 ASIC）

```
  Socket 0                                           Socket 1
  ┌────────────────┐                        ┌────────────────┐
  │ L2s → OpenLLC  │                        │ L2s → OpenLLC  │
  │   (local HN-F) │                        │   (local HN-F) │
  └───────┬────────┘                        └───────┬────────┘
          │ CHI SN                                  │ CHI SN
          ▼                                         ▼
  ┌───────────────┐    Cross-Socket Link    ┌───────────────┐
  │   CXL / CHI   │◄══════════════════════►│   CXL / CHI   │
  │  Bridge + Dir  │    (chip-to-chip)      │  Bridge + Dir  │
  └───────┬───────┘                        └───────┬───────┘
          │ AXI4                                   │ AXI4
          ▼                                        ▼
     Local DDR MC                             Local DDR MC
```

每个插槽有自己的 OpenLLC（L3）。跨插槽桥通过 CHI fabric 向远端插槽的
HN-F 发出监听，以处理远端缓存行请求。这是大多数多插槽服务器处理器
采用的 NUMA 风格方案。

#### 方案 C：系统级基于目录的一致性

系统级目录位于两个插槽之间，跟踪每个缓存行由哪个插槽拥有。此方案最
干净，但需要构建定制的目录控制器。

### 4.2 推荐架构（方案 B）

对于实际的双插槽 XiangShan 系统，推荐方案 B，原因如下：

1. 每个插槽自包含——XiangShan 已提供完整的每插槽栈
   （核 → L2 → OpenLLC → OpenNCB → AXI4 内存）
2. 跨插槽流量仅在远端缓存未命中时发生
3. 每插槽 OpenLLC 作为本地 HN-F/目录
4. 跨插槽链路可以是任意一致性传输（CXL、定制 CHI 桥）

### 4.3 必需的跨插槽桥

跨插槽桥必须实现：

1. **远端读**：Socket 0 L2 在 Socket 0 OpenLLC 未命中 → 桥将 CHI ReadShared
   转发至 Socket 1 OpenLLC → Socket 1 监听其本地 L2 → 返回数据
2. **远端写**：类似流程，使用 WriteUnique/MakeUnique 和失效监听
3. **监听转发**：当 Socket 1 写一条由 Socket 0 拥有的行时，桥向 Socket 0
   发送 SnpUnique
4. **目录状态**：跟踪哪些行被远端缓存（对双插槽系统粗粒度已足够
   ——例如，每个 OpenLLC 中每行一个"remote-present"位）

---

## 5. NodeID 分配与地址路由

### 5.1 NodeID 方案

系统中每个 CHI 节点需要一个唯一的 NodeID。XiangShan 使用
`Top.scala:374-387` 中的约定：

**在单个 XSTop 中（现有约定）：**
```
Core MMIO bridges:  NodeID = NumCores + i        (per-core MMIO)
LLC aggregate:      NodeID = NumCores * 2         (shared LLC)
```

**建议的双插槽 NodeID 方案（CHI E.b，11 位 NodeID）：**

```
Socket 0:
  Core 0 (RN-F):    NodeID = 0
  Core 1 (RN-F):    NodeID = 1
  ...
  Core N-1 (RN-F):  NodeID = N-1
  MMIO bridge 0:    NodeID = N
  MMIO bridge 1:    NodeID = N+1
  ...
  OpenLLC 0 (HN-F): NodeID = 2N
  Memory SN 0:      NodeID = 2N+1

Socket 1:
  Core N (RN-F):    NodeID = 256      (use upper bits to distinguish sockets)
  Core N+1 (RN-F):  NodeID = 257
  ...
  Core 2N-1 (RN-F): NodeID = 256+N-1
  MMIO bridge N:    NodeID = 256+N
  ...
  OpenLLC 1 (HN-F): NodeID = 256+2N
  Memory SN 1:      NodeID = 256+2N+1

Cross-Socket:
  X-Socket bridge:  NodeID = 512
```

使用 bit 8（值 256）来区分 Socket 1 与 Socket 0 可简化地址解码
——互连可根据 NodeID 的高位进行路由。

### 5.2 XiangShan 中的 NodeID 传播

NodeID 从顶层输入向下流到 L2 缓存：

```
XSNoCTop.tileio.nodeID                     ← SET BY INTEGRATOR
  → XSTileWrap.module.io.nodeID
    → XSTile.module.io.nodeID
      → L2Top.module.io.nodeID
        → TL2CHICoupledL2.io_nodeID         ← USED IN CHI TRANSACTIONS
```

源文件：`XSNoCTop.scala:250`、`L2Top.scala:359`

每个 tile 的 `nodeID` 输入必须由集成者（你的 DualSocketTop 模块）驱动
为正确的唯一值。

### 5.3 基于地址的 CHI 路由

在 XSTop 中，CHI 流量按地址路由：

源文件：`Top.scala:372-376`

```scala
route(
  core.module.io.chi.get,
  Map(
    (AddressSet(0x0L, 0x00007fffffffL), NumCores + i)      // MMIO: 0x0 - 0x7FFFFFFF
  ) ++ AddressSet(0x0L, 0xffffffffffffL)
    .subtract(AddressSet(0x0L, 0x00007fffffffL))
    .map(addr => (addr, NumCores * 2)).toMap                // LLC: everything else
)
```

**解读：** `0x0 - 0x7FFFFFFF` 范围内的地址被路由到每核 MMIO 桥。
其余所有地址（主要是 `0x80000000+`）被路由到共享 OpenLLC。

对于双插槽系统，跨插槽桥必须参与此路由：

```
Address                    → Target
0x00000000 - 0x7FFFFFFF    → Local MMIO bridge (per-core)
0x80000000 - 0x3FFFFFFFFFF → Local OpenLLC (if address maps to local DDR)
0x80000000 - 0x3FFFFFFFFFF → Cross-socket bridge (if address maps to remote DDR)
```

---

## 6. 系统地址映射（SAM）

### 6.1 XiangShan 默认地址映射

源文件：`SoC.scala:53-73`

```
0x00000000 - 0x0FFFFFFF   Peripherals (PMA: I/O, non-cacheable)
  0x00000000 - 0x0FFFFFFF   Reserved
  0x10000000 - 0x1FFFFFFF   Flash / Boot ROM (R, W)
  0x20000000 - 0x2FFFFFFF   Executable I/O region (R, W, X)
  0x30010000 - 0x3004FFFF   Device MMIO (R, W)
  0x30050000 - 0x30FFFFFF   GPU space (R, W, may be cacheable)
  0x38000000 - 0x3803FFFF   TIMER (per-SoC or per-socket)
  0x38010000 - 0x38010FFF   BEU (Bus Error Unit)
  0x38020000 - 0x38021FFF   Debug Module (R, W / R, W, X)
  0x38022000 - 0x38FFFFFF   Extended debug (R, W)
  0x39000000 - 0x39001FFF   Additional I/O (R, W)
  0x39002000 - 0x39FFFFFF   Extended I/O (R, W)
  0x3A000000 - 0x3A000FFF   PLL control
  0x3A800000                IMSIC (M-mode)
  0x3B000000                IMSIC (S/G-mode)
  0x3C000000 - 0x3FFFFFFF   PLIC
  0x40600000 - 0x4060003F   UART

0x80000000 - 0x7FFFFFFFFFF  Main Memory (cacheable, atomic, R, W, X)
  ← This is where DDR lives (PmemRanges)
```

### 6.2 建议的双插槽地址映射

对于具有独立 DDR 控制器的两个插槽，将内存空间拆分：

```
Shared Peripherals:
  0x00000000 - 0x7FFFFFFF    Same as single-socket (MMIO, boot ROM, PLIC, etc.)

Socket 0 Local DDR:
  0x00_8000_0000 - 0x3F_FFFF_FFFF    Socket 0 memory (up to 255 GB)

Socket 1 Local DDR:
  0x40_0000_0000 - 0x7F_FFFF_FFFF    Socket 1 memory (up to 255 GB)

  (Above 0x80_0000_0000: reserved or extended)
```

**路由规则：** 每个 OpenLLC/HN-F 拥有一段连续的地址范围。当 L2（RN-F）
发出请求时：

- 若地址落在本地插槽的范围内 → 由本地 HN-F 本地处理
- 若地址落在远端插槽的范围内 → 通过跨插槽桥转发到远端 HN-F

这是 NUMA（非均匀内存访问）拓扑——本地内存访问比远端更快。

### 6.3 双插槽的 PMA 配置

`SoC.scala:58-72` 中的 PMA（Physical Memory Attributes）表必须扩展以
将远端插槽的内存范围覆盖为可缓存：

```scala
PMAConfigs: Seq[PMAConfigEntry] = Seq(
  // ... existing entries ...
  // Socket 0 local DDR
  PMAConfigEntry(0x80000000L, c = true, atomic = true, a = 1, x = true, w = true, r = true),
  // Socket 1 remote DDR (same attributes — cacheable, atomic)
  PMAConfigEntry(0x4000000000L, c = true, atomic = true, a = 1, x = true, w = true, r = true),
)
```

---

## 7. 时钟域与异步桥

### 7.1 时钟域架构

源文件：`XSNoCTop.scala:76-87`

每个 XSNoCTop tile 具有**四个独立时钟域**：

```
┌─────────────────────────────────────────────────────┐
│  XSNoCTop                                           │
│                                                     │
│  ┌─────────────────────┐    ┌───────────────────┐  │
│  │   CPU Core + L2     │    │   CHI Async       │  │
│  │   (clock domain)    │───►│   Bridge          │  │
│  │                     │    │   (noc_clock)      │  │
│  └─────────────────────┘    └────────┬──────────┘  │
│                                      │ io_chi      │
│  ┌─────────────────────┐             │             │
│  │   SoC Peripherals   │             │             │
│  │   (soc_clock)       │             │             │
│  └─────────────────────┘             │             │
│                                      │             │
│  ┌─────────────────────┐             │             │
│  │   Timer / CLINT     │             │             │
│  │   (clint_clock)     │             │             │
│  └─────────────────────┘             │             │
└──────────────────────────────────────┼─────────────┘
                                       ▼
                              CHI fabric (noc_clock domain)
```

### 7.2 异步桥配置

源文件：`SoC.scala:116-118`

```scala
EnableCHIAsyncBridge:    Some(AsyncQueueParams(depth = 16, sync = 3, safe = false))
EnableClintAsyncBridge:  Some(AsyncQueueParams(depth = 8,  sync = 3, safe = false))
SeperateBusAsyncBridge:  Some(AsyncQueueParams(depth = 1,  sync = 3, safe = false))
```

- **depth**：异步 FIFO 中条目数（CHI 为 16——可处理突发流量）
- **sync**：同步器级数（3——元稳态加固的标准值）
- **safe**：是否包含复位同步（false——单独处理）

### 7.3 双插槽时钟策略

```
                    ┌──────────────────┐
                    │  Reference Clock  │
                    │    (100 MHz)      │
                    └───────┬──────────┘
                            │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
         ┌────────┐   ┌────────┐   ┌────────┐
         │PLL Sock0│   │PLL NoC │   │PLL Sock1│
         │ 2 GHz  │   │ 1 GHz  │   │ 2 GHz  │
         └───┬────┘   └───┬────┘   └───┬────┘
             │             │             │
        Socket 0       Shared NoC    Socket 1
        cpu_clock      noc_clock     cpu_clock
```

**推荐做法：** 每个插槽运行自己的核时钟（为了功耗/散热管理可以是不同
频率）。CHI NoC fabric 运行于共享的 `noc_clock`。XSNoCTop 中的异步桥
处理域交叉。

对于跨插槽链路（芯片间），还需要额外的 PHY 级时钟恢复（SerDes PLL），
这超出了数字逻辑范畴。

---

## 8. 缓存一致性：目录与监听流程

### 8.1 一致性协议

XiangShan 使用 CHI 的基于目录的一致性。OpenLLC 作为 HN-F（Home Node -
Fully coherent）并维护缓存行状态的目录。

**缓存状态（CHI）：**

| 状态 | 含义 |
|-------|---------|
| I (Invalid) | 未缓存 |
| SC (Shared Clean) | 已缓存、只读、与内存一致 |
| UC (Unique Clean) | 独占缓存、与内存一致 |
| UD (Unique Dirty) | 独占缓存、已修改 |
| SD (Shared Dirty) | 共享缓存，但此副本为脏（CHI E.b） |

### 8.2 单插槽监听流程（现有）

```
Core A (RN-F)          OpenLLC (HN-F)          Core B (RN-F)
    │                       │                       │
    │── ReadShared ────────►│                       │
    │                       │── SnpShared ─────────►│
    │                       │                       │
    │                       │◄── SnpRespData ───────│
    │                       │                       │
    │◄── CompData ──────────│                       │
    │                       │                       │
    │── CompAck ───────────►│                       │
```

### 8.3 跨插槽监听流程（新增——必须实现）

当 Socket 0 上的 Core A 读取 Socket 1 上 Core B 所拥有的行时：

```
Socket 0                    Cross-Socket              Socket 1
Core A    OpenLLC 0         Bridge           OpenLLC 1    Core B
  │           │                │                 │           │
  │─ReadShared►│               │                 │           │
  │           │ (miss, addr    │                 │           │
  │           │  maps to S1)   │                 │           │
  │           │──CHI Req──────►│                 │           │
  │           │                │──CHI ReadShared►│           │
  │           │                │                 │─SnpShared►│
  │           │                │                 │           │
  │           │                │                 │◄SnpResp───│
  │           │                │◄──CompData──────│           │
  │           │◄──CompData─────│                 │           │
  │◄CompData──│                │                 │           │
  │─CompAck──►│                │                 │           │
```

**跨插槽桥必须：**
1. 截获地址映射到远端内存的本地 HN-F CHI 请求
2. 通过芯片间链路将其转发到远端 HN-F
3. 将响应返回到发起端 HN-F
4. 双向处理监听转发

### 8.4 双插槽的目录扩展

每个 OpenLLC 目录项必须扩展一个"远端存在"位：

```
Directory Entry (extended):
  ┌──────────┬───────────┬──────────────────┬────────────────┐
  │  Tag     │  State    │  Local Sharers   │  Remote Present│
  │          │ (I/SC/UC/ │  (bitmask of     │  (1 bit: is    │
  │          │  UD/SD)   │  local RN-Fs)    │  cached on     │
  │          │           │                  │  other socket) │
  └──────────┴───────────┴──────────────────┴────────────────┘
```

当"Remote Present"置位时，HN-F 在授予独占访问之前必须发送跨插槽监听。
这可以在 OpenLLC 的监听过滤器中作为额外的监听目标来实现。

---

## 9. 中断与定时器架构

### 9.1 当前中断拓扑（单插槽）

源文件：`Top.scala:162-167`、`SoC.scala:503-508`

```
External IRQs ──► PLIC ──► Core 0..N-1
Debug Module  ──► Core 0..N-1  (debug interrupt)
Timer/CLINT   ──► Core 0..N-1  (mtip, msip)
NMI           ──► Core 0..N-1  (non-maskable)
IMSIC (AIA)   ──► Core 0..N-1  (MSI-based, S/G mode)
```

### 9.2 双插槽中断方案

#### 方案 A：每插槽 PLIC + 全局中断路由器

```
                     ┌────────────────┐
                     │  Global IRQ    │
External IRQs ──────►│  Router        │
                     └───┬────────┬───┘
                         │        │
                    ┌────▼──┐  ┌──▼────┐
                    │PLIC S0│  │PLIC S1│
                    └───┬───┘  └───┬───┘
                        │          │
                  Socket 0      Socket 1
                  cores         cores
```

每个插槽有自己的 PLIC。全局路由器根据亲和性配置，将外部中断分发到对应
插槽的 PLIC。

#### 方案 B：共享 PLIC（更简单）

单一 PLIC 实例服务两个插槽中的所有核。Socket 1 核对 PLIC 的 MMIO 访问
通过跨插槽 MMIO 桥路由。这会给远端插槽上的中断 claim/complete 操作
增加延迟。

#### 方案 C：IMSIC / AIA（推荐，具备可扩展性）

XiangShan 支持带 IMSIC 的 RISC-V AIA（Advanced Interrupt Architecture）。
在 AIA 中：
- 中断作为 MSI（Message-Signaled Interrupts）传递
- 每个 hart 有本地 IMSIC 接收器
- 中断路由通过写入内存映射的 IMSIC 寄存器完成
- 跨插槽中断无需中央 PLIC

源文件：`SoC.scala:106-114`

```scala
IMSICParams: aia.IMSICParams = aia.IMSICParams(
  imsicIntSrcWidth = 9,      // Up to 512 interrupt sources
  mAddr = 0x3A800000,        // M-mode IMSIC address
  sgAddr = 0x3B000000,       // S/G-mode IMSIC address
  geilen = 7,                // Guest external interrupt lines
  vgeinWidth = 6,            // Virtual guest EI width
  iselectWidth = 12,
  EnableImsicAsyncBridge = true,
  HasTEEIMSIC = false
)
```

### 9.3 定时器 / CLINT

源文件：`SoC.scala:119-121`

```scala
UsePrivateClint: Boolean = false   // When true, each tile has its own CLINT
```

**对于双插槽系统：** 设置 `UsePrivateClint = true`。每个 tile 拥有私有
CLINT 定时器，消除跨插槽定时器访问延迟。`clint_clock` 输入驱动参考
时间基准。

`mtime` 寄存器必须跨插槽同步——要么让两个插槽的 `clint_clock` 由
同一源驱动，要么使用共享时间计数器广播。

---

## 10. 跨插槽电源管理

### 10.1 每插槽电源状态机

源文件：`XSNoCTop.scala:89-121`

XSNoCTop 包含一个具有以下状态的低功耗状态机：

```
sIDLE → sL2FLUSH → sWAITWFI → sEXITCO → sWAITQ → sQREQ → sPOFFREQ
```

**状态转换：**
1. **sIDLE**：正常工作
2. **sL2FLUSH**：L2 缓存刷新启动（CSR 写入触发 `l2_flush_en`）
3. **sWAITWFI**：等待 CPU 执行 WFI 指令
4. **sEXITCO**：退出一致性——去断言 `syscoreq`，等待 `syscoack` 去断言
5. **sWAITQ**：等待 Q-channel 握手
6. **sQREQ**：Q-channel 请求阶段
7. **sPOFFREQ**：掉电请求——`o_cpu_no_op` 断言，可安全门控/断电

### 10.2 CHI 系统一致性（SysCo）信号

源文件：`XSNoCTop.scala:106, 117`

```
syscoreq (output): Asserted when this node is part of the coherence domain
syscoack (input):  Asserted by the interconnect to acknowledge coherency participation
```

**双插槽电源管理：**
- 在关闭 Socket 1 之前，其 tile 必须退出一致性：
  1. 刷新 L2 缓存（所有脏行写回）
  2. 去断言所有 Socket 1 tile 上的 `syscoreq`
  3. 等待 `syscoack` 去断言（互连确认移除）
  4. Socket 1 的行不再在一致性域中被跟踪
  5. 可安全对 Socket 1 进行电源门控

### 10.3 WFI 时钟门控

源文件：`XSNoCTop.scala:124-162`

当 `WFIClockGate = true` 时，tile 在 WFI 时自动门控其核时钟。时钟将
在以下情况重新启用：
- 中断到达（msip、mtip、meip、seip、NMI、debug）
- CHI 监听到达（来自任意 RX 通道的 `flitpend` 信号）
- MSI 信息变为有效（`msi_info_vld`）

对于双插槽：`flitpend` 唤醒路径至关重要——跨插槽监听必须能够唤醒远端
插槽上处于 WFI 休眠的核。请确保芯片间 PHY 在核被时钟门控时仍保持
`flitpend` 信号。

---

## 11. RTL 生成与构建流程

### 11.1 构建单个 XSNoCTop Tile

```bash
cd /path/to/XiangShan

# Generate RTL for a single XSNoCTop tile (CHI E.b)
make verilog CONFIG=XSNoCTopConfig NUM_CORES=1 ISSUE=E.b

# The generated Verilog will be at:
#   build/rtl/XSNoCTopXSTop.sv    (or similar)
```

源文件：`Makefile:36, 50-52`

```makefile
TOP = $(XSTOP_PREFIX)XSTop      # With XSNoCTop prefix: "XSNoCTopXSTop"
CONFIG ?= TLConfig              # Override with XSNoCTopConfig
NUM_CORES ?= 1                  # Cores per tile (typically 1 for XSNoCTop)
ISSUE ?= E.b                    # CHI issue version
```

### 11.2 使用自定义参数构建

对于双插槽配置，你可能希望通过 YAML 自定义参数：

```yaml
# two_socket_config.yml
XSTopPrefix: "Socket"
CHIIssue: "E.b"
EnableCHIAsyncBridge: true
UsePrivateClint: true
WFIClockGate: true
EnablePowerDown: true
```

```bash
make verilog CONFIG=XSNoCTopConfig YAML_CONFIG=two_socket_config.yml
```

### 11.3 生成两个插槽

由于两个插槽使用相同的 tile RTL（仅通过 `nodeID` 和 `hartId` 输入
区分），你只需生成一次 XSNoCTop tile，并在顶层集成封装中实例化两次。

```bash
# Step 1: Generate the tile RTL
make verilog CONFIG=XSNoCTopConfig NUM_CORES=1 ISSUE=E.b

# Step 2: Write DualSocketTop wrapper (SystemVerilog / Chisel)
#         that instantiates 2x XSNoCTop + LLC + bridges + fabric
```

---

## 12. 集成模块：DualSocketTop

### 12.1 模块架构

下面是 DualSocketTop 集成模块的推荐结构。该模块将作为新的 Chisel
模块或 SystemVerilog 封装编写。

```
DualSocketTop
├── Socket 0: XSNoCTop (tile 0..N-1)
│   ├── io_chi ──► CHI Router 0
│   └── tileio.nodeID := 0.U
├── Socket 1: XSNoCTop (tile N..2N-1)
│   ├── io_chi ──► CHI Router 1
│   └── tileio.nodeID := 256.U
├── OpenLLC 0 (local to Socket 0)
│   ├── rn(0) ◄── CHI Router 0 (LLC port)
│   ├── sn ──► OpenNCB 0 ──► AXI4 Memory 0
│   └── nodeID := (2*N).U
├── OpenLLC 1 (local to Socket 1)
│   ├── rn(0) ◄── CHI Router 1 (LLC port)
│   ├── sn ──► OpenNCB 1 ──► AXI4 Memory 1
│   └── nodeID := (256 + 2*N).U
├── MMIO Bridge 0 ◄── CHI Router 0 (MMIO port)
│   └── ──► AXI4 Peripheral Bus
├── MMIO Bridge 1 ◄── CHI Router 1 (MMIO port)
│   └── ──► AXI4 Peripheral Bus
├── Cross-Socket Bridge
│   ├── port_0 ◄──► CHI Router 0
│   └── port_1 ◄──► CHI Router 1
├── Shared PLIC / per-socket IMSIC
├── Debug Module
└── Clock / Reset Generation
```

### 12.2 CHI 路由器（每插槽）

每个插槽需要一个 CHI 路由器，根据地址将 tile 的 `io_chi` 流量导向
正确的目的地：

```
CHI Router (per-socket):
  Input:  io_chi from XSNoCTop tile
  Output: 3 destinations
    ├── MMIO:           Addresses 0x0 - 0x7FFFFFFF          → MMIO Bridge
    ├── Local LLC:      Addresses in local DDR range         → Local OpenLLC
    └── Cross-Socket:   Addresses in remote DDR range        → X-Socket Bridge
```

这相当于 `Top.scala:372-378` 中的 `route()` + `bind()` 逻辑，但增加
了一个跨插槽目的地。

### 12.3 伪代码（Chisel 风格）

```scala
class DualSocketTop(implicit p: Parameters) extends RawModule {
  val N = 1  // cores per socket

  // ── Clock / Reset ──
  val cpu_clock_0  = IO(Input(Clock()))
  val cpu_clock_1  = IO(Input(Clock()))
  val noc_clock    = IO(Input(Clock()))
  val cpu_reset    = IO(Input(AsyncReset()))
  val noc_reset    = IO(Input(AsyncReset()))

  // ── Memory Ports ──
  val mem_axi_0    = IO(new AXI4Bundle(...))   // Socket 0 DDR
  val mem_axi_1    = IO(new AXI4Bundle(...))   // Socket 1 DDR

  // ── Socket 0 ──
  val socket0 = Module(new XSNoCTop())
  socket0.clock        := cpu_clock_0
  socket0.reset        := cpu_reset
  socket0.noc_clock.get := noc_clock
  socket0.noc_reset.get := noc_reset
  socket0.tileio.hartId     := 0.U
  socket0.tileio.nodeID     := 0.U
  socket0.tileio.riscv_rst_vec := "h80000000".U

  // ── Socket 1 ──
  val socket1 = Module(new XSNoCTop())
  socket1.clock        := cpu_clock_1
  socket1.reset        := cpu_reset
  socket1.noc_clock.get := noc_clock
  socket1.noc_reset.get := noc_reset
  socket1.tileio.hartId     := 1.U
  socket1.tileio.nodeID     := 256.U
  socket1.tileio.riscv_rst_vec := "h80000000".U

  // ── OpenLLC 0 (Socket 0 L3) ──
  val llc0 = Module(new OpenLLC())
  llc0.io.nodeID := (2 * N).U

  // ── OpenLLC 1 (Socket 1 L3) ──
  val llc1 = Module(new OpenLLC())
  llc1.io.nodeID := (256 + 2 * N).U

  // ── CHI Routing ──
  // Socket 0: route io_chi to (MMIO bridge, local LLC, cross-socket)
  val router0 = Module(new CHIRouter(
    mmioRange  = AddressSet(0x0L, 0x7fffffffL),
    localRange = AddressSet(0x80000000L, 0x3f7fffffffL),   // Socket 0 DDR
    remoteRange = AddressSet(0x4000000000L, 0x3fffffffffL)  // Socket 1 DDR
  ))
  router0.io.in <> socket0.io_chi

  // Connect router outputs
  mmioBridge0.io.chi  <> router0.io.mmio
  llc0.io.rn(0)       <> router0.io.local
  xSocketBridge.io.s0 <> router0.io.remote

  // (Similar for Socket 1)

  // ── Memory ──
  val ncb0 = Module(new OpenNCB())  // LLC 0 → DDR 0
  ncb0.io.chi <> llc0.io.sn
  mem_axi_0   <> ncb0.io.axi4

  val ncb1 = Module(new OpenNCB())  // LLC 1 → DDR 1
  ncb1.io.chi <> llc1.io.sn
  mem_axi_1   <> ncb1.io.axi4
}
```

### 12.4 每插槽多核

对于每插槽多核，使用 `XSTop`（而不是 `XSNoCTop`）并设置 `NUM_CORES > 1`
和 `CONFIG=CHIConfig`。每个 `XSTop` 已经包含内部 CHI 路由、OpenLLC 和
OpenNCB。跨插槽桥则连接两个 `XSTop` 实例的内存端口。

或者，在每个插槽中实例化多个 `XSNoCTop` tile，并自行构建插槽内 CHI
fabric 以获得最大控制力。

---

## 13. 验证策略

### 13.1 单插槽验证

XiangShan 已提供：

- **Difftest**：与 NEMU/Spike 参考模型的联合仿真
- **CHILogger**：协议级跟踪日志（`Top.scala:369-386`）
- **ISA 测试**：rv64ui/um/uf/ud/ua 合规性

```bash
# Build simulation binary with difftest
make emu CONFIG=XSNoCDiffTopConfig NUM_CORES=1 ISSUE=E.b

# Run ISA tests
./build/emu -i ready-to-run/rv64ui-p-add.bin
```

### 13.2 双插槽验证计划

| 阶段 | 重点 | 方法 |
|-------|-------|--------|
| 1 | 单 tile CHI 合规性 | 在 io_chi 上使用 CHI 协议检查器 |
| 2 | 双 tile 共享 LLC | 实例化 2 个 XSNoCTop + 1 个 OpenLLC，运行 MP 测试 |
| 3 | 双 tile 双 LLC，不跨插槽 | 每个 tile 仅访问本地内存 |
| 4 | 跨插槽一致性 | MOESI 压力测试：生产者-消费者、伪共享 |
| 5 | 跨插槽原子操作 | LR/SC、跨插槽边界的 AMO |
| 6 | 电源管理 | 远端插槽活跃时的 SysCo 退出/进入 |
| 7 | 性能 | STREAM、GUPS、内存延迟基准测试（本地 vs 远端） |

### 13.3 跨插槽一致性的关键测试场景

1. **基本共享读**：Core A（S0）写，Core B（S1）读——数据必须可见
2. **独占所有权转移**：Core A（S0）持有独占后，Core B（S1）写入
3. **伪共享**：同一缓存行中相邻字节被不同插槽修改
4. **跨插槽原子**：S0 上 LR、S0 上 SC，其中行最后由 S1 写入
5. **回写失效**：S0 LLC 驱逐 S1 缓存的行——必须使 S1 的副本失效
6. **负载下 SysCo 退出**：S0 正在主动访问共享数据时 S1 退出一致性

---

## 14. 物理设计考量

### 14.1 芯片间链路方案

| 技术 | 带宽 | 延迟 | 引脚 | 用例 |
|------------|-----------|---------|------|----------|
| CXL 2.0 (PCIe 5.0) | 64 GB/s | ~100-200 ns | PCIe 通道 | 行业标准 |
| CXL 3.0 (PCIe 6.0) | 128 GB/s | ~50-100 ns | PCIe 通道 | 下一代服务器 |
| 定制 CHI-over-SerDes | 可配置 | ~50-150 ns | 定制 | 完全控制 |
| UCIe (die-to-die) | 256+ GB/s | ~2-5 ns | 凸点阵列 | Chiplet / 2.5D |
| 直接并行 CHI | 最高 | ~1-2 ns | 500+ 连线 | 同封装 MCM |

### 14.2 延迟预算

```
Local memory access (same socket):
  L1 hit:     1-3 cycles
  L2 hit:     8-15 cycles
  L3 hit:     20-40 cycles
  DDR:        100-200 cycles

Remote memory access (cross-socket):
  L1 hit:     1-3 cycles (if cached locally)
  L2 hit:     8-15 cycles (if cached locally)
  Remote L3:  L3 latency + link latency (60-100 cycles additional)
  Remote DDR: DDR latency + link latency (150-400 cycles total)
```

### 14.3 带宽需求

对于每插槽 4 核、2 GHz 的双插槽系统：
- 每插槽 L2→L3 带宽：~64 GB/s（4 核 × 每周期 1 缓存行 × 64B）
- 跨插槽带宽需求：总量的 ~10-20%（共享工作负载）= 6-13 GB/s
- 最低跨插槽链路：推荐双向 16 GB/s

### 14.4 软件中的 NUMA 感知

操作系统和运行时必须具备 NUMA 感知：
- Linux：通过设备树 / ACPI SRAT 配置 NUMA 节点
- 内存分配：优先使用本地插槽的 DDR
- 线程调度：将线程绑定到其数据附近的核
- 页迁移：将频繁访问的远端页移至本地 DDR

---

## 附录：信号参考

### A.1 XSNoCTop 完整 IO 列表

```
IO Signal                    Direction  Width        Description
─────────────────────────────────────────────────────────────────
clock                        Input      1            Core clock
reset                        Input      1 (async)    Core reset
noc_clock                    Input      1            NoC clock domain
noc_reset                    Input      1 (async)    NoC reset
soc_clock                    Input      1            SoC peripheral clock
soc_reset                    Input      1 (async)    SoC peripheral reset
clint_clock                  Input      1            Timer reference clock
clint_reset                  Input      1 (async)    Timer reset
io_chi.tx.req.*              Output     (bundle)     CHI TX Request channel
io_chi.tx.rsp.*              Output     (bundle)     CHI TX Response channel
io_chi.tx.dat.*              Output     (bundle)     CHI TX Data channel
io_chi.rx.snp.*              Input      (bundle)     CHI RX Snoop channel
io_chi.rx.snp.flitpend       Input      1            Snoop flit pending
io_chi.rx.rsp.*              Input      (bundle)     CHI RX Response channel
io_chi.rx.rsp.flitpend       Input      1            Response flit pending
io_chi.rx.dat.*              Input      (bundle)     CHI RX Data channel
io_chi.rx.dat.flitpend       Input      1            Data flit pending
io_chi.syscoreq              Output     1            System coherency request
io_chi.syscoack              Input      1            System coherency ack
tileio.hartId                Input      varies       Hardware thread ID
tileio.nodeID                Input      7/9/11       CHI Node ID
tileio.riscv_rst_vec         Input      48           Reset vector address
tileio.riscv_halt            Output     1            CPU halted
tileio.riscv_critical_error  Output     1            Critical error
tileio.hartResetReq          Input      1            Per-hart reset request
tileio.hartIsInReset         Output     1            Hart in reset state
io.lp.i_cpu_sw_rst_n         Input      1            Software reset (active low)
io.lp.i_cpu_iso_en           Input      1            Isolation enable
io.lp.i_cpu_pwrdown_req_n    Input      1            Power-down request
io.lp.o_cpu_pwrdown_ack_n    Output     1            Power-down ack
io.lp.o_cpu_no_op            Output     1            CPU idle / no-op
io.dft                       Input      (bundle)     DFT SRAM broadcast (optional)
io.dft_reset                 Input      (bundle)     DFT reset (optional)
```

### A.2 关键源文件

| 文件 | 用途 |
|------|---------|
| `top/XSNoCTop.scala` | 带 CHI 端口的每 tile 顶层模块 |
| `top/Top.scala` | 多核单插槽顶层（路由参考） |
| `top/Configs.scala` | 所有命名配置 |
| `system/SoC.scala` | SoC 参数、地址映射、CHI 设置 |
| `xiangshan/L2Top.scala` | 带 TL 到 CHI 转换的 L2 缓存 |
| `xiangshan/XSTileWrap.scala` | 带异步桥的 tile 封装 |
| `xiangshan/Parameters.scala` | 核参数、NodeIDWidth |
| `Makefile` | 构建目标和 CONFIG/ISSUE 标志 |

### A.3 构建命令速查

```bash
# Single XSNoCTop tile (CHI E.b)
make verilog CONFIG=XSNoCTopConfig ISSUE=E.b

# Single XSNoCTop tile with difftest
make emu CONFIG=XSNoCDiffTopConfig ISSUE=E.b

# Multi-core single-socket with CHI (for reference)
make verilog CONFIG=CHIConfig NUM_CORES=4 ISSUE=E.b

# Minimal CHI config (smaller, faster compile)
make verilog CONFIG=CHIMinimalConfig ISSUE=E.b

# Custom CHI address width
make verilog CONFIG=XSNoCTopConfig ISSUE=E.b CHI_ADDR_WIDTH=48
```
