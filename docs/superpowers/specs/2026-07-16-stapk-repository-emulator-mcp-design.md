# stAPK 仓库级 Android Emulator MCP 设计

## 目标

只在 stAPK 仓库中提供 `Pixel_8` AVD 的完整生命周期工具，让 Codex 在设备未启动时自行启动可见的独立 Emulator 窗口，并继续使用现有 `mobile-mcp` 完成安装、交互和截图验证。

## 范围

- 新 MCP 只管理名称严格等于 `Pixel_8` 的 AVD。
- 提供 `status`、`start`、`ensure_started`、`stop`、`restart` 五项工具。
- 不修改 `C:\Users\31029\.codex\config.toml`，不替代全局 `mobile-mcp`。
- 不接受任意可执行文件、shell 命令、AVD 名称或 Emulator 参数。
- 默认启动可见窗口，保留 Android Emulator 的 Quick Boot 行为。

## 结构

| 文件 | 职责 |
|---|---|
| `scripts/mcp/stapk-emulator-core.mjs` | SDK 路径解析、设备识别、启动等待、停止和重启 |
| `scripts/mcp/stapk-emulator-mcp.mjs` | MCP stdio 协议、工具 schema 和结构化结果 |
| `test/mcp/stapk-emulator-core.test.mjs` | 通过注入命令执行器验证生命周期状态机 |
| `.codex/config.toml` | 仅在本仓库注册 `stapk-emulator` MCP |

MCP 协议层不直接拼接命令。核心层只调用固定的 `emulator.exe` 和 `adb.exe`，参数由内部常量生成。

## 设备识别

1. 通过 `adb devices` 获取在线的 `emulator-*` serial。
2. 对每个 serial 调用 `adb -s <serial> emu avd name`。
3. 只有返回首个非空行严格等于 `Pixel_8` 的设备才属于本 MCP。
4. 实体设备和其他 AVD 永远不进入停止或重启目标集合。

## 生命周期

- `status`：返回 `stopped`、`booting` 或 `ready`，以及已识别的 serial。
- `start`：若目标已存在则幂等返回；否则验证 AVD 清单后以独立进程启动，并等待最多 180 秒。
- `ensure_started`：语义等同于幂等 `start`，作为测试前的标准入口。
- `stop`：重新确认 AVD 名称后执行 `adb -s <serial> emu kill`，并等待设备从列表消失。
- `restart`：只停止已确认的 `Pixel_8`，等待退出后再启动并等待 ready。

ready 的判定必须同时满足：设备状态为 `device`，且 `adb -s <serial> shell getprop sys.boot_completed` 返回 `1`。

## 路径与错误

Android SDK 按 `ANDROID_SDK_ROOT`、`ANDROID_HOME`、Windows `%LOCALAPPDATA%\Android\Sdk` 顺序查找。找不到 SDK、AVD 不存在、启动进程失败、启动超时和停止超时均返回稳定错误 code，不在 MCP 输出中暴露无关环境变量。

## 验证

- Node 单元测试覆盖已停止、启动中、已就绪、重复启动、其他 AVD 共存、启动超时和安全停止。
- MCP stdio smoke test 验证五项工具可以被列出。
- 真实验收先关闭目标 AVD，再调用 `ensure_started`，确认 `Pixel_8` ready，随后交给 `mobile-mcp` 继续 stAPK 设备测试。
