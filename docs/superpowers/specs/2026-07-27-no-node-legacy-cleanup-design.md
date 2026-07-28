# 0.3.x 旧路线清理设计

**日期：** 2026-07-27
**状态：** 已确认，待实施

## 目标

将已废弃的 Termux/Node runtime 实现从当前工作树移除，减少仓库体积和维护噪音，同时保持 stAPK Mobile 0.3.x 的 no-node 构建、测试和发布流程不变。

本次不改写 Git 历史。已发布的 tag 和旧实现仍可通过历史提交追溯。

## 范围

删除以下仅服务于 v0.1.x/v0.2.x Termux、Node runtime 或 payload 路线的内容：

- `upstream/termux-app/`
- `scripts/build-bootstrap-aarch64.sh`
- `scripts/build-runtime-archive.sh`
- `scripts/prepare-sillytavern-payload.sh`
- `scripts/stapk-transform.mjs`
- `scripts/test-stapk-init-payload.sh`
- `scripts/requirements.txt`
- `transform/config/config.android.yaml`
- `patches/sillytavern/series`
- `docs/reference/working-apk-bootstrap-content.md`
- `docs/superpowers/plans/2026-05-28-stapk-debug-plan.md`
- `docs/superpowers/specs/runtime-dependencies.md`
- `.omo/run-continuation/`
- 未跟踪的 `upstream/` 与 `payload/` 残留构建产物。

同步清理上述路径在 `.gitignore`、`.gitattributes`、测试与文档中的引用。

## 保留项

以下内容是 0.3.x no-node 主线的一部分，不能删除：

- `mobile/` 及其转换后的 Web assets。
- `scripts/stapk-build-no-node-apk.mjs`、`stapk-transform-no-node.mjs`、扫描器和 verifier。
- `patches/sillytavern-no-node/`、`transform/no-node/` 与 `transform/schemas/`。
- 根目录 `package.json`、CI/Release workflow、`CHANGELOG.md` 和现行 no-node 设计文档。

本次不处理未跟踪且被忽略的 `AGENTS.md`、`OPERATION_LOG.md`，避免删除本地工作环境配置或个人记录。

## 实施调整

1. 删除旧路线文件和目录。
2. 从 `.gitignore` 与 `.gitattributes` 移除旧 payload、bootstrap 和 Termux 资源规则，并将 `/.omo/` 加入 `.gitignore`。
3. 从 `test/no-node/build-orchestrator.test.mjs` 删除要求跟踪旧 `SillyTavern.tar.gz` 的断言。
4. 更新 `CLAUDE.md` 与 `docs/reference/github-release-automation.md`，移除当前工作树仍保留 Termux 参考实现的描述。

不删除 `CHANGELOG.md` 内的旧版本记录；它是发布历史而非运行时依赖。

## 验收标准

清理后的工作树必须满足：

1. 活跃构建脚本、CI、Release workflow 和 no-node 测试不再引用已删除路径。
2. `npm run test:no-node` 通过。
3. `npm run build:no-node-apk -- --variant debug --ref release` 成功生成并验证 0.3.x Debug APK。
4. `git diff --check` 无空白错误。

## 风险与控制

唯一的功能风险是遗留路径被测试、构建或发布配置间接引用。实施会先用全文搜索定位并清理引用，再运行完整 no-node 测试与 Debug 构建。若任一验证失败，停止删除扩展范围并根据失败引用修正，而不恢复旧运行时路线。
