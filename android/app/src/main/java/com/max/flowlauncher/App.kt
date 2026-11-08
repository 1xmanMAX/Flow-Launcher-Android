package com.max.flowlauncher

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class App : Application() {

    companion object {
        lateinit var app: App
            private set

        /** Para lo que no puede ir en el hilo de la interfaz. */
        val fondo: ExecutorService = hilos("flow-fondo", 1, Thread.NORM_PRIORITY - 1)

        val principal = Handler(Looper.getMainLooper())

        /** Se abre cuando el nucleo ya esta listo. */
        val listo = CountDownLatch(1)

        /**
         * Hilos que nunca tumban la app: un fallo en una tarea se apunta en
         * el log y el hilo sigue; lo que llega tras cerrarlos se ignora.
         */
        fun hilos(nombre: String, n: Int = 1, prioridad: Int = Thread.NORM_PRIORITY): ExecutorService =
            Seguro(nombre, n, prioridad)
    }

    private class Seguro(nombre: String, n: Int, prioridad: Int) : ThreadPoolExecutor(
        n, n, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        ThreadFactory { r ->
            Thread(r, nombre).apply {
                priority = prioridad
                // Por si algo se escapa igualmente: nada de cerrar el proceso.
                setUncaughtExceptionHandler { t, e -> Log.e("Flow", "Fallo en ${t.name}", e) }
            }
        },
    ) {
        override fun execute(r: Runnable) {
            try {
                super.execute {
                    try {
                        r.run()
                    } catch (e: Throwable) {
                        Log.e("Flow", "Fallo en segundo plano", e)
                    }
                }
            } catch (e: RejectedExecutionException) {
                // Ya cerrado (la ventana se fue): no hay a quien contestar.
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        fondo.execute {
            try {
                // Disco lleno, assets rotos...: se sigue sin los plugins incluidos.
                val plugins = runCatching { Plugins.instalarIncluidos(this) }
                    .getOrElse { File(filesDir, "plugins") }
                Nucleo.iniciar(
                    File(filesDir, "datos").absolutePath,
                    applicationInfo.nativeLibraryDir,
                    plugins.absolutePath,
                )
            } catch (e: Throwable) {
                Log.e("Flow", "No se pudo iniciar el nucleo", e)
            } finally {
                // Pase lo que pase, nadie se queda esperando.
                listo.countDown()
            }
            runCatching { Fuentes.cargarTodo(this) }.onFailure { Log.e("Flow", "Fuentes", it) }
            runCatching { Proveedores.cargar(this) }
        }
        // Al abrir el buscador, los archivos al dia (el nucleo decide si hace
        // falta: casi siempre no hace nada).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                if (a !is BuscarActivity) return
                fondo.execute {
                    listo.await(3, TimeUnit.SECONDS)
                    runCatching { Fuentes.indexarArchivos(a.applicationContext) }
                }
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // Apps instaladas o quitadas: releerlas.
        val filtro = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                fondo.execute {
                    Fuentes.cargarApps(c)
                    // Una app instalada o actualizada puede ser un proveedor nuevo.
                    Proveedores.cargar(c)
                }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receptor, filtro, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receptor, filtro)
            }
        }
    }
}
