# stAPK 0.3.0 SillyTavern APK 转换体系设计

日期：2026-06-25
状态：持续维护，Phase 1 部分已落地
范围：0.3.0 将仓库从“封装启动 SillyTavern 的 Android 外壳”升级为“把 upstream SillyTavern 可重复转换为 Android APK 的工程系统”

## 结论

0.3.0 不重写 SillyTavern，也不长期维护一个脱离 upstream 的 SillyTavern fork。

0.3.0 的主线是建立一套可重复执行的转换流水线：

```text
指定 SillyTavern upstream ref
    -> 拉取干净源码
    -> 校验 Node / npm / package-lock / native addon 风险
    -> 应用 stAPK Patch Queue
    -> 生成 Android payload 与 manifest
    -> 注入 mobile APK assets
    -> 构建 APK
    -> 安装到模拟器或真机验证
```

普通用户路径变为：

```text
点击 stAPK Mobile 图标
    -> App 全屏启动页
    -> 内部自动部署 runtime / payload
    -> 内部自动启动 SillyTavern server.js
    -> WebView 加载本机 SillyTavern
    -> 用户直接看到 SillyTavern 自己的 loading / UI
```

Android App 是 SillyTavern 的移动端运行容器和转换产物，不是新的聊天产品。WebView 仍是主界面容器，但不再暴露控制面板、外部浏览器按钮、备份恢复按钮或插件清理按钮。

## 评审修订记录（2026-06-25）

本节汇总初稿评审中逐项讨论并确认的关键决策，正文相关章节已据此修订。每条均基于对当前仓库 / payload / `command-line.js` 源码的实证核对。

| # | 主题 | 决策 | 落地位置 |
|---|------|------|----------|
| 1 | payload 平台无关性 | 转换脚本**强校验平台无关性**（扫 `.node` + 解析依赖 `os`/`cpu`/`libc` + 检查 optional 平台包是否落地），命中硬失败除非显式 override 并写 manifest。当前 payload 为 WASM-only（`onnxruntime-web`），这是必须守护的契约基线 | Step 8、已核对事实 |
| 2 | native addon 判定 | 三层语义：① `.node` 硬失败 ② 平台相关产物硬失败 ③ `nan`/`node-addon-api` 等关键字**仅告警写 manifest**。判定基于**打包结果实际产物**，关键字不直接据此失败，避免误伤与死锁 | Step 8 |
| 3 | 配置来源单一化 | 网络/安全字段**统一由 `config.android.yaml` 承载**；CLI 只传 `--configPath`/`--dataRoot`/`--port 8000`，删除 `--listen false --enableIPv4 ...` 双源。已确认优先级为 CLI > config > default | Step 9、Node 启动合同 |
| 4 | 数据迁移红线 | 目录合同 + **可工作的迁移 hook 必须同一 PR**；迁移幂等 + 失败回滚；**迁移未确认成功不得启动 server**（停在启动失败页） | 0.2.0 数据迁移、Phase 2/3 |
| 5 | 进程生命周期 | `KeepAliveService` **升级为 Node 进程持有者**（`startForegroundService` + `START_STICKY`，进程/前台/WakeLock/Stop 收敛一处）；`MainActivity` 改为 `bindService` 读状态，`onDestroy` 不再 kill。排除 `RuntimeProcessOwner` 单例 | KeepAliveService、Phase 2 |
| 6 | 事实补录 | 补「payload WASM-only / `onnxruntime-web` / 零 `.node` / 命令行参数类型与优先级」为已核对事实 | 已核对事实 |
| 7 | runtime 前置 | Node 20+ arm64 runtime 设为 **Phase 1 第 0 里程碑**，独立于转换脚本先在真机验证可运行 | Phase 1 |
| 8 | CI 生成过渡 | 切换「长期 CI 生成 payload」前先**实测一次 CI 全流程耗时/配额**，过渡期保留 LFS 回退 | 生成 payload 体积风险 |

## 进度更新（2026-07-06）

本节记录当前工作区相对初稿已经落地的内容，避免后续执行时把已完成项再次当作拟新增任务。

- `scripts/stapk-transform.mjs` 已落地，支持从 upstream ref 生成 `payload.tgz`、`payload-manifest.json`、转换报告和 `config.android.yaml`。
- 根目录已新增 `package.json` / `package-lock.json`，转换工具链使用 Node `>=20` 与构建期依赖 `yaml`。
- `runtime-poc.zip` 已被 `runtime-android-arm64-node24.zip` 替换，runtime manifest 记录 Node `v24.17.0`。
- `.github/workflows/ci.yml` 与 `.github/workflows/release.yml` 已过渡接入 `actions/setup-node` 和 `stapk-transform.mjs`，当前输出到 `build/ci-payload` 并上传转换报告。
- `transform/config/config.android.yaml` 已存在；当前 drift 检查仍是报告/告警性质，哪些缺失字段应阻断还未形成最终规则。
- 仍未落地：`scripts/stapk-verify-transform.mjs`、`transform/schemas/payload-manifest.schema.json`、Phase 2/3 Android 启动体验与数据迁移重构、正式 release 使用 transform 输出作为 APK 输入的闭环。

## 已核对事实

本节只记录当前仓库或当前 payload 中已经核对过的事实。后续设计不得把未实现能力写成本节事实。

核对时间：2026-07-06。涉及远端分支、GitHub Release 或 Android 官方文档的事实是动态事实，实施前必须重新核对。

| 项目 | 当前事实 | 证据来源 |
|------|----------|----------|
| 当前发布基线 | `master` 已有 `v0.2.0` tag | `git log --oneline --decorate --max-count=20 --all` |
| 当前 Android 工程 | 独立工程位于 `mobile/` | `mobile/settings.gradle.kts` |
| 当前包名 | `com.stapk.mobile` | `mobile/app/build.gradle.kts` |
| 当前 minSdk | `24` | `mobile/app/build.gradle.kts` |
| 当前 compileSdk | `34` | `mobile/app/build.gradle.kts` |
| 当前 targetSdk | `28` | `mobile/app/build.gradle.kts` |
| 当前 APK versionName | `versionName = "1.0"`，尚未从 Git tag 或 `TERMUX_APP_VERSION_NAME` 注入 | `mobile/app/build.gradle.kts` 与 `.github/workflows/release.yml` |
| 当前 UI | `MainActivity` 使用 XML + Kotlin，包含控制面板和 WebView | `mobile/app/src/main/res/layout/activity_main.xml` 与 `MainActivity.kt` |
| 当前 server 生命周期 | Node 进程由 `MainActivity` 持有；`MainActivity.onDestroy()` 会调用 `runtimeManager.stopSillyTavern()` | `mobile/app/src/main/java/com/stapk/mobile/MainActivity.kt` |
| 当前 KeepAliveService | 只创建前台通知并持有 `WakeLock`，不拥有 Node 进程生命周期 | `mobile/app/src/main/java/com/stapk/mobile/KeepAliveService.kt` |
| 当前外链分流 | `TavernWebViewClient.shouldOverrideUrlLoading()` 对所有 URL 返回 `false`，没有外链分流 | `mobile/app/src/main/java/com/stapk/mobile/TavernWebViewClient.kt` |
| 当前 Blob 下载 | JS bridge 写入公共 Downloads，尚未按 0.3.0 App 私有目录/SAF 策略重验 | `MainActivity.BlobDownloader` |
| 当前运行时资产 | `runtime-android-arm64-node24.zip` 在 `mobile/app/src/main/assets/`；archive manifest 记录 Node `v24.17.0` | `Get-ChildItem mobile/app/src/main/assets` 与 archive 内 `runtime-manifest.json` |
| 当前 payload 资产 | `payload.tgz` 在 `mobile/app/src/main/assets/` | `Get-ChildItem mobile/app/src/main/assets` |
| 当前 LFS 规则 | `payload.tgz` 与 `runtime-android-arm64-node*.zip` 通过 Git LFS 跟踪 | `.gitattributes` |
| 当前 payload upstream | `https://github.com/SillyTavern/SillyTavern.git` branch `release` commit `51ad27fb86d39a3daca3adaa970375c9670c12df` | `mobile/app/src/main/assets/payload-manifest.json` |
| 当前 upstream release HEAD | `release` 仍指向 `51ad27fb86d39a3daca3adaa970375c9670c12df` | `git ls-remote https://github.com/SillyTavern/SillyTavern.git refs/heads/release` |
| 当前 SillyTavern 版本 | `1.18.0` | `payload-manifest.json` 与 payload 内 `package.json` |
| 当前 payload 内锁文件 | payload 内存在 `SillyTavern/package-lock.json` | `tar -tzf mobile/app/src/main/assets/payload.tgz` |
| 当前 SillyTavern Node 要求 | `package.json` 声明 `engines.node >= 20` | payload 内 `SillyTavern/package.json` |
| 当前 payload manifest 记录的 runtime Node | `v24.17.0`，`engine_check.passed = true` 且 `override = false` | `mobile/app/src/main/assets/payload-manifest.json` |
| 当前 `config.yaml` 状态 | payload 根目录没有 `SillyTavern/config.yaml` | `tar -xOzf ... SillyTavern/config.yaml` 返回 not found |
| 当前 upstream 默认配置 | payload 内存在 `SillyTavern/default/config.yaml`，可作为 Android 模板派生基线 | `tar -tzf mobile/app/src/main/assets/payload.tgz` |
| SillyTavern 启动参数能力 | `--configPath`、`--dataRoot`、`--port`、`--listen`、`--enableIPv4`、`--enableIPv6` 存在 | payload 内 `SillyTavern/src/command-line.js` |
| 当前 CI | `CI` 工作流已安装 Node 20，并在 Gradle 前运行 `stapk-transform.mjs` 到 `build/ci-payload`；APK 仍使用仓库内 assets | `.github/workflows/ci.yml` |
| 当前 Release | tag `v*` 工作流已安装 Node 20，并在 release build 前运行 `stapk-transform.mjs` 到 `build/ci-payload`；正式资产仍只上传 APK 与 sha256 | `.github/workflows/release.yml` |
| 当前 JS 工具链 | 根目录已有 `package.json` / `package-lock.json`，声明 Node `>=20` 与构建期 `yaml` 依赖 | `package.json` |
| 当前转换脚本 | `scripts/stapk-transform.mjs` 已存在；`scripts/stapk-verify-transform.mjs` 尚未存在 | `Get-ChildItem scripts` |
| 当前 Android 配置模板 | `transform/config/config.android.yaml` 已存在 | `Test-Path transform/config/config.android.yaml` |
| 当前 payload 平台无关性 | payload 为平台无关（WASM-only），无任何 `.node` 原生二进制 | `tar -tzf payload.tgz \| grep '\.node$'` 返回空 |
| 当前 ONNX 后端 | 使用 `onnxruntime-web`（WASM 后端），非 `onnxruntime-node`（原生后端） | `tar -tzf payload.tgz \| grep onnxruntime` |
| 当前 WASM 产物 | payload 内存在 `ort-wasm*.wasm`、`tiktoken_bg.wasm`、`@jsquash/*.wasm` 等 | `tar -tzf payload.tgz \| grep '\.wasm$'` |
| 当前命令行参数类型 | `port` 为 number、`listen` 为 boolean、`enableIPv4`/`enableIPv6` 为 string（经 `stringToBool` 转换） | payload 内 `SillyTavern/src/command-line.js` |
| 当前参数优先级 | non-global 模式下 `cliArguments.X ?? getConfigValue(...) ?? default`，即 CLI > config.yaml > 内置默认；`isGlobal` 默认 `false` | payload 内 `SillyTavern/src/command-line.js` |

