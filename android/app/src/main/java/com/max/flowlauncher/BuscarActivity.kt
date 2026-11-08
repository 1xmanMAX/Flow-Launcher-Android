package com.max.flowlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * La ventana de busqueda. Cada tecla:
 * 1. lo local sale del nucleo al instante, en este mismo hilo;
 * 2. si hay plugins, se les pregunta en otro hilo y lo suyo se junta al
 *    llegar (si mientras tanto no se ha seguido escribiendo).
 */
class BuscarActivity : Activity() {

    private companion object {
        /** Cuanto se espera a los plugins antes de ensenar solo lo local. */
        const val ESPERA_ANTES_DE_LO_LOCAL = 120L
        /** Cuanto tienen que tardar para que salga el indicador de carga. */
        const val ESPERA_ANTES_DE_CARGANDO = 300L
        /** Lo mas largo que se acepta de otra app como consulta. */
        const val MAX_TEXTO = 500

        /** La ultima busqueda (ver [guardarUltima]). */
        @Volatile
        var ultima: String? = null
    }

    private lateinit var consulta: EditText
    private lateinit var lista: ListView
    private lateinit var cargando: ProgressBar
    private lateinit var separador: View
    private val adaptador = Adaptador()

    /** Sube con cada tecla: lo que llega tarde de los plugins se descarta. */
    @Volatile
    private var turno = 0
    private var locales: List<Resultado> = emptyList()

    /** Tras cerrarse, lo que se le mande se ignora (nada de RejectedExecutionException). */
    private val hiloPlugins = App.hilos("flow-plugins")

    /** Lo que llega tarde (plugins, el nucleo al arrancar) no toca una ventana ya cerrada. */
    private val cerrada get() = isDestroyed || isFinishing

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        setContentView(R.layout.buscar)
        // Que las apps proveedoras ya esten despiertas para la primera tecla.
        App.fondo.execute { Proveedores.calentar(applicationContext) }
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // adjustResize no sirve de borde a borde (Android 15): el teclado se
        // resta como relleno y el panel, con la lista, cabe en lo que queda.
        Bordes.deBordeABorde(window)
        Bordes.vigilar(findViewById(R.id.fondo), conTeclado = true)
        consulta = findViewById(R.id.consulta)
        lista = findViewById(R.id.lista)
        cargando = findViewById(R.id.cargando)
        separador = findViewById(R.id.separador)
        lista.adapter = adaptador

        findViewById<View>(R.id.fondo).setOnClickListener { finish() }
        findViewById<View>(R.id.panel).setOnClickListener { /* no cerrar */ }

