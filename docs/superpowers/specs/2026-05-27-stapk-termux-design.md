# stAPK Termux 定制版项目方案与实施计划

日期：2026-05-27  
状态：方案草案，等待确认后进入实现计划  
目标目录：`C:\Users\31029\Documents\GitHub\stapk-termux`

## 1. 项目目标

做一个轻改版 Termux APK，用 Termux 作为运行底座，内置 SillyTavern、Node.js、Git、npm 和必要运行依赖。用户安装 APK 后默认看到图形化控制面板，而不是命令行。控制面板封装启动、停止、打开网页、Git 更新、日志查看、备份恢复等动作，降低普通用户误操作概率。

第一版不追求重写 Termux，也不改 SillyTavern 官方启动流程。原则是：Termux 负责 Linux/Android 运行环境，SillyTavern 官方脚本负责依赖安装和启动，stAPK 只负责预置内容、GUI 封装、状态展示、日志和恢复。

## 2. 第一版范围

第一版只覆盖主流安卓手机，目标 ABI 为 `arm64-v8a`，Termux 架构对应 `aarch64`。最低 Android 版本沿用当前 Termux `apt-android-7` 变体，即 Android 7+。

必须具备：

- APK 内置可离线运行的 Termux bootstrap。
- APK 内置 `nodejs-lts`、`npm`、`git`、`bash`、`coreutils`、`tar`、`gzip` 等基础工具。
- APK 内置 SillyTavern release 分支源码，并保留 Git 仓库信息，使后续 `git pull` 可用。
- APK 内置已安装好的 `node_modules`，首次启动不依赖 `git clone` 或 `npm install`。
- App 首次启动时解包并初始化 `$HOME/SillyTavern`。
- 默认 Launcher 页面是控制面板，不直接进入 Termux 终端。
- 普通功能通过按钮执行白名单脚本，不给普通用户自由命令行。
- 终端入口保留在高级/诊断菜单里，用于排障，不放在首屏。
- 更新按钮执行官方推荐的 Git 更新流程：进入 SillyTavern 目录后 `git pull` 或 `git pull --rebase --autostash`。
- 启动按钮执行官方 `bash start.sh`，不重写 SillyTavern 启动逻辑。

第一版不做：

- 不支持 32 位 Android。
- 不支持 x86/x86_64 模拟器专用包。
- 不做多用户云同步。
- 不改 SillyTavern 核心代码。
- 不提供任意命令执行入口给普通用户。
- 不上架应用商店，只按 GitHub Release 或手动分发 APK 处理。

## 3. 官方资源与获取位置

### 3.1 Termux App 源码

源码仓库：<https://github.com/termux/termux-app>  
用途：作为 APK 基础工程，保留 Termux 的终端、session、service、bootstrap 安装和 Android 适配能力。

建议起点：

- 优先从最新稳定 release tag 建 fork，例如当前已确认的 `v0.118.3`。
- 不直接在 master 上长期开发，避免上游未发布改动带来不可控差异。

注意点：

- Termux App 仓库是 GPLv3 only。分发修改版 APK 时，需要提供对应源码、许可证和修改说明。
- Termux 官方 GitHub build 使用公开测试签名，不应拿来作为正式分发签名。
- 如果沿用 `com.termux` 包名，会和用户手机上已安装的官方 Termux 冲突。
- 如果改成自己的包名，需要按 Termux 文档处理 `TermuxConstants`、manifest placeholder、bootstrap 中的路径和 package name。

### 3.2 Termux Packages 源码和包

源码仓库：<https://github.com/termux/termux-packages>  
用途：获取和构建 Termux 包，包括 `nodejs-lts`、`npm`、`git`，以及自定义 bootstrap。

关键包：

- `nodejs-lts`：Termux 当前包脚本显示版本为 `24.15.0`，满足 SillyTavern `node >= 20` 要求。
- `npm`：从 Termux `nodejs-lts` v24.13.0 起 npm 不再默认随 nodejs-lts 捆绑，必须显式包含。
- `git`：Termux 当前包脚本显示版本为 `2.54.0`，用于 SillyTavern 后续 Git 更新。

建议做法：

- 用 Termux Packages 构建自定义 `aarch64` bootstrap，而不是首次启动后联网 `pkg install`。
- bootstrap 内预装 `nodejs-lts`、`npm`、`git` 和基础工具，使首次启动离线可用。
- 保留 apt/dpkg 数据库，方便后续诊断或高级模式下更新包。