## 0.2.0 的问题边界

0.2.0 当前可以确认以下事情已经实现或可运行：

- Android App 私有目录中可以部署 runtime 和 payload。
- Kotlin 层可以用 `ProcessBuilder` 启动本地 Node 进程。
- WebView 可以打开本机 SillyTavern 页面。
- 已实现外部浏览器入口、前台服务和 WakeLock；后台体验改善必须以模拟器或真机回归记录为准，不能只从代码存在推导。
- GitHub Actions 可以构建 `mobile` APK 并按 tag 发布。

但 0.2.0 的工程身份仍然偏向“把一份现成 payload 放进 APK 并提供控制面板”。这带来几个问题：

- payload 是结果文件，不是从 upstream 到 APK 的可重复过程。
- 更新 SillyTavern 时，需要人工判断如何刷新 payload。
- Android 适配差异没有统一收敛到 patch queue 或转换脚本。
- 普通用户仍然看到 `Start Server`、日志、外部浏览器等中间态。
- release metadata 不足以完整追溯 SillyTavern commit、patch set、runtime、转换脚本版本。

0.3.0 要解决的是这些工程系统问题，而不是重写 SillyTavern 的业务逻辑。

## 目标

### 产品目标

- 用户打开 App 后直接进入 SillyTavern loading / UI。
- 普通用户路径中不出现控制面板、启动按钮、停止按钮、外部浏览器按钮、备份恢复按钮、插件清理按钮。
- WebView 是 App 主界面容器，外部浏览器不是主路径。
- 用户数据使用 App 私有目录。
- App 内不提供独立的备份恢复、插件清理、文件管理便利功能。
- SillyTavern 自身已有的上传、下载、导入、导出、外链能力必须通过 WebView / Android 桥接逐项验证；验证前只能作为 0.3.0 设计目标。

### 工程目标

- 从干净 upstream SillyTavern ref 生成 payload。
- 所有 stAPK 对 upstream 的差异都进入 patch queue、转换脚本、Android 容器代码或配置模板。
- 转换过程能在本地和 CI 中重复执行。
- 转换产物包含 manifest，可追溯 upstream ref、实际 commit、SillyTavern version、runtime version、patch queue hash、构建时间和工具版本。
- 转换脚本必须先做兼容性检查，再生成 payload。
- release 构建必须以转换产物为输入，而不是依赖手工刷新过的 payload。

### 质量目标

- patch 冲突时失败，不自动吞掉冲突。
- Node engine 不满足时失败，除非显式传入强制参数并写入 manifest。
- native addon 扫描发现风险时失败，除非显式传入强制参数并写入 manifest。
- APK 安装后至少在 Android 模拟器上验证：启动、runtime 部署、server ready、WebView loading、进入 SillyTavern 首页。
- 诊断日志必须覆盖转换阶段和运行阶段。

## 非目标

- 不重写 SillyTavern 前端。
- 不用 Kotlin / Compose 实现聊天界面。
- 不维护长期 SillyTavern fork。
- 不把用户数据放到外部可见目录。
- 不做 App 内备份恢复按钮。
- 不做 App 内插件清理按钮。
- 不做在线 `git pull` 更新 SillyTavern。
- 不在 0.3.0 中实现多 ABI release。正式目标仍先聚焦 `arm64-v8a`。
- 不把 WebView 移除。WebView 是 0.3.0 的主界面容器。

## 前置阻断项

以下问题是 0.3.0 的前置门槛。已落地的门槛仍保留验收要求；未落地部分不能在 release 前跳过。

### 1. Node 20+ Android arm64 runtime

当前事实：

- `mobile/app/src/main/assets/runtime-android-arm64-node24.zip` 已存在，archive manifest 记录 Node `v24.17.0`。
- `mobile/app/src/main/assets/payload-manifest.json` 记录的 runtime Node 是 `v24.17.0`，`engine_check.passed = true`。
- 当前 payload 内 SillyTavern `package.json` 声明 `engines.node >= 20`。
- `scripts/build-runtime-archive.sh` 已用于生成可追溯 runtime archive。
- x86_64 模拟器上的 arm64 翻译层曾暴露 `libz.so.1` 加载问题，不能替代 arm64 真机最终验收。

结论：Node Runtime 升级本身已从 Node 18 POC 推进到 Node 24 archive，但正式 release 前仍必须保留两个验收门槛：

1. 转换脚本必须默认阻断 engine mismatch，release 不使用 `--allow-engine-mismatch`。
2. 必须在 arm64-v8a 真机或等价环境记录 `node --version` 与 `node server.js` 启动证据。

manifest 至少记录 runtime archive sha256、Node version、构建来源和提取脚本版本；设备实测 Node version 应写入运行验证记录或 release 验收记录。

### 2. `.mjs` 转换脚本工具链

当前已落地：

- `scripts/stapk-transform.mjs` 已存在。
- 根目录已有 `package.json` / `package-lock.json`，声明 `"type": "module"`、`engines.node >= 20` 和构建期 `yaml` 依赖。
- CI / Release workflow 已通过 `actions/setup-node` 固定 Node 20。

仍待落地：

- `scripts/stapk-verify-transform.mjs`。
- `transform/schemas/payload-manifest.schema.json`。

0.3.0 可以继续使用 `.mjs`，但必须明确工具链合同：

- CI 使用 `actions/setup-node` 固定 Node 20 LTS 或更高版本。
- 本地文档要求 `node --version` 满足同一最低版本。
- `.mjs` 文件通过根目录 `package.json` 的 `"type": "module"` 运行。
- `package-lock.json` 必须随工具链依赖同步更新。
- 转换脚本只使用目标 Node 版本稳定支持的 ESM、`fs/promises`、`child_process` 和标准库能力，不引入未声明的实验特性。

