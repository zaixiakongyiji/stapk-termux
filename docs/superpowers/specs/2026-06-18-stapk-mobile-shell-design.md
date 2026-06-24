# stAPK Mobile 原生外壳 MVP 设计

日期：2026-06-18
状态：草案
范围：脱离 Termux 的第一版手机酒馆 App MVP

## 目标

stAPK Mobile 的第一版目标是把当前 stAPK Termux 原型升级为一个真正的 Android App：用户点击图标后直接进入 SillyTavern，不需要看到控制面板、终端、启动按钮或额外设置。

第一版只做“原生 Android 外壳 + 官方 SillyTavern Web UI”。Android App 负责内置运行环境、自动启动本地 SillyTavern 服务、WebView 承载页面、异常兜底和隐藏 Debug 日志；SillyTavern 本身继续使用官方启动与前端，不重写聊天 UI。

## 非目标

- 不再基于 Termux App fork 开发。
- 不暴露 Termux 终端、Linux 包管理、`$PREFIX` 或 shell 脚本控制面。
- 不在 MVP 中实现 App 内一键更新 SillyTavern。
- 不重写 SillyTavern 前端，不做原生聊天界面。
- **（已修正）** 原计划不做控制面板，但根据实际需求，MVP 引入了简洁的控制面板，支持显式的启动、日志查看、外部浏览器跳转以及一键备份/恢复功能。
- **（已修正）** 增加了外部浏览器保活服务（KeepAliveService），保证用户切出 App 时的持久运行。

## 当前项目定位

现有 stAPK Termux 已验证以下能力：

- SillyTavern 可以被打包进 APK 并在 Android 设备本地运行。
- 控制面板可以部署 payload、启动服务、读取状态、显示日志。
- TermuxService 托管运行时比脱管 `nohup` 更稳定。
- GitHub Actions、Git LFS、Release 流程已经为大体积 payload 分发提供基础。

新项目不应继续沿用 Termux 作为最终底座。现有项目应被视为验证原型和迁移来源，而不是继续堆叠产品功能的主线。

## 推荐方案

采用独立 Android 工程，首选 Kotlin 开发，内置 Android arm64 可运行的 Node.js runtime 和 SillyTavern payload。

MVP 目标 `minSdkVersion 24`（Android 7.0），与现有 stAPK Termux 的 Android 7+ 支持范围保持一致。

这个方案不能默认复用当前 stAPK Termux 的 Node.js。现有 Node.js 来自 Termux 包管理器，可能依赖 Termux bootstrap、动态库链、运行时 prefix 和 linker 行为。脱离 Termux 后，MVP 的第一项工作必须先确定 Node.js runtime 来源，并在真机上验证最小命令能运行。

候选 runtime 来源：

| 方案 | 用途 | 风险 |
|------|------|------|
| 自行交叉编译 Node.js for Android arm64 | 长期推荐方向，依赖链最可控 | 编译复杂，需要 CI 维护 |
| nodejs-mobile 或同类移动端 Node runtime | POC 候选，可缩短验证时间 | 活跃度、Node API 版本和 ABI 需评估 |
| 直接复用 Termux 编译的 node 与依赖库 | 只适合作为对照实验 | 可能隐含 Termux prefix、动态库路径和 patched 环境依赖 |

验收顺序必须是：

```text
确认 runtime 来源
    ↓
真机运行 node --version
    ↓
真机运行 node -e "console.log(process.versions)"
    ↓
真机运行最小 HTTP server
    ↓
真机运行 SillyTavern server.js
```

如果 `node --version` 不能在真机 App 私有环境中稳定输出，后续 WebView、payload 和产品体验都不进入实现。

启动流程如下：

```text
用户点击 App 图标
    ↓
MainActivity 启动
    ↓
绑定或启动 RuntimeForegroundService
    ↓
RuntimeManager 检查 runtime / payload / state
    ↓
首次运行时解压 node runtime 和 SillyTavern 程序文件
    ↓
使用 ProcessBuilder 启动 node server.js
    ↓
轮询 http://localhost:8000
    ↓
服务 ready 后 WebView 加载官方 SillyTavern UI
```

用户正常路径中不出现“启动”动作。启动服务只是 App 内部实现细节。

