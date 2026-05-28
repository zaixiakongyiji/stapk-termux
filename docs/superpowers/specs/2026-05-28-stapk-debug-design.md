# stAPK 调试开关与执行记录设计（方案 B）

日期：2026-05-28
状态：待实现
范围：stAPK 控制面板首页 Debug 开关 + 执行记录落盘

## 目标
- 首页提供调试开关，打开后显示“最近一次执行记录”，用于确认是否执行了启动/停止/初始化/更新等脚本，以及执行结果。
- 记录写入 `~/.stapk/logs/debug.log` 便于排障，但开关本身不持久化。

## 非目标
- 不做跨应用重启后自动回填 UI（仅显示本次运行内“最近一次”）。
- 不做复杂日志检索/过滤/分页。

## 现状与约束
- 启动/停止等操作通过 `StapkControlActivity.executeScriptSync/executeScriptInBackground` 统一执行脚本。
- 日志页读取 `~/.stapk/logs/*.log`，不存在则显示“暂无日志”。
- `stapk-init` 才会创建 `~/.stapk/logs` 目录；`stapk-start` 在写日志前未显式创建目录。

## 设计概述
1) 在首页添加“调试开关”。
2) 开关开启后显示“最近一次执行记录”卡片（完整字段见下）。
3) 每次脚本执行前后追加写入 `~/.stapk/logs/debug.log`。
4) 若脚本不可执行或未部署，也写入失败记录，明确原因。

## UI/UX
### 入口
- 控制面板首页（状态卡片下方或主按钮区上方）。

### 调试面板内容（开关打开时展示）
- 脚本名
- 完整命令行（含参数）
- 开始时间 / 结束时间 / 耗时
- 退出码
- stdout 末尾 N 行
- stderr 末尾 N 行
- 对应日志文件路径（例如 `~/.stapk/logs/start.log`）
- 脚本部署状态（是否存在/可执行）
- bootstrap 状态（已完成/未完成/失败）

### 交互
- “复制调试记录”按钮：复制当前“最近一次执行记录”到剪贴板。
- 开关关闭：隐藏调试面板。

## 数据模型
### 运行时内存态
`DebugRecord`（仅保留最近一次）：
- `scriptName`
- `commandLine`
- `startTimeMs`
- `endTimeMs`
- `durationMs`
- `exitCode`（若不可执行则用特定错误码，如 `-127`）
- `stdoutTail`
- `stderrTail`
- `logFilePath`
- `scriptExecutable`（bool）
- `bootstrapStatus`（enum：unknown/ready/not_ready/error）

### 落盘格式
- 文件：`~/.stapk/logs/debug.log`
- 格式：人类可读文本（便于直接打开查看），每次执行追加一条分隔块。

## 采集点
- 统一在 `executeScriptSync(...)` 中采集：
  - 执行前：脚本存在/可执行、命令行、开始时间、bootstrap 状态。
  - 执行后：退出码、stdout/stderr 末尾 N 行、结束时间/耗时。
- 若脚本不存在/不可执行：不执行进程，但仍生成 Debug 记录并写入 debug.log。

## 采集范围与限制
- stdout/stderr 只保留末尾 N 行（建议 200 行），避免 UI 卡顿与内存膨胀。
- debug.log 只追加，不做清理（后续可考虑轮转，但不在本次范围）。

## 失败与降级
- 若 `~/.stapk/logs` 不存在：创建目录后写入。
- 若 debug.log 写入失败：UI 仍展示“最近一次记录”，但额外提示“落盘失败”。

## 需要修改的文件（设计预期）
- `upstream/termux-app/app/src/main/java/com/termux/app/StapkControlActivity.java`
- `upstream/termux-app/app/src/main/res/layout/activity_stapk_control.xml`
- `upstream/termux-app/app/src/main/res/values/stapk_strings.xml`
- （可能新增）`upstream/termux-app/app/src/main/java/com/termux/app/stapk/DebugRecord.java`

## 兼容性与安全
- 不改变现有脚本逻辑，仅增加可观测性。
- debug.log 仅包含本地执行信息，不包含用户密钥等敏感数据。

## 验收标准
- 首页开启调试开关后，能看到最近一次执行记录（字段完整）。
- 任何脚本执行成功/失败都会在 `debug.log` 中追加记录。
- 脚本不可执行时，调试记录仍可准确反映原因。
