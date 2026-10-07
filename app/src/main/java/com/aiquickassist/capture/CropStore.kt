package com.aiquickassist.capture

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Guarda el recorte real como archivo temporal y lo expone con un content:// Uri. */
object CropStore {
    fun authority(ctx: Context) = ctx.packageName + ".files"

    fun save(ctx: Context, bmp: Bitmap): Uri {
        val dir = File(ctx.cacheDir, "crops").apply { mkdirs() }
        // Conserva los últimos recortes (Google puede tardar en leer el archivo); limpia los viejos
        val now = System.currentTimeMillis()
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEachIndexed { i, f ->
            if (i >= 4 || now - f.lastModified() > 60 * 60 * 1000L) f.delete()
        }
        val png = bmp.width.toLong() * bmp.height <= 1_500_000
        val file = File(dir, "crop_$now." + if (png) "png" else "jpg")
        file.outputStream().use {
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it)
        }
        return FileProvider.getUriForFile(ctx, authority(ctx), file)
    }
}
