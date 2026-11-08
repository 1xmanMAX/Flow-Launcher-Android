package com.max.flowlauncher

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import java.text.Normalizer

/**
 * Los contactos en memoria (para los comandos: `wa ana hola`, `sms`, `mail`) y
 * lo que WhatsApp, Telegram, Signal y compania anaden a cada uno: sus filas de
 * «Mensaje», «Llamada» y «Videollamada» en la agenda del sistema. Esas filas
 * se abren igual que en la app de Contactos: directas al chat o a la llamada.
 *
 * Todo en el hilo de fondo; con el permiso de contactos que ya se pide.
 */
object Agenda {

    class Persona(val nombre: String, val telefonos: List<String>, val correos: List<String>) {
        val normal = normalizar(nombre)
    }

    /** Una fila de una app de mensajeria en la agenda. */
    class Fila(val nombre: String, val app: Mensajeria, val que: String, val dato: Long, val tipo: String)

    /** Las apps que se conocen, por el principio de sus tipos MIME en la agenda. */
    enum class Mensajeria(val prefijo: String, val paquete: String, val nombre: String, val claves: String) {
        WHATSAPP("vnd.android.cursor.item/vnd.com.whatsapp.", "com.whatsapp", "WhatsApp", "whatsapp wsp wa"),
        WHATSAPP_NEGOCIOS("vnd.android.cursor.item/vnd.com.whatsapp.w4b.", "com.whatsapp.w4b", "WhatsApp Business", "whatsapp wsp wa business"),
        TELEGRAM("vnd.android.cursor.item/vnd.org.telegram.messenger.", "org.telegram.messenger", "Telegram", "telegram tg"),
        SIGNAL("vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.", "org.thoughtcrime.securesms", "Signal", "signal"),
        VIBER("vnd.android.cursor.item/vnd.com.viber.voip.", "com.viber.voip", "Viber", "viber"),
        MEET("vnd.android.cursor.item/com.google.android.apps.tachyon.", "com.google.android.apps.tachyon", "Meet", "meet duo"),
        ;

        companion object {
            /** La de un tipo MIME (la de negocios antes que la normal: su prefijo es mas largo). */
            fun de(tipo: String): Mensajeria? = entries.sortedByDescending { it.prefijo.length }.firstOrNull { tipo.startsWith(it.prefijo) }
        }
    }

    @Volatile
    var personas: List<Persona> = emptyList()
        private set

    fun normalizar(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Lee telefonos y correos de todos los contactos. */
    fun cargar(c: Context) {
        val telefonos = HashMap<Long, MutableList<String>>()
        val correos = HashMap<Long, MutableList<String>>()
        val nombres = HashMap<Long, String>()
        runCatching {
            c.contentResolver.query(
                Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER), null, null, null,
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    cur.getString(1)?.let { nombres[id] = it }
                    val n = cur.getString(2) ?: continue
                    val l = telefonos.getOrPut(id) { ArrayList() }
                    if (l.none { solo(it) == solo(n) }) l += n
                }
            }
        }
        runCatching {
            c.contentResolver.query(
                Email.CONTENT_URI, arrayOf(Email.CONTACT_ID, Email.DISPLAY_NAME_PRIMARY, Email.ADDRESS), null, null, null,
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    cur.getString(1)?.let { nombres.putIfAbsent(id, it) }
                    val e = cur.getString(2) ?: continue
                    correos.getOrPut(id) { ArrayList() } += e
                }
            }
        }
        personas = nombres.map { (id, n) -> Persona(n, telefonos[id].orEmpty(), correos[id].orEmpty()) }
            .sortedBy { it.normal }
    }

    /** Las filas de WhatsApp, Telegram... de la agenda. */
    fun mensajeria(c: Context): List<Fila> {
        val r = ArrayList<Fila>()
        runCatching {
            c.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME_PRIMARY, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA3),
                "${ContactsContract.Data.MIMETYPE} LIKE ?", arrayOf("vnd.android.cursor.item/%"), null,
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val tipo = cur.getString(2) ?: continue
                    val app = Mensajeria.de(tipo) ?: continue
                    val nombre = cur.getString(1) ?: continue
                    // DATA3 es lo que la app quiere que se lea («Mensaje a +51…», «Llamada de voz»).
                    val que = cur.getString(3)?.takeIf { it.isNotBlank() } ?: porDefecto(tipo)
                    r += Fila(nombre, app, que, cur.getLong(0), tipo)
                }
            }
        }
        return r
    }

    private fun porDefecto(tipo: String) = when {
        tipo.contains("video") -> "Videollamada"
        tipo.contains("call") || tipo.contains("voip") -> "Llamada"
        else -> "Mensaje"
    }

    /** Como lo abre la app de Contactos: la fila de datos con su tipo. */
    fun intent(f: Fila): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, f.dato), f.tipo)

    fun solo(n: String) = n.filter { it.isDigit() || it == '+' }

    /**
     * Los contactos cuyo nombre empieza por las primeras palabras de [texto]
     * (cada palabra, el comienzo de una del nombre). Devuelve cada contacto con
     * lo que sobra detras (el mensaje): `ana garcia hola` → (Ana García, «hola»).
     */
    fun buscar(texto: String, max: Int = 4): List<Pair<Persona, String>> {
        val palabras = texto.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (palabras.isEmpty()) return emptyList()
        val l = personas
        for (k in minOf(palabras.size, 4) downTo 1) {
            val q = palabras.take(k).map(::normalizar)
            val resto = palabras.drop(k).joinToString(" ")
            val encajan = l.filter { p ->
                val ws = p.normal.split(' ', '-', '.').filter { it.isNotEmpty() }
                q.all { w -> ws.any { it.startsWith(w) } }
            }
            if (encajan.isNotEmpty()) return encajan.take(max).map { it to resto }
        }
        return emptyList()
    }
}
