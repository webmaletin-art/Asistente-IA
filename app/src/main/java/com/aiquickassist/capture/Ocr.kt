package com.aiquickassist.capture

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** OCR local (ML Kit, modelo incluido en la app). Solo se ejecuta bajo demanda. */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun read(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        result.textBlocks.sortedBy { it.boundingBox?.top ?: 0 }
            .joinToString("\n") { b -> b.lines.joinToString("\n") { it.text } }
    }
}
