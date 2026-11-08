package com.max.flowlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Las apps que ponen sus resultados y sus acciones en el buscador sin ser
 * plugins: un `ContentProvider` con la accion `com.max.flowlauncher.PROVEEDOR`
 * (contrato v2 en `docs/proveedores.md`). PixPin lo usa para `p …`: buscar
 * sus archivos, mensajes y tareas, y apuntar, mandar o marcar sin abrirse.
 *
 * Se les pregunta a todas a la vez, en otro hilo, con limite de tiempo: una
 * app lenta se queda fuera, no frena a las demas.
 */
object Proveedores {
    const val ACCION = "com.max.flowlauncher.PROVEEDOR"
    private const val META_PALABRA = "com.max.flowlauncher.palabra"
    private const val META_NOMBRE = "com.max.flowlauncher.nombre"
    /** Cuanto se espera a cada proveedor al buscar. */
    private const val ESPERA_MS = 400L
    /** Y a un menu (se pidio a proposito: puede tardar un poco mas). */
    private const val ESPERA_MENU_MS = 1500L
    private const val MAX_FILAS = 30

    /** Las acciones `call:` llevan en `accion` esto, la autoridad y el metodo. */
    const val LLAMAR = "llamar:"

    class Proveedor(val paquete: String, val autoridad: String, val palabra: String, val nombre: String)

    /** A quien preguntar y con que texto. */
    class Destino(val p: Proveedor, val texto: String, val global: Boolean)

    @Volatile
    private var lista: List<Proveedor> = emptyList()
    private val hilos = App.hilos("flow-proveedores", 4)
    /** Quien espera a todos (aparte, para no quitarle un hilo a ellos). */
    private val coordinador = App.hilos("flow-proveedores-espera", 1)

    /** Lee del sistema que apps son proveedores. En el hilo de fondo. */
    fun cargar(c: Context) {
        val pm = c.packageManager
        lista = runCatching {
            pm.queryIntentContentProviders(Intent(ACCION), PackageManager.GET_META_DATA).mapNotNull { r ->
                val pi = r.providerInfo ?: return@mapNotNull null
                if (pi.packageName == c.packageName) return@mapNotNull null
                val autoridad = pi.authority?.split(';')?.firstOrNull() ?: return@mapNotNull null
                val meta = pi.metaData
                val palabra = meta?.getString(META_PALABRA)?.trim().orEmpty().ifEmpty { "*" }
                val nombre = meta?.getString(META_NOMBRE)
                    ?: runCatching { pi.applicationInfo.loadLabel(pm).toString() }.getOrDefault(pi.packageName)
                Proveedor(pi.packageName, autoridad, palabra, nombre)
            }
        }.getOrDefault(emptyList())
        // Sus palabras son suyas: los plugins con la misma (el `p` de reserva) se apagan.
        runCatching { Nucleo.cederPalabras(lista.map { it.palabra }.filter { it != "*" }.toTypedArray()) }
    }

    fun todos(): List<Proveedor> = lista

    private fun porAutoridad(a: String) = lista.firstOrNull { it.autoridad == a }

    /** Como los plugins: con palabra clave (`p tesis`) solo los suyos; si no, los globales. */
    fun destinos(consulta: String): List<Destino> {
        val l = lista
        if (l.isEmpty()) return emptyList()
        val q = consulta.trimStart()
        val espacio = q.indexOf(' ')
        if (espacio > 0) {
            val palabra = q.substring(0, espacio)
            val con = l.filter { it.palabra != "*" && it.palabra.equals(palabra, ignoreCase = true) }
            // Sin recortar el final: `p Tesis > ` no es lo mismo que `p Tesis >`.
            if (con.isNotEmpty()) return con.map { Destino(it, q.substring(espacio + 1), false) }
        }
        if (q.isBlank()) return emptyList()
        return l.filter { it.palabra == "*" }.map { Destino(it, q.trim(), true) }
    }

    /** Arranca sus procesos antes de la primera tecla (la primera consulta a una app dormida tarda). */
    fun calentar(c: Context) {
        for (p in lista) hilos.execute { runCatching { preguntar(c, p, "buscar", "", CancellationSignal())?.close() } }
    }

    private fun preguntar(c: Context, p: Proveedor, ruta: String, dato: String, cancelar: CancellationSignal) =
        c.contentResolver.query(Uri.parse("content://${p.autoridad}/$ruta"), null, null, arrayOf(dato), null, cancelar)

    /** [buscar] en otro hilo, para hacerlo a la vez que los plugins. */
    fun buscarAparte(c: Context, destinos: List<Destino>): Future<List<Resultado>>? =
        coordinador.submitOrNull { buscar(c, destinos) }

    /** Pregunta a todos los destinos a la vez; vuelve en [ESPERA_MS] como mucho. */
    fun buscar(c: Context, destinos: List<Destino>): List<Resultado> {
        if (destinos.isEmpty()) return emptyList()
        val tareas = destinos.map { d ->
            val cancelar = CancellationSignal()
            cancelar to hilos.submitOrNull { leer(c, d.p, "buscar", d.texto, d.global, cancelar) }
        }
        return esperar(tareas, ESPERA_MS)
    }

    /** El menu de una fila (su columna `menu`). En un hilo de fondo. */
    fun menu(c: Context, r: Resultado): List<Resultado> {
        val p = lista.firstOrNull { "proveedor:${it.paquete}" == r.fuente } ?: return emptyList()
        val dato = r.contexto ?: return emptyList()
        val cancelar = CancellationSignal()
        return esperar(listOf(cancelar to hilos.submitOrNull { leer(c, p, "menu", dato, false, cancelar) }), ESPERA_MENU_MS)
    }

