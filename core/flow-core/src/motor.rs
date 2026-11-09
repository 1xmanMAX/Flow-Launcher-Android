//! El buscador entero: lo escrito entra, los resultados salen.
//!
//! Dos pasos, para que nunca haya espera al teclear:
//! 1. [`Motor::consultar`]: lo local (indice, calculadora, busquedas web,
//!    sugerencias de palabras clave). Sincrono, menos de un milisegundo.
//! 2. [`Motor::consultar_plugins`]: lo de los plugins, en paralelo y con
//!    limite de tiempo. La app lo llama en otro hilo y lo junta al llegar.
//!    Tambien los archivos del movil (`c tesis`, ver [`crate::archivos`]):
//!    pocos ms, pero nunca en el hilo de la interfaz.

use crate::archivos::Archivos;
use crate::busqueda_web::{self, Busqueda};
use crate::calculadora;
use crate::coincidir::puntuar;
use crate::historial::{self, Historial};
use crate::indice::{Elemento, Indice, Tipo};
use crate::normalizar;
use crate::plugins::Host;
use crate::resultado::{ordenar, Resultado};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, RwLock};
use std::time::Duration;

pub struct Motor {
    indice: RwLock<Indice>,
    historial: Mutex<Historial>,
    busquedas: RwLock<Vec<Busqueda>>,
    pub host: Host,
    /// `c <nombre>`: los archivos del almacenamiento.
    pub archivos: Archivos,
    /// La palabra clave de los archivos (`c` si no se cambia en Ajustes).
    palabra_archivos: RwLock<String>,
    dir: PathBuf,
}

/// La palabra clave de los archivos, como `c` en Everything.
pub const PALABRA_ARCHIVOS: &str = "c";

/// Lo que no cabe en los 20 de siempre: de archivos se ensenan hasta 30.
const MAX_ARCHIVOS: usize = 30;

pub struct Respuesta {
    pub resultados: Vec<Resultado>,
    /// Si hay plugins a los que preguntar (la app llama a
    /// [`Motor::consultar_plugins`]).
    pub plugins: bool,
}

impl Motor {
    pub fn nuevo(dir: &Path, dir_libs: Option<PathBuf>) -> Motor {
        let _ = std::fs::create_dir_all(dir);
        let busquedas = std::fs::read_to_string(dir.join("busquedas.json"))
            .ok()
            .and_then(|t| busqueda_web::importar_flow(&t).ok())
            .unwrap_or_else(busqueda_web::por_defecto);
        Motor {
            indice: RwLock::new(Indice::default()),
            historial: Mutex::new(Historial::cargar(dir)),
            busquedas: RwLock::new(busquedas),
            host: Host::nuevo(dir.join("ajustes-plugins"), dir_libs),
            archivos: Archivos::default(),
            palabra_archivos: RwLock::new(PALABRA_ARCHIVOS.into()),
            dir: dir.to_path_buf(),
        }
    }

    pub fn cargar_elementos(&self, tipo: Tipo, elementos: Vec<Elemento>) {
        if let Ok(mut i) = self.indice.write() {
            i.reemplazar(tipo, elementos);
        }
    }