## 架构概览

```text
stAPK Mobile
├── MainActivity
│   ├── 控制面板 (启动、日志、外部浏览器、备份、恢复)
│   ├── 动态权限请求 (通知权限)
│   └── WebView 主界面 (默认不展示，通过外部浏览器承载主要业务)
├── KeepAliveService
│   ├── 提供前台服务常驻通知 (防止 OOM 查杀)
│   └── 申请 WakeLock (防止 CPU 休眠)
├── RuntimeManager
│   ├── 解压 runtime 和 payload
│   ├── 启动 node 进程
│   ├── 检查 localhost ready
│   └── 提供一键备份/恢复能力 (ZIP 压缩/解压)
│   └── 停止或重启异常进程
├── TavernWebViewClient
│   ├── 加载 localhost:8000
│   ├── 外链跳转外部浏览器
│   ├── 文件选择支持
│   └── 下载处理
├── DebugLogStore
│   ├── 收集 node stdout / stderr
│   ├── 记录启动阶段事件
│   └── 提供隐藏 Debug 面板内容
└── PayloadInstaller
    ├── 校验内置版本
    ├── 保留用户数据
    └── 升级程序文件
```

## 模块设计

### MainActivity

`MainActivity` 目前是唯一对普通用户可见的入口。由于用户倾向于使用原生浏览器并具备一定的控制能力，MVP 引入了一个显式的控制面板，包含：

- **日志与状态**：显示当前服务器是否就绪。
- **Start Server**：手动启动本地 node 进程。
- **Open Browser**：启动外部浏览器（如 Chrome），并拉起保活服务（KeepAliveService）。
- **Backup / Restore Data**：利用 Android 存储访问框架 (SAF) 导出/导入以 `.zip` 为后缀的 `data` 目录压缩包。

内部 WebView 依然存在，但主要被隐藏，用户通过点击“Open Browser”获得最佳体验。启动失败或无权限时，可以直接复制诊断日志。

### RuntimeManager

`RuntimeManager` 负责管理本地 SillyTavern 服务生命周期。它不依赖 shell 脚本作为核心控制面，而是由 Android Kotlin 直接完成目录检查、环境变量设置、进程启动和日志采集。

`RuntimeManager` 不应直接由 Activity 独占持有。为确保用户在使用外部浏览器时节点进程不会被系统意外终止，MVP 引入了 `KeepAliveService`。当用户点击“Open Browser”时，如果 Server 已启动，则拉起该前台服务（Foreground Service，Type `dataSync`），并在后台持有一个 `PARTIAL_WAKE_LOCK` 防止 CPU 休眠。当用户返回应用界面时，服务被终止。

启动 node 时需要显式设置：

- 工作目录：SillyTavern 程序目录。
- `PATH`：包含内置 node runtime 目录。
- `LD_LIBRARY_PATH`：包含内置 native library 目录。
- `NODE_ENV=production`。
- 命令参数：显式传入 `--configPath` 和 `--dataRoot`。

同时不能假设 `LD_LIBRARY_PATH` 一定足以解决动态库加载。Android 7.0+ 对动态链接非 NDK 库和私有平台库有额外限制，POC 必须验证 native binary 的放置目录、执行权限和依赖库加载策略。

Runtime POC 至少需要比较两类部署方式：

- 可执行文件和依赖库解压到 App 私有目录后执行。
- native 依赖放入 APK `jniLibs/<abi>/`，运行时通过 `nativeLibraryDir` 加载。

验收时必须记录：

- `node` 实际路径。
- `node --version` 输出。
- `ProcessBuilder` 传入的环境变量。
- 缺失动态库或 `dlopen` 失败日志。
- 目标设备 Android 版本、ABI、厂商 ROM。

MVP 已采用前台服务与唤醒锁确保进程存活，这是长期重型 Web 进程驻留必不可少的手段。

### PayloadInstaller

`PayloadInstaller` 负责把 APK 内置资产部署到 App 私有目录。它需要区分程序文件和用户数据，避免 App 升级覆盖用户内容。

建议目录结构：

