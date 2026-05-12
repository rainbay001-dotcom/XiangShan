#!/bin/bash
# One-click build + RTL generation + VCS simulation for CHIHNSubNode
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NOOP_HOME="${NOOP_HOME:-$(cd "$SCRIPT_DIR/.." && pwd)}"

CONFIG="${CONFIG:-CHIConfig}"
NUM_CORES="${NUM_CORES:-2}"
ISSUE="${ISSUE:-E.b}"
CONSIDER_FSDB="${CONSIDER_FSDB:-0}"
EMU_THREADS="${EMU_THREADS:-4}"
WORKLOAD="${1:-}"

cd "$NOOP_HOME"

# ---- 1. RTL generation ----
echo "[chi-sim] Generating RTL (CONFIG=$CONFIG, NUM_CORES=$NUM_CORES, ISSUE=$ISSUE) ..."
make verilog CONFIG="$CONFIG" NUM_CORES="$NUM_CORES" ISSUE="$ISSUE" -j"$(nproc)"

# ---- 2. Build simv ----
SIMV_FLAGS="CONFIG=$CONFIG NUM_CORES=$NUM_CORES ISSUE=$ISSUE"
if [ "$CONSIDER_FSDB" = "1" ]; then
  if [ -z "${VERDI_HOME:-}" ]; then
    echo "[WARN] CONSIDER_FSDB=1 but VERDI_HOME is not set; FSDB waveform may fail"
  fi
  SIMV_FLAGS="$SIMV_FLAGS CONSIDER_FSDB=1"
fi

echo "[chi-sim] Compiling simv ..."
make simv $SIMV_FLAGS -j"$(nproc)" 2>&1 | tee "$NOOP_HOME/compile.log"

# ---- 3. Run simulation ----
if [ -z "$WORKLOAD" ]; then
  echo "[chi-sim] No workload specified. Simulation binary: $NOOP_HOME/build/simv"
  echo "Usage: $0 <path-to-workload.bin>"
  exit 0
fi

echo "[chi-sim] Running simulation: $WORKLOAD"
"$NOOP_HOME/build/simv" \
  +workload="$WORKLOAD" \
  +no-diff \
  +max-cycles=10000000 \
  2>&1 | tee "$NOOP_HOME/sim.log"

if grep -q "HIT GOOD TRAP" "$NOOP_HOME/sim.log"; then
  echo "[chi-sim] SUCCESS: HIT GOOD TRAP"
else
  echo "[chi-sim] FAIL: HIT GOOD TRAP not found"
  exit 1
fi
