package com.aiquickassist.dofpa.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

data class ImportResult(val fileName: String, val status: String, val passages: Int, val note: String?)

/**
 * Datos PERSONALES de DOFPA (Room): Mi Cuaderno y, a futuro, progreso de estudio.
 * El corpus vive aparte en [CorpusRepo] para que importar/restaurar nunca toque las notas.
 * Las tablas `documents`/`passages` de Room quedan solo para migrar lo importado en la Etapa 1.
 */
object DofpaRepo {
    private lateinit var db: DofpaDb
    fun init(ctx: Context) { db = DofpaDb.get(ctx) }

    fun notes(): Flow<List<DofpaNote>> = db.notes().all()
    fun addNote(n: DofpaNote) = db.notes().insert(n)
    fun deleteNote(id: Long) = db.notes().delete(id)

    /** Borra un documento de la Etapa 1 de Room una vez migrado al corpus nuevo. */
    fun deleteLegacy(docId: Long) {
        db.passages().deleteFtsForDoc(docId)
        db.passages().deleteForDoc(docId)
        db.documents().delete(docId)
    }
}
