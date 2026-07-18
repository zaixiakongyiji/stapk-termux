# stAPK 无 Node 原生适配转换器 Implementation Plan

> **执行状态（2026-07-12）：** Task 0-9 已完成并保留为基础 MVP 历史记录。Task 10-12 已被 `docs/plan/2026-07-12-stapk-single-user-feature-completion-plan.md` 替代，不再从本文继续执行；旧数据迁移改为项目主体完成后的独立可选项目。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 stAPK 0.3.0 从 Node runtime 容器路线切换为无 Node 原生适配路线：保留 SillyTavern 官方 Web UI，由 Android Kotlin/Java 本地 HTTP 兼容层承接 settings、characters、chats 和 OpenAI-compatible 真实对话 MVP。

**Architecture:** 构建期使用 no-node transform 从指定 SillyTavern upstream ref 生成 `sillytavern-web/`、`api-contract.json`、`stapk-web-manifest.json` 和 `transform-report.json`，不生成 `payload.tgz`，不打包 Node runtime。运行期由 `NativeHttpService` 启动 loopback-only Kotlin HTTP server，WebView 加载本地地址，前端 `/api/...` 请求由原生 controller 处理，本地数据写入 app 私有目录。

**Tech Stack:** Node.js 20+ 仅用于构建期 transform/contract 脚本；Android Kotlin；Android WebView；NanoHTTPD 2.3.1 作为 MVP HTTP server spike；OkHttp 4.12.0 访问 OpenAI-compatible provider；JUnit 4.13.2 做 JVM 单元测试；GitHub Actions；JSON contract/report 文件。

## Global Constraints

- 所有新增解释、计划、注释和文档使用中文；代码标识符、路径、API endpoint 保持英文。
- APK 运行时不得包含 Node.js binary、npm、`node_modules/`、`runtime-android-arm64-node*.zip` 或旧 `payload.tgz`。
- APK 运行时不得通过 `ProcessBuilder`、JNI 或 shell 启动 `node`、`npm`、`server.js`。
- 第一版必须保留 SillyTavern 官方 Web UI，通过 WebView 加载。
- 第一版只支持 OpenAI-compatible provider，默认关闭 streaming。
- 第一版允许隐藏非 OpenAI-compatible provider、extensions、world info、图片生成、TTS、STT、vectors、embedding、RAG、caption、translation 等非 MVP 入口。
- 第一版角色能力支持基础 JSON/表单字段和默认头像；PNG/WEBP 角色卡导入导出不进入 MVP。
- 本地 HTTP server 只绑定 `127.0.0.1`，端口由系统随机分配，Activity 通过服务状态读取端口并加载 WebView。
- API key 第一版存 app 私有目录，必须和普通 settings 分离，不得进入日志、transform report、validation record 或测试快照。
- 用户数据保存在 `filesDir/user_data/`，配置保存在 `filesDir/user_config/`，secrets 保存在 `filesDir/secrets/`，日志保存在 `filesDir/logs/`，状态保存在 `filesDir/state/`。
- 旧 `filesDir/SillyTavern/data/` 数据迁移失败时不得删除旧数据。
- 禁止主动执行 git commit/push；每个任务只提供建议提交信息。
- 旧 Node 容器 completion plan 已废弃；执行本计划时不得继续按 `docs/plan/2026-07-06-stapk-0.3-completion-plan.md` 推进。

---

## 文件结构

### 新增构建期脚本和配置

- `scripts/stapk-scan-web-contract.mjs`
  - 扫描 SillyTavern `public/` 前端资源中的 `fetch('/api/...')`、`fetch("/api/...")`、`getGenerateUrl()` 等调用。
  - 生成 frontend API call 列表，标记 `implemented`、`unsupported`、`needs_review`。

- `scripts/stapk-transform-no-node.mjs`
  - 拉取 upstream ref。
  - 应用 patch queue。
  - 复制 `public/` 到 no-node web output。
  - 调用 contract scanner。
  - 生成 `sillytavern-web/`、`api-contract.json`、`stapk-web-manifest.json`、`transform-report.json`。

- `scripts/stapk-verify-no-node-transform.mjs`
  - 校验 no-node transform 输出。
  - 阻断 Node runtime、`node_modules/`、`payload.tgz`、`server.js` 运行入口进入 Android assets。
  - 校验 MVP endpoint contract 和 web manifest。

- `transform/no-node/mvp-api-allowlist.json`
  - 声明 MVP 已实现 endpoint、允许隐藏 endpoint、需要人工复核的动态 endpoint。

- `transform/no-node/web-defaults.json`
  - 声明 Android no-node 默认设置，例如 `main_api=openai`、`chat_completion_source=openai`、`stream_openai=false`。

- `transform/schemas/api-contract.schema.json`
  - 校验 `api-contract.json`。

- `transform/schemas/no-node-web-manifest.schema.json`
  - 校验 `stapk-web-manifest.json`。

### 新增构建期测试

- `test/no-node/contract-scanner.test.mjs`
  - 覆盖静态 fetch、动态 generate URL、unsupported endpoint 标记。

- `test/no-node/no-node-transform.test.mjs`
  - 覆盖 transform 输出结构和 Node 产物阻断。

### Android 原生兼容层

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterPaths.kt`
  - 统一定义 web、config、data、secrets、logs、state 目录。

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterState.kt`
  - 统一定义 `STARTING`、`RUNNING`、`FAILED`、`STOPPED`、`MIGRATING`、`MIGRATION_FAILED`。

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpService.kt`
  - 前台服务，拥有本地 HTTP server 生命周期。

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
  - NanoHTTPD 封装，路由分发到 controller。

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/HttpResponse.kt`
  - 原生 controller 的统一响应模型。

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/StaticAssetController.kt`
  - 提供 Web UI 静态资源。

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/SettingsController.kt`
  - 实现 `/api/settings/get`、`/api/settings/save`。

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/CharacterController.kt`
  - 实现 MVP character endpoint。

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ChatController.kt`
  - 实现 MVP chat endpoint。

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/SecretStore.kt`
  - 保存和读取 OpenAI-compatible API key，输出时脱敏。

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/OpenAiCompatibleController.kt`
  - 实现 `/api/backends/chat-completions/status` 和 `/api/backends/chat-completions/generate`。

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/LegacyMigrationManager.kt`
  - 从旧 Node 容器数据目录迁移到 no-node 目录。

- `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/DiagnosticsBundle.kt`
  - 收集错误诊断和日志尾部，供 UI 复制。

### Android UI 和 Manifest

- `mobile/app/src/main/java/com/stapk/mobile/MainActivity.kt`
  - 改为启动/绑定 `NativeHttpService`，加载 `http://127.0.0.1:<port>/`。

- `mobile/app/src/main/res/layout/activity_main.xml`
  - 收敛为 loading、webView、errorView 三个区域。

- `mobile/app/src/main/AndroidManifest.xml`
  - 注册 `NativeHttpService`。

- `mobile/app/src/main/res/values/strings.xml`
  - 中文错误文案和通知文案。

### Android 测试

- `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/NativeAdapterPathsTest.kt`
- `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/SettingsControllerTest.kt`
- `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/CharacterControllerTest.kt`
- `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ChatControllerTest.kt`
- `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/SecretStoreTest.kt`
- `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/OpenAiCompatibleControllerTest.kt`
- `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/LegacyMigrationManagerTest.kt`

### 文档和 CI

- `.github/workflows/ci.yml`
  - 改为 no-node transform + verify。

- `.github/workflows/release.yml`
  - 改为 no-node transform + verify，release artifact 上传 web manifest、api contract、transform report。

- `CLAUDE.md`
  - 更新当前方向：旧 Node 容器路线是历史实现，新开发以 no-node 设计和计划为准。

- `README.md`
  - 更新产品描述，去掉“内置 Node.js runtime”主线描述。

- `docs/superpowers/specs/2026-06-25-stapk-transformer-design.md`
  - 标记为旧 Node 容器路线历史参考。

- `docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md`
  - 根据实施发现补充状态。

- `docs/plan/2026-07-09-stapk-no-node-native-adapter-validation-record.md`
  - 记录本地、CI、真机验证证据。

---

### Task 0：冻结新方向并清理旧计划入口（已完成：2026-07-09）

**目的：** 防止后续执行者继续按旧 Node runtime completion plan 实施。

**执行记录（2026-07-09）：**
- 已删除旧计划入口 `docs/plan/2026-07-06-stapk-0.3-completion-plan.md`。
- 已更新 `CLAUDE.md`、`README.md` 和 `docs/superpowers/specs/2026-06-25-stapk-transformer-design.md`，明确 2026-07-09 no-node 原生适配设计为当前主线。
- `git diff --check` 已通过；Windows 环境仅提示 Markdown 文件下次可能按 `autocrlf` 转换换行。

**Files:**
- Delete: `docs/plan/2026-07-06-stapk-0.3-completion-plan.md`
- Create: `docs/plan/2026-07-09-stapk-no-node-native-adapter-implementation-plan.md`
- Modify: `CLAUDE.md`
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-06-25-stapk-transformer-design.md`

**Interfaces:**
- Consumes: `docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md`
- Produces: 文档层面的方向切换说明，后续任务以本计划为唯一实施入口。

- [ ] **Step 1：确认工作区边界**

Run:

```powershell
git status --short
git branch --show-current
```

Expected:

```text
?? docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md
?? docs/plan/2026-07-09-stapk-no-node-native-adapter-implementation-plan.md
codex/stapk-0.3-transformer-design
```

如果出现不相关修改，记录但不回滚。

- [ ] **Step 2：在 `CLAUDE.md` 更新方向说明**

把当前 “Direction: the 0.3.0 transformer redesign” 替换为：

```markdown
## Direction: the no-Node native adapter redesign

`docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md` is the authoritative spec for the next major direction. The previous 0.3.0 Node-runtime container plan is historical reference only.