### 3.3 SillyTavern 源码

源码仓库：<https://github.com/SillyTavern/SillyTavern>  
官方 Android/Termux 文档：<https://docs.sillytavern.app/installation/android-%28termux%29/>  
官方更新文档：<https://docs.sillytavern.app/installation/updating/>  
当前已确认最新 release：`1.18.0`，发布日期 2026-05-03。

建议获取方式：

```bash
git clone --branch release --depth=1 https://github.com/SillyTavern/SillyTavern.git SillyTavern
cd SillyTavern
npm install --no-save --no-audit --no-fund --loglevel=error --no-progress --omit=dev --ignore-scripts
```

说明：

- 必须保留 `.git` 目录，否则后续按钮无法执行官方 Git 更新流程。
- `--depth=1` 可降低 APK 体积，后续 `git pull` 仍可工作。
- 构建时记录当前 commit、branch、SillyTavern 版本、Node 版本和 npm lock 状态到 `payload-manifest.json`。
- SillyTavern 是 AGPL-3.0。分发包含它的 APK 时，需要保留许可证，并提供对应源码或明确源码获取方式。

### 3.4 SillyTavern 官方启动流程

当前 release 分支 `start.sh` 的核心行为是：

```bash
cd "$(dirname "$0")"
export NODE_ENV=production
npm install --no-save --no-audit --no-fund --loglevel=error --no-progress --omit=dev --ignore-scripts
node "server.js" "$@"
```

因此第一版不改启动脚本，GUI 的“启动”按钮执行：

```bash
cd "$HOME/SillyTavern" && bash start.sh
```

这会带来一个可接受的特性：首次离线启动时，已有 `node_modules` 能直接使用；联网更新后，如果依赖变化，官方脚本会按官方方式尝试补齐依赖。

## 4. 技术选型

### 4.1 Android 层

基座：Termux App fork  
语言：优先沿用 Termux 现有 Java 代码和 XML 布局  
UI：Android 原生 Activity/Fragment，不引入 Jetpack Compose  
构建：Gradle + Android Gradle Plugin，沿用 Termux 工程结构  
目标包：第一版原型可临时沿用 `com.termux`，正式分发建议改为自有包名，例如 `com.stapk.termux`

选择 Java/XML 的原因：

- Termux App 现有代码以 Java 为主。
- 改动少，容易跟踪上游。
- 不引入新 UI 技术栈，减少构建和维护变量。

### 4.2 运行环境层

运行时：Termux `apt-android-7` bootstrap  
架构：`aarch64` / APK ABI `arm64-v8a`  
Node：Termux `nodejs-lts`  
npm：Termux `npm` 包  
Git：Termux `git` 包  
Shell：Termux bash/sh  
压缩格式：第一版用 `tar.gz`，如果 APK 体积压力明显，再切换到 `tar.zst` 并内置 `zstd`

### 4.3 SillyTavern 层

分支：默认 `release`  
更新：默认 `git pull --rebase --autostash`  
启动：默认 `bash start.sh`  
数据模式：第一版采用官方默认 standalone 模式，即配置和数据保留在 SillyTavern 安装目录中  
网页入口：控制面板读取配置或默认打开 `http://127.0.0.1:8000`

不默认使用 `--global`，因为它改变数据路径。后续如果要把用户数据从程序目录中彻底拆出，可以作为第二阶段评估。

## 5. 总体架构

```text
Android Launcher
  -> stAPK Control Activity
      -> Command Runner
          -> Termux Service / Session
              -> $PREFIX/bin/stapk-* wrapper scripts
                  -> $HOME/SillyTavern/start.sh
                  -> git pull
                  -> process stop / log / backup scripts

APK assets
  -> SillyTavern payload tarball
  -> payload-manifest.json

Termux bootstrap
  -> bash/coreutils/git/nodejs-lts/npm/tar/gzip

Runtime home
  -> $HOME/SillyTavern
  -> $HOME/.stapk/logs
  -> $HOME/.stapk/backups
  -> $HOME/.stapk/state
```

架构边界：