    pub fn consultar(&self, texto: &str, max: usize) -> Respuesta {
        let texto = recortar(texto.trim_start());
        let (primera, resto) = texto.split_once(' ').unwrap_or((texto, ""));
        let tiene_espacio = texto.contains(' ');

        // `c tesis`: solo archivos, y se buscan en el paso 2 (en otro hilo).
        if self.consulta_archivos(texto).is_some() {
            return Respuesta { resultados: Vec::new(), plugins: true };
        }
        // `g gatos`: solo la busqueda web.
        if tiene_espacio {
            let b = self.busquedas.read().ok().and_then(|b| {
                b.iter().find(|b| b.activa && b.palabra.eq_ignore_ascii_case(primera)).cloned()
            });
            if let Some(b) = b {
                return Respuesta { resultados: vec![resultado_web(&b, resto.trim(), 10_000)], plugins: false };
            }
        }
        // `p algo`: solo el plugin; lo suyo llega en el paso 2.
        if tiene_espacio && self.host.tiene_palabra(primera) {
            return Respuesta { resultados: Vec::new(), plugins: true };
        }

        let mut r = match (self.indice.read(), self.historial.lock()) {
            (Ok(i), Ok(h)) => i.buscar(texto, max, &h),
            _ => Vec::new(),
        };

        if let Some(v) = calculadora::calcular(texto) {
            let s = calculadora::formato(v);
            r.push(Resultado {
                titulo: s.clone(),
                subtitulo: format!("{} — toca para copiar", texto.trim()),
                puntos: 5000,
                accion: format!("copiar:{s}"),
                fuente: "calc".into(),
                ..Default::default()
            });
        }

        if !texto.trim().is_empty() {
            self.sugerir_palabras(texto.trim(), &mut r);
            // Al final, buscarlo en la web con la primera busqueda.
            if let Some(b) = self.busquedas.read().ok().and_then(|b| b.iter().find(|b| b.activa).cloned()) {
                r.push(resultado_web(&b, texto.trim(), -10_000));
            }
        }
        ordenar(&mut r, max);
        let plugins = !self.host.destinos(texto).is_empty();
        Respuesta { resultados: r, plugins }
    }

