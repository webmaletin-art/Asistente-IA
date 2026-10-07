package com.aiquickassist.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryItem(
    val id: Long,
    val time: Long,
    val manual: Boolean,
    val question: String,
    val options: List<Option>,
    val answer: String,
    val explanation: String,
    val engine: String
)

/** Historial local en un archivo JSON (solo texto, sin capturas). */
object HistoryStore {
    private lateinit var file: File
    val items = mutableStateListOf<HistoryItem>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "history.json")
        items.clear()
        runCatching {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val opts = o.getJSONArray("options")
                items += HistoryItem(
                    o.getLong("id"), o.getLong("time"), o.optBoolean("manual"),
                    o.getString("question"),
                    (0 until opts.length()).map { Option(opts.getJSONObject(it).getString("l"), opts.getJSONObject(it).getString("t")) },
                    o.getString("answer"), o.optString("explanation"), o.optString("engine")
                )
            }
        }
    }

    fun add(r: AnalysisResult) {
        if (!Settings.historyEnabled) return
        val b = r.best
        val answer = (b.choice.joinToString(", ") + " " + b.answer).trim()
        val now = System.currentTimeMillis()
        items.add(0, HistoryItem(now, now, r.manual, r.parsed.question, r.parsed.options, answer, b.explanation, r.engineLabel))
        while (items.size > 200) items.removeAt(items.lastIndex)
        save()
    }

    fun remove(id: Long) { items.removeAll { it.id == id }; save() }
    fun clear() { items.clear(); save() }

    private fun save() {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("time", it.time).put("manual", it.manual)
                .put("question", it.question).put("answer", it.answer)
                .put("explanation", it.explanation).put("engine", it.engine)
                .put("options", JSONArray().also { a -> it.options.forEach { o -> a.put(JSONObject().put("l", o.label).put("t", o.text)) } }))
        }
        runCatching { file.writeText(arr.toString()) }
    }
}
