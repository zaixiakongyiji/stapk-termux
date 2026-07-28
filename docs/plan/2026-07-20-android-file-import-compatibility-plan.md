# Android 文件导入兼容修复实施计划

> **执行要求：** 实施时必须使用 `superpowers:test-driven-development`，逐项勾选步骤。项目规范禁止自动执行 Git commit/push；每个任务仅给出建议提交信息，由用户决定是否手动提交。

**目标：** 让所有 Web 文件上传入口在 Android 标准 DocumentsUI 和小米文件管理器下都能返回可读 `Uri`，并在选择结果不可用时显示明确错误。

**架构：** `MainActivity` 不再直接依赖 `WebChromeClient.FileChooserParams.parseResult()` 的单一路径。新增独立的 `WebFileChooserCoordinator`：启动端统一使用 `ACTION_OPEN_DOCUMENT`，返回端合并标准结果、`data`、`clipData`、单个 `EXTRA_STREAM` 和小米 `ArrayList<Uri>`，最后由 Activity 验证读取权限再交给 WebView。

**技术栈：** Kotlin、Android `Intent`/SAF、WebView、JUnit 4、Robolectric 4.12.2、Gradle。

## 全局约束

- 当前主线仅修改 `mobile/`；已移除的 Termux 旧路线不再属于当前工作树。
- `minSdk=24`、`compileSdk=34`、`targetSdk=28` 保持不变。
- 不新增运行时 Node.js、npm、归档解包或全局明文网络权限。
- 兼容逻辑覆盖角色卡、世界书、聊天记录等所有 `<input type="file">`，不得按业务类型写特例。
- 只接受 `content://` 和 `file://`；重复 URI 必须去重并保持首次出现顺序。
- 用户取消选择时静默返回 `null`；系统返回 `RESULT_OK` 但没有可读 URI 时显示中文错误。
- 真机验收不得清数据、卸载应用或修改无关用户数据；用户重新连接真机前只做 JVM、Node 和模拟器验证。
- 禁止自动执行 Git commit/push。建议提交信息仅作参考。

## 文件结构

- Create: `mobile/app/src/main/java/com/stapk/mobile/WebFileChooserCoordinator.kt`
  - 创建标准 SAF Intent，解析并合并 OEM 文件选择结果，过滤不可用 URI。
- Create: `mobile/app/src/test/java/com/stapk/mobile/WebFileChooserCoordinatorTest.kt`
  - 覆盖标准返回、小米 `ArrayList<Uri>`、单 URI、`clipData`、取消、非法 scheme、去重和可读性过滤。
- Modify: `mobile/app/build.gradle.kts`
  - 启用 Android 资源 JVM 测试并加入 Robolectric。
- Modify: `mobile/app/src/main/java/com/stapk/mobile/MainActivity.kt`
  - 接入 coordinator，保留单一上传 callback 生命周期并显示可见错误。
- Modify: `mobile/app/src/main/res/values/strings.xml`
  - 增加文件选择失败提示。

---

### 任务 1：为 OEM 文件选择结果建立可测试兼容层

**Files:**
- Create: `mobile/app/src/test/java/com/stapk/mobile/WebFileChooserCoordinatorTest.kt`
- Create: `mobile/app/src/main/java/com/stapk/mobile/WebFileChooserCoordinator.kt`
- Modify: `mobile/app/build.gradle.kts`

**Interfaces:**
- Produces: `prepareWebFileChooserIntent(baseIntent: Intent, acceptTypes: Array<String>, allowMultiple: Boolean): Intent`
- Produces: `parseWebFileChooserResult(resultCode: Int, data: Intent?, standardResult: Array<Uri>?): Array<Uri>?`
- Produces: `filterReadableFileChooserUris(uris: Array<Uri>?, canRead: (Uri) -> Boolean): Array<Uri>?`

- [x] **步骤 1：配置 Robolectric JVM 测试环境**

在 `android {}` 中增加：

```kotlin
testOptions {
    unitTests.isIncludeAndroidResources = true
}
```

在 `dependencies {}` 中增加：

