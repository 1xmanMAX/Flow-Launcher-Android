package com.max.flowlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Las apps que ponen sus resultados en el buscador sin ser plugins: un
 * `ContentProvider` con la accion `com.max.flowlauncher.PROVEEDOR` (ver
 * `docs/proveedores.md`). PixPin lo usa para que `p tesis` encuentre sus
 * archivos, mensajes y listas.
 *
 * Se les pregunta a todas a la vez, en otro hilo, con limite de tiempo: una
 * app lenta se queda fuera, no frena a las demas.
 */
object Proveedores {
    const val ACCION = "com.max.flowlauncher.PROVEEDOR"
    private const val META_PALABRA = "com.max.flowlauncher.palabra"
    private const val META_NOMBRE = "com.max.flowlauncher.nombre"
    /** Cuanto se espera a cada proveedor. */
    private const val ESPERA_MS = 400L
    private const val MAX_FILAS = 30

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
    }

    fun todos(): List<Proveedor> = lista

    /** Como los plugins: con palabra clave (`p tesis`) solo los suyos; si no, los globales. */
    fun destinos(consulta: String): List<Destino> {
        val l = lista
        if (l.isEmpty()) return emptyList()
        val q = consulta.trimStart()
        val espacio = q.indexOf(' ')
        if (espacio > 0) {
            val palabra = q.substring(0, espacio)
            val con = l.filter { it.palabra != "*" && it.palabra.equals(palabra, ignoreCase = true) }
            if (con.isNotEmpty()) return con.map { Destino(it, q.substring(espacio + 1).trim(), false) }
        }
        if (q.isBlank()) return emptyList()
        return l.filter { it.palabra == "*" }.map { Destino(it, q.trim(), true) }
    }

    /** Arranca sus procesos antes de la primera tecla (la primera consulta a una app dormida tarda). */
    fun calentar(c: Context) {
        for (p in lista) hilos.execute { runCatching { preguntar(c, p, "", CancellationSignal())?.close() } }
    }

    private fun preguntar(c: Context, p: Proveedor, texto: String, cancelar: CancellationSignal) =
        c.contentResolver.query(
            Uri.parse("content://${p.autoridad}/buscar"), null, null, arrayOf(texto), null, cancelar,
        )

    /** Pregunta a todos los destinos a la vez; vuelve en [ESPERA_MS] como mucho. */
    fun buscar(c: Context, destinos: List<Destino>): List<Resultado> {
        if (destinos.isEmpty()) return emptyList()
        val tareas = destinos.map { d ->
            val cancelar = CancellationSignal()
            cancelar to hilos.submitOrNull { leer(c, d, cancelar) }
        }
        val fin = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ESPERA_MS)
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

    private fun leer(c: Context, d: Destino, cancelar: CancellationSignal): List<Resultado> {
        val cur = runCatching { preguntar(c, d.p, d.texto, cancelar) }.getOrNull() ?: return emptyList()
        val r = ArrayList<Resultado>()
        cur.use {
            fun col(n: String) = it.getColumnIndex(n)
            val ti = col("titulo")
            val su = col("subtitulo")
            val ic = col("icono")
            val pu = col("puntos")
            val ac = col("accion")
            val co = col("copiar")
            while (it.moveToNext() && r.size < MAX_FILAS) {
                fun texto(i: Int) = if (i >= 0) runCatching { it.getString(i) }.getOrNull().orEmpty() else ""
                val titulo = texto(ti)
                val accion = texto(ac)
                if (titulo.isEmpty() || accion.isEmpty()) continue
                val puntos = if (pu >= 0) runCatching { it.getInt(pu) }.getOrDefault(0).coerceIn(0, 1000) else 0
                r += Resultado(
                    titulo = titulo,
                    subtitulo = texto(su),
                    icono = texto(ic).ifEmpty { "pkg:${d.p.paquete}" },
                    // Con su palabra clave, por encima de lo demas; globales,
                    // por debajo de lo local muy seguro (como los plugins).
                    puntos = if (d.global) puntos - 200 else 3300 + puntos,
                    accion = "url:$accion",
                    fuente = "proveedor:${d.p.paquete}",
                    resaltado = IntArray(0),
                    accionPlugin = null,
                    contexto = null,
                    autocompletar = null,
                    copiar = texto(co).ifEmpty { null },
                )
            }
        }
        return r
    }

    /** [buscar] en otro hilo, para hacerlo a la vez que los plugins. */
    fun buscarAparte(c: Context, destinos: List<Destino>): Future<List<Resultado>>? =
        coordinador.submitOrNull { buscar(c, destinos) }

    private fun <T> java.util.concurrent.ExecutorService.submitOrNull(f: () -> T): Future<T>? =
        runCatching { submit(Callable { f() }) }.getOrNull()
}
