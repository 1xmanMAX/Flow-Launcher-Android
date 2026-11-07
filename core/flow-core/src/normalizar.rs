//! Texto para comparar: minusculas y sin acentos, **un caracter por cada
//! caracter** del original. Asi la posicion de lo que coincide en el texto
//! normalizado es la misma que en el original (para resaltarlo).

/// Un caracter en minuscula y sin tilde (`Á` -> `a`, `ñ` -> `n`, `Ç` -> `c`).
#[inline]
pub fn caracter(c: char) -> char {
    if c.is_ascii() {
        return c.to_ascii_lowercase();
    }
    let c = c.to_lowercase().next().unwrap_or(c);
    match c {
        'á' | 'à' | 'â' | 'ä' | 'ã' | 'å' | 'ā' | 'ă' | 'ą' => 'a',
        'é' | 'è' | 'ê' | 'ë' | 'ē' | 'ė' | 'ę' | 'ě' => 'e',
        'í' | 'ì' | 'î' | 'ï' | 'ī' | 'į' | 'ı' => 'i',
        'ó' | 'ò' | 'ô' | 'ö' | 'õ' | 'ø' | 'ō' | 'ő' => 'o',
        'ú' | 'ù' | 'û' | 'ü' | 'ū' | 'ů' | 'ű' | 'ų' => 'u',
        'ý' | 'ÿ' => 'y',
        'ñ' | 'ń' | 'ň' => 'n',
        'ç' | 'ć' | 'č' => 'c',
        'ś' | 'š' | 'ş' => 's',
        'ź' | 'ż' | 'ž' => 'z',
        'ł' => 'l',
        'đ' | 'ď' => 'd',
        'ř' => 'r',
        'ť' => 't',
        'ğ' => 'g',
        otro => otro,
    }
}

/// El texto entero, normalizado caracter a caracter.
pub fn texto(s: &str) -> Vec<char> {
    s.chars().map(caracter).collect()
}

/// Lo mismo, como `String` (para comparar palabras clave).
pub fn cadena(s: &str) -> String {
    s.chars().map(caracter).collect()
}

#[cfg(test)]
mod pruebas {
    use super::*;

    #[test]
    fn quita_tildes_y_mayusculas() {
        assert_eq!(cadena("Configuración ÁÉÍÓÚ Ñandú"), "configuracion aeiou nandu");
    }

    #[test]
    fn un_caracter_por_caracter() {
        let s = "Çà İstanbul ß";
        assert_eq!(texto(s).len(), s.chars().count());
    }
}
