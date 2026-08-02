# stAPK 远程 Embedding 与本地 SQLite 向量存储设计

日期：2026-07-30
状态：已实施并验收；正式维护基线为 API 35 及以上
范围：在无 Node.js 运行时约束下恢复 SillyTavern Data Bank、Vector Storage 和向量化 World Info；远程服务只负责生成 embedding，Android 本地 SQLite 负责持久化和精确检索

## 实施状态（2026-07-31）

- Provider 配置、SecretStore、远程 Embedding client、VectorCodec、本地 SQLite、精确 Top-K、损坏恢复、十个 HTTP route、capability/UI contract 和 fixed patch queue 均已实现。
- Pixel 8 / Android 15（API 35）已完成 Custom OpenAI-compatible、Data Bank、聊天 RAG、World Info、重启持久化、错误注入、隐私扫描和性能验收。
- 当前重新构建的 Debug APK SHA-256 为 `6beacb5e307760516a275fd79d5a90739393bda076572fe2d57ccd0780b9d602`；138 个 no-node tests 和 460 个 Android JVM tests 通过。相同实现链的前一候选 `996fc5d6...e1c1e530` 已通过 3 个 instrumentation tests 与完整远程 Embedding/RAG 验收；当前候选已在 API 35 覆盖安装并通过 `app_ready`、配置持久化、无 Node 和无 crash/ANR 最小回归。
- 项目只维护、回归和承诺 API 35 及以上；API 24/29 不再纳入支持矩阵，不阻断本设计收口。
- 完整证据见 [`docs/plan/2026-07-30-stapk-vector-storage-validation-record.md`](../../plan/2026-07-30-stapk-vector-storage-validation-record.md)。

## 结论

第一版采用以下组合：

```text
SillyTavern 官方 Vector Storage 前端
    -> 切分聊天、Data Bank 文件和 World Info
    -> POST /api/vector/*
    -> Kotlin EmbeddingProviderClient 调用远程 OpenAI-compatible /embeddings
    -> Kotlin 校验并归一化 Float32 向量
    -> Android framework SQLite 保存 BLOB
    -> Kotlin Cursor 流式精确扫描 + Top-K 小根堆
    -> 返回 SillyTavern 原有 hashes / metadata 响应
```

APK 不内置 embedding 模型，不启动独立向量数据库进程，不引入 Node.js、Python、ONNX Runtime、SQLite 向量扩展、FAISS、HNSW 或 native ABI 库。

SQLite 只承担持久化；向量序列化、维度校验、余弦相似度和 Top-K 排序由 Kotlin 实现。不同 Provider、endpoint、model 或维度的向量必须隔离，不能互相比较。

## 背景与当前事实

- 当前 Web 资产来自 SillyTavern 1.18.0 `release`。
- 官方前端已经包含 Data Bank、Vector Storage、聊天向量化和 World Info 向量激活逻辑。
- 前端负责文件切块、聊天同步、World Info 条目同步和 Prompt 注入。
- 上游后端通过 `vectra.LocalIndex` 保存向量，索引路径按 `source / collectionId / model` 隔离。
- Android capability 已将 `remote.embeddings` 标记为运行时可用，并通过 `configuredActions` 显示 `#vectors_container`；未配置 Provider 时功能开关保持关闭。
- Native adapter 已注册七个 `/api/vector/*` 和三个 `/api/stapk/embeddings/*` route。
- Android 工程继续保持 `minSdk=24`，不为本次维护范围决策改动既有工具链；该技术安装下限不构成对 API 24–34 的支持承诺。当前没有 Room、SQLite native extension 或其他数据库依赖。

## 目标

