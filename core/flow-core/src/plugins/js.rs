//! Plugins de JavaScript, dentro del propio nucleo con QuickJS (sin Node,
//! sin procesos: en Android no se puede lanzar un `node`).
//!
//! # La API del plugin
//!
//! Es la del JSON-RPC de Flow pasada a funciones, para que portar un plugin
//! sea cambiar el `stdout` por un `return`:
//!
//! ```js
//! // Obligatoria. query = { search, rawQuery, actionKeyword, searchTerms, isReQuery }
//! function query(query) {
//!   return [{
//!     title: "Hola " + query.search, subTitle: "Toca para copiarlo",
//!     icoPath: "Images/icono.png", score: 100,
//!     jsonRPCAction: { method: "copiar", parameters: ["Hola"] },
//!     contextData: "Hola",
//!   }];
//! }
//! // Opcional: el menu contextual de un resultado (recibe su contextData).
//! function context_menu(data) { return [ /* resultados */ ]; }
//! // Las acciones: el `method` de la jsonRPCAction, con `parameters` como
//! // argumentos. Devuelve `false` (o `{hide: false}`) para no cerrar.
//! function copiar(texto) { Flow.copyToClipboard(texto); }
//! // Opcional, una vez al cargar: { currentPluginMetadata, api }.
//! function initialize(context) {}
//! ```
//!
//! Las funciones pueden ser globales o ir en `module.exports` (CommonJS). Lo
//! devuelto puede ser la lista o `{result: [...]}`, en camelCase o PascalCase
//! (`Title`, `SubTitle`...), y tambien una promesa (`async function`).
//!
//! El objeto global `Flow`:
//! - `Flow.changeQuery(q, requery)`, `Flow.showMsg(titulo, sub, icono)`,
//!   `Flow.copyToClipboard(texto)`, `Flow.openUrl(url)`,
//!   `Flow.hideMainWindow()`: lo mismo que `Flow.Launcher.ChangeQuery`...
//!   en un plugin ejecutable (la app recibe los mismos eventos).
//! - `Flow.call(metodo, ...params)`: cualquier otro metodo de la API de Flow.
//! - `Flow.storage.get(clave)`, `.set(clave, valor)`, `.remove(clave)`,
//!   `.keys()`: datos del plugin (cualquier cosa que quepa en JSON, hasta
//!   1 MB en total) en `storage.json` de su carpeta de ajustes.
//! - `Flow.metadata`: el `currentPluginMetadata` (id, nombre, carpetas...).
//! - `console.log/info/warn/error`: no hacen nada (no hay consola).
//!
//! No hay red ni ficheros ni temporizadores (`setTimeout`). Una peticion
//! HTTP (`Flow.http`) queda para mas adelante: pediria meter TLS en el
//! nucleo.
//!
//! # Limites
//!
//! Un motor (`Runtime`) por plugin, creado la primera vez que se usa y
//! reutilizado. Como mucho [`MEMORIA`] de memoria y [`PILA`] de pila; cada
//! llamada se corta al pasar su tiempo (un `while (true) {}` no cuelga
//! nada). Las llamadas al mismo plugin van de una en una (un `Mutex`); a
//! plugins distintos, en paralelo.

use super::proceso::{evento, Eventos};
use crate::historial::escribir_seguro;
use rquickjs::context::{intrinsic, EvalOptions};
use rquickjs::{CatchResultExt, Context, Function, Object, Persistent, Runtime};
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, TryLockError};
use std::time::{Duration, Instant};

/// Memoria maxima de un plugin (su motor entero).
pub const MEMORIA: usize = 16 << 20;
/// Pila maxima de JavaScript (los hilos que llaman tienen bastante mas).
pub const PILA: usize = 256 << 10;
/// Tiempo para leer el script y su `initialize`.
pub const CARGA: Duration = Duration::from_secs(2);
const MAX_ALMACEN: usize = 1 << 20;
const MAX_EVENTOS: usize = 100;

/// Lo que trae el lenguaje, ademas de lo basico (Object, Array, String,
/// Math, BigInt...). Fuera Proxy, WeakRef, `performance` y DOMException:
/// un plugin no los necesita y asi QuickJS ocupa menos.
type Intrinsecos = (
    intrinsic::Date,
    intrinsic::Eval,
    intrinsic::RegExpCompiler,
    intrinsic::RegExp,
    intrinsic::Json,
    intrinsic::MapSet,
    intrinsic::TypedArrays,
    intrinsic::Promise,
);

