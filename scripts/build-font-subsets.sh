#!/usr/bin/env bash
# 中文字体子集化脚本 —— 为 MoRealm 阅读器生成可打包的子集 ttf
#
# 完整 Noto Serif SC / Noto Sans SC 单体超过 60MB，无法直接打包进 APK。
# 用 fonttools 的 pyftsubset 抽取常用汉字 + 标点 + 拉丁，typical 体积 1.5-3MB / 字体。
#
# 依赖:
#   pip install fonttools brotli
#
# 输入:
#   scripts/font-sources/  下放完整字体（自行下载，许可：Noto SIL OFL）
#     - NotoSerifSC-Regular.otf
#     - NotoSansSC-Regular.otf
#     - 楷体 / 仿宋 用户自选（推荐 LXGW 的开源方案）
#
# 输出:
#   app/src/main/assets/fonts/
#     - NotoSerifSC.ttf   ← 命名必须严格匹配 FontRepository.assetFontFiles
#     - NotoSansSC.ttf
#     - Kaiti.ttf
#     - FangSong.ttf

set -euo pipefail

SRC_DIR="$(cd "$(dirname "$0")" && pwd)/font-sources"
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/fonts"
GLYPHS_FILE="$(dirname "$0")/common-chinese-glyphs.txt"

if [[ ! -d "$SRC_DIR" ]]; then
    echo "[!] 源目录不存在: $SRC_DIR"
    echo "    请先放入 NotoSerifSC-Regular.otf / NotoSansSC-Regular.otf 等完整字体"
    exit 1
fi

if [[ ! -f "$GLYPHS_FILE" ]]; then
    echo "[!] 字符集文件不存在: $GLYPHS_FILE"
    echo "    建议用 GB18030 一级字库（约 7000 字）+ 二级常用 1000 字 + ASCII + 中文标点"
    exit 1
fi

mkdir -p "$OUT_DIR"

# 公共参数：保留 OpenType layout 表（衬线连笔）+ 字距，输出 ttf（Android 兼容性最好）
COMMON_OPTS=(
    "--text-file=$GLYPHS_FILE"
    "--no-hinting"
    "--desubroutinize"
    "--layout-features=*"
    "--flavor=ttf"
    "--drop-tables=DSIG"
)

subset() {
    local in="$1"
    local out="$2"
    if [[ ! -f "$SRC_DIR/$in" ]]; then
        echo "  [skip] $in 不存在，跳过"
        return
    fi
    echo "  [subset] $in -> $out"
    pyftsubset "$SRC_DIR/$in" --output-file="$OUT_DIR/$out" "${COMMON_OPTS[@]}"
    local size_kb
    size_kb=$(du -k "$OUT_DIR/$out" | cut -f1)
    echo "           输出 ${size_kb}KB"
}

echo "==> 子集化中文字体到 $OUT_DIR"
subset "NotoSerifSC-Regular.otf" "NotoSerifSC.ttf"
subset "NotoSansSC-Regular.otf" "NotoSansSC.ttf"
subset "Kaiti-Regular.ttf" "Kaiti.ttf"
subset "FangSong-Regular.ttf" "FangSong.ttf"

echo "==> 完成。下次构建 APK 时会一并打包，FontRepository 会自动加载。"