New work targets an APK that does not include or run Node.js. The app keeps SillyTavern's official Web UI in WebView and serves the required `/api/...` surface through a Kotlin/Java native loopback HTTP compatibility layer. Do not add new runtime dependencies on `node`, `npm`, `node_modules`, `payload.tgz`, or `runtime-android-arm64-node*.zip` for this direction.
```

- [ ] **Step 3：在 `README.md` 标注当前路线调整**

在 README 顶部简介后添加：

```markdown
> 方向调整：新的 no-Node 原生适配路线不再把 Node.js runtime 打包进 APK。当前仓库中 Node runtime/payload 相关内容属于既有 0.2.x/旧 0.3.0 容器路线，后续实现以 `docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md` 为准。
```

- [ ] **Step 4：在旧 transformer 设计文档顶部添加历史说明**

在 `docs/superpowers/specs/2026-06-25-stapk-transformer-design.md` 标题下添加：

```markdown
> **历史参考：** 本文档描述的是 Node runtime + WebView 容器路线。2026-07-09 后的新方向改为无 Node 原生适配转换器，权威设计见 `docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md`。
```

- [ ] **Step 5：验证文档状态**

Run:

```powershell
git diff --check
rg -n "2026-07-06-stapk-0.3-completion-plan|Node.js runtime asset|KeepAliveService.*Node" docs/plan CLAUDE.md README.md docs/superpowers/specs/2026-06-25-stapk-transformer-design.md
```

Expected:

```text
git diff --check exits 0
rg only finds historical-reference text, not active execution instructions
```

**建议提交：**

```text
docs: 切换为无 Node 原生适配计划
```

---

### Task 1：实现前端 API 契约扫描（已完成：2026-07-09）

**目的：** 在改 Android 代码前先知道官方 Web UI 会调用哪些 `/api/...`，并用 allowlist 管住 MVP 范围。

**执行记录（2026-07-09）：**
- 已新增 `scripts/stapk-scan-web-contract.mjs`、`transform/no-node/mvp-api-allowlist.json`、`transform/schemas/api-contract.schema.json` 和 `test/no-node/contract-scanner.test.mjs`。
- 已在 `package.json` 增加 `test:no-node` 和 `scan:no-node-contract`。
- `npm run test:no-node` 已通过，契约扫描单测覆盖 exact implemented、unsupported hidden prefix 和 unknown needs_review。

**Files:**
- Create: `scripts/stapk-scan-web-contract.mjs`
- Create: `transform/no-node/mvp-api-allowlist.json`
- Create: `transform/schemas/api-contract.schema.json`
- Create: `test/no-node/contract-scanner.test.mjs`
- Modify: `package.json`

**Interfaces:**
- Produces: `scanWebContract({ webRoot, allowlistFile, upstream }) -> Promise<ApiContract>`
- Produces: CLI `node scripts/stapk-scan-web-contract.mjs --web-root <dir> --allowlist <file> --out <file> --upstream-commit <sha> --upstream-ref <ref> --upstream-version <version>`
- Later tasks consume: `api-contract.json`

- [ ] **Step 1：新增 allowlist 文件**

Create `transform/no-node/mvp-api-allowlist.json`:

```json
{
  "schema_version": 1,
  "implemented": [
    { "method": "GET", "path": "/version" },
    { "method": "GET", "path": "/csrf-token" },
    { "method": "POST", "path": "/api/ping" },
    { "method": "POST", "path": "/api/settings/get" },
    { "method": "POST", "path": "/api/settings/save" },
    { "method": "POST", "path": "/api/characters/all" },
    { "method": "POST", "path": "/api/characters/get" },
    { "method": "POST", "path": "/api/characters/create" },
    { "method": "POST", "path": "/api/characters/edit" },
    { "method": "POST", "path": "/api/characters/delete" },
    { "method": "POST", "path": "/api/characters/chats" },
    { "method": "POST", "path": "/api/chats/get" },
    { "method": "POST", "path": "/api/chats/save" },
    { "method": "POST", "path": "/api/chats/delete" },
    { "method": "POST", "path": "/api/chats/search" },
    { "method": "POST", "path": "/api/backends/chat-completions/status" },
    { "method": "POST", "path": "/api/backends/chat-completions/generate" }
  ],
  "unsupported_hidden": [
    "/api/extensions",
    "/api/worldinfo",
    "/api/tokenizers",
    "/api/images",
    "/api/speech",
    "/api/vectors",
    "/api/translate",
    "/api/stable-diffusion"
  ],
  "dynamic_review_patterns": [
    "getGenerateUrl",
    "endpoint",
    "url"
  ]
}
```

- [ ] **Step 2：新增 contract schema**

Create `transform/schemas/api-contract.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["schema_version", "upstream", "frontend_api_calls", "unsupported_api_calls", "needs_review"],
  "properties": {
    "schema_version": { "const": 1 },
    "upstream": {
      "type": "object",
      "required": ["repo", "ref", "commit", "version"],
      "properties": {
        "repo": { "type": "string", "minLength": 1 },
        "ref": { "type": "string", "minLength": 1 },
        "commit": { "type": "string", "pattern": "^[0-9a-f]{7,40}$" },
        "version": { "type": "string", "minLength": 1 }
      }
    },
    "frontend_api_calls": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["method", "path", "source", "status"],
        "properties": {
          "method": { "type": "string", "enum": ["GET", "POST", "PUT", "PATCH", "DELETE", "UNKNOWN"] },
          "path": { "type": "string", "minLength": 1 },
          "source": { "type": "string", "minLength": 1 },
          "status": { "type": "string", "enum": ["implemented", "unsupported", "needs_review"] }
        }
      }
    },
    "unsupported_api_calls": { "type": "array" },
    "needs_review": { "type": "array" }
  },
  "additionalProperties": true
}
```

- [ ] **Step 3：写失败测试：静态 fetch 被识别并标记 implemented**

Create `test/no-node/contract-scanner.test.mjs`:

```js
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { scanWebContract } from '../../scripts/stapk-scan-web-contract.mjs';

async function makeFixture(files) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'stapk-contract-'));
  for (const [name, content] of Object.entries(files)) {
    const file = path.join(dir, name);
    await fs.mkdir(path.dirname(file), { recursive: true });
    await fs.writeFile(file, content, 'utf8');
  }
  return dir;
}

test('marks allowlisted static fetch calls as implemented', async () => {
  const webRoot = await makeFixture({
    'public/script.js': "await fetch('/api/settings/get', { method: 'POST' });"
  });
  const allowlistFile = path.join(webRoot, 'allowlist.json');
  await fs.writeFile(allowlistFile, JSON.stringify({
    schema_version: 1,
    implemented: [{ method: 'POST', path: '/api/settings/get' }],
    unsupported_hidden: [],
    dynamic_review_patterns: []
  }));

  const contract = await scanWebContract({
    webRoot,
    allowlistFile,
    upstream: {
      repo: 'https://github.com/SillyTavern/SillyTavern.git',
      ref: 'release',
      commit: '51ad27f',
      version: '1.18.0'
    }
  });

  assert.deepEqual(contract.frontend_api_calls, [{
    method: 'POST',
    path: '/api/settings/get',
    source: 'public/script.js',
    status: 'implemented'
  }]);
  assert.deepEqual(contract.unsupported_api_calls, []);
});
```

- [ ] **Step 4：运行测试确认失败**

Run:

```powershell
node --test test/no-node/contract-scanner.test.mjs
```

Expected:

```text
FAIL ... Cannot find module ... scripts/stapk-scan-web-contract.mjs
```

- [ ] **Step 5：实现 scanner 最小版本**

Create `scripts/stapk-scan-web-contract.mjs`:

```js
import fs from 'node:fs/promises';
import path from 'node:path';
import { parseArgs } from 'node:util';

const FETCH_RE = /fetch\(\s*['"`]([^'"`]+)['"`]\s*(?:,\s*\{([^)]*)\})?/g;

async function walk(dir) {
  const entries = await fs.readdir(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) files.push(...await walk(full));
    if (entry.isFile() && /\.(js|mjs|html)$/.test(entry.name)) files.push(full);
  }
  return files;
}

function normalizeSource(webRoot, file) {
  return path.relative(webRoot, file).replaceAll(path.sep, '/');
}

function inferMethod(optionsText) {
  if (!optionsText) return 'GET';
  const match = optionsText.match(/method\s*:\s*['"`]([A-Za-z]+)['"`]/);
  return match ? match[1].toUpperCase() : 'GET';
}

function keyOf(method, apiPath) {
  return `${method.toUpperCase()} ${apiPath}`;
}

function statusFor(method, apiPath, allowlist) {
  const implemented = new Set((allowlist.implemented || []).map(x => keyOf(x.method, x.path)));
  if (implemented.has(keyOf(method, apiPath))) return 'implemented';
  const hidden = allowlist.unsupported_hidden || [];
  if (hidden.some(prefix => apiPath === prefix || apiPath.startsWith(`${prefix}/`))) return 'unsupported';
  return 'needs_review';
}

async function readJson(file) {
  return JSON.parse(await fs.readFile(file, 'utf8'));
}

export async function scanWebContract({ webRoot, allowlistFile, upstream }) {
  const allowlist = await readJson(allowlistFile);
  const files = await walk(webRoot);
  const calls = new Map();

  for (const file of files) {
    const source = normalizeSource(webRoot, file);
    const text = await fs.readFile(file, 'utf8');
    for (const match of text.matchAll(FETCH_RE)) {
      const apiPath = match[1];
      if (!apiPath.startsWith('/api/') && apiPath !== '/version' && apiPath !== '/csrf-token') continue;
      const method = inferMethod(match[2]);
      const status = statusFor(method, apiPath, allowlist);
      calls.set(`${method} ${apiPath} ${source}`, { method, path: apiPath, source, status });
    }
  }

  const frontendApiCalls = [...calls.values()].sort((a, b) =>
    `${a.path} ${a.source}`.localeCompare(`${b.path} ${b.source}`),
  );

  return {
    schema_version: 1,
    upstream,
    frontend_api_calls: frontendApiCalls,
    unsupported_api_calls: frontendApiCalls.filter(x => x.status === 'unsupported'),
    needs_review: frontendApiCalls.filter(x => x.status === 'needs_review')
  };
}

async function main() {
  const { values } = parseArgs({
    options: {
      'web-root': { type: 'string' },
      allowlist: { type: 'string', default: 'transform/no-node/mvp-api-allowlist.json' },
      out: { type: 'string' },
      'upstream-repo': { type: 'string', default: 'https://github.com/SillyTavern/SillyTavern.git' },
      'upstream-ref': { type: 'string', default: 'release' },
      'upstream-commit': { type: 'string' },
      'upstream-version': { type: 'string' }
    },
    strict: true
  });

  if (!values['web-root'] || !values.out || !values['upstream-commit'] || !values['upstream-version']) {
    throw new Error('--web-root, --out, --upstream-commit and --upstream-version are required');
  }

  const contract = await scanWebContract({
    webRoot: path.resolve(values['web-root']),
    allowlistFile: path.resolve(values.allowlist),
    upstream: {
      repo: values['upstream-repo'],
      ref: values['upstream-ref'],
      commit: values['upstream-commit'],
      version: values['upstream-version']
    }
  });

  await fs.mkdir(path.dirname(path.resolve(values.out)), { recursive: true });
  await fs.writeFile(path.resolve(values.out), JSON.stringify(contract, null, 2));
}

if (import.meta.url === `file://${process.argv[1].replaceAll('\\', '/')}`) {
  main().catch(error => {
    console.error(error.message);
    process.exit(1);
  });
}
```

- [ ] **Step 6：跑测试确认通过**

Run:

```powershell
node --test test/no-node/contract-scanner.test.mjs
node --check scripts/stapk-scan-web-contract.mjs
```

Expected:

```text
PASS test/no-node/contract-scanner.test.mjs
node --check exits 0
```

- [ ] **Step 7：增加 package scripts**

Modify `package.json`:

```json
{
  "scripts": {
    "test:no-node": "node --test test/no-node/*.test.mjs",
    "scan:no-node-contract": "node scripts/stapk-scan-web-contract.mjs --web-root build/stapk-no-node/upstream/public --out build/stapk-no-node/api-contract.json --upstream-commit 51ad27f --upstream-version 1.18.0"
  }
}
```

Preserve existing fields and merge scripts if already present.

- [ ] **Step 8：最终验证**

Run:

```powershell
npm run test:no-node
git diff --check
```

Expected:

```text
all no-node tests pass
git diff --check exits 0
```

**建议提交：**

```text
test: 添加无 Node 前端 API 契约扫描
```

---

### Task 2：实现 no-node transform 输出结构（已完成：2026-07-09）

**目的：** 从 upstream 生成 Android 可打包的 Web UI assets 和 manifest，彻底避开 Node runtime/payload 输出。

**执行记录（2026-07-09）：**
- 已新增 `scripts/stapk-transform-no-node.mjs`、`scripts/stapk-verify-no-node-transform.mjs`、`transform/schemas/no-node-web-manifest.schema.json` 和 `test/no-node/no-node-transform.test.mjs`。
- 已在 `package.json` 增加 `transform:no-node` 和 `transform:no-node:verify`。
- `npm run transform:no-node` 已从 SillyTavern `release` 生成 `build/no-node-payload/`，resolved commit 为 `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8`。
- 当前输出包含 `sillytavern-web/`、`api-contract.json`、`stapk-web-manifest.json`、`transform-report.json`，manifest 中 `noRuntimeNode: true`。
- 当前 API 契约统计：`implemented=1`、`unsupported_hidden=26`、`needs_review=227`。后续 Task 3-9 必须继续收敛这些 `needs_review`，不能把它们当作已实现。
- `npm run transform:no-node:verify` 已通过，verifier 会阻断 `node_modules`、Node binary、runtime zip、`payload.tgz` 和 `server.js` 等运行时 Node 产物。

**Files:**
- Create: `scripts/stapk-transform-no-node.mjs`
- Create: `scripts/stapk-verify-no-node-transform.mjs`
- Create: `transform/schemas/no-node-web-manifest.schema.json`
- Create: `test/no-node/no-node-transform.test.mjs`
- Modify: `package.json`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: `scanWebContract()` from `scripts/stapk-scan-web-contract.mjs`
- Produces: `build/no-node-payload/sillytavern-web/`
- Produces: `build/no-node-payload/stapk-web-manifest.json`
- Produces: `build/no-node-payload/api-contract.json`
- Produces: `build/no-node-payload/transform-report.json`
- CLI: `node scripts/stapk-transform-no-node.mjs --ref release --out build/no-node-payload --clean`

- [ ] **Step 1：写失败测试：transform 输出不含 Node 产物**

Create `test/no-node/no-node-transform.test.mjs`:

```js
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { verifyNoNodeOutput } from '../../scripts/stapk-verify-no-node-transform.mjs';

test('verifyNoNodeOutput rejects node runtime artifacts', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'stapk-no-node-output-'));
  await fs.mkdir(path.join(dir, 'sillytavern-web'), { recursive: true });
  await fs.writeFile(path.join(dir, 'runtime-android-arm64-node24.zip'), 'bad');

  const result = await verifyNoNodeOutput(dir);

  assert.equal(result.ok, false);
  assert.match(result.errors.join('\n'), /runtime-android-arm64-node24\.zip/);
});
```

- [ ] **Step 2：运行测试确认失败**

Run:

```powershell
node --test test/no-node/no-node-transform.test.mjs
```

Expected:

```text
FAIL ... Cannot find module ... scripts/stapk-verify-no-node-transform.mjs
```

- [ ] **Step 3：实现 no-node verify 最小版本**

Create `scripts/stapk-verify-no-node-transform.mjs`:

```js
import fs from 'node:fs/promises';
import path from 'node:path';
import { parseArgs } from 'node:util';

