package com.aiquickassist.dofpa.search

data class CorpusDoc(
    val id: Long, val title: String, val fileName: String, val kind: String, val subject: String?, val unit: String?,
    val law: String?, val pages: Int, val passages: Int, val sha256: String, val status: String, val note: String?, val source: String
)

data class CorpusPassage(
    val id: Long, val docId: Long, val ordinal: Int, val page: Int, val chapter: String?, val section: String?,
    val article: String?, val text: String
)

data class CorpusConcept(val term: String, val passageId: Long, val docId: Long, val page: Int, val definition: String)

/**
 * Base de conocimiento de DOFPA en un archivo SQLite propio (separado de las notas del usuario).
 * El mismo esquema sirve para el paquete maestro (.dofpa) y para la base local del teléfono.
 * El índice FTS4 y el vocabulario se construyen SIEMPRE con [Norm] (Kotlin), así la normalización
 * del índice y de las consultas es idéntica.
 */
class CorpusStore(val sql: Sql) {
    companion object {
        const val FORMAT = "dofpa-corpus"
        const val SCHEMA = 2
        val DDL = listOf(
            "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)",
            """CREATE TABLE IF NOT EXISTS documents(id INTEGER PRIMARY KEY, title TEXT NOT NULL, file_name TEXT, kind TEXT, subject TEXT,
               unit TEXT, law TEXT, pages INTEGER DEFAULT 0, passages INTEGER DEFAULT 0, sha256 TEXT, status TEXT, note TEXT, source TEXT)""",
            """CREATE TABLE IF NOT EXISTS passages(id INTEGER PRIMARY KEY, doc_id INTEGER NOT NULL, ordinal INTEGER, page INTEGER,
               chapter TEXT, section TEXT, article TEXT, refs TEXT, method TEXT, text TEXT NOT NULL)""",
            "CREATE INDEX IF NOT EXISTS idx_passages_doc ON passages(doc_id, ordinal)",
            "CREATE INDEX IF NOT EXISTS idx_passages_doc_page ON passages(doc_id, page)",
            "CREATE TABLE IF NOT EXISTS concepts(id INTEGER PRIMARY KEY, term TEXT, norm TEXT, passage_id INTEGER, doc_id INTEGER, page INTEGER, definition TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_concepts_norm ON concepts(norm)",
            "CREATE TABLE IF NOT EXISTS relations(a TEXT, b TEXT, kind TEXT, weight INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_relations_a ON relations(a)",
            "CREATE TABLE IF NOT EXISTS vocab(term TEXT PRIMARY KEY, df INTEGER) WITHOUT ROWID",
            "CREATE VIRTUAL TABLE IF NOT EXISTS passages_fts USING fts4(norm, head, refs)"
        )
    }

    @Volatile var version: Long = 0; private set
    @Volatile private var docsCache: List<CorpusDoc>? = null
    @Volatile private var vocabCache: Map<String, Int>? = null
    @Volatile private var conceptCache: Map<String, CorpusConcept>? = null

    fun createSchema() { DDL.forEach { sql.exec(it) } }

    fun changed() { version++; docsCache = null; vocabCache = null; conceptCache = null }

    fun meta(key: String): String? = sql.query("SELECT value FROM meta WHERE key=?", arrayOf(key)).firstOrNull()?.str("value")
    fun setMeta(key: String, value: String) = sql.exec("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", arrayOf(key, value))

    // ---------- lectura ----------
    fun documents(): List<CorpusDoc> = docsCache ?: sql.query("SELECT * FROM documents ORDER BY title").map {
        CorpusDoc(it.long("id"), it.str("title").orEmpty(), it.str("file_name").orEmpty(), it.str("kind") ?: "documento",
            it.str("subject"), it.str("unit"), it.str("law")?.takeIf { l -> l.isNotBlank() }, it.int("pages"), it.int("passages"),
            it.str("sha256").orEmpty(), it.str("status") ?: "OK", it.str("note"), it.str("source") ?: "paquete")
    }.also { docsCache = it }

