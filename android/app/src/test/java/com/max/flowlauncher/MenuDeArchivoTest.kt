package com.max.flowlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El menu de un archivo del movil, con lo que ofrecen las apps proveedoras
 * («Compartir como HTML / PDF / JPG» de PinDesk, contrato v3, 10-nov-2026).
 */
class MenuDeArchivoTest {

    private val ruta = "/storage/emulated/0/Download/Tesis.docx"

    private fun dePinDesk(titulo: String, metodo: String = "compartir_como") = Resultado(
        titulo = titulo, subtitulo = "", icono = "", puntos = 3300, accion = "${Proveedores.LLAMAR}com.forge.pixpin.flow:$metodo",
        fuente = "proveedor:com.forge.pixpin", resaltado = IntArray(0), accionPlugin = "{\"formato\":\"pdf\"}", contexto = "{\"x\":1}",
        autocompletar = null, copiar = "algo",
    )

    @Test fun sinAppsElMenuDeSiempre() {
        val m = MenuDeArchivo.de(ruta, pinDesk = true)
        assertEquals(listOf("Compartir", "Enviar a PinDesk", "Abrir con…", "Abrir la carpeta donde está", "Copiar la ruta", "Copiar el nombre"), m.map { it.titulo })
    }

    @Test fun loDePinDeskVaDebajoDeCompartir() {
        val apps = listOf("Compartir como HTML", "Compartir como PDF", "Compartir como JPG").map { MenuDeArchivo.paraArchivo(dePinDesk(it), ruta) }
        val m = MenuDeArchivo.de(ruta, pinDesk = true, deApps = apps)
        assertEquals(
            listOf("Compartir", "Compartir como HTML", "Compartir como PDF", "Compartir como JPG", "Enviar a PinDesk", "Abrir con…"),
            m.take(6).map { it.titulo }
        )
        // Cada una sabe a que archivo va, y no trae menu propio ni «copiar».
        val pdf = m[2]
        assertTrue(MenuDeArchivo.esDeArchivo(pdf))
        assertEquals(ruta, MenuDeArchivo.rutaDe(pdf))
        assertNull(pdf.copiar)
        assertEquals("{\"formato\":\"pdf\"}", pdf.accionPlugin)
        assertTrue(pdf.accion.startsWith(Proveedores.LLAMAR))
    }

    @Test fun lasDeOtroArchivoOUnaCarpetaNoSalen() {
        val otra = MenuDeArchivo.paraArchivo(dePinDesk("Compartir como PDF"), "/storage/emulated/0/otro.pdf")
        assertTrue(MenuDeArchivo.de(ruta, deApps = listOf(otra)).none { it.titulo == "Compartir como PDF" })
        // Una fila sin marcar (de un menu normal) tampoco.
        assertTrue(MenuDeArchivo.de(ruta, deApps = listOf(dePinDesk("Compartir como PDF"))).none { it.titulo == "Compartir como PDF" })
        // Una carpeta no se comparte como nada.
        val carpeta = MenuDeArchivo.de("/storage/emulated/0/Download/", deApps = listOf(MenuDeArchivo.paraArchivo(dePinDesk("Compartir como PDF"), "/storage/emulated/0/Download")))
        assertEquals(listOf("Abrir la carpeta", "Copiar la ruta", "Copiar el nombre"), carpeta.map { it.titulo })
        assertFalse(MenuDeArchivo.esDeArchivo(dePinDesk("x")))
    }

    @Test fun laVersionDelContrato() {
        assertEquals(2, MenuDeArchivo.contrato(null))
        assertEquals(3, MenuDeArchivo.contrato(3))
        assertEquals(3, MenuDeArchivo.contrato(" 3 "))
        assertEquals(2, MenuDeArchivo.contrato("tres"))
        assertEquals(2, MenuDeArchivo.contrato(0))
        assertFalse(MenuDeArchivo.preguntaPorArchivos(2))
        assertTrue(MenuDeArchivo.preguntaPorArchivos(3))
        assertTrue(MenuDeArchivo.preguntaPorArchivos(4))
    }
}