const FORBIDDEN_NAMES = [
  'payload.tgz',
  'runtime-android-arm64-node24.zip',
  'runtime-android-arm64-node24.zip.sha256',
  'node_modules'
];

async function exists(file) {
  try {
    await fs.stat(file);
    return true;
  } catch {
    return false;
  }
}

async function walk(dir) {
  const entries = await fs.readdir(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    files.push(full);
    if (entry.isDirectory()) files.push(...await walk(full));
  }
  return files;
}

export async function verifyNoNodeOutput(outDir) {
  const errors = [];
  for (const required of ['sillytavern-web', 'api-contract.json', 'stapk-web-manifest.json', 'transform-report.json']) {
    if (!await exists(path.join(outDir, required))) errors.push(`missing required output: ${required}`);
  }

  if (await exists(outDir)) {
    for (const file of await walk(outDir)) {
      const rel = path.relative(outDir, file).replaceAll(path.sep, '/');
      if (FORBIDDEN_NAMES.some(name => rel === name || rel.includes(`/${name}/`) || rel.endsWith(`/${name}`))) {
        errors.push(`forbidden Node artifact: ${rel}`);
      }
    }
  }

  return { ok: errors.length === 0, errors };
}

async function main() {
  const { values } = parseArgs({
    options: { out: { type: 'string', default: 'build/no-node-payload' } },
    strict: true
  });
  const result = await verifyNoNodeOutput(path.resolve(values.out));
  if (!result.ok) {
    console.error('stapk no-node transform verification failed');
    for (const error of result.errors) console.error(`- ${error}`);
    process.exit(1);
  }
  console.log('stapk no-node transform verification passed');
}

if (import.meta.url === `file://${process.argv[1].replaceAll('\\', '/')}`) {
  main().catch(error => {
    console.error(error.message);
    process.exit(1);
  });
}
```

- [ ] **Step 4：验证失败测试变绿**

Run:

```powershell
node --test test/no-node/no-node-transform.test.mjs
node --check scripts/stapk-verify-no-node-transform.mjs
```

Expected:

```text
PASS test/no-node/no-node-transform.test.mjs
node --check exits 0
```

- [ ] **Step 5：新增 no-node web manifest schema**

Create `transform/schemas/no-node-web-manifest.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "required": ["schema_version", "generated_at", "upstream", "web", "node_runtime"],
  "properties": {
    "schema_version": { "const": 1 },
    "generated_at": { "type": "string", "minLength": 1 },
    "upstream": {
      "type": "object",
      "required": ["repo", "ref", "commit", "version"],
      "properties": {
        "repo": { "type": "string", "minLength": 1 },
        "ref": { "type": "string", "minLength": 1 },
        "commit": { "type": "string", "pattern": "^[0-9a-f]{7,40}$" },
        "version": { "type": "string", "minLength": 1 }
      }
    },
    "web": {
      "type": "object",
      "required": ["root", "sha256", "file_count"],
      "properties": {
        "root": { "const": "sillytavern-web" },
        "sha256": { "type": "string", "pattern": "^[0-9a-f]{64}$" },
        "file_count": { "type": "integer", "minimum": 1 }
      }
    },
    "node_runtime": { "const": false }
  },
  "additionalProperties": true
}
```

- [ ] **Step 6：实现 transform 脚本**

Create `scripts/stapk-transform-no-node.mjs`:

```js
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { parseArgs } from 'node:util';
import { scanWebContract } from './stapk-scan-web-contract.mjs';