```kotlin
testImplementation("org.robolectric:robolectric:4.12.2")
```

- [x] **步骤 2：先写失败测试，覆盖标准 Intent 和小米异常返回**

创建 `WebFileChooserCoordinatorTest.kt`，使用 `@RunWith(RobolectricTestRunner::class)` 和 `@Config(sdk = [34])`。测试至少包含以下断言：

```kotlin
@Test
fun `chooser intent uses open document and preserves accepted MIME types`() {
    val intent = prepareWebFileChooserIntent(
        Intent(Intent.ACTION_GET_CONTENT).setType("image/png"),
        arrayOf("image/png"),
        allowMultiple = false
    )

    assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
    assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
    assertEquals("image/png", intent.type)
    assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
    assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
}

@Test
fun `xiaomi ArrayList Uri in extra stream is accepted`() {
    val uri = Uri.parse("content://com.android.fileexplorer.fileprovider/download/card.png")
    val data = Intent().putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))

    assertArrayEquals(
        arrayOf(uri),
        parseWebFileChooserResult(Activity.RESULT_OK, data, standardResult = null)
    )
}

@Test
@Config(sdk = [32])
fun `legacy Android also reads ArrayList Uri from extra stream`() {
    val uri = Uri.parse("content://com.android.fileexplorer.fileprovider/download/legacy.png")
    val data = Intent().putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri))

    assertArrayEquals(
        arrayOf(uri),
        parseWebFileChooserResult(Activity.RESULT_OK, data, standardResult = null)
    )
}

@Test
fun `all supported sources are merged and deduplicated`() {
    val first = Uri.parse("content://documents/first.json")
    val second = Uri.parse("content://documents/second.json")
    val clipData = ClipData.newRawUri("first", first).apply {
        addItem(ClipData.Item(second))
    }
    val data = Intent().apply {
        this.data = first
        this.clipData = clipData
        putExtra(Intent.EXTRA_STREAM, second)
    }

    assertArrayEquals(
        arrayOf(first, second),
        parseWebFileChooserResult(Activity.RESULT_OK, data, arrayOf(first))
    )
}

@Test
fun `cancel and unsupported schemes return no files`() {
    assertNull(parseWebFileChooserResult(Activity.RESULT_CANCELED, null, null))
    assertNull(
        parseWebFileChooserResult(
            Activity.RESULT_OK,
            Intent().setData(Uri.parse("https://example.com/card.png")),
            null
        )
    )
}

@Test
fun `unreadable Uri values are removed`() {
    val readable = Uri.parse("content://documents/readable.json")
    val blocked = Uri.parse("content://documents/blocked.json")

    assertArrayEquals(
        arrayOf(readable),
        filterReadableFileChooserUris(arrayOf(readable, blocked)) { it == readable }
    )
    assertNull(filterReadableFileChooserUris(arrayOf(blocked)) { false })
}
```

- [x] **步骤 3：运行测试并确认先失败**

Run:

```powershell
Set-Location mobile
.\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.WebFileChooserCoordinatorTest"
```

Expected: FAIL，错误指向三个 coordinator 函数尚未定义。

- [x] **步骤 4：实现最小 coordinator**

创建 `WebFileChooserCoordinator.kt`。核心实现必须采用 `LinkedHashSet` 保序去重，并分别保护 Android 33+ 与旧版 Parcelable API：

