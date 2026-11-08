package com.max.flowlauncher

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import org.json.JSONArray
import java.io.File

/** Hacer lo que dice un resultado (ver `accion` en `resultado.rs`). */
object Acciones {

    /** Lo que de verdad hace la linterna (la puede tocar otra app o el sistema). */
    @Volatile
    private var linterna = false
    private var idLinterna: String? = null
    private var vigia: CameraManager.TorchCallback? = null

    /**
     * Devuelve si hay que cerrar el buscador. Las de plugin no pasan por
     * aqui: van a otro hilo ([BuscarActivity]).
     */
    fun ejecutar(c: Context, accion: String, alCambiarConsulta: (String) -> Unit): Boolean {
        val (tipo, resto) = accion.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (tipo) {
            "app" -> {
                val partes = resto.split('/', limit = 2)
                if (partes.size < 2) return false
                val (paquete, actividad) = partes
                abrir(
                    c,
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(ComponentName(paquete, actividad))
                        .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
                )
            }
            "url" -> abrir(c, Intent(Intent.ACTION_VIEW, Uri.parse(resto)))
            "intent" -> abrir(c, runCatching { Intent.parseUri(resto, Intent.URI_INTENT_SCHEME) }.getOrNull())
            "copiar" -> {
                copiar(c, resto)
                true
            }
            "consulta" -> {
                alCambiarConsulta(resto)
                false
            }
            "interno" -> interno(c, resto)
            "archivo" -> archivo(c, resto)
            "compartir" -> compartir(c, resto)
            "pindesk" -> enviarAPinDesk(c, resto)
            "abrircon" -> archivo(c, resto, elegir = true)
            else -> false
        }
    }

    /**
     * Un archivo del movil (`c tesis`): con la app que le toque, por
     * [ProveedorDeArchivos]. Las carpetas llegan acabadas en `/` (sin mirar
     * el disco en este hilo).
     */
    private fun archivo(c: Context, ruta: String, elegir: Boolean = false): Boolean {
        if (ruta.endsWith("/")) return carpeta(c, ruta.trimEnd('/'))
        val f = File(ruta)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ProveedorDeArchivos.uri(c, f), ProveedorDeArchivos.tipo(f.name) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // «Abrir con…»: siempre la lista de apps, aunque haya una por defecto.
        return abrir(c, if (elegir) Intent.createChooser(i, "Abrir «${f.name}» con") else i)
    }

    /** PinDesk (PixPin) instalado: su entrada para recibir archivos. */
    const val PINDESK = "com.forge.pixpin"
    private const val PINDESK_RECIBIR = "com.forge.pixpin.guardados.AbrirConPixPinActivity"

    fun hayPinDesk(c: Context) = runCatching { c.packageManager.getPackageInfo(PINDESK, 0); true }.getOrDefault(false)

