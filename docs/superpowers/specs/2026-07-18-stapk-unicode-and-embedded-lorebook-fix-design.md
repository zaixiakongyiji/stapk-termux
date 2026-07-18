# stAPK Unicode 与内嵌世界书修复设计

**状态：** 实施与模拟器验收完成（2026-07-18）

## 1. 背景

真机验证暴露了四个表面上不同的问题：

- 中文 World Info 导入后提示失败，选择后不显示条目。
- 中文 World Info 无法删除。
- 角色卡中的内嵌 World/Lorebook 没有形成可靠的自动导入和绑定。
- 导入 OpenAI preset 并应用 Regex 后，大量中文显示为 `U+FFFD` 替换字符。

测试文件本身均为有效 UTF-8。设备中的原始角色 JSON 也完整保留“珞蒹葭”、内嵌世界书名称及 13 条词条。因此问题不属于 Android 字体覆盖范围，而属于 Native HTTP 请求体解码与后续持久化。

## 2. 根因与证据

SillyTavern 的 `getRequestHeaders()` 对 JSON 请求发送：

```http
Content-Type: application/json
```

NanoHTTPD 2.3.1 在没有显式 `charset` 时使用 `US-ASCII` 解码非 multipart 请求体。UTF-8 中文字节在进入 controller 前已经不可逆地变成 `U+FFFD`。

设备验证结果：

- `/api/worldinfo/get` 使用 `application/json` 读取“写实世界V7.82”返回 `404`；增加 `charset=utf-8` 后返回 `200` 和 25 条词条。
- 一次性中文 World Info 使用无 charset 删除返回 `404`，文件仍存在；使用 UTF-8 删除返回 `200`。
- 角色卡原始 JSON 中没有 `U+FFFD`；经 `/api/worldinfo/edit` 保存的内嵌世界书包含 143,753 个 `U+FFFD`。
- 当前聊天原始 `chat[].mes` 含 10,185 个 `U+FFFD`，不是 Regex 仅在 DOM 层造成的显示问题。
- 当前 settings 与保存后的 preset 各含 210,987 个 `U+FFFD`，主要位于 prompt 和 `extensions.regex_scripts`。

现有真实 World Info HTTP 测试使用 ASCII 文件名 `real-world-v7.82.json`，只覆盖了 multipart 文件正文中的中文，没有覆盖 JSON 请求字段中的中文名称，因此未能发现该边界。

## 3. 设计目标

1. 将没有显式 charset 的 JSON 请求统一按 UTF-8 解码，行为与 JSON 标准和 SillyTavern Node server 保持一致。
2. 修复所有共享该边界的中文保存、读取和删除操作，包括 settings、preset、chat、World Info 和第三方扩展调用。
3. 角色卡包含有效 `data.character_book` 时，在角色导入事务中自动导入并绑定 World/Lorebook。
4. 自动导入不得静默覆盖用户已有的同名 World Info。
5. 保持上游 checkout 不变；前端适配继续通过固定 patch queue 应用。
6. 不尝试修复设备上已经被替换为 `U+FFFD` 的历史数据；重新导入原始文件后应正常。完整旧数据迁移仍是项目主体完成后的可选项。

## 4. Native JSON UTF-8 边界

`NativeHttpServer.parseNanoRequest()` 在调用 NanoHTTPD `parseBody()` 前检查 `Content-Type`：

- MIME 为 `application/json` 或以 `+json` 结尾；
- 请求没有显式 `charset`；
- 将该请求的 Content-Type 补为 `charset=UTF-8` 后再交给 NanoHTTPD。

显式指定 charset 的请求保持原样。multipart 继续由 Apache FileUpload 按现有边界解析，不进入该逻辑。这样可以在单一入口修复所有 controller，而不是逐个修改前端 endpoint。

## 5. 内嵌 World/Lorebook 自动导入

`WorldInfoController` 提供一个内部领域接口，将角色卡 `data.character_book` 转换为 SillyTavern World Info object 并持久化。转换继续复用当前 `convertCharacterBook()`，不得维护第二套字段映射。

命名和冲突规则：

1. 优先使用 `character_book.name`；为空时使用 `<角色名>'s Lorebook`。
2. 名称经过现有安全文件名规范化。
3. 同名文件不存在：创建该名称。
4. 同名文件与转换结果结构完全相同：复用该文件，不重复写入。
5. 同名文件内容不同：使用现有 `-1`、`-2` 唯一命名规则，不覆盖已有文件。

