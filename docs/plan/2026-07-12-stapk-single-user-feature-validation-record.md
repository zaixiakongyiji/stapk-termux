# stAPK 0.3 单用户功能最终验证记录

> 更新日期：2026-07-17
> 主计划：`docs/plan/2026-07-12-stapk-single-user-feature-completion-plan.md`

## 记录规则

- `通过`：本轮有可重复命令或设备证据。
- `待验收`：代码和自动化已覆盖，但仍缺目标设备上的完整人工流程。
- `不适用`：设计明确排除或推迟到主体完成后的可选项目。
- 每次 Release 候选必须记录 APK SHA-256、upstream ref/commit、Android 版本、AVD/设备和 clean install 结果。

## 构建证据

| 项目 | 状态 | 证据 |
|---|---|---|
| no-node tests | 通过 | `npm run test:no-node`，68/68 |
| transform | 通过 | upstream `release` -> `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8` |
| strict capability | 通过 | `visibleNeedsReview=[]`，`unassignedEndpoints=[]` |
| Android JVM tests | 通过 | `:app:testDebugUnitTest` |
| Debug APK | 通过 | `output/stapk-mobile-debug.apk`，25,540,284 bytes |
| 单命令 orchestrator | 通过 | `npm run build:no-node-apk -- --variant debug --ref release`，生成六类 output |
| APK 内无 Node | 通过 | no-node verifier；Pixel 8 仅有 `com.stapk.mobile` 进程 |

## 功能矩阵

| 场景 | Pixel 8 / Android 15 | Android 10 | Android 7 | 备注 |
|---|---|---|---|---|
| clean install、首次加载、重启 | 通过 | 不适用 | 不适用 | 最新候选 APK 先卸载旧包再安装并进入官方 UI；上一轮稳定基线第 3 秒为 Android 启动画面、第 18 秒到达 `app_ready`。本轮在构建负载后首次 zh-CN 启动约 2 分钟，日志为 Web assets 解析与 GC，无崩溃，列为性能观察项 |
| Persona | 通过 | 不适用 | 不适用 | 官方 UI 创建 `Step8`，Persona 文件和 lorebook 绑定经重启保留 |
| 角色 PNG/JSON 导入导出 | 通过 | 不适用 | 不适用 | SAF 与重新导入已有设备证据 |
| 群组、群聊 | 通过 | 不适用 | 不适用 | `stepgroup`、普通群聊和 JSONL 导入群聊均持久化 |
| recent chats | 通过 | 不适用 | 不适用 | 角色聊天及两个群聊显示名称、摘要、消息数和文件大小，重启后仍存在 |
| 聊天 JSONL/TXT 导入导出 | 通过 | 不适用 | 不适用 | TXT 内容和两个 1776-byte JSONL 文件在宿主侧复核，JSONL 可重新导入 |
| World Info Unicode 与四类绑定 | 通过 | 不适用 | 不适用 | Unicode CRUD 与 global、character、Persona、group chat 绑定均经重启复核 |
| 背景 | 通过 | 不适用 | 不适用 | `__transparent.png=200`，背景面板可见 |
| 附件 | 通过 | 不适用 | 不适用 | SAF 选择 `step8.txt`，上传文件与聊天 JSONL 引用经重启保留 |
| Tokenizer | 通过 | 不适用 | 不适用 | 官方 UI 显示本地 OpenAI-compatible token 计数，JVM tests 通过 |
| settings/themes/presets/snapshots | 通过 | 不适用 | 不适用 | `steptheme`、`steppreset` 经重启保留；snapshot 显示真实大小并从 UI 恢复设置 |
| 账户头像与角色审查回归 | 通过 | 不适用 | 不适用 | Data URL 上传由 `/api/users/me` 回读并在官方账户弹窗渲染，清除后回退 Persona thumbnail；角色 edit 在 force-stop/relaunch 后仍保留 `description=after` |
| OpenAI-compatible 真实 provider | 通过 | 不适用 | 不适用 | custom host `catiecli.sukaka.top`、模型 `gcli-gemini-3.1-flash-lite`：models 与 chat completions 均 HTTP 200，官方 UI 显示回复；重启后配置和 secret 保留，Secrets API 仅返回 `********`，settings/diagnostics 无敏感字段 |
| SAF 配置变化生命周期 | 通过 | 不适用 | 不适用 | 页面旋转后成功保存，ticket 被消费 |
| 黑色 WebView surface | 通过 | 不适用 | 不适用 | API 35 冷启动第 3 秒显示原生启动页而非黑屏，第 13 秒官方 UI 可用；`app_ready` 约 12.1 秒 |

## 排除与延期

- APK 内置 Node.js、本地重型模型、任意 Node 扩展和 multiuser：设计排除。
- 0.2.x 旧数据迁移、完整应用 ZIP 备份恢复、Data Maid：主体完成后的可选独立项目。
- 项目只维护、回归和承诺 Android 15（API 35）及以上；Android 7–14（API 24–34）不再纳入支持矩阵。既有 `minSdk=24` 仅表示技术安装下限，不构成兼容性承诺。

## Release 候选填写区

| 字段 | 值 |
|---|---|
| stAPK tag | 未打 tag（Debug 候选） |
| SillyTavern ref | `release` |
| resolved commit | `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8` |
| APK | `output/stapk-mobile-debug.apk`（`0.3.2-dev`/30200） |
| SHA-256 | `6848ba540be83d907d4b81dce2e30ea677344d40ed7b01858e42a97aeef310a9` |
| 验收结论 | 一键构建、Pixel 8 / API 35 覆盖安装、官方单用户 UI 矩阵、真实外部 OpenAI-compatible chat/Embedding、Vector Storage/RAG、重启持久化和日志隐私验收通过；API 24–34 不属于正式维护范围 |
