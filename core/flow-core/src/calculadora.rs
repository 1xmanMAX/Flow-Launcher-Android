//! La calculadora del buscador: `2+3*4`, `(1,5+2)^2`, `sqrt(16)`, `15%`...
//! Acepta coma o punto decimal y `x` para multiplicar.

/// El valor, solo si lo escrito es una cuenta (tiene un numero y una
/// operacion) y se entiende entera.
pub fn calcular(texto: &str) -> Option<f64> {
    let t = texto.trim();
    if t.is_empty() || !t.chars().any(|c| c.is_ascii_digit()) {
        return None;
    }
    let tiene_operacion = t.chars().any(|c| "+-*/^%x×÷(".contains(c));
    if !tiene_operacion {
        return None;
    }
    if t.chars().count() > 300 {
        return None;
    }
    let mut p = Analizador { c: t.chars().collect(), i: 0, hondo: 0 };
    let v = p.suma()?;
    p.espacios();
    if p.i != p.c.len() || !v.is_finite() {
        return None;
    }
    Some(v)
}

/// El numero para mostrarlo: entero si lo es, si no hasta 10 cifras.
pub fn formato(v: f64) -> String {
    if v.fract() == 0.0 && v.abs() < 1e15 {
        return format!("{}", v as i64);
    }
    let s = format!("{:.10}", v);
    let s = s.trim_end_matches('0').trim_end_matches('.');
    s.to_string()
}

struct Analizador {
    c: Vec<char>,
    i: usize,
    /// Cuantos parentesis o signos anidados: con un limite, un texto con
    /// miles de `(` no desborda la pila.
    hondo: u32,
}

const MAX_HONDO: u32 = 64;

impl Analizador {
    fn espacios(&mut self) {
        while self.i < self.c.len() && self.c[self.i].is_whitespace() {
            self.i += 1;
        }
    }

    fn ver(&mut self) -> Option<char> {
        self.espacios();
        self.c.get(self.i).copied()
    }

    fn suma(&mut self) -> Option<f64> {
        let mut v = self.producto()?;
        loop {
            match self.ver() {
                Some('+') => {
                    self.i += 1;
                    v += self.producto()?;
                }
                Some('-') => {
                    self.i += 1;
                    v -= self.producto()?;
                }
                _ => return Some(v),
            }
        }
    }

    fn producto(&mut self) -> Option<f64> {
        let mut v = self.potencia()?;
        loop {
            match self.ver() {
                Some('*' | 'x' | '×') => {
                    self.i += 1;
                    v *= self.potencia()?;
                }
                Some('/' | '÷') => {
                    self.i += 1;
                    v /= self.potencia()?;
                }
                // `15 % 4` es el resto; `15%` solo, un porcentaje (ver `unario`).
                Some('%') => {
                    self.i += 1;
                    v %= self.potencia()?;
                }
                _ => return Some(v),
            }
        }
    }

    fn potencia(&mut self) -> Option<f64> {
        let base = self.unario()?;
        if self.ver() == Some('^') {
            self.i += 1;
            let exp = self.potencia()?;
            return Some(base.powf(exp));
        }
        Some(base)
    }

    fn unario(&mut self) -> Option<f64> {
        self.hondo += 1;
        if self.hondo > MAX_HONDO {
            return None;
        }
        let r = self.unario_sin_limite();
        self.hondo -= 1;
        r
    }

    fn unario_sin_limite(&mut self) -> Option<f64> {
        match self.ver() {
            Some('-') => {
                self.i += 1;
                Some(-self.unario()?)
            }
            Some('+') => {
                self.i += 1;
                self.unario()
            }
            _ => {
                let v = self.primario()?;
                // Porcentaje al final de un numero: `20%` = 0.2.
                if self.ver() == Some('%') {
                    let siguiente = self.c[self.i + 1..].iter().find(|c| !c.is_whitespace());
                    if siguiente.is_none_or(|c| "+-*/)^x×÷".contains(*c)) {
                        self.i += 1;
                        return Some(v / 100.0);
                    }
                }
                Some(v)
            }
        }
    }

    fn primario(&mut self) -> Option<f64> {
        match self.ver()? {
            '(' => {
                self.i += 1;
                let v = self.suma()?;
                if self.ver() != Some(')') {
                    return None;
                }
                self.i += 1;
                Some(v)
            }
            c if c.is_ascii_digit() || c == '.' || c == ',' => self.numero(),
            c if c.is_alphabetic() => {
                let inicio = self.i;
                while self.i < self.c.len() && self.c[self.i].is_alphabetic() {
                    self.i += 1;
                }
                let nombre: String = self.c[inicio..self.i].iter().collect::<String>().to_lowercase();
                match nombre.as_str() {
                    "pi" | "π" => return Some(std::f64::consts::PI),
                    "e" => return Some(std::f64::consts::E),
                    _ => {}
                }
                if self.ver() != Some('(') {
                    return None;
                }
                let x = self.primario()?;
                Some(match nombre.as_str() {
                    "sqrt" | "raiz" => x.sqrt(),
                    "sin" | "sen" => x.sin(),
                    "cos" => x.cos(),
                    "tan" => x.tan(),
                    "ln" => x.ln(),
                    "log" => x.log10(),
                    "abs" => x.abs(),
                    "round" | "redondear" => x.round(),
                    _ => return None,
                })
            }
            _ => None,
        }
    }

    fn numero(&mut self) -> Option<f64> {
        let inicio = self.i;
        while self.i < self.c.len() && (self.c[self.i].is_ascii_digit() || self.c[self.i] == '.' || self.c[self.i] == ',') {
            self.i += 1;
        }
        let s: String = self.c[inicio..self.i].iter().map(|c| if *c == ',' { '.' } else { *c }).collect();
        s.parse().ok()
    }
}

#[cfg(test)]
mod pruebas {
    use super::*;

    #[test]
    fn cuentas() {
        assert_eq!(calcular("2+3*4"), Some(14.0));
        assert_eq!(calcular("(1,5+2)^2"), Some(12.25));
        assert_eq!(calcular("2^3^2"), Some(512.0));
        assert_eq!(calcular("-3 x 2"), Some(-6.0));
        assert_eq!(calcular("sqrt(16)+1"), Some(5.0));
        assert_eq!(calcular("200*15%"), Some(30.0));
        assert_eq!(calcular("17 % 5"), Some(2.0));
    }

    #[test]
    fn no_es_una_cuenta() {
        assert_eq!(calcular("42"), None);
        assert_eq!(calcular("whatsapp"), None);
        assert_eq!(calcular("g 2+2"), None);
        assert_eq!(calcular("1/0"), None);
        assert_eq!(calcular("2+"), None);
        // Ni miles de parentesis ni de signos tumban nada.
        assert_eq!(calcular(&format!("{}1{}", "(".repeat(5000), ")".repeat(5000))), None);
        assert_eq!(calcular(&format!("{}1", "-".repeat(200))), None);
        assert_eq!(calcular("((((1+2))))*2"), Some(6.0));
    }

    #[test]
    fn formatos() {
        assert_eq!(formato(14.0), "14");
        assert_eq!(formato(0.1 + 0.2), "0.3");
        assert_eq!(formato(1.0 / 3.0), "0.3333333333");
    }
}
