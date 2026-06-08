# 更新日志

## v0.1.1 - 运行时托管与自动发版版 (2026-06-08)

### 🚀 运行时与启动链路

- **切换为托管运行时** — SillyTavern 启动链路改为由 `TermuxService` 托管 `stapk-runtime`，不再依赖旧的脱管 `nohup` 方案
- **状态模型升级** — 控制面板改为解析结构化状态快照，统一识别 `not_initialized / starting / running / stopped`
- **停止链路重构** — `stapk-stop` 优先清理托管运行时 PID，再兜底处理遗留 `node` 进程

### 🛠 稳定性修复

- **修复 CRLF 脚本问题** — 统一 `assets/stapk/` 为 `LF` 换行，解决 `pipefail`、`$'\\r'`、`unexpected end of file` 等启动失败
- **修复 bootstrap Shebang 遗留问题** — 自动修正 `npm`/`npx` 等脚本中的旧包名路径，避免启动时找不到正确解释器
- **补齐后台任务运行环境** — 修复 `LD_LIBRARY_PATH`、`PATH` 等环境变量传递，避免托管任务拉起后找不到基础运行时
- **脚本与 Intent 合同测试补齐** — 为状态解析、启动 Intent、Shell 环境和脚本格式增加单测覆盖

### 📦 发布流程

- **接入 GitHub Actions CI** — `master` 上的 `push / pull_request` 会自动执行关键单测和 Debug 构建
- **接入 tag 自动发版** — 推送 `v*` 标签后，GitHub 可自动构建 `arm64-v8a release APK` 并上传到 GitHub Release
- **版本显示自动化** — 控制面板底部 `stAPK v...` 改为直接读取当前构建版本，和 tag / APK 元数据保持一致

### ⚠️ 已知问题

- **后台保活仍需继续优化** — 当前托管运行时已切换完成，但“点击启动后需切后台/切回浏览器才有响应”的现象仍在继续排查
- **x86_64 包仅用于调试** — 当前正式自动发版仅面向 `arm64-v8a`，模拟器用 `x86_64` bootstrap 仍不作为正式支持目标

---

## v0.1.0 - 首个公开测试版 (2026-05-29)

### 🚀 核心功能

- **图形化控制面板** — 无需命令行，按钮操作 SillyTavern 的全部功能
- **一键启动/停止** — 后台运行 SillyTavern 服务，带 wake-lock 防止休眠中断
- **离线可用** — 首次启动无需联网，预装 Node.js、npm、Git、bash 等完整环境
- **Git 在线更新** — 一键 `git pull --rebase --autostash` 更新到最新版
- **备份恢复** — 备份/恢复角色卡、对话、扩展、配置文件
- **Git 回滚** — 更新出问题时一键回退到上一个版本
- **状态监控** — 实时显示进程状态、端口监听、版本信息
- **日志查看** — 分类查看初始化/启动/更新/备份日志，支持复制和刷新
- **终端入口** — 高级用户连点版本号 7 次进入原生 Termux 终端
- **诊断报告** — 一键导出完整诊断信息用于排错

### 📦 内置组件

| 组件 | 版本 |
|------|------|
| Termux 基础环境 | v0.118.3 |
| Node.js | v24.15.0 LTS |
| npm | v11.16.0 |
| Git | v2.54.0 |
| SillyTavern | v1.18.0 (release) |

### 🛠 技术细节

- **架构**: arm64-v8a (aarch64)
- **最低系统**: Android 7.0+
- **APK 体积**: ~206 MB（含完整运行环境 + SillyTavern）
- **安装后占用**: ~1.5 GB
- **签名**: 调试签名（安装前需开启"允许安装未知来源"）

### ⚠️ 已知问题

- 当前使用调试签名，有安装过官方 Termux 的设备需先卸载
- 不支持 32 位 Android 和 x86 模拟器
- 首次发布，可能存在未发现的兼容性问题

---

> stAPK Termux 基于 [Termux App](https://github.com/termux/termux-app) v0.118.3 构建，采用 AGPL-3.0 许可证
