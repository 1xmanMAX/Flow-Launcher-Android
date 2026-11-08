package com.max.flowlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Path
import android.graphics.Matrix
import android.content.res.Configuration
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

    /** La hoja de una extension (se reutiliza: es inmutable). */
    fun de(clave: String, densidad: Float): Drawable {
        val ext = extension(clave)
        return synchronized(cache) {
            cache.getOrPut("ext:$ext") { Hoja(familia(ext).color, ext.take(4).uppercase(), densidad) }
        }
    }

    /** Las funciones: su simbolo de trazo fino, en su color, sobre el mismo color muy claro. */
    private class Funcion(val icono: Int, val color: Int)

    private val funciones = mapOf(
        "web" to Funcion(R.drawable.ic_web, 0x2F7BF5),
        "calc" to Funcion(R.drawable.ic_calc, 0xF2711C),
        "ajustes" to Funcion(R.drawable.ic_ajustes, 0x5F6B7A),
        "contactos" to Funcion(R.drawable.ic_contacto, 0x2E9E4F),
        "archivos" to Funcion(R.drawable.ic_carpeta, 0xE8A317),
        "carpeta" to Funcion(R.drawable.ic_carpeta, 0xE8A317),
        "plugin" to Funcion(R.drawable.ic_plugin, 0x7048E8),
        "buscar" to Funcion(R.drawable.ic_buscar, 0x5F6B7A),
        "ic_compartir" to Funcion(R.drawable.ic_compartir, 0x0E9AA7),
        "ic_abrir_con" to Funcion(R.drawable.ic_abrir_con, 0x6741D9),
        "ic_copiar" to Funcion(R.drawable.ic_copiar, 0x6C757D),
        "ic_carpeta" to Funcion(R.drawable.ic_carpeta, 0xE8A317),
        "ic_ajustes" to Funcion(R.drawable.ic_ajustes, 0x5F6B7A),
        "ic_buscar" to Funcion(R.drawable.ic_buscar, 0x5F6B7A),
    )

    /** Un simbolo de otra app con su color, en el mismo cuadrado suave que las funciones. */
    fun suave(c: Context, simbolo: Drawable, rgb: Int): Drawable {
        val oscuro = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val color = 0xFF000000.toInt() or rgb
        return Suave(color, if (oscuro) 0x33 else 0x1F, simbolo.mutate().apply { setTint(color) }, c.resources.displayMetrics.density)
    }

    /** `web`, `calc`, `ic_compartir`... o null si no es una de las nuestras. */
    fun funcion(c: Context, nombre: String): Drawable? {
        val f = funciones[nombre] ?: return null
        val oscuro = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return synchronized(cache) {
            cache.getOrPut("fn:$nombre:$oscuro") {
                val color = 0xFF000000.toInt() or f.color
                val simbolo = c.getDrawable(f.icono)?.mutate()?.apply { setTint(color) }
                // En oscuro el fondo un poco mas presente: si no, no se ve.
                Suave(color, if (oscuro) 0x33 else 0x1F, simbolo, c.resources.displayMetrics.density)
            }
        }
    }

    /**
     * Un archivo: una hoja cuadrada de su color, con la esquina de arriba a la
     * derecha doblada y la extension en mayusculas (como la de PixPin, pero
     * cuadrada). Dibujada sobre una rejilla de 36 x 36.
     */
    private class Hoja(color: Int, val rotulo: String, d: Float) : Drawable() {
        private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFF000000.toInt() or color }
        private val pliegue = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0x59FFFFFF }
        private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.02f
        }
        private val lado = (36 * d).toInt()
        private val hoja = Path()
        private val esquina = Path()
        private val m = Matrix()
        private var para = Rect()

        private fun rehacer(b: Rect) {
            if (b == para) return
            para = Rect(b)
            val l = minOf(b.width(), b.height()).toFloat()
            val e = l / 36f
            fun base() = Path().apply {
                // M8 1h14l13 13v13a8 8 0 0 1-8 8H9a8 8 0 0 1-8-8V9a8 8 0 0 1 8-8z
                moveTo(9f, 1f); lineTo(22f, 1f); lineTo(35f, 14f); lineTo(35f, 27f)
                arcTo(RectF(19f, 19f, 35f, 35f), 0f, 90f); lineTo(9f, 35f)
                arcTo(RectF(1f, 19f, 17f, 35f), 90f, 90f); lineTo(1f, 9f)
                arcTo(RectF(1f, 1f, 17f, 17f), 180f, 90f); close()
            }
            fun pliegue() = Path().apply {
                // M22 1v7a6 6 0 0 0 6 6h7z
                moveTo(22f, 1f); lineTo(22f, 8f); arcTo(RectF(22f, 2f, 34f, 14f), 180f, -90f); lineTo(35f, 14f); close()
            }
            m.reset()
            m.setScale(e, e)
            m.postTranslate(b.exactCenterX() - l / 2, b.exactCenterY() - l / 2)
            hoja.reset(); hoja.addPath(base(), m)
            esquina.reset(); esquina.addPath(pliegue(), m)
            letra.textSize = e * if (rotulo.length >= 4) 8.5f else 10f
        }

        override fun draw(c: Canvas) {
            val b = bounds
            rehacer(b)
            c.drawPath(hoja, fondo)
            c.drawPath(esquina, pliegue)
            if (rotulo.isEmpty()) return
            val l = minOf(b.width(), b.height()).toFloat()
            val e = l / 36f
            val x0 = b.exactCenterX() - l / 2
            val y0 = b.exactCenterY() - l / 2
            c.drawText(rotulo, x0 + 17f * e, y0 + 26f * e, letra)
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

    /** Una funcion: cuadrado de esquinas redondeadas en su color muy claro y el simbolo en su color. */
    private class Suave(color: Int, alfa: Int, val simbolo: Drawable?, d: Float) : Drawable() {
        private val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = (alfa shl 24) or (color and 0xFFFFFF) }
        private val lado = (36 * d).toInt()
        private val caja = RectF()

        override fun draw(c: Canvas) {
            val b = bounds
            val l = minOf(b.width(), b.height()).toFloat()
            caja.set(b.exactCenterX() - l / 2, b.exactCenterY() - l / 2, b.exactCenterX() + l / 2, b.exactCenterY() + l / 2)
            val r = l * 0.28f
            c.drawRoundRect(caja, r, r, fondo)
            val s = simbolo ?: return
            // El simbolo, a algo mas de la mitad.
            val t = (l * 0.235f).toInt()
            s.setBounds(caja.left.toInt() + t, caja.top.toInt() + t, caja.right.toInt() - t, caja.bottom.toInt() - t)
            s.draw(c)
        }

        override fun getIntrinsicWidth() = lado
        override fun getIntrinsicHeight() = lado
        override fun setAlpha(a: Int) {
            fondo.alpha = a
            simbolo?.alpha = a
        }
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
