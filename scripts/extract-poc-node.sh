#!/bin/bash
set -euo pipefail

# 工作目录设定
WORKSPACE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="$WORKSPACE_DIR/output"
ASSETS_DIR="$WORKSPACE_DIR/mobile/app/src/main/assets"
TEMP_DIR="$WORKSPACE_DIR/mobile/build/tmp_poc"

BOOTSTRAP_ZIP="$OUTPUT_DIR/stapk-bootstrap-aarch64.zip"
POC_ZIP="$ASSETS_DIR/runtime-poc.zip"

if [ ! -f "$BOOTSTRAP_ZIP" ]; then
    echo "未找到 $BOOTSTRAP_ZIP"
    exit 1
fi

echo "清空并创建临时目录..."
rm -rf "$TEMP_DIR"
mkdir -p "$TEMP_DIR/runtime/bin"
mkdir -p "$TEMP_DIR/runtime/lib"
mkdir -p "$ASSETS_DIR"

echo "解压 bootstrap 到临时目录..."
unzip -q "$BOOTSTRAP_ZIP" -d "$TEMP_DIR/bootstrap"

echo "提取 node 二进制..."
cp "$TEMP_DIR/bootstrap/usr/bin/node" "$TEMP_DIR/runtime/bin/"

echo "提取 node 依赖的动态库..."
# 这里我们直接把常见的 node 依赖考出来
# 如果 ldd 可用的话可以用来精确收集，但在非 aarch64 环境下可能不行
# 简单的做法是把所有相关 .so 复制出来
cp "$TEMP_DIR/bootstrap/usr/lib/libicu"*.so* "$TEMP_DIR/runtime/lib/" 2>/dev/null || true
cp "$TEMP_DIR/bootstrap/usr/lib/libz.so"* "$TEMP_DIR/runtime/lib/" 2>/dev/null || true
cp "$TEMP_DIR/bootstrap/usr/lib/libcrypto.so"* "$TEMP_DIR/runtime/lib/" 2>/dev/null || true
cp "$TEMP_DIR/bootstrap/usr/lib/libssl.so"* "$TEMP_DIR/runtime/lib/" 2>/dev/null || true
cp "$TEMP_DIR/bootstrap/usr/lib/libcares.so"* "$TEMP_DIR/runtime/lib/" 2>/dev/null || true
cp "$TEMP_DIR/bootstrap/usr/lib/libnghttp2.so"* "$TEMP_DIR/runtime/lib/" 2>/dev/null || true
cp "$TEMP_DIR/bootstrap/usr/lib/libbrotli"*.so* "$TEMP_DIR/runtime/lib/" 2>/dev/null || true
cp "$TEMP_DIR/bootstrap/usr/lib/libc++_shared.so" "$TEMP_DIR/runtime/lib/" 2>/dev/null || true

echo "生成依赖清单..."
mkdir -p "$WORKSPACE_DIR/docs/superpowers/specs"
DEPENDENCY_LIST="$WORKSPACE_DIR/docs/superpowers/specs/runtime-dependencies.md"

echo "# Runtime POC 依赖清单" > "$DEPENDENCY_LIST"
echo "## 二进制" >> "$DEPENDENCY_LIST"
echo "- node" >> "$DEPENDENCY_LIST"
echo "## 动态库" >> "$DEPENDENCY_LIST"
ls -1 "$TEMP_DIR/runtime/lib" | sed 's/^/- /' >> "$DEPENDENCY_LIST"

echo "打包 runtime-poc.zip..."
cd "$TEMP_DIR"
zip -qr "$POC_ZIP" runtime/

echo "清理临时文件..."
cd "$WORKSPACE_DIR"
rm -rf "$TEMP_DIR"

echo "完成！生成的 ZIP 位于：$POC_ZIP"