1. 复用官方 Data Bank 和 Vector Storage UI，不重新实现一套 Android RAG 界面。
2. 兼容当前上游七个 `/api/vector/*` 请求和响应。
3. 支持 Data Bank 文件、聊天消息和 World Info 三类官方向量化场景。
4. embedding 由用户配置的远程 HTTP Provider 生成。
5. 向量和 metadata 持久化在 app-private SQLite 中，force-stop 和重启后继续可用。
6. 支持常见及未来未知的 embedding 维度，不在 schema 中固定 384、768、1536 或 3072。
7. 模型、endpoint 或维度变化时 fail closed，不混用不兼容的向量空间。
8. 不把文本、向量、API Key 或 Provider 响应写入诊断日志。
9. 保持运行时 no-node 门禁和现有 capability verifier 严格通过。

## 非目标

- 不支持 0.2.x 数据迁移。
- 不在手机运行本地 embedding 模型。
- 不连接远程 Qdrant、Milvus、Pinecone 等向量数据库。
- 不实现近似最近邻索引、HNSW、量化或 GPU 加速。
- 不在第一版支持 Gemini、Cohere、Ollama、llama.cpp、KoboldCpp、WebLLM、Extras 或其他专有 embedding 协议。
- 不新增 PDF、Office、网页抓取或翻译解析能力；沿用当前官方前端已经能读取的 Data Bank 内容。
- 不实现 Vector Summarization 的 Extras/WebLLM 路线；只保留当前可用的 Main API summarization，且默认关闭。
- 不把向量索引当作用户唯一数据源；聊天、文件和 World Info 始终是可重建的 canonical data。

## 方案比较

### 方案一：远程 embedding + 本地 SQLite 精确检索（采用）

优点：

- 不携带模型和 native ABI 库。
- 不需要用户部署远程向量数据库。
- 向量索引保留在手机，只有待 embedding 的文本发送到 Provider。
- framework SQLite 在 API 24+ 可用，数据库文件格式稳定。
- 精确检索行为可预测，适合手机上的中小规模角色聊天和资料库。

代价：

- 首次向量化需要联网并可能产生 API 费用。
- 大型索引查询时间随 item 数量和维度线性增长。
- Provider 切换或模型变化需要重新生成索引。

### 方案二：远程 embedding + 远程向量数据库（拒绝）

Android 代码较少，但用户还需要配置和维护另一项服务，密钥、隐私、网络错误和数据一致性边界更复杂，不适合当前开箱即用目标。

### 方案三：本地 embedding + 本地向量存储（延期）

可以离线工作，但会引入模型下载、数百 MB 体积、推理库、ABI、内存、功耗和机型差异。当前没有足够用户需求支撑这项复杂度。

## 用户界面与 capability

### 入口

沿用官方入口：

```text
扩展程序
└── Vector Storage
    ├── Embedding Provider
    ├── Data Bank files
    ├── Chat messages
    └── World Info
```

不在 Android 设置中新增第二套 RAG 页面。

### 第一版 Provider 选择

只显示：

1. `OpenAI`
2. `Custom OpenAI-compatible`

隐藏或禁用其他 Provider 选项，并显示“当前 Android no-node 版本尚未支持该 embedding 协议”。不允许继续保留 `Local (Transformers)` 作为默认值。

未完成配置时：

- Vector Storage 可以打开，但功能开关保持关闭。
- 启用前显示隐私说明：发送给 embedding Provider 的内容可能包含聊天、World Info 和 Data Bank 文本片段。
- 用户确认后才保存启用状态。

### Provider 配置

OpenAI：

- Base URL 固定为 `https://api.openai.com/v1`。
- 使用现有 `api_key_openai`。
- 模型选择沿用官方 OpenAI embedding 模型列表。

Custom OpenAI-compatible：

- 用户填写 HTTPS Base URL 和模型名称。
- API Key 使用新增 secret key `api_key_embedding`，不得写入 settings 或 Provider 配置文件。
- 前端 source id 固定为 `stapk_openai_compatible`；其他未知 source fail closed。
- Base URL 只在受控配置入口保存；`/api/vector/*` 请求不得临时覆盖 URL。
- HTTP 只允许 loopback；非 loopback Provider 必须使用 HTTPS。

