#!/bin/bash
set -euo pipefail

# ========== 配置 ==========
MILL_VERSIONS=(
  "0.12.3"
  "0.12.15"
)

CACHE_DIR="${HOME}/.mill/download"
MAVEN_BASE="https://repo1.maven.org/maven2/com/lihaoyi/mill-dist"
GITHUB_BASE="https://github.com/com-lihaoyi/mill/releases/download"

mkdir -p "$CACHE_DIR"

download_with_fallback() {
  local version="$1"
  local filename="$2"
  local dest="$3"

  local maven_url="${MAVEN_BASE}/${version}/${filename}"
  local github_url="${GITHUB_BASE}/${version}/${filename}"

  if [ -f "$dest" ]; then
    echo "[SKIP] $filename already exists"
    return 0
  fi

  echo "[INFO] Downloading $filename ..."
  if curl -fsSL --retry 3 -o "$dest" "$maven_url" 2>/dev/null; then
    echo "[OK]   Downloaded from Maven Central: $filename"
  elif curl -fsSL --retry 3 -o "$dest" "$github_url" 2>/dev/null; then
    echo "[OK]   Downloaded from GitHub Releases: $filename"
  else
    echo "[ERR]  Failed to download $filename from both sources" >&2
    rm -f "$dest"
    return 1
  fi
}

for VER in "${MILL_VERSIONS[@]}"; do
  echo "===== mill ${VER} ====="

  LAUNCHER="mill-${VER}"
  LAUNCHER_DEST="${CACHE_DIR}/${LAUNCHER}"
  download_with_fallback "$VER" "$LAUNCHER" "$LAUNCHER_DEST"
  chmod +x "$LAUNCHER_DEST" 2>/dev/null || true

  for EXT in jar assembly; do
    FILE="mill-dist-${VER}.${EXT}"
    DEST="${CACHE_DIR}/${FILE}"
    download_with_fallback "$VER" "$FILE" "$DEST" || true
  done

  echo ""
done

echo "===== Done. Cache directory: ${CACHE_DIR} ====="
ls -lh "$CACHE_DIR"
