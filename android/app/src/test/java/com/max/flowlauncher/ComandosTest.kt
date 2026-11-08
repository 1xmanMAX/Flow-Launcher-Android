package com.max.flowlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ComandosTest {

    @Test fun duraciones() {
        assertEquals(600 to "", Comandos.duracion("10m"))
        assertEquals(600 to "pasta", Comandos.duracion("10 min pasta"))
        assertEquals(5400 to "", Comandos.duracion("1h30"))
        assertEquals(5400 to "", Comandos.duracion("1h 30m"))
        assertEquals(90 to "", Comandos.duracion("90s"))
        assertEquals(25 * 60 to "", Comandos.duracion("25"))
        assertNull(Comandos.duracion("pasta"))
        assertNull(Comandos.duracion("0m"))
    }

    /** Un domingo, 8-nov-2026 a las 15:00. */
    private fun ahora() = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 8, 15, 0, 0) }

    @Test fun fechas() {
        val (c, titulo, hora) = Comandos.fecha("reunión mañana 10:30", ahora())
        assertEquals("reunión", titulo)
        assertTrue(hora)
        assertEquals(9, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, c.get(Calendar.MINUTE))

        val (l, t2, h2) = Comandos.fecha("dentista el lunes", ahora())
        assertEquals("dentista el", t2)
        assertFalse(h2)
        assertEquals(Calendar.MONDAY, l.get(Calendar.DAY_OF_WEEK))
        assertEquals(9, l.get(Calendar.DAY_OF_MONTH))

        val (p, t3, h3) = Comandos.fecha("cumple de Ana 24/12 a las 8pm", ahora())
        assertEquals("cumple de Ana", t3)
        assertTrue(h3)
        assertEquals(24, p.get(Calendar.DAY_OF_MONTH))
        assertEquals(20, p.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun normalizar() {
        assertEquals("jose maria", Agenda.normalizar("José María"))
    }
}
