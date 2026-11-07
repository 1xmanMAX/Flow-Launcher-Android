//! Los plugins de Flow Launcher: cargarlos, decidir a cuales va cada
//! consulta, preguntarles en paralelo y entender lo que contestan.

pub mod ajustes;
pub mod manifiesto;
pub mod proceso;

use crate::resultado::Resultado;
use manifiesto::{Lenguaje, Manifiesto};
use proceso::{Eventos, ProcesoV2};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

/// La version de Flow que se anuncia a los plugins (`FLOW_VERSION`): la de
/// la API que se imita.
pub const FLOW_VERSION: &str = "2.0.0";

/// Una clave de un objeto JSON sin importar mayusculas (`Title`, `title`).
pub fn clave<'a>(v: &'a Value, k: &str) -> Option<&'a Value> {
    let o = v.as_object()?;
    o.get(k).or_else(|| o.iter().find(|(c, _)| c.eq_ignore_ascii_case(k)).map(|(_, v)| v))
}

pub struct Plugin {
    pub m: Manifiesto,
    pub exe: Option<PathBuf>,
    dir_ajustes: PathBuf,
    ajustes: ajustes::Ajustes,
    /// Variables de entorno que Flow les pone (`FLOW_VERSION`...).
    entorno: Vec<(String, String)>,
    proceso: Mutex<Option<Arc<ProcesoV2>>>,
}

impl Plugin {
    pub fn compatible(&self) -> bool {
        self.exe.is_some() && !matches!(self.m.lenguaje, Lenguaje::Otro(_))
    }

    pub fn estado(&self) -> String {
        if let Some(e) = self.m.explicacion_no_compatible() {
            return e;
        }
        if self.exe.is_none() {
            return format!("No se encuentra el programa «{}»", self.m.ejecutable);
        }
        if self.m.desactivado {
            return "Desactivado".into();
        }
        "Listo".into()
    }

    fn metadatos(&self) -> Value {
        let m = &self.m;
        json!({
            "id": m.id, "name": m.nombre, "author": m.autor, "version": m.version,
            "language": match &m.lenguaje { Lenguaje::EjecutableV1 => "Executable", Lenguaje::EjecutableV2 => "Executable_V2", Lenguaje::Otro(o) => o.as_str() },
            "description": m.descripcion, "website": m.web, "actionKeywords": m.palabras,
            "actionKeyword": m.palabras.first(),
            "pluginDirectory": m.dir, "pluginSettingsDirectoryPath": self.dir_ajustes,
            "executeFilePath": self.exe, "executeFileName": m.ejecutable, "icoPath": m.icono,
            "disabled": m.desactivado, "homeDisabled": false, "hideActionKeywordPanel": false,
            "searchDelayTime": Value::Null, "minimumAppVersion": "",
            "pluginCacheDirectoryPath": self.dir_ajustes.join("Cache"),
        })
    }

    /// El proceso v2, arrancado (e `initialize`) la primera vez o si murio.
    fn proceso(&self, eventos: &Eventos) -> Option<Arc<ProcesoV2>> {
        let mut p = self.proceso.lock().ok()?;
        if let Some(vivo) = p.as_ref().filter(|p| p.vivo()) {
            return Some(vivo.clone());
        }
        let _ = std::fs::create_dir_all(&self.dir_ajustes);
        let nuevo = ProcesoV2::lanzar(self.exe.as_ref()?, &self.m.dir, &self.entorno, eventos.clone()).ok()?;
        nuevo.llamar(
            "initialize",
            json!([{ "currentPluginMetadata": self.metadatos(), "api": {} }]),
            Duration::from_secs(5),
        );
        *p = Some(nuevo.clone());
        Some(nuevo)
    }

    /// Calienta el proceso antes de la primera tecla.
    pub fn preparar(&self, eventos: &Eventos) {
        if self.m.lenguaje == Lenguaje::EjecutableV2 && self.compatible() {
            self.proceso(eventos);
        }
    }

