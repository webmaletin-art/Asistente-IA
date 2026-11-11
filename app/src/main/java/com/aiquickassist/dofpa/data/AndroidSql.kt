package com.aiquickassist.dofpa.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.aiquickassist.dofpa.search.Row
import com.aiquickassist.dofpa.search.Sql

/** [Sql] sobre el SQLite nativo de Android (FTS4 y matchinfo disponibles en todos los dispositivos). */
class AndroidSql(val db: SQLiteDatabase) : Sql {
    override fun exec(sql: String, args: Array<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args)
    }

    override fun query(sql: String, args: Array<Any?>): List<Row> =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            val out = ArrayList<Row>(c.count)
            while (c.moveToNext()) {
                val m = HashMap<String, Any?>(c.columnCount)
                for (i in 0 until c.columnCount) m[c.getColumnName(i)] = when (c.getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> c.getString(i)
                    Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                    else -> null
                }
                out.add(Row(m))
            }
            out
        }

    override fun <T> tx(block: () -> T): T {
        db.beginTransaction()
        try { val r = block(); db.setTransactionSuccessful(); return r } finally { db.endTransaction() }
    }
}
