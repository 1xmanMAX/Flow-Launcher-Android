package com.max.flowlauncher

import android.content.Context
import java.io.File

/**
 * Los plugins que vienen con la app: su carpeta (`plugin.json`, imagenes)
 * esta en `assets/plugins/`; su programa, en `jniLibs` como `lib<nombre>.so`
 * (Android solo deja ejecutar desde ahi).
 */
object Plugins {

    fun instalarIncluidos(c: Context): File {
        val destino = File(c.filesDir, "plugins")
        val marca = File(destino, ".version")
        // La version y la fecha de instalacion: un APK nuevo con el mismo
        // versionCode (al desarrollar) tambien trae sus plugins.
        val actualizada = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val version = "${BuildConfig.VERSION_CODE}-$actualizada"
        if (marca.isFile && marca.readText() == version) return destino
        copiar(c, "plugins", destino)
        marca.writeText(version)
        return destino
    }

    private fun copiar(c: Context, ruta: String, destino: File) {
        val hijos = c.assets.list(ruta) ?: return
        if (hijos.isEmpty()) {
            destino.parentFile?.mkdirs()
            c.assets.open(ruta).use { e -> destino.outputStream().use { e.copyTo(it) } }
            return
        }
        destino.mkdirs()
        for (h in hijos) copiar(c, "$ruta/$h", File(destino, h))
    }
}