    /// `peticion_v1`: el JSON que Flow pasa como argumento en el modo v1
    /// (`{"id", "method", "parameters", "settings"}`).
    fn pedir(&self, metodo: &str, params_v2: Value, peticion_v1: Value, espera: Duration, eventos: &Eventos) -> Option<Value> {
        let r = match self.m.lenguaje {
            Lenguaje::EjecutableV2 => self.proceso(eventos)?.llamar(metodo, params_v2, espera),
            Lenguaje::EjecutableV1 => {
                let r = proceso::una_vez(self.exe.as_ref()?, &self.m.dir, &self.entorno, &peticion_v1, espera)?;
                // En v1 lo que el plugin le pide a Flow viene como respuesta:
                // `{"method": "Flow.Launcher.ChangeQuery", "parameters": [...]}`.
                if let Some(m) = clave(&r, "method").and_then(Value::as_str) {
                    let params = clave(&r, "parameters").cloned().unwrap_or(Value::Null);
                    if let Ok(mut e) = eventos.lock() {
                        e.push(proceso::evento(m, params));
                    }
                }
                Some(r)
            }
            Lenguaje::Otro(_) => None,
        }?;
        // Lo que Flow mira en cualquier respuesta: ajustes nuevos y un mensaje
        // de depuracion para ensenar.
        if let Some(c) = clave(&r, "settingsChange") {
            self.ajustes.cambiar(c);
        }
        if let Some(d) = clave(&r, "debugMessage").and_then(Value::as_str).filter(|d| !d.is_empty()) {
            if let Ok(mut e) = eventos.lock() {
                e.push(proceso::evento("ShowMsg", json!([self.m.nombre, d, ""])));
            }
        }
        Some(r)
    }

    fn v1(&self, metodo: &str, parametros: Value) -> Value {
        json!({ "id": 1, "method": metodo, "parameters": parametros, "settings": self.ajustes.json() })
    }

    pub fn consultar(&self, crudo: &str, palabra: &str, busqueda: &str, espera: Duration, eventos: &Eventos) -> Vec<Resultado> {
        let recortada = crudo.trim();
        let q = json!({
            "originalQuery": crudo, "rawQuery": recortada, "trimmedQuery": recortada,
            "search": busqueda, "actionKeyword": palabra,
            "searchTerms": busqueda.split_whitespace().collect::<Vec<_>>(),
            "isReQuery": false, "isHomeQuery": false,
        });
        let peticion_v1 = self.v1("query", json!([busqueda]));
        let Some(r) = self.pedir("query", json!([q, self.ajustes.json()]), peticion_v1, espera, eventos) else {
            return Vec::new();
        };
        self.leer_resultados(&r, palabra == "*" || palabra.is_empty())
    }

    pub fn menu(&self, contexto: &Value, espera: Duration, eventos: &Eventos) -> Vec<Resultado> {
        let mut v1 = self.v1("context_menu", json!([contexto]));
        v1["settings"] = Value::Null;
        let r = self.pedir("context_menu", json!([contexto]), v1, espera, eventos);
        r.map(|r| self.leer_resultados(&r, false)).unwrap_or_default()
    }

    /// Ejecutar una `jsonRPCAction`. Devuelve si hay que cerrar el buscador.
    pub fn ejecutar(&self, accion: &Value, espera: Duration, eventos: &Eventos) -> bool {
        let Some(metodo) = clave(accion, "method").and_then(Value::as_str) else { return true };
        let parametros = clave(accion, "parameters").cloned().unwrap_or(json!([]));
        // Flow tambien deja que la accion sea directamente una suya.
        if metodo.starts_with("Flow.Launcher.") {
            if let Ok(mut e) = eventos.lock() {
                e.push(proceso::evento(metodo, parametros));
            }
            return true;
        }
        // En v1 Flow manda la accion tal cual (con sus `settings`).
        let mut v1 = accion.clone();
        if clave(&v1, "settings").is_none() {
            v1["settings"] = self.ajustes.json();
        }
        let r = self.pedir(metodo, json!([parametros]), v1, espera, eventos);
        let respuesta = r.as_ref().and_then(|r| clave(r, "hide")).and_then(Value::as_bool);
        match self.m.lenguaje {
            // V2: Flow solo mira `hide`, y por defecto cierra.
            Lenguaje::EjecutableV2 => respuesta.unwrap_or(true),
            _ => respuesta.unwrap_or(!clave(accion, "dontHideAfterAction").and_then(Value::as_bool).unwrap_or(false)),
        }
    }

