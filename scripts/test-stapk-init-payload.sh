#!/bin/bash
# 回归测试：验证 stapk-init 的 payload 文件检测和提取逻辑
# 测试 .tar.gz（当前格式）和 .tar（向后兼容）两种 payload 格式
#
# 用法: bash scripts/test-stapk-init-payload.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
STAPK_INIT="$PROJECT_ROOT/upstream/termux-app/app/src/main/assets/stapk/stapk-init"

PASS=0
FAIL=0

pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }

cleanup() { rm -rf "${TMPDIR:-}" ; }
trap cleanup EXIT

# 提取 stapk-init 中的 payload 检测逻辑作为独立函数
detect_payload() {
    local ASSETS_DIR="$1"
    if [ -f "$ASSETS_DIR/SillyTavern.tar.gz" ]; then
        echo "gz:$ASSETS_DIR/SillyTavern.tar.gz:-xzf"
    elif [ -f "$ASSETS_DIR/SillyTavern.tar" ]; then
        echo "tar:$ASSETS_DIR/SillyTavern.tar:-xf"
    else
        echo "none"
    fi
}

echo "=== 测试 stapk-init payload 检测逻辑 ==="

# 测试 1: .tar.gz 存在 → 应优先匹配 .tar.gz，使用 -xzf
echo "[测试 1] .tar.gz 格式 payload 检测"
TMPDIR=$(mktemp -d)
mkdir -p "$TMPDIR/assets"
touch "$TMPDIR/assets/SillyTavern.tar.gz"
touch "$TMPDIR/assets/payload-manifest.json"

RESULT=$(detect_payload "$TMPDIR/assets")
if [[ "$RESULT" == gz:* ]] && [[ "$RESULT" == *"-xzf" ]]; then
    pass ".tar.gz 被正确检测，tar 标志 = -xzf"
else
    fail ".tar.gz 应被检测并返回 -xzf，实际结果: $RESULT"
fi

rm -rf "$TMPDIR"

# 测试 2: 只有 .tar 存在 → 应回退到 .tar，使用 -xf
echo "[测试 2] .tar 格式 payload 向后兼容检测"
TMPDIR=$(mktemp -d)
mkdir -p "$TMPDIR/assets"
touch "$TMPDIR/assets/SillyTavern.tar"
touch "$TMPDIR/assets/payload-manifest.json"

RESULT=$(detect_payload "$TMPDIR/assets")
if [[ "$RESULT" == tar:* ]] && [[ "$RESULT" == *"-xf" ]]; then
    pass ".tar 被正确检测，tar 标志 = -xf"
else
    fail ".tar 应被检测并返回 -xf，实际结果: $RESULT"
fi

rm -rf "$TMPDIR"

# 测试 3: .tar.gz 和 .tar 同时存在 → 应优先匹配 .tar.gz
echo "[测试 3] 两种格式同时存在 → 优先 .tar.gz"
TMPDIR=$(mktemp -d)
mkdir -p "$TMPDIR/assets"
touch "$TMPDIR/assets/SillyTavern.tar.gz"
touch "$TMPDIR/assets/SillyTavern.tar"
touch "$TMPDIR/assets/payload-manifest.json"

RESULT=$(detect_payload "$TMPDIR/assets")
if [[ "$RESULT" == gz:* ]] && [[ "$RESULT" == *"-xzf" ]]; then
    pass "两种格式都存在时优先 .tar.gz"
else
    fail "应优先匹配 .tar.gz，实际结果: $RESULT"
fi

rm -rf "$TMPDIR"

# 测试 4: 都不存在 → 应返回 none
echo "[测试 4] 无 payload 文件 → 错误"
TMPDIR=$(mktemp -d)
mkdir -p "$TMPDIR/assets"

RESULT=$(detect_payload "$TMPDIR/assets")
if [[ "$RESULT" == "none" ]]; then
    pass "无 payload 文件正确返回 none"
else
    fail "应返回 none，实际结果: $RESULT"
fi

rm -rf "$TMPDIR"

# 测试 5: stapk-init 脚本语法检查
echo "[测试 5] stapk-init 语法检查"
if bash -n "$STAPK_INIT" 2>&1; then
    pass "stapk-init 语法正确"
else
    fail "stapk-init 语法错误"
fi

# 测试 6: 确认脚本中包含正确的文件搜索逻辑
echo "[测试 6] 确认 stapk-init 包含 SillyTavern.tar.gz 引用"
if grep -q "SillyTavern.tar.gz" "$STAPK_INIT"; then
    pass "stapk-init 引用了 SillyTavern.tar.gz"
else
    fail "stapk-init 缺少 SillyTavern.tar.gz 引用"
fi

# 测试 7: 确认 fix_tar 使用了 -z 标志（用于 gzip）
echo "[测试 7] 确认 TAR_FLAGS 包含 -z（gzip 支持）"
if grep -q 'TAR_FLAGS="-xzf"' "$STAPK_INIT"; then
    pass "TAR_FLAGS 包含 -xzf 用于 .tar.gz 解压"
else
    fail "TAR_FLAGS 缺少 -xzf"
fi

echo ""
echo "=== 测试结果: $PASS 通过, $FAIL 失败 ==="

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
exit 0
