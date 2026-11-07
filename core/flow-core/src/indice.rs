//! Lo que se busca sin plugins: apps, contactos, ajustes, atajos... La app
//! los lee del sistema y los carga aqui por tipos; buscar es recorrerlos en
//! memoria (unos miles: menos de un milisegundo).

use crate::coincidir::puntuar;
use crate::historial::Historial;
use crate::normalizar;
use crate::resultado::Resultado;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Tipo {
    App,
    Contacto,
    Ajuste,
    Atajo,
    Archivo,
}

impl Tipo {
    pub fn de_numero(n: i32) -> Option<Tipo> {
        Some(match n {
            0 => Tipo::App,
            1 => Tipo::Contacto,
            2 => Tipo::Ajuste,
            3 => Tipo::Atajo,
            4 => Tipo::Archivo,
            _ => return None,
        })
    }

    pub fn fuente(self) -> &'static str {
        match self {
            Tipo::App => "apps",
            Tipo::Contacto => "contactos",
            Tipo::Ajuste => "ajustes",
            Tipo::Atajo => "atajo",
            Tipo::Archivo => "archivos",
        }
    }

    /// Lo que se abre mas a menudo, primero a igualdad de parecido.
    fn peso(self) -> i32 {
        match self {
            Tipo::App => 60,
            Tipo::Atajo => 40,
            Tipo::Contacto => 20,
            Tipo::Ajuste => 0,
            Tipo::Archivo => -20,
        }
    }
}

#[derive(Debug, Clone)]
pub struct Elemento {
    pub tipo: Tipo,
    pub titulo: String,
    pub subtitulo: String,
    pub accion: String,
    pub icono: String,
    titulo_n: Vec<char>,
    /// Que letras tiene (titulo y claves): si falta alguna de la consulta,
    /// ni se mira.
    mascara: u64,
    /// El subtitulo y otras palabras con que encontrarlo (`wifi`, `red`...).
    claves_n: Vec<char>,
}

impl Elemento {
    pub fn nuevo(tipo: Tipo, titulo: &str, subtitulo: &str, accion: &str, icono: &str, claves: &str) -> Self {
        let mut claves_n = normalizar::texto(subtitulo);
        if !claves.is_empty() {
            claves_n.push(' ');
            claves_n.extend(normalizar::texto(claves));
        }
        let titulo_n = normalizar::texto(titulo);
        let mascara = mascara(&titulo_n) | mascara(&claves_n);
        Elemento {
            mascara,
            tipo,
            titulo: titulo.to_string(),
            subtitulo: subtitulo.to_string(),
            accion: accion.to_string(),
            icono: icono.to_string(),
            titulo_n,
            claves_n,
        }
    }

    fn resultado(&self, puntos: i32, resaltado: Vec<u16>) -> Resultado {
        Resultado {
            titulo: self.titulo.clone(),
            subtitulo: self.subtitulo.clone(),
            icono: self.icono.clone(),
            puntos,
            accion: self.accion.clone(),
            fuente: self.tipo.fuente().into(),
            resaltado,
            ..Default::default()
        }
    }
}

/// Un bit por letra o cifra (el resto comparte el ultimo).
fn mascara(t: &[char]) -> u64 {
    let mut m = 0u64;
    for &c in t {
        m |= match c {
            'a'..='z' => 1 << (c as u32 - 'a' as u32),
            '0'..='9' => 1 << (26 + c as u32 - '0' as u32),
            ' ' => 0,
            _ => 1 << 63,
        };
    }
    m
}

#[derive(Debug, Default)]
pub struct Indice {
    elementos: Vec<Elemento>,
}

impl Indice {
    /// Cambia todos los de un tipo de golpe (la app relee las apps al
    /// instalar o quitar una).
    pub fn reemplazar(&mut self, tipo: Tipo, nuevos: Vec<Elemento>) {
        self.elementos.retain(|e| e.tipo != tipo);
        self.elementos.extend(nuevos);
    }

    pub fn len(&self) -> usize {
        self.elementos.len()
    }

    pub fn is_empty(&self) -> bool {
        self.elementos.is_empty()
    }

    pub fn buscar(&self, consulta: &str, max: usize, historial: &Historial) -> Vec<Resultado> {
        let q = normalizar::texto(consulta.trim());
        let qs: String = q.iter().collect();
        if q.is_empty() {
            return self.mas_usados(max, historial);
        }
        let mut r: Vec<Resultado> = Vec::new();
        let mq = mascara(&q);
        for e in &self.elementos {
            if mq & !e.mascara != 0 {
                continue;
            }
            let (puntos, resaltado) = match puntuar(&q, &e.titulo_n) {
                Some(c) => (c.puntos, c.posiciones),
                // Por el subtitulo o las claves vale menos, y no se resalta.
                None => match puntuar(&q, &e.claves_n) {
                    Some(c) => (c.puntos - 250, Vec::new()),
                    None => continue,
                },
            };
            let puntos = puntos + e.tipo.peso() + historial.extra(&qs, &e.accion);
            r.push(e.resultado(puntos, resaltado));
        }
        crate::resultado::ordenar(&mut r, max);
        r
    }