    /// `{"result": [...]}` o directamente la lista; claves en camelCase o
    /// PascalCase.
    fn leer_resultados(&self, r: &Value, global: bool) -> Vec<Resultado> {
        let lista = match r {
            Value::Array(a) => a,
            otro => match clave(otro, "result") {
                Some(Value::Array(a)) => a,
                _ => return Vec::new(),
            },
        };
        let icono_plugin = self.m.icono.as_ref().map(|p| p.to_string_lossy().to_string()).unwrap_or_default();
        lista
            .iter()
            .filter(|x| x.is_object())
            .map(|x| {
                let s = |k: &str| clave(x, k).and_then(Value::as_str).unwrap_or("").to_string();
                let icono = match s("IcoPath") {
                    i if i.is_empty() => icono_plugin.clone(),
                    i if i.starts_with("http") || Path::new(&i).is_absolute() => i,
                    i => self.m.dir.join(i.replace('\\', "/")).to_string_lossy().to_string(),
                };
                let score = clave(x, "Score").and_then(Value::as_i64).unwrap_or(0).clamp(-100_000, 100_000) as i32;
                let resaltado = clave(x, "TitleHighlightData")
                    .and_then(Value::as_array)
                    .map(|a| a.iter().filter_map(Value::as_u64).map(|n| n as u16).collect())
                    .unwrap_or_default();
                let opcional = |k: &str| Some(s(k)).filter(|t| !t.is_empty());
                Resultado {
                    titulo: s("Title"),
                    subtitulo: s("SubTitle"),
                    icono,
                    // Los globales, por debajo de lo que sale del indice si
                    // no estan muy seguros.
                    puntos: if global { score.min(900) } else { score + 2000 },
                    accion: format!("plugin:{}", self.m.id),
                    fuente: format!("plugin:{}", self.m.id),
                    resaltado,
                    plugin: clave(x, "JsonRPCAction").filter(|a| a.is_object()).cloned(),
                    contexto: clave(x, "ContextData").filter(|c| !c.is_null()).cloned(),
                    autocompletar: opcional("AutoCompleteText"),
                    copiar: opcional("CopyText"),
                }
            })
            .collect()
    }

    pub fn cerrar(&self) {
        if let Some(p) = self.proceso.lock().ok().and_then(|mut p| p.take()) {
            p.cerrar();
        }
    }
}

#[derive(Default)]
pub struct Host {
    plugins: RwLock<Vec<Arc<Plugin>>>,
    pub eventos: Eventos,
    dir_libs: Option<PathBuf>,
    dir_ajustes: PathBuf,
}

/// A que plugins va una consulta y con que.
pub struct Destino {
    pub plugin: Arc<Plugin>,
    pub palabra: String,
    pub busqueda: String,
}

impl Host {
    pub fn nuevo(dir_ajustes: PathBuf, dir_libs: Option<PathBuf>) -> Self {
        Host { dir_libs, dir_ajustes, ..Default::default() }
    }

    /// Carga cada subcarpeta con `plugin.json`. Devuelve los errores.
    pub fn cargar_carpeta(&self, raiz: &Path) -> Vec<String> {
        let mut errores = Vec::new();
        let Ok(dirs) = std::fs::read_dir(raiz) else { return errores };
        let mut dirs: Vec<PathBuf> = dirs.flatten().map(|d| d.path()).filter(|p| p.join("plugin.json").is_file()).collect();
        dirs.sort();
        for d in dirs {
            if let Err(e) = self.cargar(&d) {
                errores.push(e);
            }
        }
        errores
    }

