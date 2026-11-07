//! Un resultado tal como lo ve la app: lo mismo venga del indice, de la
//! calculadora, de una busqueda web o de un plugin.

use serde_json::{json, Map, Value};

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Resultado {
    pub titulo: String,
    pub subtitulo: String,
    /// `app:<paquete>/<actividad>`, una ruta a una imagen, o vacio (la app
    /// pone uno segun la `fuente`).
    pub icono: String,
    pub puntos: i32,
    /// Que hacer al elegirlo. La app entiende: `app:`, `url:`, `intent:`,
    /// `copiar:`, `consulta:` (cambiar lo escrito) y `plugin:` (pedirselo al
    /// plugin con [`Resultado::plugin`]).
    pub accion: String,
    /// `apps`, `contactos`, `ajustes`, `web`, `calc`, `atajo` o `plugin:<ID>`.
    pub fuente: String,
    /// Que caracteres del titulo coinciden (para resaltarlos).
    pub resaltado: Vec<u16>,
    /// La `jsonRPCAction` del plugin.
    pub plugin: Option<Value>,
    /// El `contextData` del plugin (para su menu contextual).
    pub contexto: Option<Value>,
    /// Lo que se escribe al autocompletar (tabulador o la flecha).
    pub autocompletar: Option<String>,
    /// Lo que se copia con una pulsacion larga.
    pub copiar: Option<String>,
}

impl Resultado {
    pub fn a_json(&self) -> Value {
        let mut m = Map::new();
        m.insert("t".into(), json!(self.titulo));
        m.insert("s".into(), json!(self.subtitulo));
        m.insert("i".into(), json!(self.icono));
        m.insert("p".into(), json!(self.puntos));
        m.insert("a".into(), json!(self.accion));
        m.insert("f".into(), json!(self.fuente));
        if !self.resaltado.is_empty() {
            m.insert("r".into(), json!(self.resaltado));
        }
        if let Some(v) = &self.plugin {
            m.insert("pa".into(), v.clone());
        }
        if let Some(v) = &self.contexto {
            m.insert("cx".into(), v.clone());
        }
        if let Some(v) = &self.autocompletar {
            m.insert("ac".into(), json!(v));
        }
        if let Some(v) = &self.copiar {
            m.insert("cp".into(), json!(v));
        }
        Value::Object(m)
    }
}

/// Ordena de mas a menos puntos (estable: a igualdad, el orden de llegada) y
/// se queda con los `max` primeros.
pub fn ordenar(r: &mut Vec<Resultado>, max: usize) {
    r.sort_by(|a, b| b.puntos.cmp(&a.puntos));
    r.truncate(max);
}

pub fn lista_json(r: &[Resultado]) -> Value {
    Value::Array(r.iter().map(Resultado::a_json).collect())
}