- Android UI 不直接拼复杂命令，只调用固定白名单动作。
- 白名单动作落到 `$PREFIX/bin/stapk-*` 脚本，便于调试和复用。
- Termux 负责实际进程、环境变量、PATH、文件权限。
- SillyTavern 官方脚本负责 npm 依赖和 server 启动。
- 日志统一写入 `$HOME/.stapk/logs`，UI 只读取和展示日志。

## 6. 文件和目录规划

### 6.1 APK 工程目录

```text
stapk-termux/
  upstream/
    termux-app/                 # fork 或 submodule，存放 Termux App 源码
    termux-packages/            # fork 或 submodule，构建自定义 bootstrap
  payload/
    SillyTavern/                # 构建时生成，不直接手改
    payload-manifest.json
    SillyTavern.tar.gz
  docs/
    superpowers/specs/
  scripts/
    prepare-sillytavern-payload.sh
    build-bootstrap-aarch64.sh
    build-apk.ps1
```

### 6.2 手机运行时目录

```text
$PREFIX/bin/
  stapk-init
  stapk-start
  stapk-stop
  stapk-update
  stapk-open-url
  stapk-backup
  stapk-restore
  stapk-report

$HOME/
  SillyTavern/
    .git/
    start.sh
    server.js
    package.json
    node_modules/
    config.yaml
    data/

  .stapk/
    logs/
      init.log
      start.log
      update.log
      backup.log
    backups/
    state/
      initialized
      running.json
      last-error.json
      bundled-payload-manifest.json
      runtime-manifest.json
      app-upgrade.json
```

## 7. 核心流程

### 7.1 获取源码流程

开发机准备：

1. 安装 Git、JDK、Android Studio/Android SDK、Android NDK、Gradle 环境。
2. 准备 Linux 构建环境，推荐 WSL2 Ubuntu 或 GitHub Actions Linux runner。
3. 在 `C:\Users\31029\Documents\GitHub\stapk-termux` 下拉取 Termux App：

```bash
git clone https://github.com/termux/termux-app.git upstream/termux-app
cd upstream/termux-app
git checkout v0.118.3
```

4. 拉取 Termux Packages：

```bash
git clone https://github.com/termux/termux-packages.git upstream/termux-packages
```

5. 构建或准备 SillyTavern payload：

```bash
git clone --branch release --depth=1 https://github.com/SillyTavern/SillyTavern.git payload/SillyTavern
cd payload/SillyTavern
npm install --no-save --no-audit --no-fund --loglevel=error --no-progress --omit=dev --ignore-scripts
```

### 7.2 自定义 bootstrap 流程

目标是生成一个离线可用的 `aarch64` bootstrap。

做法：

1. 基于 Termux Packages 构建或复用官方 bootstrap 机制。
2. 将必需包加入 bootstrap：
   - `bash`
   - `coreutils`
   - `busybox`
   - `tar`
   - `gzip`
   - `git`
   - `nodejs-lts`
   - `npm`
   - `openssl`
   - `ca-certificates`
3. 生成 `bootstrap-aarch64.zip`。
4. 放入 Termux App 的 `app/src/main/cpp/`。
5. 调整 Termux App 的 bootstrap 校验逻辑或 checksum，使 Gradle 使用本地自定义 bootstrap。
6. 只保留 `arm64-v8a` 输出，避免多 ABI 包体积膨胀。

验收标准：

- 新安装 APK 后断网打开，`node --version`、`npm --version`、`git --version` 都可在 Termux 环境中执行。
- `$PREFIX`、`$HOME`、PATH 和 SSL 证书正常。
- `git ls-remote https://github.com/SillyTavern/SillyTavern` 在联网时可用。

### 7.3 SillyTavern payload 制作流程

payload 不是普通 zip 下载包，而是可后续 Git 更新的工作树。

步骤：

1. 在与目标环境兼容的 aarch64 Termux 环境中准备 `payload/SillyTavern`。
2. 使用 `release` 分支浅克隆，并保留 `.git`。
3. 执行与官方 `start.sh` 等价的生产依赖安装命令。
4. 记录 manifest。下面字段由 payload 构建脚本在打包时自动写入，示例中的值表示字段含义：

```json
{
  "sillytavern_repo": "https://github.com/SillyTavern/SillyTavern.git",
  "branch": "release",
  "commit": "git rev-parse HEAD 的输出",
  "sillytavern_version": "package.json 中的 version",
  "node_version": "node --version 的输出",
  "npm_version": "npm --version 的输出",
  "created_at": "ISO-8601 格式的打包时间"
}
```

