#!/bin/bash
# chi-sim.sh — XiangShan CHIHNSubNode 编译与 VCS 仿真一键脚本
#
# 用法示例：
#   # 完整流程（构建测试程序 → 生成 RTL → 构建 simv → 运行仿真）
#   bash scripts/chi-sim.sh --all
#
#   # 仅运行仿真（已有 ./build/simv 和 test bin）
#   bash scripts/chi-sim.sh --run --workload /path/to/memrw-riscv64-xs.bin
#
#   # 仅构建 nexus-am 测试程序
#   bash scripts/chi-sim.sh --build-test
#
#   # 仅构建 simv（已有 RTL）
#   bash scripts/chi-sim.sh --build-simv
#
# 环境变量（可提前 export，也可在命令行前缀指定）：
#   NOOP_HOME      XiangShan 工程根目录（默认：脚本所在目录的父目录）
#   AM_HOME        nexus-am 根目录（默认：${NOOP_HOME}/../nexus-am）
#   NEMU_HOME      NEMU 根目录（默认：${NOOP_HOME}/../NEMU）
#   VERDI_HOME     Synopsys Verdi 安装路径（用于 FSDB 波形，可选）
#   FIRTOOL        firtool 二进制路径（可选，不设则由 firtool-resolver 自动下载）
#   REF_SO         参考模型 .so 路径（默认：${NOOP_HOME}/ready-to-run/riscv64-nemu-interpreter-so）

set -euo pipefail

# ============================================================
# 颜色输出
# ============================================================
if [ -t 1 ]; then
    RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
    BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'
else
    RED=''; GREEN=''; YELLOW=''; BLUE=''; CYAN=''; NC=''
fi
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[ OK ]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error() { echo -e "${RED}[ERR ]${NC}  $*" >&2; }
die()   { error "$*"; exit 1; }

# ============================================================
# 路径解析
# ============================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NOOP_HOME="${NOOP_HOME:-$(cd "$SCRIPT_DIR/.." && pwd)}"
AM_HOME="${AM_HOME:-$(cd "$NOOP_HOME/../nexus-am" 2>/dev/null && pwd || echo "")}"
NEMU_HOME="${NEMU_HOME:-$(cd "$NOOP_HOME/../NEMU" 2>/dev/null && pwd || echo "")}"
REF_SO="${REF_SO:-${NOOP_HOME}/ready-to-run/riscv64-nemu-interpreter-so}"

# ============================================================
# 默认参数
# ============================================================
CONFIG="${CONFIG:-CHIConfig}"
NUM_CORES="${NUM_CORES:-2}"
ISSUE="${ISSUE:-E.b}"
JOBS="${JOBS:-$(nproc)}"
MAX_CYCLES="${MAX_CYCLES:-50000000}"
WORKLOAD=""
WAVE_FORMAT="${WAVE_FORMAT:-}"       # 留空则不输出波形；vpd 或 fsdb
CONSIDER_FSDB="${CONSIDER_FSDB:-0}"

# 操作开关（全部默认 off，通过参数开启）
DO_BUILD_TEST=0
DO_BUILD_SIMV=0
DO_RUN=0
DO_ALL=0

# ============================================================
# 帮助信息
# ============================================================
usage() {
    cat <<EOF
用法: bash scripts/chi-sim.sh [选项]

操作选项（至少选一个）：
  --all             执行全部步骤：构建测试程序 → 生成 RTL → 构建 simv → 运行仿真
  --build-test      编译 tests/memrw 测试程序（需要 AM_HOME）
  --build-simv      构建 VCS 仿真器 ./build/simv
  --run             运行 VCS 仿真（需要 --workload 或已构建好的 memrw bin）

仿真参数：
  --workload PATH   指定待运行的 .bin 文件（不指定则用 memrw 构建结果）
  --ref-so PATH     参考模型 .so 路径（默认：${REF_SO}）
  --max-cycles N    最大仿真周期数（默认：${MAX_CYCLES}）
  --wave vpd|fsdb   输出波形文件格式（不设则不输出波形）
  --no-diff         禁用 difftest（不使用参考模型）

构建参数：
  --config CFG      Makefile CONFIG（默认：${CONFIG}）
  --cores N         NUM_CORES（默认：${NUM_CORES}）
  --issue ISSUE     CHI issue（默认：${ISSUE}，可选 B/C/E.b）
  --jobs N          并行作业数（默认：nproc = ${JOBS}）

环境：
  --noop-home PATH  NOOP_HOME
  --am-home PATH    AM_HOME（nexus-am 路径）
  --nemu-home PATH  NEMU_HOME
  --verdi PATH      VERDI_HOME（FSDB 波形需要）
  --firtool PATH    firtool 二进制路径

  -h, --help        显示此帮助
EOF
    exit 0
}

