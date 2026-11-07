package com.max.flowlauncher

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** El boton «Buscar» de los ajustes rapidos. */
class TileBuscar : TileService() {
    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val i = Intent(this, BuscarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}

/** Tras reiniciar el movil o actualizar la app, la burbuja vuelve si estaba. */
class AlArrancar : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (BurbujaService.activa(c)) runCatching { BurbujaService.cambiar(c, true) }
    }
}
