package com.aiquickassist.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Respuesta HTTP no exitosa: conserva el código y el cuerpo (sin datos sensibles) para diagnosticar. */
class HttpException(val code: Int, val body: String) : Exception("HTTP $code")

object Http {
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 30_000): String =
        request(url, "GET", null, headers, readTimeoutMs)

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 30_000): String =
        request(url, "POST", body, headers, readTimeoutMs)

    private suspend fun request(url: String, method: String, body: String?, headers: Map<String, String>, readTimeoutMs: Int): String =
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 15_000
                c.readTimeout = readTimeoutMs
                c.setRequestProperty("User-Agent", "AIQuickAssist/1.0 (Android; uso personal)")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = try {
                    c.responseCode
                } catch (e: IOException) {
                    // Android lanza IOException ante un 401 sin cabecera WWW-Authenticate: es un rechazo de credenciales.
                    if (e.message.orEmpty().contains("authentication challenge", true)) 401 else throw e
                }
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = runCatching { stream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                if (code !in 200..299) throw HttpException(code, text.take(4000))
                text
            } finally {
                c.disconnect()
            }
        }
}
