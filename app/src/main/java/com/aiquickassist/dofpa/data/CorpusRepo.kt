package com.aiquickassist.dofpa.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aiquickassist.dofpa.ingest.Chunker
import com.aiquickassist.dofpa.ingest.TextExtractor
import com.aiquickassist.dofpa.search.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Estado de una operación larga (importar / indexar / restaurar), visible en la UI sin bloquearla. */
class Progress {
    var running by mutableStateOf(false)
    var label by mutableStateOf("")
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)
}

/**
 * Corpus de DOFPA en el teléfono: archivo SQLite privado (`dofpa_corpus.db`), separado de las notas
 * (Room). Importar un paquete no toca Mi Cuaderno. Mantiene una copia de seguridad para restaurar.
 */
object CorpusRepo {
    private lateinit var app: Context
    private var db: SQLiteDatabase? = null
    lateinit var store: CorpusStore; private set
    lateinit var engine: SearchEngine; private set
    val progress = Progress()
    var revision by mutableIntStateOf(0); private set   // la UI observa esto para refrescar

    private fun file() = app.getDatabasePath("dofpa_corpus.db")
    private fun backupFile() = app.getDatabasePath("dofpa_corpus_backup.db")
    fun hasBackup() = ::app.isInitialized && backupFile().exists()

    @Synchronized fun init(ctx: Context) {
        if (::app.isInitialized && db != null) return
        app = ctx.applicationContext
        open()
    }

    private fun open() {
        file().parentFile?.mkdirs()
        val d = SQLiteDatabase.openOrCreateDatabase(file(), null)
        d.enableWriteAheadLogging()
        db = d
        store = CorpusStore(AndroidSql(d)).apply { createSchema() }
        engine = SearchEngine(store)
        revision++
    }

    private fun close() { runCatching { db?.close() }; db = null }

    /** Primera vez: pasa los documentos importados en la Etapa 1 (Room) al corpus nuevo y siembra la demo si está vacío. */
    suspend fun bootstrap() = withContext(Dispatchers.IO) {
        runCatching { migrateLegacy() }
        if (store.counts().first == 0) seedDemo()
        if (ftsMissing()) store.reindex()
        revision++
    }

    private fun ftsMissing(): Boolean {
        val p = store.sql.query("SELECT count(*) n FROM passages").first().int("n")
        val f = store.sql.query("SELECT count(*) n FROM passages_fts").first().int("n")
        return p > 0 && f < p
    }

    private fun migrateLegacy() {
        val room = DofpaDb.get(app)
        val legacy = room.documents().allNow().filter { it.kind != "ficticio" && it.passages > 0 }
        if (legacy.isEmpty()) { room.documents().deleteDemo(); return }
        store.sql.tx {
            for (d in legacy) {
                val id = store.insertDocument(CorpusDoc(0, d.title, d.fileName, d.kind, d.subject, d.unit, null, d.pages, d.passages, d.sha256, d.status, d.note, "usuario"))
                room.passages().forDoc(d.id).forEach { p -> store.insertPassage(id, p.ordinal, p.page, null, p.section, p.text) }
            }
        }
        store.reindex()
        legacy.forEach { DofpaRepo.deleteLegacy(it.id) }
        room.documents().deleteDemo()
    }

    fun seedDemo() {
        store.sql.tx {
            DemoCorpus.docs.forEach { (title, subject, pages) ->
                val id = store.insertDocument(CorpusDoc(0, title, "$title.pdf", "ficticio", subject, null, null, pages.size, 0, "", "OK", "Documento de PRUEBA (ficticio)", "demo"))
                var n = 0
                pages.forEachIndexed { i, t -> Chunker.chunk(listOf(com.aiquickassist.dofpa.ingest.Page(i + 1, t.trimIndent()))).forEach { c ->
                    store.insertPassage(id, n++, c.page, null, c.section, c.text)
                } }
            }
        }
        store.reindex()
    }

    fun removeDemo() { store.documents().filter { it.source == "demo" }.forEach { store.deleteDocument(it.id) }; revision++ }
    fun deleteDocument(id: Long) { store.deleteDocument(id); revision++ }

    // ---------------------------------------------------------------- documentos sueltos del usuario
    suspend fun importDocuments(ctx: Context, uris: List<Uri>): List<ImportResult> = withContext(Dispatchers.IO) {
        progress.running = true; progress.error = null; progress.total = uris.size
        val out = ArrayList<ImportResult>()
        try {
            uris.forEachIndexed { i, uri ->
                val (name, mime) = meta(ctx, uri)
                progress.label = "Importando ${i + 1}/${uris.size}: $name"; progress.done = i
                val ex = TextExtractor.extract(ctx, uri, name, mime)
                if (ex.pages.isEmpty()) { out += ImportResult(name, "ERROR", 0, ex.note); return@forEachIndexed }
                val chunks = Chunker.chunk(ex.pages)
                val docId = store.sql.tx {
                    val id = store.insertDocument(CorpusDoc(0, name.substringBeforeLast('.').replace('_', ' '), name, "documento", null, null, null,
                        ex.pages.size, chunks.size, "", ex.status, ex.note, "usuario"))
                    chunks.forEachIndexed { k, c -> store.insertPassage(id, k, c.page, null, c.section, c.text) }
                    id
                }
                store.reindex(onlyDoc = docId)
                out += ImportResult(name, ex.status, chunks.size, ex.note)
            }
        } catch (e: Exception) { progress.error = e.message } finally { progress.running = false; revision++ }
        out
    }