    fn mas_usados(&self, max: usize, historial: &Historial) -> Vec<Resultado> {
        let mut r = Vec::new();
        for (accion, puntos) in historial.mas_usadas(max * 2) {
            if let Some(e) = self.elementos.iter().find(|e| e.accion == accion) {
                r.push(e.resultado(puntos, Vec::new()));
            }
            if r.len() >= max {
                break;
            }
        }
        r
    }
}

#[cfg(test)]
mod pruebas {
    use super::*;

    fn indice() -> Indice {
        let mut i = Indice::default();
        i.reemplazar(
            Tipo::App,
            vec![
                Elemento::nuevo(Tipo::App, "Cámara", "com.android.camera", "app:cam", "", ""),
                Elemento::nuevo(Tipo::App, "Calculadora", "com.android.calc", "app:calc", "", ""),
                Elemento::nuevo(Tipo::App, "WhatsApp", "com.whatsapp", "app:wsp", "", ""),
            ],
        );
        i.reemplazar(
            Tipo::Ajuste,
            vec![Elemento::nuevo(Tipo::Ajuste, "Wi-Fi", "Ajustes", "intent:wifi", "", "red internet inalambrica")],
        );
        i
    }

    #[test]
    fn encuentra_y_ordena() {
        let h = Historial::default();
        let r = indice().buscar("ca", 10, &h);
        // Por el titulo, primero; lo que solo coincide por las claves, detras.
        let primeros: Vec<&str> = r[..2].iter().map(|x| x.accion.as_str()).collect();
        assert!(primeros.contains(&"app:cam") && primeros.contains(&"app:calc"), "{primeros:?}");
    }

    #[test]
    fn por_las_claves() {
        let h = Historial::default();
        let r = indice().buscar("internet", 10, &h);
        assert_eq!(r[0].accion, "intent:wifi");
    }

    #[test]
    fn el_historial_cambia_el_orden() {
        let mut h = Historial::default();
        let i = indice();
        let antes = i.buscar("ca", 10, &h);
        let segundo = antes[1].accion.clone();
        h.registrar("ca", &segundo);
        assert_eq!(i.buscar("ca", 10, &h)[0].accion, segundo);
    }

    #[test]
    fn reemplazar_un_tipo_deja_los_demas() {
        let mut i = indice();
        i.reemplazar(Tipo::App, vec![]);
        assert_eq!(i.len(), 1);
    }

    #[test]
    fn sin_consulta_los_mas_usados() {
        let mut h = Historial::default();
        let i = indice();
        assert!(i.buscar("", 5, &h).is_empty());
        h.registrar("wsp", "app:wsp");
        assert_eq!(i.buscar("", 5, &h)[0].accion, "app:wsp");
    }
}

#[cfg(test)]
mod rendimiento {
    use super::*;

    /// 5000 elementos (mas que apps + contactos + ajustes de un movil normal).
    #[test]
    fn cinco_mil_en_menos_de_un_milisegundo() {
        let palabras = ["Google", "Maps", "Cámara", "Ajustes", "Notas", "Música", "Correo", "Fotos", "Reloj", "Banco"];
        let mut v = Vec::new();
        for i in 0..5000 {
            let t = format!("{} {} {}", palabras[i % 10], palabras[(i / 10) % 10], i);
            v.push(Elemento::nuevo(Tipo::Contacto, &t, "+34 600 000 000", &format!("c:{i}"), "", ""));
        }
        let mut idx = Indice::default();
        idx.reemplazar(Tipo::Contacto, v);
        let h = Historial::default();
        let consultas = ["g", "go", "goo", "gm", "cam aj", "musica", "xyz", "notas 12"];
        let t = std::time::Instant::now();
        let mut n = 0;
        for _ in 0..20 {
            for q in consultas {
                n += idx.buscar(q, 20, &h).len();
            }
        }
        let media = t.elapsed() / (20 * consultas.len() as u32);
        eprintln!("5000 elementos, consulta media: {media:?} ({n})");
        if !cfg!(debug_assertions) {
            assert!(media < std::time::Duration::from_millis(1), "{media:?}");
        }
    }
}
