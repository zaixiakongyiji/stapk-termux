# stAPK Unicode 与内嵌世界书修复实施计划

> **执行要求：** 使用 `superpowers:executing-plans` 按任务执行；每个生产代码修改前必须先建立 RED 测试。仓库规则禁止 Codex 主动执行 `git commit/push`，因此计划不包含提交步骤。

**目标：** 修复无 charset JSON 导致的中文永久损坏，并让角色卡内嵌 World/Lorebook 自动、安全地导入和绑定。

**架构：** Native HTTP server 在 NanoHTTPD 解码前为无 charset JSON 补 UTF-8；WorldInfoController 复用现有 character book 转换逻辑提供内嵌导入接口；CharacterController 负责角色、头像和新建世界书的逻辑事务；固定 upstream patch 只刷新前端 World Info 列表。

**技术栈：** Kotlin、NanoHTTPD 2.3.1、Gson、JUnit 4、Node.js `node:test`、SillyTavern 固定 patch queue、Android WebView/CDP。

**执行状态（2026-07-18）：** Task 1-7 已完成；自动化门禁和 Pixel 8 / Android 15 干净安装验收全部通过。

## 全局约束

- 不修改 SillyTavern upstream checkout，只修改 `patches/sillytavern-no-node/`。
- 不提交或推送 Git。
- 不覆盖同名但内容不同的用户 World Info。
- 不记录 API key、prompt、Regex、角色卡或 World Info 正文。
- 设备验收前必须卸载旧 `com.stapk.mobile`，清除已经损坏的数据。
- 旧数据迁移不属于本计划。

---

### Task 1: 建立无 charset JSON 的 UTF-8 RED 基线

**文件：**
- 修改：`mobile/app/src/test/java/com/stapk/mobile/nativeadapter/WorldInfoControllerTest.kt`
- 修改：`mobile/app/src/test/java/com/stapk/mobile/nativeadapter/SettingsControllerTest.kt`

**接口：**
- 输入：真实 `NativeHttpServer`，`Content-Type: application/json`，UTF-8 请求体。
- 输出：controller 收到的字符串必须保持原始 Unicode code point。

- [x] **Step 1: 添加中文 World Info HTTP round-trip 测试**

测试通过 `/api/worldinfo/edit` 创建“中文世界书”，通过 `/api/worldinfo/get` 读取一条中文 comment，再通过 `/api/worldinfo/delete` 删除。三个请求都故意不发送 charset。

- [x] **Step 2: 添加中文 settings/Regex HTTP round-trip 测试**

POST `/api/settings/save`，payload 中放入 `extension_settings.regex` 中文 script name 和 replace string；随后 `/api/settings/get`，断言没有 `U+FFFD` 且字符串 identity 相等。