### 3. Phase 2 / Phase 3 的目录合同

Phase 2 的启动体验重构和 Phase 3 的数据迁移不能在实现上彼此脱节。`RuntimeManager` 重构时必须直接采用 Phase 3 的最终目录约定：

```text
filesDir/runtime/
filesDir/app_payload/SillyTavern/
filesDir/user_config/config.yaml
filesDir/user_data/
filesDir/logs/
filesDir/state/
```

Phase 2 可以先不完成全部迁移策略，但不得引入与 Phase 3 冲突的临时目录。Phase 3 的迁移入口应挂在 `RuntimeManager` 初始化流程中，发生在 payload 安装和 server 启动之前。

### 4. 发布版停止入口

0.3.0 开发调试阶段可以通过 `adb shell am force-stop com.stapk.mobile` 停止，但正式 release 不应只依赖 adb 或系统强制停止。

首个正式 0.3.0 release 前，`KeepAliveService` 前台通知至少应提供一个 `Stop` action：

- 停止 Node 进程。
- 释放 `WakeLock`。
- 停止前台服务。
- 写入 runtime 日志。
- Activity 存活时同步 UI 到停止或错误状态。

这不等同于恢复 0.2.0 控制面板；停止入口放在通知栏即可，不进入普通用户主界面。

### 5. Android 默认配置模板

`transform/config/config.android.yaml` 已存在，但当前 drift 检查仍以报告为主，尚未定义“缺失哪些 upstream 字段必须阻断 release”的最终规则。0.3.0 不能在未确认阻断规则前声称 Android 默认配置已经完整稳定。

模板设计原则：

- 只写 Android 必须稳定的字段。
- 不覆盖用户 API key、角色、聊天、扩展和个人偏好。
- 网络字段必须服务于本机 WebView：`port: 8000`、仅本机监听、IPv4 优先。
- 数据目录使用启动参数 `--dataRoot <filesDir>/user_data`，不在模板中硬编码绝对设备路径。
- 模板生成后必须与当前 upstream 默认 `config.yaml` 做字段差异审查，确认没有遗漏 upstream 新增必需字段。

初版模板内容应在实施计划中单独列出，并以转换验证命令检查 YAML 可解析、SillyTavern 可启动、升级时不覆盖用户已有配置。

## 设计原则

### 1. Upstream 不可变

`SillyTavern/SillyTavern` 是 upstream。stAPK 不直接修改 upstream 仓库，也不假设 upstream 会接受 Android 专用改动。

转换脚本每次从指定 ref 得到干净源码，然后应用本仓库维护的 patch queue。任何修改 upstream 源码的行为都必须能在 patch 文件中看到。

### 2. Patch Queue 是产品资产

patch queue 与 Android 容器代码同等重要。每个 patch 必须说明：

- 为什么需要。
- 修改哪些 upstream 文件。
- 是否可通过 Android 容器代码替代。
- upstream 更新时冲突应如何处理。
- 对用户数据和 SillyTavern 行为是否有影响。

如果一个问题能只在 Android 容器层解决，就不要 patch upstream。

### 3. 生成产物不靠记忆

payload、manifest、APK 都是生成产物。生成产物必须能追溯到：

- SillyTavern repo。
- 请求的 ref。
- 实际 commit。
- `package.json` version。
- `package-lock.json` hash。
- patch queue hash。
- runtime archive hash。
- 转换脚本版本。
- 构建机器上的 Node/npm/Git 版本。

### 4. 用户体验隐藏中间态

用户不关心 runtime、payload、Node、端口或控制面板。运行阶段需要一个全屏启动页和 WebView 主界面。

启动页只承担两个职责：

- server 未 ready 时展示 stAPK / SillyTavern 风格的 loading。
- 失败时展示明确错误和复制诊断日志入口。

一旦本机 server ready，WebView 加载 `http://127.0.0.1:8000/` 或 `http://localhost:8000/`。页面内的 SillyTavern loading 由 upstream 前端负责。

### 5. App 私有目录优先

0.3.0 使用 App 私有目录。这样权限少，行为稳定，用户数据不会暴露到公共存储。

由于 0.2.0 已经把数据放在 App 私有目录下，0.3.0 必须设计升级兼容，不能因为目录重排覆盖用户数据。

### 6. 失败必须可诊断

转换失败、patch 冲突、engine mismatch、runtime 缺失、payload 校验失败、server 启动失败、WebView 加载失败，都必须有日志和明确错误码。

## 总体架构

```text
stapk-termux
├── scripts/
│   ├── stapk-transform.mjs              # 已有：从 upstream 生成 payload 与 manifest
│   ├── stapk-verify-transform.mjs       # 待补：校验 payload / manifest / patch queue
│   └── prepare-sillytavern-payload.sh   # 既有脚本，0.3.0 后降级为历史参考或兼容入口
├── patches/
│   └── sillytavern/
│       ├── series                       # 已有：patch 应用顺序，当前为空队列
│       └── *.patch                      # 可选：Android 转换专用 patch
├── transform/
│   ├── config/
│   │   └── config.android.yaml          # 已有：Android 默认配置模板
│   └── schemas/
│       └── payload-manifest.schema.json # 待补：manifest 结构校验
├── mobile/
│   └── app/src/main/
│       ├── assets/
│       │   ├── payload.tgz              # 0.3.0 由转换脚本生成或刷新
│       │   ├── payload-manifest.json    # 0.3.0 由转换脚本生成或刷新
│       │   └── runtime-android-arm64-node24.zip # 已有：Node 24 Android arm64 runtime
│       ├── java/com/stapk/mobile/
│       │   ├── MainActivity.kt          # 改为全屏启动页 + WebView
│       │   ├── RuntimeManager.kt        # 保留并重构为自动部署/启动
│       │   ├── KeepAliveService.kt      # 保留后台保活职责
│       │   └── TavernWebViewClient.kt   # 保留文件选择/下载/外链处理
│       └── res/
│           └── layout/activity_main.xml # 移除控制面板布局
└── docs/superpowers/specs/
    └── 2026-06-25-stapk-transformer-design.md
```

说明：

- `scripts/stapk-transform.mjs`、`patches/sillytavern/series`、`transform/config/config.android.yaml` 已在当前工作区出现。
- `mobile/app/src/main/assets/payload.tgz` 当前仍作为 APK asset 存在；0.3.0 的目标是由转换脚本生成或刷新它，不再作为人工维护输入。
- `runtime-poc.zip` 已移除；当前 runtime asset 是 `runtime-android-arm64-node24.zip`。
- CI / Release 已过渡运行 transform 并上传报告，但尚未完全用 `build/ci-payload` 替代 APK 内 assets；长期策略仍需一次 CI 全流程耗时/配额实测支撑。

## 转换流水线设计

### 命令入口

主命令：

```bash
node scripts/stapk-transform.mjs \
  --ref release \
  --runtime mobile/app/src/main/assets/runtime-android-arm64-node24.zip \
  --out mobile/app/src/main/assets \
  --clean
```

参数语义：

| 参数 | 必填 | 说明 |
|------|------|------|
| `--repo` | 否 | SillyTavern upstream repo，默认 `https://github.com/SillyTavern/SillyTavern.git` |
| `--ref` | 是 | branch、tag 或 commit。release 构建必须记录原始 ref 和解析后的 commit |
| `--runtime` | 是 | Android runtime archive 路径；0.3.0 release 必须指向 Node 20+ arm64 runtime |
| `--out` | 是 | 输出目录，默认面向 `mobile/app/src/main/assets` |
| `--clean` | 否 | 清理中间工作目录后重新生成 |
| `--allow-engine-mismatch` | 否 | 允许 Node engine 不满足，但 manifest 必须记录 |
| `--allow-native-addon` | 否 | 允许 native addon 扫描命中，但 manifest 必须记录 |

工作目录：

```text
build/stapk-transform/
├── upstream/       # 干净 SillyTavern checkout
├── patched/        # 应用 patch queue 后的源码
├── package/        # 安装生产依赖后的打包目录
└── reports/        # 转换日志、patch 报告、native addon 扫描报告
```

使用 `build/` 的原因是根 `.gitignore` 已忽略 `build/`，不需要额外忽略中间源码。

### 固定步骤

#### Step 1：准备工作目录

- 如果传入 `--clean`，删除 `build/stapk-transform/`。
- 创建 `upstream/`、`patched/`、`package/`、`reports/`。
- 记录工具版本：
  - `git --version`
  - `node --version`
  - `npm --version`
  - `tar --version` 或平台可用 tar 信息

#### Step 2：拉取 upstream

推荐过程：