导入成功后，把最终名称写入角色卡 `data.extensions.world`。角色 JSON、头像和新建的 World Info 作为一个逻辑事务：若角色文件写入失败，只回滚本次新建的 World Info；复用的既有文件永不删除。

角色导入响应增加兼容字段：

```json
{
  "file_name": "character",
  "embedded_world": {
    "name": "-------------------------珞蒹葭",
    "entry_count": 13,
    "created": true
  }
}
```

没有内嵌世界书时仍只返回 `file_name`，不破坏上游调用。

## 6. 前端同步

新增固定 patch：角色导入响应包含 `embedded_world` 时，前端 `await updateWorldInfoList()` 后再刷新角色列表。由于角色的 `extensions.world` 已指向实际文件，后续 `checkEmbeddedWorld()` 能确认世界书存在，不再重复提示或再次覆盖。

前端只负责刷新和成功提示，不负责第二次转换或持久化，避免与 Native 事务产生双写。

## 7. 错误处理

- JSON charset 修复只改变无 charset JSON 的解码方式，不吞掉 JSON parse error。
- 内嵌世界书字段存在但结构无效时，角色导入返回现有 `invalid_character` 错误，防止显示“角色导入成功”但静默丢失其声明的绑定数据。
- World Info 写入失败时回滚本次角色导入，不留下只有角色或只有世界书的半完成状态。
- 日志只记录 endpoint、状态和错误码，不记录角色卡、World Info、prompt、Regex 正文或 API key。

## 8. 测试与验收

自动化测试必须覆盖：

- 真实 HTTP `application/json` 无 charset 的中文 World Info 创建、读取和删除。
- 中文 settings、preset/Regex 和 chat payload 通过 Native server 后无 `U+FFFD`。
- 真实角色卡 fixture 自动创建 13 条内嵌世界书并写入正确绑定名。
- 同名相同内容复用；同名不同内容使用 `-1`；角色写入失败时删除本次新建世界书。
- transform patch 能刷新 `world_names`，且不会保留前端双写调用。

设备验收使用干净安装，按以下顺序执行：

1. 导入 `cc7481f898a8e631.png`，确认角色“珞蒹葭”和 13 条内嵌世界书自动出现并绑定。
2. 导入 `写实世界V7.82.json`，确认 25 条词条、中文正文和删除功能。
3. 导入 `Izumi 0707.json`，授权并应用 26 条 Regex，确认 prompt、聊天和 Regex UI 中没有 `U+FFFD`。
4. 重启 App 后重复读取，确认持久化数据仍为 UTF-8。

## 9. 非目标

- 不恢复已经损坏且没有原始来源的 `U+FFFD` 数据。
- 不开发整应用 ZIP 备份恢复或旧数据迁移。
- 不改变第三方扩展的 JavaScript 权限模型。
- 不修改 SillyTavern upstream checkout。

## 10. 实施与验收结果

- `NativeHttpServer` 在 NanoHTTPD 解析 body 前为无 charset 的 `application/json` 和 `application/*+json` 补充 `charset=UTF-8`；显式 charset、form 和 multipart 保持原样。
- 中文 World Info 与 settings/Regex 真实 HTTP round-trip 测试已覆盖创建、读取、保存和删除，落盘内容的 `U+FFFD` 计数为 0。
- 真实角色卡导入后自动创建 `-------------------------珞蒹葭`，转换并渲染 13 条词条，同时把最终名称写入角色 `data.extensions.world`。
- 自动导入已验证相同内容复用、不同内容使用 `-1` 后缀、新建世界书随角色写入失败回滚、复用世界书不随失败删除。
- 固定 patch queue 新增 `0010-stapk-mobile-unicode-and-embedded-lorebook.patch`；角色导入响应包含 `embedded_world` 时只刷新 World Info 列表，不执行前端二次保存。
- 干净安装后，独立 World Info 显示 25 条中文词条及展开正文，删除后列表与私有文件同步消失。
- 导入 `Izumi 0707.json` 并授权后，Regex 面板渲染 26 条中文规则；preset、聊天 JSONL、角色卡和世界书文件的 `U+FFFD` 计数均为 0。
- 强制停止并重启 App 后，角色、内嵌世界书、preset、26 条 Regex 和中文聊天继续可读，WebView 页面替换字符计数为 0。

设备验收使用 Pixel 8 / Android 15 模拟器 `emulator-5554`。已经损坏的旧文件不会被自动修复，必须从原始文件重新导入；旧数据迁移仍只作为项目主体完成后的可选独立项目。
