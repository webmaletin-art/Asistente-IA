package com.aiquickassist.dofpa.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Insert fun insert(doc: DofpaDocument): Long
    @Update fun update(doc: DofpaDocument)
    @Query("SELECT * FROM documents ORDER BY importedAt DESC") fun all(): Flow<List<DofpaDocument>>
    @Query("SELECT * FROM documents ORDER BY importedAt DESC") fun allNow(): List<DofpaDocument>
    @Query("SELECT * FROM documents WHERE id = :id") fun byId(id: Long): DofpaDocument?
    @Query("SELECT COUNT(*) FROM documents") fun count(): Int
    @Query("SELECT COUNT(*) FROM documents WHERE kind = 'ficticio'") fun demoCount(): Int
    @Query("SELECT DISTINCT subject FROM documents WHERE subject IS NOT NULL ORDER BY subject") fun subjects(): List<String>
    @Query("DELETE FROM documents WHERE id = :id") fun delete(id: Long)
    @Query("DELETE FROM documents WHERE kind = 'ficticio'") fun deleteDemo()
}

@Dao
interface PassageDao {
    @Insert fun insert(p: DofpaPassage): Long
    @Insert fun insertFts(f: PassageFts)
    @Query("DELETE FROM passages WHERE docId = :docId") fun deleteForDoc(docId: Long)
    @Query("DELETE FROM passages_fts WHERE rowid IN (SELECT id FROM passages WHERE docId = :docId)") fun deleteFtsForDoc(docId: Long)
    @Query("SELECT COUNT(*) FROM passages") fun count(): Int

    @Query("SELECT * FROM passages WHERE docId = :docId ORDER BY ordinal") fun forDoc(docId: Long): List<DofpaPassage>

    /** Búsqueda FTS: devuelve pasajes que coinciden, con datos del documento para citar. */
    @Query(
        """
        SELECT p.id AS passageId, p.docId AS docId, d.title AS title, d.kind AS kind,
               p.page AS page, p.section AS section, p.text AS text
        FROM passages_fts f
        JOIN passages p ON p.id = f.rowid
        JOIN documents d ON d.id = p.docId
        WHERE passages_fts MATCH :match
        LIMIT :limit
        """
    )
    fun search(match: String, limit: Int): List<PassageHit>
}

@Dao
interface NoteDao {
    @Insert fun insert(n: DofpaNote): Long
    @Update fun update(n: DofpaNote)
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") fun all(): Flow<List<DofpaNote>>
    @Query("SELECT * FROM notes WHERE id = :id") fun byId(id: Long): DofpaNote?
    @Query("DELETE FROM notes WHERE id = :id") fun delete(id: Long)
}