```bash
git init build/stapk-transform/upstream
git -C build/stapk-transform/upstream remote add origin https://github.com/SillyTavern/SillyTavern.git
git -C build/stapk-transform/upstream fetch --depth=1 origin <ref>
git -C build/stapk-transform/upstream checkout --detach FETCH_HEAD
git -C build/stapk-transform/upstream rev-parse HEAD
```

如果 `<ref>` 是 branch，manifest 同时记录：

- requested ref，例如 `release`
- resolved commit，例如 `51ad27fb86d39a3daca3adaa970375c9670c12df`

这样可以避免“release 分支后来移动了，但 APK 说不清打包了哪个 commit”。

#### Step 3：读取 upstream 元数据

必须读取：

- `package.json`
- `package-lock.json`
- `LICENSE`
- `server.js`
- `src/command-line.js`

必须记录：

- `package.json` 中的 `version`
- `package.json` 中的 `engines.node`
- `package-lock.json` SHA-256
- `server.js` 是否存在
- `src/command-line.js` 是否包含 `configPath`、`dataRoot`、`port`

如果 `package-lock.json` 不存在，转换失败。原因是没有 lock file 就无法得到稳定依赖图。

#### Step 4：校验 Node engine

转换脚本读取 `package.json.engines.node`，并同时校验两个版本：

- 构建机 Node 版本：用于运行转换脚本和执行 `npm ci`。
- Android runtime Node 版本：从 `--runtime` archive 的 manifest 或实际执行结果读取，用于 APK 内运行 SillyTavern。

两者都必须满足 upstream engine。只满足构建机 Node 不足以发布，因为最终用户运行的是 APK 内 runtime。

规则：

- 两个版本都满足 engine：继续。
- 任一版本不满足 engine：默认失败。
- 用户显式传入 `--allow-engine-mismatch`：继续，但 manifest 写入：

```json
{
  "engine_check": {
    "required": ">= 20",
    "build_node": "v20.x.x",
    "runtime_node": "v19.0.0",
    "passed": false,
    "override": true
  }
}
```

0.3.0 release 构建不应使用 `--allow-engine-mismatch`。当前 payload manifest 已记录 runtime Node `v24.17.0` 且 engine check 通过；后续如果 upstream 提高 engine 要求，转换脚本仍必须默认阻断 mismatch。

#### Step 5：复制干净源码到 patched

将 `upstream/` 复制到 `patched/`，排除：

- `.git/`
- 本地转换报告
- 临时构建目录

复制后在 `patched/` 中运行 patch queue。

#### Step 6：应用 Patch Queue

patch queue 目录：

```text
patches/sillytavern/
├── series
├── 0001-*.patch
├── 0002-*.patch
└── ...
```

`series` 内容示例：

```text
0001-android-default-config.patch
0002-webview-download-compat.patch
```

应用规则：

```bash
PROJECT_ROOT="$(pwd)"
git -C build/stapk-transform/patched init
git -C build/stapk-transform/patched config user.name "stAPK Transformer"
git -C build/stapk-transform/patched config user.email "transformer@stapk.local"
git -C build/stapk-transform/patched add .
git -C build/stapk-transform/patched commit -m "baseline"
git -C build/stapk-transform/patched apply --3way "$PROJECT_ROOT/patches/sillytavern/0001-android-default-config.patch"
```

如果 patch 冲突：

- 转换失败。
- 输出冲突文件列表。
- 不生成 payload。
- `reports/patch-report.json` 记录失败 patch、目标 commit、冲突文件。

patch 规范：

- patch 文件名必须有序号。
- patch 顶部必须包含注释块：

```text
Subject: [stapk] android default config
Reason: Make SillyTavern start cleanly inside Android private app storage.
Upstream impact: Android-only defaults; no business logic change.
Fallback: Can be replaced by external config template if upstream config semantics change.
```

若没有 patch，`series` 可以为空。空 patch queue 也是合法状态，但 manifest 仍必须记录空队列 hash。

#### Step 7：安装生产依赖

由于 payload 中存在 `package-lock.json`，0.3.0 应使用：

```bash
npm ci --omit=dev --ignore-scripts --no-audit --no-fund --loglevel=error --no-progress
```

选择 `npm ci` 的原因：

- 严格使用 lock file。
- lock 与 package 不一致时失败。
- 比 `npm install` 更适合 release 构建。

默认 `--ignore-scripts` 的原因：

- 减少构建机执行第三方包 postinstall 的不确定性。
- 避免在 Linux CI 里生成不适合 Android 的 native artifact。

如果 upstream 未来必须依赖 install scripts，转换脚本必须先失败，并把具体包名和脚本写入报告。不能静默放开。

#### Step 8：平台无关性与 native addon 校验

> 评审决定（2026-06-25，问题 1 / 问题 2）：当前 payload 是平台无关的（WASM-only，`onnxruntime-web`，零 `.node`）。这是它能在 Android arm64 运行的契约基线，转换脚本必须主动守护，而不是靠模拟器启动兜底。判定一律基于**打包结果中的实际产物**，关键字只作为信号记录，不直接据此失败。

校验分三层，语义互不混淆：

**① 真实原生二进制（硬失败）**

- 扫描打包结果中是否存在 `.node` 文件。
- 命中即转换失败：这是确凿会在 Android 上崩的证据。

**② 平台相关产物（硬失败）**

- 解析每个已安装依赖 `package.json` 的 `os` / `cpu` / `libc` 字段。
- 检查 `optionalDependencies` 中的平台专用包是否被装入打包目录（例如被解析为 Linux/x64 的预编译产物）。
- 命中即转换失败：构建机平台（Linux x64）与目标平台（Android arm64）不一致时，`npm ci` 可能静默拉错平台产物，必须在转换阶段第一时间暴露。

**③ 构建意图信号（仅告警，不失败）**

- 关键字：`binding.gyp`、`node-gyp`、`prebuild`、`node-pre-gyp`、`bindings`、`nan`、`node-addon-api`。
- 这些只说明某个包**可能**想编译原生模块，不等于打包结果里真有原生产物。由于使用 `--ignore-scripts`，编译不会发生。
- 命中只写入 `reports/` 与 manifest 作为信号，**不阻断转换**。这避免「正常的纯 JS 依赖声明了 `node-addon-api` 就误报失败、又因 release 禁用 override 而死锁」。

override 规则：

- ① 与 ② 默认硬失败；显式传入 `--allow-native-addon` 才继续，但 manifest 必须记录命中列表与 override 标记。
- ③ 永不阻断，无需 override。

0.3.0 release 构建不应使用 `--allow-native-addon`。如果 upstream 引入真实 native addon 或平台相关产物，需要先设计 Android ABI 构建链路。

#### Step 9：注入 Android 默认配置

SillyTavern 当前可通过 `--configPath` 和 `--dataRoot` 指定路径。0.3.0 不应把用户配置写死在 upstream 源码里。

推荐配置来源：

```text
transform/config/config.android.yaml
```

模板来源规则：

- 以 upstream `SillyTavern/default/config.yaml` 为基线。
- 只覆盖 Android 容器必须稳定的字段。
- 每次 upstream ref 更新时，对比 upstream 默认配置和 Android 模板，输出字段差异报告。
- 模板缺少 upstream 新增必需字段时，转换失败或要求人工确认。

0.3.0 首版 Android 特化字段建议限定为：

```yaml
listen: false
listenAddress:
  ipv4: 127.0.0.1
  ipv6: '[::1]'
protocol:
  ipv4: true
  ipv6: false
browserLaunch:
  enabled: false
  hostname: '127.0.0.1'
  port: -1
  avoidLocalhost: true
ssl:
  enabled: false
whitelistMode: true
whitelist:
  - ::1
  - 127.0.0.1
enableCorsProxy: false
disableCsrfProtection: false
securityOverride: false
```

不建议在模板中写入：

- 用户 API key、模型供应商、角色、聊天、扩展配置。
- 设备绝对路径。`dataRoot` 由启动参数 `--dataRoot <filesDir>/user_data` 提供，避免模板随安装路径变化。
- 未经验证的插件/扩展策略，例如强行关闭 `extensions.autoUpdate` 或固定 `git.backend`。这些可能影响 upstream 行为，必须单独实测后再决定。

该文件用于首次运行时复制到：

```text
filesDir/user_config/config.yaml
```

启动参数显式传入：

```text
--configPath <filesDir>/user_config/config.yaml
--dataRoot <filesDir>/user_data
--port 8000
```