function run(cmd, cwd) {
  return execSync(cmd, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

async function ensureDir(dir) {
  await fs.mkdir(dir, { recursive: true });
}

async function hashTree(dir) {
  const hash = crypto.createHash('sha256');
  let count = 0;
  async function walk(current) {
    const entries = await fs.readdir(current, { withFileTypes: true });
    entries.sort((a, b) => a.name.localeCompare(b.name));
    for (const entry of entries) {
      const full = path.join(current, entry.name);
      const rel = path.relative(dir, full).replaceAll(path.sep, '/');
      if (entry.isDirectory()) {
        await walk(full);
      } else {
        count += 1;
        hash.update(rel);
        hash.update(await fs.readFile(full));
      }
    }
  }
  await walk(dir);
  return { sha256: hash.digest('hex'), file_count: count };
}

async function main() {
  const { values } = parseArgs({
    options: {
      repo: { type: 'string', default: 'https://github.com/SillyTavern/SillyTavern.git' },
      ref: { type: 'string', default: 'release' },
      out: { type: 'string', default: 'build/no-node-payload' },
      clean: { type: 'boolean', default: false }
    },
    strict: true
  });

  const buildDir = path.resolve('build/stapk-no-node');
  const upstreamDir = path.join(buildDir, 'upstream');
  const outDir = path.resolve(values.out);

  if (values.clean) {
    await fs.rm(buildDir, { recursive: true, force: true });
    await fs.rm(outDir, { recursive: true, force: true });
  }
  await ensureDir(upstreamDir);
  await ensureDir(outDir);

  if (!existsSync(path.join(upstreamDir, '.git'))) {
    run('git init', upstreamDir);
    run(`git remote add origin ${values.repo}`, upstreamDir);
  }
  run(`git fetch --depth=1 origin ${values.ref}`, upstreamDir);
  run('git checkout --detach FETCH_HEAD', upstreamDir);

  const commit = run('git rev-parse HEAD', upstreamDir);
  const pkg = JSON.parse(await fs.readFile(path.join(upstreamDir, 'package.json'), 'utf8'));
  const publicDir = path.join(upstreamDir, 'public');
  if (!existsSync(path.join(publicDir, 'index.html'))) throw new Error('public/index.html missing');

  const webOut = path.join(outDir, 'sillytavern-web');
  await fs.rm(webOut, { recursive: true, force: true });
  await fs.cp(publicDir, webOut, { recursive: true });

  const web = await hashTree(webOut);
  const upstream = { repo: values.repo, ref: values.ref, commit, version: pkg.version };
  const contract = await scanWebContract({
    webRoot: publicDir,
    allowlistFile: path.resolve('transform/no-node/mvp-api-allowlist.json'),
    upstream
  });

  const manifest = {
    schema_version: 1,
    generated_at: new Date().toISOString(),
    upstream,
    web: { root: 'sillytavern-web', ...web },
    node_runtime: false
  };

  const report = {
    schema_version: 1,
    generated_at: manifest.generated_at,
    unsupported_api_count: contract.unsupported_api_calls.length,
    needs_review_count: contract.needs_review.length,
    warnings: contract.needs_review.map(x => `needs review: ${x.method} ${x.path} in ${x.source}`)
  };

  await fs.writeFile(path.join(outDir, 'api-contract.json'), JSON.stringify(contract, null, 2));
  await fs.writeFile(path.join(outDir, 'stapk-web-manifest.json'), JSON.stringify(manifest, null, 2));
  await fs.writeFile(path.join(outDir, 'transform-report.json'), JSON.stringify(report, null, 2));
  console.log(`stapk no-node transform completed: ${outDir}`);
}

main().catch(error => {
  console.error(error.message);
  process.exit(1);
});
```

- [ ] **Step 7：更新 package scripts**

Modify `package.json` scripts:

```json
{
  "transform:no-node": "node scripts/stapk-transform-no-node.mjs --ref release --out build/no-node-payload --clean",
  "transform:no-node:verify": "node scripts/stapk-verify-no-node-transform.mjs --out build/no-node-payload"
}
```

- [ ] **Step 8：本地验证 transform**

Run:

```powershell
node --check scripts/stapk-transform-no-node.mjs
npm run transform:no-node
npm run transform:no-node:verify
```

Expected:

```text
stapk no-node transform completed: ...build/no-node-payload
stapk no-node transform verification passed
```

- [ ] **Step 9：检查 no-node 输出不含旧产物**

Run:

```powershell
Get-ChildItem -Recurse build/no-node-payload | Select-String -Pattern 'node_modules|runtime-android|payload.tgz'
```

Expected:

```text
no matches
```

**建议提交：**

```text
feat: 生成无 Node Web 转换产物
```

---

### Task 3：新增 Android no-node 路径和状态模型（已完成：2026-07-10）

**目的：** 在 Android 侧先建立无 Node 目录合同和状态模型，避免继续沿用 `RuntimeManager` 的 runtime/payload 语义。

**执行记录（2026-07-10）：**
- 已在 `mobile/app/build.gradle.kts` 增加 JUnit 4.13.2 JVM 单元测试依赖。
- 已新增 `NativeAdapterPaths`、`NativeAdapterStatus` 和 `NativeAdapterState`，固定 `web`、`user_config`、`user_data`、`secrets`、`logs`、`state` 与旧 `SillyTavern` 目录合同。
- 已新增 `NativeAdapterPathsTest`，覆盖全部派生路径、六个状态值和 `NativeAdapterState` 默认字段语义。
- TDD RED 已确认因 `NativeAdapterPaths`、`NativeAdapterStatus` 和 `NativeAdapterState` 尚不存在而编译失败；恢复最小实现后 focused test 与完整 `:app:testDebugUnitTest` 均通过。
- 任务级复核已通过，结论为 spec compliant、Task quality approved，无未解决 Critical、Important 或 Minor 问题。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterPaths.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterState.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/NativeAdapterPathsTest.kt`
- Modify: `mobile/app/build.gradle.kts`

**Interfaces:**
- Produces: `data class NativeAdapterPaths(private val filesDir: File)`
- Produces: `enum class NativeAdapterStatus`
- Later tasks consume: `NativeAdapterPaths`, `NativeAdapterStatus`

- [x] **Step 1：添加测试依赖**

Modify `mobile/app/build.gradle.kts` dependencies:

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation("junit:junit:4.13.2")
}
```

- [x] **Step 2：写失败测试**

Create `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/NativeAdapterPathsTest.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class NativeAdapterPathsTest {
    @Test
    fun `paths use no-node directory contract`() {
        val root = File("root")
        val paths = NativeAdapterPaths(root)

        assertEquals(File(root, "web"), paths.webDir)
        assertEquals(File(root, "user_config"), paths.userConfigDir)
        assertEquals(File(root, "user_data"), paths.userDataDir)
        assertEquals(File(root, "secrets"), paths.secretsDir)
        assertEquals(File(root, "logs"), paths.logsDir)
        assertEquals(File(root, "state"), paths.stateDir)
        assertEquals(File(root, "SillyTavern"), paths.legacySillyTavernDir)
    }
}
```

- [x] **Step 3：运行测试确认失败**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.NativeAdapterPathsTest"
```

Expected:

```text
Compilation error: Unresolved reference: NativeAdapterPaths
```

- [x] **Step 4：实现路径和状态模型**

Create `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterPaths.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import java.io.File

data class NativeAdapterPaths(private val filesDir: File) {
    val webDir: File = File(filesDir, "web")
    val webManifestFile: File = File(File(filesDir, "state"), "installed-web-manifest.json")
    val userConfigDir: File = File(filesDir, "user_config")
    val settingsFile: File = File(userConfigDir, "settings.json")
    val providerConfigFile: File = File(userConfigDir, "provider-openai-compatible.json")
    val userDataDir: File = File(filesDir, "user_data")
    val charactersDir: File = File(userDataDir, "characters")
    val chatsDir: File = File(userDataDir, "chats")
    val secretsDir: File = File(filesDir, "secrets")
    val logsDir: File = File(filesDir, "logs")
    val stateDir: File = File(filesDir, "state")
    val adapterStateFile: File = File(stateDir, "native-adapter-state.json")
    val legacySillyTavernDir: File = File(filesDir, "SillyTavern")
}
```

Create `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeAdapterState.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

enum class NativeAdapterStatus {
    STARTING,
    RUNNING,
    FAILED,
    STOPPED,
    MIGRATING,
    MIGRATION_FAILED
}

data class NativeAdapterState(
    val status: NativeAdapterStatus,
    val port: Int? = null,
    val message: String = ""
)
```

- [x] **Step 5：验证测试通过**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.NativeAdapterPathsTest"
```

Expected:

```text
BUILD SUCCESSFUL
```

**建议提交：**

```text
feat: 定义无 Node 原生适配目录合同
```

---

### Task 4：建立本地 HTTP server 骨架和静态资源服务（已完成：2026-07-10）

**目的：** 用 Android 原生 HTTP server 替代 Node server 的最小入口，先支持 `/`、`/version` 和静态资源。

**执行记录（2026-07-10）：**
- 已新增 `HttpResponse`、`StaticAssetController`、`NativeHttpServer` 和 foreground `NativeHttpService`，并在 Manifest 中以 `dataSync` foreground service 注册。
- `NativeHttpServer` 固定绑定 `127.0.0.1`，默认使用系统随机端口；真实 loopback 测试已覆盖 `/version` 和 `/`。
- 静态资源测试覆盖首页、404、文本/二进制 body、实际 no-node Web 资源 MIME、sibling-prefix 目录穿越和 non-canonical 根目录拒绝。
- foreground setup、通知创建、`startForeground()` 和 server 启动统一进入可测试错误边界；失败时清理并返回 `FAILED`，STOP/FAILED 不使用 sticky 重启。
- focused test、完整 `:app:testDebugUnitTest` 和 `:app:assembleDebug` 均通过；任务级 re-review 结论为 spec compliant，Critical/Important/Minor 均为 0。
- foreground notification、Service 进程回收和设备重启行为尚未经过模拟器或真机验证，继续保留到端到端验证阶段。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/HttpResponse.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/StaticAssetController.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpService.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/StaticAssetControllerTest.kt`
- Modify: `mobile/app/build.gradle.kts`
- Modify: `mobile/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `NativeAdapterPaths`
- Produces: `class NativeHttpServer(paths: NativeAdapterPaths, port: Int = 0)`
- Produces: `class StaticAssetController(private val webDir: File)`
- Later tasks consume: route registration and response helpers.

- [x] **Step 1：添加 NanoHTTPD 依赖**

Modify `mobile/app/build.gradle.kts` dependencies:

```kotlin
implementation("org.nanohttpd:nanohttpd:2.3.1")
```

- [x] **Step 2：写失败测试：静态资源返回 index**

Create `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/StaticAssetControllerTest.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StaticAssetControllerTest {
    @Test
    fun `serves index html for root path`() {
        val dir = Files.createTempDirectory("stapk-web").toFile()
        File(dir, "index.html").writeText("<html>ok</html>")
        val controller = StaticAssetController(dir)

        val response = controller.serve("/")

        assertEquals(200, response.statusCode)
        assertEquals("text/html; charset=utf-8", response.mimeType)
        assertTrue(response.bodyText!!.contains("ok"))
    }
}
```

- [x] **Step 3：运行测试确认失败**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.StaticAssetControllerTest"
```

Expected:

```text
Compilation error: Unresolved reference: StaticAssetController
```

- [x] **Step 4：实现 HttpResponse 和 StaticAssetController**

Create `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/HttpResponse.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

data class HttpResponse(
    val statusCode: Int,
    val mimeType: String,
    val bodyText: String? = null,
    val bodyBytes: ByteArray? = null
) {
    companion object {
        fun json(statusCode: Int, body: String): HttpResponse =
            HttpResponse(statusCode, "application/json; charset=utf-8", bodyText = body)

        fun text(statusCode: Int, body: String): HttpResponse =
            HttpResponse(statusCode, "text/plain; charset=utf-8", bodyText = body)
    }
}
```

Create `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/StaticAssetController.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import java.io.File

class StaticAssetController(private val webDir: File) {
    fun serve(path: String): HttpResponse {
        val normalized = if (path == "/") "index.html" else path.removePrefix("/")
        val target = File(webDir, normalized)
        val canonicalRoot = webDir.canonicalFile
        val canonicalTarget = target.canonicalFile

        if (!canonicalTarget.path.startsWith(canonicalRoot.path)) {
            return HttpResponse.text(403, "Forbidden")
        }

        if (!canonicalTarget.exists() || canonicalTarget.isDirectory) {
            return HttpResponse.text(404, "Not found")
        }

        val mime = when (canonicalTarget.extension.lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> "application/octet-stream"
        }

        return if (mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json") || mime.contains("svg")) {
            HttpResponse(200, mime, bodyText = canonicalTarget.readText())
        } else {
            HttpResponse(200, mime, bodyBytes = canonicalTarget.readBytes())
        }
    }
}
```

- [x] **Step 5：实现 NativeHttpServer 路由骨架**

Create `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import fi.iki.elonen.NanoHTTPD
import java.io.File

class NativeHttpServer(
    private val paths: NativeAdapterPaths,
    port: Int = 0
) : NanoHTTPD("127.0.0.1", port) {
    private val staticAssets = StaticAssetController(paths.webDir)

    override fun serve(session: IHTTPSession): Response {
        val response = when (val uri = session.uri) {
            "/version" -> HttpResponse.json(200, """{"agent":"stapk-mobile","node_runtime":false}""")
            else -> staticAssets.serve(uri)
        }

        val status = when (response.statusCode) {
            200 -> Response.Status.OK
            403 -> Response.Status.FORBIDDEN
            404 -> Response.Status.NOT_FOUND
            else -> Response.Status.INTERNAL_ERROR
        }

        return if (response.bodyBytes != null) {
            newFixedLengthResponse(status, response.mimeType, response.bodyBytes.inputStream(), response.bodyBytes.size.toLong())
        } else {
            newFixedLengthResponse(status, response.mimeType, response.bodyText ?: "")
        }
    }
}
```

- [x] **Step 6：实现 NativeHttpService 最小骨架并注册 Manifest**

Create `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpService.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

class NativeHttpService : Service() {
    companion object {
        const val ACTION_START = "com.stapk.mobile.nativeadapter.START"
        const val ACTION_STOP = "com.stapk.mobile.nativeadapter.STOP"
    }

    private val binder = LocalBinder()
    private var server: NativeHttpServer? = null
    private var state: NativeAdapterState = NativeAdapterState(NativeAdapterStatus.STOPPED)

    inner class LocalBinder : Binder() {
        fun service(): NativeHttpService = this@NativeHttpService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopServer()
            else -> startServer()
        }
        return START_STICKY
    }

    fun currentState(): NativeAdapterState = state

    private fun startServer() {
        if (server != null) return
        state = NativeAdapterState(NativeAdapterStatus.STARTING)
        val paths = NativeAdapterPaths(filesDir)
        paths.webDir.mkdirs()
        server = NativeHttpServer(paths).also {
            it.start()
            state = NativeAdapterState(NativeAdapterStatus.RUNNING, port = it.listeningPort)
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        state = NativeAdapterState(NativeAdapterStatus.STOPPED)
        stopSelf()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
```

Modify `mobile/app/src/main/AndroidManifest.xml`:

```xml
<service
    android:name=".nativeadapter.NativeHttpService"
    android:exported="false" />
```

- [x] **Step 7：验证测试和构建**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.StaticAssetControllerTest"
.\gradlew.bat --no-daemon :app:assembleDebug
```

Expected:

```text
BUILD SUCCESSFUL
```

**建议提交：**

```text
feat: 建立原生 HTTP 兼容层骨架
```

---

### Task 5：接入 no-node WebView 壳和 transform assets

**目的：** MainActivity 不再启动 Node，不再展示旧控制面板，而是启动原生 HTTP 服务并加载 Web UI。

**Files:**
- Modify: `mobile/app/src/main/java/com/stapk/mobile/MainActivity.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/TavernWebViewClient.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpService.kt`
- Modify: `mobile/app/src/main/AndroidManifest.xml`
- Delete: `mobile/app/src/main/java/com/stapk/mobile/RuntimeManager.kt`
- Delete: `mobile/app/src/main/java/com/stapk/mobile/KeepAliveService.kt`
- Modify: `mobile/app/src/main/res/layout/activity_main.xml`
- Modify: `mobile/app/src/main/res/values/strings.xml`
- Modify: `mobile/app/src/main/assets/`
- Modify: `scripts/stapk-transform-no-node.mjs`
- Modify: `scripts/stapk-verify-no-node-transform.mjs`
- Modify: `test/no-node/no-node-transform.test.mjs`

**Interfaces:**
- Consumes: `NativeHttpService.currentState(): NativeAdapterState`
- Produces: WebView loads `http://127.0.0.1:<port>/`

- [x] **Step 1：复制 no-node transform 输出到 Android assets**

Run:

```powershell
npm run transform:no-node
```

Expected:

```text
assets/sillytavern-web/index.html exists
assets/sillytavern-web/lib.js is a Webpack browser bundle
legacy payload/runtime assets do not exist
```

真实设备验证发现 upstream `public/lib.js` 是 Webpack 入口源码，官方 Node server 会通过 `webpack-serve` 动态返回 bundle。转换器现已在 patched tree 运行 `npm ci --ignore-scripts` 和 upstream `docker/build-lib.js`，再把生成的 `lib.js` 复制到 no-node Web 产物；verifier 会阻断仍含裸模块 import 的源码版 `lib.js`。

- [x] **Step 2：让 service 安装并按 manifest 刷新 web assets**

实际实现使用 `WebAssetSource` 隔离 `AssetManager`，并在 server start 前调用：

```kotlin
installWebAssetsIfNeeded(paths, AndroidWebAssetSource(assets))
```

安装器比较 bundled/installed `stapk-web-manifest.json`；manifest 不同或首页缺失时先写 staging 目录，再替换 `filesDir/web`。JVM 测试覆盖首次安装、相同版本跳过和新版本替换旧文件。

- [x] **Step 3：收敛 layout**

Replace `activity_main.xml` with three root children IDs:

```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:id="@+id/loadingView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center"
        android:text="@string/loading_sillytavern" />

    <WebView
        android:id="@+id/webView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />

    <LinearLayout
        android:id="@+id/errorView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center"
        android:orientation="vertical"
        android:padding="24dp"
        android:visibility="gone">

        <TextView
            android:id="@+id/errorText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/start_failed" />

        <Button
            android:id="@+id/retryButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/retry" />
    </LinearLayout>
</FrameLayout>
```

- [x] **Step 4：新增 strings**

Modify `strings.xml`:

```xml
<string name="loading_sillytavern">正在启动 SillyTavern...</string>
<string name="start_failed">启动失败</string>
<string name="retry">重试</string>
```

- [x] **Step 5：改 MainActivity 为绑定 NativeHttpService**

Replace Node-specific initialization in `MainActivity.onCreate()` with:

```kotlin
private lateinit var webView: WebView
private lateinit var loadingView: View
private lateinit var errorView: View
private var nativeService: com.stapk.mobile.nativeadapter.NativeHttpService? = null

private val connection = object : android.content.ServiceConnection {
    override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
        val local = binder as com.stapk.mobile.nativeadapter.NativeHttpService.LocalBinder
        nativeService = local.service()
        loadWhenReady()
    }

    override fun onServiceDisconnected(name: android.content.ComponentName?) {
        nativeService = null
    }
}

private fun startNativeAdapter() {
    val intent = Intent(this, com.stapk.mobile.nativeadapter.NativeHttpService::class.java)
        .setAction(com.stapk.mobile.nativeadapter.NativeHttpService.ACTION_START)
    startService(intent)
    bindService(intent, connection, BIND_AUTO_CREATE)
}

private fun loadWhenReady() {
    val state = nativeService?.currentState() ?: return
    if (state.status == com.stapk.mobile.nativeadapter.NativeAdapterStatus.RUNNING && state.port != null) {
        loadingView.visibility = View.GONE
        errorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl("http://127.0.0.1:${state.port}/")
    } else if (state.status == com.stapk.mobile.nativeadapter.NativeAdapterStatus.FAILED) {
        loadingView.visibility = View.GONE
        webView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    } else {
        webView.postDelayed({ loadWhenReady() }, 250)
    }
}
```

Ensure `onDestroy()` unbinds service and does not stop Node:

```kotlin
override fun onDestroy() {
    runCatching { unbindService(connection) }
    super.onDestroy()
}
```

实际实现还包括 `ContextCompat.startForegroundService()`、后台线程安装 assets/启动 server、250 ms 状态轮询、失败重试、WebView history 返回、SAF 文件选择和外部 HTTPS 系统浏览器跳转；旧 `RuntimeManager`、`KeepAliveService` 及其 manifest 注册已删除。下载/导出 SAF 桥接不在本 Task 中冒充已完成能力，继续进入端到端验证清单。

- [x] **Step 6：验证无 Node 关键字不再在 MainActivity 主路径出现**

Run:

```powershell
rg -n "RuntimeManager|startSillyTavern|stopSillyTavern|node|payload" mobile/app/src/main/java/com/stapk/mobile/MainActivity.kt
```

Expected:

```text
no matches
```

- [x] **Step 7：构建验证**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:assembleDebug
```

Expected:

```text
BUILD SUCCESSFUL
```

**设备验证记录（2026-07-10，Pixel 8 / Android 15 模拟器）：**

```text
APK install/launch: passed
files/web installed files: 590
files/web/lib.js: 1,947,206 bytes
NativeHttpService: foreground=true, startRequested=true
GET /version: 200, node_runtime=false
GET /: 200, contains <title>SillyTavern</title>
GET /lib.js: 200, 1,947,206 bytes
pidof node: no output
Back -> launcher: passed; foreground service remains alive
Relaunch: passed
```

设备首次验证暴露并修复了源码版 `lib.js` 的裸模块 `lodash` 错误。修复后 Web UI 已执行并稳定显示 `Couldn't get CSRF token`；这是 Task 6 尚未实现 `/csrf-token` 的预期边界，不再属于 Task 5 静态资源或 WebView 壳故障。完整首页、下载/导出 SAF 和业务闭环继续保留到后续 Task。

**建议提交：**

```text
feat: 接入无 Node WebView 启动壳
```

---

### Task 6：实现 settings 和基础系统 endpoint

**目的：** 让官方 Web UI 能完成启动期 settings/ping/version/csrf 请求。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/SettingsController.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/SettingsControllerTest.kt`
- Modify: `mobile/app/build.gradle.kts`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/StaticAssetControllerTest.kt`

**Interfaces:**
- Produces: `class SettingsController(private val paths: NativeAdapterPaths)`
- Produces: `fun getSettings(): HttpResponse`
- Produces: `fun saveSettings(body: String): HttpResponse`
- Consumes: `HttpResponse`

**实施契约校正：** upstream `public/script.js` 会把 `/api/settings/get` 的顶层 `settings` 字段作为 JSON 字符串再次解析，并直接遍历多个预设数组。原计划中的扁平 settings 响应无法通过真实启动流程，因此实际实现返回与 upstream `src/endpoints/settings.js` 一致的 envelope；OpenAI-compatible 字段位于前端实际读取的 `oai_settings` 对象内。

- [x] **Step 1：写失败测试：默认 settings 指向 OpenAI-compatible 且关闭 streaming**

Create `SettingsControllerTest.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SettingsControllerTest {
    @Test
    fun `default settings use openai compatible and disable streaming`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-settings").toFile())
        val controller = SettingsController(paths)

        val response = controller.getSettings()

        assertTrue(response.bodyText!!.contains("\"main_api\":\"openai\""))
        assertTrue(response.bodyText!!.contains("\"chat_completion_source\":\"openai\""))
        assertTrue(response.bodyText!!.contains("\"stream_openai\":false"))
    }
}
```

- [x] **Step 2：运行测试确认失败**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.SettingsControllerTest"
```

Expected:

```text
Compilation error: Unresolved reference: SettingsController
```

- [x] **Step 3：实现 SettingsController**

Create `SettingsController.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import org.json.JSONObject

class SettingsController(private val paths: NativeAdapterPaths) {
    fun getSettings(): HttpResponse {
        paths.userConfigDir.mkdirs()
        if (!paths.settingsFile.exists()) {
            paths.settingsFile.writeText(defaultSettings().toString())
        }
        return HttpResponse.json(200, paths.settingsFile.readText())
    }

    fun saveSettings(body: String): HttpResponse {
        paths.userConfigDir.mkdirs()
        val input = JSONObject(body.ifBlank { "{}" })
        val existing = if (paths.settingsFile.exists()) JSONObject(paths.settingsFile.readText()) else defaultSettings()
        for (key in input.keys()) {
            existing.put(key, input.get(key))
        }
        existing.put("main_api", "openai")
        existing.put("chat_completion_source", existing.optString("chat_completion_source", "openai"))
        existing.put("stream_openai", false)
        paths.settingsFile.writeText(existing.toString())
        return HttpResponse.json(200, """{"result":"ok"}""")
    }

    private fun defaultSettings(): JSONObject = JSONObject()
        .put("main_api", "openai")
        .put("chat_completion_source", "openai")
        .put("stream_openai", false)
        .put("openai_model", "gpt-4o-mini")
        .put("custom_model", "")
        .put("reverse_proxy", "")
}
```

- [x] **Step 4：把系统/settings endpoint 接入 NativeHttpServer**

In `NativeHttpServer`, add:

```kotlin
private val settings = SettingsController(paths)

private fun parseBody(session: IHTTPSession): String {
    val files = HashMap<String, String>()
    session.parseBody(files)
    return files["postData"] ?: ""
}
```

Update route branch:

```kotlin
val response = when (val uri = session.uri) {
    "/version" -> HttpResponse.json(200, """{"agent":"stapk-mobile","node_runtime":false}""")
    "/csrf-token" -> HttpResponse.json(200, """{"token":"stapk-no-node"}""")
    "/api/ping" -> HttpResponse.json(200, """{"pong":true}""")
    "/api/settings/get" -> settings.getSettings()
    "/api/settings/save" -> settings.saveSettings(parseBody(session))
    else -> staticAssets.serve(uri)
}
```

- [x] **Step 5：验证测试通过**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.SettingsControllerTest"
.\gradlew.bat --no-daemon :app:assembleDebug
```

Expected:

```text
BUILD SUCCESSFUL
```

**实施与验收记录（2026-07-11）：**

- `SettingsController` 使用 Gson 2.14.0 让 Android 和 JVM 测试共享同一 JSON 行为；默认 settings 固定 `main_api=openai`、`oai_settings.chat_completion_source=openai` 和 `oai_settings.stream_openai=false`。
- `/api/settings/get` 返回 upstream 所需的 `settings` JSON 字符串、预设数组、extensions/accounts 开关和禁用的 request compression 配置；`/api/settings/save` 递归合并用户设置后重新施加固定 provider/streaming 约束。
- `/version` 从已安装的 `stapk-web-manifest.json` 读取 `pkgVersion`、`gitRevision` 和 `gitBranch`，不在 Kotlin 中硬编码 upstream 版本。
- NanoHTTPD 会在路由前消费 POST body。真实 loopback 测试证明，如果 `/api/ping` 的 `{}` 未消费，keep-alive 连接上的下一次 `GET /` 会收到 400；该问题已纳入回归测试。
- TDD RED 依次覆盖 `SettingsController` 缺失、`saveSettings` 缺失、version 字段缺失和 settings 路由 404；最终 Android JVM 测试为 27/27 通过。
- `:app:assembleDebug`、7/7 no-node 转换测试、transform verifier 和 APK 禁止 Node 资产扫描全部通过，APK 内禁止项为 0。
- 本机 Gradle 8.2 曾卡在 `WatchingVirtualFileSystem -> PosixFileSystemFunctions.listFileSystems`；线程栈确认后，验证命令使用 `--no-watch-fs`，未修改项目运行逻辑。

**设备验证记录（2026-07-11，Pixel 8 / Android 15 模拟器）：**

```text
GET /version: 200, pkgVersion=1.18.0, node_runtime=false
GET /csrf-token: 200, token=stapk-no-node
POST /api/ping: 200, pong=true
POST /api/settings/get: 200, main_api=openai, source=openai, stream_openai=false
settings_loaded_before/settings_loaded_after/settings_loaded: emitted
settings save/restart persistence: passed
pidof/process scan for node: no Node process
next boundary: POST /api/characters/all -> 404 (Task 7)
```

Task 6 已越过原先的 CSRF/settings 启动阻断。Web UI 当前停在角色列表读取阶段，`/api/characters/all` 属于 Task 7，本 Task 不越界实现。

**建议提交：**

```text
feat: 实现无 Node 设置接口
```

---

### Task 7：实现角色和聊天本地存储 endpoint

**目的：** 支持 MVP 本地角色/聊天读写和重启恢复。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/CharacterController.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/ChatController.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/CharacterControllerTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/ChatControllerTest.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/StaticAssetControllerTest.kt`

**Interfaces:**
- Produces: `CharacterController.allCharacters(): HttpResponse`
- Produces: `CharacterController.getCharacter(body: String): HttpResponse`
- Produces: `CharacterController.createCharacter(body: String): HttpResponse`
- Produces: `CharacterController.editCharacter(body: String): HttpResponse`
- Produces: `CharacterController.deleteCharacter(body: String): HttpResponse`
- Produces: `CharacterController.characterChats(body: String): HttpResponse`
- Produces: `ChatController.getChat(body: String): HttpResponse`
- Produces: `ChatController.saveChat(body: String): HttpResponse`
- Produces: `ChatController.deleteChat(body: String): HttpResponse`
- Produces: `ChatController.searchChats(body: String): HttpResponse`

**实施契约校正：** upstream 角色 create/edit 使用 `multipart/form-data`，对外 avatar identity 为 `.png`，`/api/characters/chats` 返回数组；聊天 delete 使用 `chatfile`，search 还要求消息数、预览、时间和文件大小。原计划中的 JSON-only HTTP、`.json` avatar 和对象 map 响应不能被官方 UI 正常使用。实际实现保持“JSON 角色数据 + 默认头像”的设计边界，在 HTTP 层把 multipart 参数转换为控制器 JSON，并提供默认 PNG/thumbnail 路由。

- [x] **Step 1：写失败测试：创建角色后可列出**

Create `CharacterControllerTest.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CharacterControllerTest {
    @Test
    fun `created character appears in all characters`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-character").toFile())
        val controller = CharacterController(paths)

        controller.createCharacter("""{"name":"Alice","description":"test"}""")
        val response = controller.allCharacters()

        assertTrue(response.bodyText!!.contains("Alice"))
        assertTrue(response.bodyText!!.contains("alice.json"))
    }
}
```

- [x] **Step 2：写失败测试：保存聊天后可读取**

Create `ChatControllerTest.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ChatControllerTest {
    @Test
    fun `saved chat can be read back`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-chat").toFile())
        val controller = ChatController(paths)

        controller.saveChat("""{"avatar_url":"alice.json","file_name":"hello","chat":[{"name":"User","is_user":true,"mes":"Hi"}]}""")
        val response = controller.getChat("""{"avatar_url":"alice.json","file_name":"hello"}""")

        assertTrue(response.bodyText!!.contains("Hi"))
    }
}
```

- [x] **Step 3：运行测试确认失败**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.CharacterControllerTest" --tests "com.stapk.mobile.nativeadapter.ChatControllerTest"
```

