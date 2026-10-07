package com.aiquickassist.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Guarda la clave de Gemini cifrada con una clave AES del Android Keystore. */
object SecureStore {
    private const val ALIAS = "aiqa_gemini_key"
    private const val PREF = "secure"
    private const val ENTRY = "gemini"
    private lateinit var ctx: Context

    fun init(c: Context) { ctx = c.applicationContext }

    private val prefs get() = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun saveGeminiKey(key: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val enc = cipher.doFinal(key.trim().toByteArray())
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
        prefs.edit().putString(ENTRY, packed).apply()
    }

    fun geminiKey(): String? = runCatching {
        val packed = prefs.getString(ENTRY, null) ?: return null
        val (iv, enc) = packed.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)))
    }.getOrNull()

    fun hasGeminiKey() = prefs.contains(ENTRY)

    /** Solo los últimos 4 caracteres; nunca la clave completa. */
    fun maskedGeminiKey(): String {
        val k = geminiKey() ?: return ""
        return "••••••••••••" + k.takeLast(4)
    }

    fun clearGeminiKey() {
        prefs.edit().remove(ENTRY).apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }
}
