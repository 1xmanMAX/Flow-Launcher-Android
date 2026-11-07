//! **Texto** (`tx`): plugin de ejemplo con el protocolo de Flow Launcher.
//! Funciona igual en Flow (Windows) y en el buscador de Android.
//!
//! - Con un JSON como argumento: modo `Executable` (v1), una peticion por
//!   proceso y la respuesta por stdout.
//! - Sin argumentos: modo `Executable_V2`, JSON-RPC 2.0 por stdin/stdout, un
//!   mensaje por linea, y se queda vivo.
//!
//! `tx Hola Mundo` -> MAYUSCULAS, minusculas, Titulo, al reves, Base64 y la
//! cuenta de letras y palabras. Elegir uno lo copia.

use serde_json::{json, Value};
use std::io::{BufRead, Write};

fn titulo(s: &str) -> String {
    s.split(' ')
        .map(|p| {
            let mut c = p.chars();
            match c.next() {
                Some(f) => f.to_uppercase().collect::<String>() + &c.as_str().to_lowercase(),
                None => String::new(),
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn base64(datos: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut s = String::with_capacity(datos.len().div_ceil(3) * 4);
    for b in datos.chunks(3) {
        let n = (b[0] as u32) << 16 | (*b.get(1).unwrap_or(&0) as u32) << 8 | *b.get(2).unwrap_or(&0) as u32;
        for i in 0..4 {
            if i <= b.len() {
                s.push(T[(n >> (18 - 6 * i) & 63) as usize] as char);
            } else {
                s.push('=');
            }
        }
    }
    s
}

fn resultado(titulo: &str, subtitulo: &str, copiar: &str, puntos: i64) -> Value {
    json!({
        "title": titulo,
        "subTitle": subtitulo,
        "icoPath": "Images/texto.png",
        "score": puntos,
        "copyText": copiar,
        "contextData": copiar,
        "jsonRPCAction": { "method": "copiar", "parameters": [copiar], "dontHideAfterAction": false },
    })
}

fn consulta(busqueda: &str) -> Value {
    let s = busqueda.trim();
    if s.is_empty() {
        return json!([{
            "title": "Escribe un texto para transformarlo",
            "subTitle": "tx Hola Mundo → MAYÚSCULAS, minúsculas, Título, Base64…",
            "icoPath": "Images/texto.png", "score": 0,
        }]);
    }
    let alreves: String = s.chars().rev().collect();
    let cuenta = format!("{} caracteres · {} palabras", s.chars().count(), s.split_whitespace().count());
    json!([
        resultado(&s.to_uppercase(), "MAYÚSCULAS · toca para copiar", &s.to_uppercase(), 100),
        resultado(&s.to_lowercase(), "minúsculas · toca para copiar", &s.to_lowercase(), 90),
        resultado(&titulo(s), "Título · toca para copiar", &titulo(s), 80),
        resultado(&alreves, "Al revés · toca para copiar", &alreves, 70),
        resultado(&base64(s.as_bytes()), "Base64 · toca para copiar", &base64(s.as_bytes()), 60),
        resultado(&cuenta, "Cuenta · toca para copiar", &cuenta, 50),
    ])
}

fn menu(contexto: &Value) -> Value {
    let t = contexto.as_str().unwrap_or("");
    json!([{
        "title": format!("Buscar «{t}» en Google"),
        "subTitle": "Cambia la consulta a «g …»",
        "icoPath": "Images/texto.png", "score": 0,
        "jsonRPCAction": { "method": "Flow.Launcher.ChangeQuery", "parameters": [format!("g {t}"), true] },
    }])
}

/// Flow manda los parametros de una accion envueltos (`[[p1]]`) en v2.
fn argumentos(params: &Value) -> Vec<Value> {
    match params {
        Value::Array(a) if a.len() == 1 && a[0].is_array() => a[0].as_array().cloned().unwrap_or_default(),
        Value::Array(a) => a.clone(),
        _ => Vec::new(),
    }
}

fn primero(params: &Value) -> Value {
    match params {
        Value::Array(a) => a.first().cloned().unwrap_or(Value::Null),
        otro => otro.clone(),
    }
}

fn v1(peticion: &str) -> Value {
    let Ok(p) = serde_json::from_str::<Value>(peticion) else { return json!({}) };
    let metodo = p.get("method").and_then(Value::as_str).unwrap_or("");
    let params = p.get("parameters").cloned().unwrap_or(json!([]));
    match metodo {
        "query" => json!({ "result": consulta(primero(&params).as_str().unwrap_or("")) }),
        "context_menu" => json!({ "result": menu(&primero(&params)) }),
        "copiar" => {
            let t = argumentos(&params).first().and_then(Value::as_str).unwrap_or("").to_string();
            json!({ "method": "Flow.Launcher.CopyToClipboard", "parameters": [t, false, true] })
        }
        _ => json!({}),
    }
}

fn v2() {
    let mut salida = std::io::stdout().lock();
    let mut escribir = |v: Value| {
        let _ = writeln!(salida, "{v}");
        let _ = salida.flush();
    };
    let mut siguiente = 1_000_000u64;
    for linea in std::io::stdin().lock().lines() {
        let Ok(linea) = linea else { break };
        let Ok(msg) = serde_json::from_str::<Value>(linea.trim()) else { continue };
        // Las respuestas a lo que le pedimos al host no llevan `method`.
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
            "copiar" => {
                let t = argumentos(&params).first().and_then(Value::as_str).unwrap_or("").to_string();
                escribir(json!({ "jsonrpc": "2.0", "id": siguiente, "method": "CopyToClipboard", "params": [t, false, true] }));
                siguiente += 1;
                json!({ "hide": true })
            }
            "close" => break,
            _ => Value::Null,
        };
        if let Some(id) = id {
            escribir(json!({ "jsonrpc": "2.0", "id": id, "result": resultado }));
        }
    }
}

fn main() {
    match std::env::args().nth(1) {
        Some(p) if p.trim_start().starts_with('{') => {
            print!("{}", v1(&p));
        }
        _ => v2(),
    }
}

#[cfg(test)]
mod pruebas {
    use super::*;

    #[test]
    fn base64_correcto() {
        assert_eq!(base64(b"Hola"), "SG9sYQ==");
        assert_eq!(base64(b"abc"), "YWJj");
        assert_eq!(base64("ñ".as_bytes()), "w7E=");
    }

    #[test]
    fn titulo_correcto() {
        assert_eq!(titulo("hOLA mUNDO"), "Hola Mundo");
    }

    #[test]
    fn v1_copia_pidiendoselo_a_flow() {
        let r = v1(r#"{"method":"copiar","parameters":["x"]}"#);
        assert_eq!(r["method"], "Flow.Launcher.CopyToClipboard");
    }
}
