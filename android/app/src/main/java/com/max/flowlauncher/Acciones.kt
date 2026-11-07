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
import android.widget.Toast
import org.json.JSONArray

/** Hacer lo que dice un resultado (ver `accion` en `resultado.rs`). */
object Acciones {

    private var linterna = false

    /**
     * Devuelve si hay que cerrar el buscador. Las de plugin no pasan por
     * aqui: van a otro hilo ([BuscarActivity]).
     */
    fun ejecutar(c: Context, accion: String, alCambiarConsulta: (String) -> Unit): Boolean {
        val (tipo, resto) = accion.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (tipo) {
            "app" -> {
                val (paquete, actividad) = resto.split('/', limit = 2)
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
            else -> false
        }
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
        cm.setPrimaryClip(ClipData.newPlainText("Flow", texto))
        // Android 13+ ya ensena su propio aviso al copiar.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            Toast.makeText(c, "Copiado: ${texto.take(60)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun interno(c: Context, que: String): Boolean {
        if (que == "linterna") {
            val cm = c.getSystemService(CameraManager::class.java) ?: return true
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return true
            linterna = !linterna
            runCatching { cm.setTorchMode(id, linterna) }
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
