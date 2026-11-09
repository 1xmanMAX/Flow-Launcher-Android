package com.max.flowlauncher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Pasa un archivo del movil a otra app (`content://`: desde Android 7 no se
 * puede con `file://`). Como el FileProvider de AndroidX, pero en unas
 * lineas y sin dependencias: solo lectura y solo dentro del almacenamiento
 * (nada de los datos privados de la app). No esta exportado: la otra app
 * solo lee la URI concreta que se le da con `FLAG_GRANT_READ_URI_PERMISSION`.
 */
class ProveedorDeArchivos : ContentProvider() {

    companion object {
        fun autoridad(c: Context) = "${c.packageName}.archivos"

        /** `content://<paquete>.archivos/storage/emulated/0/Download/x.pdf`. */
        fun uri(c: Context, f: File): Uri =
            Uri.Builder().scheme("content").authority(autoridad(c)).path(f.absolutePath).build()

        /** `application/pdf` de `x.PDF`; null si no se sabe. */
        const val APK = "application/vnd.android.package-archive"

        fun tipo(nombre: String): String? {
            val ext = nombre.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return null
            // Algunos moviles no lo tienen en su tabla, y sin el tipo exacto no sale el instalador.
            if (ext == "apk") return APK
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }

        /** El archivo de la URI, si es uno de verdad dentro del almacenamiento. */
        private fun archivo(uri: Uri): File? {
            val ruta = uri.path ?: return null
            val raiz = runCatching { Environment.getExternalStorageDirectory().canonicalPath }.getOrNull()
                ?: return null
            // La ruta real (sin `..` ni enlaces) tiene que quedar dentro.
            val f = runCatching { File(ruta).canonicalFile }.getOrNull() ?: return null
            // Y fuera de `Android/data` y `Android/obb` (ahi estan los de esta app).
            val privado = f.path.startsWith("$raiz/Android/data/") || f.path.startsWith("$raiz/Android/obb/")
            return if (f.path.startsWith("$raiz/") && !privado && f.isFile) f else null
        }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, modo: String): ParcelFileDescriptor {
        if (modo != "r") throw SecurityException("Solo lectura")
        val f = archivo(uri) ?: throw FileNotFoundException(uri.path)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String =
        tipo(uri.lastPathSegment ?: "") ?: "application/octet-stream"

    /** Nombre y tamano: lo que preguntan casi todas las apps antes de abrir. */
    override fun query(
        uri: Uri,
        columnas: Array<out String>?,
        seleccion: String?,
        args: Array<out String>?,
        orden: String?,
    ): Cursor? {
        val f = archivo(uri) ?: return null
        val pedidas = columnas ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val nombres = ArrayList<String>()
        val valores = ArrayList<Any>()
        for (col in pedidas) {
            when (col) {
                OpenableColumns.DISPLAY_NAME -> {
                    nombres += col
                    valores += f.name
                }
                OpenableColumns.SIZE -> {
                    nombres += col
                    valores += f.length()
                }
            }
        }
        return MatrixCursor(nombres.toTypedArray(), 1).apply { addRow(valores.toTypedArray()) }
    }

    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
}