    pub fn cargar(&self, dir: &Path) -> Result<(), String> {
        let m = Manifiesto::leer(dir)?;
        let exe = m.ruta_ejecutable(self.dir_libs.as_deref());
        // Como Flow: la carpeta de ajustes lleva el nombre del plugin.
        let nombre: String = m.nombre.chars().filter(|c| c.is_alphanumeric() || " -_.".contains(*c)).collect();
        let dir_ajustes = self.dir_ajustes.join(if nombre.trim().is_empty() { m.id.as_str() } else { nombre.trim() });
        let ajustes = ajustes::Ajustes::cargar(&m.dir, &dir_ajustes);
        let datos = self.dir_ajustes.parent().unwrap_or(&self.dir_ajustes).to_string_lossy().to_string();
        let entorno = vec![
            ("FLOW_VERSION".to_string(), FLOW_VERSION.to_string()),
            ("FLOW_PROGRAM_DIRECTORY".to_string(), datos.clone()),
            ("FLOW_APPLICATION_DIRECTORY".to_string(), datos),
        ];
        let p = Arc::new(Plugin { m, exe, dir_ajustes, ajustes, entorno, proceso: Mutex::new(None) });
        let mut lista = self.plugins.write().map_err(|_| "bloqueo roto")?;
        if let Some(viejo) = lista.iter().position(|x| x.m.id == p.m.id) {
            lista.remove(viejo).cerrar();
        }
        lista.push(p);
        Ok(())
    }

    pub fn lista(&self) -> Vec<Arc<Plugin>> {
        self.plugins.read().map(|p| p.clone()).unwrap_or_default()
    }

    pub fn buscar_id(&self, id: &str) -> Option<Arc<Plugin>> {
        self.lista().into_iter().find(|p| p.m.id == id)
    }

    /// Los plugins con palabra clave (`p texto`) mandan solos; si no hay
    /// ninguno, la consulta entera va a los globales (`*`).
    pub fn destinos(&self, crudo: &str) -> Vec<Destino> {
        let crudo = crudo.trim_start();
        let activos: Vec<Arc<Plugin>> = self.lista().into_iter().filter(|p| p.compatible() && !p.m.desactivado).collect();
        // La palabra clave solo cuenta seguida de un espacio (`p `), como en Flow.
        let (primera, resto) = crudo.split_once(' ').unwrap_or(("", ""));
        let mut d: Vec<Destino> = activos
            .iter()
            .filter_map(|p| {
                let palabra = p.m.palabras.iter().find(|k| !primera.is_empty() && *k != "*" && k.eq_ignore_ascii_case(primera))?;
                Some(Destino { plugin: p.clone(), palabra: palabra.clone(), busqueda: resto.to_string() })
            })
            .collect();
        if d.is_empty() && !crudo.trim().is_empty() {
            d = activos
                .iter()
                .filter(|p| p.m.es_global())
                .map(|p| Destino { plugin: p.clone(), palabra: String::new(), busqueda: crudo.to_string() })
                .collect();
        }
        d
    }

    pub fn tiene_palabra(&self, primera: &str) -> bool {
        self.lista().iter().any(|p| p.compatible() && p.m.palabras.iter().any(|k| k != "*" && k.eq_ignore_ascii_case(primera)))
    }

    /// Pregunta a todos los destinos a la vez. Ninguno espera mas de
    /// `espera`: el lento se queda fuera, no frena a los demas.
    pub fn consultar(&self, crudo: &str, espera: Duration) -> Vec<Resultado> {
        let destinos = self.destinos(crudo);
        if destinos.is_empty() {
            return Vec::new();
        }
        let crudo = crudo.trim_start();
        let mut todos = Vec::new();
        std::thread::scope(|s| {
            let hilos: Vec<_> = destinos
                .iter()
                .map(|d| s.spawn(|| d.plugin.consultar(crudo, &d.palabra, &d.busqueda, espera, &self.eventos)))
                .collect();
            for h in hilos {
                if let Ok(r) = h.join() {
                    todos.extend(r);
                }
            }
        });
        todos
    }

    pub fn tomar_eventos(&self) -> Vec<Value> {
        self.eventos.lock().map(|mut e| std::mem::take(&mut *e)).unwrap_or_default()
    }

    pub fn preparar(&self) {
        for p in self.lista() {
            p.preparar(&self.eventos);
        }
    }

    pub fn cerrar(&self) {
        for p in self.lista() {
            p.cerrar();
        }
    }
}