    fun knownLaws(): Set<String> = documents().mapNotNull { it.law }.toSet()

    fun vocab(): Map<String, Int> = vocabCache ?: HashMap<String, Int>().also { m ->
        sql.query("SELECT term, df FROM vocab").forEach { m[it.str("term")!!] = it.int("df") }
    }.also { vocabCache = it }

    fun concepts(): Map<String, CorpusConcept> = conceptCache ?: LinkedHashMap<String, CorpusConcept>().also { m ->
        sql.query("SELECT term, norm, passage_id, doc_id, page, definition FROM concepts ORDER BY length(definition) DESC").forEach {
            val n = it.str("norm") ?: return@forEach
            if (n !in m) m[n] = CorpusConcept(it.str("term").orEmpty(), it.long("passage_id"), it.long("doc_id"), it.int("page"), it.str("definition").orEmpty())
        }
    }.also { conceptCache = it }

    fun counts(): Pair<Int, Int> =
        sql.query("SELECT (SELECT count(*) FROM documents) d, (SELECT count(*) FROM passages) p").first().let { it.int("d") to it.int("p") }

    private fun passage(r: Row) = CorpusPassage(r.long("id"), r.long("doc_id"), r.int("ordinal"), r.int("page"),
        r.str("chapter"), r.str("section"), r.str("article"), r.str("text").orEmpty())

