package com.max.flowlauncher.prueba

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/** Un proveedor falso: dos archivos, una tarea y una accion `call:`. */
class Proveedor : ContentProvider() {
    private val columnas = arrayOf("titulo", "subtitulo", "icono", "puntos", "accion", "extras", "copiar", "menu")
    private var hecha = false

    override fun onCreate() = true

    override fun query(uri: Uri, p: Array<out String>?, s: String?, args: Array<out String>?, o: String?): Cursor {
        val c = MatrixCursor(columnas)
        val q = args?.firstOrNull().orEmpty().trim()
        when (uri.lastPathSegment) {
            "menu" -> c.addRow(arrayOf<Any>("Copiar la ruta de $q", "Menú", "", 0, "call:copiar", "{\"que\":\"$q\"}", "", ""))
            else -> {
                if (q.startsWith("t ")) {
                    val t = q.removePrefix("t ").trim()
                    c.addRow(arrayOf<Any>("Apuntar «$t» en Inbox", "Tarea nueva", "", 1000, "call:anadir_tarea", "{\"texto\":\"$t\"}", "", ""))
                } else if ("tesis".startsWith(q.lowercase()) || q.lowercase().startsWith("tes")) {
                    c.addRow(arrayOf<Any>("Tesis final.pdf", "Tesis · 12 oct", "ext:pdf", 900, "https://example.com/tesis", "", "Tesis final.pdf", "tesis-pdf"))
                    c.addRow(arrayOf<Any>("Tesis borrador.docx", "Tesis · 3 oct", "ext:docx", 800, "https://example.com/borrador", "", "", ""))
                    c.addRow(arrayOf<Any>(if (hecha) "☑ Revisar tesis" else "☐ Revisar tesis", "Inbox", "", 700, "call:marcar_tarea", "{\"indice\":0}", "", ""))
                }
            }
        }
        return c
    }

    override fun call(metodo: String, arg: String?, extras: Bundle?): Bundle = Bundle().apply {
        when (metodo) {
            "anadir_tarea" -> putString("mensaje", "Tarea apuntada: ${extras?.getString("texto")}")
            "marcar_tarea" -> {
                hecha = !hecha
                putString("mensaje", if (hecha) "Hecha" else "Pendiente")
                putString("consulta", "tesis")
            }
            else -> putString("error", "No sé hacer $metodo")
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
