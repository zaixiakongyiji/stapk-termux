# GitHub 自动构建与发版

## 概览

stAPK Termux 现在支持由 GitHub Actions 接管构建和发版。

当前自动化分成两条流水线：

- `CI`：在 `master` 上的 `push / pull_request` 自动执行关键单测和 `assembleDebug`
- `Release`：在推送 `v*` 标签时自动构建 `arm64-v8a release APK`，并自动创建/更新 GitHub Release

> `v0.1.0` 是此前手动上传的版本。从后续版本开始，推荐全部改为 tag 驱动的自动发版。

---

## 工作流位置

GitHub 只会识别仓库根目录下的工作流：

- `.github/workflows/ci.yml`
- `.github/workflows/release.yml`

`upstream/termux-app/.github/workflows/` 中的文件仅作为上游参考，不会直接驱动本仓库发版。

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
2. 安装 JDK / Android SDK / NDK
3. 重写 CI 环境的 `local.properties`
4. 构建 `arm64-v8a release APK`
5. 生成 SHA-256 校验文件
6. 从 `CHANGELOG.md` 中提取当前 tag 对应版本段落作为 Release 正文
7. 创建或更新对应的 GitHub Release
8. 上传以下发布资产：
   - `stapk-termux_<tag>_arm64-v8a.apk`
   - `stapk-termux_<tag>_arm64-v8a.apk.sha256`
   - `output-metadata.json`

---

## Bootstrap 大文件约定

项目使用的自定义 bootstrap 不是官方原版，其中：

- `upstream/termux-app/app/src/main/cpp/bootstrap-aarch64.zip`
- `upstream/termux-app/app/src/main/cpp/bootstrap-x86_64.zip`

由仓库通过 Git LFS 跟踪，而不是在 CI 中临时从官方 release 下载。

原因是：

- `bootstrap-aarch64.zip` 体积超过普通 Git 单文件限制
- 自定义 bootstrap 与官方 bootstrap 的内容和校验值并不一致
- GitHub Actions 如果不启用 `lfs: true`，检出的只会是 pointer 文件，构建会失败

因此 `ci.yml` 和 `release.yml` 的 `actions/checkout` 都必须保持：

```yaml
with:
  lfs: true
```

---

## 版本号规则

工作流会自动做两件事：

1. 把 Git tag 去掉前缀 `v` 后写入 Android `versionName`
   - 例如 `v0.1.1` 会变成 APK 内部版本号 `0.1.1`
2. 用完整 tag 参与 APK 文件命名
   - 例如 `stapk-termux_v0.1.1_arm64-v8a.apk`

控制面板底部的 `stAPK v...` 版本号也会读取当前构建的 `versionName`，因此会自动和 tag 保持一致。

---

## Release 正文来源

GitHub Release 的正文不再依赖自动生成说明，而是直接读取 `CHANGELOG.md` 中与 tag 同名的版本段落。

例如当你推送：

```bash
git tag v0.1.1
git push origin v0.1.1
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

当前仓库可以直接使用 `upstream/termux-app/app/release.keystore` 构建 release 包，因此 GitHub Actions 在不开启额外 Secrets 的情况下也能完成发布。

### 推荐做法

工作流同时支持用 GitHub Secrets 覆盖签名材料。后续如果你希望把签名信息从仓库中迁出，可以在仓库的 `Settings -> Secrets and variables -> Actions` 中配置：

- `TERMUX_RELEASE_KEYSTORE_BASE64`
- `TERMUX_RELEASE_STORE_PASSWORD`
- `TERMUX_RELEASE_KEY_ALIAS`
- `TERMUX_RELEASE_KEY_PASSWORD`

工作流会优先使用这些 Secrets；如果未配置，则回退到当前仓库内的 `release.keystore`。

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

- `stapk-termux_v0.1.1_arm64-v8a.apk`
- `stapk-termux_v0.1.1_arm64-v8a.apk.sha256`

---

## CI 流水线说明

`ci.yml` 在 `master` 的提交和 PR 上会自动执行：

- `StapkScriptAssetsTest`
- `TermuxShellUtilsTest`
- `StapkBootstrapShebangFixerTest`
- `StapkRuntimeControllerTest`
- `StapkStatusSnapshotTest`
- `:app:assembleDebug`

同时会上传一个调试用 artifact：

- `stapk-debug-arm64-v8a`

这可以用于快速验证 PR 没把主构建链路打断。

---

## 注意事项

### 1. GitHub runner 的 `local.properties`

仓库里当前的 `local.properties` 指向本地 Windows SDK 路径。GitHub Actions 运行在 Linux 上，必须在工作流里重写 `sdk.dir`，否则构建会失败。

### 2. 自动发版当前只发布 `arm64-v8a`

这是刻意设计。stAPK 的目标用户主要是手机设备，正式发版没有必要默认附带 `universal` 或 `x86_64` 包。

### 3. `v0.1.0` 是手动上传历史版本

历史版本不受这套自动工作流回填。后续从 `v0.1.1` 或你选择的下一个 tag 开始，就可以完全改成 GitHub 自动发版。
