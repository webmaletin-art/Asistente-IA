package com.aiquickassist.dofpa.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * DOFPA — modelo de datos LOCAL. Toda la enciclopedia vive en el dispositivo del usuario:
 * nada de corpus en el repositorio ni en el APK. El usuario importa sus documentos y DOFPA
 * los indexa aquí para consulta offline.
 */

/** Un documento importado (ley, unidad académica, acta modelo, etc.). */
@Entity(tableName = "documents")
data class DofpaDocument(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val fileName: String,
    val mime: String,
    val subject: String? = null,   // materia detectada/asignada
    val unit: String? = null,      // trayecto / unidad
    val kind: String = "documento",// documento | ley | acta | apunte | ficticio
    val pages: Int = 0,
    val passages: Int = 0,
    val importedAt: Long = System.currentTimeMillis(),
    val sha256: String = "",
    val status: String = "OK",     // OK | PARCIAL | ERROR
    val note: String? = null       // p. ej. "escaneado: OCR parcial"
)

/** Un fragmento recuperable del documento, con anclaje preciso (página/sección). */
@Entity(
    tableName = "passages",
    indices = [Index("docId")]
)
data class DofpaPassage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long,
    val ordinal: Int,
    val page: Int = 0,
    val section: String? = null,   // artículo / título / capítulo detectado
    val text: String
)

/** Índice de texto completo (FTS4) para búsqueda offline instantánea. rowid = passage.id. */
@Fts4
@Entity(tableName = "passages_fts")
data class PassageFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val text: String
)

/** Nota de "Mi Cuaderno". */
@Entity(tableName = "notes")
data class DofpaNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val subject: String? = null,
    val tags: String? = null,
    val body: String,
    val sources: String? = null,   // citas guardadas desde el chat
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Resultado de búsqueda: un pasaje con los datos de su documento para citar. */
data class PassageHit(
    val passageId: Long,
    val docId: Long,
    val title: String,
    val kind: String,
    val page: Int,
    val section: String?,
    val text: String
)