5. 打包：

```bash
tar --numeric-owner --preserve-permissions -czf SillyTavern.tar.gz SillyTavern payload-manifest.json
```

6. 将 tarball 放入 APK assets。

验收标准：

- 断网首次启动后，payload 能完整解包。
- `cd ~/SillyTavern && bash start.sh` 可以启动。
- `.git/config` 指向官方 SillyTavern 仓库。
- 后续联网执行 `git pull --rebase --autostash` 可成功。

### 7.4 首次启动初始化流程

首次启动由控制面板触发初始化，不显示终端。

流程：

1. App 检查 Termux bootstrap 是否已安装。
2. 如果未安装，使用 Termux 原有 bootstrap 安装流程。
3. 检查 `$HOME/.stapk/state/initialized`。
4. 如果未初始化：
   - 创建 `$HOME/.stapk/logs`、`backups`、`state`。
   - 从 APK assets 拷贝 `SillyTavern.tar.gz` 到临时目录。
   - 解包到 `$HOME/SillyTavern`。
   - 写入 `bundled-payload-manifest.json` 和 `runtime-manifest.json`。
   - 设置脚本可执行权限。
   - 执行轻量校验：`node --version`、`npm --version`、`git --version`、`test -f ~/SillyTavern/start.sh`。
   - 写入 initialized 标记。
5. 如果已初始化，不自动解包 APK 内置 payload，也不覆盖 `$HOME/SillyTavern`。控制面板只刷新运行时版本信息。
6. 初始化成功后进入控制面板首页。

失败处理：

- 初始化任何一步失败都写入 `init.log`。
- UI 显示“初始化失败”，提供“重试初始化”和“导出诊断日志”。
- 如果 `$HOME/SillyTavern` 已部分存在，重试前先移动到 `$HOME/.stapk/backups/failed-init-时间戳`，避免覆盖用户数据。

### 7.5 APK 覆盖升级与版本管理

覆盖安装新 APK 时，Android 会保留 App 数据目录，Termux 的 `$PREFIX` 和 `$HOME` 也会保留。Termux bootstrap 只应在 `$PREFIX` 缺失或为空时安装；新 APK assets 中的 bootstrap 或 SillyTavern payload 不会自动替换已经初始化的运行环境。

第一版必须遵守这条规则：

> 已初始化环境中，APK 覆盖升级不得自动用 APK 内置 payload 覆盖用户当前的 `$HOME/SillyTavern`。

原因是用户可能已经通过控制面板的 Git 更新按钮把 SillyTavern 更新到比新 APK 内置 payload 更新的版本。例如用户运行时已经是 SillyTavern `1.19.0`，新 APK 内置 payload 只有 `1.18.2`，此时自动解包会造成版本回退和潜在数据损坏。

覆盖升级后执行的检查：

1. 读取 APK assets 中的 `payload-manifest.json`，复制到 `$HOME/.stapk/state/bundled-payload-manifest.json`，只作为“当前 APK 内置版本”记录。
2. 检查 `$HOME/SillyTavern/.git` 和 `package.json`，生成 `$HOME/.stapk/state/runtime-manifest.json`，记录“用户当前实际运行版本”。
3. 如果 `initialized` 存在，跳过 SillyTavern payload 解包。
4. 如果 APK 内置 payload 版本高于运行时版本，只提示“APK 内置版本较新”，不自动覆盖；建议用户使用 Git 更新按钮。
5. 如果运行时版本高于 APK 内置 payload，只提示“运行时已由用户更新”，不做回退。
6. 如果运行时目录损坏，进入修复页，由用户选择“修复依赖”“Git 恢复”“重新初始化”。

manifest 分工：

```text
bundled-payload-manifest.json
  记录当前 APK 内置的 SillyTavern commit、版本、Node/npm 版本、打包时间。

runtime-manifest.json
  记录 $HOME/SillyTavern 当前实际 commit、分支、远程仓库、package.json version、
  node/npm/git 当前版本、最后一次 Git 更新结果。

app-upgrade.json
  记录上一次运行的 stAPK versionCode/versionName、当前 APK 版本、是否发生覆盖升级、
  覆盖升级后执行了哪些检查。
```

bootstrap 和 `$PREFIX` 的规则：

