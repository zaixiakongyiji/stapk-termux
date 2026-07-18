# stAPK README 与截图刷新设计

## 背景

`README.md` 已描述 0.3.0 no-node 架构，但仍引用 0.2.x 控制面板截图，并把正式 APK 写成仅支持 `arm64-v8a`。Pixel 8 / Android 15 截图还暴露了状态栏浅色背景与浅色图标叠加的问题。

## 目标

- README 面向最终用户优先说明 0.3.0 beta 的定位、下载方式和运行边界。
- 用“已支持 / 有限支持 / 暂不支持”矩阵明确当前能力，避免把条件兼容写成完整支持。
- 使用 Pixel 8 模拟器中的真实 App 页面替换旧截图，展示主界面、中文 World Info、扩展与 Regex/Summarize。
- 修复深色 WebView 周围的 Android system bar，使时间、信号、电量和手势导航在截图与日常使用中清晰可见。

## README 结构

1. 项目定位与 `v0.3.0-beta.1` 下载入口。
2. 三张真实 Android 截图。
3. 当前能力矩阵。
4. no-node 工作原理、安装要求、构建和自动发版说明。
5. 测试版边界、许可证与致谢。

## 能力边界

### 已支持

- OpenAI-compatible 主 API 与流式/非流式聊天生成。
- 单用户角色、Persona、聊天、群组、群聊和 recent chats。
- PNG/JSON 角色卡、内嵌 World Info、独立 World Info、预设、主题、Regex 和 Main API Summarize。
- 背景、附件、媒体、Tokenizer、Quick Reply 以及应用内 SAF 数据导入导出。
- client-only 第三方扩展的安装、启用、禁用、版本检查、更新、删除和重新安装。
- 诊断导出和敏感字段脱敏。

### 有限支持

- 第三方扩展只在其依赖的 SillyTavern API 已由 Native adapter 提供时可用；不承诺任意扩展兼容。
- 远程 embedding、图片、TTS、STT、字幕和翻译不内置模型，当前仅保留外部能力边界。
- Android 15 / API 35 已完成设备验收；Android 7 / API 24 与 Android 10 / API 29 尚待真机验证。

### 暂不支持

- Node.js、npm、Python、Shell server extension 和任意服务端插件。
- 本地 LLM、向量模型及其他重型本地模型。
- multiuser、远程访问和非 OpenAI-compatible provider。
- 0.2.x 自动数据迁移、完整应用备份恢复和 Data Maid。

## 截图规范

- 来源固定为 Pixel 8 / Android 15 模拟器中的当前 Debug APK，不使用旧版控制面板或外部浏览器画面。
- 截图保留 Android 状态栏和导航栏，证明应用运行在真实 Android shell 中。
- 不显示 API key、完整私密 prompt、诊断日志或其他敏感信息。
- 三张图片分别命名为 `screenshot-home.png`、`screenshot-world-info.png` 和 `screenshot-extensions.png`。

## System Bar 修复

应用使用专用 `Theme.StAPKMobile` 深色主题，显式设置 `statusBarColor=#101010`、`navigationBarColor=#000000`，并禁用 light status/navigation bar appearance。Manifest 不再直接引用平台 Light 主题。

## 验收

- 资源契约测试覆盖 Manifest 与 system bar 主题属性。
- Pixel 8 clean install 后，状态栏背景为深色，时间、信号和电量清晰可见。
- README 中不存在 0.2.x 控制面板截图、仅 `arm64-v8a` 或旧发版标签示例。
- 三张图片可由 Markdown 正常引用，文件存在且来自当前模拟器。