    /**
     * Mandar un archivo a PinDesk, como «Compartir → PinDesk» pero sin
     * buscarlo en la lista: lo conocido se abre de paso y lo demas da su menu
     * (`AbrirConPixPinActivity`).
     */
    private fun enviarAPinDesk(c: Context, ruta: String): Boolean {
        val f = File(ruta)
        if (!f.isFile) return false
        val uri = ProveedorDeArchivos.uri(c, f)
        val i = Intent(Intent.ACTION_SEND)
            .setClassName(PINDESK, PINDESK_RECIBIR)
            .setType(ProveedorDeArchivos.tipo(f.name) ?: "application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = android.content.ClipData.newUri(c.contentResolver, f.name, uri)
        return abrir(c, i)
    }

    /**
     * Compartir un archivo con cualquier app (WhatsApp, Gmail, Drive...). Va
     * un `content://` con permiso de lectura solo para la app elegida: no se
     * copia nada y nadie mas puede leerlo.
     */
    private fun compartir(c: Context, ruta: String): Boolean {
        val f = File(ruta)
        if (!f.isFile) {
            Toast.makeText(c, "Ya no está: ${f.name}", Toast.LENGTH_SHORT).show()
            return false
        }
        val uri = ProveedorDeArchivos.uri(c, f)
        val enviar = Intent(Intent.ACTION_SEND)
            .setType(ProveedorDeArchivos.tipo(f.name) ?: "application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, f.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Con ClipData el permiso llega tambien a la app que se elija en la lista.
        enviar.clipData = android.content.ClipData.newUri(c.contentResolver, f.name, uri)
        return abrir(c, Intent.createChooser(enviar, "Compartir «${f.name}»"))
    }

    /**
     * Una carpeta, en el gestor de archivos del sistema si se deja (no todos
     * los moviles lo abren por fuera); si no, se copia la ruta.
     */
    private fun carpeta(c: Context, ruta: String): Boolean {
        val raiz = Environment.getExternalStorageDirectory().path
        if (ruta.startsWith("$raiz/")) {
            val doc = "primary:" + ruta.removePrefix("$raiz/")
            val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", doc)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                c.startActivity(i)
                return true
            } catch (e: RuntimeException) {
                // ActivityNotFoundException, SecurityException...: a copiar.
            }
        }
        copiar(c, ruta)
        if (Build.VERSION.SDK_INT >= 33) {
            Toast.makeText(c, "No se puede abrir la carpeta: ruta copiada", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    fun abrir(c: Context, i: Intent?): Boolean {
        if (i == null) return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            c.startActivity(i)
            true
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(c, "No hay ninguna app para abrirlo", Toast.LENGTH_SHORT).show()
            false
        } catch (e: RuntimeException) {
            // SecurityException, FileUriExposedException...
            Toast.makeText(c, "Android no deja abrirlo", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun copiar(c: Context, texto: String) {
        val cm = c.getSystemService(ClipboardManager::class.java) ?: return
        // Un texto enorme no cabe por Binder (TransactionTooLargeException).
        if (runCatching { cm.setPrimaryClip(ClipData.newPlainText("Flow", texto)) }.isFailure) return
        // Android 13+ ya ensena su propio aviso al copiar.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(c, "Copiado: ${texto.take(60)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun interno(c: Context, que: String): Boolean {
        if (que == "linterna") {
            // CameraAccessException no es RuntimeException: todo dentro.
            runCatching {
                val cm = c.applicationContext.getSystemService(CameraManager::class.java) ?: return true
                val id = idLinterna ?: cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }?.also { idLinterna = it } ?: return true
                if (vigia == null) {
                    // Una sola vez: avisa de cada cambio (tambien los de fuera).
                    val v = object : CameraManager.TorchCallback() {
                        override fun onTorchModeChanged(camara: String, encendida: Boolean) {
                            if (camara == idLinterna) linterna = encendida
                        }

                        override fun onTorchModeUnavailable(camara: String) {
                            if (camara == idLinterna) linterna = false
                        }
                    }
                    cm.registerTorchCallback(v, App.principal)
                    vigia = v
                }
                val nueva = !linterna
                cm.setTorchMode(id, nueva)
                linterna = nueva
            }.onFailure {
                Toast.makeText(c, "No se pudo usar la linterna", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    /**
     * Lo que pide un plugin (los metodos de la API de Flow). Devuelve si hay
     * que cerrar el buscador.
     */
    fun eventos(c: Context, eventos: JSONArray?, alCambiarConsulta: (String) -> Unit): Boolean {
        if (eventos == null) return false
        var cerrar = false
        for (n in 0 until eventos.length()) {
            val e = eventos.optJSONObject(n) ?: continue
            val p = e.optJSONArray("params") ?: JSONArray()
            when (e.optString("metodo")) {
                "ChangeQuery" -> alCambiarConsulta(p.optString(0))
                "ShowMsg", "ShowMsgError" -> {
                    val t = listOf(p.optString(0), p.optString(1)).filter { it.isNotEmpty() }.joinToString(": ")
                    Toast.makeText(c, t, Toast.LENGTH_LONG).show()
                }
                "CopyToClipboard" -> copiar(c, p.optString(0))
                "OpenUrl", "OpenWebUrl", "OpenUri", "OpenAppUri" ->
                    cerrar = abrir(c, Intent(Intent.ACTION_VIEW, Uri.parse(p.optString(0)))) || cerrar
                "OpenDirectory" -> {
                    val ruta = p.optString(1).ifEmpty { p.optString(0) }
                    cerrar = abrir(c, Intent(Intent.ACTION_VIEW, Uri.parse("file://$ruta"))) || cerrar
                }
                "HideMainWindow" -> cerrar = true
            }
        }
        return cerrar
    }
}