    /// Lo escrito tras `c ` si es una busqueda de archivos (y estan activos).
    fn consulta_archivos<'a>(&self, texto: &'a str) -> Option<&'a str> {
        let (primera, resto) = texto.trim_start().split_once(' ')?;
        let p = self.palabra_archivos();
        (primera.eq_ignore_ascii_case(&p) && self.archivos.activo()).then_some(resto)
    }

    /// La palabra clave de los archivos.
    pub fn palabra_archivos(&self) -> String {
        self.palabra_archivos.read().map(|p| p.clone()).unwrap_or_else(|_| PALABRA_ARCHIVOS.into())
    }

    /// Cambiarla (Ajustes → Palabras clave). Vacia o con espacios: la de siempre.
    pub fn cambiar_palabra_archivos(&self, p: &str) {
        let p = p.trim();
        let p = if p.is_empty() || p.contains(char::is_whitespace) { PALABRA_ARCHIVOS } else { p };
        if let Ok(mut x) = self.palabra_archivos.write() {
            *x = p.to_string();
        }
    }

    /// Escribir `yo` sugiere `yt` (YouTube) y el nombre de cada plugin, para
    /// que se descubran sin leer el manual.
    fn sugerir_palabras(&self, texto: &str, r: &mut Vec<Resultado>) {
        if texto.contains(' ') || texto.chars().count() > 20 {
            return;
        }
        let q = normalizar::texto(texto);
        let mut sugerir = |titulo: &str, palabra: &str, fuente: &str, icono: String| {
            let por_palabra = normalizar::cadena(palabra).starts_with(&normalizar::cadena(texto));
            let por_nombre = puntuar(&q, &normalizar::texto(titulo)).filter(|c| c.puntos >= 600);
            if !por_palabra && por_nombre.is_none() {
                return;
            }
            let puntos = if por_palabra && palabra.len() == texto.len() { 900 } else { 300 };
            r.push(Resultado {
                titulo: format!("{titulo}  ·  {palabra}"),
                subtitulo: format!("Escribe «{palabra} » y lo que quieras buscar"),
                icono,
                puntos,
                accion: format!("consulta:{palabra} "),
                fuente: fuente.into(),
                autocompletar: Some(format!("{palabra} ")),
                ..Default::default()
            });
        };
        if self.archivos.activo() {
            let p = self.palabra_archivos();
            sugerir("Archivos del móvil", &p, "archivos", "carpeta".into());
        }
        if let Ok(b) = self.busquedas.read() {
            for b in b.iter().filter(|b| b.activa) {
                sugerir(&b.titulo, &b.palabra, "web", String::new());
            }
        }
        for p in self.host.lista() {
            if !self.host.usable(&p) {
                continue;
            }
            if let Some(k) = p.m.palabras.iter().find(|k| *k != "*") {
                let icono = p.m.icono.as_ref().map(|i| i.to_string_lossy().to_string()).unwrap_or_default();
                sugerir(&p.m.nombre, k, &format!("plugin:{}", p.m.id), icono);
            }
        }
    }

    pub fn consultar_plugins(&self, texto: &str, max: usize, espera: Duration) -> Vec<Resultado> {
        let texto = recortar(texto);
        let (mut r, max) = match self.consulta_archivos(texto) {
            Some(q) => {
                // Viejo: se rehace en otro hilo; esta vez, con el que hay.
                self.archivos.refrescar();
                (self.archivos.buscar(q, MAX_ARCHIVOS.max(max)), MAX_ARCHIVOS.max(max))
            }
            None => (self.host.consultar(texto, espera), max),
        };
        // Lo elegido antes con esta consulta, primero (como en lo local).
        if let Ok(h) = self.historial.lock() {
            let q = normalizar::cadena(texto.trim());
            for x in r.iter_mut() {
                x.puntos += h.extra(&q, &clave_historial(x));
            }
        }
        ordenar(&mut r, max);
        r
    }

    /// Los puntos de mas por uso de cada clave (lo que la app trae de fuera, como las filas de
    /// PinDesk): lo que mas se abre, y lo elegido con esta misma consulta, sube.
    pub fn puntos_de_uso(&self, consulta: &str, claves: &[String]) -> Vec<i32> {
        let q = normalizar::cadena(recortar(consulta).trim());
        match self.historial.lock() {
            Ok(h) => claves.iter().map(|k| h.extra(&q, k)).collect(),
            Err(_) => vec![0; claves.len()],
        }
    }

    /// La app avisa de lo elegido. Para un plugin se recuerda por su titulo.
    pub fn registrar_uso(&self, consulta: &str, accion: &str) {
        let copia = self.historial.lock().ok().and_then(|mut h| {
            h.registrar(consulta, accion);
            h.copia()
        });
        if let Some(c) = copia {
            c.escribir();
        }
    }

    pub fn importar_busquedas(&self, json_texto: &str) -> Result<usize, String> {
        let nuevas = busqueda_web::importar_flow(json_texto)?;
        let mut b = self.busquedas.write().map_err(|_| "bloqueo roto")?;
        let n = busqueda_web::juntar(&mut b, nuevas);
        historial::escribir_seguro(&self.dir.join("busquedas.json"), busqueda_web::a_json(&b).to_string().as_bytes());
        Ok(n)
    }

    pub fn busquedas_json(&self) -> Value {
        self.busquedas.read().map(|b| busqueda_web::a_json(&b)).unwrap_or(json!({}))
    }

    pub fn plugins_json(&self) -> Value {
        Value::Array(
            self.host
                .lista()
                .iter()
                .map(|p| {
                    json!({
                        "id": p.m.id, "nombre": p.m.nombre, "descripcion": p.m.descripcion,
                        "autor": p.m.autor, "version": p.m.version, "palabras": p.m.palabras,
                        "icono": p.m.icono, "compatible": p.compatible(), "estado": p.estado(),
                    })
                })
                .collect(),
        )
    }

    pub fn elementos(&self) -> usize {
        self.indice.read().map(|i| i.len()).unwrap_or(0)
    }

    pub fn indexar_archivos(&self, raiz: &Path) {
        self.archivos.indexar(raiz);
    }
}

/// Nadie busca con mas de 500 caracteres: un texto enorme pegado no puede
/// frenar la busqueda.
fn recortar(t: &str) -> &str {
    match t.char_indices().nth(500) {
        Some((i, _)) => &t[..i],
        None => t,
    }
}

/// Como se recuerda un resultado en el historial.
pub fn clave_historial(r: &Resultado) -> String {
    if r.accion.starts_with("plugin:") {
        format!("{}|{}", r.accion, r.titulo)
    } else {
        r.accion.clone()
    }
}

fn resultado_web(b: &Busqueda, consulta: &str, puntos: i32) -> Resultado {
    let (titulo, accion) = if consulta.is_empty() {
        let raiz = b.url.split("/search").next().unwrap_or(&b.url).replace("{q}", "");
        (format!("Abrir {}", b.titulo), format!("url:{raiz}"))
    } else {
        (format!("Buscar «{consulta}» en {}", b.titulo), format!("url:{}", b.url_para(consulta)))
    };
    Resultado { titulo, subtitulo: b.url.replace("{q}", "…"), puntos, accion, fuente: "web".into(), ..Default::default() }
}