配置页提供“测试连接”按钮。测试只发送一个固定、非用户内容的短文本，成功后显示返回维度，不保存测试向量。

### capability 变化

当前实现状态：

- `remote.embeddings=true`
- `/api/vector/*` 从 `external_optional` 收敛为 `implemented`
- `#vectors_container` 从 hidden contract 移到 implemented UI contract
- fixed patch queue 只负责 Provider 选项裁剪、配置入口和 capability gate，不修改上游仓库

## Android 组件

### `EmbeddingProviderConfigStore`

保存非敏感 Provider 配置：

```kotlin
data class EmbeddingProviderConfig(
    val type: EmbeddingProviderType,
    val baseUrl: String,
    val model: String
)
```

配置文件建议为 `user_config/embedding-provider.json`。API Key 始终由 `SecretStore` 管理。

### `EmbeddingProviderClient`

职责：

- 从 ConfigStore 和 SecretStore 取得不可变配置快照。
- 调用 `${baseUrl}/embeddings`。
- 请求体为 `{ "input": [...], "model": "..." }`。
- 按 Provider 返回的 `index` 恢复原输入顺序。
- 验证结果数量、维度、有限数值和非零范数。
- 返回同一批次、同一维度的 `FloatArray`。
- 记录脱敏的 host、HTTP 状态、耗时、批次大小和返回维度。

Provider 请求不自动重试。429、超时和 5xx 直接返回给前端，由用户重新触发同步，避免不可见的重复计费。

### `VectorCodec`

统一格式：

```text
Float32
Little Endian
unit-normalized
BLOB length = dimension * 4
```

编码前拒绝：

- 空向量
- `NaN` 或正负无穷
- 零范数
- 维度大于 32768

归一化向量保存后，cosine similarity 可以使用 dot product 计算。

### `VectorDatabaseHelper`

使用 Android framework `SQLiteOpenHelper`，不引入 Room 或 SQLite native extension。

`NativeHttpService` 使用 `applicationContext` 创建并持有 Helper/Repository，再注入 `NativeHttpServer`；服务停止时由同一 owner 关闭数据库。不得让每个 Controller 或 HTTP 请求各自创建 Helper。

初始化：

```text
PRAGMA foreign_keys=ON
PRAGMA journal_mode=WAL
PRAGMA synchronous=NORMAL
PRAGMA user_version=1
```

数据库使用 `applicationContext.getDatabasePath("vector-store.db")`。它是可由 canonical data 重建的派生索引，默认不进入完整数据备份；备份恢复后由用户重新向量化。

### `VectorRepository`

职责：

- collection namespace 解析。
- item upsert、hash list、delete、purge。
- 单 collection 精确查询。
- 多 collection 全局 Top-K 查询。
- transaction 和数据库损坏恢复边界。

Provider 网络调用必须发生在 SQLite 写事务之外。只有整个 embedding 批次通过验证后才开启 transaction 并 upsert，避免长时间占用写锁。

### `VectorController`

注册并兼容：

```text
POST /api/vector/list
POST /api/vector/insert
POST /api/vector/delete
POST /api/vector/query
POST /api/vector/query-multi
POST /api/vector/purge
POST /api/vector/purge-all
```

新增受控配置接口：

```text
POST /api/stapk/embeddings/config/get
POST /api/stapk/embeddings/config/save
POST /api/stapk/embeddings/test
```

这些接口不得返回 secret value。

## SQLite 数据模型

### `vector_collections`

```sql
CREATE TABLE vector_collections (
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
    collection_key       TEXT NOT NULL,
    provider_type        TEXT NOT NULL,
    endpoint_fingerprint TEXT NOT NULL,
    model                TEXT NOT NULL,
    dimension            INTEGER NOT NULL CHECK (dimension > 0 AND dimension <= 32768),
    created_at           INTEGER NOT NULL,
    updated_at           INTEGER NOT NULL,
    UNIQUE (
        collection_key,
        provider_type,
        endpoint_fingerprint,
        model
    )
);
```