> 评审决定（2026-06-25，问题 3）：网络与安全字段（`listen`、`listenAddress`、`protocol.ipv4/ipv6`、`browserLaunch`、`ssl`、`whitelist` 等）**统一以 `config.android.yaml` 为唯一来源**，不在命令行重复传入。原因：
>
> - 已核对 `command-line.js` 的优先级为 `cliArguments.X ?? getConfigValue(...) ?? default`，CLI 与 config 双写虽因 CLI 优先而结果一致，但两处维护、对不上时难排查。
> - config 是声明式、可读、可 diff，且能与 upstream `default/config.yaml` 做字段差异审查（漂移检查的抓手）。
> - `enableIPv4`/`enableIPv6` 在 upstream 是 `string` 类型经 `stringToBool` 转换，命令行写法易出歧义；放进 config 由 YAML 布尔承载更稳。
>
> CLI 只保留与设备/安装路径强相关的最小集：`--configPath`、`--dataRoot`，外加 `--port 8000`（端口是容器最该锁死、也最该显式可见的一项）。

如果 `config.android.yaml` 缺失，App 可以让 SillyTavern 的 `initConfig(configPath)` 初始化配置，但转换验证必须记录“使用 upstream 默认初始化配置”。release 推荐提供显式模板，避免不同 upstream 版本默认值漂移。

模板验收：

- YAML 可解析。
- SillyTavern 使用该模板和 Android 启动参数可以启动。
- `browserLaunch.enabled` 实测不会拉起外部浏览器。
- `listen` 与 `listenAddress` 实测只服务本机 WebView。
- 现有用户 `filesDir/user_config/config.yaml` 存在时不被覆盖。

#### Step 10：生成 payload

payload 包结构：

```text
payload.tgz
├── SillyTavern/
│   ├── package.json
│   ├── package-lock.json
│   ├── server.js
│   ├── src/
│   ├── public/
│   └── node_modules/
└── payload-manifest.json
```

打包要求：

- 使用稳定路径名 `SillyTavern/`。
- 保留可执行权限。
- 不包含 `.git/`。
- 不包含转换中间报告。
- 不包含开发依赖。
- 不包含用户数据。

#### Step 11：生成 manifest

`payload-manifest.json` 示例结构：

```json
{
  "schema_version": 1,
  "transformer": {
    "name": "stapk-transform",
    "version": "0.3.0",
    "created_at": "2026-06-25T00:00:00Z"
  },
  "sillytavern": {
    "repo": "https://github.com/SillyTavern/SillyTavern.git",
    "requested_ref": "release",
    "resolved_commit": "51ad27fb86d39a3daca3adaa970375c9670c12df",
    "version": "1.18.0",
    "package_lock_sha256": "<sha256>"
  },
  "runtime": {
    "archive": "runtime-android-arm64-node24.zip",
    "archive_sha256": "<sha256>",
    "node_version": "v24.17.0"
  },
  "patch_queue": {
    "series_sha256": "<sha256>",
    "patches": [
      {
        "file": "0001-android-default-config.patch",
        "sha256": "<sha256>"
      }
    ]
  },
  "engine_check": {
    "required": ">= 20",
    "build_node": "v20.x.x",
    "runtime_node": "v20.x.x",
    "passed": true,
    "override": false
  },
  "native_addon_scan": {
    "has_native_addon": false,
    "matches": []
  },
  "payload": {
    "archive": "payload.tgz",
    "archive_sha256": "<sha256>",
    "unpacked_size_bytes": 0,
    "required_free_bytes": 0
  },
  "tools": {
    "git": "git version ...",
    "node": "v20.x.x",
    "npm": "..."
  }
}
```

manifest 是运行时和 release 页面共同引用的权威来源。

## Android 容器设计

### MainActivity

0.3.0 的 `MainActivity` 应从控制面板变为全屏启动容器。

职责：

- 请求必要权限，例如 Android 13+ 通知权限。
- 展示全屏启动页。
- 初始化 `RuntimeManager`。
- 监听 server ready 状态。
- 创建和配置 WebView。
- 在 server ready 后加载 SillyTavern。
- 在失败时展示错误页和复制诊断日志入口。

不再包含：

- `Start Server`。
- `Stop Server`。
- `Open Browser`。
- `Backup Data`。
- `Restore Data`。
- `Manage Extensions`。
- Node 版本调试按钮。

启动页视觉：

```text
黑色背景
居中 stAPK / SillyTavern 标识
下方 loading 动效
状态文字：Initializing... / Starting... / Loading...
失败时替换为错误摘要和复制日志按钮
```

启动页可以是原生 View，也可以是本地 HTML。推荐先用原生 View，原因是 server 未 ready 时 WebView 无法访问本机页面。

当 server ready 后，WebView 加载：

```text
http://127.0.0.1:8000/
```

如果在 Android 版本或 network security 配置中发现 `127.0.0.1` 兼容问题，回退到：

```text
http://localhost:8000/
```

回退规则必须写入诊断日志。

### RuntimeManager

`RuntimeManager` 是 App 内运行时编排中心。

职责：

- 校验 Android runtime archive 是否存在；0.3.0 release 输入必须是 Node 20+ arm64 runtime。
- 校验 `payload.tgz` 和 `payload-manifest.json` 是否存在。
- 校验 manifest schema。
- 解压 runtime 到 `filesDir/runtime/`。
- 解压 SillyTavern 程序文件到 `filesDir/app_payload/SillyTavern/`。
- 初始化或保留用户数据目录。
- 复制 Android 默认配置到 `filesDir/user_config/config.yaml`。
- 启动 Node 进程。
- 收集 stdout / stderr。
- 轮询 `http://127.0.0.1:8000/`。
- 停止进程。

不再承担：

- 备份数据。
- 恢复数据。
- 清理插件。
- 处理外部浏览器主路径。

推荐目录：

```text
filesDir/
├── runtime/
│   ├── bin/node
│   └── lib/*.so
├── app_payload/
│   └── SillyTavern/
├── user_data/
├── user_config/
│   └── config.yaml
├── logs/
│   ├── runtime.log
│   ├── node.log
│   └── webview.log
└── state/
    ├── installed-payload-manifest.json
    ├── installed-runtime-manifest.json
    └── migration-state.json
```

### 0.2.0 数据迁移

当前 0.2.0 实现将 SillyTavern 解压到：

```text
filesDir/SillyTavern/
```

其中用户数据位于：

```text
filesDir/SillyTavern/data/
```

0.3.0 首次启动时必须检测旧目录。

推荐迁移规则：

1. 如果 `filesDir/user_data/` 已存在，不迁移。
2. 如果 `filesDir/SillyTavern/data/` 存在且 `filesDir/user_data/` 不存在，将旧 `data` 目录移动到 `filesDir/user_data/`。
3. 如果移动失败，复制后校验文件数量和总大小，再删除旧目录。
4. 迁移结果写入 `filesDir/state/migration-state.json`。
5. 不删除旧 `filesDir/SillyTavern/` 程序目录，直到新 payload 成功安装并启动一次。

这样可以在不提供 App 内备份功能的前提下，保护 0.2.0 用户升级数据。

> 评审决定（2026-06-25，问题 4，数据安全红线）：
>
> - **目录合同与可工作的迁移 hook 必须在同一个 PR 落地**，不允许「先把数据读写切到 `filesDir/user_data`、迁移逻辑留 TODO」。否则会出现「Activity 已读新目录、迁移仍为空」的中间态，0.2.0 用户升级后会看到空数据（旧数据其实还在 `filesDir/SillyTavern/data` 但 App 不再读）。
> - 迁移 hook 即便在 Phase 2 阶段，也必须是**可工作的最小实现**（检测旧目录 → 移动/复制校验 → 写 `migration-state.json`），不能是空函数。
> - 迁移必须**幂等 + 失败回滚**：任一步失败保留旧目录并在启动页报错。
> - **迁移未确认成功，不得启动 server**。绝不允许在空目录上把 server 跑起来；此时应停在启动失败页并提供复制诊断日志入口。
> - 现有的「不删旧目录直到新 payload 启动一次」只防误删，不防误读，因此上述「迁移成功前不启动 server」是必须叠加的第二层保护。

### Node 启动合同

`ProcessBuilder` 命令：

```text
<filesDir>/runtime/bin/node
server.js
--configPath <filesDir>/user_config/config.yaml
--dataRoot <filesDir>/user_data
--port 8000
```

> 评审决定（2026-06-25，问题 3）：命令行不再传 `--listen`、`--enableIPv4`、`--enableIPv6`。这些网络字段由 `config.android.yaml` 承载，保持单一来源。

工作目录：

```text
<filesDir>/app_payload/SillyTavern
```

环境变量：

```text
PATH=<filesDir>/runtime/bin:<system PATH>
LD_LIBRARY_PATH=<filesDir>/runtime/lib:<existing LD_LIBRARY_PATH>
TMPDIR=<cacheDir>
HOME=<filesDir>
NODE_ENV=production
```

诊断日志必须记录：