    // ---------------------------------------------------------------- paquete maestro (.dofpa)
    /**
     * Importa el paquete maestro: copia y descomprime a almacenamiento privado, verifica formato, versión,
     * estructura e integridad (hash del contenido), construye el índice, conserva los documentos que el usuario
     * importó a mano y reemplaza el corpus de forma atómica dejando una copia de seguridad.
     */
    suspend fun importPackage(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        progress.running = true; progress.error = null; progress.done = 0; progress.total = 0
        val tmp = File(app.cacheDir, "dofpa_import.db")
        try {
            progress.label = "Copiando el paquete…"
            tmp.delete()
            ctx.contentResolver.openInputStream(uri)!!.use { raw ->
                val bin = BufferedInputStream(raw); bin.mark(4)
                val gz = bin.read() == 0x1f && bin.read() == 0x8b; bin.reset()
                (if (gz) GZIPInputStream(bin, 1 shl 16) else bin).use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 16) } }
            }
            progress.label = "Verificando estructura e integridad…"
            val nd = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READWRITE)
            val report: String
            try {
                val s = CorpusStore(AndroidSql(nd))
                val format = runCatching { s.meta("format") }.getOrNull()
                require(format == CorpusStore.FORMAT) { "El archivo no es un paquete DOFPA (formato: ${format ?: "desconocido"})." }
                val schema = s.meta("schema")?.toIntOrNull() ?: 0
                require(schema in 1..CorpusStore.SCHEMA) { "Versión de paquete $schema no compatible con esta app (admite hasta ${CorpusStore.SCHEMA}). Actualizá la app." }
                val expected = s.meta("content_sha256")
                val actual = contentHash(s)
                require(expected == null || expected == actual) { "El paquete está dañado: el hash del contenido no coincide." }
                s.createSchema()
                progress.label = "Construyendo el índice de búsqueda…"
                s.reindex { d, t -> progress.done = d; progress.total = t }
                val (docs, pass) = s.counts()
                report = "Corpus ${s.meta("corpus_version") ?: "?"}: $docs documentos, $pass fragmentos."
            } finally { nd.close() }

            progress.label = "Reemplazando el corpus (con copia de seguridad)…"
            val keep = store.documents().filter { it.source == "usuario" }.map { it to store.passagesOfDoc(it.id) }
            close()
            backupFile().delete(); File(backupFile().path + "-wal").delete(); File(backupFile().path + "-shm").delete()
            if (file().exists()) file().renameTo(backupFile())
            File(file().path + "-wal").delete(); File(file().path + "-shm").delete()
            check(tmp.renameTo(file())) { "No se pudo instalar el paquete." }
            open()
            if (keep.isNotEmpty()) {
                keep.forEach { (d, ps) ->
                    val id = store.sql.tx {
                        val nid = store.insertDocument(d.copy(id = 0))
                        ps.forEach { p -> store.insertPassage(nid, p.ordinal, p.page, p.chapter, p.section, p.text) }
                        nid
                    }
                    store.reindex(onlyDoc = id)
                }
            }
            report + (if (keep.isNotEmpty()) " Se conservaron ${keep.size} documentos tuyos." else "")
        } catch (e: Exception) {
            progress.error = e.message ?: e.javaClass.simpleName
            if (db == null) open()
            "Error: ${progress.error}"
        } finally { tmp.delete(); progress.running = false; revision++ }
    }

    /** Vuelve al corpus anterior a la última importación de paquete. */
    suspend fun restoreBackup(): String = withContext(Dispatchers.IO) {
        if (!backupFile().exists()) return@withContext "No hay copia de seguridad."
        progress.running = true; progress.label = "Restaurando la copia anterior…"
        try {
            close()
            val tmp = File(file().path + ".swap")
            file().renameTo(tmp); backupFile().renameTo(file()); tmp.renameTo(backupFile())
            File(file().path + "-wal").delete(); File(file().path + "-shm").delete()
            open(); "Corpus restaurado."
        } finally { progress.running = false; revision++ }
    }

    suspend fun rebuildIndex() = withContext(Dispatchers.IO) {
        progress.running = true; progress.label = "Reconstruyendo el índice…"
        try { store.reindex { d, t -> progress.done = d; progress.total = t } } finally { progress.running = false; revision++ }
    }

    /** Hash estable del contenido (id, documento, página, texto), el mismo que calcula el generador del paquete. */
    fun contentHash(s: CorpusStore): String {
        val md = MessageDigest.getInstance("SHA-256"); var last = 0L
        while (true) {
            val rows = s.sql.query("SELECT id, doc_id, page, text FROM passages WHERE id > ? ORDER BY id LIMIT 1000", arrayOf(last))
            if (rows.isEmpty()) break
            rows.forEach { r -> last = r.long("id"); md.update("${r.long("id")}|${r.long("doc_id")}|${r.int("page")}|${r.str("text")}\n".toByteArray()) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun meta(ctx: Context, uri: Uri): Pair<String, String> {
        var name = uri.lastPathSegment ?: "documento"
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
            }
        }
        return name to (ctx.contentResolver.getType(uri) ?: "application/octet-stream")
    }
}
