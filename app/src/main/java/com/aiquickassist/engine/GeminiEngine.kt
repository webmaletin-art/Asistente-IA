package com.aiquickassist.engine

import android.graphics.Bitmap
import android.util.Base64
import com.aiquickassist.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Cliente de Gemini (opcional). Usa la clave y la cuota del propio usuario. */
object GeminiEngine {
    @Volatile private var resolved: String? = null

    /** Elige el mejor modelo disponible: versión más nueva, rápido (flash) antes que pro, estable antes que preview. */
    fun pickModel(names: List<String>): String? = rankModels(names).firstOrNull()

    fun rankModels(names: List<String>): List<String> {
        val re = Regex("""^gemini-(\d+(?:\.\d+)?)-(flash-lite|flash|pro)(?:-(latest|\d{3}|preview.*))?$""")
        val bad = listOf("tts", "image", "live", "audio", "embedding", "exp", "vision", "robotics", "computer", "thinking", "native")
        return names.map { it.removePrefix("models/") }
            .filter { n -> bad.none { n.contains(it) } }
            .mapNotNull { n -> re.matchEntire(n)?.let { m ->
                val tier = when (m.groupValues[2]) { "flash" -> 3; "flash-lite" -> 2; else -> 1 }
                val stable = if (m.groupValues[3].startsWith("preview")) 0 else 1
                Triple(n, m.groupValues[1].toDouble(), tier * 10 + stable)
            } }
            .sortedWith(compareByDescending<Triple<String, Double, Int>> { it.second }.thenByDescending { it.third })
            .map { it.first }
    }

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    /** Consulta los modelos disponibles para la clave del usuario. */
    suspend fun listModels(): List<String> {
        val key = SecureStore.geminiKey() ?: return emptyList()
        val j = JSONObject(Http.get("$BASE/models?pageSize=200", mapOf("x-goog-api-key" to key), 20_000))
        val arr = j.optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { m -> (0 until (m.optJSONArray("supportedGenerationMethods")?.length() ?: 0)).any { m.getJSONArray("supportedGenerationMethods").getString(it) == "generateContent" } }
            .map { it.getString("name") }
    }

    /** Último error real (para mostrar la causa en DOFPA y en Ajustes). */
    @Volatile var lastError: GeminiError? = null

    @Volatile private var ranked: List<String>? = null

    /** Modelos a probar, en orden: el elegido a mano o, en «auto», los mejores disponibles. */
    private suspend fun candidates(): List<String> {
        val m = Settings.geminiModel.trim()
        if (m.isNotEmpty() && m != "auto") return listOf(m)
        ranked?.let { return it }
        val list = runCatching { rankModels(listModels()).take(4) }.getOrNull().orEmpty().ifEmpty { listOf("gemini-flash-latest") }
        ranked = list
        return list
    }

    /** Modelo en uso (el primero de la lista). */
    suspend fun model(): String = resolved ?: candidates().first()

    fun resetModel() { resolved = null; ranked = null }

    fun available() = Settings.geminiEnabled && SecureStore.hasGeminiKey()

    suspend fun ask(p: ParsedQuestion, image: Bitmap? = null): Block {
        if (!Settings.geminiEnabled || !SecureStore.hasGeminiKey()) throw AnalysisException("Gemini no está configurado.", needsGemini = true)
        val text = call(buildPrompt(p), image)
        return parse(text, p)
    }

    /** Respuesta de texto libre a un prompt completo (lo usa DOFPA con fragmentos citados). */
    suspend fun complete(prompt: String, image: Bitmap? = null): String {
        if (!Settings.geminiEnabled || !SecureStore.hasGeminiKey()) throw AnalysisException("Gemini no está configurado.", needsGemini = true)
        return call(prompt, image, json = false, maxTokens = 4096)
    }

    /** Devuelve null solo si Gemini respondió de verdad; si no, el error comprensible (con detalle técnico). */
    suspend fun test(): String? = try {
        val t = call("Respondé solamente con la palabra OK.", null, json = false, maxTokens = 1024)
        if (t.isBlank()) "Gemini respondió vacío." else null
    } catch (e: AnalysisException) { e.message } catch (e: Exception) { GeminiErrors.fromException(e).let { "${it.message} (${it.detail})" } }

    /** Diagnóstico paso a paso, sin revelar la clave. */
    suspend fun diagnose(): List<String> {
        val out = ArrayList<String>()
        val key = SecureStore.geminiKey()
        if (key == null) { out += "✕ No hay clave guardada."; return out }
        out += "• Clave guardada: ${key.length} caracteres, formato ${if (key.startsWith("AIza")) "esperado (AIza…)" else "NO estándar (debería empezar con AIza)"}" +
            (if (key.any { it.isWhitespace() }) " · ⚠ contiene espacios" else "")
        // 1) Conectividad sin clave: el servidor debe contestar 403 (prueba que hay red y TLS)
        var t0 = System.currentTimeMillis()
        try { Http.get("$BASE/models?pageSize=1", emptyMap(), 15_000); out += "• Servidor alcanzable (${System.currentTimeMillis() - t0} ms)" }
        catch (e: HttpException) { out += "✓ Servidor alcanzable: HTTP ${e.code} sin clave, como se espera (${System.currentTimeMillis() - t0} ms)" }
        catch (e: Exception) { val g = GeminiErrors.fromException(e); out += "✕ Red: ${g.message} [${g.detail}]"; return out }
        // 2) Clave: listar modelos
        t0 = System.currentTimeMillis()
        val models = try { listModels().also { out += "✓ Clave aceptada: ${it.size} modelos disponibles (${System.currentTimeMillis() - t0} ms)" } }
        catch (e: HttpException) { val g = GeminiErrors.fromHttp(e.code, e.body); out += "✕ Clave: ${g.message} [${g.detail}]"; return out }
        catch (e: Exception) { val g = GeminiErrors.fromException(e); out += "✕ Clave: ${g.message} [${g.detail}]"; return out }
        // 3) Modelo
        resetModel()
        val cands = candidates()
        out += "• Modelo configurado: ${Settings.geminiModel} → se probará: ${cands.joinToString()}"
        if (Settings.geminiModel != "auto" && models.none { it.removePrefix("models/") == Settings.geminiModel })
            out += "⚠ El modelo «${Settings.geminiModel}» no figura en la lista de tu clave."
        // 4) Generación real
        t0 = System.currentTimeMillis()
        try {
            val t = call("Respondé solamente con la palabra OK.", null, json = false, maxTokens = 1024)
            out += "✓ Respuesta real de ${resolved ?: cands.first()}: «${t.trim().take(40)}» (${System.currentTimeMillis() - t0} ms)"
        } catch (e: AnalysisException) { out += "✕ Generación: ${e.message}" }
        return out
    }

