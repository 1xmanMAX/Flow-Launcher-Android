package com.max.flowlauncher

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * **Las búsquedas de Google, en el buscador** (21-nov-2026, el usuario: «que se integre el buscador de Google a
 * través de su query, y las búsquedas de Google directamente ahí»).
 *
 * Lo que va escribiéndose se completa con las sugerencias de Google (las de la caja de google.com), y cada una abre
 * su búsqueda. Con la etiqueta «G Google» (`g `) salen todas; sin etiqueta, unas pocas al final, debajo de lo del
 * móvil, si está encendido en Ajustes ([enTodas]).
 *
 * Lo escrito solo se manda a Google así, por https, y nunca lo de los modos de archivos, contactos, plugins o apps.
 * Sin red, o si tarda más de [ESPERA_MS], no sale nada (el resto del buscador no espera).
 */
object SugerenciasGoogle {
    const val ESPERA_MS = 1500
    /** Con la etiqueta de Google. */
    const val MAX_EN_GOOGLE = 8
    /** Sin etiqueta, al final. */
    const val MAX_EN_TODAS = 3

    private const val PREFS = "google"

    /** Si salen también sin la etiqueta de Google (Ajustes → Búsquedas web). Encendido de serie. */
    fun enTodas(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("en_todas", true)

    fun cambiarEnTodas(c: Context, si: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("en_todas", si).apply()

    /** El enlace de la búsqueda de [q] en Google. */
    fun buscar(q: String) = "https://www.google.com/search?q=" + Uri.encode(q)

    /** **Las sugerencias de [q]**, o vacío. Con red: fuera del hilo de la pantalla. */
    fun de(q: String): List<String> {
        val t = q.trim()
        if (t.isEmpty() || t.length > 200) return emptyList()
        val idioma = Locale.getDefault().language.ifEmpty { "es" }
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&ie=utf-8&oe=utf-8&hl=$idioma&q=" + Uri.encode(t)
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = ESPERA_MS
            readTimeout = ESPERA_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (con.responseCode != 200) return emptyList()
            leer(con.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
        } finally {
            con.disconnect()
        }
    }

    /** La respuesta de Google, `["gatos", ["gatos negros", "gatos bebes", …]]`, a una lista. */
    fun leer(json: String): List<String> = runCatching {
        val a = JSONArray(json).getJSONArray(1)
        (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
    }.getOrDefault(emptyList())

    /** Las filas de [sugerencias] para [palabra] (la de Google, para autocompletar dentro de su etiqueta). */
    fun filas(sugerencias: List<String>, palabra: String, max: Int, puntos: Int): List<Resultado> =
        sugerencias.take(max).mapIndexed { i, s ->
            Resultado(
                titulo = s, subtitulo = "Buscar en Google", icono = "res:ic_google", puntos = puntos - i,
                accion = "url:${buscar(s)}", fuente = "web", resaltado = IntArray(0), accionPlugin = null,
                contexto = null, autocompletar = "$palabra $s", copiar = s,
            )
        }
}
