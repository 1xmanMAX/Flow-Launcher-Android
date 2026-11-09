//! Lo que se ha elegido antes sube: cuantas veces, cuan reciente y, sobre
//! todo, si fue lo elegido con esta misma consulta (como Flow).
//!
//! Se guarda en un fichero de texto, una linea por accion:
//! `veces \t ultima_ms \t accion`, y otro con `consulta \t accion`.

use std::collections::HashMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

const MAX_CONSULTAS: usize = 2000;
const MAX_USOS: usize = 1000;

/// Lo que no se recuerda: busquedas web (llevan lo buscado), lo copiado y
/// los cambios de consulta. Ni aportan ni deben quedarse en disco.
fn se_recuerda(accion: &str) -> bool {
    !(accion.starts_with("url:") || accion.starts_with("copiar:") || accion.starts_with("consulta:"))
}

pub fn ahora_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[derive(Debug, Default)]
pub struct Historial {
    usos: HashMap<String, (u32, u64)>,
    /// La ultima accion elegida para cada consulta (normalizada).
    elegidos: HashMap<String, String>,
    ruta: Option<PathBuf>,
    cambiado: bool,
}

impl Historial {
    pub fn cargar(dir: &Path) -> Self {
        let mut h = Historial { ruta: Some(dir.to_path_buf()), ..Default::default() };
        if let Ok(texto) = std::fs::read_to_string(dir.join("usos.tsv")) {
            for linea in texto.lines() {
                let mut p = linea.splitn(3, '\t');
                let (Some(v), Some(u), Some(a)) = (p.next(), p.next(), p.next()) else { continue };
                if let (Ok(v), Ok(u)) = (v.parse(), u.parse()) {
                    h.usos.insert(a.to_string(), (v, u));
                }
            }
        }
        if let Ok(texto) = std::fs::read_to_string(dir.join("elegidos.tsv")) {
            for linea in texto.lines() {
                if let Some((q, a)) = linea.split_once('\t') {
                    h.elegidos.insert(q.to_string(), a.to_string());
                }
            }
        }
        h
    }

    pub fn registrar(&mut self, consulta: &str, accion: &str) {
        if !se_recuerda(accion) {
            return;
        }
        if self.usos.len() >= MAX_USOS && !self.usos.contains_key(accion) {
            // Fuera el mas antiguo.
            if let Some(viejo) = self.usos.iter().min_by_key(|(_, (_, u))| *u).map(|(a, _)| a.clone()) {
                self.usos.remove(&viejo);
            }
        }
        let e = self.usos.entry(accion.to_string()).or_insert((0, 0));
        e.0 = e.0.saturating_add(1);
        e.1 = ahora_ms();
        let q = crate::normalizar::cadena(consulta.trim());
        if !q.is_empty() {
            if self.elegidos.len() >= MAX_CONSULTAS {
                self.elegidos.clear();
            }
            self.elegidos.insert(q, accion.to_string());
        }
        self.cambiado = true;
    }

    /// Los puntos de mas por haberlo elegido antes.
    pub fn extra(&self, consulta_n: &str, accion: &str) -> i32 {
        let mut extra = 0;
        if let Some((veces, ultima)) = self.usos.get(accion) {
            extra += (*veces).min(30) as i32 * 10;
            let horas = ahora_ms().saturating_sub(*ultima) / 3_600_000;
            extra += match horas {
                0..=1 => 60,
                2..=24 => 40,
                25..=168 => 20,
                _ => 0,
            };
        }
        if !consulta_n.is_empty() && self.elegidos.get(consulta_n).is_some_and(|a| a == accion) {
            extra += 400;
        }
        extra
    }

    /// Solo lo de haberlo elegido con esta misma consulta (sin contar lo usado en general).
    pub fn elegido(&self, consulta_n: &str, accion: &str) -> i32 {
        if !consulta_n.is_empty() && self.elegidos.get(consulta_n).is_some_and(|a| a == accion) {
            400
        } else {
            0
        }
    }

