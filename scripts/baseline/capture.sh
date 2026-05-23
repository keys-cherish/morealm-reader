#!/usr/bin/env bash
# Baseline snapshot capture helper.
#
# 装机后翻到目标页 → 跑此 script → adb 截屏 → 入仓 current/。
#
# 用法（单页）：
#   scripts/baseline/capture.sh 04-sample-epub-vol-title
#   → 调 `adb shell screencap` 截当前屏 → 存 temp/baseline-snapshots/current/04-sample-epub-vol-title.png
#
# 用法（批量后 freeze 为 baseline）：
#   scripts/baseline/capture.sh --freeze v1.5-pre-d-refactor
#   → cp current/* v1.5-pre-d-refactor/  +  生成 manifest.txt
#
# 用法（确认 adb 可用）：
#   scripts/baseline/capture.sh --check
#
# 命名约定：NN-short-name（NN = 01-99 双位数，按代表页清单排序）

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

CURRENT_DIR="temp/baseline-snapshots/current"
mkdir -p "$CURRENT_DIR"

case "${1:-}" in
    --check)
        if ! command -v adb >/dev/null 2>&1; then
            echo "[ERROR] adb not found in PATH" >&2; exit 1
        fi
        if ! adb get-state >/dev/null 2>&1; then
            echo "[ERROR] No connected device. Run 'adb devices' to check." >&2; exit 1
        fi
        echo "[OK] adb device:"
        adb devices
        exit 0
        ;;
    --freeze)
        TARGET="${2:?usage: capture.sh --freeze <baseline-dir-name>}"
        TARGET_DIR="temp/baseline-snapshots/$TARGET"
        mkdir -p "$TARGET_DIR"
        echo "Freezing current/ → $TARGET_DIR/"
        cp -v "$CURRENT_DIR"/*.png "$TARGET_DIR/" 2>/dev/null || true
        # 生成 manifest.txt
        cd "$TARGET_DIR"
        sha256sum *.png > manifest.txt 2>/dev/null || echo "(no PNGs)" > manifest.txt
        COMMIT_HASH=$(cd "$REPO_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo "uncommitted")
        echo "# Frozen at $(date -u +%Y-%m-%dT%H:%M:%SZ) (commit $COMMIT_HASH)" >> manifest.txt
        echo "Done. Manifest:"
        cat manifest.txt
        exit 0
        ;;
    "")
        echo "Usage: $0 <name>          # capture current screen → current/{name}.png"
        echo "       $0 --check         # verify adb device connected"
        echo "       $0 --freeze <dir>  # freeze current/ → temp/baseline-snapshots/<dir>/"
        echo ""
        echo "Example workflow:"
        echo "  1. Install APK + open MoRealm on device"
        echo "  2. Manually navigate to target chapter/page"
        echo "  3. Run: $0 04-sample-epub-vol-title"
        echo "  4. Repeat for all 12 representative pages"
        echo "  5. Run: $0 --freeze v1.5-pre-d-refactor  (only once, after all 12 captured)"
        exit 0
        ;;
esac

NAME="$1"
OUT="$CURRENT_DIR/$NAME.png"

if ! command -v adb >/dev/null 2>&1; then
    echo "[ERROR] adb not found in PATH" >&2; exit 1
fi
if ! adb get-state >/dev/null 2>&1; then
    echo "[ERROR] No connected device" >&2; exit 1
fi

echo "Capturing → $OUT"
adb shell screencap -p /sdcard/baseline_temp.png
adb pull /sdcard/baseline_temp.png "$OUT" >/dev/null
adb shell rm /sdcard/baseline_temp.png
echo "[OK] saved: $OUT  ($(du -h "$OUT" | cut -f1))"
