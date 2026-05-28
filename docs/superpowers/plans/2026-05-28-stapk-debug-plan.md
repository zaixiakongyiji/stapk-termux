# stAPK Debug 执行记录 Implementation Plan

> **For agentic workers:** 这是一个多步骤实现任务。严格按步骤执行，每一步都可以独立验证。不要跳步。

## 目标
在 stAPK 控制面板首页新增 Debug 开关与“最近一次执行记录”面板，记录所有脚本执行的关键信息，并追加写入 `~/.stapk/logs/debug.log`。开关不持久化。

## 作用范围与文件结构
将新增调试记录模型与写入逻辑集中在 `StapkControlActivity` 及一个小型数据类（可选放在 `com.termux.app.stapk` 包）。

预计修改/新增文件：
- `upstream/termux-app/app/src/main/java/com/termux/app/StapkControlActivity.java`
  - 记录脚本执行的调试信息
  - 控制调试面板显示与复制
  - 追加写入 debug.log
- `upstream/termux-app/app/src/main/res/layout/activity_stapk_control.xml`
  - 添加 Debug 开关与调试面板 UI
- `upstream/termux-app/app/src/main/res/values/stapk_strings.xml`
  - 新增 Debug 相关文案
- （可选）`upstream/termux-app/app/src/main/java/com/termux/app/stapk/DebugRecord.java`
  - 数据模型

## 测试与验证范围
- 手动验证：
  - 开关关闭时不显示调试面板
  - 开关开启后显示最近一次执行记录（字段齐全）
  - 执行任一脚本后 `~/.stapk/logs/debug.log` 追加记录
  - 脚本不可执行时仍生成记录且显示失败原因

## 实施步骤（细粒度）

### 1. UI 添加
1.1 在 `activity_stapk_control.xml` 中新增 Debug 开关（放在状态卡片下方或主按钮上方）。
1.2 新增调试面板卡片：包含标题、字段文本区域、复制按钮。
1.3 默认调试面板隐藏（`visibility="gone"`）。

### 2. 文案补齐
2.1 在 `stapk_strings.xml` 新增 Debug 开关标题、面板标题、字段标签、复制提示等文案。

### 3. DebugRecord 模型
3.1 新增 `DebugRecord` 数据类（若决定放新包，先建包 `com.termux.app.stapk`）。
3.2 包含：脚本名、命令行、开始/结束/耗时、退出码、stdout/stderr tail、日志路径、脚本可执行状态、bootstrap 状态。
3.3 提供 `toDisplayString()` 用于 UI 展示，`toLogString()` 用于 debug.log 落盘。

### 4. 执行采集与落盘
4.1 在 `StapkControlActivity.executeScriptSync` 中注入采集逻辑：
  - 执行前：记录脚本可执行性、命令行、开始时间、bootstrap 状态。
  - 执行后：记录退出码、stdout/stderr tail、结束时间/耗时。
4.2 将 debug 记录写入 `~/.stapk/logs/debug.log`（确保目录存在）。
4.3 对不可执行脚本：不启动进程，也要生成 debug 记录并写入。

### 5. UI 绑定与交互
5.1 `StapkControlActivity` 增加 Debug 开关监听，控制调试面板显示。
5.2 在脚本执行结束时更新 UI 的“最近一次记录”。
5.3 复制按钮复制当前调试记录文本。

### 6. 验证
6.1 手动运行：点击启动/停止/初始化，确认 debug 面板更新。
6.2 确认 `~/.stapk/logs/debug.log` 写入内容与 UI 一致。
6.3 模拟脚本不可执行（临时改脚本权限）验证失败记录路径。

## 风险点与规避
- stdout/stderr 输出过大：仅保留末尾 N 行（建议 200 行）。
- 目录不存在：写入前 `mkdir -p ~/.stapk/logs`。
- UI 卡顿：所有采集/写入保持在后台线程。
