#!/bin/bash
# 批量下载指定版本的 mill 到本地缓存，便于断网环境使用。
# 用法: bash scripts/mill-cache-download.sh [--cache-dir DIR]
set -euo pipefail

# ========== 配置 ==========
MILL_VERSIONS=(
  "0.12.3"
  "0.12.15"
)

# mill 下载缓存目录（与 mill bootstrap 脚本约定的路径一致）
MILL_CACHE_DIR="${HOME}/.mill/download"

# Maven 仓库列表（按优先级排列）
MAVEN_REPOS=(
  "https://repo1.maven.org/maven2"
  "https://repo.maven.apache.org/maven2"
  "https://oss.sonatype.org/content/repositories/releases"
)

# ========== 颜色输出 ==========
if [[ -t 1 ]]; then
  GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'
else
  GREEN=''; YELLOW=''; RED=''; CYAN=''; NC=''
fi

info()  { printf "${GREEN}[INFO]${NC}  %s\n" "$*"; }
warn()  { printf "${YELLOW}[WARN]${NC}  %s\n" "$*" >&2; }
error() { printf "${RED}[ERROR]${NC} %s\n" "$*" >&2; }
step()  { printf "${CYAN}[STEP]${NC}  %s\n" "$*"; }

# ========== 参数解析 ==========
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cache-dir)
      MILL_CACHE_DIR="$2"; shift 2 ;;
    --help|-h)
      echo "用法: $0 [--cache-dir DIR]"
      echo ""
      echo "  --cache-dir DIR   指定 mill 缓存目录（默认: ~/.mill/download）"
      echo ""
      echo "下载以下版本到本地缓存："
      printf '  %s\n' "${MILL_VERSIONS[@]}"
      exit 0 ;;
    *)
      error "未知参数: $1"; exit 1 ;;
  esac
done

# ========== 工具检查 ==========
check_deps() {
  local missing=()
  for cmd in curl java; do
    command -v "${cmd}" &>/dev/null || missing+=("${cmd}")
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    error "缺少必要工具: ${missing[*]}"
    error "请先安装后再运行此脚本"
    exit 1
  fi

  # sha256sum / shasum 二选一
  if command -v sha256sum &>/dev/null; then
    SHA256_CMD="sha256sum"
  elif command -v shasum &>/dev/null; then
    SHA256_CMD="shasum -a 256"
  else
    warn "未找到 sha256sum/shasum，跳过校验和验证"
    SHA256_CMD=""
  fi
}

# ========== 带重试的下载函数 ==========
# 返回 0 表示成功，非 0 表示失败
download_url() {
  local url="$1"
  local dest="$2"
  local max_retries="${3:-3}"

  local attempt
  for attempt in $(seq 1 "${max_retries}"); do
    if curl -fsSL \
        --connect-timeout 30 \
        --max-time 600 \
        --retry 3 \
        --retry-delay 2 \
        --retry-connrefused \
        --progress-bar \
        -o "${dest}" \
        "${url}" 2>&1; then
      return 0
    fi
    [[ "${attempt}" -lt "${max_retries}" ]] && \
      warn "第 ${attempt}/${max_retries} 次下载失败，${attempt} 秒后重试…"
    sleep "${attempt}"
  done
  return 1
}

# ========== SHA-256 校验 ==========
verify_checksum() {
  local file="$1"
  local url_base="$2"   # 不含 .sha256 的 URL 前缀

  [[ -z "${SHA256_CMD}" ]] && return 0  # 无工具时跳过

  local sha_tmp
  sha_tmp="$(mktemp)"
  if curl -fsSL --connect-timeout 15 --max-time 60 \
      -o "${sha_tmp}" "${url_base}.sha256" 2>/dev/null; then
    local expected actual
    expected="$(awk '{print $1}' "${sha_tmp}")"
    actual="$(${SHA256_CMD} "${file}" | awk '{print $1}')"
    rm -f "${sha_tmp}"
    if [[ "${expected}" == "${actual}" ]]; then
      info "  校验和验证通过"
      return 0
    else
      warn "  校验和不匹配！期望: ${expected}，实际: ${actual}"
      return 1
    fi
  else
    rm -f "${sha_tmp}"
    warn "  无法获取校验和文件，跳过验证"
    return 0
  fi
}