- 覆盖安装 APK 不自动重装 `$PREFIX`。
- 新 APK 内置了更新的 Node/Git/npm 时，不代表用户已有 `$PREFIX` 会自动升级。
- 第一版只做版本检测和提示，不做自动 apt/dpkg 迁移。
- 如必须升级 Node/Git/npm，提供“更新运行环境”高级按钮，执行前备份 `$PREFIX` 包列表和 stAPK 状态，并显示风险说明。
- 若 `$PREFIX` 缺失、为空或基础命令不可用，则进入 bootstrap 修复流程。

重新初始化规则：

- “重新初始化”是高级修复动作，不出现在普通首页。
- 执行前必须二次确认，明确说明会移动当前 `$HOME/SillyTavern`。
- 不直接删除旧目录，而是移动到 `$HOME/.stapk/backups/reinit-时间戳/SillyTavern`。
- 然后从 APK 内置 payload 重新解包。
- 重新初始化完成后，保留旧备份路径并允许用户手动恢复 `data/` 和 `config.yaml`。

### 7.6 启动流程

按钮：启动酒馆

执行：

```bash
cd "$HOME/SillyTavern" && bash start.sh
```

UI 状态：

- 点击后进入“启动中”。
- 输出写入 `$HOME/.stapk/logs/start.log`。
- 检测本地端口或进程状态，成功后显示“运行中”。
- 成功后“打开酒馆”按钮可用。

注意：

- 因为官方 `start.sh` 会执行 npm install，离线时依赖已存在应能继续启动。
- 如果联网更新后依赖变化，`start.sh` 会尝试按官方方式安装依赖。
- 不改官方 start.sh，避免跟上游行为分叉。

### 7.7 停止流程

按钮：停止酒馆

优先方式：

- 由 Android 层记录启动时创建的 Termux session/process。
- 停止时关闭对应 session 或发送终止信号。

兜底方式：

```bash
pkill -f "node .*server.js"
```

停止后：

- UI 显示“已停止”。
- 保留最后日志。
- 不删除任何 SillyTavern 数据。

### 7.8 打开网页流程

按钮：打开酒馆

流程：

1. 检查服务是否运行。
2. 读取配置端口，缺省使用 `8000`。
3. 打开系统浏览器或内置 WebView：

```text
http://127.0.0.1:8000
```

第一版建议优先打开系统浏览器，避免 WebView 兼容问题。后续再评估内置 WebView。

### 7.9 Git 更新流程

按钮：更新酒馆

默认执行：

```bash
cd "$HOME/SillyTavern" && git pull --rebase --autostash
```

流程：

1. 检查网络。
2. 如果酒馆正在运行，提示先停止或自动停止。
3. 更新前记录当前 commit。
4. 备份 `config.yaml` 和 `data/`。
5. 执行 `git pull --rebase --autostash`。
6. 更新成功后显示新 commit。
7. 用户点击“启动”时继续走官方 `bash start.sh`，由官方脚本处理依赖。

失败处理：

- `git pull` 失败时显示错误摘要。
- 提供“查看日志”。
- 提供“回滚到更新前 commit”按钮：

```bash
cd "$HOME/SillyTavern" && git reset --hard "$PREVIOUS_COMMIT"
```

回滚是破坏性动作，必须在 UI 二次确认。

### 7.10 备份与恢复流程

按钮：备份数据

第一版备份内容：

- `$HOME/SillyTavern/config.yaml`
- `$HOME/SillyTavern/data/`
- 用户安装的扩展目录，按 SillyTavern 实际目录确认后纳入

输出：

```text
$HOME/.stapk/backups/sillytavern-backup-YYYYMMDD-HHMMSS.tar.gz
```

恢复流程：

1. 用户选择备份。
2. 如果酒馆运行中，先停止。
3. 当前数据先备份为 `before-restore-时间戳`。
4. 解包覆盖。
5. 显示恢复结果。

### 7.11 日志和诊断流程

日志页面显示：

- 初始化日志
- 启动日志
- 更新日志
- 最近一次错误
- Node/npm/git 版本
- SillyTavern 当前 commit
- Android 版本、ABI、App 版本

导出诊断包：

```text
$HOME/.stapk/reports/stapk-report-YYYYMMDD-HHMMSS.tar.gz
```

诊断包不应默认包含聊天记录或 API keys。包含敏感信息前需要提示用户。

