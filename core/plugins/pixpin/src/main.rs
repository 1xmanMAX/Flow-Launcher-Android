//! **PixPin Max** (`p`) para el buscador de Android: el plugin de Flow de
//! Windows (`PIXPIN_PRO_PLUGIN_FLOWLAUNCHER`), portado.
//!
//! En Windows el plugin lee el disco de PixPin y le manda pedidos por
//! `WM_COPYDATA`. En Android los datos de PixPin son privados de la app, asi
//! que aqui todo va por la puerta que PixPin abre para eso: sus senas
//! `pindesk://atajo/...` (`atajos/AtajoActivity.kt`), el equivalente de los
//! pedidos del PC. Lo que en Windows se buscaba en el disco (proyectos,
//! mensajes, archivos) se busca con el buscador de la propia PixPin
//! (`pindesk://atajo/buscar?texto=`).
//!
//! La misma sintaxis que en Windows (`consulta.rs`):
//! - nada: las funciones;
//! - `<verbo> [texto]`: `chat hola`, `tarea comprar pan`, `nota idea`,
//!   `grabar`, `tareas`, `lecciones`...;
//! - una letra y un blanco: `t <texto>` tarea, `n` nota, `c` capturar,
//!   `g` galeria, `a` leccion nueva, `b` buscar;
//! - cualquier otra cosa: buscarlo en PixPin, o mandarlo al chat, a una
//!   tarea o a una nota.
//!
//! Protocolo `Executable_V2` de Flow (JSON-RPC por stdin/stdout) y tambien
//! `Executable` (v1, la peticion como argumento).

use serde_json::{json, Value};
use std::io::{BufRead, Write};

const ICONO: &str = "Images/pixpin.png";
const ESQUEMA: &str = "pindesk://atajo";

/// Una funcion de PixPin: como se llama, con que palabras se encuentra, si
/// lleva texto y la sena que la hace.
struct Funcion {
    titulo: &'static str,
    subtitulo: &'static str,
    /// La primera es el verbo; la letra sola, el atajo.
    palabras: &'static [&'static str],
    /// La ruta de la sena (`tarea`, `chat`...).
    ruta: &'static str,
    /// Como se llama el dato del texto (`texto`, `titulo`), si lo lleva.
    dato: Option<&'static str>,
    /// Lo que dice el titulo cuando hay texto: «Apuntar «x» como tarea».
    con_texto: &'static str,
}

const FUNCIONES: &[Funcion] = &[
    Funcion { titulo: "Buscar en PixPin", subtitulo: "Proyectos, mensajes, notas y archivos", palabras: &["buscar", "b"], ruta: "buscar", dato: Some("texto"), con_texto: "Buscar «{}» en PixPin" },
    Funcion { titulo: "Chat", subtitulo: "Mensajes guardados", palabras: &["chat", "mensajes"], ruta: "chat", dato: Some("texto"), con_texto: "Escribir «{}» en el chat" },
    Funcion { titulo: "Tarea nueva", subtitulo: "Apuntarla en el Inbox", palabras: &["tarea", "t", "inbox"], ruta: "tarea", dato: Some("texto"), con_texto: "Apuntar «{}» como tarea" },
    Funcion { titulo: "Tareas", subtitulo: "Todas las listas de tareas", palabras: &["tareas", "pendientes"], ruta: "tareas", dato: None, con_texto: "" },
    Funcion { titulo: "Lista de tareas nueva", subtitulo: "Una lista nueva en el chat", palabras: &["lista"], ruta: "lista", dato: Some("titulo"), con_texto: "Lista de tareas «{}»" },
    Funcion { titulo: "Nota nueva", subtitulo: "Abre el editor de notas", palabras: &["nota", "n"], ruta: "nota", dato: Some("texto"), con_texto: "Nota con «{}»" },
    Funcion { titulo: "Grabar", subtitulo: "El micrófono flotante: una nota de voz", palabras: &["grabar", "voz", "audio"], ruta: "grabar", dato: None, con_texto: "" },
    Funcion { titulo: "Capturar", subtitulo: "Recortar una zona de la pantalla", palabras: &["capturar", "c", "captura"], ruta: "capturar", dato: None, con_texto: "" },
    Funcion { titulo: "Capturas", subtitulo: "La galería de capturas", palabras: &["capturas", "g", "galeria"], ruta: "capturas", dato: None, con_texto: "" },
    Funcion { titulo: "Soltar archivos", subtitulo: "La caja flotante para soltar archivos al chat", palabras: &["soltar"], ruta: "soltar", dato: None, con_texto: "" },
    Funcion { titulo: "Lección nueva", subtitulo: "Apuntar algo aprendido", palabras: &["leccion", "a", "aprendi"], ruta: "leccion", dato: None, con_texto: "" },
    Funcion { titulo: "Lecciones", subtitulo: "Las lecciones aprendidas", palabras: &["lecciones", "repasar"], ruta: "lecciones", dato: None, con_texto: "" },
    Funcion { titulo: "Proyectos", subtitulo: "La lista de chats", palabras: &["proyectos", "chats"], ruta: "proyectos", dato: None, con_texto: "" },
    Funcion { titulo: "Sincronizar", subtitulo: "Con el PC y el grupo", palabras: &["sincronizar", "sincro"], ruta: "sincronizar", dato: None, con_texto: "" },
];

