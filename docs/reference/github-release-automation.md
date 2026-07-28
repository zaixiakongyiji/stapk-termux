# GitHub 自动构建与发版

## 概览

stAPK Mobile 现在支持由 GitHub Actions 接管构建和发版。

当前自动化分成两条流水线：

- `CI`：在 `master` 上的 `push / pull_request` 自动执行转换验证、关键单测和 `assembleDebug`
- `Release`：在推送 `v*` 标签时自动执行转换验证、构建通用 release APK，并自动创建/更新 GitHub Release

> `v0.1.x` 是旧 Termux fork 阶段，`v0.2.0` 起主线是 `mobile/` 原生 Android 工程。后续推荐全部改为 tag 驱动的自动发版。

---

## 工作流位置

GitHub 只会识别仓库根目录下的工作流：

- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`

---

## 自动发版触发规则

`release.yml` 的触发条件是：

```yaml
on:
  push:
    tags:
      - "v*"
```

也就是说，只要推送类似下面的标签：

- `v0.1.1`
- `v0.2.0`
- `v1.0.0`

GitHub 就会自动：

1. 检出当前 tag 对应代码
2. 安装 JDK / Android SDK / Node.js 20
3. 重写 CI 环境的 `local.properties`
4. 运行统一的 `build:no-node-apk` 严格门禁，将 SillyTavern 转换产物写入 Android assets
5. 执行 Android 单测并构建签名的通用 release APK
6. 注入 tag 对应的 Android `versionName` 和单调递增 `versionCode`
7. 生成带 tag 的 APK 与 SHA-256 校验文件
8. 从 `CHANGELOG.md` 中提取当前 tag 对应版本段落作为 Release 正文
9. 创建或更新对应的 GitHub Release；带 `-` 的 tag 自动标记为 prerelease
10. 上传以下发布资产：
   - `stapk-mobile_<tag>.apk`
   - `stapk-mobile_<tag>.apk.sha256`
   - `api-contract.json`
   - `stapk-capabilities.json`
   - `stapk-web-manifest.json`
   - `transform-report.json`

GitHub Actions artifact 同时保留 APK 与转换证据，Release APK 直接来自本次工作流生成的 assets，不使用历史 payload。

---

## 大文件与转换产物约定

当前 `mobile/` 主线不再打包 Node.js runtime 或 SillyTavern payload。Web assets 由 no-node 转换器在构建期生成，CI/Release 不再下载历史 Termux LFS 资产。

---

## 版本号规则

目标规则：

1. 把 Git tag 去掉前缀 `v` 后写入 Android `versionName`
   - 例如 `v0.1.1` 会变成 APK 内部版本号 `0.1.1`
2. 用完整 tag 参与 APK 文件命名
   - 例如 `stapk-mobile_v0.3.0.apk`

`release.yml` 会导出 `STAPK_VERSION_NAME` 与 `STAPK_VERSION_CODE`，`mobile/app/build.gradle.kts` 直接读取这两个环境变量。`v0.3.0-beta.1` 的 APK 版本名为 `0.3.0-beta.1`，正式版使用更高的同版本 `versionCode`。

---

## Release 正文来源

GitHub Release 的正文不再依赖自动生成说明，而是直接读取 `CHANGELOG.md` 中与 tag 同名的版本段落。

例如当你推送：

```bash
git tag v0.3.0-beta.1
git push origin v0.3.0-beta.1
```

工作流会从 `CHANGELOG.md` 中提取：

```md
## v0.1.1 - ...
...
```

直到下一个 `## v...` 标题之前的全部内容，并将其作为 GitHub Release 页面正文。

因此发版前必须先更新 `CHANGELOG.md`，并保证标题格式与 tag 一致：

```md
## v0.1.1 - 版本说明 (2026-06-08)
```

如果工作流找不到对应标题，会直接失败，避免发布出一个没有版本说明的 Release。

---

## 签名策略

### 当前默认行为

CI 使用 Android Gradle Plugin 默认的 debug 签名，因此 `master` 上的自动测试和 `assembleDebug` 不依赖仓库内现成 keystore。

但 `release` 构建不会使用仓库里的本地签名文件，因为这些文件没有纳入版本控制，GitHub Actions 运行环境也拿不到它们。

### 推荐做法

`release.yml` 现在要求在仓库的 `Settings -> Secrets and variables -> Actions` 中显式配置以下 Secrets：

- `TERMUX_RELEASE_KEYSTORE_BASE64`
- `TERMUX_RELEASE_STORE_PASSWORD`
- `TERMUX_RELEASE_KEY_ALIAS`
- `TERMUX_RELEASE_KEY_PASSWORD`

如果缺少其中任意一项，Release 工作流会在签名配置阶段直接失败，并给出明确错误信息。

---

## 标准发版步骤

### 1. 更新代码与文档

发版前至少检查这些内容：

- 代码已经合并到 `master`
- `README.md` / 相关文档已同步
- `CHANGELOG.md` 已新增与目标 tag 同名的版本段落

### 2. 推送 `master`

```bash
git push origin master
```

### 3. 创建并推送版本标签

```bash
git tag v0.1.1
git push origin v0.1.1
```

### 4. 等待 GitHub Actions 完成

进入仓库的 `Actions` 页面，查看 `Release` 工作流是否成功。

### 5. 检查 GitHub Release 页面

确认 Release 下是否已经自动出现：

- `stapk-mobile_v0.3.0-beta.1.apk`
- `stapk-mobile_v0.3.0-beta.1.apk.sha256`

---

## CI 流水线说明

`ci.yml` 在 `master` 的提交和 PR 上会自动执行：

- `npm run build:no-node-apk -- --variant debug --ref release`
- no-node 转换测试与能力合同校验
- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- 上传 Debug APK、SHA-256 与四个转换证据文件

同时会上传一个调试用 artifact：

- `stapk-debug-app`

这可以用于快速验证 PR 没把主构建链路打断。

---

## 注意事项

### 1. GitHub runner 的 `local.properties`

仓库里当前的 `local.properties` 指向本地 Windows SDK 路径。GitHub Actions 运行在 Linux 上，必须在工作流里重写 `sdk.dir`，否则构建会失败。

### 2. 自动发版发布通用 APK

0.3.0 no-node 主线不包含 ABI 相关的 native runtime，APK 没有 `native-code` 限制，因此不再使用 `arm64-v8a` 后缀误导用户。
