package com.max.flowlauncher

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/**
 * Los iconos se cargan fuera del hilo de la interfaz y se guardan en memoria:
 * la segunda vez salen al instante. Mientras tanto, el icono del tipo.
 */
object Iconos {
    private val cache = LruCache<String, Drawable>(150)
    private val hilo = Executors.newFixedThreadPool(2) { r -> Thread(r, "flow-iconos") }

    fun porDefecto(fuente: String): Int = when {
        fuente == "web" -> R.drawable.ic_web
        fuente == "calc" -> R.drawable.ic_calc
        fuente == "ajustes" -> R.drawable.ic_ajustes
        fuente == "contactos" -> R.drawable.ic_contacto
        fuente.startsWith("plugin:") -> R.drawable.ic_plugin
        else -> R.drawable.ic_buscar
    }

    fun poner(v: ImageView, r: Resultado) {
        val clave = r.icono
        v.tag = clave
        if (clave.isEmpty()) {
            v.setImageResource(porDefecto(r.fuente))
            return
        }
        val hay = cache.get(clave)
        if (hay != null) {
            v.setImageDrawable(hay)
            return
        }
        v.setImageResource(porDefecto(r.fuente))
        val c = v.context.applicationContext
        val lado = v.layoutParams?.width?.takeIf { it > 0 } ?: 96
        hilo.execute {
            val d = runCatching { cargar(c, clave, lado) }.getOrNull() ?: return@execute
            cache.put(clave, d)
            App.principal.post { if (v.tag == clave) v.setImageDrawable(d) }
        }
    }

    private fun cargar(c: Context, clave: String, lado: Int): Drawable? {
        if (clave.startsWith("app:")) {
            val (paquete, actividad) = clave.substring(4).split('/', limit = 2)
            return c.packageManager.getActivityIcon(ComponentName(paquete, actividad))
        }
        // Una imagen de un plugin: a su tamano, no al del fichero.
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(clave, o)
        if (o.outWidth <= 0) return null
        var escala = 1
        while (o.outWidth / (escala * 2) >= lado && o.outHeight / (escala * 2) >= lado) escala *= 2
        val b = BitmapFactory.decodeFile(clave, BitmapFactory.Options().apply { inSampleSize = escala })
            ?: return null
        return BitmapDrawable(c.resources, b)
    }
}