/// Minusculas y sin tildes.
fn normalizar(s: &str) -> String {
    s.chars()
        .flat_map(char::to_lowercase)
        .map(|c| match c {
            'á' | 'à' | 'ä' | 'â' => 'a',
            'é' | 'è' | 'ë' | 'ê' => 'e',
            'í' | 'ì' | 'ï' | 'î' => 'i',
            'ó' | 'ò' | 'ö' | 'ô' => 'o',
            'ú' | 'ù' | 'ü' | 'û' => 'u',
            'ñ' => 'n',
            otro => otro,
        })
        .collect()
}

/// Percent-encoding para la consulta de la sena.
fn codificar(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-_.~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

fn sena(f: &Funcion, texto: &str) -> String {
    match f.dato {
        Some(d) if !texto.is_empty() => format!("{ESQUEMA}/{}?{d}={}", f.ruta, codificar(texto)),
        _ => format!("{ESQUEMA}/{}", f.ruta),
    }
}

fn resultado(f: &Funcion, texto: &str, puntos: i64) -> Value {
    let titulo = if texto.is_empty() || f.dato.is_none() {
        f.titulo.to_string()
    } else {
        f.con_texto.replace("{}", texto)
    };
    let uri = sena(f, texto);
    json!({
        "title": titulo,
        "subTitle": f.subtitulo,
        "icoPath": ICONO,
        "score": puntos,
        // `OpenUri` lo hace el buscador: abre la sena con PixPin.
        "jsonRPCAction": { "method": "Flow.Launcher.OpenUri", "parameters": [uri] },
        "autoCompleteText": format!("p {} ", f.palabras[0]),
        "contextData": uri,
    })
}

/// Lo escrito detras de `p`, entendido.
fn consulta(busqueda: &str) -> Value {
    let s = busqueda.trim();
    if s.is_empty() {
        return Value::Array(FUNCIONES.iter().enumerate().map(|(i, f)| resultado(f, "", 1000 - i as i64)).collect());
    }
    let (primera, resto) = match s.split_once(char::is_whitespace) {
        Some((a, b)) => (a, b.trim()),
        None => (s, ""),
    };
    let p = normalizar(primera);

    // `tarea comprar pan`, `t comprar pan`, `tareas`: una funcion por su
    // palabra. Una letra sola solo vale seguida de un blanco o sola (`t`), no
    // pegada a otras (`tx` es lo de siempre).
    if let Some(f) = FUNCIONES.iter().find(|f| f.palabras.iter().any(|w| *w == p)) {
        let mut r = vec![resultado(f, resto, 2000)];
        // Con texto y una funcion sin texto (`grabar clase 3`): tambien
        // buscarlo, por si era eso.
        if f.dato.is_none() && !resto.is_empty() {
            r.push(resultado(&FUNCIONES[0], s, 500));
        }
        return Value::Array(r);
    }

    // Lo demas: las funciones cuyo nombre empieza asi, y lo que se puede
    // hacer con el texto (buscarlo primero, como en Windows).
    let q = normalizar(s);
    let mut r: Vec<Value> = FUNCIONES
        .iter()
        .filter(|f| f.dato.is_none() || f.ruta == "lista")
        .filter(|f| {
            normalizar(f.titulo).split_whitespace().any(|w| w.starts_with(&q))
                || f.palabras.iter().any(|w| w.len() > 1 && w.starts_with(&q))
        })
        .map(|f| resultado(f, "", 1500))
        .collect();
    for (f, puntos) in [("buscar", 1200), ("chat", 900), ("tarea", 800), ("nota", 700)] {
        if let Some(f) = FUNCIONES.iter().find(|x| x.ruta == f) {
            r.push(resultado(f, s, puntos));
        }
    }
    Value::Array(r)
}

/// Menu contextual: copiar la sena (para un atajo, Tasker...).
fn menu(contexto: &Value) -> Value {
    let uri = contexto.as_str().unwrap_or("");
    json!([{
        "title": "Copiar la seña",
        "subTitle": uri,
        "icoPath": ICONO,
        "score": 0,
        "jsonRPCAction": { "method": "Flow.Launcher.CopyToClipboard", "parameters": [uri, false, true] },
    }])
}

fn primero(params: &Value) -> Value {
    match params {
        Value::Array(a) => a.first().cloned().unwrap_or(Value::Null),
        otro => otro.clone(),
    }
}

/// El modo v1: una peticion, una respuesta.
fn v1(peticion: &str) -> Value {
    let Ok(p) = serde_json::from_str::<Value>(peticion) else { return json!({}) };
    let metodo = p.get("method").and_then(Value::as_str).unwrap_or("");
    let params = p.get("parameters").cloned().unwrap_or(json!([]));
    match metodo {
        "query" => json!({ "result": consulta(primero(&params).as_str().unwrap_or("")) }),
        "context_menu" => json!({ "result": menu(&primero(&params)) }),
        _ => json!({}),
    }
}

fn v2() {
    let mut salida = std::io::stdout().lock();
    for linea in std::io::stdin().lock().lines() {
        let Ok(linea) = linea else { break };
        let Ok(msg) = serde_json::from_str::<Value>(linea.trim()) else { continue };
        let Some(metodo) = msg.get("method").and_then(Value::as_str) else { continue };
        let id = msg.get("id").cloned().filter(|i| !i.is_null());
        let params = msg.get("params").cloned().unwrap_or(Value::Null);
        let resultado = match metodo {
            "initialize" => json!({}),
            "query" => {
                let q = primero(&params);
                json!({ "result": consulta(q.get("search").and_then(Value::as_str).unwrap_or("")) })
            }
            "context_menu" => json!({ "result": menu(&primero(&params)) }),
            "close" => {
                if let Some(id) = id {
                    let _ = writeln!(salida, "{}", json!({ "jsonrpc": "2.0", "id": id, "result": null }));
                }
                break;
            }
            _ => json!({ "hide": true }),
        };
        if let Some(id) = id {
            let _ = writeln!(salida, "{}", json!({ "jsonrpc": "2.0", "id": id, "result": resultado }));
            let _ = salida.flush();
        }
    }
}

fn main() {
    match std::env::args().nth(1) {
        Some(p) if p.trim_start().starts_with('{') => print!("{}", v1(&p)),
        _ => v2(),
    }
}

#[cfg(test)]
mod pruebas {
    use super::*;

    fn uri(r: &Value) -> &str {
        r["jsonRPCAction"]["parameters"][0].as_str().unwrap()
    }

    #[test]
    fn sin_nada_las_funciones() {
        let r = consulta("");
        assert_eq!(r.as_array().unwrap().len(), FUNCIONES.len());
        assert_eq!(uri(&r[0]), "pindesk://atajo/buscar");
    }

    #[test]
    fn verbos_y_letras() {
        assert_eq!(uri(&consulta("tarea comprar pan")[0]), "pindesk://atajo/tarea?texto=comprar%20pan");
        assert_eq!(uri(&consulta("t comprar pan")[0]), "pindesk://atajo/tarea?texto=comprar%20pan");
        assert_eq!(uri(&consulta("nota idea")[0]), "pindesk://atajo/nota?texto=idea");
        assert_eq!(uri(&consulta("chat hola")[0]), "pindesk://atajo/chat?texto=hola");
        assert_eq!(uri(&consulta("tareas")[0]), "pindesk://atajo/tareas");
        assert_eq!(uri(&consulta("lista compras")[0]), "pindesk://atajo/lista?titulo=compras");
        assert_eq!(uri(&consulta("c")[0]), "pindesk://atajo/capturar");
        assert_eq!(uri(&consulta("Lección")[0]), "pindesk://atajo/leccion");
        // Sin texto: abrir el chat (sin `?texto=`).
        assert_eq!(uri(&consulta("chat")[0]), "pindesk://atajo/chat");
    }

    #[test]
    fn otra_cosa_se_busca_primero() {
        let r = consulta("tesis capítulo 2");
        let r = r.as_array().unwrap();
        let mejor = r.iter().max_by_key(|x| x["score"].as_i64()).unwrap();
        assert_eq!(uri(mejor), "pindesk://atajo/buscar?texto=tesis%20cap%C3%ADtulo%202");
        assert!(r.iter().any(|x| uri(x).starts_with("pindesk://atajo/tarea?texto=")));
    }

    #[test]
    fn el_comienzo_de_una_funcion_la_sugiere() {
        let r = consulta("grab");
        assert!(r.as_array().unwrap().iter().any(|x| uri(x) == "pindesk://atajo/grabar"));
    }

    #[test]
    fn v1_igual() {
        let r = v1(r#"{"method":"query","parameters":["t pan"]}"#);
        assert_eq!(uri(&r["result"][0]), "pindesk://atajo/tarea?texto=pan");
    }
}