/// Lo que ve el script: `Flow`, `module`, `console`. Devuelve las dos
/// funciones con las que el nucleo le llama, fuera del alcance del script.
const PRELUDIO: &str = r#"(function (evento, guardar, almacen, metadatos) {
  'use strict';
  var g = globalThis, pendiente = null;
  var datos = {};
  try { datos = JSON.parse(almacen) || {}; } catch (e) {}
  function pedir(m) { evento(m, JSON.stringify(Array.prototype.slice.call(arguments, 1))); }
  var nada = function () {};
  g.console = { log: nada, info: nada, warn: nada, error: nada, debug: nada };
  g.module = { exports: {} };
  g.exports = g.module.exports;
  g.Flow = Object.freeze({
    metadata: JSON.parse(metadatos),
    changeQuery: function (q, requery) { pedir('ChangeQuery', String(q), !!requery); },
    showMsg: function (t, s, i) { pedir('ShowMsg', String(t), s == null ? '' : String(s), i == null ? '' : String(i)); },
    copyToClipboard: function (t) { pedir('CopyToClipboard', String(t)); },
    openUrl: function (u) { pedir('OpenUrl', String(u)); },
    hideMainWindow: function () { pedir('HideMainWindow'); },
    call: function (m) { pedir.apply(null, arguments); },
    storage: Object.freeze({
      get: function (k) { var v = datos[k]; return v === undefined ? undefined : JSON.parse(JSON.stringify(v)); },
      set: function (k, v) { datos[String(k)] = v === undefined ? null : JSON.parse(JSON.stringify(v)); return guardar(JSON.stringify(datos)); },
      remove: function (k) { delete datos[k]; return guardar(JSON.stringify(datos)); },
      keys: function () { return Object.keys(datos); },
    }),
  });
  function json(r) { return JSON.stringify(r === undefined ? null : r); }
  return {
    // Cadena JSON con lo devuelto, null si no existe la funcion, o true si
    // devolvio una promesa (el nucleo vacia la cola y llama a `recoger`).
    llamar: function (nombre, args) {
      var e = g.module && g.module.exports, f = e && e[nombre], yo = e;
      if (typeof f !== 'function') { f = g[nombre]; yo = g; }
      if (typeof f !== 'function') return null;
      var r = f.apply(yo, JSON.parse(args));
      if (r && typeof r.then === 'function') {
        var p = pendiente = { listo: false };
        r.then(function (v) { p.listo = true; p.v = v; }, function () { p.listo = true; p.error = true; });
        return true;
      }
      return json(r);
    },
    recoger: function () {
      var p = pendiente; pendiente = null;
      return p && p.listo && !p.error ? json(p.v) : null;
    },
  };
})"#;

/// Un plugin de JavaScript: su motor, sin crear hasta que haga falta.
pub struct Js {
    archivo: PathBuf,
    dir_ajustes: PathBuf,
    estado: Mutex<Estado>,
}

enum Estado {
    SinCargar,
    Listo(Box<Motor>),
    /// No se pudo cargar (error en el script, se paso de tiempo o de
    /// memoria): no se reintenta hasta recargar el plugin.
    Roto(String),
}

/// El motor de un plugin.
///
/// Los campos se sueltan en orden: las funciones guardadas antes que el
/// contexto, y el contexto antes que el `Runtime`.
struct Motor {
    llamar: Persistent<Function<'static>>,
    recoger: Persistent<Function<'static>>,
    ctx: Context,
    rt: Runtime,
    /// Cuando se corta lo que este corriendo (ns desde `inicio`; `u64::MAX`
    /// = nunca).
    limite: Arc<AtomicU64>,
    inicio: Instant,
    /// Donde dejan sus eventos las funciones de `Flow` durante una llamada.
    eventos: Arc<Mutex<Option<Eventos>>>,
}

// SAFETY: sin la opcion `parallel`, rquickjs usa `Rc` dentro y no es `Send`.
// Todo lo que toca el motor (Runtime, Context, valores guardados y las
// funciones nativas, que solo capturan `Arc`) vive dentro de `Motor`, y a
// `Motor` solo se llega con el `Mutex` de `Js` cogido: nunca lo usan dos
// hilos a la vez, solo pasa de uno a otro. QuickJS no esta atado a un hilo
// (el tope de la pila se actualiza al entrar: ver `pila_de_este_hilo`).
unsafe impl Send for Motor {}

