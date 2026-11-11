package com.aiquickassist.engine

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Tipo de fallo de Gemini, con mensaje comprensible y si vale la pena reintentar. */
enum class GeminiFail(val transient: Boolean) {
    INVALID_KEY(false), PERMISSION(false), API_DISABLED(false), LOCATION(false), BILLING(false),
    MODEL_NOT_FOUND(false), BAD_REQUEST(false), QUOTA_EXHAUSTED(false), RATE_LIMIT(true),
    SERVER(true), TIMEOUT(true), NO_INTERNET(false), TLS(false), CONNECT(true), EMPTY(false), OTHER(false)
}

data class GeminiError(
    val kind: GeminiFail,
    val message: String,          // texto para el usuario (nunca incluye la clave)
    val detail: String = "",      // técnico: HTTP, status, reason, excepción
    val retryAfterMs: Long = 0
)

/** Traduce respuestas HTTP y excepciones de red de la API de Gemini a errores claros. Lógica pura y testeable. */
object GeminiErrors {

    fun fromHttp(code: Int, body: String, model: String = ""): GeminiError {
        var status = ""; var reason = ""; var msg = ""; var retry = 0L; var quotaId = ""
        runCatching {
            val e = JSONObject(body).getJSONObject("error")
            status = e.optString("status"); msg = e.optString("message")
            val det = e.optJSONArray("details")
            if (det != null) for (i in 0 until det.length()) {
                val d = det.optJSONObject(i) ?: continue
                if (reason.isEmpty()) reason = d.optString("reason")
                d.optString("retryDelay").takeIf { it.endsWith("s") }?.let { s ->
                    retry = ((s.removeSuffix("s").toDoubleOrNull() ?: 0.0) * 1000).toLong()
                }
                val v = d.optJSONArray("violations")
                if (v != null && v.length() > 0) quotaId = v.optJSONObject(0)?.optString("quotaId").orEmpty()
            }
        }
        val detail = "HTTP $code" + (if (status.isNotEmpty()) " $status" else "") + (if (reason.isNotEmpty()) " · $reason" else "") +
            (if (msg.isNotEmpty()) " · ${msg.take(160)}" else "")
        val m = msg.lowercase()
        return when {
            reason == "API_KEY_INVALID" || m.contains("api key not valid") || code == 401 ->
                GeminiError(GeminiFail.INVALID_KEY, "Clave inválida: Google no reconoce la clave API. Copiala de nuevo desde Google AI Studio.", detail)
            reason == "SERVICE_DISABLED" || reason == "API_KEY_SERVICE_BLOCKED" || m.contains("has not been used in project") || m.contains("is disabled") ->
                GeminiError(GeminiFail.API_DISABLED, "La API de Gemini no está habilitada para el proyecto de esta clave (o la clave tiene restringida esa API).", detail)
            m.contains("location is not supported") || m.contains("user location") ->
                GeminiError(GeminiFail.LOCATION, "Google indica que la API de Gemini no está disponible en tu región.", detail)
            reason.startsWith("API_KEY_") || m.contains("blocked") || (code == 403 && m.contains("android")) ->
                GeminiError(GeminiFail.PERMISSION, "La clave tiene restricciones (por app, IP o API) que bloquean esta solicitud. Quitá las restricciones de la clave en Google Cloud o creá una sin restricciones.", detail)
            code == 403 -> GeminiError(GeminiFail.PERMISSION, "Permiso denegado por Google para esta clave.", detail)
            code == 404 || status == "NOT_FOUND" ->
                GeminiError(GeminiFail.MODEL_NOT_FOUND, "Modelo no disponible para esta clave${if (model.isNotEmpty()) ": $model" else ""}.", detail)
            code == 429 || status == "RESOURCE_EXHAUSTED" -> {
                val perMinute = quotaId.contains("PerMinute", true) || m.contains("per minute") || (retry in 1..60_000 && !quotaId.contains("PerDay", true))
                if (perMinute) GeminiError(GeminiFail.RATE_LIMIT, "Límite por minuto alcanzado; se reintenta en unos segundos.", detail, retry.coerceIn(1_000, 30_000))
                else GeminiError(GeminiFail.QUOTA_EXHAUSTED, "Cuota agotada para esta clave (límite diario o de facturación). Se usa el respaldo.", detail)
            }
            status == "FAILED_PRECONDITION" && m.contains("billing") ->
                GeminiError(GeminiFail.BILLING, "El proyecto de la clave requiere facturación para este modelo.", detail)
            code == 400 -> GeminiError(GeminiFail.BAD_REQUEST, "Solicitud rechazada por Gemini (formato o parámetro no admitido por el modelo).", detail)
            code in 500..599 -> GeminiError(GeminiFail.SERVER, "Servidor de Gemini temporalmente no disponible ($code).", detail)
            else -> GeminiError(GeminiFail.OTHER, "Error de Gemini ($code).", detail)
        }
    }

    fun fromException(e: Throwable): GeminiError {
        val d = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(160)}"
        return when (e) {
            is UnknownHostException -> GeminiError(GeminiFail.NO_INTERNET, "Sin Internet o sin DNS: no se pudo resolver generativelanguage.googleapis.com.", d)
            is SocketTimeoutException -> GeminiError(GeminiFail.TIMEOUT, "Tiempo de espera agotado esperando a Gemini.", d)
            is SSLException -> GeminiError(GeminiFail.TLS, "Error de conexión segura (TLS). Revisá fecha y hora del teléfono, VPN o red que intercepte HTTPS.", d)
            is ConnectException -> GeminiError(GeminiFail.CONNECT, "No se pudo conectar con el servidor de Gemini.", d)
            is IOException -> GeminiError(GeminiFail.CONNECT, "La conexión con Gemini se interrumpió.", d)
            else -> GeminiError(GeminiFail.OTHER, "Error inesperado al llamar a Gemini.", d)
        }
    }

    /** Extrae el texto de una respuesta generateContent; uniendo todas las partes que no son "pensamiento". */
    fun parseText(resp: String): Pair<String?, GeminiError?> {
        val j = JSONObject(resp)
        j.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let {
            return null to GeminiError(GeminiFail.EMPTY, "Gemini bloqueó la consulta por sus filtros ($it).", "blockReason=$it")
        }
        val c = j.optJSONArray("candidates")?.optJSONObject(0)
            ?: return null to GeminiError(GeminiFail.EMPTY, "Gemini no devolvió candidatos.", "sin candidates")
        val parts = c.optJSONObject("content")?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text"))
        }
        val finish = c.optString("finishReason")
        if (sb.isBlank()) return null to GeminiError(GeminiFail.EMPTY,
            if (finish == "MAX_TOKENS") "Gemini agotó los tokens antes de responder (razonamiento largo)." else "Gemini devolvió una respuesta vacía.",
            "finishReason=$finish")
        return sb.toString() to null
    }
}
