package com.max.flowlauncher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.abs

/**
 * La burbuja flotante: un circulo sobre las demas apps que abre el buscador.
 * Se arrastra a donde se quiera y se pega al borde. Mantiene el proceso
 * vivo, asi el buscador se abre siempre «en caliente».
 */
class BurbujaService : Service() {

    companion object {
        private const val CANAL = "burbuja"
        private const val PREFS = "burbuja"

        fun activa(c: Context) = c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("activa", false)

        fun cambiar(c: Context, activa: Boolean) {
            c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("activa", activa).apply()
            val i = Intent(c, BurbujaService::class.java)
            if (!activa) {
                c.stopService(i)
            } else if (puede(c)) {
                // Android 12+ puede negarlo (ForegroundServiceStartNotAllowedException).
                try {
                    if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
                } catch (e: Exception) {
                    Log.w("Flow", "No se pudo arrancar la burbuja", e)
                }
            }
        }

        fun puede(c: Context) = Settings.canDrawOverlays(c)

        fun abrirBuscador(c: Context) {
            try {
                c.startActivity(
                    Intent(c, BuscarActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            } catch (e: RuntimeException) {
                Log.w("Flow", "No se pudo abrir el buscador", e)
            }
        }
    }

    private var vista: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var lado = 0
    private lateinit var wm: WindowManager

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        if (!primerPlano() || !puede(this) || !mostrar()) stopSelf()
    }

    /** Al girar la pantalla, la burbuja sigue pegada a su borde y dentro. */
    override fun onConfigurationChanged(nueva: Configuration) {
        super.onConfigurationChanged(nueva)
        val v = vista ?: return
        val p = lp ?: return
        colocar(p, alBorde = p.x > 0)
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** Al borde (derecho si [alBorde]) y sin salirse por arriba ni por abajo. */
    private fun colocar(p: WindowManager.LayoutParams, alBorde: Boolean) {
        val m = resources.displayMetrics
        p.x = if (alBorde) (m.widthPixels - lado).coerceAtLeast(0) else 0
        p.y = p.y.coerceIn(0, (m.heightPixels - lado).coerceAtLeast(0))
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY

    override fun onDestroy() {
        vista?.let { runCatching { wm.removeView(it) } }
        vista = null
        super.onDestroy()
    }

    /** Devuelve si se pudo pasar a primer plano. */
    private fun primerPlano(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm?.createNotificationChannel(
                NotificationChannel(CANAL, getString(R.string.canal_burbuja), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, BuscarActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANAL) else Notification.Builder(this)
        val n = b.setSmallIcon(R.drawable.ic_lupa_blanca)
            .setContentTitle(getString(R.string.burbuja_activa))
            .setContentText(getString(R.string.toca_para_buscar))
            .setContentIntent(abrir)
            .setOngoing(true)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, n)
            }
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (31+), SecurityException...
            Log.w("Flow", "La burbuja no pudo pasar a primer plano", e)
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun mostrar(): Boolean {
        val d = resources.displayMetrics.density
        val lado = (52 * d).toInt()
        this.lado = lado
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val tipo = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            lado, lado, tipo,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", resources.displayMetrics.widthPixels - lado)
            y = prefs.getInt("y", resources.displayMetrics.heightPixels / 3)
        }
        // Lo guardado puede ser de otra orientacion o de otra pantalla.
        colocar(lp, alBorde = lp.x > 0)
        val v = ImageView(this).apply {
            setImageResource(R.drawable.ic_lupa_blanca)
            setBackgroundResource(R.drawable.burbuja)
            val m = (13 * d).toInt()
            setPadding(m, m, m, m)
            alpha = 0.85f
            contentDescription = getString(R.string.buscar)
            elevation = 6 * d
        }
        val umbral = ViewConfiguration.get(this).scaledTouchSlop
        var x0 = 0
        var y0 = 0
        var tx = 0f
        var ty = 0f
        var arrastrando = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = lp.x; y0 = lp.y; tx = e.rawX; ty = e.rawY
                    arrastrando = false
                    v.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (!arrastrando && (abs(dx) > umbral || abs(dy) > umbral)) arrastrando = true
                    if (arrastrando) {
                        lp.x = x0 + dx.toInt()
                        lp.y = y0 + dy.toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 0.85f
                    if (arrastrando) {
                        // Al borde mas cercano.
                        val ancho = resources.displayMetrics.widthPixels
                        lp.x = if (lp.x + lado / 2 < ancho / 2) 0 else ancho - lado
                        lp.y = lp.y.coerceIn(0, resources.displayMetrics.heightPixels - lado)
                        runCatching { wm.updateViewLayout(v, lp) }
                        prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        abrirBuscador(this)
                    }
                }
            }
            true
        }
        return try {
            // BadTokenException, o el permiso se quito justo ahora.
            wm.addView(v, lp)
            vista = v
            this.lp = lp
            true
        } catch (e: RuntimeException) {
            Log.w("Flow", "No se pudo mostrar la burbuja", e)
            false
        }
    }
}
