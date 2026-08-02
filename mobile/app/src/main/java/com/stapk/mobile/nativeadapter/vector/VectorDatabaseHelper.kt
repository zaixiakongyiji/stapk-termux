package com.stapk.mobile.nativeadapter.vector

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import com.stapk.mobile.nativeadapter.DiagnosticArea
import android.database.sqlite.SQLiteOpenHelper
import com.stapk.mobile.nativeadapter.DiagnosticLogger
import com.stapk.mobile.nativeadapter.NativeAdapterPaths
import java.io.File
import java.io.IOException

class VectorDatabaseHelper private constructor(
    context: Context,
    private val paths: NativeAdapterPaths,
    private val diagnosticLogger: DiagnosticLogger,
    private val clock: () -> Long,
    databaseConfig: DatabaseConfig
) : SQLiteOpenHelper(context.applicationContext, databaseConfig.name, null, DATABASE_VERSION) {
    private val databaseFile: File = context.applicationContext.getDatabasePath(databaseConfig.name)

    constructor(
        context: Context,
        paths: NativeAdapterPaths,
        diagnosticLogger: DiagnosticLogger,
        clock: () -> Long = System::currentTimeMillis
    ) : this(context, paths, diagnosticLogger, clock, DatabaseConfig(DATABASE_NAME))

    internal constructor(
        context: Context,
        paths: NativeAdapterPaths,
        diagnosticLogger: DiagnosticLogger,
        clock: () -> Long,
        databaseName: String
    ) : this(context, paths, diagnosticLogger, clock, DatabaseConfig(databaseName))

    override fun onConfigure(database: SQLiteDatabase) {
        super.onConfigure(database)
        database.setForeignKeyConstraintsEnabled(true)
        database.rawQuery("PRAGMA journal_mode=WAL", null).use { cursor ->
            cursor.moveToFirst()
        }
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE vector_collections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                collection_key TEXT NOT NULL,
                provider_type TEXT NOT NULL,
                endpoint_fingerprint TEXT NOT NULL,
                model TEXT NOT NULL,
                dimension INTEGER NOT NULL CHECK (dimension > 0 AND dimension <= 32768),
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE (collection_key, provider_type, endpoint_fingerprint, model)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE vector_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                collection_id INTEGER NOT NULL,
                content_hash INTEGER NOT NULL,
                item_index INTEGER NOT NULL,
                text TEXT NOT NULL,
                vector_blob BLOB NOT NULL CHECK (length(vector_blob) > 0 AND length(vector_blob) % 4 = 0),
                updated_at INTEGER NOT NULL,
                FOREIGN KEY (collection_id) REFERENCES vector_collections(id) ON DELETE CASCADE,
                UNIQUE (collection_id, content_hash)
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX idx_vector_items_collection ON vector_items(collection_id)"
        )
    }

    override fun onOpen(database: SQLiteDatabase) {
        super.onOpen(database)
        database.execSQL("PRAGMA synchronous=NORMAL")
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Unsupported vector database upgrade: $oldVersion -> $newVersion")
    }

    @Synchronized
    fun quarantineAndRecreate(cause: Throwable): Nothing {
        val quarantinedCount = try {
            close()
            val quarantine = nextQuarantineDirectory()
            check(quarantine.mkdirs()) { "Unable to create vector database quarantine directory" }
            var moved = 0
            databaseFiles().forEach { source ->
                if (source.exists()) {
                    moveToQuarantine(source, quarantine.resolve(source.name))
                    moved++
                }
            }
            writableDatabase
            moved
        } catch (_: Throwable) {
            throw EmbeddingFailure(500, "vector_store_corrupt")
        }
        runCatching {
            diagnosticLogger.event(
                DiagnosticArea.VECTOR,
                "vector_index_quarantined",
                mapOf(
                    "quarantinedCount" to quarantinedCount.toString(),
                    "errorClass" to cause.javaClass.name
                )
            )
        }
        throw EmbeddingFailure(409, "vector_index_rebuild_required")
    }

    internal fun databasePath(): File = databaseFile

    private fun databaseFiles(): List<File> = listOf(
        databaseFile,
        databaseFile.resolveSibling("${databaseFile.name}-wal"),
        databaseFile.resolveSibling("${databaseFile.name}-shm")
    )

    private fun nextQuarantineDirectory(): File {
        val root = paths.quarantineDir
        check(root.exists() || root.mkdirs()) { "Unable to create vector quarantine root" }
        val prefix = "vector-store-${clock()}"
        var candidate = root.resolve(prefix)
        var suffix = 1
        while (candidate.exists()) {
            candidate = root.resolve("$prefix-$suffix")
            suffix++
        }
        return candidate
    }

    private fun moveToQuarantine(source: File, destination: File) {
        check(destination.parentFile?.exists() == true || destination.parentFile?.mkdirs() == true) {
            "Unable to create vector database quarantine directory"
        }
        if (!source.renameTo(destination)) {
            throw IOException("Unable to quarantine vector database file")
        }
    }

    private companion object {
        const val DATABASE_NAME = "vector-store.db"
        const val DATABASE_VERSION = 1
    }

    private data class DatabaseConfig(val name: String)
}
