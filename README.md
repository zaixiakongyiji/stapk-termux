# stAPK Mobile

> 将 SillyTavern 转换为无 Node.js 运行时的 Android 原生应用。

stAPK Mobile 的 0.3.0 主线是把 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 官方 Web UI 转换成一个 Android APK。APK 运行时不再内置 Node.js、npm、`node_modules` 或 `server.js`；Android 侧通过 WebView 加载静态前端资源，并由 Kotlin/Java 本地 HTTP 兼容后端提供 MVP 所需接口。

旧的 0.2.x Node runtime 方案仅保留在 Git 历史中。当前开发以 [no-node 原生适配设计](docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md) 和 [单用户功能完成计划](docs/plan/2026-07-12-stapk-single-user-feature-completion-plan.md) 为准。

## 目标能力

- **官方 Web UI** — 保留 SillyTavern 的前端界面，避免重新实现聊天产品。
- **无运行时 Node.js** — APK 不携带 Node.js、npm、`node_modules`、runtime zip 或 payload tar 解压流程。
- **原生本地后端** — Android 内部 HTTP server 提供静态资源、角色卡、聊天记录、设置和 OpenAI-compatible MVP 接口。
- **应用私有数据** — 用户数据落在 app-private 目录；0.2.x 旧数据迁移是主体完成后的可选独立项目。
- **可重复转换** — 构建期脚本从 upstream SillyTavern ref 生成 no-node Web 资产、API 契约和 manifest。

## 当前实现与验收状态

- **核心能力**：Persona、角色卡、群组与群聊、recent chats、World Info、背景、附件、Tokenizer、settings/themes/presets/snapshots、诊断和 SAF 数据导入导出已接入原生兼容层。
- **外部可选能力**：远程 embedding、图片、TTS、STT、字幕和翻译需要后续配置对应外部服务；当前只显示能力说明，不内置模型。
- **明确排除能力**：本地重型模型、第三方 Node/Python/Shell server extension、multiuser、远程访问和非 OpenAI-compatible provider 不属于 0.3.0 主体范围。
- **安装边界**：0.3.0 按全新安装交付；0.2.x 数据迁移、完整应用备份恢复和 Data Maid 是主体完成后的可选独立项目。
- **设备状态**：截至 2026-07-17，Pixel 8 / Android 15（API 35）已完成 output APK clean install、无 Node 进程、官方单用户 UI 能力矩阵和真实外部 OpenAI-compatible provider 验收；Android 7（API 24）和 Android 10（API 29）延期到后续真机验收。详见 [最终验证记录](docs/plan/2026-07-12-stapk-single-user-feature-validation-record.md)。

## 截图

![stAPK 0.2.x 控制面板日志界面](docs/images/screenshot-log.png)

![stAPK 沉浸全屏界面](docs/images/screenshot-ui.png)

## 目标工作原理

```text
┌──────────────────────────────────────┐
│           stAPK Mobile               │
│  ┌────────────────────────────────┐  │
│  │   Android UI (Kotlin)           │  │
│  │   MainActivity / WebView        │  │
│  │   SillyTavern official Web UI   │  │
│  └──────────────┬─────────────────┘  │
│                 │ 127.0.0.1:<port>   │
│  ┌──────────────▼─────────────────┐  │
│  │   NativeLocalServer            │  │
│  │   Static Web assets            │  │
│  │   Character / Chat / Settings  │  │
│  │   OpenAI-compatible adapter    │  │
│  └────────────────────────────────┘  │
└──────────────────────────────────────┘
```

1. 构建期脚本拉取指定 SillyTavern upstream ref，只提取和补丁化浏览器端 Web 资产。
2. APK 启动后，原生本地 HTTP server 随应用生命周期启动，随机选择 loopback 端口。
3. WebView 加载官方 Web UI；前端请求由原生兼容后端处理，MVP 阶段只开放 OpenAI-compatible 聊天能力。

## 下载

前往 [Releases](../../releases) 页面下载最新 APK。

| 要求 | 说明 |
|------|------|
| Android 版本 | 7.0+ (API 24+) |
| 架构 | arm64-v8a |
| 存储空间 | APK 本体加用户角色、聊天和媒体数据 |

## 构建

构建期需要 Node.js 20+、JDK 17 和 Android SDK；APK 运行时不包含 Node.js。

```bash
# 1. 克隆代码
git clone https://github.com/zaixiakongyiji/stapk-termux.git

# 2. 安装构建期依赖
npm ci

# 3. 从 SillyTavern release 一键转换、严格验证并构建 APK
npm run build:no-node-apk -- --variant debug --ref release

# 4. APK 位于
# output/stapk-mobile-debug.apk
```

命令同时生成 APK SHA-256、API contract、capability runtime、Web manifest 和 transform report。Release 构建使用 `--variant release`，并要求配置 Gradle signing 环境变量。

> 详细构建指南见 [AGENTS.md](AGENTS.md)

## 自动发版

仓库根目录已经提供 GitHub Actions 工作流：

- `CI`：提交到 `master` 或提交 PR 时执行完整 no-node 严格门禁和 Debug 构建
- `Release`：推送 `v*` tag 时执行同一门禁，构建 release APK 并上传六类发布证据

Release 可通过仓库变量 `SILLYTAVERN_REF` 固定上游 tag/commit；未设置时使用 `release`，resolved commit 会写入 manifest。

标准发版方式：

```bash
git push origin master
git tag v0.1.1
git push origin v0.1.1
```

详细说明见 [GitHub 自动构建与发版](docs/reference/github-release-automation.md)。

## 目录结构

```
stapk-termux/
├── mobile/                 # 原生 Android 客户端源码
│   └── app/src/main/
│       ├── assets/         # no-node Web 资产、contract 和 manifest
│       ├── java/           # Kotlin 原生兼容后端与 Android 生命周期
│       └── res/            # UI 布局
├── .github/workflows/      # CI/CD 自动化构建脚本
└── docs/                   # 设计文档与规范
```

## 许可证

本应用为 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 的非官方 Android 转换产物。内置或转换生成的 SillyTavern Web 资产遵循其原始许可证 ([AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html))。外壳和原生兼容后端相关自定义代码保留在本仓库。

## 致谢

- [SillyTavern](https://sillytavern.app) — AI 角色扮演前端
- [Node.js](https://nodejs.org/) — 构建期工具链

---

**stAPK Mobile** — 让 SillyTavern 在手机上开箱即用。