    private fun buildPrompt(p: ParsedQuestion): String = buildString {
        append("Eres un asistente que resuelve preguntas de forma breve y precisa. Responde en el idioma de la pregunta.\n")
        append("Devuelve SOLO un JSON con los campos: \"choice\" (lista de letras elegidas, vacía si no hay opciones), ")
        append("\"answer\" (respuesta corta), \"explanation\" (máximo 3 frases), \"confidence\" (0 a 1).\n\n")
        append("Tipo detectado: ${p.type.label}\n")
        if (p.context.isNotBlank()) append("Contexto:\n${p.context.take(1500)}\n\n")
        if (p.hasImage) append("La imagen adjunta forma parte de la pregunta.\n")
        append("Pregunta: ${p.question.ifBlank { "Analiza la imagen y responde con claridad." }}\n")
        if (p.options.isNotEmpty()) {
            append("Opciones:\n")
            p.options.forEach { append("${it.label}. ${it.text}\n") }
            if (p.type == QType.MULTI_SELECT) append("Puede haber más de una respuesta correcta.\n")
            if (p.type == QType.TRUE_FALSE) append("Es una afirmación o pregunta de verdadero/falso: elige V (verdadera) o F (falsa) en \"choice\" y explica en una frase.\n")
        }
    }

    private suspend fun call(prompt: String, image: Bitmap?, json: Boolean = true, maxTokens: Int = 2048): String {
        val key = SecureStore.geminiKey()?.trim() ?: throw AnalysisException("Gemini no está configurado.", needsGemini = true)
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (image != null) parts.put(JSONObject().put("inline_data",
            JSONObject().put("mime_type", "image/jpeg").put("data", toBase64(image))))
        val genCfg = JSONObject().put("temperature", 0.2).put("maxOutputTokens", maxTokens)
        if (json) genCfg.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", genCfg).toString()
        var last: GeminiError? = null
        for (model in candidates()) {
            // Reintentos limitados con espera progresiva SOLO para errores transitorios; los permanentes se informan al instante.
            for (attempt in 0..2) {
                val err: GeminiError = try {
                    val resp = Http.postJson("$BASE/models/$model:generateContent", body, mapOf("x-goog-api-key" to key), 60_000)
                    val (text, e) = GeminiErrors.parseText(resp)
                    if (text != null) { resolved = model; lastError = null; return text }
                    e!!
                } catch (e: HttpException) {
                    GeminiErrors.fromHttp(e.code, e.body, model)
                } catch (e: org.json.JSONException) {
                    GeminiError(GeminiFail.OTHER, "Respuesta de Gemini con formato inesperado.", e.message.orEmpty().take(120))
                } catch (e: java.io.IOException) {
                    GeminiErrors.fromException(e)
                }
                last = err; lastError = err
                if (err.kind == GeminiFail.MODEL_NOT_FOUND) break          // probar el siguiente modelo
                if (!err.kind.transient || attempt == 2) {
                    if (err.kind.transient) break                          // agotados los reintentos: siguiente modelo
                    throw AnalysisException("${err.message} [${err.detail}]")
                }
                kotlinx.coroutines.delay(if (err.retryAfterMs > 0) err.retryAfterMs else 1000L * (attempt + 1) * (attempt + 1))
            }
        }
        ranked = null
        val e = last ?: GeminiError(GeminiFail.OTHER, "Gemini no respondió.")
        throw AnalysisException("${e.message} [${e.detail}]")
    }

    private fun parse(raw: String, p: ParsedQuestion): Block {
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val j = runCatching { JSONObject(clean) }.getOrNull()
            ?: return Block(Origin.GEMINI, answer = clean.take(400), confidence = 0.5)
        val ch = j.optJSONArray("choice")
        val labels = (0 until (ch?.length() ?: 0)).map { ch!!.optString(it).trim().uppercase().take(1) }
            .filter { l -> p.options.any { it.label.equals(l, true) } }
        val answer = j.optString("answer").ifBlank { labels.firstNotNullOfOrNull { l -> p.options.firstOrNull { it.label == l }?.text }.orEmpty() }
        return Block(Origin.GEMINI, labels, answer, j.optString("explanation"), emptyList(), j.optDouble("confidence", 0.7).coerceIn(0.0, 1.0))
    }

    private fun toBase64(src: Bitmap): String {
        var bmp = src
        val max = 1280
        if (bmp.width > max || bmp.height > max) {
            val s = max.toFloat() / maxOf(bmp.width, bmp.height)
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
