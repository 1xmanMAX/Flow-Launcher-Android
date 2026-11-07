package com.max.flowlauncher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Los ajustes: burbuja, contactos, importar las busquedas web de Flow y la
 * lista de plugins. Hecha en codigo, sin dependencias.
 */
class MainActivity : Activity() {

    private companion object {
        const val PEDIR_CONTACTOS = 1
        const val IMPORTAR = 2
        const val NOTIFICACIONES = 3
    }

    private lateinit var col: LinearLayout
    private lateinit var burbuja: Switch
    private lateinit var contactos: Switch
    private lateinit var plugins: TextView

    private val d by lazy { resources.displayMetrics.density }

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val m = (20 * d).toInt()
            setPadding(m, m, m, m)
        }
        setContentView(ScrollView(this).apply { addView(col) })

        titulo(getString(R.string.app_name), 26f)
        texto(getString(R.string.intro))

        boton(getString(R.string.abrir_buscador)) { BurbujaService.abrirBuscador(this) }

        seccion("Acceso rápido")
        burbuja = interruptor("Burbuja flotante", "Un círculo sobre las demás apps; tócalo para buscar.") { activa ->
            if (activa && !BurbujaService.puede(this)) {
                Toast.makeText(this, "Permite «Mostrar sobre otras apps» y vuelve", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            if (activa && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICACIONES)
            }
            BurbujaService.cambiar(this, activa)
        }
        texto(
            "También puedes buscar:\n" +
                "• Con el botón «Buscar» de los ajustes rápidos (desliza dos veces desde arriba y edita los botones).\n" +
                "• Seleccionando texto en cualquier app → «Buscar con Flow».\n" +
                "• Compartiendo texto con la app.\n" +
                "• Como asistente: Ajustes → Apps → Apps predeterminadas → Asistente (según el móvil).",
        )

        seccion("Qué buscar")
        contactos = interruptor("Contactos", "Buscar por nombre; mantener pulsado copia el número.") { activa ->
            if (activa && Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), PEDIR_CONTACTOS)
            } else if (!activa) {
                Toast.makeText(this, "Quita el permiso en la ficha de la app", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        seccion("Búsquedas web")
        texto(
            "Escribe la palabra clave y lo que buscas: «g gatos», «yt música», «wiki Lima», «map café».\n" +
                "Puedes traer las tuyas de Flow Launcher: el fichero Settings.json de " +
                "%APPDATA%\\FlowLauncher\\Settings\\Plugins\\Flow.Launcher.Plugin.WebSearch\\",
        )
        boton("Importar Settings.json de Flow") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                IMPORTAR,
            )
        }

        seccion("Plugins")
        plugins = texto("Cargando…")
    }

    override fun onResume() {
        super.onResume()
        burbuja.isChecked = BurbujaService.activa(this) && BurbujaService.puede(this)
        // Volver del permiso de superposicion: si se dio, arrancarla.
        if (BurbujaService.activa(this) && BurbujaService.puede(this)) BurbujaService.cambiar(this, true)
        contactos.isChecked = Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        App.fondo.execute {
            App.listo.await(3, TimeUnit.SECONDS)
            val e = JSONObject(Nucleo.estado())
            App.principal.post { pintarPlugins(e) }
        }
    }

    private fun pintarPlugins(e: JSONObject) {
        val a = e.optJSONArray("plugins")
        val sb = StringBuilder()
        if (a == null || a.length() == 0) sb.append("Ninguno.")
        for (n in 0 until (a?.length() ?: 0)) {
            val p = a!!.getJSONObject(n)
            val palabras = p.optJSONArray("palabras")?.let { k -> List(k.length()) { k.getString(it) } } ?: emptyList()
            sb.append(if (p.optBoolean("compatible")) "✅ " else "⚠️ ")
                .append(p.optString("nombre")).append("  ·  ").append(palabras.joinToString(" "))
                .append("\n    ").append(p.optString("descripcion").ifEmpty { p.optString("estado") })
                .append("\n    ").append(p.optString("estado")).append(" · v").append(p.optString("version"))
                .append("\n\n")
        }
        sb.append("Elementos indexados: ").append(e.optInt("elementos"))
        plugins.text = sb.toString().trim()
    }

    override fun onRequestPermissionsResult(codigo: Int, permisos: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(codigo, permisos, r)
        if (codigo == PEDIR_CONTACTOS) {
            val si = r.firstOrNull() == PackageManager.PERMISSION_GRANTED
            contactos.isChecked = si
            if (si) App.fondo.execute { Fuentes.cargarContactos(this) }
        }
    }

    @Deprecated("API de Activity sin AndroidX")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(codigo, resultado, datos)
        val uri = datos?.data
        if (codigo != IMPORTAR || resultado != RESULT_OK || uri == null) return
        App.fondo.execute {
            val texto = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            val r = if (texto == null) JSONObject().put("error", "No se pudo leer el fichero")
            else JSONObject(Nucleo.importarBusquedas(texto.trimStart('﻿')))
            App.principal.post {
                val msg = if (r.has("ok")) "Importadas ${r.optInt("ok")} búsquedas web" else r.optString("error")
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- piezas de la pantalla ---

    private fun titulo(t: String, tam: Float) = TextView(this).apply {
        text = t
        textSize = tam
        setTextColor(colorPrimario())
        col.addView(this)
    }

    private fun seccion(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        isAllCaps = true
        letterSpacing = 0.08f
        setTextColor(if (Build.VERSION.SDK_INT >= 23) getColor(R.color.acento) else resources.getColor(R.color.acento))
        setPadding(0, (24 * d).toInt(), 0, (6 * d).toInt())
        col.addView(this)
    }

    private fun texto(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setLineSpacing(0f, 1.15f)
        setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        col.addView(this)
    }

    private fun boton(t: String, alPulsar: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { alPulsar() }
        col.addView(this)
    }

    private fun interruptor(t: String, sub: String, alCambiar: (Boolean) -> Unit): Switch {
        val s = Switch(this).apply {
            text = t
            textSize = 16f
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        col.addView(s)
        texto(sub).alpha = 0.7f
        // Solo los cambios del usuario, no los de onResume.
        s.setOnClickListener { alCambiar(s.isChecked) }
        return s
    }

    private fun colorPrimario(): Int {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val c = a.getColor(0, 0)
        a.recycle()
        return c
    }
}
