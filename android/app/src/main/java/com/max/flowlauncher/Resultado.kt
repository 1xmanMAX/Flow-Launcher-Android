package com.max.flowlauncher

import org.json.JSONArray
import org.json.JSONObject

/** Un resultado del nucleo (ver `core/flow-core/src/resultado.rs`). */
class Resultado(
    val titulo: String,
    val subtitulo: String,
    val icono: String,
    val puntos: Int,
    val accion: String,
    val fuente: String,
    val resaltado: IntArray,
    /** La `jsonRPCAction` del plugin, en JSON. */
    val accionPlugin: String?,
    /** El `contextData` del plugin, en JSON. */
    val contexto: String?,
    val autocompletar: String?,
    val copiar: String?,
) {
    val pluginId: String? get() = if (fuente.startsWith("plugin:")) fuente.substring(7) else null

    /** Como lo recuerda el historial (igual que `clave_historial` en Rust). */
    val claveHistorial: String get() = if (accion.startsWith("plugin:")) "$accion|$titulo" else accion

    companion object {
        fun de(o: JSONObject): Resultado {
            val r = o.optJSONArray("r")
            val resaltado = if (r == null) IntArray(0) else IntArray(r.length()) { r.optInt(it) }
            return Resultado(
                titulo = o.optString("t"),
                subtitulo = o.optString("s"),
                icono = o.optString("i"),
                puntos = o.optInt("p"),
                accion = o.optString("a"),
                fuente = o.optString("f"),
                resaltado = resaltado,
                accionPlugin = o.opt("pa")?.toString(),
                contexto = o.opt("cx")?.toString(),
                autocompletar = o.optString("ac").ifEmpty { null },
                copiar = o.optString("cp").ifEmpty { null },
            )
        }

        fun lista(a: JSONArray?): List<Resultado> {
            if (a == null) return emptyList()
            // Lo que no sea un objeto se salta en vez de fallar.
            return (0 until a.length()).mapNotNull { n -> a.optJSONObject(n)?.let(::de) }
        }
    }
}