impl Js {
    pub fn nuevo(archivo: PathBuf, dir_ajustes: PathBuf) -> Js {
        Js { archivo, dir_ajustes, estado: Mutex::new(Estado::SinCargar) }
    }

    /// Por que no funciona, si no funciona. No espera a una llamada en
    /// curso.
    pub fn error(&self) -> Option<String> {
        match self.estado.try_lock() {
            Ok(e) => match &*e {
                Estado::Roto(m) => Some(m.clone()),
                _ => None,
            },
            Err(_) => None,
        }
    }

    /// Crear el motor ya (y leer el script), para que la primera consulta
    /// no lo pague.
    pub fn preparar(&self, metadatos: &dyn Fn() -> Value, eventos: &Eventos) {
        if let Some(mut e) = self.coger(Instant::now() + CARGA) {
            self.listo(&mut e, metadatos, eventos);
        }
    }

    /// Llama a `metodo(...args)` del script. `None` si no existe, falla, o
    /// no termina en `espera` (contando lo que espere a otra llamada al
    /// mismo plugin).
    pub fn llamar(&self, metodo: &str, args: &Value, espera: Duration, metadatos: &dyn Fn() -> Value, eventos: &Eventos) -> Option<Value> {
        let fin = Instant::now() + espera;
        let mut e = self.coger(fin)?;
        let m = self.listo(&mut e, metadatos, eventos)?;
        m.llamar(metodo, args, fin, eventos)
    }

    /// El `Mutex`, esperando como mucho hasta `fin`.
    fn coger(&self, fin: Instant) -> Option<MutexGuard<'_, Estado>> {
        loop {
            match self.estado.try_lock() {
                Ok(e) => return Some(e),
                Err(TryLockError::Poisoned(e)) => return Some(e.into_inner()),
                Err(TryLockError::WouldBlock) if Instant::now() >= fin => return None,
                Err(TryLockError::WouldBlock) => std::thread::sleep(Duration::from_millis(1)),
            }
        }
    }

    fn listo<'a>(&self, e: &'a mut Estado, metadatos: &dyn Fn() -> Value, eventos: &Eventos) -> Option<&'a Motor> {
        if matches!(e, Estado::SinCargar) {
            *e = match Motor::cargar(&self.archivo, &self.dir_ajustes, &metadatos(), eventos) {
                Ok(m) => Estado::Listo(Box::new(m)),
                Err(m) => Estado::Roto(m),
            };
        }
        match e {
            Estado::Listo(m) => Some(m),
            _ => None,
        }
    }
}

impl Motor {
    /// QuickJS mide la pila desde donde se le dijo; si la llamada llega por
    /// otro hilo (otra pila), hay que decirselo otra vez o cree que se ha
    /// desbordado. rquickjs solo lo hace con `parallel`.
    fn pila_de_este_hilo(&self) {
        // SAFETY: el Runtime es nuestro y no lo usa nadie mas ahora.
        unsafe { rquickjs::qjs::JS_UpdateStackTop(self.ctx.get_runtime_ptr()) }
    }

