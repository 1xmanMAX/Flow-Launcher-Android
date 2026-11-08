package com.max.flowlauncher

import java.io.File

/**
 * Lo que sale al mantener pulsado un archivo del movil (`c tesis`):
 * compartir (lo primero), abrir con otra app, la carpeta y copiar.
 *
 * Compartir no copia el archivo: se manda un `content://` de
 * [ProveedorDeArchivos] con permiso de lectura solo para la app elegida y solo
 * mientras lo usa. Rapido (nada que preparar) y seguro (nadie mas lo lee).
 */
object MenuDeArchivo {

    fun de(ruta: String, pinDesk: Boolean = false): List<Resultado> {
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
