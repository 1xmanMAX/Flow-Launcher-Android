//! Hablar con el programa de un plugin.
//!
//! - [`ProcesoV2`]: se queda vivo; JSON-RPC 2.0 por stdin/stdout, un mensaje
//!   por linea. Un hilo lee lo que llega: las respuestas despiertan a quien
//!   espera su `id`; las peticiones del plugin (`ChangeQuery`, `ShowMsg`...)
//!   se contestan enseguida y se guardan como eventos para la app.
//! - [`una_vez`]: el modo v1, un proceso por peticion con el JSON como
//!   argumento y la respuesta entera por stdout.

use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::path::Path;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub type Eventos = Arc<Mutex<Vec<Value>>>;

/// Lo que el plugin le pide a la app, sin el prefijo de Flow:
/// `{"metodo": "ChangeQuery", "params": [...]}`.
pub fn evento(metodo: &str, params: Value) -> Value {
    let m = metodo.strip_prefix("Flow.Launcher.").unwrap_or(metodo);
    json!({ "metodo": m, "params": params })
}

/// El nombre de un metodo de la API de Flow, sin prefijo ni `Async`.
fn base(metodo: &str) -> &str {
    let m = metodo.strip_prefix("Flow.Launcher.").unwrap_or(metodo);
    m.strip_suffix("Async").unwrap_or(m)
}

/// Lo que se le pasa a la app (lo que cambia algo que se ve).
fn es_evento(metodo: &str) -> bool {
    matches!(
        base(metodo),
        "ChangeQuery" | "CopyToClipboard" | "ShowMsg" | "ShowMsgError" | "OpenUrl" | "OpenWebUrl" | "OpenUri"
            | "OpenAppUri" | "OpenDirectory" | "HideMainWindow" | "ShowMainWindow" | "BackToQueryResults"
            | "StartLoadingBar" | "StopLoadingBar" | "UpdateResults"
    )
}

/// La respuesta a lo que el plugin le pide a Flow (`JsonRPCPublicAPI` de
/// Flow). Lo que no se conoce, error -32601 como hace Flow.
pub fn responder_api(metodo: &str, params: &Value) -> Result<Value, String> {
    let primero = || match params {
        Value::Array(a) => a.first().cloned().unwrap_or(Value::Null),
        otro => otro.clone(),
    };
    match base(metodo) {
        m if es_evento(m) => Ok(Value::Null),
        "IsMainWindowVisible" => Ok(json!(true)),
        "ActionKeywordAssigned" => Ok(json!(false)),
        "OpenPluginSettingsWindow" => Ok(json!(false)),
        // Sin traducciones: la clave misma.
        "GetTranslation" => Ok(primero()),
        "LogDebug" | "LogInfo" | "LogWarn" | "LogError" | "LogException" | "SavePluginSettings"
        | "SavePluginCaches" | "SaveAppAllSettings" | "ReloadAllPluginData" | "AddActionKeyword"
        | "RemoveActionKeyword" | "CheckForNewUpdate" => Ok(Value::Null),
        "GetAllPlugins" => Ok(json!([])),
        otro => Err(format!("metodo no disponible en Android: {otro}")),
    }
}

pub struct ProcesoV2 {
    entrada: Mutex<ChildStdin>,
    hijo: Mutex<Child>,
    pendientes: Arc<Mutex<HashMap<u64, Sender<Value>>>>,
    siguiente: AtomicU64,
    vivo: Arc<AtomicBool>,
}

impl ProcesoV2 {
    pub fn lanzar(exe: &Path, dir: &Path, entorno: &[(String, String)], eventos: Eventos) -> std::io::Result<Arc<ProcesoV2>> {
        let mut hijo = Command::new(exe)
            .current_dir(dir)
            .envs(entorno.iter().map(|(k, v)| (k, v)))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()?;
        let entrada = hijo.stdin.take().expect("stdin");
        let salida = hijo.stdout.take().expect("stdout");
        let errores = hijo.stderr.take().expect("stderr");
        let p = Arc::new(ProcesoV2 {
            entrada: Mutex::new(entrada),
            hijo: Mutex::new(hijo),
            pendientes: Arc::new(Mutex::new(HashMap::new())),
            siguiente: AtomicU64::new(1),
            vivo: Arc::new(AtomicBool::new(true)),
        });

        // Lo que llega del plugin.
        let pendientes = p.pendientes.clone();
        let vivo = p.vivo.clone();
        let debil = Arc::downgrade(&p);
        std::thread::Builder::new()
            .name("plugin-lector".into())
            .spawn(move || {
                for linea in BufReader::new(salida).lines() {
                    let Ok(linea) = linea else { break };
                    let Ok(msg) = serde_json::from_str::<Value>(linea.trim()) else { continue };
                    if let Some(metodo) = msg.get("method").and_then(Value::as_str) {
                        let params = msg.get("params").cloned().unwrap_or(Value::Null);
                        let respuesta = responder_api(metodo, &params);
                        if respuesta.is_ok() && es_evento(metodo) {
                            if let Ok(mut e) = eventos.lock() {
                                if e.len() < 100 {
                                    e.push(evento(metodo, params));
                                }
                            }
                        }
                        // Una peticion con id espera respuesta: se le da ya.
                        if let (Some(id), Some(p)) = (msg.get("id").filter(|i| !i.is_null()), debil.upgrade()) {
                            p.escribir(&match respuesta {
                                Ok(r) => json!({ "jsonrpc": "2.0", "id": id, "result": r }),
                                Err(m) => json!({ "jsonrpc": "2.0", "id": id, "error": { "code": -32601, "message": m } }),
                            });
                        }
                    } else if let Some(id) = msg.get("id").and_then(Value::as_u64) {
                        if let Some(tx) = pendientes.lock().ok().and_then(|mut m| m.remove(&id)) {
                            let r = match msg.get("error") {
                                Some(e) => json!({ "__error": e }),
                                None => msg.get("result").cloned().unwrap_or(Value::Null),
                            };
                            let _ = tx.send(r);
                        }
                    }
                }
                vivo.store(false, Ordering::SeqCst);
                // Que nadie se quede esperando a un proceso muerto.
                if let Ok(mut m) = pendientes.lock() {
                    m.clear();
                }
            })?;

        // stderr hay que vaciarlo o el plugin se bloquea al llenarse la
        // tuberia. Flow lo trata como un fallo; aqui solo se descarta.
        std::thread::Builder::new()
            .name("plugin-stderr".into())
            .spawn(move || {
                let mut b = [0u8; 4096];
                let mut e = errores;
                while matches!(e.read(&mut b), Ok(n) if n > 0) {}
            })?;
        Ok(p)
    }

