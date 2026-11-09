package com.max.flowlauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Calendar
import java.util.Locale

/**
 * Comandos que hablan con otras apps sin plugins, con sus enlaces oficiales:
 *
 * | Escribes | Hace |
 * |---|---|
 * | `wa ana hola` · `wa 987654321 hola` | WhatsApp con ese chat y el mensaje ya escrito |
 * | `wa hola` | Mandar «hola» por WhatsApp eligiendo el chat |
 * | `tg usuario` · `tg hola` | Telegram: ese chat o mandar el texto |
 * | `sms ana hola` · `tel ana` · `mail ana asunto` | SMS, llamada, correo |
 * | `nav casa` | Navegar con Maps |
 * | `timer 10m` · `alarma 7:30 gym` | El reloj del sistema |
 * | `cal reunión mañana 10` | Un evento en el calendario |
 * | `sp canción` | Buscar en Spotify |
 *
 * Sincrono y en memoria (la agenda ya esta cargada): sale con cada tecla.
 */
object Comandos {

    private class Comando(val palabra: String, val nombre: String, val ayuda: String)

    private val COMANDOS = listOf(
        Comando("wa", "WhatsApp", "wa <contacto o número> <mensaje>"),
        Comando("tg", "Telegram", "tg <usuario> · tg <mensaje>"),
        Comando("sms", "SMS", "sms <contacto> <mensaje>"),
        Comando("tel", "Llamar", "tel <contacto o número>"),
        Comando("mail", "Correo", "mail <contacto o correo> <asunto>"),
        Comando("nav", "Navegar", "nav <lugar>"),
        Comando("timer", "Temporizador", "timer 10m · timer 1h30 pasta"),
        Comando("alarma", "Alarma", "alarma 7:30 · alarma 18 gym"),
        Comando("cal", "Calendario", "cal reunión mañana 10:30"),
        Comando("sp", "Spotify", "sp <canción, artista o álbum>"),
    )

    private const val WHATSAPP = "com.whatsapp"
    private const val TELEGRAM = "org.telegram.messenger"
    private const val SPOTIFY = "com.spotify.music"

    /**
     * Lo de un comando, o null si lo escrito no es uno. Con la palabra sola
     * (sin espacio) no manda: solo sugiere ([sugerencias]).
     */
    fun consulta(c: Context, q: String): List<Resultado>? {
        val t = q.trimStart()
        val esp = t.indexOf(' ')
        if (esp <= 0) return null
        val palabra = t.substring(0, esp).lowercase()
        val resto = t.substring(esp + 1).trim()
        val r = ArrayList<Resultado>()
        when (palabra) {
            "wa" -> whatsapp(c, resto, r)
            "tg" -> telegram(c, resto, r)
            "sms" -> sms(c, resto, r)
            "tel" -> llamar(c, resto, r)
            "mail" -> correo(c, resto, r)
            "nav" -> navegar(c, resto, r)
            "timer" -> temporizador(c, resto, r)
            "alarma" -> alarma(c, resto, r)
            "cal" -> calendario(c, resto, r)
            "sp" -> spotify(c, resto, r)
            else -> return null
        }
        if (r.isEmpty()) {
            val cmd = COMANDOS.first { it.palabra == palabra }
            r += fila(c, cmd.nombre, cmd.ayuda, "", null)
        }
        // En el orden en que se anaden, por encima de todo.
        return r.mapIndexed { i, x -> x.copia(6000 - i) }
    }

    /** Escribir `wa` o `tim` sugiere el comando (sin tapar la app WhatsApp, que va antes). */
    fun sugerencias(c: Context, q: String): List<Resultado> {
        val t = q.trim().lowercase()
        if (t.length < 2 || t.contains(' ')) return emptyList()
        return COMANDOS.filter { it.palabra.startsWith(t) }.map {
            Resultado(
                titulo = "${it.nombre}  ·  ${it.palabra}", subtitulo = it.ayuda, icono = "res:ic_buscar",
                puntos = if (it.palabra == t) 900 else 300, accion = "consulta:${it.palabra} ", fuente = "comando",
                resaltado = IntArray(0), accionPlugin = null, contexto = null, autocompletar = "${it.palabra} ", copiar = null,
            )
        }
    }

    // --- cada comando ---

