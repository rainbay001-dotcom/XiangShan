# openmlc 需求分析

## 1. 文档目的

本文档面向 **openmlc**（cluster 级 CHI 中间节点）的模块/组件级验证工作，回答三个问题：
1. 这个模块为什么要存在（需求来源）；
2. 它要解决什么验证问题（验证目标）；
3. 它在系统中的定位、接口和边界是什么（规格范围）。

具体的、可追踪的条目化需求见配套文档《openmlc需求列表》（`docs/openmlc-requirements-list.md`）。本文档不涉及 RTL 实现细节，只做需求侧的分析与规格约束。

---

## 2. 背景

### 2.1 修改前拓扑：L2 直连一致性节点 HA

在插入 openmlc 之前，一个 cluster 内每个核的 L2 Cache（CHI 角色为 **RN-F**，Fully-coherent Request Node）各自独立通过 CHI 总线对外连接到系统级一致性节点 **HA**（Home Agent，CHI 协议中的 Home Node）：

```
L2[core0] (RN-F) ────CHI──────┐
L2[core1] (RN-F) ────CHI──────┼──▶  HA (Home Node)
      ...                     │
L2[coreN-1] (RN-F) ──CHI──────┘
```

> 说明：在本仓库当前主线架构中，Home Node 的角色通常由 `xscache.openLLC.OpenLLC`（面向 DRAM 的末级 Cache/HN-F，见 `src/main/scala/top/Top.scala:240-330`）承担；在更广义的 SoC 语境下，HA 也可能指代跨 cluster/跨 die 的系统级一致性互联（如 ARM CMN/CCN 或自研 Coherent Hub），不一定是本仓库内建模块。本文档中的“HA”按后者的广义定义处理，即：**openmlc 面对的上游是一个黑盒/灰盒的 CHI Home Node**，具体协议行为可能与仓库内 OpenLLC 不完全一致，需要在验证环境中单独建模或用真实 HA IP 替换（见第 11 节开放问题）。

在这种拓扑下，HA 直接面对 N 个独立的 RN-F，观察和控制的粒度只能是单核（每个 srcID/RN 一条链路），无法在 **cluster 边界**统一地：
- 采样/统计整个 cluster 对 HA 的聚合流量特征（带宽、outstanding 数、请求类型分布）；
- 在不改动 L2/HA 任何一方设计的前提下，注入延迟、丢弃/重排响应、模拟 HA 侧 retry 风暴等异常场景；
- 观测某一核的请求是否因为 HA 侧的资源受限（credit、MSHR、snoop 队列）而被间接拖慢其他核。

### 2.2 需要回答的问题

> 需要验证 **HA 对一个包含多个核的 cluster 的影响**。

也就是说，本次需求不是"给 cluster 增加一块新的功能性 Cache"，而是"在 cluster 与 HA 之间建立一个可控、可观测的验证边界点"，用以研究和验证：HA 的协议行为（retry、snoop 广播、时序、带宽限制、outstanding 容量）会如何传导、放大或衰减到一个多核 cluster 内部，是否会造成核间干扰（noisy-neighbor）、公平性问题、死锁/活锁风险或性能回退。

### 2.3 openmlc 与 openllc 的定位差异

用户明确要求 openmlc **不同于** openllc，二者的差异是本次需求分析的核心：

| 维度 | openllc（现有） | openmlc（本次新增） |
|---|---|---|
| CHI 协议角色 | 对 RN（L2）呈现 **HN-F**（终结一致性，含目录+数据存储） | 对 L2 呈现 **HN**，对 HA 呈现 **RN**（是"透传/代理"节点，不终结一致性） |
| 系统位置 | 位于 cluster 下游、DRAM 上游（Last-Level Cache，内存路径终点） | 位于 cluster 内部边界、L2 与外部 HA 之间（cluster 出口，不靠近内存） |
| 是否持有数据副本 | 是，有 Directory + DataStorage，真实终结一致性事务 | 默认**不持有**完整数据副本（详见第 5.3 节），以透传/聚合为主 |
| 主要目的 | 功能性末级缓存，提升访存性能、降低 DRAM 带宽压力 | **验证性**边界节点，用于观测/控制/度量 HA 与多核 cluster 的交互 |
| 是否可综合进最终芯片 | 是，生产必需 | 视验证阶段而定，需支持编译期可裁剪（默认不进最终 tapeout 版本，或以 transparent bypass 模式存在） |
| 一致性终结点 | openllc 自身 | 仍然是 HA（openmlc 不改变协议终结点，只是在中间插入可观测/可控层） |

