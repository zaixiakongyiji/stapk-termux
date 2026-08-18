# stAPK 文档索引

本目录只保留当前架构、仍有维护价值的设计、发布说明和可追溯验证记录。已完成的一次性实施计划和问题修复过程由 Git 历史保存，不再作为当前执行入口。

## 当前架构

- [no-node 原生适配设计](superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md)：项目总体架构、能力边界和 Android 原生兼容层。
- [扩展与导入兼容设计](superpowers/specs/2026-07-17-stapk-extension-and-import-compatibility-design.md)：client-only 扩展、角色卡、World Info 和 Regex 兼容边界。
- [扩展事务恢复设计](superpowers/specs/2026-07-21-stapk-extension-transaction-recovery-design.md)：扩展安装、更新、删除和进程中断恢复。
- [远程 Embedding 与本地 Vector/RAG 设计](superpowers/specs/2026-07-30-stapk-remote-embedding-local-vector-design.md)：远程 Embedding、本地 SQLite 索引和隐私门禁。
- [仓库级 Emulator MCP 设计](superpowers/specs/2026-07-16-stapk-repository-emulator-mcp-design.md)：`Pixel_8` AVD 生命周期工具。

## 验证记录

- [单用户功能验证记录](plan/2026-07-12-stapk-single-user-feature-validation-record.md)
- [Vector Storage 验证记录](plan/2026-07-30-stapk-vector-storage-validation-record.md)

验证记录只描述已实际执行的命令和设备证据。Debug 设备功能验收与正式签名 APK 的版本、签名和校验验证必须分开陈述。

## 历史实施记录

- [0.3.x 单用户功能补齐实施记录](plan/2026-07-12-stapk-single-user-feature-completion-plan.md)：主体任务已经完成；其中完整应用备份恢复与 Data Maid 尚未实施，启动时必须重新设计和计划。

## 构建与发布

- [GitHub 自动构建与发版](reference/github-release-automation.md)
- [README](../README.md)
- [CHANGELOG](../CHANGELOG.md)

## 文档维护规则

- 新增功能先写设计，再写实施计划；完成并验收后，只保留仍有架构或运维价值的文档。
- 不把一次性调试日志、代理 brief、工作树 diff、APK 或截图证据放入 `docs/`。
- 当前事实优先写入 README、CLAUDE、主设计或验证记录，避免多个计划重复维护同一状态。
- 所有文档链接使用仓库相对路径；删除文档时必须同步修复引用。