- node 绝对路径。
- 工作目录。
- 参数列表。
- `PATH` 首段。
- `LD_LIBRARY_PATH` 首段。
- payload manifest 摘要。
- server ready 耗时。
- 退出码。

不得在日志中写入用户 API key、cookie 或完整聊天内容。

### KeepAliveService

0.3.0 中 `KeepAliveService` 保留，但触发逻辑和职责边界必须改变。

0.2.0 是用户点击 `Open Browser` 时拉起前台服务。0.3.0 没有 `Open Browser`，因此：

- App 启动 SillyTavern server 后，直接启动前台服务。
- WebView 在前台时也允许服务存在，因为 server 是用户可感知的长期任务。
- 用户退出 App 或系统销毁 Activity 时，不立即杀掉 server 是 0.3.0 设计目标，不是当前事实。

要让该目标成立，server 生命周期必须从 `MainActivity` 中移出。`MainActivity.onDestroy()` 不得直接调用 `runtimeManager.stopSillyTavern()`，否则系统销毁 Activity 时仍会停止 Node 进程。

> 评审决定（2026-06-25，问题 5）：架构选型为「**`KeepAliveService` 升级为 Node 进程持有者**」，而非新建 `RuntimeService` 或引入 `RuntimeProcessOwner` 单例。理由：复用现有组件、改动集中，并把「Node 进程 + 前台通知 + `WakeLock` + 通知栏 `Stop` action」四者收敛到同一处，与前置阻断 4 要求的通知栏 Stop 入口天然契合。`RuntimeProcessOwner` 单例持有 OS 进程 + WakeLock 生命周期边界模糊、易泄漏，按反模式排除。
>
> 具体职责划分：
>
> - `KeepAliveService` 持有 `RuntimeManager`（或其持有的 Node `Process`），用 `startForegroundService` + `START_STICKY` 拉起；只有 `onDestroy()` 或通知栏 `Stop` action 才真正 kill Node。
> - `MainActivity` 通过 `bindService` 拿到引用，只读状态/日志、渲染启动页与 WebView，**不再拥有进程**；`onDestroy()` 不再调用 `stopSillyTavern()`。

当前 0.2.0 代码不满足这个目标：`KeepAliveService` 只负责前台通知和 `WakeLock`（`START_NOT_STICKY`），Node 进程仍由 `MainActivity` 间接持有。因此 0.3.0 实施计划必须包含生命周期迁移，并用 Activity 重建、Home 键、最近任务切换、锁屏、通知点击返回等场景回归。

0.3.0 开发调试阶段可以不提供主界面停止按钮，并通过 `adb shell am force-stop com.stapk.mobile` 停止。正式 release 前必须提供通知栏 `Stop` action；该入口不进入主界面，不恢复 0.2.0 控制面板。

### TavernWebViewClient

WebView 只负责本机 SillyTavern。

目标规则：

- `http://127.0.0.1:8000` 和 `http://localhost:8000` 留在 WebView。
- 外部 `https://` 链接交给系统浏览器，但当前 `TavernWebViewClient` 尚未实现分流，需要在 0.3.0 中补齐并验证。
- 文件上传使用 Android 文件选择器，但必须实测 SillyTavern 导入角色、导入聊天、导入图片等入口。
- 下载使用 Android 下载桥接或 SAF，但必须实测普通下载、Blob 下载、文件名、MIME type、重复文件名和失败提示。
- Blob 下载可以沿用 JS bridge 思路，但当前实现写公共 Downloads；0.3.0 必须重新确认它是否符合 App 私有目录策略、scoped storage 和目标 Android 版本行为。
- 不修改 SillyTavern DOM 来改变聊天体验。
- 不劫持 SillyTavern API。

WebView 能力验收不能只看代码存在。每次 release 前必须逐项记录：上传、下载、导入、导出、外链、返回键、刷新、横竖屏切换和进程重建后的页面恢复。

### 网络安全配置

当前已有：

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">127.0.0.1</domain>
        <domain includeSubdomains="true">localhost</domain>
    </domain-config>
</network-security-config>
```

当前配置的意图是只允许本机明文访问，并保持全局明文关闭。[Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config) 文档把 `domain-config` 描述为针对特定 destination 的配置，`base-config cleartextTrafficPermitted="false"` 是 target Android 9/API 28+ 的默认方向；但 `127.0.0.1` 作为 IP literal、`localhost` 作为 hostname 在目标 Android 版本和 WebView 网络栈中的实际匹配必须实测。

0.3.0 不得为了简化 WebView 加载打开全局明文流量。发布前必须在目标模拟器或真机上分别验证：

- `http://127.0.0.1:8000/` 是否可被 WebView 加载。
- `http://localhost:8000/` 是否可被 WebView 加载。
- 任意非本机 `http://` URL 是否不会被 App WebView 直接以明文加载。
- 实际采用的 host 和回退路径是否写入诊断日志。

## Release 与 CI 设计

### CI 目标

0.3.0 的 CI 不应只跑 Gradle。它还必须验证转换过程。

当前 `.github/workflows/ci.yml` 和 `.github/workflows/release.yml` 尚未配置 `actions/setup-node`，因此 0.3.0 需要显式增加并固定 Node 20 LTS 或更高版本。

推荐 CI 顺序：

```text
checkout with lfs
setup JDK / Android SDK
setup Node 20+ for transformer
verify Android runtime archive is Node 20+
run stapk-transform
run stapk-verify-transform
run Gradle unit tests
build Debug APK
upload Debug APK + manifest + transform reports
```

### Release 目标

tag release 流程：

```text
checkout tag
setup JDK / Android SDK / Node 20+
verify Android runtime archive is Node 20+
run stapk-transform --ref <configured SillyTavern ref>
run stapk-verify-transform
build release APK
copy payload manifest into release assets
generate APK sha256
publish APK, sha256, payload-manifest.json, transform-report.json
```

release 资产建议：

```text
stapk-mobile_v0.3.0_arm64-v8a.apk
stapk-mobile_v0.3.0_arm64-v8a.apk.sha256
payload-manifest_v0.3.0.json
transform-report_v0.3.0.json
```

### 版本号策略

0.3.0 需要实现 APK `versionName` 从 Git tag 注入，例如 `v0.3.0` -> `0.3.0`。

当前实现不满足这个目标：`mobile/app/build.gradle.kts` 仍写死 `versionName = "1.0"`；Release workflow 虽然设置了 `TERMUX_APP_VERSION_NAME=${RELEASE_TAG#v}`，但 Gradle 文件尚未读取该环境变量。实施 0.3.0 时必须把这件事纳入 Release 构建任务，并在 `assembleRelease` 后用 APK metadata 或 Gradle 输出验证。

SillyTavern 版本单独记录在 payload manifest 中，不混入 APK 版本号。

示例：

```text
APK versionName: 0.3.0
SillyTavern version: 1.18.0
SillyTavern commit: 51ad27fb86d39a3daca3adaa970375c9670c12df
Patch queue hash: ...
Runtime node: v20.x.x
```

## Patch Queue 维护规则

### patch 分类

| 分类 | 说明 | 示例 |
|------|------|------|
| `android-config` | Android 默认配置或路径适配 | 默认 dataRoot / browser launch 行为 |
| `webview-compat` | WebView 兼容修正 | 下载、文件选择、外链 |
| `runtime-compat` | Android Node runtime 兼容 | 禁用不适合 Android 的自动打开浏览器 |
| `branding` | stAPK 必需标识 | loading 标识或 manifest metadata |

### patch 最小化原则

每次 upstream 更新前先尝试空 patch queue。

只有满足以下条件时才新增 patch：

- Android 容器层无法解决。
- 配置模板无法解决。
- 不 patch 会导致启动失败或核心功能不可用。
- patch 范围可以清晰解释。

### patch 审查清单

新增或修改 patch 前必须回答：

- 是否改了 SillyTavern 业务逻辑？
- 是否影响桌面/浏览器版行为？
- 是否能改成运行时配置？
- 是否能改成 Android WebViewClient 行为？
- upstream 更新冲突时应该保留、重写还是删除？

### patch 冲突处理

不能自动跳过 patch。

冲突时输出：

```text
FAILED_PATCH=0002-webview-download-compat.patch
UPSTREAM_COMMIT=<commit>
CONFLICT_FILES=<files>
REPORT=build/stapk-transform/reports/patch-report.json
```

开发者处理冲突后，需要重新生成 patch 并跑完整转换验证。

## 数据与权限策略

### 用户数据

0.3.0 使用 App 私有目录：

```text
filesDir/user_data
```

原因：

- 不需要外部存储权限。
- 不受 Android scoped storage 差异影响。
- 与 0.2.0 私有目录路线一致。
- 卸载 App 时数据随 App 清理，符合 Android 默认语义。

