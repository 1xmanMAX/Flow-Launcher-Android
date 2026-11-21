package com.max.flowlauncher

import android.content.Context
import org.json.JSONObject

/**
 * **Los modos de la caja de búsqueda** (21-nov-2026, el usuario: «cuando escribimos la C, que no solo aparezca la C,
 * sino que se salte a archivos con el icono de una carpeta; con la de Google, una G»).
 *
 * Escribir una palabra clave y un espacio (`c `, `g `, `yt `, `p `, `wa `…) la convierte en una **etiqueta** delante
 * de la caja, con su icono y su nombre («📁 Archivos», «G Google»); lo que se escribe después se busca ahí. Borrar
 * con la caja vacía quita la etiqueta. Por dentro la consulta sigue siendo la de siempre (`c tesis`): el núcleo, los
 * plugins y las apps proveedoras no cambian.
 */
object Modos {

    /** Un modo: su [palabra] clave, cómo se llama y su icono (las claves de [Iconos]). */
    data class Modo(val palabra: String, val nombre: String, val icono: String, val tipo: Tipo, val url: String? = null) {
        /** Si es la búsqueda de Google (tiene sugerencias, ver [SugerenciasGoogle]). */
        val esGoogle: Boolean get() = tipo == Tipo.WEB && url?.contains("google.", ignoreCase = true) == true && url.contains("/search")
    }

    enum class Tipo { ARCHIVOS, WEB, PLUGIN, APP, COMANDO }

    @Volatile
    private var todos: List<Modo> = emptyList()

    /** Releerlos (al abrir el buscador; las palabras se pueden cambiar en Ajustes). Fuera del hilo de la pantalla. */
    fun cargar(c: Context) {
        val l = ArrayList<Modo>()
        // Lo de las apps proveedoras manda sobre los plugins con su misma palabra (como en el núcleo).
        for (p in Proveedores.todos()) if (p.palabra != "*") l += Modo(p.palabra, p.nombre, "pkg:${p.paquete}", Tipo.APP)
        val estado = runCatching { JSONObject(Nucleo.estado()) }.getOrNull()
        if (estado != null) {
            if (estado.optInt("archivos") > 0 || estado.optBoolean("indexando")) {
                l += Modo(Palabras.de(c, "archivos", "c"), "Archivos", "res:ic_carpeta", Tipo.ARCHIVOS)
            }
            val web = estado.optJSONObject("busquedas")?.optJSONArray("SearchSources")
            if (web != null) for (i in 0 until web.length()) {
                val b = web.optJSONObject(i) ?: continue
                if (!b.optBoolean("Enabled", true)) continue
                val url = b.optString("Url")
                val m = Modo(b.optString("ActionKeyword"), b.optString("Title"), "def:web", Tipo.WEB, url)
                l += if (m.esGoogle) m.copy(icono = "res:ic_google") else m
            }
            val plugins = estado.optJSONArray("plugins")
            if (plugins != null) for (i in 0 until plugins.length()) {
                val p = plugins.optJSONObject(i) ?: continue
                if (!p.optBoolean("compatible", true)) continue
                val palabras = p.optJSONArray("palabras") ?: continue
                val icono = p.optString("icono").takeIf { it.isNotEmpty() && it != "null" }.orEmpty()
                for (k in 0 until palabras.length()) {
                    val w = palabras.optString(k)
                    if (w.isNotEmpty() && w != "*") l += Modo(w, p.optString("nombre"), icono.ifEmpty { "def:plugin:${p.optString("id")}" }, Tipo.PLUGIN)
                }
            }
        }
        for ((palabra, nombre, _) in Comandos.lista()) {
            l += Modo(Palabras.de(c, "cmd:$palabra", palabra), nombre, iconoDeComando(palabra), Tipo.COMANDO)
        }
        // La primera con cada palabra gana (proveedor > archivos > web > plugin > comando).
        todos = l.filter { it.palabra.isNotBlank() }.distinctBy { it.palabra.lowercase() }
    }

    private fun iconoDeComando(palabra: String) = when (palabra) {
        "wa" -> "pkg:com.whatsapp"
        "tg" -> "pkg:org.telegram.messenger"
        "sp" -> "pkg:com.spotify.music"
        else -> "res:ic_buscar"
    }

    /** El modo de esta [palabra], o null. */
    fun de(palabra: String): Modo? = todos.firstOrNull { it.palabra.equals(palabra, ignoreCase = true) }

    /**
     * **Si lo escrito empieza por una palabra clave y un espacio**, su modo y lo que va detrás (`"c tesis"` →
     * Archivos, `"tesis"`). Si no, null.
     */
    fun partir(texto: String): Pair<Modo, String>? {
        val i = texto.indexOf(' ')
        if (i <= 0) return null
        val m = de(texto.substring(0, i)) ?: return null
        return m to texto.substring(i + 1)
    }

    /** El modo de los archivos o el de Google, para los atajos del widget. */
    fun delWidget(c: Context, cual: String?): Modo? = when (cual) {
        Widget.MODO_ARCHIVOS -> todos.firstOrNull { it.tipo == Tipo.ARCHIVOS } ?: Modo(Palabras.de(c, "archivos", "c"), "Archivos", "res:ic_carpeta", Tipo.ARCHIVOS)
        Widget.MODO_GOOGLE -> todos.firstOrNull { it.esGoogle }
        else -> null
    }

    /** Para pintar su icono con [Iconos.poner], que trabaja con resultados. */
    fun comoResultado(m: Modo) = Resultado(
        titulo = m.nombre, subtitulo = "", icono = m.icono.removePrefix("def:").let { if (m.icono.startsWith("def:")) "" else m.icono },
        puntos = 0, accion = "", fuente = when {
            m.icono.startsWith("def:plugin:") -> m.icono.removePrefix("def:")
            m.tipo == Tipo.WEB -> "web"
            m.tipo == Tipo.ARCHIVOS -> "archivos"
            else -> "buscar"
        },
        resaltado = IntArray(0), accionPlugin = null, contexto = null, autocompletar = null, copiar = null,
    )
}
