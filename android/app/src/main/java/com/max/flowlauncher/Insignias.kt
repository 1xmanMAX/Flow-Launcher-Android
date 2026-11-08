package com.max.flowlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
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
            cache.getOrPut("ext:$ext") {
                val f = familia(ext)
                Baldosa(f.color, densidad, rotulo = ext.take(4), textoOscuro = f.textoOscuro)
            }
        }
    }

    /**
     * Las funciones: el mismo cuadrado que los archivos, con su color y el
     * simbolo en blanco. Asi todo lo que no es una app se ve de una familia.
     */
    private class Funcion(val icono: Int, val color: Int)

    private val funciones = mapOf(
        "web" to Funcion(R.drawable.ic_web, 0x2F7BF5),
        "calc" to Funcion(R.drawable.ic_calc, 0xF2711C),
        "ajustes" to Funcion(R.drawable.ic_ajustes, 0x5F6B7A),
        "contactos" to Funcion(R.drawable.ic_contacto, 0x2E9E4F),
        "archivos" to Funcion(R.drawable.ic_carpeta, 0xE8A317),
        "carpeta" to Funcion(R.drawable.ic_carpeta, 0xE8A317),
        "plugin" to Funcion(R.drawable.ic_plugin, 0x7048E8),
        "buscar" to Funcion(R.drawable.ic_buscar, 0x495057),
        "ic_compartir" to Funcion(R.drawable.ic_compartir, 0x1098AD),
        "ic_abrir_con" to Funcion(R.drawable.ic_abrir_con, 0x5B3CC4),
        "ic_copiar" to Funcion(R.drawable.ic_copiar, 0x6C757D),
        "ic_carpeta" to Funcion(R.drawable.ic_carpeta, 0xE8A317),
    )

    /** `web`, `calc`, `ic_compartir`... o null si no es una de las nuestras. */
    fun funcion(c: Context, nombre: String): Drawable? {
        val f = funciones[nombre] ?: return null
        return synchronized(cache) {
            cache.getOrPut("fn:$nombre") {
                val simbolo = c.getDrawable(f.icono)?.mutate()?.apply { setTint(0xFFFFFFFF.toInt()) }
                Baldosa(f.color, c.resources.displayMetrics.density, simbolo = simbolo)
            }
        }
    }

    /**
     * Un cuadrado de esquinas redondeadas (casi un «squircle», como los iconos
     * de iOS y Raycast) con un brillo suave arriba, y dentro la extension o un
     * simbolo. Mismo tamano y forma para todo: la lista se ve ordenada.
     */
    private class Baldosa(
        color: Int,
        d: Float,
        val rotulo: String = "",
        textoOscuro: Boolean = false,
        val simbolo: Drawable? = null,
    ) : Drawable() {
        private val base = 0xFF000000.toInt() or color
        private val fondo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * d
            this.color = 0x1F000000
        }
        private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (textoOscuro) 0xE6000000.toInt() else 0xFFFFFFFF.toInt()
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.02f
        }
        private val lado = (36 * d).toInt()
        private val caja = RectF()
        private var degradadoPara = -1

        /** Un poco mas claro arriba y un poco mas oscuro abajo: con volumen, sin brillos falsos. */
        private fun degradado(alto: Float, top: Float) {
            val clave = (alto.toInt() shl 16) xor top.toInt()
            if (clave == degradadoPara) return
            degradadoPara = clave
            fondo.shader = LinearGradient(
                0f, top, 0f, top + alto,
                mezclar(base, 0xFFFFFFFF.toInt(), 0.14f), mezclar(base, 0xFF000000.toInt(), 0.10f),
                Shader.TileMode.CLAMP,
            )
        }

        override fun draw(c: Canvas) {
            val b = bounds
            val l = minOf(b.width(), b.height()).toFloat()
            // Cuadrado, centrado en lo que le den.
            caja.set(b.exactCenterX() - l / 2, b.exactCenterY() - l / 2, b.exactCenterX() + l / 2, b.exactCenterY() + l / 2)
            val radio = l * 0.26f
            degradado(l, caja.top)
            c.drawRoundRect(caja, radio, radio, fondo)
            val m = borde.strokeWidth / 2
            caja.inset(m, m)
            c.drawRoundRect(caja, radio - m, radio - m, borde)
            caja.inset(-m, -m)
            val s = simbolo
            if (s != null) {
                val t = (l * 0.24f).toInt()
                s.setBounds(caja.left.toInt() + t, caja.top.toInt() + t, caja.right.toInt() - t, caja.bottom.toInt() - t)
                s.draw(c)
                return
            }
            if (rotulo.isEmpty()) return
            letra.textSize = l * when (rotulo.length) {
                1, 2 -> 0.38f
                3 -> 0.31f
                else -> 0.25f
            }
            val y = caja.centerY() - (letra.descent() + letra.ascent()) / 2
            c.drawText(rotulo, caja.centerX(), y, letra)
        }

        override fun getIntrinsicWidth() = lado
        override fun getIntrinsicHeight() = lado
        override fun setAlpha(a: Int) {
            fondo.alpha = a
            letra.alpha = a
            simbolo?.alpha = a
        }
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT

        private fun mezclar(a: Int, b: Int, t: Float): Int {
            fun canal(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt() shl s
            return (0xFF shl 24) or canal(16) or canal(8) or canal(0)
        }
    }
}