# ============================================================
# 参数解析
# ============================================================
NO_DIFF=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --all)            DO_ALL=1 ;;
        --build-test)     DO_BUILD_TEST=1 ;;
        --build-simv)     DO_BUILD_SIMV=1 ;;
        --run)            DO_RUN=1 ;;
        --workload)       WORKLOAD="$2"; shift ;;
        --ref-so)         REF_SO="$2"; shift ;;
        --max-cycles)     MAX_CYCLES="$2"; shift ;;
        --wave)           WAVE_FORMAT="$2"; shift ;;
        --no-diff)        NO_DIFF=1 ;;
        --config)         CONFIG="$2"; shift ;;
        --cores)          NUM_CORES="$2"; shift ;;
        --issue)          ISSUE="$2"; shift ;;
        --jobs)           JOBS="$2"; shift ;;
        --noop-home)      NOOP_HOME="$2"; shift ;;
        --am-home)        AM_HOME="$2"; shift ;;
        --nemu-home)      NEMU_HOME="$2"; shift ;;
        --verdi)          VERDI_HOME="$2"; shift ;;
        --firtool)        FIRTOOL="$2"; shift ;;
        -h|--help)        usage ;;
        *)                die "未知参数: $1，请用 --help 查看帮助" ;;
    esac
    shift
done

if [[ $DO_ALL -eq 1 ]]; then
    DO_BUILD_TEST=1; DO_BUILD_SIMV=1; DO_RUN=1
fi

if [[ $DO_BUILD_TEST -eq 0 && $DO_BUILD_SIMV -eq 0 && $DO_RUN -eq 0 ]]; then
    usage
fi

# ============================================================
# 环境检查
# ============================================================
info "NOOP_HOME  = $NOOP_HOME"
info "AM_HOME    = ${AM_HOME:-<未设置>}"
info "REF_SO     = $REF_SO"
info "CONFIG     = $CONFIG  NUM_CORES=$NUM_CORES  ISSUE=$ISSUE"

[[ -d "$NOOP_HOME" ]] || die "NOOP_HOME 不存在: $NOOP_HOME"
cd "$NOOP_HOME"

# ============================================================
# 步骤 1：构建 nexus-am 测试程序
# ============================================================
build_test() {
    info "=== 步骤 1：构建 nexus-am memrw 测试程序 ==="
    [[ -n "$AM_HOME" && -d "$AM_HOME" ]] || die "AM_HOME 未设置或路径不存在: ${AM_HOME:-<空>}"
    export AM_HOME
    make -C "$NOOP_HOME/tests/memrw" ARCH=riscv64-xs -j"$JOBS"
    BUILT_BIN="$NOOP_HOME/tests/memrw/build/memrw-riscv64-xs.bin"
    [[ -f "$BUILT_BIN" ]] || die "构建失败，未找到: $BUILT_BIN"
    ok "测试程序已生成: $BUILT_BIN"
    # 如果用户没有手动指定 workload，用刚构建的 bin
    if [[ -z "$WORKLOAD" ]]; then
        WORKLOAD="$BUILT_BIN"
    fi
}

# ============================================================
# 步骤 2：构建 VCS 仿真器（simv）
# ============================================================
build_simv() {
    info "=== 步骤 2：生成仿真 RTL + 构建 VCS simv ==="

    # 设置 Verdi/FSDB 环境（如果有）
    if [[ -n "${VERDI_HOME:-}" && -d "${VERDI_HOME}" ]]; then
        export VERDI_HOME
        export LD_LIBRARY_PATH="${VERDI_HOME}/share/PLI/lib/LINUXAMD64:${LD_LIBRARY_PATH:-}"
        CONSIDER_FSDB=1
        info "VERDI_HOME = $VERDI_HOME (FSDB 已启用)"
    else
        warn "VERDI_HOME 未设置，将跳过 FSDB 支持（可通过 --verdi 指定）"
    fi

    # firtool 路径（可选）
    FIRTOOL_MAKE_ARG=""
    if [[ -n "${FIRTOOL:-}" ]]; then
        [[ -x "${FIRTOOL}" ]] || die "firtool 不存在或不可执行: $FIRTOOL"
        FIRTOOL_MAKE_ARG="FIRTOOL=${FIRTOOL}"
        info "firtool = $FIRTOOL"
    fi

    # 生成仿真 Verilog
    info "生成仿真 Verilog（sim-verilog）..."
    make sim-verilog CONFIG="$CONFIG" NUM_CORES="$NUM_CORES" ISSUE="$ISSUE" $FIRTOOL_MAKE_ARG

    # 构建 simv
    info "构建 VCS simv..."
    make simv CONFIG="$CONFIG" NUM_CORES="$NUM_CORES" ISSUE="$ISSUE" \
        CONSIDER_FSDB="$CONSIDER_FSDB" -j"$JOBS"

    [[ -f "$NOOP_HOME/build/simv" ]] || die "simv 构建失败，未找到 $NOOP_HOME/build/simv"
    ok "simv 构建成功: $NOOP_HOME/build/simv"
}