    private fun esperar(tareas: List<Pair<CancellationSignal, Future<List<Resultado>>?>>, ms: Long): List<Resultado> {
        val fin = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        val r = ArrayList<Resultado>()
        for ((cancelar, f) in tareas) {
            if (f == null) continue
            val queda = (fin - System.nanoTime()).coerceAtLeast(0)
            runCatching { r += f.get(queda, TimeUnit.NANOSECONDS) }.onFailure {
                cancelar.cancel()
                f.cancel(true)
            }
        }
        return r
    }

    private fun leer(c: Context, p: Proveedor, ruta: String, dato: String, global: Boolean, cancelar: CancellationSignal): List<Resultado> {
        val cur = runCatching { preguntar(c, p, ruta, dato, cancelar) }.getOrNull() ?: return emptyList()
        val r = ArrayList<Resultado>()
        cur.use {
            val col = Columnas(it)
            while (it.moveToNext() && r.size < MAX_FILAS) {
                fila(it, col, p, global)?.let(r::add)
            }
        }
        return r
    }

    private class Columnas(c: Cursor) {
        val titulo = c.getColumnIndex("titulo")
        val subtitulo = c.getColumnIndex("subtitulo")
        val icono = c.getColumnIndex("icono")
        val puntos = c.getColumnIndex("puntos")
        val accion = c.getColumnIndex("accion")
        val extras = c.getColumnIndex("extras")
        val copiar = c.getColumnIndex("copiar")
        val autocompletar = c.getColumnIndex("autocompletar")
        val menu = c.getColumnIndex("menu")
        val resaltado = c.getColumnIndex("resaltado")
    }

    private fun fila(c: Cursor, col: Columnas, p: Proveedor, global: Boolean): Resultado? {
        fun texto(i: Int) = if (i >= 0) runCatching { c.getString(i) }.getOrNull().orEmpty() else ""
        val titulo = texto(col.titulo)
        if (titulo.isEmpty()) return null
        val crudo = texto(col.accion)
        val accion = when {
            crudo.startsWith("call:") -> "$LLAMAR${p.autoridad}:${crudo.removePrefix("call:")}"
            // Entrar en algo (un proyecto, una lista): cambia lo escrito.
            crudo.startsWith("consulta:") -> "consulta:${p.palabra} ${crudo.removePrefix("consulta:")}"
            crudo.isEmpty() -> ""
            else -> "url:$crudo"
        }
        val puntos = if (col.puntos >= 0) runCatching { c.getInt(col.puntos) }.getOrDefault(0).coerceIn(0, 1000) else 0
        val resaltado = texto(col.resaltado).split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
        return Resultado(
            titulo = titulo,
            subtitulo = texto(col.subtitulo),
            icono = texto(col.icono).ifEmpty { "pkg:${p.paquete}" },
            // Con su palabra clave, por encima de lo demas; globales, por
            // debajo de lo local muy seguro (como los plugins).
            puntos = if (global) puntos - 200 else 3300 + puntos,
            accion = accion,
            fuente = "proveedor:${p.paquete}",
            resaltado = resaltado,
            // Los datos de `call:` viajan aqui (JSON).
            accionPlugin = texto(col.extras).ifEmpty { null },
            // Y lo que pide su menu, aqui.
            contexto = texto(col.menu).ifEmpty { null },
            autocompletar = texto(col.autocompletar).ifEmpty { null }?.let { "${p.palabra} $it" },
            copiar = texto(col.copiar).ifEmpty { null },
        )
    }

    /** Lo que contesta un `call`. */
    class Respuesta(val mensaje: String?, val error: String?, val cerrar: Boolean, val consulta: String?, val abrir: String?)

    /**
     * Hace la accion `call:` de una fila, sin abrir la app. Bloquea: en un
     * hilo de fondo.
     */
    fun llamar(c: Context, r: Resultado): Respuesta {
        val resto = r.accion.removePrefix(LLAMAR)
        val autoridad = resto.substringBeforeLast(':')
        val metodo = resto.substringAfterLast(':')
        val p = porAutoridad(autoridad)
        val extras = Bundle()
        runCatching {
            val o = JSONObject(r.accionPlugin ?: "{}")
            for (k in o.keys()) {
                when (val v = o.get(k)) {
                    is Boolean -> extras.putBoolean(k, v)
                    is Int -> extras.putInt(k, v)
                    is Long -> extras.putLong(k, v)
                    is Double -> extras.putDouble(k, v)
                    JSONObject.NULL -> {}
                    else -> extras.putString(k, v.toString())
                }
            }
        }
        val b = runCatching {
            c.contentResolver.call(Uri.parse("content://$autoridad"), metodo, null, extras)
        }.getOrElse { e ->
            return Respuesta(null, "${p?.nombre ?: "La app"} no contestó (${e.javaClass.simpleName})", false, null, null)
        } ?: Bundle()
        val error = b.getString("error")
        return Respuesta(
            mensaje = b.getString("mensaje"),
            error = error,
            cerrar = error == null && b.getBoolean("cerrar", true),
            consulta = b.getString("consulta")?.let { "${p?.palabra ?: ""} $it".trimStart() },
            abrir = b.getString("abrir"),
        )
    }

    private fun <T> java.util.concurrent.ExecutorService.submitOrNull(f: () -> T): Future<T>? =
        runCatching { submit(Callable { f() }) }.getOrNull()
}
