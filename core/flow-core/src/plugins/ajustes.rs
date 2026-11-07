//! Los ajustes de un plugin, como en Flow: `Settings.json` en su carpeta de
//! ajustes, con los valores por defecto de su `SettingsTemplate.yaml`. El
//! plugin los recibe con cada consulta y los cambia con `settingsChange`.

use serde_json::{Map, Value};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub struct Ajustes {
    ruta: PathBuf,
    valores: Mutex<Map<String, Value>>,
}

impl Ajustes {
    pub fn cargar(dir_plugin: &Path, dir_ajustes: &Path) -> Ajustes {
        let mut valores = std::fs::read_to_string(dir_plugin.join("SettingsTemplate.yaml"))
            .map(|t| por_defecto(&t))
            .unwrap_or_default();
        let ruta = dir_ajustes.join("Settings.json");
        if let Some(Value::Object(guardados)) =
            std::fs::read_to_string(&ruta).ok().and_then(|t| serde_json::from_str(&t).ok())
        {
            valores.extend(guardados);
        }
        Ajustes { ruta, valores: Mutex::new(valores) }
    }

    pub fn json(&self) -> Value {
        self.valores.lock().map(|v| Value::Object(v.clone())).unwrap_or(Value::Null)
    }

    /// Junta un `settingsChange` y lo guarda.
    pub fn cambiar(&self, cambio: &Value) {
        let Some(c) = cambio.as_object().filter(|c| !c.is_empty()) else { return };
        let Ok(mut v) = self.valores.lock() else { return };
        for (k, x) in c {
            v.insert(k.clone(), x.clone());
        }
        if let Some(dir) = self.ruta.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        crate::historial::escribir_seguro(&self.ruta, Value::Object(v.clone()).to_string().as_bytes());
    }
}

/// Los `defaultValue` del `SettingsTemplate.yaml`. No hace falta un lector de
/// YAML entero: el formato de Flow es siempre una lista `body` de
/// `- type: ...` con `attributes:` que tienen `name:` y `defaultValue:`.
pub fn por_defecto(yaml: &str) -> Map<String, Value> {
    let mut r = Map::new();
    let mut tipo = String::new();
    let mut nombre: Option<String> = None;
    let mut valor: Option<String> = None;
    let cerrar = |tipo: &str, nombre: &mut Option<String>, valor: &mut Option<String>, r: &mut Map<String, Value>| {
        if let Some(n) = nombre.take() {
            let v = valor.take().unwrap_or_default();
            let v = match tipo {
                "checkbox" => Value::Bool(v.eq_ignore_ascii_case("true")),
                "textBlock" | "separator" | "hyperlink" => return,
                _ => Value::String(v),
            };
            r.insert(n, v);
        }
        *valor = None;
    };
    for linea in yaml.lines() {
        let l = linea.trim();
        if let Some(t) = l.strip_prefix("- type:").or_else(|| l.strip_prefix("-type:")) {
            cerrar(&tipo, &mut nombre, &mut valor, &mut r);
            tipo = limpiar(t);
        } else if let Some(n) = l.strip_prefix("name:") {
            nombre = Some(limpiar(n));
        } else if let Some(v) = l.strip_prefix("defaultValue:") {
            valor = Some(limpiar(v));
        }
    }
    cerrar(&tipo, &mut nombre, &mut valor, &mut r);
    r
}

fn limpiar(s: &str) -> String {
    let s = s.trim();
    let s = s.split(" #").next().unwrap_or(s).trim();
    s.trim_matches(|c| c == '"' || c == '\'').to_string()
}

#[cfg(test)]
mod pruebas {
    use super::*;

    #[test]
    fn lee_la_plantilla_de_flow() {
        let yaml = r#"
body:
  - type: textBlock
    attributes:
      description: Hola
  - type: input
    attributes:
      name: apiKey
      label: Clave
      defaultValue: ""
  - type: checkbox
    attributes:
      name: mostrarTodo
      defaultValue: true
  - type: dropdown
    attributes:
      name: idioma
      defaultValue: 'es'
      options:
        - es
        - en
"#;
        let d = por_defecto(yaml);
        assert_eq!(d.len(), 3);
        assert_eq!(d["apiKey"], "");
        assert_eq!(d["mostrarTodo"], true);
        assert_eq!(d["idioma"], "es");
    }

    #[test]
    fn guarda_los_cambios() {
        let dir = std::env::temp_dir().join(format!("flowcore-ajustes-{}", crate::historial::ahora_ms()));
        let a = Ajustes::cargar(&dir, &dir);
        a.cambiar(&serde_json::json!({ "x": 1 }));
        assert_eq!(Ajustes::cargar(&dir, &dir).json()["x"], 1);
        let _ = std::fs::remove_dir_all(dir);
    }
}
