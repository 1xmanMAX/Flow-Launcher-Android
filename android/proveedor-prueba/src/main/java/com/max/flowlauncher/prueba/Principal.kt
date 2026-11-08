package com.max.flowlauncher.prueba

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Una pantalla cualquiera: que «w» tenga una app reciente que ensenar. */
class Principal : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(TextView(this).apply { text = "Proveedor de prueba" })
    }
}