    pub fn vivo(&self) -> bool {
        self.vivo.load(Ordering::SeqCst)
    }

    fn escribir(&self, v: &Value) -> bool {
        let mut linea = v.to_string();
        linea.push('\n');
        let Ok(mut e) = self.entrada.lock() else { return false };
        e.write_all(linea.as_bytes()).and_then(|_| e.flush()).is_ok()
    }

    /// Una peticion y su respuesta, esperando como mucho `espera`. Si no
    /// llega a tiempo se le avisa al plugin (`$/cancelRequest`) y `None`.
    pub fn llamar(&self, metodo: &str, params: Value, espera: Duration) -> Option<Value> {
        if !self.vivo() {
            return None;
        }
        let id = self.siguiente.fetch_add(1, Ordering::SeqCst);
        let (tx, rx) = mpsc::channel();
        self.pendientes.lock().ok()?.insert(id, tx);
        if !self.escribir(&json!({ "jsonrpc": "2.0", "id": id, "method": metodo, "params": params })) {
            self.pendientes.lock().ok()?.remove(&id);
            return None;
        }
        match rx.recv_timeout(espera) {
            Ok(v) if v.get("__error").is_some() => None,
            Ok(v) => Some(v),
            Err(_) => {
                if let Ok(mut m) = self.pendientes.lock() {
                    m.remove(&id);
                }
                self.notificar("$/cancelRequest", json!({ "id": id }));
                None
            }
        }
    }

    pub fn notificar(&self, metodo: &str, params: Value) {
        self.escribir(&json!({ "jsonrpc": "2.0", "method": metodo, "params": params }));
    }

    pub fn cerrar(&self) {
        // Flow lo manda como peticion y espera un poco a que conteste.
        self.llamar("close", json!([]), Duration::from_millis(300));
        if let Ok(mut h) = self.hijo.lock() {
            // Un respiro para que cierre solo; si no, a la fuerza.
            for _ in 0..10 {
                if matches!(h.try_wait(), Ok(Some(_))) {
                    return;
                }
                std::thread::sleep(Duration::from_millis(20));
            }
            let _ = h.kill();
            let _ = h.wait();
        }
    }
}

impl Drop for ProcesoV2 {
    fn drop(&mut self) {
        if let Ok(mut h) = self.hijo.lock() {
            let _ = h.kill();
            let _ = h.wait();
        }
    }
}

/// El modo v1: `programa '<json>'`, y lo que escriba por stdout es la
/// respuesta. Se mata si tarda mas de `espera`.
pub fn una_vez(exe: &Path, dir: &Path, entorno: &[(String, String)], peticion: &Value, espera: Duration) -> Option<Value> {
    let mut hijo = Command::new(exe)
        .arg(peticion.to_string())
        .current_dir(dir)
        .envs(entorno.iter().map(|(k, v)| (k, v)))
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .ok()?;
    let mut salida = hijo.stdout.take()?;
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || {
        let mut s = String::new();
        let _ = salida.read_to_string(&mut s);
        let _ = tx.send(s);
    });
    let texto = match rx.recv_timeout(espera) {
        Ok(t) => t,
        Err(_) => {
            let _ = hijo.kill();
            let _ = hijo.wait();
            return None;
        }
    };
    let _ = hijo.wait();
    let texto = texto.trim();
    if texto.is_empty() {
        return Some(Value::Null);
    }
    // Normalmente un JSON; algunos escriben una linea de registro antes.
    serde_json::from_str(texto)
        .ok()
        .or_else(|| texto.lines().rev().find_map(|l| serde_json::from_str(l.trim()).ok()))
}