`endpoint_fingerprint` 为规范化 Base URL 的 SHA-256，不保存 secret，也不在日志中输出原始 URL。Provider、endpoint 或 model 任一变化都会进入独立 namespace。

同一 namespace 首次成功生成 embedding 时固定 `dimension`。后续若 Provider 为同一配置返回不同维度，返回 `409 vector_dimension_changed`，要求用户 purge/reindex；不得自动混存。

### `vector_items`

```sql
CREATE TABLE vector_items (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    collection_id INTEGER NOT NULL,
    content_hash  INTEGER NOT NULL,
    item_index    INTEGER NOT NULL,
    text          TEXT NOT NULL,
    vector_blob   BLOB NOT NULL CHECK (length(vector_blob) > 0 AND length(vector_blob) % 4 = 0),
    updated_at    INTEGER NOT NULL,
    FOREIGN KEY (collection_id) REFERENCES vector_collections(id) ON DELETE CASCADE,
    UNIQUE (collection_id, content_hash)
);

CREATE INDEX idx_vector_items_collection
    ON vector_items(collection_id);
```

`text`、`hash` 和 `index` 用于原样构造上游期待的 metadata：

```json
{
  "hash": 123456,
  "text": "chunk text",
  "index": 2
}
```

## 维度兼容规则

- SQLite BLOB 不固定维度，因此不同 collection 可以保存任意受支持维度。
- 同一 collection namespace 的全部 item 必须与 collection.dimension 一致。
- 不同模型即使维度相同，也不得比较。
- 同一模型名称若 endpoint 不同，也不得比较。
- model 或 endpoint 改变后，`list` 对新 namespace 返回空数组，官方前端会按缺失 hash 重新同步。
- 维度变化不尝试补零、截断、投影或自动转换。

## API 兼容行为

### `/api/vector/list`

输入 `collectionId, source, model`，返回当前 Provider namespace 已保存的 hash 数组。

Controller 只接受当前 capability 开放的 source。请求中的 source/model 必须与已保存的活动配置一致；不得利用普通 vector request 临时切换 Provider、model 或 URL。UI 修改 Provider/model 时必须先调用受控 config save route。

### `/api/vector/insert`

输入官方前端产生的 `items[{hash,text,index}]`。流程：

1. 校验请求限制。
2. 取得 Provider 配置快照。
3. 在 transaction 外批量请求 embedding。
4. 校验所有返回向量数量和维度一致。
5. 归一化并编码为 BLOB。
6. transaction 内创建或核对 collection，按 hash upsert。
7. 整批成功后提交；任何失败不留下部分写入。

### `/api/vector/query`

1. 远程生成一个 query embedding。
2. 找到同 Provider、endpoint、model、dimension 的 collection。
3. Cursor 逐行读取 BLOB，不把全部向量载入内存。
4. 计算 dot product，并维护最多 `topK` 项的小根堆。
5. 按官方 response shape 返回 `hashes` 和 `metadata`。

### `/api/vector/query-multi`

query embedding 只生成一次。按 collection 顺序流式扫描，所有候选进入同一个全局 Top-K 堆，最终再按 collectionId 分组返回。

### `/api/vector/delete`

按 `collectionId + hashes` transaction 删除；不存在的 hash 视为幂等成功。

### `/api/vector/purge`

按 `collectionId` 删除该 collection 在所有 Provider/model namespace 下的索引，保持上游语义。

### `/api/vector/purge-all`

清空所有 collection 和 item，但保留数据库 schema 与 Provider 配置。操作必须经过前端确认。

## 请求限制

第一版固定：

| 项目 | 限制 |
|---|---:|
| 单次 insert item 数 | 64 |
| 单 item 文本长度 | 100000 chars |
| 单次 query collection 数 | 64 |
| `topK` | 1-100 |
| 向量最大维度 | 32768 |
| embedding response body | 32 MiB |
| Base URL 长度 | 2048 chars |
| 模型名称长度 | 256 chars |

