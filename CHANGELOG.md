# 更新日志

## v0.1.2 - 自动发版修复版 (2026-06-08)

### 📦 自动发版修复

- **修复 GitHub Actions 中的 `gradlew` 执行权限问题** — 避免 Linux runner 上出现 `Permission denied`
- **将自定义 bootstrap 纳入 Git LFS** — 让 CI / Release 能拿到正确的 Termux 运行时压缩包
- **将 SillyTavern payload 纳入 Git LFS** — 确保正式 release 包包含 `SillyTavern.tar.gz` 和 `payload-manifest.json`
- **修复 CI Debug 签名文件缺失** — 在工作流中临时生成 `dev_keystore.jks`
- **降低 CI Debug 构建内存峰值** — 改为上传 `universal` debug APK，避免 `packageDebug` 阶段 OOM
- **收紧 Release 签名配置** — 缺失 GitHub Secrets 时直接给出明确失败信息

---

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

> stAPK Termux 基于 [Termux App](https://github.com/termux/termux-app) v0.118.3 构建，采用 AGPL-3.0 许可证