结论：openllc 解决的是"访存性能"问题，openmlc 解决的是"cluster 与外部一致性域交互的**可验证性**"问题，二者服务的需求维度完全不同，不能互相替代，未来在同一条 CHI 拓扑中也可能同时存在（openmlc 在 cluster 出口，openllc 在更下游靠近内存的位置，或在不同验证场景中二选一接入）。

---

## 3. 需求来源

| 来源 | 内容 |
|---|---|
| 用户直接需求（Issue #7 评论，2026-08-20） | "现在需求来源是原本是L2 直接对外连接一致性节点HA，现在需要验证HA对一个cluster包含多个核的影响，所以添加一个openmlc不同于openllc" |
| 历史设计沉淀（同一 Issue 早期讨论） | CHIHNSubNode 的雏形设计（对 L2 呈现 HN、对下游呈现 RN 的透明代理节点），可作为 openmlc 的实现基础，但**目的不同**：CHIHNSubNode 当时是为 openllc 做集群级前置聚合，openmlc 是为**验证 HA 行为**而生，因此在需求上要新增可观测性/可控性/故障注入类需求，而不仅是协议透传 |
| 项目背景约束 | 本仓库为 XiangShan 处理器 IP，CHI 协议基于 `coupledL2.tl2chi`（PortIO/CHIOpcodes 等）已有基础设施，需求应尽量复用现有 CHI 建模能力（CHILogger、PortIO、route/bind 工具），避免重复造轮子 |

---

## 4. 验证目标

### 4.1 待验证的问题清单（问题域）

1. **Retry 机制影响**：HA 因资源不足发出 `RetryAck` 时，openmlc/cluster 侧的重试队列、重试风暴是否会导致某个核长期饿死（starvation），或引发 cluster 内部反压蔓延至所有核（包括未参与该事务的核）。
2. **Snoop 广播影响**：HA 发起跨 cluster 的 Snoop（`SnpUnique`/`SnpShared`/`SnpCleanInvalid` 等）时，openmlc 作为聚合点如何将 Snoop 分发给 cluster 内正确的 L2 子集，以及聚合 SnpResp 的时延是否会被 HA 一侧的原始 latency 放大。
3. **Credit/链路反压影响**：CHI Link-layer 的 L-Credit 机制（`linkactive`/`lcrdv`）在 HA 侧受限时，openmlc 是否会将反压不加区分地传导给 cluster 内所有核，还是能做到按 srcID 精细反压。
4. **Outstanding 容量影响**：HA 的 MSHR/OTB 容量有限，当 cluster 内多核并发产生的 outstanding 事务总数超过 HA 承受能力时，openmlc 侧的排队策略（FIFO/优先级/轮询）对各核时延分布、尾延迟（tail latency）的影响。
5. **一致性正确性**：在上述压力场景（retry、snoop 拥塞、乱序响应）下，跨核数据一致性、事务顺序（Ordered transaction/DVM）是否仍然保持正确，是否存在因 openmlc 引入而产生的新增死锁/活锁风险。
6. **公平性/QoS**：cluster 内某一核的突发流量是否会通过 openmlc→HA 的共享通路影响其他核的服务质量（noisy neighbor 问题）。
7. **可重现性**：上述场景需要能在仿真环境中**可控、可重复地复现**（而非依赖真实 HA IP 的随机行为），这是 openmlc 需要具备故障注入/流量整形能力的直接原因。

