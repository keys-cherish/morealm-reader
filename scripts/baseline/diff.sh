#!/usr/bin/env bash
# Baseline snapshot diff helper.
#
# Compares temp/baseline-snapshots/v1.5-pre-d-refactor/ (frozen baseline)
# vs temp/baseline-snapshots/current/ (latest capture).
#
# 输出：
#  - sha256 hash diff（精确匹配，零视觉变化阶段验收）
#  - 像素 diff（需要 ImageMagick `compare`，可选）
#
# Usage:
#   scripts/baseline/diff.sh             # sha256 diff only
#   scripts/baseline/diff.sh --pixel     # 加跑像素 diff（需 ImageMagick）
#   scripts/baseline/diff.sh --baseline=<dir>   # 指定基准目录（默认 v1.5-pre-d-refactor）

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

BASELINE_DIR="temp/baseline-snapshots/v1.5-pre-d-refactor"
CURRENT_DIR="temp/baseline-snapshots/current"
RUN_PIXEL_DIFF=0

for arg in "$@"; do
    case "$arg" in
        --pixel) RUN_PIXEL_DIFF=1 ;;
        --baseline=*) BASELINE_DIR="${arg#--baseline=}" ;;
        *) echo "Unknown arg: $arg" >&2; exit 1 ;;
    esac
done

if [ ! -d "$BASELINE_DIR" ]; then
    echo "[ERROR] Baseline dir not found: $BASELINE_DIR" >&2
    echo "        Run capture.sh first to create initial baseline." >&2
    exit 1
fi
if [ ! -d "$CURRENT_DIR" ]; then
    echo "[ERROR] Current dir not found: $CURRENT_DIR" >&2
    echo "        Run capture.sh to snapshot current state." >&2
    exit 1
fi

echo "=== Baseline diff ==="
echo "Baseline: $BASELINE_DIR"
echo "Current:  $CURRENT_DIR"
echo ""

# 收集两侧 PNG 文件名
BASELINE_FILES=$(cd "$BASELINE_DIR" && find . -name "*.png" -type f | sort)
CURRENT_FILES=$(cd "$CURRENT_DIR" && find . -name "*.png" -type f | sort)

# 缺失检查
MISSING_IN_CURRENT=$(comm -23 <(echo "$BASELINE_FILES") <(echo "$CURRENT_FILES"))
EXTRA_IN_CURRENT=$(comm -13 <(echo "$BASELINE_FILES") <(echo "$CURRENT_FILES"))

if [ -n "$MISSING_IN_CURRENT" ]; then
    echo "[MISSING in current/]"
    echo "$MISSING_IN_CURRENT" | sed 's/^/  /'
    echo ""
fi
if [ -n "$EXTRA_IN_CURRENT" ]; then
    echo "[EXTRA in current/ (not in baseline)]"
    echo "$EXTRA_IN_CURRENT" | sed 's/^/  /'
    echo ""
fi

# sha256 diff
echo "=== sha256 diff ==="
SAME=0
DIFF=0
COMMON=$(comm -12 <(echo "$BASELINE_FILES") <(echo "$CURRENT_FILES"))

for f in $COMMON; do
    BASELINE_HASH=$(sha256sum "$BASELINE_DIR/$f" | awk '{print $1}')
    CURRENT_HASH=$(sha256sum "$CURRENT_DIR/$f" | awk '{print $1}')
    if [ "$BASELINE_HASH" = "$CURRENT_HASH" ]; then
        SAME=$((SAME + 1))
        echo "  [SAME]    $f"
    else
        DIFF=$((DIFF + 1))
        echo "  [DIFF]    $f"
        echo "    baseline: $BASELINE_HASH"
        echo "    current:  $CURRENT_HASH"
    fi
done

echo ""
echo "Summary: $SAME identical, $DIFF differs (of $(echo "$COMMON" | wc -l | tr -d ' ') common)"

# 像素 diff（可选）
if [ "$RUN_PIXEL_DIFF" -eq 1 ]; then
    if ! command -v compare >/dev/null 2>&1; then
        echo ""
        echo "[WARN] ImageMagick 'compare' not found, skipping pixel diff" >&2
        exit 0
    fi
    echo ""
    echo "=== Pixel diff (ImageMagick) ==="
    DIFF_DIR="temp/baseline-snapshots/diff"
    mkdir -p "$DIFF_DIR"
    for f in $COMMON; do
        BASELINE_HASH=$(sha256sum "$BASELINE_DIR/$f" | awk '{print $1}')
        CURRENT_HASH=$(sha256sum "$CURRENT_DIR/$f" | awk '{print $1}')
        if [ "$BASELINE_HASH" != "$CURRENT_HASH" ]; then
            OUT="$DIFF_DIR/$(basename "$f" .png)-diff.png"
            METRIC=$(compare -metric AE "$BASELINE_DIR/$f" "$CURRENT_DIR/$f" "$OUT" 2>&1 || true)
            echo "  $f: $METRIC px different (diff PNG: $OUT)"
        fi
    done
fi

# Exit code: 0 = all same; 1 = some diff
if [ "$DIFF" -gt 0 ] || [ -n "$MISSING_IN_CURRENT" ] || [ -n "$EXTRA_IN_CURRENT" ]; then
    exit 1
fi
exit 0
