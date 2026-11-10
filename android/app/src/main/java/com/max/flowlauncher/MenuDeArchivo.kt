package com.max.flowlauncher

import java.io.File

/**
 * Lo que sale al mantener pulsado un archivo del movil (`c tesis`):
 * compartir (lo primero), abrir con otra app, la carpeta y copiar.
 *
 * Compartir no copia el archivo: se manda un `content://` de
 * [ProveedorDeArchivos] con permiso de lectura solo para la app elegida y solo
 * mientras lo usa. Rapido (nada que preparar) y seguro (nadie mas lo lee).
 *
 * Debajo de «Compartir», lo que las apps proveedoras ofrecen para ese archivo
 * (contrato v3, `query /archivo`, 10-nov-2026): PinDesk da «Compartir como
 * HTML / PDF / JPG» solo para lo que sabe pasar. No se adivina aqui: lo dice
 * la app. Sin Android, para probarlo en la JVM (`MenuDeArchivoTest`).
 */
object MenuDeArchivo {

    /**
     * Las filas de un proveedor para un archivo llevan esto delante de la ruta
     * en `contexto`: al elegirlas, [Proveedores.llamar] le pasa el archivo.
     */
    const val DE_ARCHIVO = "archivo:"

    /** Desde que version del contrato un proveedor contesta a `/archivo`. */
    const val CONTRATO_ARCHIVO = 3

    /** La version del contrato que declara un proveedor (`com.max.flowlauncher.contrato`); sin ella, la 2. */
    fun contrato(valor: Any?): Int = valor?.toString()?.trim()?.toIntOrNull()?.takeIf { it >= 1 } ?: 2

    /** Si a un proveedor de contrato [contrato] se le pregunta por los archivos. */
    fun preguntaPorArchivos(contrato: Int) = contrato >= CONTRATO_ARCHIVO

    /** Si [r] es una fila que un proveedor dio para un archivo (y a quien hay que pasarselo). */
    fun esDeArchivo(r: Resultado) = r.contexto?.startsWith(DE_ARCHIVO) == true

    /** La ruta del archivo de una fila de [esDeArchivo]. */
    fun rutaDe(r: Resultado): String? = r.contexto?.takeIf { it.startsWith(DE_ARCHIVO) }?.removePrefix(DE_ARCHIVO)

    /** Una fila de un proveedor, marcada con la ruta del archivo al que se refiere. */
    fun paraArchivo(r: Resultado, ruta: String) = Resultado(
        r.titulo, r.subtitulo, r.icono, r.puntos, r.accion, r.fuente, r.resaltado, r.accionPlugin,
        DE_ARCHIVO + ruta, null, null,
    )

    /** [deApps]: lo que dieron los proveedores para este archivo; va justo debajo de «Compartir». */
    fun de(ruta: String, pinDesk: Boolean = false, deApps: List<Resultado> = emptyList()): List<Resultado> {
        val esCarpeta = ruta.endsWith("/")
        val f = File(ruta.trimEnd('/'))
        val nombre = f.name
        val dentro = f.parent.orEmpty()
        val r = ArrayList<Resultado>()
        fun fila(titulo: String, sub: String, accion: String, icono: String) {
            r += Resultado(
                titulo = titulo, subtitulo = sub, icono = icono, puntos = 0, accion = accion,
                fuente = "archivos", resaltado = IntArray(0), accionPlugin = null, contexto = null,
                autocompletar = null, copiar = null,
            )
        }
        if (!esCarpeta) {
            fila("Compartir", "Mandar «$nombre» a otra app", "compartir:${f.path}", "res:ic_compartir")
            // Las de las apps, en su orden; solo las que de verdad son para este archivo.
            r += deApps.filter { rutaDe(it) == f.path }
            if (pinDesk) fila("Enviar a PinDesk", "Abrirlo o guardarlo en PinDesk", "pindesk:${f.path}", "pkg:${Acciones.PINDESK}")
            fila("Abrir con…", "Elegir con qué app abrirlo", "abrircon:${f.path}", "res:ic_abrir_con")
        }
        fila(
            if (esCarpeta) "Abrir la carpeta" else "Abrir la carpeta donde está",
            if (esCarpeta) f.path else dentro,
            "archivo:${if (esCarpeta) f.path else dentro}/", "res:ic_carpeta",
        )
        fila("Copiar la ruta", f.path, "copiar:${f.path}", "res:ic_copiar")
        fila("Copiar el nombre", nombre, "copiar:$nombre", "res:ic_copiar")
        return r
    }
}