Provider 请求前先检查至少还有 64 MiB 可用空间，不满足时不发起远程请求。收到 embedding 后再按真实维度计算本批预计写入量；若剩余空间小于 `max(64 MiB, 本批预计写入量 * 2)`，返回 `507 vector_storage_full` 且不开始 SQLite transaction。第二次检查可能发生在已经产生 embedding 费用之后，但能保证未知维度的首次请求仍然安全落盘。

## 并发与事务

- `SQLiteOpenHelper`、Repository 和 Controller 由 Native HTTP service 生命周期持有一个实例，并注入 NativeHttpServer。
- 网络调用不持有数据库 transaction 或全局写锁。
- insert/delete/purge 使用 SQLite transaction。
- query 使用只读 Cursor，逐条关闭 BLOB 和 Cursor。
- config 在请求开始时复制成不可变快照，进行中的请求不受 UI 中途修改影响。
- purge 与 insert 并发时依赖 transaction serialization；后提交操作的结果生效，不产生半条数据。
- app/service 停止时关闭 helper；WAL checkpoint 由 SQLite 正常生命周期处理。

## 错误处理

| HTTP | error code | 场景 |
|---:|---|---|
| 400 | `vector_invalid_request` | 缺字段、越界、非法 collection/model |
| 401 | `embedding_key_missing` | 未配置 API Key |
| 409 | `vector_dimension_changed` | 同 namespace 返回不同维度 |
| 413 | `vector_request_too_large` | item、文本、批次或响应超过限制 |
| 422 | `embedding_invalid_vector` | 数量不符、NaN、无穷或零范数 |
| 429 | `embedding_rate_limited` | Provider 限流 |
| 502 | `embedding_provider_error` | Provider 非成功响应或 JSON 非法 |
| 504 | `embedding_timeout` | Provider 超时 |
| 507 | `vector_storage_full` | 磁盘空间不足或 SQLite full |

数据库损坏时：

1. 停止当前操作。
2. 关闭 helper。
3. 将损坏 DB、WAL 和 SHM 移入 quarantine，文件名带时间戳。
4. 创建空 schema。
5. 返回 `409 vector_index_rebuild_required`。
6. UI 提示索引是派生数据，可点击“Vectorize All”重建。

不得静默触发远程重建，以免产生不可预期的 API 费用。

## 安全与隐私

- Provider URL 只能通过受控 config route 保存，普通 vector request 不接受临时 URL。
- 非 loopback Base URL 必须为 HTTPS。
- SecretStore 新增 `api_key_embedding`，读取接口只返回 `********`。
- 诊断日志不记录 API Key、Authorization、原始 Base URL、聊天文本、Data Bank 文本、World Info、embedding 数组或 metadata.text。
- 可记录：Provider host、HTTP status、durationMs、batch count、dimension、collectionId 的 SHA-256、model 的 SHA-256、数据库大小和错误类。
- 第一次启用前必须说明远程 embedding 会发送文本片段。
- 第三方 client-only 扩展只能调用已经配置的 Provider，不能借 `/api/vector/*` 指定任意目标地址。

## 存储与备份边界

- 聊天、附件、World Info 是 canonical data。
- `databases/vector-store.db` 是可重建索引，不是用户内容的唯一副本。
- 完整应用备份功能尚未实施；后续实现时默认排除 vector DB、WAL 和 SHM，避免备份体积膨胀和不一致快照。
- 恢复完整备份后 Vector Storage 显示“需要重新向量化”。
- purge 不删除聊天、附件、World Info、Provider 配置或 API Key。

## 性能策略

第一版使用精确扫描，不做近似索引：

- BLOB 在 Cursor 中逐行解码。
- 查询向量只归一化一次。
- Top-K 只保留 `topK` 个候选，不保存完整排序结果。
- multi-query 共享一次 query embedding。
- insert 沿用官方前端的小批次同步，Controller 上限 64。