## 8. UI 设计

### 8.1 首页

首页只放最常用状态和按钮：

- 状态：未初始化 / 已停止 / 启动中 / 运行中 / 更新中 / 错误
- 当前版本：SillyTavern version + Git commit
- 主按钮：启动 / 停止
- 次按钮：打开酒馆
- 操作按钮：更新、日志、备份

### 8.2 更新页

显示：

- 当前分支
- 当前 commit
- 远程仓库
- 上次更新时间
- 更新按钮
- 更新日志
- 回滚按钮，仅在更新失败或用户展开高级操作时显示

### 8.3 日志页

显示最近日志，支持复制和导出。普通用户不看到完整 shell，只看到命令名称、状态、摘要和日志文本。

### 8.4 高级/诊断页

隐藏入口，例如设置页连续点击版本号开启。

包含：

- 打开 Termux 终端
- 重新执行初始化检查
- 导出诊断包
- 查看 `$HOME` 路径
- 重置控制面板状态

不在第一版提供任意命令输入框。

## 9. 构建流程

### 9.1 开发构建

1. 拉取 Termux App fork。
2. 应用 stAPK 改动：新增控制面板 Activity、白名单命令 runner、assets payload。
3. 使用自定义 aarch64 bootstrap。
4. 使用 debug 签名构建：

```bash
./gradlew :app:assembleDebug
```

5. 安装到测试手机：

```bash
adb install -r app/build/outputs/apk/debug/*.apk
```

### 9.2 发布构建

1. 使用自有 release keystore，不使用 Termux GitHub 测试 key。
2. 只构建 `arm64-v8a`。
3. 生成 APK 和 sha256sum。
4. 在 GitHub Release 发布：
   - APK
   - sha256sum
   - 源码压缩包或源码仓库链接
   - 许可证说明
   - 内置版本说明：Termux App tag、SillyTavern commit、Node/npm/git 版本

## 10. 测试计划

### 10.1 离线首次启动

测试条件：手机断网，清空 App 数据后首次打开。

通过标准：

- App 能进入控制面板。
- 初始化能完成。
- 启动按钮可启动 SillyTavern。
- 打开网页能访问本地 SillyTavern。
- 不需要 `git clone`。
- 不需要联网 `npm install`。

### 10.2 联网更新

测试条件：手机联网，SillyTavern 不运行。

通过标准：

- 更新按钮执行 Git 拉取。
- 更新日志可读。
- 更新后可启动。
- 如果没有更新，UI 显示已是最新。

### 10.3 失败恢复

测试场景：

- 初始化过程中强制退出。
- 更新过程中断网。
- `node_modules` 被删除。
- `config.yaml` 损坏。
- 端口被占用。

通过标准：

- UI 不崩溃。
- 日志可导出。
- 能重试初始化。
- 能回滚更新。
- 用户数据不被静默删除。

### 10.4 Android 兼容

测试设备：

- Android 10 arm64 手机
- Android 12/13 arm64 手机
- Android 14/15 arm64 手机
- 至少一台国产 ROM 手机，用于验证后台保活和电池优化提示

重点：

- 后台进程是否被系统杀掉。
- 通知权限和前台服务行为。
- 浏览器打开本地地址是否稳定。
- App 数据清理后的重新初始化是否可靠。

## 11. 主要风险和处理策略

### 11.1 APK 体积过大

原因：Node、Git、npm、SillyTavern、node_modules 都打包进 APK。

策略：

- 只做 `arm64-v8a`。
- SillyTavern payload 用压缩 tarball。
- 去除开发依赖和不必要缓存。
- 保留 `.git` 但使用浅克隆。
- 如果 `tar.gz` 体积不可接受，切换 `tar.zst`。

### 11.2 改包名导致 bootstrap 路径问题

Termux 路径绑定 package name，例如 `/data/data/com.termux/files/usr`。正式分发如使用自有包名，必须同步处理 bootstrap 和 Termux constants。

策略：

- 原型阶段可以先沿用 `com.termux` 验证功能。
- 正式分发前切换自有包名，并重建 bootstrap。
- 不允许和官方 Termux 插件混装，文档中明确说明。

### 11.3 官方 SillyTavern 更新破坏兼容

原因：上游可能提高 Node 要求、改依赖、改配置结构。

策略：

