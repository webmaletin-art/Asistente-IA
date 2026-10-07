package com.aiquickassist.engine

import android.graphics.Bitmap
import android.util.Base64
import com.aiquickassist.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Cliente de Gemini (opcional). Usa la clave y la cuota del propio usuario. */
object GeminiEngine {
    fun available() = Settings.geminiEnabled && SecureStore.hasGeminiKey()

    suspend fun ask(p: ParsedQuestion, image: Bitmap? = null): Block {
        if (!Settings.geminiEnabled || !SecureStore.hasGeminiKey()) throw AnalysisException("Gemini no está configurado.", needsGemini = true)
        val text = call(buildPrompt(p), image)
        return parse(text, p)
    }

    /** Devuelve null si la conexión funciona o el mensaje de error. */
    suspend fun test(): String? = try {
        call("Responde únicamente con la palabra OK.", null); null
    } catch (e: AnalysisException) { e.message } catch (e: Exception) { "Sin conexión." }

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
        }
    }

    private suspend fun call(prompt: String, image: Bitmap?): String {
        val key = SecureStore.geminiKey() ?: throw AnalysisException("Gemini no está configurado.", needsGemini = true)
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (image != null) parts.put(JSONObject().put("inline_data",
            JSONObject().put("mime_type", "image/jpeg").put("data", toBase64(image))))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 1024)
                .put("responseMimeType", "application/json")).toString()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${Settings.geminiModel}:generateContent"
        val resp = try {
            Http.postJson(url, body, mapOf("x-goog-api-key" to key))
        } catch (e: HttpException) {
            throw AnalysisException(when (e.code) {
                400, 401, 403 -> "Gemini rechazó la clave o la solicitud (${e.code}). Revisa la configuración."
                404 -> "Modelo de Gemini no encontrado: ${Settings.geminiModel}."
                429 -> "Se agotó la cuota de tu clave de Gemini. Inténtalo más tarde."
                else -> "Error de Gemini (${e.code})."
            })
        } catch (e: java.io.IOException) {
            throw AnalysisException("Sin conexión con Gemini.")
        }
        return JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
            ?: throw AnalysisException("Gemini no devolvió respuesta.")
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