### 4.2 openmlc 承担的角色

openmlc 在验证体系中扮演的角色是 **cluster-HA 边界的“验证探针 + 流量代理”（Verification Interposer）**，而不是功能性缓存节点：

- **对 L2（cluster 内部）**：呈现为 CHI **HN**，即透明地承接来自 N 个 L2 的 RN-F 事务，做 srcID/txnID 命名空间管理与地址路由；
- **对 HA（cluster 外部）**：呈现为 CHI **RN**（可以是单个聚合 RN-F，或者按需保留多个虚拟 RN 通道），代表整个 cluster 向 HA 发起/接收事务；
- **对验证环境**：提供观测点（事务日志、性能计数器）与控制点（延迟注入、Retry 注入、Snoop 时延整形、丢包/乱序模拟），供 testbench/checker 使用。

---

## 5. 总体方案

### 5.1 系统上下文（修改前 / 修改后）

**修改前：**
```
L2[0] (RN-F) ─┐
L2[1] (RN-F) ─┼──CHI──▶ HA (HN)
 ...          │
L2[N-1](RN-F)─┘
```

**修改后（插入 openmlc）：**
```
L2[0] (RN-F) ─┐
L2[1] (RN-F) ─┼──CHI──▶ openmlc ──CHI──▶ HA (HN)
 ...          │      (对L2: HN)  (对HA: RN)
L2[N-1](RN-F)─┘           │
                          ├─▶ 观测接口（事务日志/性能计数器）
                          └─▶ 控制接口（延迟注入/Retry注入/流量整形）
```

### 5.2 与 openllc 的拓扑关系（待与用户确认，见第 11 节）

存在两种可能，需要在需求冻结前明确：

- **方案 A（并列/替代关系）**：openmlc 与 openllc 是两条互斥的验证路径，验证 HA 影响时使用 openmlc（HA 侧不含仓库内 OpenLLC，而是外部/建模的一致性节点）；生产/性能验证时使用 openllc（不接 openmlc）。
- **方案 B（串联关系）**：openmlc 位于 cluster 出口，其下游即为 openllc（即 openllc 本身在此场景下扮演 HA 角色）；此时 openmlc 是"cluster 内多核到 openllc 之间"的验证代理层。

两种方案对 openmlc 的地址路由、nodeID 分配、是否需要感知 openllc 内部状态等需求有直接影响，**建议在需求列表冻结前与用户确认**。

### 5.3 是否持有数据（关键设计决策）

为了聚焦"验证 HA 影响"这一目标而不是"提升性能"，openmlc 默认定位为**透明代理**（不持有独立 Directory/DataStorage，不终结一致性），这与历史讨论中的 CHIHNSubNode 透明直通设计一致。但为了满足"聚合多核请求"的需求，openmlc 至少需要具备以下*轻量*状态管理能力（而非完整 Cache）：

- srcID/txnID 命名空间转换表（多个 L2 的 txnID 空间可能重叠，必须在转发给 HA 前统一编号，并在响应返回时正确路由回发起的 L2）；
- Outstanding 事务跟踪表（用于统计、限流、以及在故障注入场景下伪造/延迟响应）；
- 可选的 Snoop 分发表（记录哪些 cache line 曾经过哪个 L2，用于将 HA 发起的 Snoop 精确路由到目标核，而不是无差别广播——是否需要该能力取决于 HA 是否要求 openmlc 承担 snoop filter 职责，见需求列表 REQ-FUNC-*）。

---

## 6. 非功能需求概述

- **时序透明性**：默认（bypass）模式下，openmlc 引入的额外流水线深度应可配置甚至可降为 0（纯组合直通），避免在不需要验证特性时影响性能基线的可比性。
- **可裁剪性**：openmlc 的验证专用逻辑（延迟注入、日志、统计）应可在编译期开关，不应出现在最终 tapeout 配置中，或需要极小的门控开销。
- **可配置性**：cluster 内核数 N、地址空间划分、CHI Issue 版本（B/C/E.b）需可参数化，不能硬编码。
- **可观测性**：这是本次需求区别于以往 CHIHNSubNode 的核心新增点——必须提供事务级日志、性能计数器、以及可选的 Top-Down 式统计接口，供后续 checker/scoreboard 使用。
- **可复用现有基础设施**：应尽量复用仓库已有的 `coupledL2.tl2chi.PortIO`、`CHILogger`、`route`/`bind` 工具，不重复实现 CHI 编解码。