# ========== 确定 mill artifact 名称 ==========
# mill 不同版本使用不同的 artifact id
get_artifact_id() {
  local version="$1"
  local major minor
  major="$(echo "${version}" | cut -d. -f1)"
  minor="$(echo "${version}" | cut -d. -f2)"

  # mill 0.12+ 使用 mill-dist_3（Scala 3 为主）
  if [[ "${major}" -gt 0 ]] || [[ "${minor}" -ge 12 ]]; then
    echo "mill-dist_3"
  # mill 0.10-0.11 使用 mill-dist
  elif [[ "${minor}" -ge 10 ]]; then
    echo "mill-dist"
  # mill 0.9 及更早使用 mill-2.13
  else
    echo "mill-2.13"
  fi
}

# ========== 下载单个 mill 版本 ==========
download_mill_version() {
  local version="$1"
  local target="${MILL_CACHE_DIR}/${version}"

  if [[ -f "${target}" ]]; then
    info "mill ${version} 已存在于缓存，跳过。路径: ${target}"
    return 0
  fi

  local artifact_id
  artifact_id="$(get_artifact_id "${version}")"
  local artifact_filename="${artifact_id}-${version}-assembly"
  local tmp_file="${target}.tmp.$$"
  local downloaded=false

  # 1. 尝试各 Maven 仓库
  for repo in "${MAVEN_REPOS[@]}"; do
    local url="${repo}/com/lihaoyi/${artifact_id}/${version}/${artifact_filename}"
    step "尝试 Maven 仓库: ${url}"

    if download_url "${url}" "${tmp_file}"; then
      if verify_checksum "${tmp_file}" "${url}"; then
        mv "${tmp_file}" "${target}"
        chmod +x "${target}"
        downloaded=true
        info "mill ${version} 下载成功 ($(du -sh "${target}" | cut -f1))"
        break
      else
        warn "校验和不匹配，丢弃并尝试下一个源"
        rm -f "${tmp_file}"
      fi
    fi
    rm -f "${tmp_file}"
  done

  # 2. 回退到 GitHub Releases
  if ! "${downloaded}"; then
    warn "所有 Maven 仓库均失败，尝试 GitHub Releases…"
    local gh_url="https://github.com/com-lihaoyi/mill/releases/download/${version}/${artifact_filename}"
    step "GitHub Releases: ${gh_url}"

    if download_url "${gh_url}" "${tmp_file}"; then
      mv "${tmp_file}" "${target}"
      chmod +x "${target}"
      downloaded=true
      info "mill ${version} 从 GitHub Releases 下载成功 ($(du -sh "${target}" | cut -f1))"
    else
      rm -f "${tmp_file}"
    fi
  fi

  if ! "${downloaded}"; then
    error "mill ${version} 下载失败，请检查网络或版本号是否正确"
    return 1
  fi

  return 0
}

# ========== 主流程 ==========
main() {
  echo "=================================================="
  info "mill 离线缓存批量下载工具"
  info "目标版本: ${MILL_VERSIONS[*]}"
  info "缓存目录: ${MILL_CACHE_DIR}"
  echo "=================================================="

  check_deps
  mkdir -p "${MILL_CACHE_DIR}"

  local failed=()

  for version in "${MILL_VERSIONS[@]}"; do
    echo "--------------------------------------------------"
    step "处理 mill ${version}"
    echo "--------------------------------------------------"
    download_mill_version "${version}" || failed+=("${version}")
    echo ""
  done

  # ========== 结果汇总 ==========
  echo "=================================================="
  info "缓存目录内容："
  ls -lh "${MILL_CACHE_DIR}" 2>/dev/null | tail -n +2 || warn "缓存目录为空"
  echo ""

  if [[ ${#failed[@]} -gt 0 ]]; then
    error "以下版本下载失败: ${failed[*]}"
    echo ""
    error "请检查："
    error "  1. 网络连接是否正常"
    error "  2. 版本号是否存在于 Maven Central: https://repo1.maven.org/maven2/com/lihaoyi/"
    exit 1
  fi

  echo "=================================================="
  info "全部版本下载成功！"
  echo ""
  info "离线使用说明："
  info "  1. 将目录 ${MILL_CACHE_DIR} 复制到目标机器的相同路径"
  info "     例如: rsync -av ${MILL_CACHE_DIR}/ 目标机器:${MILL_CACHE_DIR}/"
  info ""
  info "  2. 目标机器上 mill bootstrap 脚本将自动使用缓存，无需联网"
  info ""
  info "  3. 若需缓存项目构建依赖（Coursier 缓存），请在联网环境下运行："
  info "     ./mill __.compile"
  info "     然后将 ~/.cache/coursier 或 ~/.ivy2 目录一并复制"
  echo "=================================================="
}

main "$@"
