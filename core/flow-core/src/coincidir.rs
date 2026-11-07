//! Busqueda difusa: cuanto se parece lo escrito a un texto.
//!
//! Los dos ya vienen normalizados ([`crate::normalizar`]). De mejor a peor:
//! empieza igual > una palabra empieza igual > iniciales (`gm` -> **G**oogle
//! **M**aps) > esta dentro > las letras salen en orden (`wsp` -> **W**hat**s**a**p**p).
//! Sin reservar memoria salvo las posiciones que se devuelven.

/// El resultado: puntos (mas es mejor) y que caracteres coinciden.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Coincidencia {
    pub puntos: i32,
    pub posiciones: Vec<u16>,
}

#[inline]
fn es_inicio_de_palabra(t: &[char], i: usize) -> bool {
    i == 0 || !t[i - 1].is_alphanumeric()
}

fn encontrar(t: &[char], q: &[char]) -> Option<usize> {
    if q.len() > t.len() {
        return None;
    }
    (0..=t.len() - q.len()).find(|&i| t[i..i + q.len()] == *q)
}

fn rango(desde: usize, n: usize) -> Vec<u16> {
    (desde..desde + n).map(|i| i as u16).collect()
}

/// Una sola palabra de la consulta (sin espacios).
fn termino(q: &[char], t: &[char]) -> Option<Coincidencia> {
    if q.is_empty() || q.len() > t.len() {
        return None;
    }
    // Lo largo del texto resta poco: entre dos iguales gana el mas corto.
    let largo = (t.len().min(200) as i32) / 4;

    if t.starts_with(q) {
        let exacto = if q.len() == t.len() { 100 } else { 0 };
        return Some(Coincidencia { puntos: 1000 + exacto - largo, posiciones: rango(0, q.len()) });
    }

    // Una palabra que empieza igual.
    let mut dentro = None;
    let mut i = 0;
    while let Some(p) = encontrar(&t[i..], q).map(|p| p + i) {
        if es_inicio_de_palabra(t, p) {
            return Some(Coincidencia {
                puntos: 850 - (p.min(60) as i32) - largo,
                posiciones: rango(p, q.len()),
            });
        }
        if dentro.is_none() {
            dentro = Some(p);
        }
        i = p + 1;
    }

    // Iniciales: cada letra empieza una palabra, en orden.
    if q.len() >= 2 {
        let mut pos = Vec::with_capacity(q.len());
        let mut k = 0;
        for (j, &c) in t.iter().enumerate() {
            if k < q.len() && c == q[k] && es_inicio_de_palabra(t, j) {
                pos.push(j as u16);
                k += 1;
            }
        }
        if k == q.len() {
            return Some(Coincidencia { puntos: 750 - largo, posiciones: pos });
        }
    }

    if let Some(p) = dentro.filter(|_| q.len() >= 2) {
        return Some(Coincidencia {
            puntos: 600 - (p.min(60) as i32) - largo,
            posiciones: rango(p, q.len()),
        });
    }

    // Con una sola letra, solo valen las de arriba: si no, todo coincide.
    if q.len() < 2 {
        return None;
    }
    difusa(q, t, largo)
}

/// Las letras de `q` en orden dentro de `t`. Primero se busca hacia delante
/// el final mas temprano; despues, hacia atras, el comienzo mas tardio (la
/// coincidencia mas apretada que acaba ahi), como hace fzf.
fn difusa(q: &[char], t: &[char], largo: i32) -> Option<Coincidencia> {
    let mut k = 0;
    let mut fin = None;
    for (j, &c) in t.iter().enumerate() {
        if c == q[k] {
            k += 1;
            if k == q.len() {
                fin = Some(j);
                break;
            }
        }
    }
    let fin = fin?;
    let mut pos = vec![0u16; q.len()];
    let mut k = q.len();
    let mut j = fin + 1;
    while k > 0 {
        j -= 1;
        if t[j] == q[k - 1] {
            k -= 1;
            pos[k] = j as u16;
        }
    }

    let mut puntos = 400 - largo - (pos[0].min(40) as i32);
    for w in 0..pos.len() {
        let p = pos[w] as usize;
        if es_inicio_de_palabra(t, p) {
            puntos += 20;
        }
        if w > 0 {
            let hueco = (pos[w] - pos[w - 1] - 1) as i32;
            puntos += if hueco == 0 { 15 } else { -(hueco.min(10) * 3) };
        }
    }
    // Muy desperdigada ya no se parece: fuera.
    let tramo = (pos[pos.len() - 1] - pos[0] + 1) as usize;
    if tramo > q.len() * 4 + 4 {
        return None;
    }
    Some(Coincidencia { puntos: puntos.max(1), posiciones: pos })
}

/// La consulta entera contra un texto. Con varias palabras, primero la frase
/// tal cual; si no, tienen que estar todas (en cualquier orden).
pub fn puntuar(q: &[char], t: &[char]) -> Option<Coincidencia> {
    if let Some(c) = termino(q, t) {
        return Some(c);
    }
    if !q.contains(&' ') {
        return None;
    }
    let mut total = 0;
    let mut n = 0;
    let mut posiciones = Vec::new();
    for palabra in q.split(|c| *c == ' ').filter(|p| !p.is_empty()) {
        let c = termino(palabra, t)?;
        total += c.puntos;
        n += 1;
        posiciones.extend(c.posiciones);
    }
    if n == 0 {
        return None;
    }
    posiciones.sort_unstable();
    posiciones.dedup();
    Some(Coincidencia { puntos: total / n - 50, posiciones })
}

#[cfg(test)]
mod pruebas {
    use super::*;
    use crate::normalizar::texto;

    fn p(q: &str, t: &str) -> Option<i32> {
        puntuar(&texto(q), &texto(t)).map(|c| c.puntos)
    }

    #[test]
    fn orden_de_preferencia() {
        let prefijo = p("goo", "Google").unwrap();
        let palabra = p("ma", "Google Maps").unwrap();
        let iniciales = p("gm", "Google Maps").unwrap();
        let dentro = p("ogl", "Google").unwrap();
        let difusa = p("wsp", "WhatsApp").unwrap();
        assert!(prefijo > palabra, "{prefijo} {palabra}");
        assert!(palabra > iniciales, "{palabra} {iniciales}");
        assert!(iniciales > dentro, "{iniciales} {dentro}");
        assert!(dentro > difusa, "{dentro} {difusa}");
    }

    #[test]
    fn sin_tildes_ni_mayusculas() {
        assert!(p("camara", "Cámara").is_some());
        assert!(p("CAM", "cámara").is_some());
    }

    #[test]
    fn exacto_gana_al_largo() {
        assert!(p("maps", "Maps").unwrap() > p("maps", "Maps Go Lite").unwrap());
    }

    #[test]
    fn varias_palabras_en_cualquier_orden() {
        assert!(p("wifi ajustes", "Ajustes de Wi-Fi").is_some());
        assert!(p("wifi xyz", "Ajustes de Wi-Fi").is_none());
    }

    #[test]
    fn una_letra_no_coincide_con_todo() {
        assert!(p("x", "Google").is_none());
        assert!(p("o", "Google").is_none());
        assert!(p("g", "Google").is_some());
    }

    #[test]
    fn desperdigada_no_vale() {
        assert!(p("az", "abcdefghijklmnopqrstuvwxyz").is_none());
    }

    #[test]
    fn posiciones_para_resaltar() {
        let c = puntuar(&texto("gm"), &texto("Google Maps")).unwrap();
        assert_eq!(c.posiciones, vec![0, 7]);
    }
}