---

## 7. 验证需求纵览（详见需求列表文档）

验证需求本身也需要分层次表达（这是"模块/组件/芯片验证需求"的题中之义）：

1. **openmlc 自身的单元级验证**：协议合规性（是否正确实现 RN/HN 双重角色的 CHI 状态机）、边界条件（cluster 内核数为 1 / 满配、地址越界、txnID 溢出）。
2. **openmlc + cluster 的子系统级验证**：多核并发压力下的功能正确性（一致性、顺序性）与非功能指标（时延分布、吞吐、公平性）。
3. **面向 HA 交互的场景化验证**：借助 openmlc 的故障注入能力，构造 HA 侧异常场景（retry 风暴、snoop 拥塞、credit 耗尽、乱序/丢弃响应）并观察 cluster 侧表现，这是本次需求的**核心验证价值**。
4. **回归/覆盖率需求**：功能覆盖率模型（CHI opcode × srcID × HA 响应场景的交叉覆盖）、断言覆盖（协议非法状态检测）。

---

## 8. 风险与开放问题

| 编号 | 问题 | 影响 | 建议处理方式 |
|---|---|---|---|
| Q1 | HA 的具体来源与协议版本未定：是仓库内 OpenLLC 充当，还是需要独立建模/桩模块（BFM）？CHI Issue 版本（B/C/E.b）？ | 决定 openmlc 需要支持的 CHI opcode 集合与接口宽度 | 需要用户/架构侧确认；建议默认先支持 CHI Issue E.b（与仓库当前 OpenLLC 一致），并预留可扩展性 |
| Q2 | HA 是否要求 openmlc 承担精确的 Snoop Filter 职责（避免无差别广播 Snoop 到所有核）？ | 决定 openmlc 是否需要维护 clientCaches 级别的目录信息，复杂度显著不同 | 需求列表中列为可选/分级需求（P1），先满足"透明广播 Snoop"的最小可用版本（P0），再迭代精确路由 |
| Q3 | openmlc 与 openllc 的拓扑关系（第 5.2 节方案 A/B）未定 | 影响地址路由、nodeID 分配、下游接口形态 | 需求冻结前必须与用户确认 |
| Q4 | 故障注入能力的具体场景清单（哪些异常必须支持、哪些是锦上添花）未定 | 影响验证需求列表的优先级划分与开发工作量 | 建议按第 4.1 节问题清单先做 P0（retry、snoop 时延、outstanding 限流）与 P1（乱序/丢弃、credit 精细反压）分级 |
| Q5 | cluster 内核数上限、是否支持多 cluster 级联（openmlc 是否需要支持树状/多级拓扑）未定 | 影响参数化设计与接口位宽 | 建议先支持单 cluster、参数化核数（默认与当前 `NumCores` 对齐），多级拓扑作为后续扩展方向 |

---

## 9. 命名说明

**openmlc**：Open **M**ulti-core c**L**uster **C**oherence（proxy）。命名强调其定位是"cluster 级、多核聚合"的一致性中间节点，区别于 **openllc** 的 **L**ast-**L**evel **C**ache 定位——前者服务于"cluster 出口的可验证性"，后者服务于"内存路径的访存性能"。

---

## 10. 与配套文档的关系

本文件是定性的需求分析与规格边界说明；条目化、可追踪、带验证方式和验收标准的具体需求见《openmlc需求列表》（`docs/openmlc-requirements-list.md`）。两份文档应保持同步：本文档的第 4/5/8 节是需求列表中各条目的分析依据。
