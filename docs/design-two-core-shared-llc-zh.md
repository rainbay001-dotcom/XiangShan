# 双核香山：每核私有 L2 与共享 OpenLLC

本文档描述了一个双核香山系统的设计，其中每个核心拥有私有的 CoupledL2，
而两个 L2 共享单个 OpenLLC 实例作为 CHI 主节点（HN-F）。该拓扑结构是
后续跨插槽缓存一致性工作的基础（参见
`docs/design-cross-socket-bridge.md`）。

## TL;DR

- **无需新增 Chisel 代码。** `CHIConfig(2)` 已经实例化了两个
  `XSTile`（每个拥有自己的 L2），并在 `Top.scala` 中将二者绑定到单个共享的
  `OpenLLC`。CHI 路径从一开始就以 `NumCores` 进行参数化。
- **需要修复一处 bug。** `TLMinimalConfig` 硬编码了
  `clientCaches = Seq(L2Param())`，因此即使使用 `--num-cores 2`，
  `OpenLLC.numRNs` 仍然被固定为 1，在绑定第二个核心时会抛出
  IndexOutOfBoundsException。已在 `src/main/scala/top/Configs.scala:284` 修复。
- **在 NPU 服务器上完成端到端全流程验证**，使用 `CHIConfig(2)`：
  elaboration 无错误，`SimTop.fir` 已生成（2.73 GB），`firtool` → 2058 个
  `.sv` 文件，全部 5 项 RTL 完整性检查通过。报告：
  `docs/verification/two-core-shared-llc.txt`。

## 拓扑结构

```
            +---------------+     +---------------+
            |  XSCore (0)   |     |  XSCore (1)   |
            +-------+-------+     +-------+-------+
                    |                     |
            +-------+-------+     +-------+-------+
            |  CoupledL2 0  |     |  CoupledL2 1  |   (private, non-inclusive)
            |  TL -> CHI    |     |  TL -> CHI    |
            +-------+-------+     +-------+-------+
                    |                     |
            CHI RN-F (NodeID=0)    CHI RN-F (NodeID=1)
                    |                     |
            +-------+---------------------+-------+
            |            OpenLLC  (HN-F)         |    (shared, non-inclusive)
            |   numRNs = 2, NodeID = NumCores*2  |
            +-------------------+-----------------+
                                |
                          CHI SN-F (memory)
                                |
                           OpenNCB (CHI -> AXI4)
                                |
                              DRAM
```

### 地址路由（`Top.scala:372-378`）

| 地址范围                              | 路由                            | NodeID          |
|---------------------------------------|---------------------------------|-----------------|
| `[0x0000_0000, 0x7fff_ffff]`          | 每核 MMIO bridge                | `NumCores + i`  |
| `[0x8000_0000, 0xffff_ffff_ffff]`     | 共享 OpenLLC（HN-F）            | `NumCores * 2`  |

当 `NumCores == 2` 时：MMIO bridge 位于 `11'h2`/`11'h3`，OpenLLC HN-F 位于
`11'h4`。这三个常量都可以在生成的 XSTop.sv 路由比较器中观察到（参见
`verification.txt`）。

## 为什么无需新增 Chisel 代码

`CHIConfig(n)` → `TLConfig(n)` + `WithCHI` 已经完成了所有繁重工作。
关键行如下：

1. `Configs.scala:424` — OpenLLC 的 `clientCaches = tiles.map { core => ... }`
   自动将客户端目录扩展为每个 tile 一个条目。
2. `Top.scala:301-310` — 当启用 `enableCHI` 时，会实例化一个
   `OpenLLC` 模块，并向其传入 `hartIds = tiles.map(_.HartId)`。
3. `Top.scala:368-383` — 一个 `for ((core, i) <- core_with_l2.zipWithIndex)`
   循环通过基于地址的路由将每个核心的 CHI 端口绑定到 `chi_openllc_opt.io.rn(i)`。
4. `Top.scala:432-434` — 每个 tile 获得一个不同的 CHI NodeID
   （`tile.module.io.nodeID := i.U`）。
5. `Top.scala:387` — OpenLLC 自身的 NodeID 被设置为 `NumCores * 2`。
6. `openLLC/LLCParam.scala:89` — `def numRNs = cacheParams.clientCaches.size`
   从配置推导出 RN-F 端口数量。
7. `openLLC/LLCParam.scala:90` — `if (numRNs == 1) "Exclusive" else "Non-inclusive"`
   使得多 RN-F 成为**显式且经过测试的设计分支**，
   而非事后补丁。
8. `openLLC/OpenLLC.scala:38` — `val rn = Vec(numRNs, Flipped(new PortIO))`
   使端口向量随 `numRNs` 扩展。

