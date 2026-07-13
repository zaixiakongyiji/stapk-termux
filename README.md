# stAPK Mobile

> 将 SillyTavern 转换为无 Node.js 运行时的 Android 原生应用。

stAPK Mobile 的 0.3.0 主线是把 [SillyTavern](https://github.com/SillyTavern/SillyTavern) 官方 Web UI 转换成一个 Android APK。APK 运行时不再内置 Node.js、npm、`node_modules` 或 `server.js`；Android 侧通过 WebView 加载静态前端资源，并由 Kotlin/Java 本地 HTTP 兼容后端提供 MVP 所需接口。

> 当前仓库仍保留 0.2.x 的“私有 Node.js + payload.tgz + WebView”实现作为迁移来源。新开发以 [no-node 原生适配设计](docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md) 和 [实施计划](docs/plan/2026-07-09-stapk-no-node-native-adapter-implementation-plan.md) 为准。

## 目标能力

- **官方 Web UI** — 保留 SillyTavern 的前端界面，避免重新实现聊天产品。
- **无运行时 Node.js** — APK 不携带 Node.js、npm、`node_modules`、runtime zip 或 payload tar 解压流程。
- **原生本地后端** — Android 内部 HTTP server 提供静态资源、角色卡、聊天记录、设置和 OpenAI-compatible MVP 接口。
- **应用私有数据** — 用户数据落在 app-private 目录，并从 0.2.x `filesDir/SillyTavern/data` 幂等迁移。
- **可重复转换** — 构建期脚本从 upstream SillyTavern ref 生成 no-node Web 资产、API 契约和 manifest。

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
| 存储空间 | ~1.0GB（解压后体积） |

## 构建

```bash
# 1. 克隆代码
git clone https://github.com/zaixiakongyiji/stapk-termux.git

# 2. 当前 0.2.x 构建仍可能需要 LFS 大文件；0.3.0 no-node 路线会移除运行时 Node 资产
git lfs pull

# 3. 构建 APK
cd mobile
./gradlew assembleDebug

# 4. APK 位于
# mobile/app/build/outputs/apk/debug/
```

> 详细构建指南见 [AGENTS.md](AGENTS.md)

## 自动发版

仓库根目录已经提供 GitHub Actions 工作流：

- `CI`：提交到 `master` 或提交 PR 时自动跑关键单测和 Debug 构建
- `Release`：推送 `v*` tag 时自动构建 `arm64-v8a release APK` 并上传到 GitHub Release

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
│       ├── assets/         # 当前仍含 0.2.x legacy assets；0.3.0 将替换为 no-node Web 资产
│       ├── java/           # Kotlin 核心代码 (RuntimeManager 等)
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
