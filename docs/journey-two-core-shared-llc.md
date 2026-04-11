# Journey: bringing up two-core shared-LLC on XiangShan

A running log of the work that stood up `CHIConfig(2)` end-to-end,
captured after the fact so future debugging has a map. The end state is
described in `design-two-core-shared-llc.md`; this doc is about *how* we
got there and the yak-shaving along the way.

## Goal

Prove that two XiangShan cores, each with a private CoupledL2, can share
a single OpenLLC HN-F over CHI — and generate SystemVerilog that
demonstrates it. This is the prerequisite for the cross-socket bridge
(`docs/design-cross-socket-bridge.md`).

## Stage 0 — static code survey (Mac)

First step was a static read of the CHI path to find out how much new
Chisel would be required. The answer turned out to be: **none**.

- `Top.scala:301-310` already wraps `OpenLLC` in `Option.when(enableCHI)`
  and passes `hartIds = tiles.map(_.HartId)`.
- `Top.scala:368-383` binds each core in a `zipWithIndex` loop:
  `chi_openllc_opt.get.io.rn(i) <> llcLogger.io.down`.
- `Top.scala:432-434` drives each tile's CHI NodeID to `i.U`.
- `openLLC/LLCParam.scala:89` derives `numRNs` from
  `clientCaches.size`.
- `openLLC/LLCParam.scala:90` even has an explicit branch: `if (numRNs
  == 1) "Exclusive" else "Non-inclusive"`. Multi-RN is a first-class
  design path, not something we'd be bolting on.
- `openLLC/OpenLLC.scala:38` scales the port vector: `val rn =
  Vec(numRNs, Flipped(new PortIO))`.

So the plan collapsed to: pick the right config class, run the build,
check the RTL.

## Stage 1 — the `TLMinimalConfig` bug

`CHIConfig(2)` elaborated cleanly, but `CHIMinimalConfig(2)` threw:

```
IndexOutOfBoundsException: 1 is out of bounds (min 0, max 0)
  at Top.scala:380
```

Root cause was in `TLMinimalConfig` (parent of `CHIMinimalConfig`):

```scala
OpenLLCParamsOpt = Option.when(up(EnableCHI))(OpenLLCParam(
  ...
  clientCaches = Seq(L2Param())   // <-- hardcoded single entry
))
```

The full `TLConfig` / `CHIConfig` path was already correct because it
used `tiles.map { core => ... }`. Fix was a one-liner at
`Configs.scala:284`:

```scala
clientCaches = tiles.map(_ => L2Param())
```

After that, both configs elaborate with `numRNs == 2`, confirmed by the
firrtl warning `openLLC/MainPipe.scala 111:60 [W004] … Vec of size 2`
(previously `size 1`).

## Stage 2 — Mac Docker runs out of memory

First attempt to generate `SimTop.fir` was on Mac + Docker Desktop.
Elaboration ran fine, but the Chisel Converter stage blew through
`-Xmx12G` and got SIGKILLed by the kernel at around the 13 GB mark.
Bumping to `-Xmx13G` actually died *earlier*.

Docker Desktop on this 16 GB Mac M5 is hard-capped at 15.6 GiB total
container memory. That's not enough headroom for CHIConfig(2)'s Chisel
Converter. Moved workflow to the NPU server (aarch64 Linux, 2 TB RAM).

## Stage 3 — moving the repo to the NPU server

`git clone` of the fork worked but was slow on a few submodules.
Retry loop handled it eventually. This is where the first aarch64-
specific surprise showed up: **several submodule working trees were
empty** (`huancun`, `ChiselIOPMP`) despite `git submodule status`
reporting the correct commit. The fix was to deinit, wipe
`.git/modules/<mod>` and the directory, then re-init recursively.

Worth remembering: `git submodule status` only tells you about the
*recorded* commit, not whether the tree exists. If compile complains
about missing `freechips.rocketchip.*` or `huancun.*` imports, check
that the actual source files are on disk.

## Stage 4 — the espresso-is-x86_64 trap

With everything compiling, elaboration on the NPU server died with a
baffling error:

```
[749] /home/Ray/XiangShan/src/main/resources/espresso: 1: Syntax error: ")" unexpected
[749] Exception in thread "espresso stdin thread" java.io.IOException: Stream closed
```

The XiangShan repo ships a **committed x86_64 ELF** at
`src/main/resources/espresso`. On the aarch64 server the kernel can't
exec it, so the shell falls through and tries to parse the ELF bytes as
a shell script. The `)` in the binary header is what it trips on.

Fix: rebuild native aarch64 espresso from source.

```bash
git clone --depth 1 https://github.com/chipsalliance/espresso /tmp/espresso
mkdir /tmp/espresso/build && cd /tmp/espresso/build
cmake -DBUILD_DOC=OFF ..    # BUILD_DOC=OFF avoids an asciidoctor dep
make -j8
cp src/espresso /home/Ray/XiangShan/src/main/resources/espresso
```

Elaboration then got all the way through, 140 warnings, zero errors.

## Stage 5 — `NOOP_HOME` surprise

The run after the espresso fix still failed, but much later — *after*
elaboration completed:

```
Exception in thread "main" java.util.NoSuchElementException: NOOP_HOME
  at difftest.common.FileControl$.write(FileControl.scala:25)
  at difftest.util.Query$.collect(Query.scala:63)
  at difftest.DifftestModule$.collect(Difftest.scala:589)
```