        consulta.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = buscar(s?.toString() ?: "")
        })
        consulta.setOnEditorActionListener { _, id, ev ->
            val enter = id == EditorInfo.IME_ACTION_GO ||
                (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
            if (enter) adaptador.items.firstOrNull()?.let { elegir(it) }
            enter
        }
        lista.setOnItemClickListener { _, _, pos, _ -> adaptador.items.getOrNull(pos)?.let { elegir(it) } }
        lista.setOnItemLongClickListener { _, _, pos, _ ->
            adaptador.items.getOrNull(pos)?.let { pulsacionLarga(it) }
            true
        }

        // El nucleo arranca con el proceso; casi siempre ya esta listo.
        if (App.listo.count > 0L) {
            Thread {
                runCatching { App.listo.await(3, TimeUnit.SECONDS) }
                App.principal.post { recibirIntent(intent) }
            }.start()
        } else {
            recibirIntent(intent)
        }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        recibirIntent(i)
    }

    override fun onResume() {
        super.onResume()
        consulta.requestFocus()
        // Sin perder adjustResize (lo que aun usa Android 7–10).
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        consulta.post {
            getSystemService(InputMethodManager::class.java)?.showSoftInput(consulta, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onPause() {
        super.onPause()
        // Lo escrito se recuerda para la proxima vez (como Flow).
        guardarUltima(consulta.text.toString())
        // Fuera de la vista no se queda abierta.
        if (!isChangingConfigurations) finish()
    }

    /** La ultima busqueda: en memoria y en disco (por si el proceso muere). */
    private fun guardarUltima(t: String) {
        if (t == ultima) return
        ultima = t
        runCatching { getSharedPreferences("buscar", MODE_PRIVATE).edit().putString("ultima", t).apply() }
    }

    private fun leerUltima(): String = ultima
        ?: runCatching { getSharedPreferences("buscar", MODE_PRIVATE).getString("ultima", "") }.getOrNull().orEmpty()
            .also { ultima = it }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        hiloPlugins.shutdownNow()
    }

    private fun recibirIntent(i: Intent?) {
        if (cerrada) return
        // Los extras de otra app pueden venir rotos (BadParcelableException...).
        val texto = runCatching {
            when (i?.action) {
                Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
                else -> i?.getStringExtra("consulta")
            }
        }.getOrNull() ?: ""
        val nuevo = texto.trim().take(MAX_TEXTO)
        if (nuevo.isNotEmpty()) {
            // Un texto enorme compartido no es una busqueda: solo el principio.
            cambiarConsulta(nuevo)
            return
        }
        // Abrir sin nada: la ultima busqueda, con sus resultados y todo
        // seleccionado. Escribir la sustituye; tocar el texto la edita.
        val antes = leerUltima()
        cambiarConsulta(antes)
        if (antes.isNotEmpty()) consulta.selectAll()
    }

    private fun cambiarConsulta(t: String) {
        if (cerrada) return
        consulta.setText(t)
        consulta.setSelection(t.length)
        // setText ya llama a buscar() por el TextWatcher.
        if (t.isEmpty()) buscar("")
    }

    /** Lo que se ensena si lo de los plugins tarda (ver [buscar]). */
    private var mostrarLocales: Runnable? = null
    private var verCargando: Runnable? = null

    private fun buscar(q: String) {
        if (cerrada) return
        val mio = ++turno
        // Si el nucleo falla (no cargo, JSON raro), sin resultados pero sin cerrarse.
        val r = runCatching { JSONObject(Nucleo.consultar(q, 20)) }.getOrElse { JSONObject() }
        locales = Resultado.lista(r.optJSONArray("r"))
        mostrarLocales?.let { App.principal.removeCallbacks(it) }
        verCargando?.let { App.principal.removeCallbacks(it) }
        // `wa ana hola`, `timer 10m`...: un comando manda solo, y al instante.
        Comandos.consulta(this, q)?.let { cmd ->
            cargando.visibility = View.INVISIBLE
            mostrar(cmd)
            return
        }
        Comandos.sugerencias(this, q).takeIf { it.isNotEmpty() }?.let { s ->
            locales = (locales + s).sortedByDescending { it.puntos }
        }
        val proveedores = Proveedores.destinos(q).takeIf { it.isNotEmpty() }
        // `p tesis`: la palabra clave es de la app; lo local no pinta nada.
        if (proveedores?.any { !it.global } == true) locales = emptyList()
        if (!r.optBoolean("p") && proveedores == null) {
            cargando.visibility = View.INVISIBLE
            mostrar(locales)
            return
        }
        // Sin parpadeo: lo de antes se queda en pantalla y lo nuevo sale de
        // una vez, ya junto con lo de los plugins (casi siempre en unos ms).
        // Solo si tardan se ensena antes lo local, y el indicador de carga
        // solo si tardan de verdad.
        mostrarLocales = Runnable { if (mio == turno && !cerrada) mostrar(locales) }.also {
            App.principal.postDelayed(it, ESPERA_ANTES_DE_LO_LOCAL)
        }
        verCargando = Runnable { if (mio == turno && !cerrada) cargando.visibility = View.VISIBLE }.also {
            App.principal.postDelayed(it, ESPERA_ANTES_DE_CARGANDO)
        }
        val locales = locales
        val hayPlugins = r.optBoolean("p")
        hiloPlugins.execute {
            // Si ya se escribio otra tecla, esta consulta no le importa a nadie.
            if (mio != turno) return@execute
            // Las apps proveedoras (PixPin...) en paralelo con los plugins.
            val deApps = proveedores?.let { d -> Proveedores.buscarAparte(applicationContext, d) }
            val dePlugins = if (!hayPlugins) emptyList() else runCatching {
                Resultado.lista(JSONArray(Nucleo.consultarPlugins(q, 20, 1500)))
            }.getOrElse { emptyList() }
            val de = dePlugins + (runCatching { deApps?.get() }.getOrNull() ?: emptyList())
            val ev = runCatching { JSONArray(Nucleo.eventos()) }.getOrElse { JSONArray() }
            App.principal.post {
                if (cerrada || mio != turno) return@post
                mostrarLocales?.let { App.principal.removeCallbacks(it) }
                verCargando?.let { App.principal.removeCallbacks(it) }
                cargando.visibility = View.INVISIBLE
                if (ev.length() > 0 && Acciones.eventos(this, ev, ::cambiarConsulta)) finish()
                mostrar((locales + de).sortedByDescending { it.puntos }.take(30))
            }
        }
    }

    private fun mostrar(r: List<Resultado>) {
        // Lo mismo que ya se ve: no se repinta nada.
        val igual = r.size == adaptador.items.size && r.indices.all { i ->
            val a = r[i]
            val b = adaptador.items[i]
            a.titulo == b.titulo && a.subtitulo == b.subtitulo && a.icono == b.icono && a.resaltado.contentEquals(b.resaltado)
        }
        adaptador.items = r
        adaptador.consultaActual = consulta.text.toString()
        if (igual) return
        adaptador.notifyDataSetChanged()
        val sep = if (r.isEmpty()) View.GONE else View.VISIBLE
        if (separador.visibility != sep) separador.visibility = sep
        if (r.isNotEmpty() && lista.firstVisiblePosition != 0) lista.setSelection(0)
    }

    private fun elegir(r: Resultado) {
        val q = consulta.text.toString()
        App.fondo.execute { Nucleo.registrarUso(q, r.claveHistorial) }
        val id = r.pluginId
        if (r.accion.startsWith("plugin:") && id != null) {
            val accion = r.accionPlugin ?: run {
                // Sin accion: como en Flow, autocompletar si se puede.
                r.autocompletar?.let { cambiarConsulta(it) }
                return
            }
            cargando.visibility = View.VISIBLE
            hiloPlugins.execute {
                val res = runCatching { JSONObject(Nucleo.ejecutar(id, accion)) }.getOrElse { JSONObject() }
                App.principal.post {
                    if (cerrada) return@post
                    cargando.visibility = View.INVISIBLE
                    val pideCerrar = Acciones.eventos(this, res.optJSONArray("eventos"), ::cambiarConsulta)
                    if (res.optBoolean("cerrar", true) || pideCerrar) finish()
                }
            }
            return
        }
        // Una accion de una app proveedora (apuntar una tarea en PixPin...):
        // la hace ella, sin abrirse.
        if (r.accion.startsWith(Proveedores.LLAMAR)) {
            val mio = turno
            hiloPlugins.execute {
                val res = Proveedores.llamar(applicationContext, r)
                App.principal.post {
                    if (cerrada) return@post
                    (res.error ?: res.mensaje)?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_SHORT).show() }
                    res.abrir?.let { Acciones.ejecutar(this, "url:$it", ::cambiarConsulta) }
                    when {
                        res.consulta != null && mio == turno -> cambiarConsulta(res.consulta)
                        res.cerrar -> finish()
                    }
                }
            }
            return
        }
        if (Acciones.ejecutar(this, r.accion, ::cambiarConsulta)) finish()
    }

    /** Menu del plugin, copiar, o la ficha de la app. */
    private fun pulsacionLarga(r: Resultado) {
        // Un archivo del movil: compartir, abrir con, su carpeta, copiar.
        if (r.accion.startsWith("archivo:")) {
            ++turno
            mostrar(MenuDeArchivo.de(r.accion.removePrefix("archivo:"), Acciones.hayPinDesk(this)))
            return
        }
        // El menu de una fila de una app proveedora.
        if (r.fuente.startsWith("proveedor:") && r.contexto != null) {
            val mio = ++turno
            hiloPlugins.execute {
                val menu = Proveedores.menu(applicationContext, r)
                App.principal.post {
                    if (!cerrada && mio == turno && menu.isNotEmpty()) mostrar(menu)
                }
            }
            return
        }
        val id = r.pluginId
        val cx = r.contexto
        if (id != null && cx != null) {
            cargando.visibility = View.VISIBLE
            val mio = ++turno
            hiloPlugins.execute {
                val menu = runCatching { Resultado.lista(JSONArray(Nucleo.menu(id, cx))) }.getOrElse { emptyList() }
                App.principal.post {
                    if (cerrada) return@post
                    cargando.visibility = View.INVISIBLE
                    if (mio == turno && menu.isNotEmpty()) mostrar(menu)
                }
            }
            return
        }
        when {
            r.copiar != null -> Acciones.copiar(this, r.copiar)
            r.fuente == "contactos" && r.subtitulo.isNotEmpty() -> Acciones.copiar(this, r.subtitulo)
            r.accion.startsWith("app:") -> {
                val paquete = r.accion.substring(4).substringBefore('/')
                Acciones.abrir(this, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$paquete")))
                finish()
            }
            else -> Acciones.copiar(this, r.titulo)
        }
    }

    private inner class Adaptador : BaseAdapter() {
        var items: List<Resultado> = emptyList()
        var consultaActual = ""
        private val acento by lazy {
            getColor(R.color.acento)
        }

        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, reciclada: View?, padre: ViewGroup): View {
            val v = reciclada ?: LayoutInflater.from(padre.context).inflate(R.layout.fila, padre, false).also {
                it.tag = Fila(it.findViewById(R.id.icono), it.findViewById(R.id.titulo), it.findViewById(R.id.subtitulo))
            }
            val f = v.tag as Fila
            val r = items[p]
            f.titulo.text = resaltar(r)
            val sub = r.subtitulo.ifEmpty { if (r.fuente == "apps") "Aplicación" else "" }
            f.subtitulo.text = sub
            f.subtitulo.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
            Iconos.poner(f.icono, r)
            return v
        }

        private fun resaltar(r: Resultado): CharSequence {
            if (r.resaltado.isEmpty()) return r.titulo
            val s = SpannableString(r.titulo)
            // Las posiciones son de caracteres (Unicode), no de UTF-16.
            for (pos in r.resaltado) {
                if (pos >= r.titulo.codePointCount(0, r.titulo.length)) continue
                val i = r.titulo.offsetByCodePoints(0, pos)
                val fin = r.titulo.offsetByCodePoints(i, 1)
                s.setSpan(ForegroundColorSpan(acento), i, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(StyleSpan(Typeface.BOLD), i, fin, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return s
        }
    }

    private class Fila(val icono: ImageView, val titulo: TextView, val subtitulo: TextView)
}
