package com.max.flowlauncher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * El cuadro de color con la extension escrita (`pdf` en rojo, `docx` en
 * azul...), **los mismos colores que PixPin** (`motor/ColorDeExtension.kt`
 * de PixPin Android): de un vistazo se sabe que es cada archivo.
 *
 * Un resultado lo pide con el icono `ext:<extension>` (o `ext:<nombre.ext>`).
 */
object Insignias {

    private class Familia(val color: Int, val textoOscuro: Boolean = false)

    private val PDF = Familia(0xE5484D)
    private val WORD = Familia(0x2F7BF5)
    private val HOJA = Familia(0x2E9E4F)
    private val PRESENTACION = Familia(0xF2711C)
    private val PLANO = Familia(0xF5B800, textoOscuro = true)
    private val MODELO = Familia(0x0FA3A3)
    private val IMAGEN = Familia(0xD6336C)
    private val AUDIO = Familia(0x8E44AD)
    private val VIDEO = Familia(0x5B3CC4)
    private val COMPRIMIDO = Familia(0x8D6E63)
    private val CODIGO = Familia(0x1098AD)
    private val TEXTO = Familia(0x607D8B)
    private val LIBRO = Familia(0x3B5BDB)
    private val APLICACION = Familia(0x6CBF5B)
    private val PIXPIN = Familia(0x7048E8)
    private val SIN_EXTENSION = Familia(0x868E96)

    private val porExtension: Map<String, Familia> = HashMap<String, Familia>().apply {
        fun poner(f: Familia, vararg ext: String) = ext.forEach { put(it, f) }
        poner(PDF, "pdf")
        poner(WORD, "doc", "docx", "docm", "dot", "dotx", "odt", "rtf", "pages", "wps")
        poner(HOJA, "xls", "xlsx", "xlsm", "xlsb", "csv", "tsv", "ods", "numbers")
        poner(PRESENTACION, "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx", "odp", "key")
        poner(PLANO, "dwg", "dxf", "dwf", "dwfx", "dgn", "dwt", "plt", "rvt", "rfa", "ifc", "nwd", "nwc")
        poner(MODELO, "obj", "stl", "fbx", "3ds", "blend", "skp", "gltf", "glb", "dae", "ply", "step", "stp", "iges", "igs", "3dm", "usdz")
        poner(IMAGEN, "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "tif", "tiff", "svg", "psd", "ai", "raw", "dng", "avif", "ico")
        poner(AUDIO, "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "wma", "mid", "midi")
        poner(VIDEO, "mp4", "mov", "avi", "mkv", "webm", "3gp", "m4v", "wmv", "flv")
        poner(COMPRIMIDO, "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst")
        poner(CODIGO, "html", "htm", "css", "js", "ts", "json", "xml", "kt", "kts", "java", "py", "c", "h", "cpp", "hpp", "cs", "rs", "go", "sh", "sql", "yml", "yaml", "tex", "m", "ipynb")
        poner(TEXTO, "txt", "md", "markdown", "log", "ini", "cfg", "srt", "vtt")
        poner(LIBRO, "epub", "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr")
        poner(APLICACION, "apk", "aab", "xapk", "apks", "exe", "msi", "dmg", "deb", "appimage")
        poner(PIXPIN, "pindesk", "pixpin", "excalidraw")
    }

    /** Lo que no esta en la tabla: siempre el mismo color para la misma extension. */
    private val RESTO = intArrayOf(0x5C7CFA, 0x20C997, 0xFF6B6B, 0xFAB005, 0x845EF7, 0x15AABF, 0xE64980, 0x82C91E)

    /** `pdf` de `ext:pdf`, `ext:Tesis final.PDF` o `ext:.pdf`. */
    fun extension(clave: String): String {
        val s = clave.removePrefix("ext:").trim()
        val e = if (s.contains('.')) s.substringAfterLast('.') else s
        return e.lowercase().take(10)
    }

    private fun familia(ext: String): Familia {
        if (ext.isEmpty()) return SIN_EXTENSION
        porExtension[ext]?.let { return it }
        val color = RESTO[ext.sumOf { it.code } % RESTO.size]
        return Familia(color, textoOscuro = color == 0xFAB005)
    }

    private val cache = HashMap<String, Drawable>()

    /** El cuadro de una extension (se reutiliza: es inmutable). */
    fun de(clave: String, densidad: Float): Drawable {
        val ext = extension(clave)
        return synchronized(cache) {
            cache.getOrPut(ext) { Cuadro(ext.take(4), familia(ext), densidad) }
        }
    }

    /** Como el de PixPin: esquinas redondeadas y la extension en minusculas. */
    private class Cuadro(val rotulo: String, f: Familia, d: Float) : Drawable() {
        private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() or f.color }
        private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (f.textoOscuro) 0xFF222222.toInt() else 0xFFFFFFFF.toInt()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        private val radio = 7 * d
        private val lado = (36 * d).toInt()
        private val caja = RectF()

        override fun draw(c: Canvas) {
            val b = bounds
            // Un poco mas alto que ancho, como una hoja.
            val ancho = b.width() * 0.82f
            caja.set(b.exactCenterX() - ancho / 2, b.top.toFloat(), b.exactCenterX() + ancho / 2, b.bottom.toFloat())
            c.drawRoundRect(caja, radio, radio, fondo)
            if (rotulo.isEmpty()) return
            letra.textSize = ancho * when (rotulo.length) {
                1, 2 -> 0.42f
                3 -> 0.36f
                else -> 0.29f
            }
            val y = caja.centerY() - (letra.descent() + letra.ascent()) / 2
            c.drawText(rotulo, caja.centerX(), y, letra)
        }

        override fun getIntrinsicWidth() = lado
        override fun getIntrinsicHeight() = lado
        override fun setAlpha(a: Int) {
            fondo.alpha = a
            letra.alpha = a
        }
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