### 导入导出

0.3.0 不提供独立备份/恢复按钮。

设计目标是保留 SillyTavern 自己的导入/导出入口。Android 容器只提供 WebView 所需的文件选择、下载桥接或 SAF 入口，不额外封装备份恢复 UI。

该能力必须逐项验证，不能在实现前写成事实。最低验证项：

- 角色卡导入。
- 聊天记录导入。
- 图片或附件选择。
- 角色卡导出。
- 聊天记录导出。
- Blob 下载。
- 外链打开。

如果任一项依赖公共 Downloads、系统文件选择器或 scoped storage 行为，必须记录目标 Android 版本、实际保存位置、失败提示和是否需要权限。

### 插件管理

0.3.0 不提供插件清理按钮。

插件仍是 SillyTavern 数据/扩展体系的一部分。用户如需处理，后续可以通过开发者工具、root、run-as、备份文件或 SillyTavern 自身能力处理。0.3.0 不为此增加 UI。

### 权限

保留：

- `INTERNET`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_DATA_SYNC`
- `POST_NOTIFICATIONS`
- `WAKE_LOCK`

不新增：

- `READ_EXTERNAL_STORAGE`
- `WRITE_EXTERNAL_STORAGE`
- `MANAGE_EXTERNAL_STORAGE`

若后续下载功能需要写公共 Downloads，优先使用系统文件选择 / SAF，而不是申请宽泛存储权限。

## 错误处理

### 转换阶段错误

| 错误 | 行为 |
|------|------|
| upstream ref 不存在 | 转换失败，输出 ref 与 repo |
| `package.json` 不存在 | 转换失败 |
| `package-lock.json` 不存在 | 转换失败 |
| Node engine 不满足 | 默认失败 |
| patch 冲突 | 转换失败，输出冲突文件 |
| `npm ci` 失败 | 转换失败，保留 npm log |
| native addon 扫描命中 | 默认失败 |
| manifest schema 不通过 | 转换失败 |
| payload 打包失败 | 转换失败 |

### 运行阶段错误

| 错误 | UI | 日志 |
|------|----|------|
| runtime archive 缺失 | 启动失败页 | 记录 asset 名称 |
| payload 缺失 | 启动失败页 | 记录 manifest 路径 |
| manifest 解析失败 | 启动失败页 | 记录 JSON 错误 |
| 磁盘空间不足 | 启动失败页 | 记录 required / available |
| node 不可执行 | 启动失败页 | 记录路径和权限 |
| 动态库加载失败 | 启动失败页 | 记录 stderr 尾部 |
| server ready 超时 | 启动失败页 | 记录超时时长、进程状态、node log 尾部 |
| WebView 加载失败 | WebView 错误页 | 记录 URL 和 error code |

失败页必须有：

- 错误摘要。
- 复制诊断日志按钮。
- 重试按钮。

不提供：

- 终端入口。
- 控制面板。
- 手动启动按钮。

## 验证策略

### 文档与设计验证

设计文档每次修改后必须检查：

- 是否把拟新增文件写成已存在文件。
- 是否包含未替换标记。
- 是否包含无法执行的命令。
- 是否与当前仓库事实矛盾。
- 是否把 App 内便利功能重新纳入 0.3.0 主线。

### 转换脚本验证

0.3.0 实施后，至少需要以下命令：

```bash
node scripts/stapk-transform.mjs --ref release --runtime mobile/app/src/main/assets/runtime-android-arm64-node24.zip --out mobile/app/src/main/assets --clean
node scripts/stapk-verify-transform.mjs --assets mobile/app/src/main/assets
```

预期：

- exit code 为 0。
- 生成 `mobile/app/src/main/assets/payload.tgz`。
- 生成 `mobile/app/src/main/assets/payload-manifest.json`。
- manifest 中 `resolved_commit` 可用 `git ls-remote` 或 `git cat-file` 追溯。
- manifest 中构建机 Node engine 与 Android runtime Node engine 均通过。
- native addon scan 未命中。

### Gradle 验证

```bash
cd mobile
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```

Windows PowerShell 可用：

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon :app:assembleDebug
```

预期：

- 单测通过。
- 生成 `mobile/app/build/outputs/apk/debug/app-debug.apk`。

### 模拟器验证

```bash
adb devices
adb install -r mobile/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.stapk.mobile
adb shell am start -n com.stapk.mobile/.MainActivity
```

预期：

- App 启动后没有控制面板。
- 首屏是 loading。
- server ready 后 WebView 显示 SillyTavern loading / 首页。
- 不需要点击 Start Server。
- 不需要点击 Open Browser。
- Activity 重建或从最近任务返回时，server 生命周期符合 0.3.0 设计；如果实现阶段尚未迁出 `MainActivity`，该项必须判失败。
- WebView 上传、下载、导入、导出、外链逐项有回归记录。
- `127.0.0.1` 与 `localhost` 的 WebView 加载行为有设备记录，实际采用的 host 写入诊断日志。

辅助检查：

```bash
adb shell run-as com.stapk.mobile ls files
adb shell run-as com.stapk.mobile ls files/app_payload/SillyTavern
adb shell run-as com.stapk.mobile ls files/user_data
adb shell run-as com.stapk.mobile cat files/state/installed-payload-manifest.json
```

### 负向验证

必须至少覆盖：

- 删除 `payload.tgz` 后启动，App 显示 payload 缺失。
- 改坏 manifest JSON 后启动，App 显示 manifest 解析失败。
- 使用不存在的 upstream ref 运行 transform，转换失败。
- 临时构造一个冲突 patch，转换失败并输出冲突文件。
- 使用不满足 engine 的 Node 运行 transform，默认失败。

## 分阶段路线

### Phase 0：设计落地前准备

目标：把 0.3.0 范围和事实边界固定下来。

输出：

- 本设计文档。
- 用户确认后的实施计划。

验收：

- 文档不包含已知虚构事实。
- 文档明确 WebView 是主界面容器。
- 文档明确 App 私有目录。
- 文档明确不做备份恢复、插件清理、控制面板。
- 文档把“当前事实、设计目标、待验证项”分开。
- 远端动态事实写明核对时间。

### Phase 1：转换脚本与 manifest

目标：从 upstream ref 生成 payload。

> 评审决定（2026-06-25，小问题 7）：**Phase 1 第 0 里程碑——先独立产出 Node 20+ Android arm64 runtime archive**。在编写任何转换脚本之前，必须先得到一个「真机 `node --version` 报 ≥ 20、且能 `node server.js` 起服务」的 runtime archive。这是比转换脚本更硬的前置：没有可运行的 Node 20+ runtime，后续转换流水线全是空中楼阁。该里程碑独立验收，不与转换脚本捆绑。

前置输入：

- **（第 0 里程碑）** Node 20+ Android arm64 runtime archive 已在真机验证可运行（`node --version` ≥ 20 且能启动 `server.js`）。
- 转换脚本运行环境 Node 版本已在本地文档和 CI 中固定。

已落地：

- `scripts/stapk-transform.mjs`
- Node 24 runtime 生成脚本与 `runtime-android-arm64-node24.zip`
- `patches/sillytavern/series`

待补：

- `scripts/stapk-verify-transform.mjs`
- `transform/schemas/payload-manifest.schema.json`

验收：

- 能从 `release` ref 生成 payload。
- 能记录 resolved commit。
- 能同时校验构建机 Node engine 和 Android runtime Node engine。
- 能扫描 native addon。
- 能应用空 patch queue。
- 能生成 manifest。
- manifest 记录 runtime archive sha256、runtime Node version 和 runtime 构建来源。

### Phase 2：Android 启动体验重构

目标：移除控制面板，打开即进入 SillyTavern。

Phase 2 必须采用 Phase 3 的最终目录约定，不能先引入临时目录再由 Phase 3 返工。数据迁移入口可以在 Phase 3 完成，但 `RuntimeManager` 的初始化流程必须预留并调用迁移步骤。

拟修改：

- `MainActivity.kt`
- `RuntimeManager.kt`
- `TavernWebViewClient.kt`
- `activity_main.xml`
- `KeepAliveService.kt`

验收：

- App 启动不显示控制面板。
- 自动部署 payload。
- 自动启动 server。
- WebView 加载本机 SillyTavern。
- 失败页可复制诊断日志。
- `RuntimeManager` 使用 `filesDir/runtime`、`filesDir/app_payload/SillyTavern`、`filesDir/user_config`、`filesDir/user_data`、`filesDir/state` 目录合同。
- 正式 release 前通知栏存在 `Stop` action，能停止 Node 进程并释放前台服务资源。

### Phase 3：数据目录与 0.2.0 迁移