Expected:

```text
Compilation error: Unresolved reference: CharacterController
```

- [x] **Step 4：实现 CharacterController MVP**

Create `CharacterController.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class CharacterController(private val paths: NativeAdapterPaths) {
    fun allCharacters(): HttpResponse {
        paths.charactersDir.mkdirs()
        val array = JSONArray()
        paths.charactersDir.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?.forEach { array.put(JSONObject(it.readText())) }
        return HttpResponse.json(200, array.toString())
    }

    fun getCharacter(body: String): HttpResponse {
        val avatar = JSONObject(body).optString("avatar_url")
        val file = File(paths.charactersDir, avatar)
        if (!file.exists()) return HttpResponse.text(404, "Character not found")
        return HttpResponse.json(200, file.readText())
    }

    fun createCharacter(body: String): HttpResponse {
        paths.charactersDir.mkdirs()
        val input = JSONObject(body.ifBlank { "{}" })
        val name = input.optString("name", "New Character")
        val fileName = slug(name) + ".json"
        val character = JSONObject()
            .put("name", name)
            .put("description", input.optString("description", ""))
            .put("avatar", fileName)
            .put("chat", "$name - Chat")
            .put("data", input)
        File(paths.charactersDir, fileName).writeText(character.toString())
        return HttpResponse.json(200, character.toString())
    }

    fun editCharacter(body: String): HttpResponse {
        val input = JSONObject(body.ifBlank { "{}" })
        val avatar = input.optString("avatar_url", input.optString("avatar"))
        val file = File(paths.charactersDir, avatar)
        if (!file.exists()) return HttpResponse.text(404, "Character not found")
        val existing = JSONObject(file.readText())
        for (key in input.keys()) existing.put(key, input.get(key))
        file.writeText(existing.toString())
        return HttpResponse.json(200, existing.toString())
    }

    fun deleteCharacter(body: String): HttpResponse {
        val avatar = JSONObject(body).optString("avatar_url")
        File(paths.charactersDir, avatar).delete()
        return HttpResponse.json(200, """{"result":"ok"}""")
    }

    fun characterChats(body: String): HttpResponse {
        val avatar = JSONObject(body).optString("avatar_url").removeSuffix(".json")
        val dir = File(paths.chatsDir, avatar)
        val result = JSONObject()
        dir.listFiles { file -> file.extension == "jsonl" }
            ?.sortedBy { it.name }
            ?.forEach { file -> result.put(file.name, JSONObject().put("file_name", file.name).put("last_mes", "")) }
        return HttpResponse.json(200, result.toString())
    }

    private fun slug(value: String): String =
        value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "character" }
}
```

