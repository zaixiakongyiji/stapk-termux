# 更新日志

## v0.2.0 - Android 原生外壳重构版 (2026-06-25)

这是 stAPK Mobile 的一次重大里程碑重构！我们彻底抛弃了原本依赖的 Termux 底层，重新编写了纯原生的 Android 客户端外壳，使得应用更加轻量、专一且符合直觉。

### 🚀 全新特性

- **全新原生体验**：告别终端控制台，启动直接挂载服务，用户界面清爽简单。
- **内置私有环境**：完全脱离 Termux 依赖，使用内置的私有 Node.js 与 Payload，避免外部环境污染与权限问题。
- **原生控制面板**：提供了一键启动服务、查看日志等操作，摒弃了复杂的命令行输入。
- **后台保活服务**：支持点击 "Open Browser" 拉起 Chrome 等外部浏览器使用，自动开启前台服务（Foreground Service）并持有唤醒锁（WakeLock），有效防止因切后台导致的断连或进程被杀。
- **一键备份/恢复**：引入了原生的 Android 存储访问框架 (SAF)，支持将数据目录一键导出为 `.zip` 压缩包，或者从外部导入恢复数据。

### 📖 简易使用指南

1. **初次启动**：安装并打开 App，如果是第一次运行，后台会自动解压基础环境和组件。您可以直接在界面的日志区域（Log）观察进度，确认看到类似 Payload Ready 的提示即代表解压完成。
2. **正常使用**：点击 **"Start Server"** 启动服务。稍等片刻后，您可以在下方内置的 WebView 直接浏览酒馆界面（点击 WebView 区域即可进入沉浸式全屏，按手机返回键即可退出全屏回到控制面板）；或者点击 **"Open Browser"** 在手机自带的原生浏览器中打开。使用原生浏览器时，通知栏会显示驻留通知为您“保活”，防止服务被杀。
3. **数据备份**：点击 **"Backup Data"** 按钮，会弹出一个系统的文件保存窗口。选择一个您记得住的文件夹点击保存，您的**所有核心数据（包括：角色卡片、聊天记录、世界书、用户设置、插件数据等）**就会被完整打包成 `.zip` 永久存放在您的手机里。
4. **数据恢复**：如果您换了手机或者重新安装了 App，点击 **"Restore Data"**，选中之前备份的那个 `.zip` 压缩包，稍等几秒钟，所有数据即可原样恢复！

---

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