```text
filesDir/
├── runtime/
│   ├── bin/node
│   └── lib/*.so
├── app_payload/
│   └── SillyTavern/
├── user_data/
│   └── SillyTavernData/
├── user_config/
│   └── config.yaml
├── logs/
│   ├── startup.log
│   └── node.log
└── state/
    ├── installed-runtime-version.json
    └── installed-payload-version.json
```

本地 SillyTavern 源码已支持 `--configPath` 和 `--dataRoot` 参数。MVP 应优先通过启动参数实现程序文件和用户数据分离：

```text
node server.js \
  --configPath <filesDir>/user_config/config.yaml \
  --dataRoot <filesDir>/user_data/SillyTavernData \
  --port 8000
```

升级时只替换 `app_payload/SillyTavern/` 中的程序文件和依赖，不覆盖 `user_config/` 与 `user_data/`。如果后续 SillyTavern 某个版本改变数据路径语义，升级流程必须先在 POC 中验证，再允许进入 release。

### TavernWebViewClient

WebView 只承载本机 SillyTavern 页面：

- 默认加载 `http://localhost:8000`。
- `127.0.0.1` 和 `localhost` 内部导航保留在 WebView。
- 外部链接交给系统浏览器。
- 文件选择使用 Android 文件选择器。
- 下载使用系统下载或 Storage Access Framework。
- WebView 加载失败时返回启动失败页，并允许复制诊断日志。

MVP 不注入 JS、不修改 SillyTavern 前端资源、不劫持业务 API。

由于 Android 9+ 默认不允许明文 HTTP，MVP 必须提供 `network_security_config.xml`，只允许本机地址明文访问：

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain>127.0.0.1</domain>
        <domain>localhost</domain>
    </domain-config>
</network-security-config>
```

不允许为了 localhost 直接打开全局明文流量。

实现时优先让 WebView 加载 `http://localhost:8000`。如果改用 `http://127.0.0.1:8000`，必须在目标 API level 上验证 network security 配置是否匹配 IP literal。

### DebugLogStore

Debug 日志是 MVP 唯一保留的“工具型”能力。默认隐藏，不影响普通用户。

记录内容：

- 当前 App 版本。
- 内置 SillyTavern payload 版本。
- runtime 部署状态。
- node 启动命令。
- 端口 ready 耗时。
- node stdout / stderr 尾部。
- 最近一次失败原因。

日志只存本地，不上传。失败页提供“复制诊断日志”按钮。

## 更新策略

MVP 采用“跟随 App 版本更新”的策略：

- APK 内置一个经过验证的 SillyTavern 版本。
- App 升级后检测内置 payload 版本。
- 若 payload 版本变更，则更新程序文件。
- 用户数据目录不覆盖。
- 升级失败时保留旧版本文件并记录错误。

App 内一键更新 SillyTavern 放到后续阶段。原因是在线更新会引入 git、npm、依赖重建、回滚和网络异常处理，复杂度不适合 MVP。

## 错误处理

MVP 需要把失败原因明确落到日志和 UI：

- 存储空间不足：停止初始化，提示释放空间。
- runtime 缺失或校验失败：提示安装包损坏。
- node 不可执行：记录文件权限、ABI、路径。
- native 依赖加载失败：记录缺失 `.so`、linker 错误和 runtime 部署方式。
- SillyTavern 启动失败：展示 node 日志尾部。
- 端口未 ready：记录超时时间和进程状态。
- WebView 加载失败：允许重试并复制诊断日志。

所有失败都应保留可复制诊断日志，避免用户只能描述“打不开”。

## 验收标准

### Runtime POC

- 明确选定至少一个 Node.js runtime 来源。
- 真机上 `node --version` 和 `node -e "console.log(process.versions)"` 能正常输出。
- 真机上最小 HTTP server 可以被 WebView 访问。
- 安装 APK 后点击图标，无需任何额外操作。
- 首次运行可以解压 runtime 和 payload。
- App 能通过 `RuntimeForegroundService` 托管内置 node 并启动 SillyTavern。
- WebView 能打开官方 SillyTavern 首页。
- Debug 日志能记录启动命令、stdout / stderr、端口 ready 状态。

### Android 虚拟机调试

Phase 1 可以使用 `mobile-mcp` 辅助调试 Android 虚拟机或已连接设备。它主要用于设备级操作：