# ============================================================
# 步骤 3：运行 VCS 仿真
# ============================================================
run_sim() {
    info "=== 步骤 3：运行 VCS 仿真 ==="

    SIMV="$NOOP_HOME/build/simv"
    [[ -f "$SIMV" ]] || die "simv 不存在，请先运行 --build-simv: $SIMV"

    # 确定 workload
    if [[ -z "$WORKLOAD" ]]; then
        # 尝试自动找到 memrw bin
        CANDIDATE="$NOOP_HOME/tests/memrw/build/memrw-riscv64-xs.bin"
        if [[ -f "$CANDIDATE" ]]; then
            WORKLOAD="$CANDIDATE"
            info "自动使用测试程序: $WORKLOAD"
        else
            die "未指定 --workload，且未找到 memrw bin: $CANDIDATE\n请先运行 --build-test 或手动指定 --workload"
        fi
    fi
    [[ -f "$WORKLOAD" ]] || die "workload 不存在: $WORKLOAD"
    info "workload  = $WORKLOAD"

    # 组装运行参数
    SIM_ARGS="+workload=${WORKLOAD} +max-cycles=${MAX_CYCLES}"

    if [[ $NO_DIFF -eq 1 ]]; then
        SIM_ARGS="$SIM_ARGS +no-diff"
        info "difftest 已禁用（+no-diff）"
    else
        [[ -f "$REF_SO" ]] || die "参考模型不存在: $REF_SO\n请通过 --ref-so 或 REF_SO 指定，或用 --no-diff 禁用 difftest"
        SIM_ARGS="$SIM_ARGS +diff=${REF_SO}"
        info "ref_so    = $REF_SO"
    fi

    if [[ -n "$WAVE_FORMAT" ]]; then
        SIM_ARGS="$SIM_ARGS +dump-wave=${WAVE_FORMAT}"
        info "波形格式  = $WAVE_FORMAT"
        if [[ "$WAVE_FORMAT" == "fsdb" ]]; then
            warn "FSDB 需要 simv 在编译时启用 CONSIDER_FSDB=1，否则会报错"
        fi
    fi

    LOG="$NOOP_HOME/build/simv_$(basename "$WORKLOAD" .bin).log"
    info "运行命令: $SIMV $SIM_ARGS"
    info "日志输出: $LOG"
    echo ""

    # assert 参数（与 xiangshan.py 保持一致）
    ASSERT_ARGS="-assert finish_maxfail=30 -assert global_finish_maxfail=10000"

    cd "$NOOP_HOME/build"
    ./simv $SIM_ARGS $ASSERT_ARGS | tee "$LOG"
    SIM_EXIT=${PIPESTATUS[0]}
    cd "$NOOP_HOME"

    echo ""
    if grep -q "HIT GOOD TRAP" "$LOG"; then
        ok "仿真完成：HIT GOOD TRAP ✓"
        # 提取 IPC 信息
        grep -E "instrCnt|cycleCnt|IPC|DIFFTEST" "$LOG" || true
        return 0
    else
        error "仿真失败：未检测到 HIT GOOD TRAP"
        if grep -q "HIT BAD TRAP" "$LOG"; then
            error "检测到 HIT BAD TRAP"
        fi
        return 1
    fi
}

# ============================================================
# 主流程
# ============================================================
[[ $DO_BUILD_TEST -eq 1 ]] && build_test
[[ $DO_BUILD_SIMV -eq 1 ]] && build_simv
[[ $DO_RUN       -eq 1 ]] && run_sim

ok "全部步骤完成。"
