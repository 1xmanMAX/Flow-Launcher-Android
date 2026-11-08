package com.max.flowlauncher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.text.Normalizer

/**
 * **Cambiar de app rapido** (`w`), el Window Walker de Flow en Android: las
 * apps usadas hace poco, de la mas reciente a la mas antigua. La app en la
 * que se estaba va al final, asi Intro lleva a la anterior, como Alt+Tab.
 *
 * Android no deja ver las ventanas de otras apps; lo que si deja, con el
 * permiso «Acceso a datos de uso», es saber cuando se uso cada una. Abrirla
 * con su icono trae su tarea tal como estaba (no la empieza de cero).
 */
object Ventanas {
    const val PALABRA = "w"
    private const val HORAS = 24L
    private const val MAX = 30
    private const val CACHE_MS = 3000L

    /** Lo que va detras de `w `, o null si la consulta no es para esto. */
    fun filtro(consulta: String): String? {
        val q = consulta.trimStart()
        if (!q.startsWith("$PALABRA ", ignoreCase = true)) return null
        return q.substring(PALABRA.length + 1).trim()
    }

    fun permitido(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
        val modo = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        }
        return modo == AppOpsManager.MODE_ALLOWED
    }

    fun pedirPermiso(c: Context) {
        Acciones.abrir(c, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private class App(val paquete: String, val actividad: String, val nombre: String, val cuando: Long)

    @Volatile
    private var cache: Pair<Long, List<App>>? = null
    private val nombres = HashMap<String, String>()

    /** Las apps usadas en las ultimas [HORAS] horas, la mas reciente primero. En un hilo de fondo. */
    private fun recientes(c: Context): List<App> {
        val ahora = System.currentTimeMillis()
        cache?.let { (t, l) -> if (ahora - t < CACHE_MS) return l }
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val ultimo = HashMap<String, Long>()
        val ev = usm.queryEvents(ahora - HORAS * 3_600_000, ahora)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) ultimo[e.packageName] = e.timeStamp
        }
        val pm = c.packageManager
        // Fuera: esta app, el escritorio y la barra del sistema.
        val escritorios = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        val fuera = escritorios + setOf(c.packageName, "com.android.systemui", "android")
        val l = ultimo.entries
            .filter { it.key !in fuera }
            .sortedByDescending { it.value }
            .mapNotNull { (paquete, cuando) ->
                val lanzar = pm.getLaunchIntentForPackage(paquete)?.component ?: return@mapNotNull null
                val nombre = synchronized(nombres) {
                    nombres.getOrPut(paquete) {
                        runCatching { pm.getApplicationInfo(paquete, 0).loadLabel(pm).toString() }.getOrDefault(paquete)
                    }
                }
                App(paquete, lanzar.className, nombre, cuando)
            }
            .take(MAX)
        cache = ahora to l
        return l
    }

    private fun normal(s: String) =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Lo escrito esta en el nombre, o son sus iniciales (`gm` -> Google Maps). */
    private fun encaja(nombre: String, q: String): Boolean {
        if (q.isEmpty()) return true
        val n = normal(nombre)
        if (n.contains(q)) return true
        val iniciales = n.split(' ', '-', '.').filter { it.isNotEmpty() }.joinToString("") { it.take(1) }
        return iniciales.startsWith(q)
    }

    private fun hace(ms: Long): String {
        val min = (System.currentTimeMillis() - ms) / 60_000
        return when {
            min < 1 -> "Hace un momento"
            min < 60 -> "Hace $min min"
            min < 120 -> "Hace 1 hora"
            else -> "Hace ${min / 60} horas"
        }
    }

    fun buscar(c: Context, texto: String): List<Resultado> {
        if (!permitido(c)) {
            return listOf(
                fila(
                    "Permitir ver las apps recientes",
                    "Ajustes → Acceso a datos de uso → Flow Buscador",
                    "ventanas:permiso", "res:ic_ajustes", 5000,
                ),
            )
        }
        val q = normal(texto)
        val todas = runCatching { recientes(c) }.getOrDefault(emptyList())
        if (todas.isEmpty()) {
            return listOf(fila("Sin apps recientes", "Las que uses saldrán aquí", "", "res:ic_buscar", 0))
        }
        // La primera es la app de la que se vino: al final, para que Intro
        // lleve a la anterior (como Alt+Tab).
        val orden = if (q.isEmpty() && todas.size > 1) todas.drop(1) + todas.first() else todas
        val actual = todas.first().paquete
        return orden.filter { encaja(it.nombre, q) }.mapIndexed { i, a ->
            fila(
                a.nombre,
                if (a.paquete == actual) "La app en la que estabas" else hace(a.cuando),
                "app:${a.paquete}/${a.actividad}",
                "app:${a.paquete}/${a.actividad}",
                // Por encima de todo y en este orden.
                6000 - i,
            )
        }
    }

    private fun fila(titulo: String, sub: String, accion: String, icono: String, puntos: Int) = Resultado(
        titulo = titulo, subtitulo = sub, icono = icono, puntos = puntos, accion = accion,
        fuente = "ventanas", resaltado = IntArray(0), accionPlugin = null, contexto = null,
        autocompletar = null, copiar = null,
    )
}
