package com.max.flowlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * La ventana de busqueda. Cada tecla:
 * 1. lo local sale del nucleo al instante, en este mismo hilo;
 * 2. si hay plugins, se les pregunta en otro hilo y lo suyo se junta al
 *    llegar (si mientras tanto no se ha seguido escribiendo).
 */
class BuscarActivity : Activity() {

    private lateinit var consulta: EditText
    private lateinit var lista: ListView
    private lateinit var cargando: ProgressBar
    private lateinit var separador: View
    private val adaptador = Adaptador()

    /** Sube con cada tecla: lo que llega tarde de los plugins se descarta. */
    @Volatile
    private var turno = 0
    private var locales: List<Resultado> = emptyList()

    private val hiloPlugins = Executors.newSingleThreadExecutor { r -> Thread(r, "flow-plugins") }

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        setContentView(R.layout.buscar)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
        lista.setOnItemClickListener { _, _, pos, _ -> elegir(adaptador.items[pos]) }
        lista.setOnItemLongClickListener { _, _, pos, _ ->
            pulsacionLarga(adaptador.items[pos])
            true
        }

        // El nucleo arranca con el proceso; casi siempre ya esta listo.
        if (App.listo.count > 0L) {
            Thread {
                App.listo.await(3, TimeUnit.SECONDS)
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
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        consulta.post {
            getSystemService(InputMethodManager::class.java)?.showSoftInput(consulta, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onPause() {
        super.onPause()
        // Fuera de la vista no se queda abierta: la proxima vez, en blanco.
        if (!isChangingConfigurations) finish()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        hiloPlugins.shutdownNow()
    }

    private fun recibirIntent(i: Intent?) {
        val texto = when (i?.action) {
            Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            else -> i?.getStringExtra("consulta")
        } ?: ""
        cambiarConsulta(texto.trim())
    }

    private fun cambiarConsulta(t: String) {
        consulta.setText(t)
        consulta.setSelection(t.length)
        // setText ya llama a buscar() por el TextWatcher.
        if (t.isEmpty()) buscar("")
    }

    private fun buscar(q: String) {
        val mio = ++turno
        val r = JSONObject(Nucleo.consultar(q, 20))
        locales = Resultado.lista(r.optJSONArray("r"))
        mostrar(locales)
        if (!r.optBoolean("p")) {
            cargando.visibility = View.INVISIBLE
            return
        }
        cargando.visibility = View.VISIBLE
        hiloPlugins.execute {
            // Si ya se escribio otra tecla, esta consulta no le importa a nadie.
            if (mio != turno) return@execute
            val de = Resultado.lista(JSONArray(Nucleo.consultarPlugins(q, 20, 1500)))
            val ev = JSONArray(Nucleo.eventos())
            App.principal.post {
                if (mio != turno) return@post
                cargando.visibility = View.INVISIBLE
                if (ev.length() > 0 && Acciones.eventos(this, ev, ::cambiarConsulta)) finish()
                mostrar((locales + de).sortedByDescending { it.puntos }.take(30))
            }
        }
    }

    private fun mostrar(r: List<Resultado>) {
        adaptador.items = r
        adaptador.consultaActual = consulta.text.toString()
        adaptador.notifyDataSetChanged()
        separador.visibility = if (r.isEmpty()) View.GONE else View.VISIBLE
        if (r.isNotEmpty()) lista.setSelection(0)
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
                val res = JSONObject(Nucleo.ejecutar(id, accion))
                App.principal.post {
                    cargando.visibility = View.INVISIBLE
                    val pideCerrar = Acciones.eventos(this, res.optJSONArray("eventos"), ::cambiarConsulta)
                    if (res.optBoolean("cerrar", true) || pideCerrar) finish()
                }
            }
            return
        }
        if (Acciones.ejecutar(this, r.accion, ::cambiarConsulta)) finish()
    }

    /** Menu del plugin, copiar, o la ficha de la app. */
    private fun pulsacionLarga(r: Resultado) {
        val id = r.pluginId
        val cx = r.contexto
        if (id != null && cx != null) {
            cargando.visibility = View.VISIBLE
            val mio = ++turno
            hiloPlugins.execute {
                val menu = Resultado.lista(JSONArray(Nucleo.menu(id, cx)))
                App.principal.post {
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
            if (Build.VERSION.SDK_INT >= 23) getColor(R.color.acento) else resources.getColor(R.color.acento)
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