    fn cargar(archivo: &Path, dir_ajustes: &Path, metadatos: &Value, eventos: &Eventos) -> Result<Motor, String> {
        let codigo = std::fs::read_to_string(archivo).map_err(|e| format!("{}: {e}", archivo.display()))?;
        let rt = Runtime::new().map_err(|e| e.to_string())?;
        rt.set_memory_limit(MEMORIA);
        rt.set_max_stack_size(PILA);
        let inicio = Instant::now();
        let limite = Arc::new(AtomicU64::new(u64::MAX));
        let l = limite.clone();
        // QuickJS lo llama cada pocos miles de instrucciones.
        rt.set_interrupt_handler(Some(Box::new(move || inicio.elapsed().as_nanos() as u64 > l.load(Ordering::Relaxed))));
        let ctx = Context::custom::<Intrinsecos>(&rt).map_err(|e| e.to_string())?;

        let ranura: Arc<Mutex<Option<Eventos>>> = Arc::new(Mutex::new(Some(eventos.clone())));
        let r = ranura.clone();
        let almacen = dir_ajustes.join("storage.json");
        let dir = dir_ajustes.to_path_buf();
        let inicial = std::fs::read_to_string(&almacen).unwrap_or_else(|_| "{}".into());
        limite.store((inicio.elapsed() + CARGA).as_nanos() as u64, Ordering::Relaxed);

        let funciones = ctx.with(|ctx| -> Result<_, String> {
            let falla = |e: rquickjs::CaughtError| e.to_string();
            let evento_js = Function::new(ctx.clone(), move |metodo: String, params: String| {
                let params = serde_json::from_str(&params).unwrap_or(Value::Null);
                if let Some(ev) = r.lock().ok().and_then(|r| r.clone()) {
                    if let Ok(mut ev) = ev.lock() {
                        if ev.len() < MAX_EVENTOS {
                            ev.push(evento(&metodo, params));
                        }
                    }
                }
            })
            .catch(&ctx)
            .map_err(falla)?;
            let guardar_js = Function::new(ctx.clone(), move |datos: String| -> bool {
                datos.len() <= MAX_ALMACEN
                    && std::fs::create_dir_all(&dir).is_ok()
                    && escribir_seguro(&almacen, datos.as_bytes())
            })
            .catch(&ctx)
            .map_err(falla)?;
            let preludio: Function = ctx.eval(PRELUDIO).catch(&ctx).map_err(falla)?;
            let api: Object = preludio
                .call((evento_js, guardar_js, inicial, metadatos.to_string()))
                .catch(&ctx)
                .map_err(falla)?;
            let llamar: Function = api.get("llamar").catch(&ctx).map_err(falla)?;
            let recoger: Function = api.get("recoger").catch(&ctx).map_err(falla)?;

            let mut opciones = EvalOptions::default();
            opciones.global = true;
            opciones.strict = false;
            opciones.filename = Some(archivo.file_name().unwrap_or_default().to_string_lossy().into_owned());
            ctx.eval_with_options::<(), _>(codigo, opciones).catch(&ctx).map_err(falla)?;
            Ok((Persistent::save(&ctx, llamar), Persistent::save(&ctx, recoger)))
        });
        let (llamar, recoger) = match funciones {
            Ok(f) => f,
            Err(e) => {
                rt.run_gc();
                return Err(format!("Error en el script: {e}"));
            }
        };
        let m = Motor { llamar, recoger, ctx, rt, limite, inicio, eventos: ranura };
        *m.eventos.lock().map_err(|_| "bloqueo roto")? = None;
        // Como el `initialize` de Flow.
        let contexto = serde_json::json!([{ "currentPluginMetadata": metadatos, "api": {} }]);
        m.llamar("initialize", &contexto, Instant::now() + CARGA, eventos);
        Ok(m)
    }

    fn llamar(&self, metodo: &str, args: &Value, fin: Instant, eventos: &Eventos) -> Option<Value> {
        let args = match args {
            Value::Array(_) => args.to_string(),
            otro => Value::Array(vec![otro.clone()]).to_string(),
        };
        let limite = fin.saturating_duration_since(self.inicio).as_nanos() as u64;
        self.limite.store(limite, Ordering::Relaxed);
        if let Ok(mut r) = self.eventos.lock() {
            *r = Some(eventos.clone());
        }
        self.pila_de_este_hilo();

        let mut r = self.ctx.with(|ctx| {
            let f = self.llamar.clone().restore(&ctx).ok()?;
            let v: rquickjs::Value = f.call((metodo, args)).catch(&ctx).ok()?;
            if v.as_bool() == Some(true) {
                return Some(Err(()));
            }
            Some(Ok(v.as_string()?.to_string().ok()?))
        });
        // Una promesa: se vacia la cola de trabajos (sin pasar de `fin`).
        if let Some(Err(())) = r {
            while Instant::now() < fin && matches!(self.rt.execute_pending_job(), Ok(true)) {}
            r = self.ctx.with(|ctx| {
                let f = self.recoger.clone().restore(&ctx).ok()?;
                let v: rquickjs::Value = f.call(()).catch(&ctx).ok()?;
                Some(Ok(v.as_string()?.to_string().ok()?))
            });
        }

        self.limite.store(u64::MAX, Ordering::Relaxed);
        if let Ok(mut r) = self.eventos.lock() {
            *r = None;
        }
        match r {
            Some(Ok(texto)) => serde_json::from_str(&texto).ok(),
            _ => {
                // Puede haberse quedado sin memoria: que recoja lo que pueda.
                self.rt.run_gc();
                None
            }
        }
    }
}
