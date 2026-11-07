//! Busquedas web por palabra clave (`g gatos`, `yt musica`), como el plugin
//! WebSearch de Flow. Se pueden importar las de Flow tal cual: su
//! `Settings.json` (`%APPDATA%\FlowLauncher\Settings\Plugins\Flow.Launcher.Plugin.WebSearch\Settings.json`).

use serde_json::{json, Value};

#[derive(Debug, Clone, PartialEq)]
pub struct Busqueda {
    pub titulo: String,
    pub palabra: String,
    /// Con `{q}` donde va lo buscado.
    pub url: String,
    pub activa: bool,
}

impl Busqueda {
    fn nueva(titulo: &str, palabra: &str, url: &str) -> Self {
        Busqueda { titulo: titulo.into(), palabra: palabra.into(), url: url.into(), activa: true }
    }

    pub fn url_para(&self, consulta: &str) -> String {
        self.url.replace("{q}", &codificar(consulta))
    }
}

pub fn por_defecto() -> Vec<Busqueda> {
    vec![
        Busqueda::nueva("Google", "g", "https://www.google.com/search?q={q}"),
        Busqueda::nueva("YouTube", "yt", "https://www.youtube.com/results?search_query={q}"),
        Busqueda::nueva("Wikipedia", "wiki", "https://es.wikipedia.org/wiki/Special:Search?search={q}"),
        Busqueda::nueva("Google Maps", "map", "https://www.google.com/maps/search/{q}"),
        Busqueda::nueva("Traductor", "tr", "https://translate.google.com/?sl=auto&tl=es&text={q}"),
        Busqueda::nueva("GitHub", "gh", "https://github.com/search?q={q}"),
        Busqueda::nueva("DuckDuckGo", "dd", "https://duckduckgo.com/?q={q}"),
        Busqueda::nueva("Play Store", "ps", "https://play.google.com/store/search?q={q}&c=apps"),
    ]
}

/// Percent-encoding de lo buscado (RFC 3986: lo que no es letra, cifra o
/// `-_.~`, en `%XX`; el espacio como `%20`).
pub fn codificar(s: &str) -> String {
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

/// Las del `Settings.json` del WebSearch de Flow (o una lista suelta de
/// fuentes). Las claves, sin importar mayusculas.
pub fn importar_flow(json_texto: &str) -> Result<Vec<Busqueda>, String> {
    let v: Value = serde_json::from_str(json_texto).map_err(|e| format!("JSON no valido: {e}"))?;
    let fuentes = match &v {
        Value::Array(a) => a.clone(),
        Value::Object(_) => crate::plugins::clave(&v, "SearchSources")
            .and_then(Value::as_array)
            .cloned()
            .ok_or("No hay «SearchSources» en el fichero")?,
        _ => return Err("Se esperaba un objeto o una lista".into()),
    };
    let mut r = Vec::new();
    for f in &fuentes {
        let texto = |k: &str| crate::plugins::clave(f, k).and_then(Value::as_str).unwrap_or("").trim().to_string();
        let (titulo, palabra, url) = (texto("Title"), texto("ActionKeyword"), texto("Url"));
        if titulo.is_empty() || palabra.is_empty() || !url.contains("{q}") {
            continue;
        }
        let activa = crate::plugins::clave(f, "Enabled").and_then(Value::as_bool).unwrap_or(true);
        r.push(Busqueda { titulo, palabra, url, activa });
    }
    if r.is_empty() {
        return Err("No se encontro ninguna busqueda valida".into());
    }
    Ok(r)
}

/// Junta las importadas con las que ya hay: la misma palabra clave se
/// sustituye.
pub fn juntar(actuales: &mut Vec<Busqueda>, nuevas: Vec<Busqueda>) -> usize {
    let n = nuevas.len();
    for b in nuevas {
        actuales.retain(|a| a.palabra != b.palabra);
        actuales.push(b);
    }
    n
}

pub fn a_json(b: &[Busqueda]) -> Value {
    json!({ "SearchSources": b.iter().map(|b| json!({
        "Title": b.titulo, "ActionKeyword": b.palabra, "Url": b.url, "Enabled": b.activa,
    })).collect::<Vec<_>>() })
}

#[cfg(test)]
mod pruebas {
    use super::*;

    #[test]
    fn codifica() {
        assert_eq!(codificar("año 2026 & más"), "a%C3%B1o%202026%20%26%20m%C3%A1s");
    }

    #[test]
    fn importa_el_settings_de_flow() {
        let flow = r#"{"SearchSources":[
            {"Title":"Google","ActionKeyword":"g","Icon":"google.png","Url":"https://www.google.com/search?q={q}","Enabled":true},
            {"title":"Reddit","actionKeyword":"r","url":"https://www.reddit.com/search/?q={q}","enabled":false},
            {"Title":"Rota","ActionKeyword":"x","Url":"https://sin-consulta.com"}
        ],"EnableSuggestion":false}"#;
        let r = importar_flow(flow).unwrap();
        assert_eq!(r.len(), 2);
        assert_eq!(r[1].palabra, "r");
        assert!(!r[1].activa);
        // Y lo exportado se vuelve a importar igual.
        assert_eq!(importar_flow(&a_json(&r).to_string()).unwrap(), r);
    }

    #[test]
    fn juntar_sustituye_la_misma_palabra() {
        let mut a = por_defecto();
        let n = a.len();
        juntar(&mut a, vec![Busqueda::nueva("Google ES", "g", "https://google.es/search?q={q}")]);
        assert_eq!(a.len(), n);
        assert_eq!(a.iter().find(|b| b.palabra == "g").unwrap().titulo, "Google ES");
    }
}
