package com.aiquickassist

import com.aiquickassist.engine.GeminiErrors
import com.aiquickassist.engine.GeminiFail
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** Respuestas reales capturadas del servidor de Gemini (sin clave válida). */
class GeminiErrorsTest {
    private val invalidKey = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT",
        "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID","domain":"googleapis.com"}]}}"""
    private val noKey = """{"error":{"code":403,"message":"Method doesn't allow unregistered callers (callers without established identity).","status":"PERMISSION_DENIED"}}"""

    @Test fun invalidKey() {
        val e = GeminiErrors.fromHttp(400, invalidKey)
        assertEquals(GeminiFail.INVALID_KEY, e.kind)
        assertTrue(e.detail.contains("HTTP 400") && e.detail.contains("API_KEY_INVALID"))
        assertFalse(e.kind.transient)
    }
    @Test fun permissionDenied() = assertEquals(GeminiFail.PERMISSION, GeminiErrors.fromHttp(403, noKey).kind)
    @Test fun restrictedKey() {
        val b = """{"error":{"code":403,"message":"Requests from this Android client application <empty> are blocked.","status":"PERMISSION_DENIED","details":[{"reason":"API_KEY_ANDROID_APP_BLOCKED"}]}}"""
        assertEquals(GeminiFail.PERMISSION, GeminiErrors.fromHttp(403, b).kind)
    }
    @Test fun apiDisabled() {
        val b = """{"error":{"code":403,"message":"Generative Language API has not been used in project 123 before or it is disabled.","status":"PERMISSION_DENIED","details":[{"reason":"SERVICE_DISABLED"}]}}"""
        assertEquals(GeminiFail.API_DISABLED, GeminiErrors.fromHttp(403, b).kind)
    }
    @Test fun modelNotFound() {
        val b = """{"error":{"code":404,"message":"models/gemini-9 is not found for API version v1beta","status":"NOT_FOUND"}}"""
        val e = GeminiErrors.fromHttp(404, b, "gemini-9")
        assertEquals(GeminiFail.MODEL_NOT_FOUND, e.kind); assertTrue(e.message.contains("gemini-9"))
    }
    @Test fun perMinuteIsTransientDailyIsNot() {
        val perMin = """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"7s"}]}}"""
        val e1 = GeminiErrors.fromHttp(429, perMin)
        assertEquals(GeminiFail.RATE_LIMIT, e1.kind); assertTrue(e1.kind.transient); assertEquals(7000, e1.retryAfterMs)
        val perDay = """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}"""
        val e2 = GeminiErrors.fromHttp(429, perDay)
        assertEquals(GeminiFail.QUOTA_EXHAUSTED, e2.kind); assertFalse(e2.kind.transient)
    }
    @Test fun serverIsTransient() = assertTrue(GeminiErrors.fromHttp(503, "{}").kind.transient)
    @Test fun badRequest() = assertEquals(GeminiFail.BAD_REQUEST, GeminiErrors.fromHttp(400, """{"error":{"code":400,"message":"Invalid JSON payload","status":"INVALID_ARGUMENT"}}""").kind)
    @Test fun networkExceptions() {
        assertEquals(GeminiFail.NO_INTERNET, GeminiErrors.fromException(UnknownHostException("generativelanguage.googleapis.com")).kind)
        assertEquals(GeminiFail.TIMEOUT, GeminiErrors.fromException(SocketTimeoutException("timeout")).kind)
        assertEquals(GeminiFail.TLS, GeminiErrors.fromException(SSLHandshakeException("cert")).kind)
        assertTrue(GeminiErrors.fromException(SocketTimeoutException("x")).detail.contains("SocketTimeoutException"))
    }
    @Test fun parseJoinsPartsAndSkipsThoughts() {
        val r = """{"candidates":[{"content":{"parts":[{"text":"pienso...","thought":true},{"text":"Hola "},{"text":"mundo"}]},"finishReason":"STOP"}]}"""
        assertEquals("Hola mundo", GeminiErrors.parseText(r).first)
    }
    @Test fun emptyByMaxTokensIsReported() {
        val r = """{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}"""
        val (t, e) = GeminiErrors.parseText(r)
        assertNull(t); assertEquals(GeminiFail.EMPTY, e!!.kind); assertTrue(e.message.contains("tokens"))
    }
    @Test fun blockedPrompt() {
        val (t, e) = GeminiErrors.parseText("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
        assertNull(t); assertTrue(e!!.message.contains("SAFETY"))
    }
}