#[cfg(test)]
mod pruebas {
    use super::*;

    fn motor() -> Motor {
        let dir = std::env::temp_dir().join(format!("flowcore-motor-{}-{}", historial::ahora_ms(), rand()));
        let m = Motor::nuevo(&dir, None);
        m.cargar_elementos(
            Tipo::App,
            vec![
                Elemento::nuevo(Tipo::App, "YouTube", "com.google.youtube", "app:yt", "", ""),
                Elemento::nuevo(Tipo::App, "Gmail", "com.google.gm", "app:gmail", "", ""),
            ],
        );
        m
    }

    fn rand() -> u32 {
        use std::sync::atomic::{AtomicU32, Ordering};
        static N: AtomicU32 = AtomicU32::new(0);
        N.fetch_add(1, Ordering::SeqCst)
    }

    #[test]
    fn palabra_clave_web() {
        let r = motor().consultar("g gatos negros", 10);
        assert_eq!(r.resultados.len(), 1);
        assert_eq!(r.resultados[0].accion, "url:https://www.google.com/search?q=gatos%20negros");
    }

    #[test]
    fn la_app_antes_que_la_sugerencia_y_la_web_al_final() {
        let r = motor().consultar("you", 10).resultados;
        assert_eq!(r[0].accion, "app:yt");
        assert!(r.iter().any(|x| x.accion == "consulta:yt "));
        assert!(r.last().unwrap().accion.starts_with("url:https://www.google.com"));
    }

    #[test]
    fn calculadora_arriba() {
        let r = motor().consultar("12*3", 10).resultados;
        assert_eq!(r[0].accion, "copiar:36");
    }

    #[test]
    fn archivos_con_c() {
        let m = motor();
        // Sin permiso (nunca se pidio indexar): `c x` es una consulta mas.
        assert!(!m.consultar("c you", 10).resultados.is_empty());
        assert!(!m.consultar("c", 10).resultados.iter().any(|x| x.fuente == "archivos"));

        let raiz = m.dir.join("movil");
        std::fs::create_dir_all(raiz.join("Download")).unwrap();
        std::fs::write(raiz.join("Download/Tesis.pdf"), b"x").unwrap();
        m.indexar_archivos(&raiz);
        assert!(m.archivos.esperar(Duration::from_secs(10)));

        let r = m.consultar("c tesis", 10);
        assert!(r.plugins && r.resultados.is_empty());
        // Con la mayuscula que pone el teclado, igual.
        assert!(m.consultar("C tesis", 10).plugins);
        let p = m.consultar_plugins("c tesis", 20, Duration::from_millis(100));
        assert_eq!(p[0].titulo, "Tesis.pdf");
        assert_eq!(p[0].subtitulo, "Descargas  ·  1 B");
        assert!(p[0].accion.starts_with("archivo:/") && p[0].accion.ends_with("/Download/Tesis.pdf"));
        // `c ` solo: lo ultimo modificado.
        assert_eq!(m.consultar_plugins("c ", 20, Duration::from_millis(100))[0].titulo, "Tesis.pdf");
        // `c` sin espacio: lo de siempre, con la sugerencia.
        let r = m.consultar("c", 10);
        assert!(r.resultados.iter().any(|x| x.accion == "consulta:c " && x.titulo.starts_with("Archivos del móvil")));
    }

    #[test]
    fn importar_sustituye_y_persiste() {
        let m = motor();
        let n = m
            .importar_busquedas(r#"{"SearchSources":[{"Title":"Reddit","ActionKeyword":"r","Url":"https://reddit.com/search?q={q}","Enabled":true}]}"#)
            .unwrap();
        assert_eq!(n, 1);
        assert_eq!(m.consultar("r gatos", 5).resultados[0].accion, "url:https://reddit.com/search?q=gatos");
        let otra = Motor::nuevo(&m.dir, None);
        assert_eq!(otra.consultar("r x", 5).resultados[0].accion, "url:https://reddit.com/search?q=x");
    }
}