difftest's `FileControl` reads `sys.env("NOOP_HOME")` during the
`collect` phase. The Dockerfile path had been setting it, but our
manual invocation hadn't. Added `NOOP_HOME=/home/Ray/XiangShan` to the
command line and that was the end of that.

This is the second time `NOOP_HOME` has bitten this project — already
in the build reference memory, but easy to forget for direct mill
invocations outside Docker.

## Stage 6 — `SimTop.fir` success

With espresso and NOOP_HOME both fixed, the third run of
`CHIConfig(2)` produced:

```
-rw-r--r--. 1 root root 2732662478 SimTop.fir
```

2.73 GB. Wall time ~830 s. 140 elaboration warnings, 0 errors. The
telltale `Vec of size 2` warning from OpenLLC/MainPipe confirmed
`numRNs = 2`.

## Stage 7 — no aarch64 firtool

With the `.fir` in hand, firtool was the next step. Problem: there is
**no aarch64 firtool binary**. Maven Central ships `llvm-firtool`
artifacts for linux-x64, macos-x64, windows-x64 — nothing for
linux-arm64. CIRCT GitHub releases are the same — `firrtl-bin-linux-x64`
but no aarch64 tarball.

Building CIRCT from source would have taken hours, so instead: install
`qemu-user` and emulate.

```bash
apt-get install -y qemu-user
qemu-x86_64 /root/.cache/llvm-firtool/1.135.0/bin/firtool --version
# LLVM version 22.0.0git / CIRCT firtool-1.135.0
```

Worked on the first try. (Note: `binfmt_misc` is not mounted in this
container, so we have to invoke `qemu-x86_64` explicitly; automatic
exec-format routing would need the kernel subsystem.)

## Stage 8 — GitHub CDN is throttled, mirror isn't

Downloading the x86_64 firtool tarball from GitHub releases hit a
throttle — about 5 KB/s. After 21 minutes only 6 MB of a 75 MB tarball
had arrived. `git clone` to github.com is fine, but the release-asset
CDN is on different infrastructure and apparently rate-limited from
this server's routing.

Switched to a Chinese GitHub proxy:

```bash
curl -L -o firrtl-bin.tar.gz \
  'https://ghproxy.net/https://github.com/llvm/circt/releases/download/firtool-1.135.0/firrtl-bin-linux-x64.tar.gz'
# 75 MB in 14 seconds — ~1000× faster
```

Saved as a memory (`reference_npu_github_proxy.md`) because this will
come up again.

## Stage 9 — firtool run under qemu

Kicked off firtool via qemu and monitored. Observations:

- Early phases were multi-threaded: cumulative CPU time climbed at
  ~12× wall time (12 threads).
- Later phases went single-threaded; CPU/wall ratio dropped to ~2.
- Total wall time: ~5 hours for the 2.73 GB `.fir` → 2058 `.sv` files.
- No `.sv` files appeared until near the very end — `--split-verilog`
  writes in a single burst after lowering completes.

The long tail was uncomfortable but didn't hit any memory pressure
(the process RSS stayed modest). A native aarch64 firtool would
obviously be faster — worth building from CIRCT source if this becomes
a frequent workflow.

## Stage 10 — RTL sanity checks

All 5 checks against the generated `XSTop.sv` / `OpenLLC.sv` pass
(report in `docs/verification/two-core-shared-llc.txt`):

1. `XSTile` instantiated 2× in XSTop — `core_with_l2` and
   `core_with_l2_1`
2. `OpenLLC` instantiated 1× — `chi_openllc_opt`
3. OpenLLC.sv has matching `io_rn_0_*` and `io_rn_1_*` port groups
   (64 wires each)
4. XSTop routing comparators use three distinct `tgtID` constants:
   `11'h2` and `11'h3` for per-core MMIO bridges, `11'h4` for the
   OpenLLC HN-F — matching `NumCores + i` and `NumCores * 2`
5. Two XSTile instances driven by distinct `.io_nodeID` constants
   (`11'h0` and `11'h1`)

## Takeaways for next time

- **Check submodule working trees, not just `git submodule status`**
  — empty trees can hide behind a correct-looking status output.
- **aarch64 XiangShan needs espresso rebuilt from source** before
  *anything* will elaborate. The committed binary is x86_64-only.
- **`NOOP_HOME` must be set for any `--enable-difftest` run**, and
  `--enable-difftest` is effectively required (`Rob.scala:1461` hits
  `None.get` without it).
- **firtool under qemu works** but takes ~5 hours for CHIConfig(2).
  Building native aarch64 firtool from CIRCT source is worth it if
  this becomes routine.
- **GitHub release-asset CDN is slow from the NPU server** — use
  `https://ghproxy.net/` as a prefix for any release download. `git
  clone` itself is unaffected.
- **Mac + Docker Desktop maxes out at 15.6 GiB** on a 16 GB Mac and
  can't handle CHIConfig(2)'s Chisel Converter. Anything beyond
  `CHIConfig(1)` belongs on the Linux server.

## What's left

- Generate a fresh `.fir` for `CHIConfig(3)+` to confirm the design
  scales past two cores (the parametric code says it should).
- Build a native aarch64 firtool from CIRCT source to avoid the 5-hour
  qemu penalty.
- Move into `design-cross-socket-bridge.md` — this two-core
  single-socket work was the prerequisite.
