# 0.3.x 旧路线清理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除废弃的 Termux/Node runtime 路线，并证明 stAPK Mobile 0.3.x no-node 流程未受影响。

**Architecture:** 仅删除不参与当前构建、测试和发布的 legacy 文件；再清理配置、测试和文档引用。`mobile/`、no-node 转换工具、patch 队列和发布工作流保持不变。

**Tech Stack:** Git、Node.js 20、npm、Android Gradle Plugin、Kotlin Android App、GitHub Actions。

## 全局约束

- [ ] 不改写 Git 历史，不创建 legacy 分支。
- [ ] 不删除 `mobile/`、`patches/sillytavern-no-node/`、`transform/no-node/` 或当前 no-node 脚本。
- [ ] 不处理未跟踪且被忽略的 `AGENTS.md`、`OPERATION_LOG.md`。
- [ ] 不执行 `git commit` 或 `git push`。

### Task 1: 移除旧路线文件与仓库规则

**Files:** 删除 `upstream/termux-app/`、未跟踪的 `upstream/`/`payload/` 残留、`.omo/run-continuation/`、五个 legacy Shell/MJS 工具、`scripts/requirements.txt`、`transform/config/config.android.yaml`、`patches/sillytavern/series`，以及三份旧路线文档；修改 `.gitignore` 和 `.gitattributes`。

- [ ] 使用 `git rm -r upstream/termux-app` 与 `git rm -r .omo/run-continuation` 删除已跟踪目录；使用 `git clean -ffdx -- upstream payload` 删除已核对的未跟踪旧残留；使用 `git rm` 删除 `scripts/build-bootstrap-aarch64.sh`、`scripts/build-runtime-archive.sh`、`scripts/prepare-sillytavern-payload.sh`、`scripts/stapk-transform.mjs`、`scripts/test-stapk-init-payload.sh`、`scripts/requirements.txt`、`transform/config/config.android.yaml`、`patches/sillytavern/series`、`docs/reference/working-apk-bootstrap-content.md`、`docs/superpowers/plans/2026-05-28-stapk-debug-plan.md` 和 `docs/superpowers/specs/runtime-dependencies.md`。
- [ ] 从 `.gitignore` 删除全部 `payload/` 和 `upstream/termux-app/` 规则；在 AI 工作流段添加 `.omo/`。
- [ ] 从 `.gitattributes` 删除开头五条 `upstream/termux-app/` 规则；保留 no-node asset 规则。
- [ ] 运行 `git status --short`、`Test-Path mobile`、`Test-Path scripts/stapk-transform-no-node.mjs`、`Test-Path patches/sillytavern-no-node`、`Test-Path transform/no-node`。预期：删除项显示为 `D`，四个活跃路径均返回 `True`。

### Task 2: 消除配置、测试和文档中的旧路线引用

**Files:** 修改 `test/no-node/build-orchestrator.test.mjs`、`CLAUDE.md` 和 `docs/reference/github-release-automation.md`。

- [ ] 从 `test/no-node/build-orchestrator.test.mjs` 删除 `assert.ok(attributes.includes('upstream/termux-app/app/src/main/assets/SillyTavern.tar.gz'));`；保留禁止 mobile runtime payload 的前两条断言。
- [ ] 从 `CLAUDE.md` 删除将 `upstream/termux-app/` 作为当前目录介绍的段落；将 legacy scripts 段替换为“当前构建期转换由 `scripts/stapk-transform-no-node.mjs` 和 `scripts/stapk-build-no-node-apk.mjs` 负责。不要重新引入 Node runtime、payload archive 或运行时解压流程。”
- [ ] 从 `docs/reference/github-release-automation.md` 删除仅描述当前工作树 `upstream/termux-app/`、bootstrap 和旧 APK 命名的段落；保留 no-node CI、Release 与发布证据说明。
- [ ] 使用 `rg -n 'upstream/termux-app|build-bootstrap-aarch64|build-runtime-archive|prepare-sillytavern-payload|stapk-transform\\.mjs|test-stapk-init-payload|config\\.android\\.yaml|runtime-dependencies' --glob '!CHANGELOG.md' --glob '!docs/superpowers/specs/2026-07-27-no-node-legacy-cleanup-design.md' --glob '!docs/superpowers/plans/2026-07-27-no-node-legacy-cleanup-plan.md' .` 扫描。预期：无匹配项。

### Task 3: 验证 0.3.x no-node 主线

**Files:** 验证 `package.json`、`mobile/`、`.github/workflows/ci.yml` 和 `.github/workflows/release.yml`。

- [ ] 运行 `npm run test:no-node`。预期：退出码为 0，无失败用例。
- [ ] 运行 `npm run build:no-node-apk -- --variant debug --ref release`。预期：退出码为 0，生成 Debug APK 及 SHA-256、API contract、capability runtime、Web manifest 和 transform report。
- [ ] 运行 `git diff --check`、`git status --short`、`git diff --stat`。预期：`git diff --check` 无输出且退出码为 0；差异仅包含本计划指定的删除、引用修复和两份清理文档。