- [x] **Step 3: 运行 RED**

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.WorldInfoControllerTest" --tests "com.stapk.mobile.nativeadapter.SettingsControllerTest"
```

预期：新增测试失败，中文名称或 Regex 字段变成 `U+FFFD`；既有 ASCII 测试通过。

### Task 2: 在 Native HTTP 边界按 UTF-8 解码 JSON

**文件：**
- 修改：`mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`
- 测试：`mobile/app/src/test/java/com/stapk/mobile/nativeadapter/NativeRouterTest.kt`

**接口：**
- 产生：`ensureJsonUtf8Charset(headers: MutableMap<String, String>)`。
- 行为：只处理 `application/json` 和 `application/*+json` 且没有 charset 的请求。

- [x] **Step 1: 实现最小 charset 规范化**

在 `session.parseBody(files)` 前定位大小写不敏感的 `content-type` header；JSON 且无 charset 时附加 `; charset=UTF-8`。

- [x] **Step 2: 增加显式 charset 和非 JSON 不变测试**

断言 `application/json; charset=ISO-8859-1` 不被改写，`application/x-www-form-urlencoded` 和 multipart 不进入 JSON 逻辑。

- [x] **Step 3: 运行 GREEN**

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.NativeRouterTest" --tests "com.stapk.mobile.nativeadapter.WorldInfoControllerTest" --tests "com.stapk.mobile.nativeadapter.SettingsControllerTest"
```

预期：中文 World Info 创建、读取、删除和 settings/Regex identity 全部通过。

### Task 3: 建立内嵌世界书自动导入 RED 基线

**文件：**
- 修改：`mobile/app/src/test/java/com/stapk/mobile/nativeadapter/CharacterControllerTest.kt`
- 修改：`mobile/app/src/test/java/com/stapk/mobile/nativeadapter/WorldInfoControllerTest.kt`

**接口：**
- 输入：角色卡 `data.character_book`。
- 输出：`EmbeddedWorldInfoImportResult(name, entryCount, created)`。

- [x] **Step 1: 使用真实角色卡 fixture 添加自动导入测试**

导入 `real-character-card.png` 后断言：response 的 `embedded_world.entry_count == 13`、最终 World Info 名称保持中文、落盘 `entries` 为 object 且 13 条、角色 `data.extensions.world` 与实际文件名一致。

- [x] **Step 2: 添加冲突策略测试**

分别覆盖同名相同内容复用，以及同名不同内容创建 `-1` 且原文件内容不变。

- [x] **Step 3: 添加事务回滚测试**

复用 CharacterController 可注入的失败 mover/store，使角色文件写入失败；断言本次新建 World Info 被删除，预先存在并复用的文件不删除。

- [x] **Step 4: 运行 RED**

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.CharacterControllerTest" --tests "com.stapk.mobile.nativeadapter.WorldInfoControllerTest"
```

预期：response 没有 `embedded_world`，World Info 文件不存在，新增测试按预期失败。

### Task 4: 实现内嵌世界书导入与角色事务

**文件：**
- 修改：`mobile/app/src/main/java/com/stapk/mobile/nativeadapter/WorldInfoController.kt`
- 修改：`mobile/app/src/main/java/com/stapk/mobile/nativeadapter/CharacterController.kt`
- 修改：`mobile/app/src/main/java/com/stapk/mobile/nativeadapter/NativeHttpServer.kt`

**接口：**
- 产生：`data class EmbeddedWorldInfoImportResult(val name: String, val entryCount: Int, val created: Boolean)`。
- 产生：`WorldInfoController.importEmbeddedCharacterBook(book: JsonObject, fallbackName: String): EmbeddedWorldInfoImportResult`。
- 产生：`WorldInfoController.rollbackEmbeddedImport(result: EmbeddedWorldInfoImportResult)`。

- [x] **Step 1: 复用现有转换实现导入接口**

调用现有 `normalizeImportedData()` / `convertCharacterBook()`，执行安全命名、相同内容复用和不同内容唯一命名。

- [x] **Step 2: CharacterController 在写角色前导入内嵌世界书**

读取 `decoded.json.data.character_book`；成功后写入最终 `data.extensions.world`，并在 response 增加 `embedded_world`。

- [x] **Step 3: 连接回滚路径**

角色 JSON 或头像写入抛异常时，仅对 `created == true` 的结果调用 rollback。

- [x] **Step 4: NativeHttpServer 共享同一 WorldInfoController**

先构造 `worldInfo`，再把它注入 `CharacterController`，避免 HTTP 导入与角色导入维护两套服务实例。

- [x] **Step 5: 运行 GREEN**

```powershell
cd mobile
.\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.nativeadapter.CharacterControllerTest" --tests "com.stapk.mobile.nativeadapter.WorldInfoControllerTest"
```

预期：13 条自动导入、复用、唯一命名和回滚测试全部通过。

### Task 5: 固定前端同步 patch

**文件：**
- 创建：`patches/sillytavern-no-node/0010-stapk-mobile-unicode-and-embedded-lorebook.patch`
- 修改：`test/no-node/task10-extension-import-compatibility.test.mjs`

**接口：**
- 消费：角色导入 response `embedded_world`。
- 行为：`await updateWorldInfoList()`，显示自动导入成功提示，不再次调用 `saveWorldInfo()`。

- [x] **Step 1: 添加 patch RED 测试**

断言 `script.js` 导入 `updateWorldInfoList`，角色导入 response 存在 `embedded_world` 时刷新列表；断言该分支不调用 `importEmbeddedWorldInfo()`。

- [x] **Step 2: 运行 RED**

```powershell
node --test test/no-node/task10-extension-import-compatibility.test.mjs
```

预期：缺少 embedded world 同步逻辑而失败。

- [x] **Step 3: 添加固定 patch 并重新转换**

```powershell
npm run transform:no-node
```

- [x] **Step 4: 运行 GREEN 与 transform 验证**

```powershell
node --test test/no-node/task10-extension-import-compatibility.test.mjs
npm run transform:no-node:verify
npm run verify:no-node-capabilities
```

预期：patch 测试、转换复现和 capability contract 全部通过。

### Task 6: 完整自动化验证与文档回填

**文件：**
- 修改：`docs/superpowers/specs/2026-07-17-stapk-extension-and-import-compatibility-design.md`
- 修改：`docs/plan/2026-07-17-stapk-extension-and-import-compatibility-implementation-plan.md`

- [x] **Step 1: 回填根因、测试缺口和修复结果**

记录 NanoHTTPD `US-ASCII` 默认行为、中文名称 HTTP 测试、自动内嵌世界书策略和旧损坏数据不迁移结论。

- [x] **Step 2: 运行完整验证链路**

```powershell
npm run test:no-node
npm run transform:no-node
npm run transform:no-node:verify
npm run verify:no-node-capabilities
cd mobile
.\gradlew.bat testDebugUnitTest
cd ..
npm run build:no-node-apk -- --variant debug --ref release
git diff --check
```

预期：全部命令退出码为 0，构建生成 debug APK。

### Task 7: Pixel 8 干净安装验收

**文件：**
- 验证产物：`output/stapk-mobile-no-node-debug.apk`
- 测试样例：`test/测试文件/cc7481f898a8e631.png`
- 测试样例：`test/测试文件/写实世界V7.82.json`
- 测试样例：`test/测试文件/Izumi 0707.json`

- [x] **Step 1: 卸载旧 App 并安装新 APK**

```powershell
adb -s emulator-5554 uninstall com.stapk.mobile
adb -s emulator-5554 install output/stapk-mobile-debug.apk
```

- [x] **Step 2: 验证角色卡及内嵌世界书**

导入角色卡，确认角色“珞蒹葭”、内嵌世界书中文名称、13 条词条和角色绑定，无额外手动导入步骤。

- [x] **Step 3: 验证独立 World Info**

导入“写实世界V7.82”，确认 25 条、正文可见；删除后列表和文件均消失。

- [x] **Step 4: 验证 preset 与 Regex**

导入 `Izumi 0707.json`，授权 26 条 Regex；确认 UI、chat、settings 和落盘 preset 的 `U+FFFD` 数量均为 0。

- [x] **Step 5: 重启并复验持久化**

强制停止并重启 App，确认角色、世界书、preset、Regex 和聊天中文仍完整。

- [x] **Step 6: 停止 Gradle daemon**

```powershell
cd mobile
.\gradlew.bat --stop
```
