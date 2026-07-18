package com.stapk.mobile.nativeadapter

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CharacterController(
    private val paths: NativeAdapterPaths,
    private val codec: CharacterCardCodec = CharacterCardCodec(),
    private val exportStore: ExportStore = ExportStore(File(paths.userDataDir, "exports")),
    private val store: AtomicFileStore = AtomicFileStore(paths.quarantineDir),
    private val worldInfoController: WorldInfoController = WorldInfoController(paths, store),
    private val avatarNormalizer: AvatarImageNormalizer = AndroidAvatarImageNormalizer,
    private val pathCopier: (File, File) -> Unit = { source, target -> copyPathWithoutReplace(source, target) },
    private val jsonMover: (File, File) -> Unit = { source, target -> moveFileWithoutReplace(source, target) }
) {
    private val gson = GsonBuilder().serializeNulls().create()

    fun allCharacters(): HttpResponse {
        paths.charactersDir.mkdirs()
        val characters = JsonArray()
        paths.charactersDir.listFiles { file -> file.isFile && file.extension.equals("json", true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val character = readCharacter(file) ?: return@forEach
                character.addProperty("avatar", "${file.nameWithoutExtension}.png")
                characters.add(character)
            }
        return HttpResponse.json(200, gson.toJson(characters))
    }

    fun getCharacter(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidCharacterResponse()
        val stem = avatarStem(request.get("avatar_url")?.asString)
            ?: return invalidCharacterResponse()
        val file = File(paths.charactersDir, "$stem.json")
        if (!file.isFile) return HttpResponse.json(404, """{"error":"character_not_found"}""")
        val rawCard = file.readText()
        val character = runCatching { JsonParser.parseString(rawCard).asJsonObject }.getOrNull()
            ?: return HttpResponse.json(500, """{"error":"invalid_character_data"}""")
        character.addProperty("json_data", rawCard)
        character.addProperty("avatar", "$stem.png")
        return HttpResponse.json(200, gson.toJson(character))
    }

    @Synchronized
    fun importCharacter(request: NativeRequest): HttpResponse {
        val upload = request.uploads["avatar"] ?: return invalidCharacterResponse()
        val format = request.form["file_type"]?.firstOrNull()?.lowercase(Locale.US).orEmpty()
        val extension = upload.originalName.substringAfterLast('.', "").lowercase(Locale.US)
        if (format !in SUPPORTED_CARD_FORMATS || extension != format) return invalidCharacterResponse()
        if (!upload.tempFile.isFile || upload.tempFile.length() > MAX_CARD_IMPORT_BYTES) {
            return invalidCharacterResponse()
        }

        val decoded = runCatching {
            when (format) {
                "png" -> codec.decodePng(upload.tempFile.readBytes())
                "json" -> codec.decodeJson(upload.tempFile.readBytes())
                else -> error("Unsupported character format")
            }
        }.getOrNull() ?: return invalidCharacterResponse()
        val avatarBytes = decoded.avatarBytes ?: defaultAvatarFile().takeIf(File::isFile)?.readBytes()
            ?: return invalidCharacterResponse()
        val preservedName = request.form["preserved_name"]?.firstOrNull().orEmpty()
        val data = decoded.json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        val characterName = data?.stringValue("name")
            .orEmpty().ifBlank { decoded.json.stringValue("name") }
            .ifBlank { upload.originalName.substringBeforeLast('.') }
        val stem = if (preservedName.isNotBlank()) {
            avatarStem(preservedName) ?: return invalidCharacterResponse()
        } else {
            uniqueStem(slug(characterName))
        }
        val embeddedBook = data?.get("character_book")?.takeUnless { it.isJsonNull }
        val embeddedWorld = if (embeddedBook != null) {
            val book = embeddedBook.takeIf { it.isJsonObject }?.asJsonObject
                ?: return invalidCharacterResponse()
            try {
                worldInfoController.importEmbeddedCharacterBook(book, "$characterName's Lorebook")
            } catch (_: IllegalArgumentException) {
                return invalidCharacterResponse()
            } catch (_: Exception) {
                return HttpResponse.json(500, """{"error":"world_info_import_failed"}""")
            }
        } else {
            null
        }
        if (embeddedWorld != null) {
            val extensions = data?.get("extensions")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: JsonObject().also { data?.add("extensions", it) }
            extensions.addProperty("world", embeddedWorld.name)
        }

        paths.charactersDir.mkdirs()
        val jsonFile = File(paths.charactersDir, "$stem.json")
        val avatarFile = File(paths.charactersDir, "$stem.png")
        return try {
            writeCharacterFiles(jsonFile, gson.toJson(decoded.json).toByteArray(Charsets.UTF_8), avatarFile, avatarBytes)
            HttpResponse.json(200, gson.toJson(JsonObject().apply {
                addProperty("file_name", stem)
                embeddedWorld?.let { world ->
                    add("embedded_world", JsonObject().apply {
                        addProperty("name", world.name)
                        addProperty("entry_count", world.entryCount)
                        addProperty("created", world.created)
                    })
                }
            }))
        } catch (_: Exception) {
            embeddedWorld?.let { world ->
                runCatching { worldInfoController.rollbackEmbeddedCharacterBook(world) }
            }
            HttpResponse.json(500, """{"error":"character_import_failed"}""")
        }
    }

    fun exportCharacter(body: String): HttpResponse {
        val input = parseObject(body) ?: return invalidCharacterResponse()
        val format = input.stringValue("format").lowercase(Locale.US)
        if (format !in SUPPORTED_CARD_FORMATS) return invalidCharacterResponse()
        val stem = avatarStem(input.stringValue("avatar_url")) ?: return invalidCharacterResponse()
        val characterFile = File(paths.charactersDir, "$stem.json")
        if (!characterFile.isFile) return HttpResponse.json(404, """{"error":"character_not_found"}""")
        val character = runCatching { codec.decodeJson(characterFile.readBytes()).json }.getOrNull()
            ?: return HttpResponse.json(500, """{"error":"invalid_character_data"}""")

        return try {
            exportStore.cleanupExpired()
            val mimeType = if (format == "png") "image/png" else "application/json"
            val ticket = exportStore.create("$stem.$format", mimeType) { target ->
                if (format == "json") {
                    target.writeText(gson.toJson(character), Charsets.UTF_8)
                } else {
                    val avatar = File(paths.charactersDir, "$stem.png").takeIf(File::isFile) ?: defaultAvatarFile()
                    require(avatar.isFile) { "Default avatar is missing" }
                    target.writeBytes(codec.encodePng(avatar.readBytes(), character))
                }
            }
            HttpResponse.file(ticket.file, ticket.fileName, ticket.token, ticket.mimeType)
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"character_export_failed"}""")
        }
    }

    fun cleanupExports() {
        exportStore.cleanupExpired()
    }

    @Synchronized
    fun editAvatar(request: NativeRequest): HttpResponse {
        val stem = avatarStem(request.form["avatar_url"]?.firstOrNull())
            ?: return invalidCharacterResponse()
        if (!File(paths.charactersDir, "$stem.json").isFile) {
            return HttpResponse.json(404, """{"error":"character_not_found"}""")
        }
        val upload = request.uploads["avatar"] ?: return invalidAvatarImageResponse()
        if (!upload.tempFile.isFile || upload.tempFile.length() > MAX_CARD_IMPORT_BYTES) {
            return invalidAvatarImageResponse()
        }
        val source = upload.tempFile.readBytes()
        if (!isSupportedAvatarImage(source)) return invalidAvatarImageResponse()
        val normalized = runCatching { avatarNormalizer.toPng(source) }.getOrNull()
            ?: return invalidAvatarImageResponse()
        if (!isPng(normalized)) return invalidAvatarImageResponse()

        return try {
            store.writeBytes(File(paths.charactersDir, "$stem.png"), normalized)
            HttpResponse.json(200, gson.toJson(JsonObject().apply { addProperty("avatar", "$stem.png") }))
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"avatar_write_failed"}""")
        }
    }

    @Synchronized
    fun duplicateCharacter(body: String): HttpResponse {
        val input = parseObject(body) ?: return invalidCharacterResponse()
        val sourceStem = avatarStem(input.stringValue("avatar_url")) ?: return invalidCharacterResponse()
        val sourceJson = File(paths.charactersDir, "$sourceStem.json")
        if (!sourceJson.isFile) return HttpResponse.json(404, """{"error":"character_not_found"}""")
        val sourceAvatar = File(paths.charactersDir, "$sourceStem.png").takeIf(File::isFile) ?: defaultAvatarFile()
        if (!sourceAvatar.isFile) return HttpResponse.json(500, """{"error":"avatar_not_found"}""")
        val targetStem = uniqueStem(sourceStem)

        return try {
            writeCharacterFiles(
                File(paths.charactersDir, "$targetStem.json"),
                sourceJson.readBytes(),
                File(paths.charactersDir, "$targetStem.png"),
                sourceAvatar.readBytes()
            )
            HttpResponse.json(200, gson.toJson(JsonObject().apply { addProperty("path", "$targetStem.png") }))
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"character_duplicate_failed"}""")
        }
    }

    @Synchronized
    fun renameCharacter(body: String): HttpResponse {
        val input = parseObject(body) ?: return invalidCharacterResponse()
        val sourceStem = avatarStem(input.stringValue("avatar_url")) ?: return invalidCharacterResponse()
        val newName = input.stringValue("new_name").trim()
        if (newName.isBlank()) return invalidCharacterResponse()
        val targetStem = slug(newName)
        val sourceJson = File(paths.charactersDir, "$sourceStem.json")
        if (!sourceJson.isFile) return HttpResponse.json(404, """{"error":"character_not_found"}""")
        val originalJson = sourceJson.readBytes()
        val character = readCharacter(sourceJson)
            ?: return HttpResponse.json(500, """{"error":"invalid_character_data"}""")
        character.addProperty("name", newName)
        character.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.addProperty("name", newName)
        if (sourceStem == targetStem) {
            store.writeText(sourceJson, gson.toJson(character))
            return HttpResponse.json(200, gson.toJson(JsonObject().apply { addProperty("avatar", "$sourceStem.png") }))
        }

        val sourceAvatar = File(paths.charactersDir, "$sourceStem.png")
        val sourceChats = File(paths.chatsDir, sourceStem)
        val sourceSprites = File(paths.charactersDir, sourceStem)
        val targetJson = File(paths.charactersDir, "$targetStem.json")
        val targetAvatar = File(paths.charactersDir, "$targetStem.png")
        val targetChats = File(paths.chatsDir, targetStem)
        val targetSprites = File(paths.charactersDir, targetStem)
        if (targetJson.exists() || targetAvatar.exists() || targetChats.exists() || targetSprites.exists()) {
            return HttpResponse.json(409, """{"error":"character_name_conflict"}""")
        }

        try {
            if (sourceAvatar.exists()) {
                pathCopier(sourceAvatar, targetAvatar)
            }
            if (sourceChats.exists()) {
                pathCopier(sourceChats, targetChats)
            }
            if (sourceSprites.exists()) {
                pathCopier(sourceSprites, targetSprites)
            }
            // 源 JSON 内容先原子更新，再用单次 move 切换公开 identity。
            store.writeText(sourceJson, gson.toJson(character))
            jsonMover(sourceJson, targetJson)
        } catch (_: Exception) {
            if (sourceJson.isFile) runCatching { store.writeBytes(sourceJson, originalJson) }
            targetJson.delete()
            targetAvatar.delete()
            targetChats.deleteRecursively()
            targetSprites.deleteRecursively()
            return HttpResponse.json(500, """{"error":"character_rename_failed"}""")
        }
        runCatching { sourceAvatar.delete() }
        runCatching { sourceChats.deleteRecursively() }
        runCatching { sourceSprites.deleteRecursively() }
        return HttpResponse.json(200, gson.toJson(JsonObject().apply { addProperty("avatar", "$targetStem.png") }))
    }

    @Synchronized
    fun mergeAttributes(body: String): HttpResponse {
        val input = parseObject(body) ?: return invalidCharacterResponse()
        return if (input.get("avatars")?.isJsonArray == true) {
            mergeAttributesBatch(input)
        } else {
            mergeAttributesSingle(input)
        }
    }

    @Synchronized
    fun createCharacter(body: String): HttpResponse {
        val input = parseObject(body) ?: return invalidCharacterResponse()
        val name = input.stringValue("ch_name").ifBlank { input.stringValue("name") }.trim()
        if (name.isBlank()) return invalidCharacterResponse()

        paths.charactersDir.mkdirs()
        val stem = uniqueStem(slug(name))
        val character = buildCharacter(input, name)
        store.writeText(File(paths.charactersDir, "$stem.json"), gson.toJson(character))
        File(paths.chatsDir, stem).mkdirs()
        return HttpResponse.text(200, "$stem.png")
    }

    @Synchronized
    fun editCharacter(body: String): HttpResponse {
        val input = parseObject(body) ?: return invalidCharacterResponse()
        val stem = avatarStem(input.stringValue("avatar_url"))
            ?: return invalidCharacterResponse()
        val file = File(paths.charactersDir, "$stem.json")
        val character = readCharacter(file)
            ?: return HttpResponse.json(404, """{"error":"character_not_found"}""")
        val data = character.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { character.add("data", it) }

        val name = input.stringValue("ch_name").ifBlank { input.stringValue("name") }
        if (name.isNotBlank()) {
            character.addProperty("name", name)
            data.addProperty("name", name)
        }
        updateTextField(character, data, input, "description")
        updateTextField(character, data, input, "personality")
        updateTextField(character, data, input, "scenario")
        updateTextField(character, data, input, "mes_example")
        val firstMessage = input.get("first_mes") ?: input.get("first_message")
        firstMessage?.takeIf { it.isJsonPrimitive }?.let {
            character.add("first_mes", it.deepCopy())
            data.add("first_mes", it.deepCopy())
        }
        input.get("creator_notes")?.takeIf { it.isJsonPrimitive }?.let {
            character.add("creatorcomment", it.deepCopy())
            data.add("creator_notes", it.deepCopy())
        }
        listOf("system_prompt", "post_history_instructions", "creator", "character_version").forEach { key ->
            input.get(key)?.takeIf { it.isJsonPrimitive }?.let { data.add(key, it.deepCopy()) }
        }
        if (input.has("tags")) {
            val tags = input.tagsValue()
            character.add("tags", tags.deepCopy())
            data.add("tags", tags)
        }
        if (input.has("alternate_greetings")) {
            data.add("alternate_greetings", input.alternateGreetingsValue())
        }
        val extensions = data.get("extensions")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { data.add("extensions", it) }
        input.get("world")?.takeIf { it.isJsonPrimitive }
            ?.let { extensions.add("world", it.deepCopy()) }
        if (input.has("depth_prompt_prompt") || input.has("depth_prompt_depth") || input.has("depth_prompt_role")) {
            val depthPrompt = extensions.get("depth_prompt")?.takeIf { it.isJsonObject }?.asJsonObject
                ?: JsonObject().also { extensions.add("depth_prompt", it) }
            input.get("depth_prompt_prompt")?.takeIf { it.isJsonPrimitive }
                ?.let { depthPrompt.add("prompt", it.deepCopy()) }
            input.stringValue("depth_prompt_depth").toIntOrNull()
                ?.let { depthPrompt.addProperty("depth", it) }
            input.get("depth_prompt_role")?.takeIf { it.isJsonPrimitive }
                ?.let { depthPrompt.add("role", it.deepCopy()) }
        }
        parseEmbeddedObject(input.stringValue("extensions"))?.let { mergeInto(extensions, it) }
        input.get("chat")?.takeIf { it.isJsonPrimitive }?.let { character.add("chat", it.deepCopy()) }

        store.writeText(file, gson.toJson(character))
        return HttpResponse.json(200, "{}")
    }

    fun serveAvatar(avatar: String): HttpResponse {
        val stem = avatarStem(avatar) ?: return invalidCharacterResponse()
        if (!File(paths.charactersDir, "$stem.json").isFile) {
            return HttpResponse.json(404, """{"error":"character_not_found"}""")
        }
        val storedAvatar = File(paths.charactersDir, "$stem.png")
        if (storedAvatar.isFile) {
            return HttpResponse(200, "image/png", bodyBytes = storedAvatar.readBytes())
        }
        val defaultAvatar = File(paths.webDir, "img/ai4.png")
        if (!defaultAvatar.isFile) {
            return HttpResponse.json(404, """{"error":"avatar_not_found"}""")
        }
        return HttpResponse(200, "image/png", bodyBytes = defaultAvatar.readBytes())
    }

    @Synchronized
    fun deleteCharacter(body: String): HttpResponse {
        val input = parseObject(body) ?: return invalidCharacterResponse()
        val stem = avatarStem(input.stringValue("avatar_url"))
            ?: return invalidCharacterResponse()
        val file = File(paths.charactersDir, "$stem.json")
        if (!file.isFile) return invalidCharacterResponse()
        file.delete()
        File(paths.charactersDir, stem).deleteRecursively()
        if (input.get("delete_chats")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
            File(paths.chatsDir, stem).deleteRecursively()
        }
        return HttpResponse.json(200, "{}")
    }

    fun characterChats(body: String): HttpResponse {
        val request = parseObject(body) ?: return invalidCharacterResponse()
        val stem = avatarStem(request.stringValue("avatar_url"))
            ?: return invalidCharacterResponse()
        val simple = request.get("simple")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        val includeMetadata = request.get("metadata")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        val summaries = JsonArray()
        File(paths.chatsDir, stem).listFiles { file -> file.isFile && file.extension.equals("jsonl", true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val lines = file.readLines().filter { it.isNotBlank() }
                val objects = lines.mapNotNull { line ->
                    runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                }
                val lastMessage = objects.drop(1).lastOrNull()
                summaries.add(JsonObject().apply {
                    addProperty("file_name", file.name)
                    addProperty("file_id", file.nameWithoutExtension)
                    if (!simple) {
                        addProperty("file_size", "${file.length()} B")
                        addProperty("chat_items", (objects.size - 1).coerceAtLeast(0))
                        addProperty("mes", lastMessage?.stringValue("mes") ?: "[The chat is empty]")
                        if (lastMessage?.has("send_date") == true) {
                            add("last_mes", lastMessage.get("send_date").deepCopy())
                        } else {
                            addProperty("last_mes", file.lastModified())
                        }
                        if (includeMetadata && objects.firstOrNull()?.has("chat_metadata") == true) {
                            add("chat_metadata", objects.first().get("chat_metadata").deepCopy())
                        }
                    }
                })
            }
        return HttpResponse.json(200, gson.toJson(summaries))
    }

    private fun buildCharacter(input: JsonObject, name: String): JsonObject {
        val description = input.stringValue("description")
        val personality = input.stringValue("personality")
        val scenario = input.stringValue("scenario")
        val firstMessage = input.stringValue("first_mes").ifBlank { input.stringValue("first_message") }
        val messageExample = input.stringValue("mes_example")
        val creatorNotes = input.stringValue("creator_notes")
        val creator = input.stringValue("creator")
        val version = input.stringValue("character_version")
        val tags = input.tagsValue()
        val talkativeness = input.stringValue("talkativeness").toDoubleOrNull() ?: 0.5
        val favorite = input.stringValue("fav").equals("true", true)
        val createdAt = isoTimestamp()
        val character = parseEmbeddedObject(input.stringValue("json_data")) ?: JsonObject()
        val data = character.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { character.add("data", it) }
        val extensions = data.get("extensions")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: JsonObject().also { data.add("extensions", it) }

        character.apply {
            addProperty("name", name)
            addProperty("description", description)
            addProperty("personality", personality)
            addProperty("scenario", scenario)
            addProperty("first_mes", firstMessage)
            addProperty("mes_example", messageExample)
            addProperty("creatorcomment", creatorNotes)
            addProperty("avatar", "none")
            addProperty("chat", "$name - Chat")
            addProperty("talkativeness", talkativeness)
            addProperty("fav", favorite)
            add("tags", tags.deepCopy())
            addProperty("spec", "chara_card_v2")
            addProperty("spec_version", "2.0")
            addProperty("create_date", createdAt)
        }
        data.apply {
            addProperty("name", name)
            addProperty("description", description)
            addProperty("personality", personality)
            addProperty("scenario", scenario)
            addProperty("first_mes", firstMessage)
            addProperty("mes_example", messageExample)
            addProperty("creator_notes", creatorNotes)
            addProperty("system_prompt", input.stringValue("system_prompt"))
            addProperty("post_history_instructions", input.stringValue("post_history_instructions"))
            addProperty("creator", creator)
            addProperty("character_version", version)
            add("tags", tags.deepCopy())
            add("alternate_greetings", input.alternateGreetingsValue())
        }
        extensions.apply {
            addProperty("talkativeness", talkativeness)
            addProperty("fav", favorite)
            addProperty("world", input.stringValue("world"))
            add("depth_prompt", JsonObject().apply {
                addProperty("prompt", input.stringValue("depth_prompt_prompt"))
                addProperty("depth", input.stringValue("depth_prompt_depth").toIntOrNull() ?: 4)
                addProperty("role", input.stringValue("depth_prompt_role").ifBlank { "system" })
            })
        }
        parseEmbeddedObject(input.stringValue("extensions"))?.let { mergeInto(extensions, it) }
        return character
    }

    private fun readCharacter(file: File): JsonObject? {
        if (!file.isFile) return null
        return runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
    }

    private fun writeCharacterFiles(jsonFile: File, jsonBytes: ByteArray, avatarFile: File, avatarBytes: ByteArray) {
        val previousJson = jsonFile.takeIf(File::isFile)?.readBytes()
        val previousAvatar = avatarFile.takeIf(File::isFile)?.readBytes()
        try {
            store.writeBytes(jsonFile, jsonBytes)
            store.writeBytes(avatarFile, avatarBytes)
        } catch (exception: Exception) {
            restoreFile(jsonFile, previousJson)
            restoreFile(avatarFile, previousAvatar)
            throw exception
        }
    }

    private fun restoreFile(file: File, previous: ByteArray?) {
        if (previous == null) {
            file.delete()
        } else {
            store.writeBytes(file, previous)
        }
    }

    private fun defaultAvatarFile(): File = File(paths.webDir, "img/ai4.png")

    private fun isSupportedAvatarImage(bytes: ByteArray): Boolean =
        isPng(bytes) ||
            (bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()) ||
            (bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()))

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= PNG_SIGNATURE.size && bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)

    private fun updateTextField(
        character: JsonObject,
        data: JsonObject,
        input: JsonObject,
        key: String
    ) {
        input.get(key)?.takeIf { it.isJsonPrimitive }?.let {
            character.add(key, it.deepCopy())
            data.add(key, it.deepCopy())
        }
    }

    private fun parseObject(body: String): JsonObject? = try {
        JsonParser.parseString(body.ifBlank { "{}" }).asJsonObject
    } catch (_: IllegalStateException) {
        null
    } catch (_: JsonParseException) {
        null
    }

    private fun parseEmbeddedObject(value: String): JsonObject? {
        if (value.isBlank()) return null
        return runCatching { JsonParser.parseString(value).asJsonObject }.getOrNull()
    }

    private fun mergeAttributesSingle(input: JsonObject): HttpResponse {
        val stem = avatarStem(input.stringValue("avatar")) ?: return invalidCharacterResponse()
        val file = File(paths.charactersDir, "$stem.json")
        val character = readCharacter(file)
            ?: return HttpResponse.json(404, """{"error":"character_not_found"}""")
        val update = input.deepCopy().apply {
            remove("avatar")
            remove("avatars")
            remove("filter")
        }
        return try {
            mergeInto(character, update)
            store.writeText(file, gson.toJson(character))
            HttpResponse.json(200, "{}")
        } catch (_: Exception) {
            HttpResponse.json(500, """{"error":"character_merge_failed"}""")
        }
    }

    private fun mergeAttributesBatch(input: JsonObject): HttpResponse {
        val payload = input.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return invalidCharacterResponse()
        val requested = input.getAsJsonArray("avatars")
        val targets = if (requested.size() == 0) {
            paths.charactersDir.listFiles { file -> file.isFile && file.extension.equals("json", true) }
                .orEmpty().sortedBy(File::getName).map { "${it.nameWithoutExtension}.png" }
        } else {
            requested.mapNotNull { value -> value.takeIf { it.isJsonPrimitive }?.asString }
        }
        val filterPath = input.get("filter")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("path")?.takeIf { it.isJsonPrimitive }?.asString
        val updated = JsonArray()
        val skipped = JsonArray()
        val failed = JsonArray()

        targets.forEach { avatar ->
            val stem = avatarStem(avatar)
            if (stem == null) {
                failed.add(avatar)
                return@forEach
            }
            val file = File(paths.charactersDir, "$stem.json")
            val character = readCharacter(file)
            if (character == null) {
                failed.add(avatar)
                return@forEach
            }
            if (filterPath != null && !hasPath(character, filterPath)) {
                skipped.add(avatar)
                return@forEach
            }
            try {
                mergeInto(character, payload)
                store.writeText(file, gson.toJson(character))
                updated.add(avatar)
            } catch (_: Exception) {
                failed.add(avatar)
            }
        }
        return HttpResponse.json(200, gson.toJson(JsonObject().apply {
            add("updated", updated)
            add("skipped", skipped)
            add("failed", failed)
        }))
    }

    private fun hasPath(root: JsonObject, path: String): Boolean {
        if (path.isBlank()) return false
        var current: com.google.gson.JsonElement = root
        path.split('.').forEach { segment ->
            if (!current.isJsonObject || !current.asJsonObject.has(segment)) return false
            current = current.asJsonObject.get(segment)
        }
        return true
    }

    private fun mergeInto(target: JsonObject, source: JsonObject) {
        source.entrySet().forEach { (key, value) ->
            val existing = target.get(key)
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString == UNSET_VALUE) {
                target.remove(key)
            } else if (existing?.isJsonObject == true && value.isJsonObject) {
                mergeInto(existing.asJsonObject, value.asJsonObject)
            } else {
                target.add(key, value.deepCopy())
            }
        }
    }

    private fun uniqueStem(base: String): String {
        var index = 0
        var candidate = base
        while (
            File(paths.charactersDir, "$candidate.json").exists() ||
            File(paths.charactersDir, "$candidate.png").exists() ||
            File(paths.chatsDir, candidate).exists()
        ) {
            index += 1
            candidate = "$base$index"
        }
        return candidate
    }

    private fun slug(value: String): String =
        value.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "character" }

    private fun avatarStem(value: String?): String? {
        val avatar = value.orEmpty()
        if (!avatar.matches(Regex("[A-Za-z0-9_-]+\\.png"))) return null
        return avatar.removeSuffix(".png")
    }

    private fun JsonObject.stringValue(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.tagsValue(): JsonArray {
        val value = get("tags")
        if (value?.isJsonArray == true) return value.asJsonArray.deepCopy()
        return JsonArray().apply {
            stringValue("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(::add)
        }
    }

    private fun JsonObject.alternateGreetingsValue(): JsonArray {
        val value = get("alternate_greetings")
        if (value?.isJsonArray == true) return value.asJsonArray.deepCopy()
        return JsonArray().apply {
            stringValue("alternate_greetings").takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun isoTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun invalidCharacterResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_character"}""")

    private fun invalidAvatarImageResponse(): HttpResponse =
        HttpResponse.json(400, """{"error":"invalid_avatar_image"}""")

    private companion object {
        val SUPPORTED_CARD_FORMATS = setOf("png", "json")
        const val UNSET_VALUE = "__@@UNSET@@__"
        const val MAX_CARD_IMPORT_BYTES = 32L * 1024L * 1024L
        val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

        fun copyPathWithoutReplace(source: File, target: File) {
            require(!target.exists()) { "Copy target already exists" }
            requireNotNull(target.parentFile).mkdirs()
            if (source.isDirectory) {
                check(source.copyRecursively(target, overwrite = false)) { "Unable to copy directory" }
            } else {
                source.copyTo(target, overwrite = false)
            }
        }

        fun moveFileWithoutReplace(source: File, target: File) {
            require(!target.exists()) { "Move target already exists" }
            requireNotNull(target.parentFile).mkdirs()
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
        }
    }
}