- [x] **Step 5：实现 ChatController MVP**

Create `ChatController.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ChatController(private val paths: NativeAdapterPaths) {
    fun getChat(body: String): HttpResponse {
        val request = JSONObject(body)
        val file = chatFile(request)
        if (!file.exists()) return HttpResponse.json(200, "[]")
        val array = JSONArray()
        file.readLines().filter { it.isNotBlank() }.forEach { array.put(JSONObject(it)) }
        return HttpResponse.json(200, array.toString())
    }

    fun saveChat(body: String): HttpResponse {
        val request = JSONObject(body)
        val file = chatFile(request)
        file.parentFile?.mkdirs()
        val chat = request.optJSONArray("chat") ?: JSONArray()
        file.writeText((0 until chat.length()).joinToString("\n") { i -> chat.getJSONObject(i).toString() })
        return HttpResponse.json(200, """{"result":"ok"}""")
    }

    fun deleteChat(body: String): HttpResponse {
        chatFile(JSONObject(body)).delete()
        return HttpResponse.json(200, """{"result":"ok"}""")
    }

    fun searchChats(body: String): HttpResponse {
        val query = JSONObject(body).optString("query", "")
        val result = JSONArray()
        paths.chatsDir.walkTopDown().filter { it.isFile && it.extension == "jsonl" }.forEach { file ->
            if (query.isBlank() || file.readText().contains(query, ignoreCase = true)) {
                result.put(JSONObject().put("file_name", file.nameWithoutExtension))
            }
        }
        return HttpResponse.json(200, result.toString())
    }

    private fun chatFile(request: JSONObject): File {
        val avatar = request.optString("avatar_url", "default").removeSuffix(".json")
        val fileName = request.optString("file_name", "default")
        return File(File(paths.chatsDir, avatar), "$fileName.jsonl")
    }
}
```

- [x] **Step 6：接入 NativeHttpServer 路由**

Add fields:

```kotlin
private val characters = CharacterController(paths)
private val chats = ChatController(paths)
```

Add route branches:

```kotlin
"/api/characters/all" -> characters.allCharacters()
"/api/characters/get" -> characters.getCharacter(parseBody(session))
"/api/characters/create" -> characters.createCharacter(parseBody(session))
"/api/characters/edit" -> characters.editCharacter(parseBody(session))
"/api/characters/delete" -> characters.deleteCharacter(parseBody(session))
"/api/characters/chats" -> characters.characterChats(parseBody(session))
"/api/chats/get" -> chats.getChat(parseBody(session))
"/api/chats/save" -> chats.saveChat(parseBody(session))
"/api/chats/delete" -> chats.deleteChat(parseBody(session))
"/api/chats/search" -> chats.searchChats(parseBody(session))
```

- [x] **Step 7：验证测试和构建**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.CharacterControllerTest" --tests "com.stapk.mobile.nativeadapter.ChatControllerTest"
.\gradlew.bat --no-daemon :app:assembleDebug
```

Expected:

```text
BUILD SUCCESSFUL
```

**实施与验收记录（2026-07-11）：**

- 角色磁盘数据使用 `filesDir/user_data/characters/<stem>.json`，HTTP/UI identity 使用 `<stem>.png`；`/characters/<avatar>` 和 avatar thumbnail 返回 bundled `img/ai4.png` 默认头像。
- Character Card 使用 V2 基础字段，create/edit 保留 `json_data` 中未知字段，支持 system prompt、post-history、alternate greetings、depth prompt 和 extensions；get 响应包含 upstream 编辑表单需要的 `json_data` 原文。
- create/edit 接受真实 upstream multipart 表单；重复角色名自动生成不覆盖的 avatar stem。
- 聊天保持 JSONL，支持 get/save/delete/search；character chats 返回 upstream 数组摘要，search 返回 `file_name`、`file_size`、`message_count`、`last_mes`、`preview_message`。
- avatar、角色、聊天文件名均经过白名单/非法字符校验，目录穿越请求返回 400；删除聊天目录只使用已校验 avatar stem 派生的 app 私有路径。
- TDD 覆盖角色创建/重复名/edit/delete/avatar/traversal/V2 保留、聊天 JSONL/search/delete/traversal、multipart 和完整 loopback 生命周期。
- Android JVM 测试为 36/36 通过；2026-07-11 使用 `--rerun-tasks` 强制重跑时 41 个 Gradle task 全部实际执行。`:app:assembleDebug`、7/7 no-node 测试、transform verifier、APK 禁止资产扫描全部通过。
- 最终复验 APK 为 `mobile/app/build/outputs/apk/debug/app-debug.apk`，大小 21,780,969 bytes，SHA-256 为 `770CB29A2EC45AF38941AA78C8C9DEAE65495F4A13426803AD6D9702F6BE1A51`，禁止 Node 资产扫描结果为 0。

**设备验证记录（2026-07-11，Pixel 8 / Android 15 模拟器）：**

```text
old com.stapk.mobile uninstall: passed
clean APK install/launch: passed
official SillyTavern home app_ready: passed
multipart character create: device_alice.png
GET character/default avatar: passed, avatar=53,230 bytes
chat save/get/search/character summary: passed
app restart persistence: character count=1, chat items=2, last message=Persist me
official Character Management: Device Alice visible
official character editor: name and multipart description loaded
Node process count: 0
```

最终 APK 强制重建后再次执行了“卸载旧包 -> 安装 -> 启动”的干净复验：创建 `final_device_alice.png` 和 `final-device-chat` 成功，终止并重启 app 后角色数仍为 1、聊天仍为 2 条、末条消息仍为 `Persist final APK`，Node 进程数为 0。

当前剩余非阻断请求包括 welcome 页 `/api/chats/recent` 和 tokenizer/token count API；它们不属于本 Task 的 endpoint 清单，留给 Task 8/9 契约收敛。设备复验重复出现 WebView 黑色 surface：后台 console/API 已就绪且数据正常，HOME 后重新打开可恢复首页，但后续 WebView 交互仍可能再次触发黑屏。该问题不判定为角色/聊天数据回归，但必须在 Task 9 或最终设备收口前单独修复并回归。

**建议提交：**

```text
feat: 实现角色和聊天本地存储接口
```

---

### Task 8：实现 secrets 和 OpenAI-compatible 非 streaming 生成

**目的：** 完成 MVP 真实对话闭环。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/SecretStore.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/OpenAiCompatibleController.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/SecretStoreTest.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/OpenAiCompatibleControllerTest.kt`
- Modify: `mobile/app/build.gradle.kts`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`

**Interfaces:**
- Produces: `class SecretStore(private val paths: NativeAdapterPaths)`
- Produces: `fun write(key: String, value: String, label: String): String?`
- Produces: `fun load(key: String): StoredSecret?`
- Produces: `fun delete(key: String, id: String?): Boolean`
- Produces: `fun readStateJson(): String`
- Produces: `class OpenAiCompatibleController(paths: NativeAdapterPaths, client: OkHttpClient = OkHttpClient())`
- Produces: `fun readSecrets(): HttpResponse`
- Produces: `fun writeSecret(body: String): HttpResponse`
- Produces: `fun deleteSecret(body: String): HttpResponse`
- Produces: `fun status(body: String): HttpResponse`
- Produces: `fun generate(body: String): HttpResponse`

**实施契约校正：** upstream 1.18.0 官方 UI 在启动时调用 `/api/secrets/read`，连接时通过 `/api/secrets/write` 写入 `api_key_openai` 或 `api_key_custom`，清理时调用 `/api/secrets/delete`；`/api/secrets/read` 返回每个 key 对应的 `{id,label,value,active}` 数组，`value` 必须脱敏。base URL 和 model 来自前端 settings 生成请求中的 `reverse_proxy`/`model` 或 `custom_url`/`model`，不能依赖一个官方 UI 不会调用的私有保存 endpoint。实际实现必须支持 `openai` 与 `custom` 两种 OpenAI-compatible 请求形状，强制向 provider 发送 `stream=false`，只转发 Chat Completions 标准字段，并过滤 `chat_completion_source`、`reverse_proxy`、`proxy_password`、`custom_url` 等本地控制字段。`status` 访问 `<baseUrl>/models` 并返回前端可读取的 `data` 数组；`generate` 访问 `<baseUrl>/chat/completions` 并原样返回成功 JSON。provider 错误需要保留可理解的 HTTP 状态与脱敏消息，任何响应、日志和 settings 均不得包含完整 API key。

- [x] **Step 1：添加 OkHttp 依赖**

Modify `mobile/app/build.gradle.kts`:

```kotlin
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

- [x] **Step 2：写失败测试：SecretStore 响应不泄露 key**

Create `SecretStoreTest.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SecretStoreTest {
    @Test
    fun `secret status is redacted`() {
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-secret").toFile())
        val store = SecretStore(paths)
        store.saveOpenAiSecret("sk-secret", "https://api.openai.com/v1", "gpt-4o-mini")

        val status = store.openAiStatusJson()

        assertTrue(status.contains("\"has_key\":true"))
        assertFalse(status.contains("sk-secret"))
    }
}
```

- [x] **Step 3：实现 SecretStore**

Create `SecretStore.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import org.json.JSONObject

data class OpenAiSecret(
    val apiKey: String,
    val baseUrl: String,
    val model: String
)

class SecretStore(private val paths: NativeAdapterPaths) {
    private val file get() = java.io.File(paths.secretsDir, "openai-compatible.json")

    fun saveOpenAiSecret(apiKey: String, baseUrl: String, model: String) {
        paths.secretsDir.mkdirs()
        file.writeText(JSONObject()
            .put("api_key", apiKey)
            .put("base_url", baseUrl.trimEnd('/'))
            .put("model", model)
            .toString())
    }

    fun loadOpenAiSecret(): OpenAiSecret? {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        return OpenAiSecret(
            apiKey = json.optString("api_key"),
            baseUrl = json.optString("base_url", "https://api.openai.com/v1").trimEnd('/'),
            model = json.optString("model", "gpt-4o-mini")
        )
    }

    fun openAiStatusJson(): String {
        val secret = loadOpenAiSecret()
        return JSONObject()
            .put("has_key", !secret?.apiKey.isNullOrBlank())
            .put("base_url", secret?.baseUrl ?: "")
            .put("model", secret?.model ?: "gpt-4o-mini")
            .toString()
    }
}
```

- [x] **Step 4：写失败测试：OpenAI 请求体使用 chat/completions**

Create `OpenAiCompatibleControllerTest.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class OpenAiCompatibleControllerTest {
    @Test
    fun `generate posts to chat completions and returns choices`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Hello"}}]}""").setResponseCode(200))
        server.start()
        val paths = NativeAdapterPaths(Files.createTempDirectory("stapk-openai").toFile())
        SecretStore(paths).saveOpenAiSecret("sk-test", server.url("/v1").toString(), "gpt-test")
        val controller = OpenAiCompatibleController(paths, OkHttpClient())

        val response = controller.generate("""{"messages":[{"role":"user","content":"Hi"}]}""")
        val request = server.takeRequest()

        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        assertTrue(response.bodyText!!.contains("Hello"))
        server.shutdown()
    }
}
```

Add test dependency:

