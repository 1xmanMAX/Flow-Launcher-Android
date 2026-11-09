package com.max.flowlauncher

import android.content.Context

/**
 * Las palabras clave que el usuario cambio en Ajustes (`wa` → `w`, `p` →
 * `pin`...). Lo que no se cambio usa la de siempre.
 *
 * Claves: `cmd:<comando>` (wa, tg, timer...), `archivos`, `plugin:<ID>` y
 * `prov:<paquete>` (las apps proveedoras, como PinDesk).
 */
object Palabras {
    private const val PREFS = "palabras"

    @Volatile
    private var cache: Map<String, String>? = null

    private fun todas(c: Context): Map<String, String> = cache ?: runCatching {
        @Suppress("UNCHECKED_CAST")
        (c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all as Map<String, Any?>)
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap()).also { cache = it }

    /** La palabra de [clave], o [porDefecto] si no se cambio. */
    fun de(c: Context, clave: String, porDefecto: String): String = todas(c)[clave] ?: porDefecto

    /** Si se cambio (y a que). */
    fun cambiada(c: Context, clave: String): String? = todas(c)[clave]

    /** Valida: sin espacios, de 1 a 12 letras. Devuelve el error o null. */
    fun error(p: String): String? = when {
        p.isBlank() -> null
        p.any { it.isWhitespace() } -> "Sin espacios"
        p.length > 12 -> "Como mucho 12 letras"
        else -> null
    }

    /** Cambiarla (vacia: volver a la de siempre) y que el nucleo se entere. */
    fun cambiar(c: Context, clave: String, palabra: String) {
        val p = palabra.trim()
        val e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (p.isEmpty()) e.remove(clave) else e.putString(clave, p)
        e.apply()
        cache = null
        aplicarUna(c, clave)
    }

    /** Al arrancar: las del nucleo (archivos y plugins). En el hilo de fondo. */
    fun aplicar(c: Context) {
        for (k in todas(c).keys) aplicarUna(c, k)
    }

    private fun aplicarUna(c: Context, clave: String) {
        val p = cambiada(c, clave).orEmpty()
        runCatching {
            when {
                clave == "archivos" -> Nucleo.palabraArchivos(p)
                clave.startsWith("plugin:") -> Nucleo.palabrasPlugin(clave.removePrefix("plugin:"), if (p.isEmpty()) emptyArray() else arrayOf(p))
                // Una app proveedora: se relee con su palabra nueva.
                clave.startsWith("prov:") -> App.fondo.execute { Proveedores.cargar(c) }
            }
        }
    }
}
