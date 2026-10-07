package com.aiquickassist.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpException(val code: Int, message: String) : Exception(message)

object Http {
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = request(url, "GET", null, headers)

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        request(url, "POST", body, headers)

    private suspend fun request(url: String, method: String, body: String?, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 10_000
                c.readTimeout = 25_000
                c.setRequestProperty("User-Agent", "AIQuickAssist/1.0 (Android; uso personal)")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw HttpException(code, text.take(300))
                text
            } finally {
                c.disconnect()
            }
        }
}