## Bug 修复：`TLMinimalConfig` 硬编码 `clientCaches`

`TLMinimalConfig`（`CHIMinimalConfig` 的父类）原本为：

```scala
OpenLLCParamsOpt = Option.when(up(EnableCHI))(OpenLLCParam(
  ...
  clientCaches = Seq(L2Param())     // <-- single entry, ignores NumCores
))
```

即使使用 `--num-cores 2`，`numRNs` 也为 1，
`Top.scala:380` 的绑定循环在将第二个核心的 CHI 端口绑定到
`chi_openllc_opt.io.rn(1)` 时会抛出 `IndexOutOfBoundsException: 1 is out of bounds
(min 0, max 0)`。

**修复**（`src/main/scala/top/Configs.scala:284`）：

```scala
clientCaches = tiles.map(_ => L2Param())
```

修复后，`CHIMinimalConfig(2)` 能够干净地 elaborate 并得到 `numRNs == 2`。
完整的 `TLConfig` / `CHIConfig` 路径本来就是正确的，因为它
使用了 `clientCaches = tiles.map { core => ... }`。

## 验证

### Elaboration 证据

`CHIConfig(2)` elaborate 时产生 140 条警告，0 条错误。证明 `numRNs == 2` 的
标志性 firrtl 警告如下：

```
openLLC/src/main/scala/openLLC/MainPipe.scala 111:60: [W004]
  Dynamic index with width 11 is too wide for Vec of size 2
  (expected index width 1).
```

（修复之前，同一行报告的是 `Vec of size 1`。）

### RTL 完整性检查

针对生成的 SystemVerilog 运行（完整的机器校验报告见
`docs/verification/two-core-shared-llc.txt`）：

| # | 检查项                                                   | 结果                                                                |
|---|---------------------------------------------------------|---------------------------------------------------------------------|
| 1 | `XSTile` 在 `XSTop.sv` 中被实例化 2 次                  | ✅ `core_with_l2` @1522, `core_with_l2_1` @1689                     |
| 2 | `OpenLLC` 在 `XSTop.sv` 中被实例化 1 次                 | ✅ `chi_openllc_opt` @2041                                          |
| 3 | `OpenLLC.sv` 同时含有 `io_rn_0_*` 和 `io_rn_1_*` 端口   | ✅ 各 64 条 wire                                                    |
| 4 | `XSTop.sv` 中不同的 `tgtID` 路由常量                    | ✅ `11'h2`, `11'h3`（MMIO bridge），`11'h4`（OpenLLC HN-F）         |
| 5 | 每个 tile 的 `.io_nodeID` 输入互不相同                  | ✅ `11'h0` @1614, `11'h1` @1781                                     |

### 流水线统计

- `SimTop.fir`：2.73 GB，耗时约 830 秒生成（mill `-Xmx48G`）
- `firtool` → 2058 个 `.sv` 文件（SimTop.sv = 5.5 MB，XSTop.sv = 458 KB，
  OpenLLC.sv = 547 KB，XSTile.sv = 163 KB）
- 总耗时：elaboration 约 14 分钟，firtool 约 5 小时（qemu x86_64 模拟
  —— 不存在 aarch64 版的 firtool 二进制；详情见 build 参考文档）

## 构建命令

完整的环境配置见 `reference_xiangshan_build.md`（memory），包括 aarch64 espresso
重新构建以及 firtool 在 qemu 下运行的路径。
在 NPU 服务器上的简短形式如下：

```bash
cd /home/Ray/XiangShan && NOOP_HOME=/home/Ray/XiangShan \
  mill -i -Djvm-xmx=48G xiangshan.test.runMain top.XiangShanSim \
    --target-dir /home/Ray/XiangShan/build --config CHIConfig \
    --num-cores 2 --issue E.b --target chirrtl \
    --enable-difftest --full-stacktrace

qemu-x86_64 /root/.cache/llvm-firtool/1.135.0/bin/firtool \
  /home/Ray/XiangShan/build/SimTop.fir --split-verilog \
  -o /home/Ray/XiangShan/build \
  --default-layer-specialization=enable \
  --disable-all-randomization \
  --lowering-options=emittedLineLength=120
```

## 有意未做的事项

- **没有 `DualCoreSharedLLCConfig` 别名** —— `CHIConfig(2)` 就是
  规范入口；再加一个命名别名只是表面文章。
- **没有新增顶层模块** —— `XSTop` 已经支持 N 核 CHI。
- **没有跨插槽桥接** —— 那是更大路线图的第二阶段，
  在 `docs/design-cross-socket-bridge.md` 中跟踪。
- **没有 EMU 或 NEMU 构建** —— 需要 Linux；NEMU 无法在 macOS ARM64 上
  原生构建（mcontext_t 冲突）。