```kotlin
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

- [x] **Step 5：实现 OpenAiCompatibleController**

Create `OpenAiCompatibleController.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenAiCompatibleController(
    paths: NativeAdapterPaths,
    private val client: OkHttpClient = OkHttpClient()
) {
    private val secrets = SecretStore(paths)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun status(body: String): HttpResponse {
        return HttpResponse.json(200, secrets.openAiStatusJson())
    }

    fun saveSecret(body: String): HttpResponse {
        val json = JSONObject(body.ifBlank { "{}" })
        secrets.saveOpenAiSecret(
            apiKey = json.optString("api_key"),
            baseUrl = json.optString("base_url", "https://api.openai.com/v1"),
            model = json.optString("model", "gpt-4o-mini")
        )
        return HttpResponse.json(200, """{"result":"ok"}""")
    }

    fun generate(body: String): HttpResponse {
        val secret = secrets.loadOpenAiSecret()
            ?: return HttpResponse.json(400, """{"error":true,"message":"缺少 OpenAI-compatible API key"}""")
        val input = JSONObject(body.ifBlank { "{}" })
        val payload = JSONObject()
            .put("model", input.optString("model", secret.model))
            .put("stream", false)
            .put("messages", input.optJSONArray("messages") ?: JSONArray())
        if (input.has("temperature")) payload.put("temperature", input.get("temperature"))
        if (input.has("max_tokens")) payload.put("max_tokens", input.get("max_tokens"))

        val request = Request.Builder()
            .url("${secret.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${secret.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://github.com/zaixiakongyiji/stapk-termux")
            .addHeader("X-Title", "stAPK Mobile")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return HttpResponse.json(response.code, JSONObject()
                    .put("error", true)
                    .put("message", responseText.ifBlank { response.message })
                    .toString())
            }
            return HttpResponse.json(200, responseText)
        }
    }
}
```

- [x] **Step 6：接入 NativeHttpServer 路由**

Add field:

```kotlin
private val openAi = OpenAiCompatibleController(paths)
```

Add route branches:

```kotlin
"/api/backends/chat-completions/status" -> openAi.status(parseBody(session))
"/api/backends/chat-completions/generate" -> openAi.generate(parseBody(session))
"/api/secrets/openai-compatible/save" -> openAi.saveSecret(parseBody(session))
```

If frontend expects different secrets endpoint after API scan, add that exact route here and keep `/api/secrets/openai-compatible/save` as internal fallback.

- [x] **Step 7：验证**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.SecretStoreTest" --tests "com.stapk.mobile.nativeadapter.OpenAiCompatibleControllerTest"
.\gradlew.bat --no-daemon :app:assembleDebug
```

Expected:

```text
BUILD SUCCESSFUL
```

**实施与验收记录（2026-07-11）：**

- 新增 OkHttp 4.12.0 和 MockWebServer 4.12.0；`SecretStore` 只在 `filesDir/secrets/openai-compatible.json` 保存 `api_key_openai`/`api_key_custom`，每次写入生成唯一 ID、保留历史记录并切换 active，read 只返回 masked value，delete 支持按 ID 或 active 幂等删除。
- 官方 `/api/secrets/read`、`/api/secrets/write`、`/api/secrets/delete` 已接入；settings 保存和读取会递归清除 `api_key_*`、`proxy_password`、`custom_include_headers`，旧 settings 中残留 secret 也不会回显。
- settings 只保留 `openai`/`custom` source 并固定 `stream_openai=false`；custom 支持 keyless provider，有 key 时才发送 `Authorization`。
- `status` 请求 `<baseUrl>/models`；`generate` 只转发 Chat Completions 标准字段，请求 provider 时强制覆盖 `stream=false`，成功响应保持 provider JSON，错误响应保留 401、422、429 等状态并脱敏。
- 非 string JSON 字段稳定返回 400；网络异常返回可理解的 502 JSON。NanoHTTPD 未内置的 provider 状态使用自定义 `IStatus`，不再折叠为 500。
- no-node allowlist 已同步 Task 6-8 的 17 个精确 endpoint，删除 broad `/api/secrets` hidden prefix；实际 Web 资源扫描得到 `implemented=14`、`unsupported_hidden=23`、`needs_review=217`，完整 transform 产物将在 Task 9 应用 patch queue 时重新生成。
- 独立首次审查发现的 settings key 注入、custom keyless、secret 多记录、422 透传和 JSON 类型问题均已增加 RED 回归并修复。二次独立 re-review 因子代理账户额度限制未执行，主线程已逐文件复核并强制重跑测试，但不得记为 `review clean`。

**自动化验证：**

```text
Gradle --rerun-tasks: 41 tasks executed, BUILD SUCCESSFUL
Android JVM tests: 46/46, failures=0, errors=0, skipped=0
no-node tests: 9/9
transform verifier: passed
APK forbidden Node assets: 0
APK: 22,357,670 bytes
SHA-256: C7C3D4671B928EAA036522FB7CF2B162633383407DBC5585E1E13AAF4A2BF510
```

**设备验证记录（Pixel 8 / Android 15 模拟器）：**

```text
old com.stapk.mobile uninstall: passed
clean APK install/launch/app_ready: passed
provider status path: GET /v1/models
provider generate path: POST /v1/chat/completions
provider model: device-test-model
provider received stream: false
provider reply: Device non-stream reply
settings response secret leaks: 0
persisted settings secret leaks: 0
secrets/read exposes full key: false
logcat full key occurrences: 0
app restart without rewriting settings/key: reply passed
Node process count: 0
```

设备验收使用本机启动并通过 `adb reverse` 暴露给模拟器的 OpenAI-compatible 测试 provider，验证了真实 HTTP、Bearer、models、Chat Completions、非 streaming 和重启持久化链路。后续 `docs/plan/2026-07-12-stapk-single-user-feature-completion-plan.md` Step 9 已于 2026-07-17 使用真实外部 custom provider 补齐外部服务差异验收。

**建议提交：**

```text
feat: 支持 OpenAI-compatible 非流式对话
```

---

### Task 9：增加 Android platform patch queue

**目的：** 让官方 Web UI 在 no-node MVP 下默认只暴露可支持功能，减少未实现 endpoint 被普通用户触发。

**Files:**
- Create: `patches/sillytavern-no-node/series`
- Create: `patches/sillytavern-no-node/0001-stapk-mobile-default-openai-compatible.patch`
- Create: `patches/sillytavern-no-node/0002-stapk-mobile-hide-unsupported-mvp-features.patch`
- Modify: `scripts/stapk-transform-no-node.mjs`
- Modify: `transform-report.json` generation in transform script

**Interfaces:**
- Consumes: upstream checkout from transform.
- Produces: patched `public/` before copying to `sillytavern-web/`.

- [x] **Step 1：创建 patch series**

Create `patches/sillytavern-no-node/series`:

```text
0001-stapk-mobile-default-openai-compatible.patch
0002-stapk-mobile-hide-unsupported-mvp-features.patch
```

- [x] **Step 2：先手工生成最小 patch 内容**

Patch 1 目标：在前端默认 settings 中确保：

```text
main_api = openai
chat_completion_source = openai
stream_openai = false
```

Patch 2 目标：隐藏非 MVP provider/extension/world info/image/TTS/STT/vector 入口。具体文件位置必须基于 `rg -n "extensions|world|stable-diffusion|tts|chat_completion_source" build/stapk-no-node/upstream/public` 的结果确定。

- [x] **Step 3：transform 脚本应用 no-node patch queue**

In `stapk-transform-no-node.mjs`, before copying `public/`, add:

```js
async function applyPatchQueue(upstreamDir) {
  const seriesPath = path.resolve('patches/sillytavern-no-node/series');
  if (!existsSync(seriesPath)) return [];
  const patches = (await fs.readFile(seriesPath, 'utf8'))
    .split(/\r?\n/)
    .map(x => x.trim())
    .filter(Boolean);
  for (const patch of patches) {
    run(`git apply --3way ${path.resolve('patches/sillytavern-no-node', patch)}`, upstreamDir);
  }
  return patches;
}
```

Call:

```js
const appliedPatches = await applyPatchQueue(upstreamDir);
```

Add to report:

```js
patches: appliedPatches
```

- [x] **Step 4：验证 patch 应用**

Run:

```powershell
npm run transform:no-node
Get-Content build/no-node-payload/transform-report.json
```

Expected:

```text
transform-report.json includes both patch filenames
```

- [x] **Step 5：验证 unsupported API 未进入普通启动路径**

Run:

```powershell
Get-Content build/no-node-payload/api-contract.json | Select-String -Pattern '"status": "needs_review"|"status": "unsupported"'
```

Expected:

```text
Only hidden or non-MVP UI paths remain; no settings/characters/chats/openai MVP endpoint is unsupported
```

**执行记录（2026-07-11）：**

- 新增固定 `series` 和两项 patch。Patch 1 在构建工作树内强制 `main_api=openai`，把旧 provider 归一化为 `openai/custom` 并关闭 streaming；Patch 2 删除非 MVP 主 API/provider 选项和 lorebook `<option>`，通过独立 `stapk-mobile.css` 隐藏 World Info、Extensions、image、TTS、STT、vector 及 welcome 动态入口。upstream checkout 未被修改。
- `applyPatchQueue()` 现在按 `series` 顺序返回 patch 文件名和队列 SHA-256；manifest 记录 `patchQueueSha256`，transform report 记录两项 patch 文件名。两项 patch 均通过 pinned upstream `8172dcd0ee672d3cd9a5e5f7af134f91a45cd2b8` 的 `git apply --check --3way`。
- 从 `--clean` 完整执行 `npm run transform:no-node` 后，Webpack 构建、no-node verifier 和 Android assets 同步成功。最终 contract 为 `implemented=14`、`unsupported_hidden=23`、`needs_review=217`；`needs_review` 是对仍保留的上游静态代码做源码扫描的结果，UI 隐藏不会删除这些 fetch。Task 9 的验收边界调整为：MVP provider/endpoint 不被误归类，普通 UI 不暴露非 MVP 入口，不把 `needs_review=0` 作为本 Task 条件。
- `npm run test:no-node` 为 15/15，Android JVM 测试为 46/46，`testDebugUnitTest assembleDebug --rerun-tasks` 的 41 个 task 全部成功；APK assets 中 Node/runtime/payload 禁止项为 0。
- 在 Pixel 8 / Android 15 模拟器先卸载旧 app 后安装最终 APK，确认欢迎页不再显示 Extensions，顶栏不再显示 World Info/Extensions，API 面板不显示主 API 类型切换，Chat Completion source 原生下拉只有 `OpenAI` 和 `Custom (OpenAI-compatible)`；ADB 确认 Node 进程数为 0，最近日志无 app fatal。
- 设备仍可偶发数秒 WebView 黑色 surface，等待后会恢复；该现象未阻断本 Task 的 UI 收敛验证，但必须在最终设备收口继续跟踪。
- Task 9 完成前的独立只读入口审查已发现并推动修复主 API、persona/group lorebook、SD/TTS 消息按钮和 welcome Extensions 遗漏；修复后的最终独立复审因平台用量额度未能启动，不记录为 review clean。

**建议提交：**

```text
feat: 添加无 Node Android 前端适配补丁队列
```

---

### Task 10：实现旧数据迁移（停止执行）

> 本 Task 不再属于项目主体完成范围。以下内容仅保留为历史方案，不得据此创建迁移 manager 或接入启动流程；如果项目主体完成后决定开发迁移，必须重新生成独立设计和实施计划。

**目的：** 从旧 Node 容器数据目录迁移 settings、characters、chats，失败时保留旧数据。

**Files:**
- Create: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/LegacyMigrationManager.kt`
- Create: `mobile/app/src/test/java/com/stapk/mobile/nativeadapter/LegacyMigrationManagerTest.kt`
- Modify: `mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpService.kt`

**Interfaces:**
- Produces: `class LegacyMigrationManager(private val paths: NativeAdapterPaths)`
- Produces: `fun migrateIfNeeded(): MigrationResult`
- Consumes: `NativeAdapterPaths`

- [ ] **Step 1：写失败测试：legacy data 复制到 user_data**

Create `LegacyMigrationManagerTest.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LegacyMigrationManagerTest {
    @Test
    fun `migrates legacy data without deleting source`() {
        val root = Files.createTempDirectory("stapk-migration").toFile()
        val paths = NativeAdapterPaths(root)
        val legacyData = File(paths.legacySillyTavernDir, "data")
        legacyData.mkdirs()
        File(legacyData, "settings.json").writeText("""{"main_api":"openai"}""")

        val result = LegacyMigrationManager(paths).migrateIfNeeded()

        assertEquals(MigrationStatus.MIGRATED, result.status)
        assertTrue(File(paths.userDataDir, "settings.json").exists())
        assertTrue(File(legacyData, "settings.json").exists())
    }
}
```

- [ ] **Step 2：实现 MigrationResult 和 manager**

Create `LegacyMigrationManager.kt`:

```kotlin
package com.stapk.mobile.nativeadapter