设备基线：在 Pixel 8 / API 35 模拟器上，10000 条 1536 维向量的本地单 collection Top-10 精确扫描应在 5 秒内完成，且查询过程新增 Java heap 不超过 64 MiB。若未达到，先优化 BLOB 解码和 Cursor 扫描；不直接引入 native ANN 库。

## 测试与验收

### JVM 单元测试

- 384、768、1024、1536、3072、32768 维 Float32 Little Endian round-trip。
- NaN、无穷、零范数、空向量和超大维度拒绝。
- Provider response 按 index 重排。
- 批次数量不符和批内维度不一致拒绝。
- OpenAI 与 Custom 配置、HTTPS/loopback URL 校验和 Secret 脱敏。
- SQLite create、upsert、list、delete、purge、purge-all。
- 不同 endpoint/model/维度 namespace 隔离。
- 单 collection 和 multi collection cosine Top-K、threshold 和官方 response shape。
- insert 中途失败 transaction rollback。
- 并发 insert/purge 不产生损坏或半写入。
- 数据库损坏隔离与 rebuild-required 响应。
- 诊断日志不含 prompt、chunk、vector、key 或 URL。

### Node/transform 契约测试

- fixed patch 只显示 OpenAI 与 Custom OpenAI-compatible。
- `Local (Transformers)` 不再是默认来源。
- `#vectors_container` 在 capability=true 时可见，加载失败时 fail closed。
- 七个 `/api/vector/*` 全部为 `implemented`。
- 无 visible `needs_review`。
- no-node verifier 不允许新增 runtime model、native vector library、Node server 或 payload archive。

### MockWebServer 集成测试

- `/embeddings` 正常批量响应。
- 返回顺序打乱后仍正确映射。
- 401、429、500、超时、过大 body、非法 JSON 和 secret 泄漏防护。
- Provider 返回维度变化时不污染旧 collection。

### 设备验收

至少覆盖：

1. clean install 后配置 Custom OpenAI-compatible embedding Provider。
2. Data Bank 全局、角色和聊天范围分别加入文本文件。
3. Vectorize All 后发送相关问题，命中的 chunk 正确进入 Prompt。
4. 聊天消息向量化能召回旧消息。
5. World Info 向量激活能命中对应条目。
6. force-stop/relaunch 后索引仍可查询。
7. 切换模型后旧索引不被混用，并提示重新向量化。
8. 断网、429 和 Provider 错误不会写入部分索引。
9. purge collection 和 purge-all 不删除 canonical data。
10. 进程列表和 APK 内容继续无 Node runtime。
11. 在正式维护基线 API 35 及以上验证配置、向量化、查询、重启和清空索引；当前以 API 35 作为最低验收设备。

## 实施边界建议

后续实施计划应分为五组，逐组验证：

1. Provider config、SecretStore 与 MockWebServer。
2. VectorCodec、SQLite schema 与 Repository。
3. 七个 API route 与上游契约测试。
4. patch queue、Provider UI 和 capability 收敛。
5. Data Bank、聊天、World Info 与 API 35 设备验收。

完整备份恢复和远程 TTS 分别立项，不进入本设计的实现计划。

## 完成定义

当前实现、自动化门禁和 API 35 设备验收证据均已完成。API 24–34 不属于正式维护范围，不再作为完成定义的一部分。

- 官方 Vector Storage UI 可配置并启用 OpenAI 或 Custom OpenAI-compatible embedding。
- Data Bank、聊天和 World Info 三条调用链均通过真实设备验收。
- 七个 `/api/vector/*` 与当前上游前端契约一致。
- 各种常见维度可保存；不兼容向量空间绝不混用。
- force-stop/relaunch 后索引可用，损坏索引可安全隔离并重建。
- Provider 错误、磁盘不足和维度变化不会产生部分写入。
- secrets、用户文本和向量不出现在日志或 API 响应错误中。
- capability verifier、no-node tests、Android JVM tests 和 Debug APK 构建全部通过。
- APK 和运行进程不包含或启动 Node.js、本地 embedding 模型或独立向量数据库服务。
