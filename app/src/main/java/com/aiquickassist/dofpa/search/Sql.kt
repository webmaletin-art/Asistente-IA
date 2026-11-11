package com.aiquickassist.dofpa.search

/** Acceso mínimo a SQLite, implementable en Android y en la JVM (tests con SQLite real). */
interface Sql {
    fun exec(sql: String, args: Array<Any?> = emptyArray())
    fun query(sql: String, args: Array<Any?> = emptyArray()): List<Row>
    fun <T> tx(block: () -> T): T
}

/** Fila genérica: valores por nombre de columna (Long, Double, String, ByteArray o null). */
class Row(private val m: Map<String, Any?>) {
    fun long(c: String): Long = (m[c] as? Number)?.toLong() ?: 0L
    fun int(c: String): Int = long(c).toInt()
    fun str(c: String): String? = m[c]?.toString()
    fun blob(c: String): ByteArray? = m[c] as? ByteArray
}
