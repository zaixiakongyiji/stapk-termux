# Node 24 Android arm64 Runtime 依赖清单

## 前置事实核对 (2026-07-02)

| 检查项 | 结果 |
|---|---|
| Termux `nodejs-lts` 版本 | **24.17.0** (≥ 20) |
| bootstrap arm64 `usr/bin/node` | **存在** (在 zip 中路径为 `bin/node`) |
| 运行时提取脚本 | 使用 `scripts/build-runtime-archive.sh` 生成 `runtime-android-arm64-node24.zip` |
| 旧 `extract-poc-node.sh` 状态 | 已废弃；原脚本 .so 列表不完整，缺少 `libsqlite3.so` 等 |
| Android minSdk 与 NDK ABI 兼容性 | **兼容**，aarch64 ELF |

## 产物

- `mobile/app/src/main/assets/runtime-android-arm64-node24.zip`
- `mobile/app/src/main/assets/runtime-android-arm64-node24.zip.sha256`
- archive 根目录包含 `runtime-manifest.json`，记录 Node `v24.17.0`、ABI、minSdk、构建来源和动态库 sha256。

## 二进制
- bin/node

## 动态库 (基于 readelf -d NEEDED)
必需的 Termux 提供动态库 (排除系统库 libc, libm, libdl)：
- libz.so.1
- libcares.so
- libsqlite3.so
- libcrypto.so.3
- libssl.so.3
- libicui18n.so.78
- libicuuc.so.78
- libc++_shared.so
