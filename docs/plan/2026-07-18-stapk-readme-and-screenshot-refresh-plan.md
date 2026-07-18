# stAPK README 与截图刷新实施计划

> **执行要求：** 按本计划逐项执行并在每个验证边界保留实际命令结果；Git 提交与发布由用户另行决定。

**目标：** 修复 Android 深色页面的 system bar 可读性，并用 3 张真实模拟器截图和准确能力矩阵刷新 README。

**架构：** Android Manifest 统一引用专用深色主题；README 只总结已由设计、测试和设备记录确认的能力。截图从 clean-installed Pixel 8 / API 35 App 直接采集，不做合成或界面美化。

**技术栈：** Android XML、Node.js test runner、Gradle、ADB、Markdown。

## 全局约束

- 不在截图中展示 API key 或完整私密数据。
- 不把外部可选能力描述为 APK 内置能力。
- 不宣称 Android 7 / Android 10 已完成设备验收。
- 不修改 SillyTavern 上游业务代码或扩大 0.3.0 功能范围。

---

### Task 1: 修复 Android system bar

**文件：**
- 修改：`mobile/app/src/main/AndroidManifest.xml`
- 修改：`mobile/app/src/main/res/values/themes.xml`
- 测试：`test/no-node/android-shell-theme.test.mjs`

- [x] 添加资源契约测试并运行，确认因 Manifest 使用平台 Light 主题而失败。
- [x] 让 Manifest 使用 `@style/Theme.StAPKMobile`，显式设置深色 system bar。
- [x] 运行 `node --test test/no-node/android-shell-theme.test.mjs`，期望 PASS。
- [x] 运行 `mobile/gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug`，期望 `BUILD SUCCESSFUL`。
- [x] 卸载旧 App、安装新 Debug APK并在 Pixel 8 截图确认状态栏可读。

### Task 2: 准备截图数据并采集

**文件：**
- 创建：`docs/images/screenshot-home.png`
- 创建：`docs/images/screenshot-world-info.png`
- 创建：`docs/images/screenshot-extensions.png`
- 删除：`docs/images/screenshot-log.png`
- 删除：`docs/images/screenshot-ui.png`

- [x] 将 `test/测试文件/cc7481f898a8e631.png`、`写实世界V7.82.json` 和 `Izumi 0707.json` 推送到模拟器 Download。
- [x] 在 App 中导入角色卡、World Info 和预设；安装两个 client-only 测试扩展。
- [x] 从主界面、World Info 编辑器和扩展设置页分别执行 ADB `screencap`。
- [x] 检查三张截图无白色 system bar、乱码、密钥或导入失败提示。

### Task 3: 刷新 README

**文件：**
- 修改：`README.md`

- [x] 增加 `v0.3.0-beta.1` 直接下载入口与 beta 提示。
- [x] 使用三张新图替换 0.2.x 截图段落。
- [x] 写入“已支持 / 有限支持 / 暂不支持”能力矩阵。
- [x] 把 APK 架构改为通用 APK，修正工作原理中的 Native adapter 命名和 tag 示例。
- [x] 检查 README 中的本地链接和图片路径均存在。

### Task 4: 最终验证

**文件：**
- 验证：上述全部文件

- [x] 运行 `npm run test:no-node`，期望全部测试通过。
- [x] 运行 `git diff --check`，期望无错误。
- [x] 检查 `git diff --stat` 与 `git status --short`，确认没有 APK、密钥或临时文件进入版本控制。
- [x] 停止 Gradle daemon；保持 Pixel 8 运行，便于用户继续查看截图对应页面。
