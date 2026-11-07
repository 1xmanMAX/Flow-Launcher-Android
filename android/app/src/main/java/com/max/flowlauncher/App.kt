package com.max.flowlauncher

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application() {

    companion object {
        lateinit var app: App
            private set

        /** Para lo que no puede ir en el hilo de la interfaz. */
        val fondo: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "flow-fondo").apply { priority = Thread.NORM_PRIORITY - 1 }
        }

        val principal = Handler(Looper.getMainLooper())

        /** Se abre cuando el nucleo ya esta listo. */
        val listo = CountDownLatch(1)
    }

    override fun onCreate() {
        super.onCreate()
        app = this
        fondo.execute {
            val plugins = Plugins.instalarIncluidos(this)
            Nucleo.iniciar(
                File(filesDir, "datos").absolutePath,
                applicationInfo.nativeLibraryDir,
                plugins.absolutePath,
            )
            listo.countDown()
            Fuentes.cargarTodo(this)
        }
        // Apps instaladas o quitadas: releerlas.
        val filtro = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                fondo.execute { Fuentes.cargarApps(c) }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receptor, filtro, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receptor, filtro)
        }
    }
}
