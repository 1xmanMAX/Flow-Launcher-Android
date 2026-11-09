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
        const val PEDIR_ARCHIVOS = 4
    }

    private lateinit var col: LinearLayout
    private lateinit var burbuja: Switch
    private lateinit var contactos: Switch
    private lateinit var archivos: Switch
    private lateinit var plugins: TextView

    private val d by lazy { resources.displayMetrics.density }

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val m = (20 * d).toInt()
            setPadding(m, m, m, m)
        }
        val raiz = ScrollView(this).apply {
            addView(col)
            clipToPadding = false
        }
        setContentView(raiz)
        // Android 15 dibuja siempre bajo las barras: dejarles su sitio.
        Bordes.vigilar(raiz, conTeclado = false)

        titulo(getString(R.string.app_name), 26f)
        texto(getString(R.string.intro))

        boton(getString(R.string.abrir_buscador)) { BurbujaService.abrirBuscador(this) }

        seccion("Acceso rápido")
        burbuja = interruptor("Burbuja flotante", "Un círculo sobre las demás apps; tócalo para buscar.") { activa ->
            if (activa && !BurbujaService.puede(this)) {
                Toast.makeText(this, "Permite «Mostrar sobre otras apps» y vuelve", Toast.LENGTH_LONG).show()
                Acciones.abrir(this, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
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
            if (activa &&
                checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), PEDIR_CONTACTOS)
            } else if (!activa) {
                Toast.makeText(this, "Quita el permiso en la ficha de la app", Toast.LENGTH_LONG).show()
                Acciones.abrir(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        archivos = interruptor(
            "Archivos del móvil",
            "Escribe «c» y el nombre: «c tesis», «c factura pdf». Busca en todo el almacenamiento, " +
                "como Everything; «c » solo enseña lo último modificado. Tocar abre el archivo; " +
                "mantener pulsado copia su ruta.",
        ) { activa ->
            if (activa && !Fuentes.puedeLeerArchivos(this)) {
                pedirArchivos()
            } else if (activa) {
                App.fondo.execute { Fuentes.indexarArchivos(this) }
            } else {
                Toast.makeText(this, "Quita el permiso de archivos para desactivarlo", Toast.LENGTH_LONG).show()
                abrirPermisoArchivos()
            }
        }

        seccion("Palabras clave")
        texto("Lo que se escribe delante para cada cosa («wa ana hola», «p tesis», «c factura»). Toca una para cambiarla.")
        listaPalabras = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listaPalabras)

        seccion("Búsquedas web")
        texto(
            "Escribe la palabra clave y lo que buscas: «g gatos», «yt música», «wiki Lima», «map café».\n" +
                "Puedes traer las tuyas de Flow Launcher: el fichero Settings.json de " +
                "%APPDATA%\\FlowLauncher\\Settings\\Plugins\\Flow.Launcher.Plugin.WebSearch\\",
        )
        boton("Importar Settings.json de Flow") {
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                    IMPORTAR,
                )
            } catch (e: RuntimeException) {
                // Algunos moviles (TV, Go) no traen selector de ficheros.
                Toast.makeText(this, "No hay ninguna app para elegir el fichero", Toast.LENGTH_SHORT).show()
            }
        }

        seccion("Plugins")
        plugins = texto("Cargando…")
    }

    override fun onResume() {
        super.onResume()
        burbuja.isChecked = BurbujaService.activa(this) && BurbujaService.puede(this)
        // Volver del permiso de superposicion: si se dio, arrancarla.
        if (BurbujaService.activa(this) && BurbujaService.puede(this)) BurbujaService.cambiar(this, true)
        contactos.isChecked =
            checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        // Volver de «Acceso a todos los archivos»: si se dio, a indexar.
        archivos.isChecked = Fuentes.puedeLeerArchivos(this)
        App.fondo.execute {
            App.listo.await(3, TimeUnit.SECONDS)
            runCatching { Fuentes.indexarArchivos(this) }
            val e = runCatching { JSONObject(Nucleo.estado()) }.getOrElse { JSONObject() }
            App.principal.post {
                if (!isDestroyed) {
                    pintarPlugins(e)
                    pintarPalabras(e)
                }
            }
        }
    }

    private lateinit var listaPalabras: LinearLayout

    /** Una palabra clave que se puede cambiar. */
    private class Palabra(val clave: String, val nombre: String, val actual: String, val deSiempre: String?)

    /** Comandos, archivos, plugins y apps proveedoras, cada uno con la suya. */
    private fun pintarPalabras(e: JSONObject) {
        val l = ArrayList<Palabra>()
        for ((id, nombre, siempre) in Comandos.lista()) {
            l += Palabra("cmd:$id", nombre, Palabras.de(this, "cmd:$id", siempre), siempre)
        }
        l += Palabra("archivos", "Archivos del móvil", Palabras.de(this, "archivos", "c"), "c")
        for (p in Proveedores.todos().filter { it.palabra != "*" }) {
            l += Palabra("prov:${p.paquete}", p.nombre, p.palabra, p.palabraOriginal)
        }
        val a = e.optJSONArray("plugins")
        for (n in 0 until (a?.length() ?: 0)) {
            val p = a!!.optJSONObject(n) ?: continue
            if (!p.optBoolean("compatible")) continue
            val actual = p.optJSONArray("palabras")?.optString(0)?.takeIf { it.isNotEmpty() && it != "*" } ?: continue
            l += Palabra("plugin:${p.optString("id")}", "Plugin ${p.optString("nombre")}", actual, null)
        }
        listaPalabras.removeAllViews()
        val acento = getColor(R.color.acento)
        for (p in l) {
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val m = (10 * d).toInt()
                setPadding(0, m, 0, m)
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { cambiarPalabra(p, l) }
            }
            fila.addView(TextView(this).apply {
                text = p.nombre
                textSize = 15f
                setTextColor(colorPrimario())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fila.addView(TextView(this).apply {
                text = p.actual
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(acento)
            })
            listaPalabras.addView(fila)
        }
    }

    private fun cambiarPalabra(p: Palabra, todas: List<Palabra>) {
        val campo = android.widget.EditText(this).apply {
            setText(p.actual)
            setSelection(p.actual.length)
            isSingleLine = true
            hint = p.deSiempre ?: p.actual
        }
        val marco = android.widget.FrameLayout(this).apply {
            val m = (20 * d).toInt()
            setPadding(m, (8 * d).toInt(), m, 0)
            addView(campo)
        }
        val dialogo = android.app.AlertDialog.Builder(this)
            .setTitle(p.nombre)
            .setMessage("Palabra clave" + (p.deSiempre?.let { " (de siempre: «$it»)" } ?: ""))
            .setView(marco)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .apply { if (p.deSiempre != null || p.clave.startsWith("plugin:")) setNeutralButton("La de siempre", null) }
            .create()
        dialogo.setOnShowListener {
            dialogo.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nueva = campo.text.toString().trim()
                val error = Palabras.error(nueva) ?: when {
                    nueva.isEmpty() -> "Escribe una palabra"
                    else -> todas.firstOrNull { it.clave != p.clave && it.actual.equals(nueva, ignoreCase = true) }
                        ?.let { "Ya la usa «${it.nombre}»" }
                }
                if (error != null) {
                    campo.error = error
                    return@setOnClickListener
                }
                guardarPalabra(p, nueva)
                dialogo.dismiss()
            }
            dialogo.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                guardarPalabra(p, "")
                dialogo.dismiss()
            }
        }
        dialogo.show()
    }

    /** Guarda (vacia: la de siempre), avisa al nucleo y repinta la lista. */
    private fun guardarPalabra(p: Palabra, nueva: String) {
        App.fondo.execute {
            Palabras.cambiar(this, p.clave, if (nueva == p.deSiempre) "" else nueva)
            // Una app proveedora se relee en el hilo de fondo: despues, repintar.
            if (p.clave.startsWith("prov:")) Proveedores.cargar(this)
            val e = runCatching { JSONObject(Nucleo.estado()) }.getOrElse { JSONObject() }
            App.principal.post {
                if (isDestroyed) return@post
                pintarPalabras(e)
                pintarPlugins(e)
                Toast.makeText(this, if (nueva.isEmpty()) "Vuelve la de siempre" else "Ahora: «$nueva …»", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pintarPlugins(e: JSONObject) {
        val a = e.optJSONArray("plugins")
        val sb = StringBuilder()
        if (a == null || a.length() == 0) sb.append("Ninguno.")
        for (n in 0 until (a?.length() ?: 0)) {
            val p = a!!.optJSONObject(n) ?: continue
            val palabras = p.optJSONArray("palabras")?.let { k -> List(k.length()) { k.getString(it) } } ?: emptyList()
            sb.append(if (p.optBoolean("compatible")) "✅ " else "⚠️ ")
                .append(p.optString("nombre")).append("  ·  ").append(palabras.joinToString(" "))
                .append("\n    ").append(p.optString("descripcion").ifEmpty { p.optString("estado") })
                .append("\n    ").append(p.optString("estado")).append(" · v").append(p.optString("version"))
                .append("\n\n")
        }
        sb.append("Elementos indexados: ").append(e.optInt("elementos"))
        if (e.optInt("archivos") > 0 || e.optBoolean("indexando")) {
            sb.append("\nArchivos indexados: ").append(e.optInt("archivos"))
            if (e.optBoolean("indexando")) sb.append(" (indexando…)")
        }
        plugins.text = sb.toString().trim()
    }

    override fun onRequestPermissionsResult(codigo: Int, permisos: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(codigo, permisos, r)
        if (codigo == PEDIR_CONTACTOS) {
            val si = r.firstOrNull() == PackageManager.PERMISSION_GRANTED
            contactos.isChecked = si
            if (si) App.fondo.execute { Fuentes.cargarContactos(this) }
        }
        if (codigo == PEDIR_ARCHIVOS) {
            val si = r.firstOrNull() == PackageManager.PERMISSION_GRANTED
            archivos.isChecked = si
            if (si) App.fondo.execute { Fuentes.indexarArchivos(this) }
        }
    }

    /** Android 11+: el permiso especial, en su pantalla; antes, el normal. */
    private fun pedirArchivos() {
        if (Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, "Permite «Acceso a todos los archivos» y vuelve", Toast.LENGTH_LONG).show()
            abrirPermisoArchivos()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PEDIR_ARCHIVOS)
        }
    }

    private fun abrirPermisoArchivos() {
        if (Build.VERSION.SDK_INT >= 30) {
            val propia = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            // Algunos moviles no tienen la pantalla de una sola app: la lista.
            if (runCatching { startActivity(propia) }.isSuccess) return
            if (Acciones.abrir(this, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))) return
        }
        Acciones.abrir(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
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
            else runCatching { JSONObject(Nucleo.importarBusquedas(texto.trimStart('\uFEFF'))) }
                .getOrElse { JSONObject().put("error", "No se pudo importar") }
            App.principal.post {
                val msg = if (r.has("ok")) "Importadas ${r.optInt("ok")} búsquedas web" else r.optString("error")
                Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
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
        setTextColor(getColor(R.color.acento))
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