    /// Las acciones mas usadas, para cuando no hay nada escrito.
    pub fn mas_usadas(&self, max: usize) -> Vec<(&str, i32)> {
        let mut v: Vec<(&str, i32)> = self
            .usos
            .keys()
            .map(|a| (a.as_str(), self.extra("", a)))
            .collect();
        v.sort_by(|a, b| b.1.cmp(&a.1));
        v.truncate(max);
        v
    }

    /// Lo que hay que escribir, si algo cambio: se prepara con el bloqueo
    /// tomado y se escribe fuera ([`Copia::escribir`]), para que el disco
    /// nunca frene una consulta.
    pub fn copia(&mut self) -> Option<Copia> {
        if !self.cambiado {
            return None;
        }
        let dir = self.ruta.clone()?;
        let mut usos = String::new();
        for (a, (v, u)) in &self.usos {
            if !a.contains('\n') {
                usos.push_str(&format!("{v}\t{u}\t{a}\n"));
            }
        }
        let mut elegidos = String::new();
        for (q, a) in &self.elegidos {
            if !q.contains(['\n', '\t']) && !a.contains('\n') {
                elegidos.push_str(&format!("{q}\t{a}\n"));
            }
        }
        self.cambiado = false;
        Some(Copia { dir, usos, elegidos })
    }

    /// Escribe a disco si algo cambio (con el bloqueo tomado: para pruebas).
    pub fn guardar(&mut self) {
        if let Some(c) = self.copia() {
            c.escribir();
        }
    }
}

pub struct Copia {
    dir: PathBuf,
    usos: String,
    elegidos: String,
}

impl Copia {
    /// Nunca falla: el historial es una ayuda.
    pub fn escribir(&self) {
        let _ = std::fs::create_dir_all(&self.dir);
        escribir_seguro(&self.dir.join("usos.tsv"), self.usos.as_bytes());
        escribir_seguro(&self.dir.join("elegidos.tsv"), self.elegidos.as_bytes());
    }
}

/// Escribe en un temporal y lo renombra: un corte a mitad no deja el
/// fichero roto.
pub fn escribir_seguro(ruta: &Path, datos: &[u8]) -> bool {
    let tmp = ruta.with_extension("tmp");
    let ok = std::fs::File::create(&tmp)
        .and_then(|mut f| f.write_all(datos).and_then(|_| f.sync_all()))
        .is_ok();
    ok && std::fs::rename(&tmp, ruta).is_ok()
}

#[cfg(test)]
mod pruebas {
    use super::*;

    #[test]
    fn lo_elegido_con_la_misma_consulta_sube_mucho() {
        let mut h = Historial::default();
        h.registrar("Cam", "app:camara");
        assert!(h.extra("cam", "app:camara") > 400);
        assert!(h.extra("otra", "app:camara") < 400);
        assert_eq!(h.extra("cam", "app:otra"), 0);
    }

    #[test]
    fn no_recuerda_lo_buscado_en_la_web() {
        let mut h = Historial::default();
        h.registrar("g secreto", "url:https://google.com/search?q=secreto");
        h.registrar("", "copiar:42");
        assert!(h.mas_usadas(10).is_empty());
    }

    #[test]
    fn tiene_tope() {
        let mut h = Historial::default();
        for i in 0..(MAX_USOS + 50) {
            h.registrar("", &format!("app:{i}"));
        }
        assert_eq!(h.usos.len(), MAX_USOS);
    }

    #[test]
    fn se_guarda_y_se_vuelve_a_leer() {
        let dir = std::env::temp_dir().join(format!("flowcore-hist-{}", ahora_ms()));
        let mut h = Historial::cargar(&dir);
        h.registrar("wsp", "app:whatsapp");
        h.guardar();
        let h2 = Historial::cargar(&dir);
        assert!(h2.extra("wsp", "app:whatsapp") > 400);
        let _ = std::fs::remove_dir_all(dir);
    }
}