    private fun whatsapp(c: Context, resto: String, r: MutableList<Resultado>) {
        if (resto.isEmpty()) {
            r += fila(c, "Abrir WhatsApp", "wa <contacto o número> <mensaje>", "url:https://wa.me/", WHATSAPP)
            return
        }
        val (numero, mensaje) = numeroDelante(resto)
        if (numero != null) {
            r += fila(c, "WhatsApp a $numero", mensaje.ifEmpty { "Abrir el chat" }.let { citar(it) },
                "url:https://wa.me/${internacional(c, numero)}${texto("?text=", mensaje)}", WHATSAPP)
        }
        for ((p, msg) in Agenda.buscar(resto)) {
            val tel = p.telefonos.firstOrNull() ?: continue
            r += fila(c, "WhatsApp a ${p.nombre}", "${citar(msg.ifEmpty { "Abrir el chat" })} · $tel",
                "url:https://wa.me/${internacional(c, tel)}${texto("?text=", msg)}", WHATSAPP)
        }
        r += fila(c, "Enviar «$resto» por WhatsApp", "Eliges el chat", enviar(resto, WHATSAPP), WHATSAPP)
    }

    private fun telegram(c: Context, resto: String, r: MutableList<Resultado>) {
        if (resto.isEmpty()) {
            r += fila(c, "Abrir Telegram", "tg <usuario> · tg <mensaje>", "url:tg://", TELEGRAM)
            return
        }
        val usuario = resto.removePrefix("@")
        if (Regex("[A-Za-z][A-Za-z0-9_]{3,31}").matches(usuario)) {
            r += fila(c, "Abrir @$usuario en Telegram", "Chat, canal o grupo", "url:https://t.me/$usuario", TELEGRAM)
        }
        r += fila(c, "Enviar «$resto» por Telegram", "Eliges el chat", enviar(resto, TELEGRAM), TELEGRAM)
    }

    private fun sms(c: Context, resto: String, r: MutableList<Resultado>) {
        val (numero, mensaje) = numeroDelante(resto)
        if (numero != null) r += fila(c, "SMS a $numero", citar(mensaje.ifEmpty { "Escribir" }), "url:sms:$numero${texto("?body=", mensaje)}", null)
        for ((p, msg) in Agenda.buscar(resto)) {
            val tel = p.telefonos.firstOrNull() ?: continue
            r += fila(c, "SMS a ${p.nombre}", "${citar(msg.ifEmpty { "Escribir" })} · $tel", "url:sms:${Agenda.solo(tel)}${texto("?body=", msg)}", null)
        }
    }

    private fun llamar(c: Context, resto: String, r: MutableList<Resultado>) {
        val (numero, _) = numeroDelante(resto)
        if (numero != null) r += fila(c, "Llamar a $numero", "Abre el teléfono con el número", "url:tel:$numero", null)
        for ((p, _) in Agenda.buscar(resto)) {
            for (tel in p.telefonos.take(2)) r += fila(c, "Llamar a ${p.nombre}", tel, "url:tel:${Agenda.solo(tel)}", null)
        }
    }

    private fun correo(c: Context, resto: String, r: MutableList<Resultado>) {
        val primera = resto.substringBefore(' ')
        if (primera.contains('@')) {
            val asunto = resto.substringAfter(' ', "")
            r += fila(c, "Correo a $primera", asunto.ifEmpty { "Escribir" }, "url:mailto:$primera${texto("?subject=", asunto)}", null)
        }
        for ((p, asunto) in Agenda.buscar(resto)) {
            for (e in p.correos.take(2)) {
                r += fila(c, "Correo a ${p.nombre}", "${asunto.ifEmpty { "Escribir" }} · $e", "url:mailto:$e${texto("?subject=", asunto)}", null)
            }
        }
    }

    private fun navegar(c: Context, resto: String, r: MutableList<Resultado>) {
        if (resto.isEmpty()) return
        r += fila(c, "Ir a «$resto»", "Navegación paso a paso", "url:google.navigation:q=${Uri.encode(resto)}", "com.google.android.apps.maps")
        r += fila(c, "Ver «$resto» en el mapa", "Cualquier app de mapas", "url:geo:0,0?q=${Uri.encode(resto)}", null)
    }

