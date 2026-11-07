//! El `plugin.json` de Flow Launcher, tal cual.

use super::clave;
use serde_json::Value;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Lenguaje {
    /// `Executable`: un proceso por cada consulta, con la peticion como
    /// argumento (lo que usa Flow 2.1.x).
    EjecutableV1,
    /// `Executable_V2`: un proceso que se queda vivo, JSON-RPC por
    /// stdin/stdout, un mensaje por linea.
    EjecutableV2,
    /// Python, C#, JavaScript...: todavia no en Android.
    Otro(String),
}

#[derive(Debug, Clone)]
pub struct Manifiesto {
    pub id: String,
    pub nombre: String,
    pub descripcion: String,
    pub autor: String,
    pub version: String,
    pub lenguaje: Lenguaje,
    pub palabras: Vec<String>,
    pub icono: Option<PathBuf>,
    pub ejecutable: String,
    pub web: String,
    pub desactivado: bool,
    pub dir: PathBuf,
    /// El JSON entero (para `initialize`).
    pub crudo: Value,
}

impl Manifiesto {
    pub fn leer(dir: &Path) -> Result<Manifiesto, String> {
        let ruta = dir.join("plugin.json");
        let texto = std::fs::read_to_string(&ruta).map_err(|e| format!("{}: {e}", ruta.display()))?;
        // Algunos plugin.json traen BOM (los escribe Windows).
        Self::de_texto(texto.trim_start_matches('\u{feff}'), dir)
    }

    pub fn de_texto(texto: &str, dir: &Path) -> Result<Manifiesto, String> {
        let v: Value = serde_json::from_str(texto).map_err(|e| format!("plugin.json no valido: {e}"))?;
        let s = |k: &str| clave(&v, k).and_then(Value::as_str).unwrap_or("").trim().to_string();
        let id = s("ID");
        if id.is_empty() {
            return Err("plugin.json sin «ID»".into());
        }
        let mut palabras: Vec<String> = match clave(&v, "ActionKeywords") {
            Some(Value::Array(a)) => a.iter().filter_map(Value::as_str).map(|p| p.trim().to_string()).collect(),
            _ => Vec::new(),
        };
        if palabras.is_empty() {
            let p = s("ActionKeyword");
            palabras.push(if p.is_empty() { "*".into() } else { p });
        }
        palabras.retain(|p| !p.is_empty());
        palabras.dedup_by(|a, b| a.eq_ignore_ascii_case(b));

        let lenguaje = match s("Language").to_ascii_lowercase().as_str() {
            "executable" => Lenguaje::EjecutableV1,
            "executable_v2" | "executablev2" => Lenguaje::EjecutableV2,
            otro => Lenguaje::Otro(otro.to_string()),
        };
        let ico = s("IcoPath").replace('\\', "/");
        let icono = (!ico.is_empty()).then(|| dir.join(ico));
        Ok(Manifiesto {
            id,
            nombre: s("Name"),
            descripcion: s("Description"),
            autor: s("Author"),
            version: s("Version"),
            lenguaje,
            palabras,
            icono,
            ejecutable: s("ExecuteFileName").replace('\\', "/"),
            web: s("Website"),
            desactivado: clave(&v, "Disabled").and_then(Value::as_bool).unwrap_or(false),
            dir: dir.to_path_buf(),
            crudo: v,
        })
    }

    pub fn es_global(&self) -> bool {
        self.palabras.iter().any(|p| p == "*")
    }

    /// Donde esta el programa. En Android no se puede ejecutar nada de la
    /// carpeta de datos de la app: los plugins van en el APK como
    /// `lib<nombre>.so` (en `dir_libs`, la `nativeLibraryDir`). En un
    /// ordenador, el fichero junto al `plugin.json`.
    pub fn ruta_ejecutable(&self, dir_libs: Option<&Path>) -> Option<PathBuf> {
        if self.ejecutable.is_empty() {
            return None;
        }
        let fichero = Path::new(&self.ejecutable).file_name()?.to_string_lossy().to_string();
        let base = fichero.strip_suffix(".exe").unwrap_or(&fichero);
        if let Some(libs) = dir_libs {
            let so = libs.join(format!("lib{base}.so"));
            if so.is_file() {
                return Some(so);
            }
        }
        [self.dir.join(&self.ejecutable), self.dir.join(base)].into_iter().find(|p| p.is_file())
    }

    pub fn explicacion_no_compatible(&self) -> Option<String> {
        match &self.lenguaje {
            Lenguaje::Otro(l) => Some(match l.as_str() {
                "python" => "Plugin de Python: aún no se pueden ejecutar en Android".into(),
                "csharp" | "fsharp" => "Plugin de .NET (C#/F#): no se puede ejecutar en Android".into(),
                "javascript" | "typescript" => "Plugin de JavaScript: llegará con el motor QuickJS".into(),
                "" => "plugin.json sin «Language»".into(),
                otro => format!("Lenguaje «{otro}» no compatible"),
            }),
            _ => None,
        }
    }
}

#[cfg(test)]
mod pruebas {
    use super::*;

    #[test]
    fn lee_el_de_pixpin() {
        let texto = "\u{feff}".to_string()
            + r#"{
          "ID": "8ABF9A87927D461EA54F3F6082337EF2",
          "ActionKeywords": ["p", "P"],
          "Name": "PixPin Max",
          "Language": "Executable",
          "IcoPath": "Images\\pixpin.png",
          "ExecuteFileName": "pixpin-lanzador.exe"
        }"#;
        let m = Manifiesto::de_texto(texto.trim_start_matches('\u{feff}'), Path::new("/x")).unwrap();
        assert_eq!(m.palabras, vec!["p"]);
        assert_eq!(m.lenguaje, Lenguaje::EjecutableV1);
        assert_eq!(m.icono.as_deref(), Some(Path::new("/x/Images/pixpin.png")));
        assert!(!m.es_global());
    }

    #[test]
    fn minusculas_palabra_suelta_y_no_compatibles() {
        let m = Manifiesto::de_texto(
            r#"{"id":"A","name":"Py","language":"python","actionKeyword":"*","executeFileName":"main.py"}"#,
            Path::new("/x"),
        )
        .unwrap();
        assert!(m.es_global());
        assert!(m.explicacion_no_compatible().unwrap().contains("Python"));
    }

    #[test]
    fn sin_id_no_vale() {
        assert!(Manifiesto::de_texto(r#"{"Name":"x"}"#, Path::new("/x")).is_err());
    }
}
