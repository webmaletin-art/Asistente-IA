package com.aiquickassist.dofpa.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aiquickassist.dofpa.ingest.Chunker
import com.aiquickassist.dofpa.ingest.TextExtractor
import com.aiquickassist.engine.TextUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class ImportResult(val fileName: String, val status: String, val passages: Int, val note: String?)

/** Punto único de acceso a la enciclopedia local de DOFPA. */
object DofpaRepo {
    private lateinit var db: DofpaDb
    fun init(ctx: Context) { db = DofpaDb.get(ctx) }

    /** Progreso de importación visible en la UI. */
    var importing by mutableStateOf(false)
    var importStatus by mutableStateOf("")

    fun documents(): Flow<List<DofpaDocument>> = db.documents().all()
    fun notes(): Flow<List<DofpaNote>> = db.notes().all()
    fun docCount(): Int = runCatching { db.documents().count() }.getOrDefault(0)
    fun passageCount(): Int = runCatching { db.passages().count() }.getOrDefault(0)
    fun passagesOf(docId: Long) = db.passages().forDoc(docId)
    fun document(id: Long) = db.documents().byId(id)

    /** Importa una lista de documentos elegidos por el usuario (SAF). Todo ocurre en el dispositivo. */
    suspend fun import(ctx: Context, uris: List<Uri>): List<ImportResult> = withContext(Dispatchers.IO) {
        importing = true
        val results = ArrayList<ImportResult>()
        try {
            uris.forEachIndexed { i, uri ->
                val (name, mime) = meta(ctx, uri)
                importStatus = "Importando ${i + 1}/${uris.size}: $name"
                results.add(importOne(ctx, uri, name, mime, kind = "documento"))
            }
        } finally { importing = false; importStatus = "" }
        results
    }

    private fun importOne(ctx: Context, uri: Uri, name: String, mime: String, kind: String): ImportResult {
        val ex = kotlinx.coroutines.runBlocking { TextExtractor.extract(ctx, uri, name, mime) }
        if (ex.pages.isEmpty()) {
            db.documents().insert(DofpaDocument(title = clean(name), fileName = name, mime = mime, kind = kind, status = "ERROR", note = ex.note))
            return ImportResult(name, "ERROR", 0, ex.note)
        }
        val chunks = Chunker.chunk(ex.pages)
        val hash = runCatching { sha256(ctx, uri) }.getOrDefault("")
        val docId = db.documents().insert(
            DofpaDocument(
                title = clean(name), fileName = name, mime = mime, kind = kind,
                subject = guessSubject(name), pages = ex.pages.size, passages = chunks.size,
                sha256 = hash, status = ex.status, note = ex.note
            )
        )
        chunks.forEachIndexed { idx, c ->
            val pid = db.passages().insert(DofpaPassage(docId = docId, ordinal = idx, page = c.page, section = c.section, text = c.text))
            db.passages().insertFts(PassageFts(rowid = pid, text = c.text))
        }
        db.documents().update(db.documents().byId(docId)!!.copy(passages = chunks.size))
        return ImportResult(name, ex.status, chunks.size, ex.note)
    }

    fun deleteDocument(docId: Long) {
        db.passages().deleteFtsForDoc(docId)
        db.passages().deleteForDoc(docId)
        db.documents().delete(docId)
    }

    // ---- Búsqueda ----
    /** Búsqueda FTS tolerante: usa prefijos y OR entre términos significativos. */
    fun search(query: String, limit: Int = 30): List<PassageHit> {
        val terms = TextUtil.keywords(query).distinct().take(8)
        if (terms.isEmpty()) return emptyList()
        val match = terms.joinToString(" OR ") { "${sanitize(it)}*" }
        return runCatching { db.passages().search(match, limit) }.getOrDefault(emptyList())
    }

    private fun sanitize(t: String) = t.replace(Regex("[^\\p{L}\\p{Nd}]"), "")

    // ---- Datos ficticios (para probar la app sin corpus real) ----
    fun hasDemo(): Boolean = runCatching { db.documents().demoCount() > 0 }.getOrDefault(false)
    fun removeDemo() {
        db.documents().allNow().filter { it.kind == "ficticio" }.forEach { deleteDocument(it.id) }
    }

    fun seedDemoIfEmpty() {
        if (db.documents().count() > 0) return
        DemoCorpus.docs.forEach { (title, subject, pages) ->
            val docId = db.documents().insert(
                DofpaDocument(title = title, fileName = "$title.pdf", mime = "demo", kind = "ficticio",
                    subject = subject, pages = pages.size, passages = 0, status = "OK", note = "Documento de PRUEBA (ficticio)")
            )
            var n = 0
            pages.forEachIndexed { pi, pageText ->
                Chunker.chunk(listOf(com.aiquickassist.dofpa.ingest.Page(pi + 1, pageText))).forEach { c ->
                    val pid = db.passages().insert(DofpaPassage(docId = docId, ordinal = n++, page = c.page, section = c.section, text = c.text))
                    db.passages().insertFts(PassageFts(rowid = pid, text = c.text))
                }
            }
            db.documents().update(db.documents().byId(docId)!!.copy(passages = n))
        }
    }

    // ---- utilidades ----
    private fun meta(ctx: Context, uri: Uri): Pair<String, String> {
        var name = uri.lastPathSegment ?: "documento"
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
            }
        }
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        return name to mime
    }

    private fun clean(fileName: String) = fileName.substringBeforeLast('.').replace('_', ' ').replace(Regex("\\s+"), " ").trim()

    private fun guessSubject(name: String): String? {
        val n = name.lowercase()
        return when {
            n.contains("ley") || n.contains("codigo") || n.contains("constitucion") || n.contains("decreto") || n.contains("resolucion") -> "Legislación"
            n.contains("acta") || n.contains("inventario") || n.contains("aprehen") -> "Actas"
            n.contains("protocolo") || n.contains("tactic") || n.contains("intervencion") -> "Procedimientos"
            n.contains("salud") || n.contains("toxicolog") || n.contains("primeros") -> "Salud"
            n.contains("unidad") || n.contains("trayecto") || n.contains("curso") -> "Académico"
            else -> null
        }
    }

    private fun sha256(ctx: Context, uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(8192); var r = ins.read(buf)
            while (r > 0) { md.update(buf, 0, r); r = ins.read(buf) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