import java.io.File

enum class MigrationStatus {
    NOT_NEEDED,
    MIGRATED,
    ALREADY_DONE,
    FAILED
}

data class MigrationResult(
    val status: MigrationStatus,
    val migratedFiles: List<String>,
    val message: String
)

class LegacyMigrationManager(private val paths: NativeAdapterPaths) {
    private val doneMarker = File(paths.stateDir, "migration-node-container-to-no-node.done")

    fun migrateIfNeeded(): MigrationResult {
        if (doneMarker.exists()) return MigrationResult(MigrationStatus.ALREADY_DONE, emptyList(), "already migrated")
        val legacyData = File(paths.legacySillyTavernDir, "data")
        if (!legacyData.exists()) return MigrationResult(MigrationStatus.NOT_NEEDED, emptyList(), "no legacy data")

        return try {
            paths.stateDir.mkdirs()
            val work = File(paths.stateDir, "migration-work")
            work.deleteRecursively()
            copyRecursivelyChecked(legacyData, work)
            paths.userDataDir.mkdirs()
            copyRecursivelyChecked(work, paths.userDataDir)
            doneMarker.writeText(System.currentTimeMillis().toString())
            val files = paths.userDataDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(paths.userDataDir).path }.toList()
            MigrationResult(MigrationStatus.MIGRATED, files, "migrated")
        } catch (error: Exception) {
            MigrationResult(MigrationStatus.FAILED, emptyList(), error.message ?: "migration failed")
        }
    }

    private fun copyRecursivelyChecked(source: File, target: File) {
        source.copyRecursively(target, overwrite = true)
        if (source.isFile && !target.exists()) error("copy failed: ${source.path}")
    }
}
```

- [ ] **Step 3：在 service 启动前运行迁移**

In `NativeHttpService.startServer()` before `installWebAssetsIfNeeded(paths)`:

```kotlin
state = NativeAdapterState(NativeAdapterStatus.MIGRATING)
val migration = LegacyMigrationManager(paths).migrateIfNeeded()
if (migration.status == MigrationStatus.FAILED) {
    state = NativeAdapterState(NativeAdapterStatus.MIGRATION_FAILED, message = migration.message)
    return
}
```

- [ ] **Step 4：验证**

Run:

```powershell
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.LegacyMigrationManagerTest"
.\gradlew.bat --no-daemon :app:assembleDebug
```

Expected:

```text
BUILD SUCCESSFUL
```

**建议提交：**

```text
feat: 添加旧数据到无 Node 目录迁移
```

---

### Task 11：CI/Release 切换到 no-node transform（剩余工作已迁移）

> 已落地部分保留为历史记录；未完成的 release metadata、严格 capability 门禁和一键构建由 `docs/plan/2026-07-12-stapk-single-user-feature-completion-plan.md` Task 11-12 接管。

**目的：** CI 和 Release 使用 no-node web assets 构建 APK，不再使用旧 payload/runtime 作为新路线输入。

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `mobile/app/src/main/assets/`
- Modify: `.gitattributes`

**Interfaces:**
- Consumes: `npm run transform:no-node`
- Consumes: `npm run transform:no-node:verify`
- Produces: APK artifact plus `api-contract.json`、`stapk-web-manifest.json`、`transform-report.json`

- [x] **Step 1：CI 运行 no-node transform**

Replace old transform step in `.github/workflows/ci.yml` with:

```yaml
- name: 生成 no-Node SillyTavern Web 产物
  shell: bash
  run: |
    START_TIME=$(date +%s)
    npm ci
    npm run test:no-node
    npm run transform:no-node
    npm run transform:no-node:verify
    END_TIME=$(date +%s)
    echo "no_node_transform_seconds=$((END_TIME - START_TIME))" >> "$GITHUB_STEP_SUMMARY"
```

- [x] **Step 2：CI artifact 上传 no-node 报告**

Add upload artifact paths:

```yaml
path: |
  build/no-node-payload/api-contract.json
  build/no-node-payload/stapk-web-manifest.json
  build/no-node-payload/transform-report.json
```

- [x] **Step 3：Release 同步 no-node transform**

In `.github/workflows/release.yml`, before Gradle release build:

```yaml
- name: 生成 no-Node Release Web 产物
  shell: bash
  run: |
    npm ci
    npm run test:no-node
    npm run transform:no-node
    npm run transform:no-node:verify
```

- [ ] **Step 4：Release assets 包含 no-node metadata**

Add to release asset preparation:

```bash
cp build/no-node-payload/api-contract.json "release-assets/api-contract_${RELEASE_TAG}.json"
cp build/no-node-payload/stapk-web-manifest.json "release-assets/stapk-web-manifest_${RELEASE_TAG}.json"
cp build/no-node-payload/transform-report.json "release-assets/transform-report_${RELEASE_TAG}.json"
```

Add to release files:

```yaml
release-assets/api-contract_${{ github.ref_name }}.json
release-assets/stapk-web-manifest_${{ github.ref_name }}.json
release-assets/transform-report_${{ github.ref_name }}.json
```

- [ ] **Step 5：处理 LFS 规则**

确认 `mobile/app/src/main/assets/payload.tgz`、Node runtime ZIP 及其 checksum 已删除；`.gitattributes` 中历史 LFS pattern 可以保留为历史兼容规则，但不得导致这些文件重新进入 APK。

- [ ] **Step 6：本地 CI 等价验证**

Run:

```powershell
npm ci
npm run test:no-node
npm run transform:no-node
npm run transform:no-node:verify
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon :app:assembleDebug
```

Expected:

```text
Node tests pass
no-node transform verification passed
Gradle test and assemble succeed
```

**建议提交：**

```text
ci: 使用无 Node 转换产物构建 APK
```

---

### Task 12：端到端验证记录和文档收口（已被替代）

> MVP 设备证据保留；项目主体完成所需的完整能力矩阵、Android 版本矩阵、SAF、备份恢复和发布文档由新计划 Task 12 接管。

**目的：** 提供可审计证据，证明新路线不是 Node 容器、WebView 可加载、MVP 数据和对话闭环可运行。

**Files:**
- Create: `docs/plan/2026-07-09-stapk-no-node-native-adapter-validation-record.md`
- Modify: `docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md`
- Modify: `README.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: all previous tasks.
- Produces: validation record.

- [ ] **Step 1：创建验证记录模板**

Create `docs/plan/2026-07-09-stapk-no-node-native-adapter-validation-record.md`:

```markdown
# stAPK 无 Node 原生适配验证记录

日期：2026-07-09
范围：no-node transform、Android 原生 HTTP 兼容层、WebView、settings/characters/chats/OpenAI-compatible MVP

## 本地验证

| 命令 | 结果 | 备注 |
| --- | --- | --- |
| `npm run test:no-node` | 未执行 | 等待记录 |
| `npm run transform:no-node` | 未执行 | 等待记录 |
| `npm run transform:no-node:verify` | 未执行 | 等待记录 |
| `.\gradlew.bat --no-daemon :app:testDebugUnitTest` | 未执行 | 等待记录 |
| `.\gradlew.bat --no-daemon :app:assembleDebug` | 未执行 | 等待记录 |

## 无 Node 资产验证

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| APK assets 不含 Node runtime | 未验证 | 等待记录 |
| APK assets 不含 `node_modules` | 未验证 | 等待记录 |
| 运行时不启动 `node` | 未验证 | 等待记录 |

## 设备验证

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| WebView 加载官方 UI | 未验证 | 等待记录 |
| WebView 下载/导出通过 SAF 落盘 | 未验证 | 等待记录 |
| settings 保存恢复 | 未验证 | 等待记录 |
| character 创建恢复 | 未验证 | 等待记录 |
| chat 保存恢复 | 未验证 | 等待记录 |
| OpenAI-compatible 生成回复 | 未验证 | 等待记录 |
```

- [ ] **Step 2：执行本地验证**

Run:

```powershell
git diff --check
npm run test:no-node
npm run transform:no-node
npm run transform:no-node:verify
Set-Location mobile
.\gradlew.bat --no-daemon :app:testDebugUnitTest
.\gradlew.bat --no-daemon :app:assembleDebug
```

Update validation record with exact pass/fail result.

- [ ] **Step 3：执行无 Node 资产验证**

Run:

```powershell
Get-ChildItem -Recurse mobile/app/src/main/assets | Select-String -Pattern 'node_modules|runtime-android|payload.tgz'
```

Expected for no-node release assets:

```text
no matches under active no-node copied assets
```

If historical files still exist in assets, record them as historical blocked items and do not claim APK is no-node release-ready until they are removed or excluded from packaging.

- [ ] **Step 4：执行设备验证**

Run:

```powershell
adb install -r mobile/app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.stapk.mobile
adb shell am start -n com.stapk.mobile/.MainActivity
adb shell pidof node
adb shell run-as com.stapk.mobile ls files
adb shell run-as com.stapk.mobile ls files/web
adb shell run-as com.stapk.mobile ls files/user_data
```

Expected:

```text
pidof node has no output
files/web exists
files/user_data exists after data operations
```

- [ ] **Step 5：记录 OpenAI-compatible 人工验证**

Manual steps:

```text
1. 打开 App。
2. 配置 OpenAI-compatible API key/base URL/model。
3. 创建角色。
4. 发送 “Hello”。
5. 等待非 streaming 回复。
6. 退出并重启 App。
7. 确认聊天记录仍存在。
```

Record:

```text
provider
base URL host only, not full key
model
request time
result
error text if failed
```

- [ ] **Step 6：更新设计状态**

In `docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md`, change status line to:

```markdown
状态：实施中，MVP 范围已冻结
```

Add validation summary section linking to validation record.

- [ ] **Step 7：最终验证**

Run:

```powershell
git diff --check
rg -n "runtime-android-arm64-node24|payload.tgz|node_modules|ProcessBuilder|server.js" README.md CLAUDE.md docs/plan/2026-07-09-stapk-no-node-native-adapter-implementation-plan.md docs/superpowers/specs/2026-07-09-stapk-no-node-native-adapter-design.md
```

Expected:

```text
Only historical-reference or forbidden-artifact-check references remain.
```

**建议提交：**

```text
docs: 记录无 Node MVP 验证结果
```

---

## 推荐执行顺序

- [x] 先执行 Task 0，确认旧 Node 容器计划已从执行入口移除。
- [x] 执行 Task 1 和 Task 2，先把 no-node transform 和 API 契约固定。
- [x] 执行 Task 3 和 Task 4，建立 Android 原生路径、状态和本地 HTTP server。
- [x] 执行 Task 5，让 WebView 走 no-node 本地 HTTP 服务。
- [x] 执行 Task 6，实现本地 settings 和基础系统 endpoint。
- [x] 执行 Task 7，实现本地 characters、chats。
- [x] 执行 Task 8，实现 OpenAI-compatible 非 streaming 真实对话。
- [x] 执行 Task 9，把非 MVP UI 和默认配置通过 patch queue 收敛。
- [x] Task 10 停止执行，旧数据迁移移出主体范围。
- [x] Task 11 的已落地部分保留，剩余工作迁移到 2026-07-12 新计划。
- [x] Task 12 的 MVP 记录保留，最终收口迁移到 2026-07-12 新计划。

## 完成定义

- [x] `docs/plan/2026-07-06-stapk-0.3-completion-plan.md` 不再作为执行计划存在。
- [x] no-node transform 输出 `sillytavern-web/`、`api-contract.json`、`stapk-web-manifest.json`、`transform-report.json`。
- [x] no-node transform verification 阻断 `node_modules`、Node binary、runtime zip、`payload.tgz`。
- [x] Android 主路径不调用 `RuntimeManager.startSillyTavern()`，不启动 `node server.js`。
- [x] `NativeHttpService` 绑定 `127.0.0.1` 随机端口并提供 Web UI 静态资源。
- [x] WebView 可以加载官方 SillyTavern UI。
- [x] settings、characters、chats 可以读写并在重启后保留。
- [x] OpenAI-compatible 非 streaming 对话可以返回真实回复（本地兼容 provider 与真实外部 custom provider 均已验证）。
- [x] API key 不出现在日志、settings 响应、transform report、validation record。
- [x] Android platform patch queue 固定 OpenAI-compatible 非流式默认值，并隐藏 no-node MVP 未支持入口。
- [x] 旧迁移不计入本计划完成定义，0.3.0 按全新安装交付。
- [x] CI 已使用 no-node transform；Release metadata 和严格门禁由新计划接管。
- [x] 验证记录明确证明 APK 运行时没有 Node 进程。