```kotlin
package com.stapk.mobile

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build

internal fun prepareWebFileChooserIntent(
    baseIntent: Intent,
    acceptTypes: Array<String>,
    allowMultiple: Boolean
): Intent = baseIntent.apply {
    action = Intent.ACTION_OPEN_DOCUMENT
    addCategory(Intent.CATEGORY_OPENABLE)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
    expandedFileChooserMimeTypes(acceptTypes)?.let { mimeTypes ->
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
    }
}

internal fun parseWebFileChooserResult(
    resultCode: Int,
    data: Intent?,
    standardResult: Array<Uri>?
): Array<Uri>? {
    if (resultCode != Activity.RESULT_OK) return null
    val uris = linkedSetOf<Uri>()
    standardResult.orEmpty().forEach(uris::add)
    data?.data?.let(uris::add)
    data?.clipData?.uris().orEmpty().forEach(uris::add)
    data?.streamUris().orEmpty().forEach(uris::add)
    return uris.filter { it.scheme == "content" || it.scheme == "file" }
        .toTypedArray()
        .takeIf { it.isNotEmpty() }
}

internal fun filterReadableFileChooserUris(
    uris: Array<Uri>?,
    canRead: (Uri) -> Boolean
): Array<Uri>? = uris.orEmpty().filter(canRead).toTypedArray().takeIf { it.isNotEmpty() }

private fun ClipData.uris(): List<Uri> = buildList {
    repeat(itemCount) { index -> getItemAt(index).uri?.let(::add) }
}

private fun Intent.streamUris(): List<Uri> = buildList {
    if (Build.VERSION.SDK_INT >= 33) {
        runCatching { getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) }
            .getOrNull()?.let(::addAll)
        runCatching { getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) }
            .getOrNull()?.let(::add)
    } else {
        @Suppress("DEPRECATION")
        runCatching { getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) }
            .getOrNull()?.let(::addAll)
        @Suppress("DEPRECATION")
        runCatching { getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }
            .getOrNull()?.let(::add)
    }
}
```

- [x] **步骤 5：运行 coordinator 测试并确认通过**

Run:

```powershell
Set-Location mobile
.\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.WebFileChooserCoordinatorTest"
```

Expected: PASS，覆盖单 URI、列表 URI、`clipData`、取消、非法 scheme、去重和可读性过滤。

- [x] **步骤 6：阶段检查，不执行 Git 提交**

Run:

```powershell
git diff --check
git status --short
```

建议提交信息：`fix: 兼容 Android 非标准文件选择结果`

---

### 任务 2：接入 WebView 上传回调并显示错误

**Files:**
- Modify: `mobile/app/src/main/java/com/stapk/mobile/MainActivity.kt`
- Modify: `mobile/app/src/main/res/values/strings.xml`
- Modify: `mobile/app/src/test/java/com/stapk/mobile/WebFileChooserCoordinatorTest.kt`

**Interfaces:**
- Consumes: `prepareWebFileChooserIntent(...)`
- Consumes: `parseWebFileChooserResult(...)`
- Consumes: `filterReadableFileChooserUris(...)`

- [x] **步骤 1：补失败测试，固定多文件模式和 JSON/JSONL MIME 行为**

在 coordinator 测试中增加：

```kotlin
@Test
fun `multiple chooser retains expanded json MIME types`() {
    val intent = prepareWebFileChooserIntent(
        Intent(Intent.ACTION_GET_CONTENT).setType("application/json"),
        arrayOf(".json, .jsonl"),
        allowMultiple = true
    )

    assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    assertArrayEquals(
        arrayOf("application/json", "application/x-ndjson", "application/octet-stream"),
        intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
    )
}
```

- [x] **步骤 2：修改 `onShowFileChooser` 使用标准 SAF Intent**

保留 callback 置空逻辑，将现有 intent 准备代码替换为：

```kotlin
val params = fileChooserParams ?: return false.also {
    fileUploadCallback?.onReceiveValue(null)
    fileUploadCallback = null
}
val baseIntent = runCatching { params.createIntent() }.getOrNull()
if (baseIntent == null) {
    fileUploadCallback?.onReceiveValue(null)
    fileUploadCallback = null
    return false
}
val intent = prepareWebFileChooserIntent(
    baseIntent = baseIntent,
    acceptTypes = params.acceptTypes,
    allowMultiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
)
```

- [x] **步骤 3：修改 `onActivityResult` 合并并验证 URI**

将标准解析结果作为候选之一；OEM extra 解析异常不得逃出 Activity：