- 默认使用 release 分支，不使用 staging。
- 更新前备份数据和记录 commit。
- 更新失败可回滚到上一个 commit。
- 控制面板展示明确错误和日志。

### 11.4 Android 后台限制

Termux 官方也提示 Android 12+ 可能杀掉大量或高 CPU 进程。

策略：

- 启动时使用前台服务通知。
- 提示用户关闭电池优化。
- 日志中记录 signal 9 或进程异常退出。
- 第一版不承诺长时间后台不被系统杀掉。

### 11.5 覆盖升级造成运行时回退

原因：APK 内置 payload 可能落后于用户通过 Git 按钮更新后的运行时版本。

策略：

- 已初始化环境永不自动解包 APK 内置 SillyTavern payload。
- 分开记录 APK 内置版本和用户运行时版本。
- APK 覆盖升级只刷新 manifest 和执行兼容性检查。
- 需要回退或重新初始化时必须由用户在高级修复页显式触发。

### 11.6 许可证合规

Termux App 是 GPLv3 only，SillyTavern 是 AGPL-3.0，Git 是 GPL-2.0，Node 是 MIT，npm 及 node_modules 包含多种许可证。

策略：

- APK 内提供“开源许可证”页面。
- GitHub Release 同步提供修改版源码。
- 记录内置三方组件版本和许可证。
- 不移除上游版权声明。

## 12. 阶段划分

### 阶段 0：方案确认

输出：

- 本文档确认。
- 项目目录创建。
- 明确包名策略：原型是否先用 `com.termux`，正式版是否改自有包名。
- 明确 UI 首版按钮列表。

### 阶段 1：原型验证

目标：

- Fork Termux App。
- 新增控制面板 Launcher Activity。
- 保留终端高级入口。
- 按钮能调用固定脚本。
- 使用现有 Termux 环境手动验证启动 SillyTavern。

验收：

- App 启动后先进控制面板。
- 点击按钮能在 Termux session 中执行 `bash start.sh`。
- 日志能在 UI 中查看。

### 阶段 2：离线 bootstrap

目标：

- 构建 aarch64 自定义 bootstrap。
- 内置 Node/npm/Git。
- 新安装后断网能执行版本检查。

验收：

- 断网首次打开不需要 `pkg install`。
- `node`、`npm`、`git` 可用。

### 阶段 3：SillyTavern payload

目标：

- 制作含 `.git` 和 `node_modules` 的 SillyTavern payload。
- 首次启动自动解包。
- 断网可启动。

验收：

- 清数据后重新初始化成功。
- 离线可启动 SillyTavern。
- 联网后 `git pull` 可用。

### 阶段 4：更新、备份、恢复

目标：

- 更新按钮封装 Git 更新。
- 更新前备份数据。
- 更新失败可回滚。
- 用户可导出日志。

验收：

- 更新成功路径通过。
- 断网失败路径可恢复。
- 备份和恢复不丢数据。

### 阶段 5：打包发布

目标：

- 切换 release 签名。
- 生成 arm64-v8a APK。
- 输出 sha256sum、许可证、版本清单。
- 发布到 GitHub Release。

验收：

- 干净手机可安装。
- 覆盖升级保留数据。
- 卸载重装行为符合预期。

## 13. 需要优先确认的决策

1. 原型阶段是否允许暂时沿用 `com.termux` 包名，以减少第一轮改动。
2. 正式分发是否使用自有包名，例如 `com.stapk.termux`。
3. 第一版是否只打开系统浏览器，不内置 WebView。
4. 更新按钮是否只执行 `git pull --rebase --autostash`，依赖处理交给下一次 `bash start.sh`。
5. 是否接受 APK 体积明显大于普通 Termux，因为内置了 Node、Git、npm、SillyTavern 和 node_modules。

## 14. 推荐决策

推荐第一版采用以下决策：

- 原型先沿用 `com.termux`，快速验证控制面板、脚本封装和 payload。
- 正式版改自有包名，避免和官方 Termux 冲突。
- 第一版使用系统浏览器打开 `127.0.0.1:8000`。
- 更新按钮只做 Git 更新，启动按钮继续执行官方 `start.sh`。
- 接受大包体积，优先换取离线即用。

这个路线改动集中、回退容易，也最贴近当前目标：不是重新发明 Termux，而是把 Termux 封装成普通用户能安全使用的 SillyTavern 运行器。