    fun passages(ids: Collection<Long>): Map<Long, CorpusPassage> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, CorpusPassage>()
        ids.chunked(400).forEach { ch ->
            sql.query("SELECT * FROM passages WHERE id IN (${ch.joinToString(",")})").forEach { out[it.long("id")] = passage(it) }
        }
        return out
    }

    fun passageIdsOfDoc(docId: Long): Set<Long> =
        sql.query("SELECT id FROM passages WHERE doc_id=?", arrayOf(docId)).mapTo(HashSet()) { it.long("id") }

    fun passagesOfDoc(docId: Long, fromPage: Int = 0, toPage: Int = Int.MAX_VALUE): List<CorpusPassage> =
        sql.query("SELECT * FROM passages WHERE doc_id=? AND page BETWEEN ? AND ? ORDER BY ordinal", arrayOf(docId, fromPage, toPage)).map(::passage)

    /** rowid + matchinfo('pcnalx') de cada fila que coincide (para BM25). */
    fun matchInfo(match: String): List<Pair<Long, IntArray>> =
        sql.query("SELECT rowid AS id, matchinfo(passages_fts, 'pcnalx') AS mi FROM passages_fts WHERE passages_fts MATCH ?", arrayOf(match))
            .map { it.long("id") to decode(it.blob("mi") ?: ByteArray(0)) }

    private fun decode(b: ByteArray): IntArray = IntArray(b.size / 4) { i ->
        (b[i * 4].toInt() and 0xff) or ((b[i * 4 + 1].toInt() and 0xff) shl 8) or
            ((b[i * 4 + 2].toInt() and 0xff) shl 16) or ((b[i * 4 + 3].toInt() and 0xff) shl 24)
    }

    // ---------- escritura ----------
    fun insertDocument(d: CorpusDoc): Long {
        sql.exec("""INSERT INTO documents(title,file_name,kind,subject,unit,law,pages,passages,sha256,status,note,source)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf(d.title, d.fileName, d.kind, d.subject, d.unit, d.law ?: Refs.lawOfTitle(d.title + " " + d.fileName),
                d.pages, d.passages, d.sha256, d.status, d.note, d.source))
        return sql.query("SELECT last_insert_rowid() AS id").first().long("id")
    }

    fun insertPassage(docId: Long, ordinal: Int, page: Int, chapter: String?, section: String?, text: String, method: String = "native"): Long {
        sql.exec("INSERT INTO passages(doc_id,ordinal,page,chapter,section,method,text) VALUES(?,?,?,?,?,?,?)",
            arrayOf(docId, ordinal, page, chapter, section, method, text))
        return sql.query("SELECT last_insert_rowid() AS id").first().long("id")
    }

    fun deleteDocument(docId: Long) = sql.tx {
        sql.exec("DELETE FROM passages_fts WHERE rowid IN (SELECT id FROM passages WHERE doc_id=?)", arrayOf(docId))
        sql.exec("DELETE FROM passages WHERE doc_id=?", arrayOf(docId))
        sql.exec("DELETE FROM concepts WHERE doc_id=?", arrayOf(docId))
        sql.exec("DELETE FROM documents WHERE id=?", arrayOf(docId))
        rebuildVocab()
        changed()
    }

    /**
     * Construye (o reconstruye) el índice: normaliza, detecta artículos y leyes citadas, llena FTS y vocabulario.
     * [onlyDoc] = índice incremental de un documento recién importado.
     */
    fun reindex(onlyDoc: Long? = null, progress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        changed()
        val docs = documents().associateBy { it.id }
        val known = knownLaws()
        val where = if (onlyDoc != null) "WHERE doc_id=$onlyDoc" else ""
        val total = sql.query("SELECT count(*) AS n FROM passages $where").first().int("n")
        sql.tx {
            if (onlyDoc == null) sql.exec("DELETE FROM passages_fts")
            else sql.exec("DELETE FROM passages_fts WHERE rowid IN (SELECT id FROM passages WHERE doc_id=?)", arrayOf(onlyDoc))
            var done = 0; var lastId = 0L
            while (true) {
                val batch = sql.query("SELECT id, doc_id, chapter, section, text FROM passages ${if (where.isEmpty()) "WHERE" else "$where AND"} id > ? ORDER BY id LIMIT 500", arrayOf(lastId))
                if (batch.isEmpty()) break
                for (r in batch) {
                    val id = r.long("id"); lastId = id
                    val text = r.str("text").orEmpty(); val section = r.str("section")
                    val norm = Norm.text(text)
                    val article = Refs.articleOf(section) ?: Refs.articleOf(text.take(60))
                    val laws = Refs.lawsIn(norm, known).toMutableSet()
                    docs[r.long("doc_id")]?.law?.let { laws.add(it) }
                    val refs = (laws.map(Refs::lawToken) + listOfNotNull(article?.let(Refs::articleToken))).joinToString(" ")
                    val head = Norm.text(listOfNotNull(r.str("chapter"), section).joinToString(" "))
                    sql.exec("UPDATE passages SET article=?, refs=? WHERE id=?", arrayOf(article, refs, id))
                    sql.exec("INSERT INTO passages_fts(rowid, norm, head, refs) VALUES(?,?,?,?)", arrayOf(id, norm, head, refs))
                    done++
                }
                progress(done, total)
            }
            rebuildVocab()
            sql.exec("UPDATE documents SET passages=(SELECT count(*) FROM passages WHERE doc_id=documents.id)")
        }
        changed()
    }

    /** Vocabulario con frecuencia de documento (para corregir errores de tipeo). */
    private fun rebuildVocab() {
        val df = HashMap<String, Int>()
        var lastId = 0L
        while (true) {
            val batch = sql.query("SELECT rowid AS id, norm FROM passages_fts WHERE rowid > ? ORDER BY rowid LIMIT 1000", arrayOf(lastId))
            if (batch.isEmpty()) break
            for (r in batch) {
                lastId = r.long("id")
                r.str("norm").orEmpty().split(' ').filter { it.length >= 3 && !it[0].isDigit() }.toSet().forEach { df[it] = (df[it] ?: 0) + 1 }
            }
        }
        sql.exec("DELETE FROM vocab")
        df.forEach { (t, n) -> sql.exec("INSERT INTO vocab(term, df) VALUES(?,?)", arrayOf(t, n)) }
    }
}