目标：App 私有目录稳定，升级不丢数据。

Phase 3 与 Phase 2 可以作为同一实施计划内的连续步骤执行；如果拆成两个 PR，Phase 2 也必须先落最终目录合同和迁移 hook，Phase 3 只补迁移细节。

验收：

- 新安装使用 `filesDir/user_data`。
- 从 0.2.0 升级时迁移 `filesDir/SillyTavern/data`。
- 迁移过程可重复、可诊断。
- payload 更新不覆盖用户数据。

### Phase 4：CI / Release 接入转换

目标：release 不再依赖手工 payload。

拟修改：

- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`

验收：

- CI 先跑 transform，再跑 Gradle。
- Release 上传 APK、sha256、payload manifest、transform report。
- tag 对应的 Release 能追溯 SillyTavern commit。

### Phase 5：Patch Queue 首轮实战

目标：用一次 upstream ref 更新验证 patch queue 维护流程。

验收：

- 从当前 `release` commit 生成 APK。
- 切到另一个测试 commit 或 staging commit，转换要么成功，要么给出明确 patch 冲突。
- 冲突处理后 patch queue 可继续生成 APK。

## 风险

### Node runtime 版本风险

当前 runtime 已升级为 Node `v24.17.0`，并满足 SillyTavern 1.18.0 的 `engines.node >= 20`。剩余风险不再是已知 engine mismatch，而是 runtime 产物来源、动态库清单和 arm64 真机运行证据必须持续可追溯。

应对：

- 0.3.0 transform 默认阻断 engine mismatch。
- 保持 Android runtime Node 版本与 upstream engine 要求同步，不能只升级构建机 Node。
- runtime 版本、archive sha256、构建来源、提取脚本版本写入 manifest。
- CI 中同时校验构建机 Node 和 Android runtime Node。

### 转换脚本工具链漂移风险

当前仓库根目录已有 `package.json` / `package-lock.json`，CI / Release workflow 已固定 Node 20。后续风险主要来自依赖升级未同步 lockfile、CI Node 版本与本地版本差异、或转换脚本使用未声明特性。

应对：

- CI / Release 使用 `actions/setup-node` 固定 Node 20 LTS 或更高版本。
- 本地文档记录最低 Node 版本。
- 转换脚本启动时输出 `node --version` 并写入 transform report。
- 如新增根 `package.json`，必须同步声明 `engines.node`、`type` 和 lockfile 策略。

### Upstream 依赖变化风险

SillyTavern 更新可能引入 native addon、install script 或新的 Node API。

应对：

- `npm ci --ignore-scripts`。
- native addon 扫描。
- engine 检查。
- Android 模拟器启动验收。

### Patch 冲突风险

Patch Queue 可能在 upstream 更新时冲突。

应对：

- patch 最小化。
- 能用配置解决就不用 patch。
- 冲突即失败。
- 输出冲突报告。

### WebView 兼容风险

SillyTavern 前端面向浏览器，WebView 可能在下载、文件选择、Blob、外链、viewport 上有差异。

应对：

- Android 容器层处理文件选择和下载。
- 不修改业务 DOM。
- 每次 release 在模拟器或真机验证上传、下载、导入、导出、外链、返回键和聊天发送。

### App 私有目录不可见风险

用户不能用普通文件管理器直接访问 App 私有目录。

应对：

- 0.3.0 接受这个取舍，优先稳定和权限最小。
- 不在 0.3.0 做外部目录模式。
- SillyTavern 自身导入导出以 WebView 桥接验证结果为准，未通过前不得写成已可用能力。

### 生成 payload 体积风险

payload 当前约 164 MB 压缩包，解压后约 393 MB。CI 生成 payload 会增加构建时间和网络依赖。

应对：

- CI 使用缓存，但缓存不能替代 manifest 校验。
- release 上传 transform report。
- 必要时短期继续 LFS 跟踪生成 payload，但长期推荐 CI 生成。

> 评审决定（2026-06-25，小问题 8）：在切换到「长期 CI 生成 payload」前，必须先**实测一次 CI 全流程**（`npm ci` + 平台无关性校验 + 打包 + 上传 + LFS）的耗时与配额，再决定是否长期化。「CI 生成」不是默认结论，而是需要数据支撑的决策。过渡期保留现有 LFS payload 作为回退输入，但 release 验收以转换脚本生成的 payload 为准。

### Android 默认配置模板漂移风险

SillyTavern upstream 默认 `default/config.yaml` 会随版本变化。Android 模板如果长期复制一份旧配置，可能漏掉 upstream 新字段或覆盖不该覆盖的用户行为。

应对：

- `config.android.yaml` 以 upstream 默认配置为基线，只覆盖 Android 必需字段。
- 转换阶段输出 upstream default 与 Android 模板的字段差异。
- 模板缺少 upstream 新增必需字段时阻断 release。
- 用户已有 `filesDir/user_config/config.yaml` 时不得覆盖。

### 发布版无法停止服务风险

如果 0.3.0 正式 release 只依赖 `adb force-stop` 或系统强制停止，普通用户无法干净停止 Node server 和前台服务。

应对：

- 主界面不恢复控制面板。
- 正式 release 前在 `KeepAliveService` 前台通知中提供 `Stop` action。
- Stop action 必须停止 Node、释放 `WakeLock`、停止前台服务并写入日志。

## 验收标准

0.3.0 可以发布的最低标准：

- 从指定 upstream ref 生成 payload。
- manifest 记录 upstream commit、SillyTavern version、runtime version、patch queue hash。
- 构建机 Node engine 和 Android runtime Node engine 均通过，runtime 必须为 Node 20+。
- native addon scan 未命中。
- CI / Release 固定 Node 20+，并记录转换脚本运行 Node 版本。
- `config.android.yaml` 模板存在、可解析，且与 upstream 默认配置差异有报告。
- APK 构建通过。
- 模拟器安装后点击图标，无需任何用户操作进入 SillyTavern loading / 首页。
- 普通路径无控制面板、无启动按钮、无外部浏览器按钮、无备份恢复按钮、无插件清理按钮。
- 前台通知提供 Stop action，普通用户可以停止服务。
- App 私有目录中用户数据不被 payload 更新覆盖。
- 0.2.0 旧数据目录存在时有迁移保护。
- 失败页能复制诊断日志。

## 后续不属于 0.3.0 的方向

- 外部可见数据目录。
- App 内备份恢复。
- App 内插件管理。
- 多 ABI release。
- 在线更新 SillyTavern。
- 原生聊天 UI。
- 去 WebView 化。

这些可以在转换体系稳定后单独设计，不能混入 0.3.0 主线。

## 自审清单

本设计文档在实施前必须反复检查：

- 已落地文件和待补文件必须分开标注，不能把 `scripts/stapk-transform.mjs`、`runtime-android-arm64-node24.zip`、`transform/config/config.android.yaml` 继续写成拟新增。
- 当前事实均来自仓库文件、payload 内容或命令输出。
- 0.3.0 范围没有包含备份恢复和插件清理。
- WebView 被明确保留为主界面容器。
- App 私有目录被明确为数据目录。
- Node engine mismatch 仍是阻断规则；当前 Node 24 runtime 已满足 SillyTavern 1.18.0 的 `>= 20` 要求。
- Node 20+ Android arm64 runtime 来源被列为 Phase 1 前置输入。
- `.mjs` 工具链要求明确，不把根 `package.json` 当作当前事实。
- Phase 2 和 Phase 3 的目录合同一致。
- 正式 release 的停止入口落在通知栏，不回到控制面板。
- `config.android.yaml` 模板来源、候选字段和漂移验证已明确。
- APK `versionName` 注入被标为 0.3.0 待实现，不是当前事实。
- server 生命周期迁出 `MainActivity` 被标为 0.3.0 待实现，不是当前事实。
- WebView 上传、下载、导入、导出、外链被标为待验证，不是当前事实。
- network security 对 `127.0.0.1` 和 `localhost` 的实际行为被标为待验证。
- 0.2.0 前台服务和 WakeLock 只写成已实现，不写成已证明改善后台体验。
- 每个阶段都有可执行验收标准。
- 没有要求修改 upstream 仓库。
- 没有要求用户手工维护 payload。
- payload 平台无关性（WASM-only）被记为已核对事实，并由 Step 8 强校验守护。
- native addon 判定基于打包结果，关键字仅作告警，不导致误报死锁。
- 网络/安全字段单一来源为 `config.android.yaml`，命令行不重复传网络 flag。
- 数据迁移 hook 与目录合同同 PR，迁移未成功不启动 server。
- `KeepAliveService` 持有 Node 进程的架构选型已写入，`MainActivity` 不再持有进程。
