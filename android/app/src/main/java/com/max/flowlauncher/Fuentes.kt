package com.max.flowlauncher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings

/**
 * Lo que se busca sin plugins, leido del sistema y cargado en el nucleo.
 * Todo en el hilo de fondo: nunca frena la interfaz.
 */
object Fuentes {

    fun cargarTodo(c: Context) {
        cargarApps(c)
        cargarAjustes(c)
        cargarContactos(c)
    }

    private fun intentUri(i: Intent) = "intent:" + i.toUri(Intent.URI_INTENT_SCHEME)

    fun cargarApps(c: Context) {
        val pm = c.packageManager
        val lanzables = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val lista = pm.queryIntentActivities(lanzables, 0)
            .filter { it.activityInfo.packageName != c.packageName }
        val titulos = ArrayList<String>(lista.size)
        val acciones = ArrayList<String>(lista.size)
        val claves = ArrayList<String>(lista.size)
        for (r in lista) {
            val a = r.activityInfo
            titulos += r.loadLabel(pm).toString()
            acciones += "app:${a.packageName}/${a.name}"
            claves += a.packageName
        }
        val vacios = Array(lista.size) { "" }
        Nucleo.cargar(
            Nucleo.APPS, titulos.toTypedArray(), vacios, acciones.toTypedArray(),
            acciones.toTypedArray(), claves.toTypedArray(),
        )
    }

    fun cargarContactos(c: Context) {
        if (Build.VERSION.SDK_INT >= 23 &&
            c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val titulos = ArrayList<String>()
        val numeros = ArrayList<String>()
        val acciones = ArrayList<String>()
        val vistos = HashSet<Long>()
        val columnas = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
        )
        runCatching {
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columnas, null, null, null,
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    if (!vistos.add(id)) continue
                    val nombre = cur.getString(1) ?: continue
                    titulos += nombre
                    numeros += cur.getString(2) ?: ""
                    val uri = ContactsContract.Contacts.getLookupUri(id, cur.getString(3) ?: "")
                    acciones += intentUri(Intent(Intent.ACTION_VIEW, uri))
                }
            }
        }
        val vacios = Array(titulos.size) { "" }
        Nucleo.cargar(
            Nucleo.CONTACTOS, titulos.toTypedArray(), numeros.toTypedArray(),
            acciones.toTypedArray(), vacios, vacios,
        )
    }

    /** Titulo, accion de Settings y palabras con que encontrarlo. */
    private val AJUSTES = listOf(
        Triple("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, "wifi red internet inalambrica"),
        Triple("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, "auriculares emparejar"),
        Triple("Pantalla", Settings.ACTION_DISPLAY_SETTINGS, "brillo tema oscuro tamaño letra"),
        Triple("Sonido", Settings.ACTION_SOUND_SETTINGS, "volumen tono vibracion"),
        Triple("Batería", Intent.ACTION_POWER_USAGE_SUMMARY, "bateria ahorro energia"),
        Triple("Ahorro de batería", Settings.ACTION_BATTERY_SAVER_SETTINGS, "bateria energia"),
        Triple("Almacenamiento", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "espacio memoria"),
        Triple("Aplicaciones", Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, "apps desinstalar"),
        Triple("Ubicación", Settings.ACTION_LOCATION_SOURCE_SETTINGS, "gps localizacion"),
        Triple("Seguridad", Settings.ACTION_SECURITY_SETTINGS, "bloqueo huella pin contraseña"),
        Triple("Privacidad", Settings.ACTION_PRIVACY_SETTINGS, "permisos"),
        Triple("Idioma", Settings.ACTION_LOCALE_SETTINGS, "idioma lengua"),
        Triple("Teclado", Settings.ACTION_INPUT_METHOD_SETTINGS, "teclado idioma escritura"),
        Triple("Fecha y hora", Settings.ACTION_DATE_SETTINGS, "reloj zona horaria"),
        Triple("Accesibilidad", Settings.ACTION_ACCESSIBILITY_SETTINGS, "lector pantalla"),
        Triple("Modo avión", Settings.ACTION_AIRPLANE_MODE_SETTINGS, "avion vuelo"),
        Triple("Datos móviles", Settings.ACTION_DATA_ROAMING_SETTINGS, "datos roaming red movil"),
        Triple("NFC", Settings.ACTION_NFC_SETTINGS, "pago contactless"),
        Triple("VPN", Settings.ACTION_VPN_SETTINGS, "vpn red privada"),
        Triple("Cuentas", Settings.ACTION_SYNC_SETTINGS, "sincronizar google"),
        Triple("No molestar", "android.settings.ZEN_MODE_SETTINGS", "silencio notificaciones"),
        Triple("Opciones de desarrollador", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "depuracion usb"),
        Triple("Acerca del teléfono", Settings.ACTION_DEVICE_INFO_SETTINGS, "informacion version android"),
        Triple("Ajustes", Settings.ACTION_SETTINGS, "configuracion"),
        Triple("Fondo de pantalla", Intent.ACTION_SET_WALLPAPER, "wallpaper"),
    )

    fun cargarAjustes(c: Context) {
        val pm = c.packageManager
        val titulos = ArrayList<String>()
        val subtitulos = ArrayList<String>()
        val acciones = ArrayList<String>()
        val claves = ArrayList<String>()
        for ((titulo, accion, k) in AJUSTES) {
            val i = Intent(accion)
            if (i.resolveActivity(pm) == null) continue
            titulos += titulo
            subtitulos += "Ajustes del sistema"
            acciones += intentUri(i)
            claves += k
        }
        // Lo propio del buscador.
        titulos += "Linterna"
        subtitulos += "Encender o apagar"
        acciones += "interno:linterna"
        claves += "flash luz torch"
        titulos += "Ajustes del buscador"
        subtitulos += "Burbuja, plugins, búsquedas web"
        acciones += intentUri(Intent(c, MainActivity::class.java))
        claves += "flow configuracion burbuja plugins"
        titulos += "Información de la app actual"
        subtitulos += "Abre los ajustes de esta app"
        acciones += intentUri(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")),
        )
        claves += "permisos app"
        val vacios = Array(titulos.size) { "" }
        Nucleo.cargar(
            Nucleo.AJUSTES, titulos.toTypedArray(), subtitulos.toTypedArray(),
            acciones.toTypedArray(), vacios, claves.toTypedArray(),
        )
    }
}
