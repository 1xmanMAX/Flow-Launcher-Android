package com.max.flowlauncher

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets

/**
 * Las barras del sistema (y el teclado) como relleno de una vista. Con
 * targetSdk 35, Android 15 dibuja siempre de borde a borde y `adjustResize`
 * ya no encoge la ventana: el sitio hay que dejarlo a mano. Sin AndroidX.
 */
object Bordes {

    /**
     * La ventana se dibuja bajo las barras y el teclado en todas las
     * versiones, asi el relleno se calcula siempre igual (como hace
     * `WindowCompat.setDecorFitsSystemWindows(window, false)`).
     */
    fun deBordeABorde(w: Window) {
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = w.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /** Suma a su relleno de siempre lo que ocupan las barras (y el teclado). */
    fun vigilar(v: View, conTeclado: Boolean) {
        val l = v.paddingLeft
        val t = v.paddingTop
        val r = v.paddingRight
        val b = v.paddingBottom
        v.setOnApplyWindowInsetsListener { vista, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                var tipos = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                if (conTeclado) tipos = tipos or WindowInsets.Type.ime()
                val x = ins.getInsets(tipos)
                vista.setPadding(l + x.left, t + x.top, r + x.right, b + x.bottom)
                WindowInsets.CONSUMED
            } else {
                // Antes de 11 el teclado ya va dentro de systemWindowInsets (con adjustResize).
                @Suppress("DEPRECATION")
                vista.setPadding(
                    l + ins.systemWindowInsetLeft, t + ins.systemWindowInsetTop,
                    r + ins.systemWindowInsetRight, b + ins.systemWindowInsetBottom,
                )
                @Suppress("DEPRECATION")
                ins.consumeSystemWindowInsets()
            }
        }
        v.requestApplyInsets()
    }
}
