package com.aiquickassist.dofpa

import com.aiquickassist.dofpa.search.Row
import com.aiquickassist.dofpa.search.Sql
import java.sql.Connection
import java.sql.DriverManager

/** Implementación de [Sql] sobre SQLite real (sqlite-jdbc) para probar el buscador en la JVM. */
class JdbcSql(url: String = "jdbc:sqlite::memory:") : Sql {
    val conn: Connection = DriverManager.getConnection(url)
    private var depth = 0

    override fun exec(sql: String, args: Array<Any?>) {
        conn.prepareStatement(sql).use { st -> args.forEachIndexed { i, a -> st.setObject(i + 1, a) }; st.execute() }
    }

    override fun query(sql: String, args: Array<Any?>): List<Row> =
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs ->
                val md = rs.metaData; val out = ArrayList<Row>()
                while (rs.next()) {
                    val m = HashMap<String, Any?>()
                    for (c in 1..md.columnCount) m[md.getColumnLabel(c)] = rs.getObject(c)
                    out.add(Row(m))
                }
                out
            }
        }

    override fun <T> tx(block: () -> T): T {
        if (depth++ == 0) conn.autoCommit = false
        try { val r = block(); if (depth == 1) conn.commit(); return r }
        catch (e: Throwable) { if (depth == 1) conn.rollback(); throw e }
        finally { if (--depth == 0) conn.autoCommit = true }
    }
}