    private fun temporizador(c: Context, resto: String, r: MutableList<Resultado>) {
        val (segundos, etiqueta) = duracion(resto) ?: return
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, segundos)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (etiqueta.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, etiqueta)
        r += fila(c, "Temporizador de ${legible(segundos)}", etiqueta.ifEmpty { "Empieza ya" }, intent(i), null, i)
    }

    private fun alarma(c: Context, resto: String, r: MutableList<Resultado>) {
        val m = Regex("^(\\d{1,2})(?:[:.h](\\d{2}))?\\s*(am|pm)?\\s*(.*)$", RegexOption.IGNORE_CASE).find(resto) ?: return
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        when (m.groupValues[3].lowercase()) {
            "pm" -> if (h < 12) h += 12
            "am" -> if (h == 12) h = 0
        }
        if (h > 23 || min > 59) return
        val etiqueta = m.groupValues[4].trim()
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, min)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (etiqueta.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, etiqueta)
        r += fila(c, "Alarma a las %d:%02d".format(h, min), etiqueta.ifEmpty { "Sin nombre" }, intent(i), null, i)
    }

    private fun calendario(c: Context, resto: String, r: MutableList<Resultado>) {
        if (resto.isEmpty()) return
        val (cuando, titulo, conHora) = fecha(resto)
        val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, titulo.ifEmpty { resto })
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cuando.timeInMillis)
        if (conHora) {
            i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, cuando.timeInMillis + 3_600_000)
        } else {
            i.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
        }
        val dia = String.format(Locale("es"), "%1\$tA %1\$te de %1\$tB", cuando) + if (conHora) String.format(" · %tH:%<tM", cuando) else " · todo el día"
        r += fila(c, "Evento: ${titulo.ifEmpty { resto }}", dia, intent(i), null, i)
    }

    private fun spotify(c: Context, resto: String, r: MutableList<Resultado>) {
        if (resto.isEmpty()) return
        val hay = runCatching { c.packageManager.getPackageInfo(SPOTIFY, 0); true }.getOrDefault(false)
        val url = if (hay) "url:spotify:search:${Uri.encode(resto)}" else "url:https://open.spotify.com/search/${Uri.encode(resto)}"
        r += fila(c, "Buscar «$resto» en Spotify", if (hay) "En la app" else "En la web", url, SPOTIFY)
    }

    // --- piezas ---

    /** `987654321 hola` → (987654321, hola). Con al menos 6 cifras. */
    private fun numeroDelante(t: String): Pair<String?, String> {
        val m = Regex("^(\\+?[\\d ()-]{6,})(?:\\s+(.*))?$").find(t.trim()) ?: return null to t
        val n = Agenda.solo(m.groupValues[1])
        if (n.count { it.isDigit() } < 6) return null to t
        return n to m.groupValues[2].trim()
    }

    /** El numero con prefijo de pais, solo cifras (lo que pide wa.me). */
    private fun internacional(c: Context, n: String): String {
        val iso = runCatching {
            val tm = c.getSystemService(TelephonyManager::class.java)
            tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: Locale.getDefault().country
        val e164 = runCatching { PhoneNumberUtils.formatNumberToE164(n, iso.uppercase()) }.getOrNull()
        return (e164 ?: n).filter { it.isDigit() }
    }

    private fun texto(pre: String, t: String) = if (t.isEmpty()) "" else pre + Uri.encode(t)
    private fun citar(t: String) = if (t == "Abrir el chat" || t == "Escribir") t else "«$t»"

    private fun enviar(t: String, paquete: String) =
        intent(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t).setPackage(paquete))

    private fun intent(i: Intent) = "intent:" + i.toUri(Intent.URI_INTENT_SCHEME)

    /** `10m`, `10 min`, `1h30`, `1h 30m`, `90s`, `25` (minutos). Lo que sobra es la etiqueta. */
    fun duracion(t: String): Pair<Int, String>? {
        val unidad = Regex("^(\\d+)\\s*(horas?|h|minutos?|min|m|segundos?|seg|s)(?![a-z])", RegexOption.IGNORE_CASE)
        var resto = t.trim()
        var total = 0
        var algo = false
        var ultima = ""
        while (true) {
            val m = unidad.find(resto)
            if (m != null) {
                val n = m.groupValues[1].toInt()
                ultima = m.groupValues[2].lowercase()
                total += n * when (ultima.first()) { 'h' -> 3600; 'm' -> 60; else -> 1 }
                algo = true
                resto = resto.substring(m.range.last + 1).trimStart()
                continue
            }
            // `1h30`: tras las horas, un numero solo son minutos; un numero solo, minutos.
            val suelto = Regex("^(\\d+)(?=\\s|$)").find(resto)
            if (suelto != null && (!algo || ultima.startsWith("h"))) {
                total += suelto.groupValues[1].toInt() * 60
                algo = true
                resto = resto.substring(suelto.range.last + 1).trimStart()
            }
            break
        }
        if (!algo || total <= 0 || total > 24 * 3600) return null
        return total to resto.trim()
    }

    private fun legible(s: Int) = buildString {
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        if (h > 0) append("$h h ")
        if (m > 0) append("$m min ")
        if (ss > 0) append("$ss s")
    }.trim()

    private val DIAS = listOf("domingo", "lunes", "martes", "miercoles", "jueves", "viernes", "sabado")

    /** `reunión mañana 10:30` → (mañana a las 10:30, «reunión», con hora). */
    fun fecha(t: String, ahora: Calendar = Calendar.getInstance()): Triple<Calendar, String, Boolean> {
        val c = (ahora.clone() as Calendar).apply { set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val quedan = ArrayList<String>()
        var conHora = false
        val palabras = t.split(Regex("\\s+"))
        var i = 0
        while (i < palabras.size) {
            val w = palabras[i]
            val n = Agenda.normalizar(w)
            val hora = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?(am|pm|h)?$").find(n)
            val dm = Regex("^(\\d{1,2})/(\\d{1,2})$").find(n)
            when {
                n == "hoy" -> {}
                n == "manana" -> c.add(Calendar.DAY_OF_YEAR, 1)
                n == "pasado" && palabras.getOrNull(i + 1)?.let(Agenda::normalizar) == "manana" -> { c.add(Calendar.DAY_OF_YEAR, 2); i++ }
                n in DIAS -> {
                    val obj = DIAS.indexOf(n) + 1
                    var d = (obj - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
                    if (d == 0) d = 7
                    c.add(Calendar.DAY_OF_YEAR, d)
                }
                dm != null -> {
                    c.set(Calendar.MONTH, dm.groupValues[2].toInt() - 1)
                    c.set(Calendar.DAY_OF_MONTH, dm.groupValues[1].toInt())
                    if (c.before(ahora)) c.add(Calendar.YEAR, 1)
                }
                hora != null && (hora.groupValues[2].isNotEmpty() || hora.groupValues[3].isNotEmpty() ||
                    palabras.getOrNull(i - 1)?.let(Agenda::normalizar) in setOf("a", "las", "alas")) -> {
                    var h = hora.groupValues[1].toInt()
                    if (hora.groupValues[3] == "pm" && h < 12) h += 12
                    if (h <= 23) {
                        c.set(Calendar.HOUR_OF_DAY, h)
                        c.set(Calendar.MINUTE, hora.groupValues[2].ifEmpty { "0" }.toInt().coerceAtMost(59))
                        conHora = true
                    }
                }
                // «a las 8»: fuera, si detras viene la hora.
                n == "a" || n == "las" -> {
                    val sig = palabras.drop(i + 1).take(2).map(Agenda::normalizar)
                    val vieneHora = sig.firstOrNull()?.first()?.isDigit() == true ||
                        (sig.firstOrNull() == "las" && sig.getOrNull(1)?.firstOrNull()?.isDigit() == true)
                    if (!vieneHora) quedan += w
                }
                else -> quedan += w
            }
            i++
        }
        if (!conHora) {
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
        }
        return Triple(c, quedan.joinToString(" "), conHora)
    }

    /** Una fila, con el icono de la app que lo va a hacer. */
    private fun fila(c: Context, titulo: String, sub: String, accion: String, paquete: String?, intent: Intent? = null): Resultado {
        val icono = paquete?.takeIf { instalada(c, it) }?.let { "pkg:$it" }
            ?: (intent ?: accion.takeIf { it.startsWith("url:") }?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it.removePrefix("url:"))) })
                ?.let { quienAbre(c, it) }?.let { "pkg:$it" }
            ?: "res:ic_buscar"
        return Resultado(
            titulo = titulo, subtitulo = sub, icono = icono, puntos = 0, accion = accion, fuente = "comando",
            resaltado = IntArray(0), accionPlugin = null, contexto = null, autocompletar = null, copiar = null,
        )
    }

    private val instaladas = HashMap<String, Boolean>()
    private fun instalada(c: Context, p: String) = synchronized(instaladas) {
        instaladas.getOrPut(p) { runCatching { c.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false) }
    }

    private val abridores = HashMap<String, String?>()
    /** La app que abre un intent (para su icono), recordada por accion y esquema. */
    private fun quienAbre(c: Context, i: Intent): String? {
        val clave = "${i.action}|${i.data?.scheme}"
        return synchronized(abridores) {
            abridores.getOrPut(clave) {
                runCatching { c.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName }.getOrNull()
                    ?.takeIf { it != "android" }
            }
        }
    }

    private fun Resultado.copia(p: Int) = Resultado(
        titulo, subtitulo, icono, p, accion, fuente, resaltado, accionPlugin, contexto, autocompletar, copiar,
    )
}