- 枚举可用 Android 设备或模拟器。
- 安装 `mobile` 工程生成的 debug APK。
- 启动、终止和重新启动 `com.stapk.mobile`。
- 截图保存当前界面，用于确认启动页、失败页和 WebView 状态。
- 列出设备上的已安装应用，确认包名和安装状态。

`mobile-mcp` 不替代 App 内 Debug 日志和 `adb logcat`。Runtime POC 的关键失败信息仍必须写入 App 内诊断日志；linker、权限和 ForegroundService 相关系统日志仍通过 `adb logcat` 或 Android Studio 采集。

### MVP

- 冷启动、热启动都能自动进入 SillyTavern。
- 普通用户路径中不出现控制面板。
- App 升级不会覆盖用户数据。
- 启动失败时能复制诊断日志。
- 外链、文件选择、返回键在 WebView 中表现正常。

## 风险与验证顺序

最高风险是 Android arm64 Node.js runtime 的稳定分发。必须先验证：

1. Node.js runtime 来源明确，且不是隐式依赖 Termux 环境。
2. node 可执行文件能在目标 Android 版本上运行。
3. `filesDir` 或 `nativeLibraryDir` 中的部署方式满足执行权限要求。
4. 所需 `.so` 能被正确加载，且未依赖 Android 私有平台库。
5. SillyTavern 当前依赖不需要不可用的 native addon。
6. `node server.js --configPath ... --dataRoot ...` 在 App 私有目录中能稳定启动。
7. WebView 访问 `localhost:8000` 已通过本机明文网络配置；`127.0.0.1:8000` 作为兼容验证项。
8. `RuntimeForegroundService` 能在切后台后继续托管 node 进程。

只有 Runtime POC 通过后，才进入完整 App 外壳和产品体验开发。

## 分阶段路线

### Phase 1：Runtime POC (✅ 已完成)

建立独立 Android 工程，验证内置 node + SillyTavern + WebView 的最小闭环。

Phase 1 的任务顺序固定为：

1. 确定 runtime 候选来源。
2. 真机验证 `node --version`。
3. 真机验证动态库加载和执行目录。
4. 真机验证最小 HTTP server。
5. 真机验证 `server.js --configPath --dataRoot`。
6. 真机验证 `KeepAliveService` 托管。
7. WebView 打开官方 SillyTavern 首页。

### Phase 2：MVP 外壳 (✅ 已完成)

补齐 `KeepAliveService`、`RuntimeManager`、`PayloadInstaller`、`TavernWebViewClient`、`DebugLogStore`，形成打开即用体验。增加了显式的控制面板。

### Phase 3：升级与数据保护 (✅ 已完成)

实现 payload 版本检测、程序文件升级、用户数据保护和失败回滚。已通过 Android 存储访问框架 (SAF) 实现了 `data` 目录的 ZIP 备份与恢复。

### Phase 4：迁移工具

从 stAPK Termux 导入角色卡、聊天记录、用户设置和扩展数据。

### Phase 5：高级能力 (✅ 已完成)

后台保活（已使用 ForegroundService 结合 WakeLock 解决），以及对外部浏览器的彻底支持（解决 ANR 与权限阻断问题）。诊断包导出等能力也已集成。

## 决策结论

第一版 stAPK Mobile 应优先证明“脱离 Termux 后打开即用”这一核心体验。所有非必要功能都延后，包括控制面板、备份恢复、设置页和在线更新。

这个 MVP 的成功标准不是功能多，而是用户点击图标后稳定进入官方 SillyTavern UI。

## 参考约束

- Node.js 官方文档当前把 Android 标记为非支持平台，Android build 没有官方 CI 覆盖，因此 runtime 必须由项目自行验证。
- Android 7.0+ 对动态链接非 NDK 库有约束，native 依赖必须打包并在目标设备验证。
- Android 9+ 默认禁用明文 HTTP，localhost 访问需要显式 network security 配置。
- ForegroundService 是托管用户可感知长期任务的 Android 标准机制，node 进程应由服务承载，而不是仅依赖 Activity 生命周期。
- Android 15 起引入 16 KB page size 兼容要求；如果 runtime 含 native code，需要纳入构建和真机/模拟器验证范围。
