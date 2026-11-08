//! El puente con Kotlin: `com.max.flowlauncher.Nucleo`.
//!
//! Todo lo complejo viaja como texto JSON (unas decenas de resultados:
//! microsegundos). Ningun panico cruza a Java: se atrapan y se devuelve algo
//! vacio.

use crate::indice::{Elemento, Tipo};
use crate::motor::Motor;
use crate::resultado::lista_json;
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use serde_json::{json, Value};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::OnceLock;
use std::time::Duration;

static MOTOR: OnceLock<Motor> = OnceLock::new();

fn motor() -> Option<&'static Motor> {
    MOTOR.get()
}

fn texto(env: &mut JNIEnv, s: &JString) -> String {
    if s.is_null() {
        return String::new();
    }
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

fn devolver(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn lista(env: &mut JNIEnv, a: &JObjectArray) -> Vec<String> {
    if a.is_null() {
        return Vec::new();
    }
    let n = env.get_array_length(a).unwrap_or(0);
    let mut v = Vec::with_capacity(n as usize);
    for i in 0..n {
        let Ok(o) = env.get_object_array_element(a, i) else {
            v.push(String::new());
            continue;
        };
        let s = JString::from(o);
        v.push(texto(env, &s));
        // Cada elemento es una referencia local: liberarla ya. En Android 7
        // la tabla tiene 512 huecos y con 100 apps (x5 listas) se agotaria.
        let _ = env.delete_local_ref(s);
    }
    v
}

fn seguro<T>(por_defecto: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(por_defecto)
}

/// `iniciar(dirDatos, dirLibs, dirPlugins)`: una vez al arrancar el proceso.
/// Devuelve los errores al cargar plugins (JSON).
#[no_mangle]
pub extern "system" fn Java_com_max_flowlauncher_Nucleo_iniciar(
    mut env: JNIEnv,
    _c: JClass,
    datos: JString,
    libs: JString,
    plugins: JString,
) -> jstring {
    let (datos, libs, plugins) = (texto(&mut env, &datos), texto(&mut env, &libs), texto(&mut env, &plugins));
    let r = seguro(json!(["fallo al iniciar"]), || {
        let mut nuevo = false;
        let m = MOTOR.get_or_init(|| {
            nuevo = true;
            let libs = (!libs.is_empty()).then(|| PathBuf::from(&libs));
            Motor::nuevo(&PathBuf::from(&datos), libs)
        });
        if !nuevo || plugins.is_empty() {
            return json!([]);
        }
        let errores = m.host.cargar_carpeta(&PathBuf::from(&plugins));
        // Los v2 arrancan ya, en otro hilo: la primera tecla no espera.
        std::thread::spawn(|| {
            if let Some(m) = motor() {
                m.host.preparar();
            }
        });
        json!(errores)
    });
    devolver(&mut env, &r.to_string())
}

/// `cargar(tipo, titulos, subtitulos, acciones, iconos, claves)`: cambia
/// todos los elementos de un tipo.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_max_flowlauncher_Nucleo_cargar(
    mut env: JNIEnv,
    _c: JClass,
    tipo: jint,
    titulos: JObjectArray,
    subtitulos: JObjectArray,
    acciones: JObjectArray,
    iconos: JObjectArray,
    claves: JObjectArray,
) -> jint {
    let (t, s, a, i, k) = (
        lista(&mut env, &titulos),
        lista(&mut env, &subtitulos),
        lista(&mut env, &acciones),
        lista(&mut env, &iconos),
        lista(&mut env, &claves),
    );
    seguro(-1, || {
        let (Some(m), Some(tipo)) = (motor(), Tipo::de_numero(tipo)) else { return -1 };
        let vacio = String::new();
        let elementos: Vec<Elemento> = t
            .iter()
            .enumerate()
            .map(|(n, titulo)| {
                let o = |v: &Vec<String>| v.get(n).unwrap_or(&vacio).clone();
                Elemento::nuevo(tipo, titulo, &o(&s), &o(&a), &o(&i), &o(&k))
            })
            .collect();
        let n = elementos.len() as jint;
        m.cargar_elementos(tipo, elementos);
        n
    })
}

/// Lo local: `{"r": [...], "p": true}` (`p`: hay plugins que preguntar).
#[no_mangle]
pub extern "system" fn Java_com_max_flowlauncher_Nucleo_consultar(
    mut env: JNIEnv,
    _c: JClass,
    consulta: JString,
    max: jint,
) -> jstring {
    let q = texto(&mut env, &consulta);
    let r = seguro(json!({ "r": [], "p": false }), || {
        let Some(m) = motor() else { return json!({ "r": [], "p": false }) };
        let r = m.consultar(&q, max.max(1) as usize);
        json!({ "r": lista_json(&r.resultados), "p": r.plugins })
    });
    devolver(&mut env, &r.to_string())
}

/// Lo de los plugins (bloquea hasta `esperaMs`: llamarlo en otro hilo).
#[no_mangle]
pub extern "system" fn Java_com_max_flowlauncher_Nucleo_consultarPlugins(
    mut env: JNIEnv,
    _c: JClass,
    consulta: JString,
    max: jint,
    espera_ms: jint,
) -> jstring {
    let q = texto(&mut env, &consulta);
    let r = seguro(json!([]), || {
        let Some(m) = motor() else { return json!([]) };
        lista_json(&m.consultar_plugins(&q, max.max(1) as usize, Duration::from_millis(espera_ms.max(50) as u64)))
    });
    devolver(&mut env, &r.to_string())
}

/// Ejecuta la `jsonRPCAction` de un resultado de plugin. Devuelve
/// `{"cerrar": bool, "eventos": [...]}`.
#[no_mangle]
pub extern "system" fn Java_com_max_flowlauncher_Nucleo_ejecutar(
    mut env: JNIEnv,
    _c: JClass,
    plugin: JString,
    accion: JString,
) -> jstring {
    let (id, accion) = (texto(&mut env, &plugin), texto(&mut env, &accion));
    let r = seguro(json!({ "cerrar": true, "eventos": [] }), || {
        let Some(m) = motor() else { return json!({ "cerrar": true, "eventos": [] }) };
        let accion: Value = serde_json::from_str(&accion).unwrap_or(Value::Null);
        let cerrar = match m.host.buscar_id(&id) {
            Some(p) => p.ejecutar(&accion, Duration::from_secs(10), &m.host.eventos),
            None => true,
        };
        json!({ "cerrar": cerrar, "eventos": m.host.tomar_eventos() })
    });
    devolver(&mut env, &r.to_string())
}

/// El menu contextual de un resultado de plugin (su `contextData`).
#[no_mangle]
pub extern "system" fn Java_com_max_flowlauncher_Nucleo_menu(
    mut env: JNIEnv,
    _c: JClass,
    plugin: JString,
    contexto: JString,
) -> jstring {
    let (id, cx) = (texto(&mut env, &plugin), texto(&mut env, &contexto));
    let r = seguro(json!([]), || {
        let (Some(m), Ok(cx)) = (motor(), serde_json::from_str::<Value>(&cx)) else { return json!([]) };
        match m.host.buscar_id(&id) {
            Some(p) => lista_json(&p.menu(&cx, Duration::from_secs(3), &m.host.eventos)),
            None => json!([]),
        }
    });
    devolver(&mut env, &r.to_string())
}

/// Lo que los plugins han pedido por su cuenta (`ChangeQuery`, `ShowMsg`...).
#[no_mangle]
pub extern "system" fn Java_com_max_flowlauncher_Nucleo_eventos(mut env: JNIEnv, _c: JClass) -> jstring {
    let r = seguro(json!([]), || motor().map(|m| json!(m.host.tomar_eventos())).unwrap_or(json!([])));
    devolver(&mut env, &r.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_max_flowlauncher_Nucleo_registrarUso(
    mut env: JNIEnv,
    _c: JClass,
    consulta: JString,
    clave: JString,
) {
    let (q, k) = (texto(&mut env, &consulta), texto(&mut env, &clave));
    seguro((), || {
        if let Some(m) = motor() {
            m.registrar_uso(&q, &k);
        }
    });
}

/// Importa el `Settings.json` del WebSearch de Flow. Devuelve
/// `{"ok": n}` o `{"error": "..."}`.
#[no_mangle]
pub extern "system" fn Java_com_max_flowlauncher_Nucleo_importarBusquedas(
    mut env: JNIEnv,
    _c: JClass,
    json_texto: JString,
) -> jstring {
    let t = texto(&mut env, &json_texto);
    let r = seguro(json!({ "error": "fallo interno" }), || match motor().map(|m| m.importar_busquedas(&t)) {
        Some(Ok(n)) => json!({ "ok": n }),
        Some(Err(e)) => json!({ "error": e }),
        None => json!({ "error": "sin iniciar" }),
    });
    devolver(&mut env, &r.to_string())
}

/// `indexarArchivos(raiz)`: empieza a indexar el almacenamiento en otro
/// hilo, o nada si el indice es reciente. Vuelve enseguida.
#[no_mangle]
pub extern "system" fn Java_com_max_flowlauncher_Nucleo_indexarArchivos(mut env: JNIEnv, _c: JClass, raiz: JString) {
    let raiz = texto(&mut env, &raiz);
    seguro((), || {
        if let (Some(m), false) = (motor(), raiz.is_empty()) {
            m.indexar_archivos(&PathBuf::from(&raiz));
        }
    });
}

/// `{"plugins": [...], "busquedas": {...}, "elementos": n, "archivos": n,
/// "indexando": bool}`.
#[no_mangle]
pub extern "system" fn Java_com_max_flowlauncher_Nucleo_estado(mut env: JNIEnv, _c: JClass) -> jstring {
    let r = seguro(json!({}), || match motor() {
        Some(m) => json!({
            "plugins": m.plugins_json(), "busquedas": m.busquedas_json(), "elementos": m.elementos(),
            "archivos": m.archivos.len(), "indexando": m.archivos.indexando(),
        }),
        None => json!({}),
    });
    devolver(&mut env, &r.to_string())
}

/// `cederPalabras(["p"])`: palabras clave de las que se encargan apps
/// proveedoras; los plugins con esas palabras dejan de usarse.
#[no_mangle]
pub extern "system" fn Java_com_max_flowlauncher_Nucleo_cederPalabras(mut env: JNIEnv, _c: JClass, palabras: JObjectArray) {
    let p = lista(&mut env, &palabras);
    seguro((), || {
        if let Some(m) = motor() {
            m.host.ceder(p);
        }
    });
}
