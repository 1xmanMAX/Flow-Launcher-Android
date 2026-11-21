package com.max.flowlauncher

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.widget.RemoteViews
import android.widget.Toast

/**
 * **El widget del buscador** (21-nov-2026, el usuario: «en vez de una bolita flotante, un widget de buscador en la
 * pantalla principal, y directamente se pueda buscar; con atajos a lo más usado de Android»).
 *
 * Un widget no puede tener una caja de texto (Android no lo deja), así que se hace como el de Google: la barra
 * entera abre el buscador **ya con el teclado puesto y vacío**, que enseña las últimas apps abiertas; el micrófono
 * abre el buscador escuchando. Dos tamaños:
 * - [Widget] (4 × 1): solo la barra.
 * - [WidgetConAtajos] (4 × 2): la barra y una fila de atajos: Google, archivos, Wi‑Fi, Bluetooth, linterna,
 *   cámara, alarmas y ajustes.
 */
open class Widget : AppWidgetProvider() {

    protected open val layout = R.layout.widget_buscador

    override fun onUpdate(c: Context, gestor: AppWidgetManager, ids: IntArray) {
        for (id in ids) gestor.updateAppWidget(id, vistas(c))
    }

    protected open fun vistas(c: Context) = RemoteViews(c.packageName, layout).apply {
        setOnClickPendingIntent(R.id.w_barra, buscador(c, 1, null))
        setOnClickPendingIntent(R.id.w_voz, buscador(c, 2, null, voz = true))
    }

    companion object {
        const val EXTRA_NUEVO = "nuevo"
        const val EXTRA_VOZ = "voz"
        /** El modo con el que abrir: [MODO_ARCHIVOS] o [MODO_GOOGLE] (su palabra clave la resuelve el buscador). */
        const val EXTRA_MODO = "modo"
        const val MODO_ARCHIVOS = "archivos"
        const val MODO_GOOGLE = "google"

        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        /** Abre el buscador vacío (las últimas apps), escuchando, o en un modo. [codigo]: uno distinto por botón. */
        fun buscador(c: Context, codigo: Int, modo: String?, voz: Boolean = false): PendingIntent =
            PendingIntent.getActivity(
                c, codigo,
                Intent(c, BuscarActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(EXTRA_NUEVO, true)
                    .putExtra(EXTRA_VOZ, voz)
                    .putExtra(EXTRA_MODO, modo),
                FLAGS,
            )

        /** Si el lanzador deja poner el widget desde la app (Android 8+, y no todos los lanzadores). */
        fun sePuedePedir(c: Context): Boolean =
            Build.VERSION.SDK_INT >= 26 && c.getSystemService(AppWidgetManager::class.java)?.isRequestPinAppWidgetSupported == true

        /** **Ponerlo en la pantalla principal** desde los ajustes: el lanzador pregunta dónde. */
        fun pedir(c: Context, conAtajos: Boolean): Boolean {
            if (Build.VERSION.SDK_INT < 26) return false
            val gestor = c.getSystemService(AppWidgetManager::class.java) ?: return false
            if (!gestor.isRequestPinAppWidgetSupported) return false
            val cual = ComponentName(c, if (conAtajos) WidgetConAtajos::class.java else Widget::class.java)
            return runCatching { gestor.requestPinAppWidget(cual, null, null) }.getOrDefault(false)
        }
    }
}

/** El widget de 4 × 2: la barra y los atajos. */
class WidgetConAtajos : Widget() {
    override val layout = R.layout.widget_buscador_atajos

    override fun vistas(c: Context) = super.vistas(c).apply {
        setOnClickPendingIntent(R.id.w_a_google, buscador(c, 10, MODO_GOOGLE))
        setOnClickPendingIntent(R.id.w_a_archivos, buscador(c, 11, MODO_ARCHIVOS))
        setOnClickPendingIntent(R.id.w_a_wifi, actividad(c, 12, Atajos.wifi()))
        setOnClickPendingIntent(R.id.w_a_bluetooth, actividad(c, 13, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
        setOnClickPendingIntent(
            R.id.w_a_linterna,
            PendingIntent.getBroadcast(c, 14, Intent(c, Atajos::class.java).setAction(Atajos.LINTERNA), PendingIntent.FLAG_IMMUTABLE),
        )
        setOnClickPendingIntent(R.id.w_a_camara, actividad(c, 15, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)))
        setOnClickPendingIntent(R.id.w_a_alarma, actividad(c, 16, Intent(AlarmClock.ACTION_SHOW_ALARMS)))
        setOnClickPendingIntent(R.id.w_a_ajustes, actividad(c, 17, Intent(Settings.ACTION_SETTINGS)))
    }

    private fun actividad(c: Context, codigo: Int, i: Intent) =
        PendingIntent.getActivity(c, codigo, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** Lo que un atajo hace sin abrir nada: la linterna. */
class Atajos : BroadcastReceiver() {
    companion object {
        const val LINTERNA = "com.max.flowlauncher.LINTERNA"

        /** El panel de Wi‑Fi (Android 10+, encima de lo que haya), o sus ajustes. */
        fun wifi(): Intent = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
    }

    override fun onReceive(c: Context, i: Intent) {
        // Solo lo nuestro: el receptor no se exporta, pero se mira igual.
        if (i.action != LINTERNA) return
        runCatching { Acciones.ejecutar(c, "interno:linterna") {} }
            .onFailure { Toast.makeText(c, "No se pudo usar la linterna", Toast.LENGTH_SHORT).show() }
    }
}
