package com.max.flowlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SugerenciasGoogleTest {
    @Test
    fun leeLaRespuestaDeGoogle() {
        assertEquals(
            listOf("gatos negros", "gatos bebés"),
            SugerenciasGoogle.leer("""["gatos",["gatos negros","gatos bebés",""],[],{"google:suggesttype":[]}]"""),
        )
        assertTrue(SugerenciasGoogle.leer("no es json").isEmpty())
        assertTrue(SugerenciasGoogle.leer("""["x"]""").isEmpty())
    }
}