```kotlin
val standardResult = runCatching {
    WebChromeClient.FileChooserParams.parseResult(resultCode, data)
}.getOrNull()
val parsedUris = parseWebFileChooserResult(resultCode, data, standardResult)
val readableUris = filterReadableFileChooserUris(parsedUris, ::canReadSelectedFile)
if (resultCode == RESULT_OK && readableUris == null) {
    Toast.makeText(this, R.string.file_import_unreadable, Toast.LENGTH_LONG).show()
}
fileUploadCallback?.onReceiveValue(readableUris)
fileUploadCallback = null
```

新增只读探测函数：

```kotlin
private fun canReadSelectedFile(uri: Uri): Boolean = runCatching {
    contentResolver.openInputStream(uri)?.use { true } ?: false
}.getOrDefault(false)
```

- [x] **步骤 4：增加中文错误资源**

在 `strings.xml` 增加：

```xml
<string name="file_import_unreadable">无法读取所选文件，请重新选择或检查文件访问权限</string>
```

- [x] **步骤 5：运行 Android JVM 测试**

Run:

```powershell
Set-Location mobile
.\gradlew.bat testDebugUnitTest --tests "com.stapk.mobile.WebFileChooserCoordinatorTest" --tests "com.stapk.mobile.MainActivityStateTest"
```

Expected: PASS。

- [x] **步骤 6：运行完整 Android 单元测试**

Run:

```powershell
Set-Location mobile
.\gradlew.bat testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`。

- [x] **步骤 7：阶段检查，不执行 Git 提交**

Run:

```powershell
git diff --check
git status --short
```

建议提交信息：`fix: 接入统一 WebView 文件导入兼容层`

---

### 任务 3：构建与设备验收

**Files:**
- Verify only: `output/`
- Verify only: 真机与模拟器应用数据

- [x] **步骤 1：运行 no-node 全量测试**

Run:

```powershell
npm run test:no-node
```

Expected: 全部 Node 测试 PASS。

- [x] **步骤 2：构建 Debug APK**

Run:

```powershell
npm run build:no-node-apk -- --variant debug --ref release
```

Expected: 构建、transform verifier、capability verifier、Android JVM tests 和 `assembleDebug` 全部通过，APK 发布到 `output/`。

- [x] **步骤 3：先在 Pixel 8 模拟器验证标准返回**

验收顺序：

1. 导入 `mobile/app/src/test/resources/fixtures/cc7481f898a8e631.png`。
2. 确认角色数量从 0 增加到 1，并出现成功或可见失败提示。
3. 导入 `mobile/app/src/test/resources/fixtures/real-world-v7.82.json`。
4. 确认世界书出现且包含 25 条。
5. 取消文件选择，确认无错误、无空数据写入。

- [ ] **步骤 4：用户重新连接小米真机后验证 OEM 返回**

验收顺序：

1. 不清数据，覆盖安装 Debug APK。
2. 通过默认小米文件管理器选择 `cc7481f898a8e631.png`。
3. 确认不再出现“点击后无反应”，角色成功新增。
4. 再选一次不支持的文件，确认显示 `无法读取所选文件` 或前端格式错误，而不是静默失败。
5. 抓取日志，确认没有未捕获 `BadTypeParcelableException` 导致应用回调中断。

- [ ] **步骤 5：最终检查，不执行 Git 提交**

Run:

```powershell
git diff --check
git status --short
```

记录测试结果、APK 路径和 SHA256；由用户决定是否暂存或提交。

## 执行记录（2026-07-20）

- Pixel 8 / Android 15 标准 DocumentsUI 导入角色卡成功，角色数量从 0 增加到 1，显示“珞蒹葭”。
- 导入 `real-world-v7.82.json` 成功，世界书显示 `写实世界V7.82` 和 25 条内容。
- 从角色导入打开标准 DocumentsUI 后取消选择，角色数量保持 1，无错误提示或空数据写入。
- 本地构建产物为 `output/stapk-mobile-debug.apk`，大小 25,347,122 bytes，SHA256 为 `c85ff682f824ef6d051f784e8bf84aeef08c9e4b20876fa13aa68ccf9e259986`。
- 小米真机 OEM 返回验收仍待用户重新连接设备后执行；不得清数据或卸载真机应用。
