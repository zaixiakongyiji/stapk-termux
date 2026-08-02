package com.stapk.mobile.nativeadapter

import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.stapk.mobile.nativeadapter.vector.VectorRoutes
import fi.iki.elonen.NanoFileUpload
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.ResponseException
import java.io.File
import java.io.FilterInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import org.apache.commons.fileupload.FileUploadBase
import org.apache.commons.fileupload.disk.DiskFileItemFactory

class NativeHttpServer private constructor(
    private val paths: NativeAdapterPaths,
    port: Int,
    private val exportStore: ExportStore,
    private val diagnosticLogger: DiagnosticLogger,
    private val vectorRoutes: VectorRoutes?,
    private val extensionSubsystemFactory: (NativeAdapterPaths, AtomicFileStore, DiagnosticLogger) -> ExtensionSubsystem,
    @Suppress("UNUSED_PARAMETER") constructorMarker: ExtensionSubsystemFactoryConstructorMarker
) : NanoHTTPD("127.0.0.1", port), NativeAdapterServer {
    constructor(
        paths: NativeAdapterPaths,
        port: Int = 0,
        exportStore: ExportStore = ExportStore(paths.exportsDir),
        diagnosticLogger: DiagnosticLogger = DiagnosticLogger(paths.logsDir),
        vectorRoutes: VectorRoutes? = null
    ) : this(
        paths,
        port,
        exportStore,
        diagnosticLogger,
        vectorRoutes,
        ::createExtensionSubsystem,
        ExtensionSubsystemFactoryConstructorMarker
    )

    constructor(
        paths: NativeAdapterPaths,
        port: Int,
        exportStore: ExportStore,
        diagnosticLogger: DiagnosticLogger
    ) : this(
        paths = paths,
        port = port,
        exportStore = exportStore,
        diagnosticLogger = diagnosticLogger,
        vectorRoutes = null
    )

    internal constructor(
        paths: NativeAdapterPaths,
        port: Int = 0,
        exportStore: ExportStore = ExportStore(paths.exportsDir),
        diagnosticLogger: DiagnosticLogger = DiagnosticLogger(paths.logsDir),
        vectorRoutes: VectorRoutes? = null,
        extensionSubsystemFactory: (NativeAdapterPaths, AtomicFileStore, DiagnosticLogger) -> ExtensionSubsystem
    ) : this(
        paths,
        port,
        exportStore,
        diagnosticLogger,
        vectorRoutes,
        extensionSubsystemFactory,
        ExtensionSubsystemFactoryConstructorMarker
    )

    @Volatile
    private var bridgeNonce: String? = null
    private val atomicStore = AtomicFileStore(paths.quarantineDir, diagnosticLogger)
    private val staticAssets = StaticAssetController(paths)
    private val settings = SettingsController(paths, atomicStore, diagnosticLogger)
    private val personas = PersonaController(paths, atomicStore)
    private val presets = PresetController(paths, atomicStore)
    private val themes = ThemeController(paths, atomicStore)
    private val uiState = UiStateController(paths, atomicStore)
    private val exports = ExportController(exportStore) { bridgeNonce }
    private val diagnostics = DiagnosticsController(paths, diagnosticLogger, exportStore)
    private val worldInfo = WorldInfoController(paths, atomicStore)
    private val characters = CharacterController(
        paths,
        exportStore = exportStore,
        store = atomicStore,
        worldInfoController = worldInfo
    )
    private val chatBackups = ChatBackupController(paths, exportStore = exportStore, diagnosticLogger = diagnosticLogger)
    private val chats = ChatController(
        paths,
        exportStore = exportStore,
        backupController = chatBackups,
        diagnosticLogger = diagnosticLogger
    )
    private val groups = GroupController(paths, store = atomicStore)
    private val groupChats = GroupChatController(paths, store = atomicStore)
    private val stats = StatsController(paths, diagnosticLogger)
    private val backgrounds = BackgroundController(paths, atomicStore)
    private val files = FileController(paths, atomicStore)
    private val imageMetadata = ImageMetadataController(paths, atomicStore)
    private val sprites = SpriteController(paths, atomicStore)
    private val tokenizer = TokenizerController()
    private val openAi = OpenAiCompatibleController(paths, diagnosticLogger = diagnosticLogger)
    private val extensionSubsystem = extensionSubsystemFactory(paths, atomicStore, diagnosticLogger)
    private val router = NativeRouter().also(::registerRoutes)

    override fun useGzipWhenAccepted(response: Response): Boolean =
        if (response.data is StreamingBodyInputStream) {
            false
        } else {
            super.useGzipWhenAccepted(response)
        }

    override fun serve(session: IHTTPSession): Response {
        exportStore.cleanupExpired()
        val request = if (session.method == Method.POST) {
            try {
                parseRequest(session)
            } catch (_: UploadTooLargeException) {
                return toLoggedNanoResponse(session, HttpResponse.json(413, """{"error":"upload_too_large"}"""))
            } catch (_: IOException) {
                return toLoggedNanoResponse(session, HttpResponse.json(400, """{"error":"invalid_request_body"}"""))
            } catch (_: ResponseException) {
                return toLoggedNanoResponse(session, HttpResponse.json(400, """{"error":"invalid_request_body"}"""))
            } catch (_: MultipartParseException) {
                return toLoggedNanoResponse(session, HttpResponse.json(400, """{"error":"invalid_request_body"}"""))
            }
        } else {
            NativeRequest(
                method = session.method.name,
                path = session.uri,
                query = parseQueryParameters(session.queryParameterString),
                form = emptyMap(),
                bodyText = "",
                uploads = emptyMap(),
                headers = session.headers
            )
        }

        val response = try {
            router.dispatch(request) ?: fallback(request)
        } finally {
            request.uploads.values.forEach { it.tempFile.delete() }
        }

        recordHttpDiagnostic(request.method, request.path, response.statusCode)
        return toNanoResponse(response)
    }

    override fun setExportBridgeNonce(nonce: String) {
        require(ExportMetadata.isToken(nonce)) { "Invalid bridge nonce" }
        bridgeNonce = nonce
    }

    override fun serverPort(): Int = listeningPort

    private fun registerRoutes(router: NativeRouter) {
        router.get("/version") { versionResponse() }
        router.get("/csrf-token") { HttpResponse.json(200, """{"token":"stapk-no-node"}""") }
        router.post("/api/ping") { HttpResponse.json(200, """{"pong":true}""") }
        router.post("/api/stapk/exports/create") { exports.create(it) }
        router.post("/api/stapk/diagnostics/summary") { diagnostics.summary() }
        router.post("/api/stapk/diagnostics/export") { diagnostics.export() }
        router.post("/api/settings/get") { settings.getSettings() }
        router.post("/api/settings/save") { settings.saveSettings(it.controllerBody()) }
        router.post("/api/settings/get-snapshots") { settings.getSnapshots() }
        router.post("/api/settings/load-snapshot") { settings.loadSnapshot(it.controllerBody()) }
        router.post("/api/settings/make-snapshot") { settings.makeSnapshot() }
        router.post("/api/settings/restore-snapshot") { settings.restoreSnapshot(it.controllerBody()) }
        router.get("/api/users/me") { settings.getCurrentUser() }
        router.post("/api/users/change-avatar") { settings.changeUserAvatar(it.controllerBody()) }
        router.post("/api/users/reset-settings") { settings.resetSettings() }
        router.post("/api/avatars/get") { personas.getAvatars() }
        router.post("/api/avatars/upload") { personas.uploadAvatar(it) }
        router.post("/api/avatars/delete") { personas.deleteAvatar(it.controllerBody()) }
        router.post("/api/themes/save") { themes.saveTheme(it.controllerBody()) }
        router.post("/api/themes/delete") { themes.deleteTheme(it.controllerBody()) }
        router.post("/api/presets/save") { presets.savePreset(it.controllerBody()) }
        router.post("/api/presets/delete") { presets.deletePreset(it.controllerBody()) }
        router.post("/api/presets/restore") { presets.restorePreset(it.controllerBody()) }
        router.post("/api/quick-replies/save") { uiState.saveQuickReplies(it.controllerBody()) }
        router.post("/api/quick-replies/delete") { uiState.deleteQuickReplies(it.controllerBody()) }
        router.post("/api/moving-ui/save") { uiState.saveMovingUi(it.controllerBody()) }
        router.post("/api/secrets/read") { openAi.readSecrets() }
        router.post("/api/secrets/write") { openAi.writeSecret(it.controllerBody()) }
        router.post("/api/secrets/delete") { openAi.deleteSecret(it.controllerBody()) }
        router.post("/api/backends/chat-completions/status") { openAi.status(it.controllerBody()) }
        router.post("/api/backends/chat-completions/generate") { openAi.generate(it.controllerBody()) }
        router.post("/api/backends/chat-completions/bias") {
            tokenizer.bias(it.query["model"]?.firstOrNull().orEmpty(), it.controllerBody())
        }
        router.post("/api/tokenizers/openai/encode") {
            tokenizer.encode(it.query["model"]?.firstOrNull().orEmpty(), it.controllerBody())
        }
        router.post("/api/tokenizers/openai/decode") {
            tokenizer.decode(it.query["model"]?.firstOrNull().orEmpty(), it.controllerBody())
        }
        router.post("/api/tokenizers/openai/count") {
            tokenizer.count(it.query["model"]?.firstOrNull().orEmpty(), it.controllerBody())
        }
        vectorRoutes?.let { vectors ->
            router.post("/api/vector/list") { vectors.list(it.controllerBody()) }
            router.post("/api/vector/insert") { vectors.insert(it.controllerBody()) }
            router.post("/api/vector/delete") { vectors.delete(it.controllerBody()) }
            router.post("/api/vector/query") { vectors.query(it.controllerBody()) }
            router.post("/api/vector/query-multi") { vectors.queryMulti(it.controllerBody()) }
            router.post("/api/vector/purge") { vectors.purge(it.controllerBody()) }
            router.post("/api/vector/purge-all") { vectors.purgeAll() }
            router.post("/api/stapk/embeddings/config/get") { vectors.getConfig() }
            router.post("/api/stapk/embeddings/config/save") { vectors.saveConfig(it.controllerBody()) }
            router.post("/api/stapk/embeddings/test") { vectors.testConfig() }
        }
        router.get("/api/extensions/discover") { extensionSubsystem.routes.discover() }
        router.post("/api/extensions/install") { extensionSubsystem.routes.install(it.controllerBody()) }
        router.post("/api/extensions/version") { extensionSubsystem.routes.version(it.controllerBody()) }
        router.post("/api/extensions/update") { extensionSubsystem.routes.update(it.controllerBody()) }
        router.post("/api/extensions/delete") { extensionSubsystem.routes.delete(it.controllerBody()) }
        router.post("/api/characters/all") { characters.allCharacters() }
        router.post("/api/characters/create") { characters.createCharacter(it.controllerBody()) }
        router.post("/api/characters/get") { characters.getCharacter(it.controllerBody()) }
        router.post("/api/characters/edit") { characters.editCharacter(it.controllerBody()) }
        router.post("/api/characters/delete") { characters.deleteCharacter(it.controllerBody()) }
        router.post("/api/characters/chats") { characters.characterChats(it.controllerBody()) }
        router.post("/api/characters/import") { characters.importCharacter(it) }
        router.post("/api/characters/export") { characters.exportCharacter(it.controllerBody()) }
        router.post("/api/characters/duplicate") { characters.duplicateCharacter(it.controllerBody()) }
        router.post("/api/characters/rename") { characters.renameCharacter(it.controllerBody()) }
        router.post("/api/characters/merge-attributes") { characters.mergeAttributes(it.controllerBody()) }
        router.post("/api/characters/edit-avatar") { characters.editAvatar(it) }
        router.post("/api/chats/get") { chats.getChat(it.controllerBody()) }
        router.post("/api/chats/save") { chats.saveChat(it.controllerBody()) }
        router.post("/api/chats/delete") { chats.deleteChat(it.controllerBody()) }
        router.post("/api/chats/search") { chats.searchChats(it.controllerBody()) }
        router.post("/api/chats/recent") { chats.recentChats(it.controllerBody()) }
        router.post("/api/chats/rename") { chats.renameChat(it.controllerBody()) }
        router.post("/api/chats/import") { chats.importChat(it) }
        router.post("/api/chats/export") { chats.exportChat(it.controllerBody()) }
        router.post("/api/backups/chat/get") { chatBackups.getBackups() }
        router.post("/api/backups/chat/download") { chatBackups.downloadBackup(it.controllerBody()) }
        router.post("/api/backups/chat/delete") { chatBackups.deleteBackup(it.controllerBody()) }
        router.post("/api/stats/get") { stats.getStats() }
        router.post("/api/stats/recreate") { stats.recreateStats() }
        router.post("/api/stats/update") { stats.updateStats(it.controllerBody()) }
        router.post("/api/worldinfo/list") { worldInfo.listWorldInfo() }
        router.post("/api/worldinfo/get") { worldInfo.getWorldInfo(it.controllerBody()) }
        router.post("/api/worldinfo/edit") { worldInfo.editWorldInfo(it.controllerBody()) }
        router.post("/api/worldinfo/delete") { worldInfo.deleteWorldInfo(it.controllerBody()) }
        router.post("/api/worldinfo/import") { worldInfo.importWorldInfo(it) }
        router.post("/api/backgrounds/all") { backgrounds.allBackgrounds() }
        router.post("/api/backgrounds/folders") { backgrounds.folders() }
        router.post("/api/backgrounds/upload") { backgrounds.uploadBackground(it) }
        router.post("/api/backgrounds/rename") { backgrounds.renameBackground(it.controllerBody()) }
        router.post("/api/backgrounds/delete") { backgrounds.deleteBackground(it.controllerBody()) }
        router.post("/api/files/sanitize-filename") { files.sanitizeFilename(it.controllerBody()) }
        router.post("/api/files/upload") { files.uploadFile(it) }
        router.post("/api/files/delete") { files.deleteFile(it.controllerBody()) }
        router.post("/api/files/verify") { files.verifyFiles(it.controllerBody()) }
        router.post("/api/images/upload") { imageMetadata.uploadImage(it.controllerBody()) }
        router.post("/api/images/list") { imageMetadata.listImages(it.controllerBody()) }
        router.post("/api/images/folders") { imageMetadata.listImageFolders() }
        router.post("/api/images/delete") { imageMetadata.deleteImage(it.controllerBody()) }
        router.post("/api/image-metadata") { imageMetadata.getMetadata(it.controllerBody()) }
        router.post("/api/image-metadata/all") { imageMetadata.allMetadata(it.controllerBody()) }
        router.post("/api/image-metadata/cleanup") { imageMetadata.cleanupMetadata() }
        router.post("/api/image-metadata/folders/get") { imageMetadata.getFolders() }
        router.post("/api/image-metadata/folders/create") { imageMetadata.createFolder(it.controllerBody()) }
        router.post("/api/image-metadata/folders/update") { imageMetadata.updateFolder(it.controllerBody()) }
        router.post("/api/image-metadata/folders/delete") { imageMetadata.deleteFolder(it.controllerBody()) }
        router.post("/api/image-metadata/folders/assign") { imageMetadata.assignFolder(it.controllerBody()) }
        router.post("/api/image-metadata/folders/unassign") { imageMetadata.unassignFolder(it.controllerBody()) }
        router.post("/api/image-metadata/folders/set-thumbnails") {
            imageMetadata.setFolderThumbnails(it.controllerBody())
        }
        router.get("/api/sprites/get") { sprites.getSprites(it.query["name"]?.firstOrNull().orEmpty()) }
        router.post("/api/sprites/upload") { sprites.uploadSprite(it) }
        router.post("/api/sprites/upload-zip") { sprites.uploadSpriteZip(it) }
        router.post("/api/sprites/delete") { sprites.deleteSprite(it.controllerBody()) }
        router.post("/api/groups/all") { groups.allGroups() }
        router.post("/api/groups/create") { groups.createGroup(it.controllerBody()) }
        router.post("/api/groups/edit") { groups.editGroup(it.controllerBody()) }
        router.post("/api/groups/delete") { groups.deleteGroup(it.controllerBody()) }
        router.post("/api/chats/group/get") { groupChats.getChat(it.controllerBody()) }
        router.post("/api/chats/group/save") { groupChats.saveChat(it.controllerBody()) }
        router.post("/api/chats/group/delete") { groupChats.deleteChat(it.controllerBody()) }
        router.post("/api/chats/group/info") { groupChats.info(it.controllerBody()) }
        router.post("/api/chats/group/import") { groupChats.importChat(it) }
    }

    override fun findExport(token: String): ExportTicket? = exportStore.find(token)

    override fun consumeExport(token: String): ExportTicket? = exportStore.consume(token)

    override fun releaseExport(token: String) = exportStore.release(token)

    private fun fallback(request: NativeRequest): HttpResponse = when {
        request.path.startsWith("/api/") -> HttpResponse.json(404, """{"error":"endpoint_not_found"}""")
        request.path == "/thumbnail" && request.query["type"]?.firstOrNull() == "persona" ->
            personas.serveAvatar(request.query["file"]?.firstOrNull().orEmpty())
        request.path == "/thumbnail" && request.query["type"]?.firstOrNull() == "avatar" ->
            characters.serveAvatar(request.query["file"]?.firstOrNull().orEmpty())
        request.path == "/thumbnail" && request.query["type"]?.firstOrNull() == "bg" ->
            staticAssets.serve("/backgrounds/${request.query["file"]?.firstOrNull().orEmpty()}")
        request.path.startsWith("/characters/") &&
            request.path.removePrefix("/characters/").contains('/') ->
            sprites.serveSprite(request.path.removePrefix("/characters/"))
        request.path.startsWith("/characters/") ->
            characters.serveAvatar(request.path.removePrefix("/characters/"))
        request.path.startsWith("/User Avatars/") ->
            personas.serveAvatar(URLDecoder.decode(request.path.removePrefix("/User Avatars/"), StandardCharsets.UTF_8.name()))
        else -> staticAssets.serve(request.path)
    }

    private fun toNanoResponse(response: HttpResponse): Response {
        val status = Response.Status.lookup(response.statusCode) ?: HttpStatus(response.statusCode)
        val nanoResponse = response.bodyStream?.let { bodyStream ->
            newChunkedResponse(status, response.mimeType, StreamingBodyInputStream(bodyStream))
        } ?: response.bodyFile?.let { bodyFile ->
            newFixedLengthResponse(
                status,
                response.mimeType,
                bodyFile.inputStream(),
                bodyFile.length()
            )
        } ?: response.bodyBytes?.let { bodyBytes ->
            newFixedLengthResponse(
                status,
                response.mimeType,
                bodyBytes.inputStream(),
                bodyBytes.size.toLong()
            )
        } ?: newFixedLengthResponse(status, response.mimeType, response.bodyText.orEmpty())
        response.headers.forEach(nanoResponse::addHeader)
        return nanoResponse
    }

    private fun toLoggedNanoResponse(session: IHTTPSession, response: HttpResponse): Response {
        recordHttpDiagnostic(session.method.name, session.uri, response.statusCode)
        return toNanoResponse(response)
    }

    private fun recordHttpDiagnostic(method: String, path: String, statusCode: Int) {
        if (statusCode < 400) return
        runCatching {
            diagnosticLogger.event(
                DiagnosticArea.HTTP,
                "http_error",
                mapOf(
                    "method" to method,
                    "path" to path,
                    "status" to statusCode.toString()
                )
            )
        }
    }

    internal fun parseRequest(session: IHTTPSession): NativeRequest =
        if (NanoFileUpload.isMultipartContent(session)) parseMultipartRequest(session) else parseNanoRequest(session)

    private fun parseNanoRequest(session: IHTTPSession): NativeRequest {
        if (session.headers.entries.any { (name, value) ->
                name.equals("transfer-encoding", ignoreCase = true) && value.isNotBlank()
            }
        ) {
            throw MultipartParseException()
        }
        session.headers.entries
            .firstOrNull { (name, _) -> name.equals("content-length", ignoreCase = true) }
            ?.value
            ?.let { rawContentLength ->
                if (rawContentLength.isEmpty() || rawContentLength.any { !it.isDigit() }) {
                    throw MultipartParseException()
                }
                val contentLength = rawContentLength.toLongOrNull() ?: throw MultipartParseException()
                if (contentLength > MAX_NON_MULTIPART_REQUEST_BYTES) throw UploadTooLargeException()
        }
        val files = HashMap<String, String>()
        val contentTypeEntry = session.headers.entries
            .firstOrNull { (name, _) -> name.equals("content-type", ignoreCase = true) }
        if (contentTypeEntry != null) {
            val normalized = normalizeJsonContentType(contentTypeEntry.value)
            if (normalized != contentTypeEntry.value) {
                session.headers[contentTypeEntry.key] = normalized
            }
        }
        session.parseBody(files)
        val bodyText = files[POST_DATA_KEY].orEmpty()
        if (bodyText.length.toLong() > MAX_NON_MULTIPART_REQUEST_BYTES) throw UploadTooLargeException()
        val uploads = files
            .filterKeys { it != POST_DATA_KEY }
            .mapValues { (fieldName, tempPath) ->
                val tempFile = File(tempPath)
                if (tempFile.length() > MAX_UPLOAD_BYTES) throw UploadTooLargeException()
                UploadedFile(
                    fieldName = fieldName,
                    originalName = session.parameters[fieldName]?.lastOrNull().orEmpty(),
                    mimeType = "application/octet-stream",
                    tempFile = tempFile
                )
            }
        val query = parseQueryParameters(session.queryParameterString)
        return NativeRequest(
            method = session.method.name,
            path = session.uri,
            query = query,
            form = formParameters(session.parameters, uploads, query),
            bodyText = bodyText,
            uploads = uploads,
            headers = session.headers
        )
    }

    private fun parseMultipartRequest(session: IHTTPSession): NativeRequest {
        val contentLength = session.headers.entries
            .firstOrNull { (name, _) -> name.equals("content-length", ignoreCase = true) }
            ?.value
            ?.let { rawContentLength ->
                if (rawContentLength.isEmpty() || rawContentLength.any { !it.isDigit() }) {
                    throw MultipartParseException()
                }
                rawContentLength.toLongOrNull() ?: throw MultipartParseException()
            }
        if (contentLength != null && contentLength > MAX_MULTIPART_REQUEST_BYTES) {
            throw UploadTooLargeException()
        }

        val uploads = linkedMapOf<String, UploadedFile>()
        val form = linkedMapOf<String, MutableList<String>>()
        try {
            val upload = NanoFileUpload(DiskFileItemFactory()).apply {
                sizeMax = MAX_MULTIPART_REQUEST_BYTES
                fileSizeMax = MAX_UPLOAD_BYTES
                fileCountMax = MAX_MULTIPART_PARTS
                partHeaderSizeMax = MAX_MULTIPART_HEADER_BYTES
            }
            val iterator = upload.getItemIterator(session)
            while (iterator.hasNext()) {
                val item = iterator.next()
                val fieldName = item.fieldName ?: throw MultipartParseException()
                item.openStream().use { input ->
                    if (item.isFormField) {
                        form.getOrPut(fieldName) { mutableListOf() }.add(readBoundedText(input))
                    } else {
                        if (fieldName in uploads) throw MultipartParseException()
                        val temporaryDir = File(paths.userDataDir, "multipart")
                        check(temporaryDir.exists() || temporaryDir.mkdirs()) { "Unable to create multipart directory" }
                        val temporary = File.createTempFile("stapk-upload-", ".tmp", temporaryDir)
                        try {
                            writeBoundedUpload(input, temporary)
                            uploads[fieldName] = UploadedFile(
                                fieldName,
                                item.name.orEmpty(),
                                item.contentType?.substringBefore(';')?.trim()?.ifBlank { null } ?: "application/octet-stream",
                                temporary
                            )
                        } catch (exception: Exception) {
                            temporary.delete()
                            throw exception
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            uploads.values.forEach { it.tempFile.delete() }
            throw classifyMultipartFailure(exception)
        }

        val query = parseQueryParameters(session.queryParameterString)
        return NativeRequest(
            method = session.method.name,
            path = session.uri,
            query = query,
            form = form,
            bodyText = "",
            uploads = uploads,
            headers = session.headers
        )
    }

    private fun readBoundedText(input: java.io.InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_MULTIPART_FIELD_BYTES) throw UploadTooLargeException()
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }
    private fun writeBoundedUpload(input: java.io.InputStream, target: File) {
        target.parentFile?.mkdirs()
        var total = 0L
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_UPLOAD_BYTES) throw UploadTooLargeException()
                output.write(buffer, 0, count)
            }
        }
    }

    private fun classifyMultipartFailure(exception: Exception): RuntimeException {
        var cause: Throwable? = exception
        var isUploadTooLarge = false
        while (cause != null) {
            if (
                cause is UploadTooLargeException ||
                cause is FileUploadBase.FileSizeLimitExceededException ||
                (cause is FileUploadBase.SizeLimitExceededException &&
                    cause.permittedSize >= MAX_MULTIPART_REQUEST_BYTES)
            ) {
                isUploadTooLarge = true
                break
            }
            cause = cause.cause
        }
        return if (isUploadTooLarge) UploadTooLargeException(exception) else MultipartParseException(exception)
    }

    private fun versionResponse(): HttpResponse {
        val upstream = runCatching {
            JsonParser.parseString(paths.webManifestFile.readText()).asJsonObject.getAsJsonObject("upstream")
        }.getOrNull()
        val pkgVersion = upstream?.get("version")?.asString ?: "unknown"
        val body = JsonObject().apply {
            addProperty("agent", "SillyTavern:$pkgVersion:Cohee#1207")
            addProperty("node_runtime", false)
            addProperty("pkgVersion", pkgVersion)
            addProperty("gitRevision", upstream?.get("commit")?.asString ?: "unknown")
            addProperty("gitBranch", upstream?.get("ref")?.asString ?: "unknown")
        }
        return HttpResponse.json(200, body.toString())
    }

    private fun NativeRequest.controllerBody(): String {
        if (bodyText.isNotBlank()) return bodyText
        return JsonObject().apply {
            form.forEach { (key, values) ->
                if (values.size <= 1) {
                    addProperty(key, values.firstOrNull().orEmpty())
                } else {
                    add(key, JsonArray().apply { values.forEach { add(it) } })
                }
            }
        }.toString()
    }

    private fun parseQueryParameters(raw: String?): Map<String, List<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        val values = linkedMapOf<String, MutableList<String>>()
        raw.split('&').forEach { item ->
            val pair = item.split('=', limit = 2)
            val key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8)
            val value = URLDecoder.decode(pair.getOrElse(1) { "" }, StandardCharsets.UTF_8)
            values.getOrPut(key) { mutableListOf() }.add(value)
        }
        return values
    }

    private fun formParameters(
        parameters: Map<String, List<String>>,
        uploads: Map<String, UploadedFile>,
        query: Map<String, List<String>>
    ): Map<String, List<String>> = parameters
        .filterKeys { it !in uploads }
        .mapValues { (key, values) ->
            values.toMutableList().also { formValues ->
                query[key].orEmpty().forEach(formValues::remove)
            }
        }
        .filterValues { it.isNotEmpty() }

    private data class HttpStatus(private val statusCode: Int) : Response.IStatus {
        override fun getRequestStatus(): Int = statusCode

        override fun getDescription(): String = "$statusCode Provider Response"
    }

    private class UploadTooLargeException(cause: Throwable? = null) : RuntimeException(cause)
    private class MultipartParseException(cause: Throwable? = null) : RuntimeException(cause)
    private class StreamingBodyInputStream(body: InputStream) : FilterInputStream(body)

    companion object {
        internal fun normalizeJsonContentType(value: String): String {
            val parts = value.split(';')
            val mimeType = parts.firstOrNull().orEmpty().trim().lowercase(Locale.US)
            val isJson = mimeType == "application/json" || mimeType.endsWith("+json")
            val hasCharset = parts.drop(1).any { parameter ->
                parameter.substringBefore('=').trim().equals("charset", ignoreCase = true)
            }
            return if (isJson && !hasCharset) "$value; charset=UTF-8" else value
        }

        private const val POST_DATA_KEY = "postData"
        private const val MAX_UPLOAD_BYTES = 32L * 1024L * 1024L
        private const val MAX_NON_MULTIPART_REQUEST_BYTES = 48L * 1024L * 1024L
        private const val MAX_MULTIPART_REQUEST_BYTES = MAX_UPLOAD_BYTES + 1024L * 1024L
        private const val MAX_MULTIPART_FIELD_BYTES = 1024L * 1024L
        private const val MAX_MULTIPART_PARTS = 32L
        private const val MAX_MULTIPART_HEADER_BYTES = 8 * 1024
    }
}

private object ExtensionSubsystemFactoryConstructorMarker
