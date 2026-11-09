package com.max.flowlauncher

/**
 * El nucleo en Rust (`libflowcore.so`, ver `core/flow-core/src/jni.rs`).
 * Lo complejo viaja como JSON.
 */
object Nucleo {
    init {
        System.loadLibrary("flowcore")
    }

    const val APPS = 0
    const val CONTACTOS = 1
    const val AJUSTES = 2
    const val ATAJOS = 3

    /** Una vez por proceso. Devuelve los errores al cargar plugins (JSON). */
    external fun iniciar(dirDatos: String, dirLibs: String, dirPlugins: String): String

    /** Cambia todos los elementos de un tipo. */
    external fun cargar(
        tipo: Int,
        titulos: Array<String>,
        subtitulos: Array<String>,
        acciones: Array<String>,
        iconos: Array<String>,
        claves: Array<String>,
    ): Int

    /** Lo local, al instante: `{"r": [...], "p": hayPlugins}`. */
    external fun consultar(consulta: String, max: Int): String

    /** Lo de los plugins. Bloquea hasta `esperaMs`: en otro hilo. */
    external fun consultarPlugins(consulta: String, max: Int, esperaMs: Int): String

    /** `{"cerrar": bool, "eventos": [...]}`. */
    external fun ejecutar(plugin: String, accion: String): String

    external fun menu(plugin: String, contexto: String): String

    external fun eventos(): String

    external fun registrarUso(consulta: String, clave: String)

    external fun importarBusquedas(json: String): String

    /** Palabras clave de las que se encargan apps proveedoras: sus plugins se apagan. */
    external fun cederPalabras(palabras: Array<String>)

    /** La palabra clave de los archivos del movil (vacia: `c`). */
    external fun palabraArchivos(palabra: String)

    /** Las palabras clave de un plugin (vacio: las de su plugin.json). */
    external fun palabrasPlugin(id: String, palabras: Array<String>)

    /**
     * Indexa los archivos de `raiz` (para `c <nombre>`) en un hilo del
     * nucleo. Vuelve enseguida; si el indice es reciente, no hace nada.
     */
    external fun indexarArchivos(raiz: String)

    /** `{"plugins": [...], "busquedas": {...}, "elementos": n, "archivos": n, "indexando": bool}`. */
    external fun estado(): String
}
