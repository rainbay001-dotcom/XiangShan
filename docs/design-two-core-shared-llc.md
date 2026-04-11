# Two-core XiangShan with per-core L2 and shared OpenLLC

This document records the design, verification, and build workflow for a
two-core XiangShan system in which each core owns a private CoupledL2 and
both L2s share a single OpenLLC instance acting as the CHI Home Node (HN-F).

## TL;DR

- **No new Chisel required.** `CHIConfig(2)` already instantiates two
  `XSTile`s (each with its own L2) and binds both to a single shared
  `OpenLLC` in `Top.scala`. The CHI path was designed from day one to
  scale with `NumCores`.
- **One bug was found and fixed** in `TLMinimalConfig`'s OpenLLC
  parameter construction (see _Bug fix_ below).
- **Elaboration verified.** Both `CHIConfig(2)` and the fixed
  `CHIMinimalConfig(2)` successfully elaborate the 2-core shared-OpenLLC
  topology with `numRNs == 2`.
- **`.fir` emission blocked on memory.** The Chisel Converter stage
  needs more than 12 GB of JVM heap, and Docker Desktop on this Mac is
  capped at 15.6 GiB total. Bump Docker Desktop → Settings → Resources →
  Memory to **≥24 GB** to finish `.fir` serialization and run firtool.

## Topology

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

Address routing in `Top.scala:372-378`:
- `[0x0000_0000, 0x7fff_ffff]`  → per-core MMIO bridge (NodeID `NumCores + i`)
- `[0x8000_0000, 0xffff_ffff_ffff]` → shared OpenLLC (NodeID `NumCores*2`)

## Why zero new Chisel was needed

`CHIConfig(n)` → `TLConfig(n)` + `WithCHI` already does all the heavy
lifting:

1. `Configs.scala:424` — OpenLLC `clientCaches = tiles.map { core => ... }`
   grows the client directory to one entry per tile automatically.
2. `Top.scala:301-310` — when `enableCHI`, a single `OpenLLC` module is
   instantiated and given `hartIds = tiles.map(_.HartId)`.
3. `Top.scala:368-383` — a `for ((core, i) <- core_with_l2.zipWithIndex)`
   loop binds each core's CHI port to `chi_openllc_opt.io.rn(i)` via
   address-based routing.
4. `Top.scala:432-434` — each tile gets a distinct CHI NodeID
   (`tile.module.io.nodeID := i.U`).
5. `Top.scala:387` — OpenLLC's own NodeID is set to `NumCores*2`.
6. `openLLC/LLCParam.scala:89` — `def numRNs = cacheParams.clientCaches.size`
   derives the RN-F port count from config.
7. `openLLC/LLCParam.scala:90` — `if (numRNs == 1) "Exclusive" else "Non-inclusive"`
   makes multi-RN-F an **explicit, tested design branch**, not an
   afterthought.
8. `openLLC/OpenLLC.scala:38` — `val rn = Vec(numRNs, Flipped(new PortIO))`
   scales port vectors with `numRNs`.

## Bug fix: `TLMinimalConfig` hardcoded `clientCaches`

`TLMinimalConfig` (the parent of `CHIMinimalConfig`) had:

```scala
OpenLLCParamsOpt = Option.when(up(EnableCHI))(OpenLLCParam(
  ...
  clientCaches = Seq(L2Param())     // <-- single entry, ignores NumCores
))
```

This meant even with `--num-cores 2`, `numRNs == 1` and the bind loop at
`Top.scala:380` would throw `IndexOutOfBoundsException: 1 is out of
bounds (min 0, max 0)` when binding the second core's CHI port to
`chi_openllc_opt.io.rn(1)`.

**Fix** (`src/main/scala/top/Configs.scala`):

```scala
clientCaches = tiles.map(_ => L2Param())
```

After the fix, `CHIMinimalConfig(2)` elaborates cleanly with `numRNs == 2`,
proven by the firrtl-emitter warning:

```
openLLC/src/main/scala/openLLC/MainPipe.scala 111:60: [W004]
  Dynamic index with width 11 is too wide for Vec of size 2 (expected
  index width 1).
```

Before the fix the same line reported `Vec of size 1`.

The full `TLConfig` / `CHIConfig` path was already correct because it
uses `clientCaches = tiles.map { core => ... }`.

## Build commands

Set Docker Desktop memory to **≥24 GB** before running.

```bash
# from the repo root on macOS
docker run --rm --platform linux/amd64 --entrypoint /bin/bash \
  -v "$PWD:/work" -w /work \
  -e NOOP_HOME=/work -e NEMU_HOME=/work/NEMU \
  -e GIT_DISCOVERY_ACROSS_FILESYSTEM=1 \
  xsdev-local:latest -c '
    git config --global --add safe.directory "*" &&
    mill -i -Djvm-xmx=20G xiangshan.test.runMain top.XiangShanSim \
      --target-dir /work/build \
      --config CHIConfig \
      --num-cores 2 \
      --issue E.b \
      --target chirrtl \
      --enable-difftest \
      --full-stacktrace
  '
```

Then run firtool separately (splits to keep JVM and firtool heaps from
competing):

```bash
docker run --rm --platform linux/amd64 --entrypoint /bin/bash \
  -v "$PWD:/work" -w /work xsdev-local:latest -c '
    /root/.cache/llvm-firtool/1.135.0/bin/firtool /work/build/SimTop.fir \
      --split-verilog -o /work/build \
      --default-layer-specialization=enable \
      --disable-all-randomization \
      --lowering-options=emittedLineLength=120
  '
```

## What was proven on macOS (15.6 GiB Docker)

| Check                                           | Status                                                         |
|-------------------------------------------------|----------------------------------------------------------------|
| `mill xiangshan.compile` succeeds               | ✅ ~3.5 min                                                    |
| No `NumCores == 1` guards in CHI path           | ✅ zero grep matches                                           |
| Per-tile distinct CHI NodeIDs                   | ✅ `Top.scala:432-434` `nodeID := i.U`                         |
| OpenLLC `numRNs` derived from `clientCaches`    | ✅ `LLCParam.scala:89`                                         |
| Multi-RN is explicit code path                  | ✅ `LLCParam.scala:90` "Non-inclusive" branch                  |
| `CHIConfig(2)` elaborates with `numRNs=2`       | ✅ firrtl warning `Vec of size 2` in openLLC/MainPipe          |
| `CHIMinimalConfig(2)` (after fix) elaborates    | ✅ same firrtl warning, 140 elab warnings, no errors           |
| `SimTop.fir` emitted                            | ⚠️ OOM-killed in Chisel Converter at `-Xmx13G` (15.6 GiB cap)  |
| `.sv` files generated                           | ⚠️ blocked on `.fir`                                           |

## What's left (future session, ≥24 GB Docker)

1. Rerun the build command above, confirm `SimTop.fir` is produced.
2. Run firtool, confirm `.sv` files are produced.
3. Five RTL sanity checks on the generated Verilog:
   - `XSTile` instantiated **2** times in SimTop
   - `OpenLLC` instantiated **1** time
   - OpenLLC has both `io_rn_0_*` and `io_rn_1_*` CHI port groups
   - OpenLLC `nodeID` constant equals 4 (`NumCores*2`)
   - Two L2Tops driven by distinct `nodeID` inputs (0 and 1)

## What was intentionally not done

- No `DualCoreSharedLLCConfig` alias — `CHIConfig(2)` is canonical.
- No new top-level module — `XSTop` already handles N-core CHI.
- No cross-socket bridge — that's Stage 2 of the broader roadmap,
  tracked in `docs/design-cross-socket-bridge.md`.
- No EMU or NEMU build — needs Linux per `reference_xiangshan_build.md`.
