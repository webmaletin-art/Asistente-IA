package com.aiquickassist.dofpa.ingest

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.aiquickassist.capture.Ocr
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

/** Una página o diapositiva extraída, con su número (1-based) y texto. */
data class Page(val number: Int, val text: String)

/** Resultado de extraer un documento: páginas con texto y un estado honesto. */
data class Extraction(val pages: List<Page>, val status: String, val note: String?)

/**
 * Extrae texto de un documento EN EL DISPOSITIVO (sin servidor). Soporta PDF con capa de texto,
 * DOCX, PPTX, TXT/Markdown e imágenes (OCR local). Lo que no puede leer lo reporta como ERROR,
 * sin fingir que recuperó contenido.
 */
object TextExtractor {
    private var pdfInit = false

    suspend fun extract(ctx: Context, uri: Uri, fileName: String, mime: String): Extraction =
        withContext(Dispatchers.IO) {
            val lower = fileName.lowercase()
            try {
                when {
                    lower.endsWith(".pdf") || mime == "application/pdf" -> pdf(ctx, uri)
                    lower.endsWith(".docx") -> office(ctx, uri, "word/document.xml", Regex("<w:t[^>]*>(.*?)</w:t>"), Regex("</w:p>"))
                    lower.endsWith(".pptx") -> office(ctx, uri, "ppt/slides/slide", Regex("<a:t>(.*?)</a:t>"), Regex("</a:p>"), perSlide = true)
                    lower.endsWith(".txt") || lower.endsWith(".md") || mime.startsWith("text/") -> plain(ctx, uri)
                    lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || mime.startsWith("image/") -> image(ctx, uri)
                    lower.endsWith(".doc") -> Extraction(emptyList(), "ERROR", "Formato .doc antiguo no soportado. Convertí el archivo a PDF o DOCX y reimportalo.")
                    else -> Extraction(emptyList(), "ERROR", "Formato no soportado: $fileName")
                }
            } catch (e: Exception) {
                Extraction(emptyList(), "ERROR", "No se pudo leer: ${e.message}")
            }
        }

    private fun pdf(ctx: Context, uri: Uri): Extraction {
        if (!pdfInit) { PDFBoxResourceLoader.init(ctx.applicationContext); pdfInit = true }
        ctx.contentResolver.openInputStream(uri).use { input ->
            PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper()
                val pages = ArrayList<Page>()
                var empty = 0
                for (i in 1..doc.numberOfPages) {
                    stripper.startPage = i; stripper.endPage = i
                    val t = stripper.getText(doc).trim()
                    if (t.isBlank()) empty++ else pages.add(Page(i, t))
                }
                return when {
                    pages.isEmpty() -> Extraction(emptyList(), "ERROR", "PDF sin capa de texto (probablemente escaneado). Usá importación con OCR por imagen.")
                    empty > 0 -> Extraction(pages, "PARCIAL", "$empty de ${doc.numberOfPages} páginas sin texto (posibles escaneos).")
                    else -> Extraction(pages, "OK", null)
                }
            }
        }
    }

    /** DOCX y PPTX son ZIP con XML; extraemos el texto de los nodos sin librerías pesadas. */
    private fun office(ctx: Context, uri: Uri, entryPrefix: String, run: Regex, paraBreak: Regex, perSlide: Boolean = false): Extraction {
        val slides = sortedMapOf<Int, StringBuilder>()
        val body = StringBuilder()
        ctx.contentResolver.openInputStream(uri).use { input ->
            ZipInputStream(input).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val name = e.name
                    if (name.startsWith(entryPrefix) && name.endsWith(".xml")) {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        val sb = StringBuilder()
                        for (m in run.findAll(paraBreak.replace(xml, "\n"))) sb.append(unescape(m.groupValues[1]))
                        if (perSlide) {
                            val num = Regex("slide(\\d+)\\.xml").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            slides.getOrPut(num) { StringBuilder() }.append(sb).append('\n')
                        } else body.append(paraText(xml, run))
                    }
                    e = zip.nextEntry
                }
            }
        }
        val pages = if (perSlide) slides.entries.mapIndexed { i, en -> Page(i + 1, en.value.toString().trim()) }.filter { it.text.isNotBlank() }
        else body.toString().split("\n\n").filter { it.isNotBlank() }.let { listOf(Page(1, body.toString().trim())) }
        return if (pages.isEmpty() || pages.all { it.text.isBlank() }) Extraction(emptyList(), "ERROR", "Documento sin texto legible.")
        else Extraction(pages, "OK", null)
    }

    private fun paraText(xml: String, run: Regex): String {
        val withBreaks = xml.replace(Regex("</w:p>"), "\n").replace(Regex("<w:tab/>"), "\t")
        return run.findAll(withBreaks).joinToString("") { unescape(it.groupValues[1]) }
    }

    private fun plain(ctx: Context, uri: Uri): Extraction {
        val t = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
        return if (t.isBlank()) Extraction(emptyList(), "ERROR", "Archivo vacío.") else Extraction(listOf(Page(1, t)), "OK", null)
    }

    private suspend fun image(ctx: Context, uri: Uri): Extraction {
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: return Extraction(emptyList(), "ERROR", "No se pudo abrir la imagen.")
        val t = runCatching { Ocr.read(bmp) }.getOrDefault("").trim()
        return if (t.isBlank()) Extraction(emptyList(), "ERROR", "La imagen no contiene texto reconocible.")
        else Extraction(listOf(Page(1, t)), "PARCIAL", "Texto obtenido por OCR: revisá posibles errores de reconocimiento.")
    }

    private fun unescape(s: String) = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
}
