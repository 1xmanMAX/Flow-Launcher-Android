//! Los archivos del movil, como el plugin Everything de Flow en Windows:
//! `c tesis` y salen al instante, esten donde esten.
//!
//! El almacenamiento se recorre una vez en otro hilo (sin seguir enlaces,
//! sin lo oculto, ni `Android/data`, `Android/obb` o las caches) y queda en
//! memoria compacto: los nombres juntos en un solo `String`, la carpeta de
//! cada uno como el numero de su entrada y una mascara de letras aparte, en
//! su propio vector, para descartar casi todo sin tocar los nombres.
//! Buscar entre 200 000 es recorrer eso: unos milisegundos.
//!
//! Si el indice tiene mas de unos minutos se rehace al buscar, en otro
//! hilo; mientras, se sigue buscando en el viejo.

use crate::coincidir::{puntuar, Coincidencia};
use crate::indice::mascara;
use crate::normalizar;
use crate::resultado::Resultado;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// Como mucho tantas entradas (archivos y carpetas): ~12 MB en el peor caso.
pub const LIMITE: usize = 300_000;

/// Mas viejo que esto, se rehace al buscar.
pub const EDAD_MAXIMA: Duration = Duration::from_secs(5 * 60);

/// La entrada de la raiz (no es una entrada: es el almacenamiento).
const RAIZ: u32 = u32::MAX;

/// Un archivo o carpeta. El nombre esta en [`IndiceArchivos::nombres`].
#[derive(Debug, Clone, Copy)]
struct Entrada {
    tam: u64,
    inicio: u32,
    /// La carpeta donde esta: su numero de entrada, o [`RAIZ`].
    padre: u32,
    /// Segundos desde 1970 (en u32 cabe hasta 2106).
    modificado: u32,
    largo: u16,
    /// Nombre solo ASCII: se puede comparar byte a byte en minusculas.
    ascii: bool,
    carpeta: bool,
}

/// Una foto del almacenamiento. Inmutable: se cambia entera por otra.
#[derive(Debug, Default)]
pub struct IndiceArchivos {
    raiz: String,
    nombres: String,
    entradas: Vec<Entrada>,
    /// Las letras de cada nombre (aparte: recorrerlas es leer poca memoria).
    mascaras: Vec<u64>,
    /// Las letras con que empieza alguna palabra del nombre: con una sola
    /// letra escrita solo valen esas ([`puntuar`] no acepta otra cosa).
    iniciales: Vec<u64>,
    /// Se llego al [`LIMITE`] y faltan cosas.
    pub cortado: bool,
}

impl IndiceArchivos {
    pub fn len(&self) -> usize {
        self.entradas.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entradas.is_empty()
    }

    fn agregar(&mut self, nombre: &str, padre: u32, carpeta: bool, modificado: u32, tam: u64, buf: &mut Vec<char>) -> u32 {
        buf.clear();
        buf.extend(nombre.chars().map(normalizar::caracter));
        let n = self.entradas.len() as u32;
        self.entradas.push(Entrada {
            tam,
            inicio: self.nombres.len() as u32,
            padre,
            modificado,
            largo: nombre.len().min(u16::MAX as usize) as u16,
            ascii: nombre.is_ascii(),
            carpeta,
        });
        self.mascaras.push(mascara(buf));
        let mut ini = 0;
        for (i, c) in buf.iter().enumerate() {
            if i == 0 || !buf[i - 1].is_alphanumeric() {
                ini |= mascara(std::slice::from_ref(c));
            }
        }
        self.iniciales.push(ini);
        self.nombres.push_str(nombre);
        n
    }

    fn nombre(&self, e: &Entrada) -> &str {
        &self.nombres[e.inicio as usize..e.inicio as usize + e.largo as usize]
    }

    /// Las carpetas desde la raiz hasta la que contiene `e` (sin la raiz).
    fn carpetas(&self, e: &Entrada) -> Vec<&str> {
        let mut v = Vec::new();
        let mut p = e.padre;
        // Por si acaso, nunca mas hondo que esto (no hay ciclos, pero...).
        while p != RAIZ && v.len() < 256 {
            let c = &self.entradas[p as usize];
            v.push(self.nombre(c));
            p = c.padre;
        }
        v.reverse();
        v
    }

    fn ruta(&self, e: &Entrada) -> String {
        let mut s = self.raiz.clone();
        for c in self.carpetas(e) {
            s.push('/');
            s.push_str(c);
        }
        s.push('/');
        s.push_str(self.nombre(e));
        s
    }

    /// Lo escrito tras la `c`. Vacio: lo ultimo modificado.
    pub fn buscar(&self, consulta: &str, max: usize, ahora: u32) -> Vec<Resultado> {
        if max == 0 {
            return Vec::new();
        }
        let q = normalizar::texto(consulta.trim());
        if q.is_empty() {
            return self.recientes(max);
        }
        let mq = mascara(&q);
        let qs: String = q.iter().collect();
        let palabras: Vec<&[u8]> = qs.split(' ').filter(|p| !p.is_empty()).map(str::as_bytes).collect();
        let mut p = Puntuador { q: &q, q8: qs.is_ascii().then_some(qs.as_bytes()), buf: Vec::new(), b8: Vec::new() };
        let mut hallados: Vec<(i32, u32)> = Vec::new();
        let una_letra = q.len() == 1;
        for (n, &m) in self.mascaras.iter().enumerate() {
            // Le falta alguna letra: ni se mira (casi todos salen aqui).
            if mq & !m != 0 || (una_letra && mq & self.iniciales[n] == 0) {
                continue;
            }
            let e = &self.entradas[n];
            let nombre = self.nombre(e);
            // Las letras de cada palabra, en orden: sin eso no hay coincidencia
            // posible y se evita copiar y puntuar.
            if e.ascii && !palabras.iter().all(|p| subsecuencia(p, nombre.as_bytes())) {
                continue;
            }
            if let Some(c) = p.puntuar(nombre, e.ascii) {
                hallados.push((c.puntos, n as u32));
            }
        }
        // **Lo mas reciente arriba** (el usuario, 9-nov-2026). Pero solo entre lo que de verdad
        // se llama asi: si hay coincidencias claras (el nombre empieza asi, una palabra, las
        // iniciales o esta dentro), las letras sueltas en orden (`tss` en «tus.mp3») no cuentan.
        let claras = hallados.iter().any(|h| h.0 >= CLARA);
        if claras {
            hallados.retain(|h| h.0 >= CLARA);
        }
        let _ = ahora;
        let fecha = |n: u32| self.entradas[n as usize].modificado;
        let orden = |a: &(i32, u32), b: &(i32, u32)| fecha(b.1).cmp(&fecha(a.1)).then(b.0.cmp(&a.0)).then(a.1.cmp(&b.1));
        mejores(&mut hallados, max, orden);
        let total = hallados.len() as i32;
        hallados
            .iter()
            .enumerate()
            .map(|(i, &(_, n))| {
                let e = &self.entradas[n as usize];
                // Solo para los que se ensenan: que letras resaltar.
                let resaltado = p.puntuar(self.nombre(e), e.ascii).map(|c| c.posiciones).unwrap_or_default();
                // Los puntos siguen el orden por fecha, para que nada lo reordene despues.
                self.resultado(e, 1000 + total - i as i32, resaltado)
            })
            .collect()
    }

    /// Los archivos (no carpetas) modificados hace menos.
    fn recientes(&self, max: usize) -> Vec<Resultado> {
        let mut v: Vec<(u32, u32)> = self
            .entradas
            .iter()
            .enumerate()
            .filter(|(_, e)| !e.carpeta)
            .map(|(n, e)| (e.modificado, n as u32))
            .collect();
        mejores(&mut v, max, |a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
        v.iter()
            .enumerate()
            .map(|(k, &(_, n))| self.resultado(&self.entradas[n as usize], 1000 - k as i32, Vec::new()))
            .collect()
    }

    fn resultado(&self, e: &Entrada, puntos: i32, resaltado: Vec<u16>) -> Resultado {
        let nombre = self.nombre(e);
        let mut ruta = self.ruta(e);
        let mut subtitulo = carpeta_legible(&self.carpetas(e));
        let icono = if e.carpeta {
            "carpeta".to_string()
        } else {
            subtitulo.push_str("  ·  ");
            subtitulo.push_str(&tamano(e.tam));
            format!("ext:{}", extension(nombre))
        };
        let copiar = Some(ruta.clone());
        // Las carpetas acaban en `/`: la app sabe que es sin mirar el disco.
        if e.carpeta {
            ruta.push('/');
        }
        Resultado {
            titulo: nombre.to_string(),
            subtitulo,
            icono,
            puntos,
            accion: format!("archivo:{ruta}"),
            fuente: "archivos".into(),
            resaltado,
            copiar,
            ..Default::default()
        }
    }
}

/// La consulta contra un nombre, con los bufferes de siempre (sin reservar
/// memoria en cada uno). Un nombre ASCII se compara byte a byte.
struct Puntuador<'a> {
    q: &'a [char],
    /// La consulta en bytes, si es ASCII (si no, no hay nombre ASCII que valga).
    q8: Option<&'a [u8]>,
    buf: Vec<char>,
    b8: Vec<u8>,
}

impl Puntuador<'_> {
    #[inline]
    fn puntuar(&mut self, nombre: &str, ascii: bool) -> Option<Coincidencia> {
        if ascii {
            let q8 = self.q8?;
            self.b8.clear();
            self.b8.extend(nombre.bytes().map(|b| b.to_ascii_lowercase()));
            puntuar(q8, &self.b8)
        } else {
            self.buf.clear();
            self.buf.extend(nombre.chars().map(normalizar::caracter));
            puntuar(self.q, &self.buf)
        }
    }
}

/// Deja en `v` los `max` primeros segun `orden`, ya ordenados.
fn mejores<T>(v: &mut Vec<T>, max: usize, orden: impl Fn(&T, &T) -> std::cmp::Ordering) {
    if v.len() > max {
        v.select_nth_unstable_by(max - 1, &orden);
        v.truncate(max);
    }
    v.sort_unstable_by(orden);
}

/// `q` (ya normalizada) sale en orden dentro de `t` (ASCII, sin normalizar).
#[inline]
fn subsecuencia(q: &[u8], t: &[u8]) -> bool {
    let mut i = 0;
    for &b in t {
        if b.to_ascii_lowercase() == q[i] {
            i += 1;
            if i == q.len() {
                return true;
            }
        }
    }
    false
}

/// Desde estos puntos una coincidencia es clara (esta dentro del nombre o mejor; ver
/// [crate::coincidir]). Por debajo, solo letras sueltas en orden.
const CLARA: i32 = 500;

/// Lo tocado hace poco sube un poco, sin pasar por encima de una
/// coincidencia mejor (empezar igual vale 150 mas que una palabra).
#[allow(dead_code)]
fn reciente(modificado: u32, ahora: u32) -> i32 {
    let dias = ahora.saturating_sub(modificado) / 86_400;
    match dias {
        _ if modificado == 0 => 0,
        0 => 100,
        1..=6 => 70,
        7..=29 => 45,
        30..=364 => 20,
        _ => 0,
    }
}

/// `pdf` de `Tesis final.PDF`; vacio si no tiene.
fn extension(nombre: &str) -> String {
    match nombre.rsplit_once('.') {
        Some((antes, ext)) if !antes.is_empty() && !ext.is_empty() => ext.to_lowercase().chars().take(10).collect(),
        _ => String::new(),
    }
}

/// `Descargas/Universidad` en vez de `/storage/emulated/0/Download/Universidad`.
fn carpeta_legible(c: &[&str]) -> String {
    let Some(primera) = c.first() else { return "Almacenamiento interno".into() };
    let primera = match *primera {
        "Download" | "Downloads" => "Descargas",
        "Documents" => "Documentos",
        "Pictures" => "Imágenes",
        "Music" => "Música",
        "Movies" => "Vídeos",
        "DCIM" => "Cámara",
        "Recordings" => "Grabaciones",
        "Ringtones" => "Tonos",
        "Podcasts" => "Podcasts",
        "Audiobooks" => "Audiolibros",
        otra => otra,
    };
    // Muy hondo: la primera y las dos ultimas, que es lo que se reconoce.
    if c.len() > 3 {
        format!("{primera}/…/{}/{}", c[c.len() - 2], c[c.len() - 1])
    } else {
        let mut s = primera.to_string();
        for x in &c[1..] {
            s.push('/');
            s.push_str(x);
        }
        s
    }
}

/// `2,4 MB`, con coma decimal.
fn tamano(b: u64) -> String {
    const K: f64 = 1024.0;
    let f = b as f64;
    let (v, u) = if f < K {
        return format!("{b} B");
    } else if f < K * K {
        return format!("{} KB", (f / K).round() as u64);
    } else if f < K * K * K {
        (f / (K * K), "MB")
    } else {
        (f / (K * K * K), "GB")
    };
    format!("{v:.1} {u}").replace('.', ",")
}

/// **Cuando llego el archivo al movil**, no la fecha que trae: muchos navegadores, al descargar,
/// le ponen la fecha del servidor (de hace meses), y lo recien descargado salia 3.º o 4.º (el
/// usuario, 9-nov-2026). La del cambio del inodo (`ctime`) es la de cuando se escribio aqui; se
/// usa la mas nueva de las dos.
fn cuando_llego(m: &std::fs::Metadata) -> u32 {
    let modificado = m.modified().map(segundos).unwrap_or(0);
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let cambio = u32::try_from(m.ctime().max(0)).unwrap_or(u32::MAX);
        return modificado.max(cambio);
    }
    #[allow(unreachable_code)]
    modificado
}

/// Las carpetas donde suele aparecer algo nuevo (descargas, fotos, capturas, documentos y lo
/// que llega por WhatsApp): si alguna cambio desde el ultimo indice, se rehace ya, sin esperar
/// a [EDAD_MAXIMA]. Mirarlas cuesta unas pocas llamadas al sistema.
const VIGILADAS: &[&str] = &[
    "Download", "Documents", "DCIM/Camera", "Pictures", "Pictures/Screenshots", "DCIM/Screenshots", "Movies", "Music",
    "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents", "Android/media/org.telegram.messenger/Telegram/Telegram Files",
];

fn hay_cambios(raiz: &Path, desde: u32) -> bool {
    VIGILADAS.iter().any(|d| std::fs::metadata(raiz.join(d)).is_ok_and(|m| cuando_llego(&m) >= desde))
}

fn segundos(t: SystemTime) -> u32 {
    t.duration_since(UNIX_EPOCH).map(|d| d.as_secs().min(u32::MAX as u64) as u32).unwrap_or(0)
}

/// Carpetas que no se recorren: caches y miniaturas (lo oculto ya se salta).
fn es_cache(nombre: &str) -> bool {
    nombre.eq_ignore_ascii_case("cache") || nombre.eq_ignore_ascii_case("caches")
}

/// Recorre `raiz` entera (sin recursion: una pila) y devuelve el indice.
pub fn construir(raiz: &Path, limite: usize) -> IndiceArchivos {
    let mut idx = IndiceArchivos {
        raiz: raiz.to_string_lossy().trim_end_matches('/').to_string(),
        ..Default::default()
    };
    // Lo privado de cada app y sus descargas (enormes, y Android no deja leerlas).
    let fuera = [raiz.join("Android").join("data"), raiz.join("Android").join("obb")];
    let mut buf = Vec::with_capacity(64);
    let mut pila: Vec<(PathBuf, u32)> = vec![(raiz.to_path_buf(), RAIZ)];
    while let Some((dir, padre)) = pila.pop() {
        let Ok(lectura) = std::fs::read_dir(&dir) else { continue };
        for d in lectura.flatten() {
            if idx.entradas.len() >= limite {
                idx.cortado = true;
                return idx;
            }
            // Un nombre que no es UTF-8 no se podria volver a abrir: fuera.
            let Ok(nombre) = d.file_name().into_string() else { continue };
            if nombre.starts_with('.') {
                continue;
            }
            // Sin seguir enlaces: nada de ciclos ni de salir del almacenamiento.
            let Ok(tipo) = d.file_type() else { continue };
            if tipo.is_symlink() {
                continue;
            }
            let carpeta = tipo.is_dir();
            if carpeta && es_cache(&nombre) {
                continue;
            }
            let ruta = d.path();
            if carpeta && fuera.contains(&ruta) {
                continue;
            }
            let (modificado, tam) = match d.metadata() {
                Ok(m) => (cuando_llego(&m), if carpeta { 0 } else { m.len() }),
                Err(_) => (0, 0),
            };
            let n = idx.agregar(&nombre, padre, carpeta, modificado, tam, &mut buf);
            if carpeta {
                pila.push((ruta, n));
            }
        }
    }
    idx.nombres.shrink_to_fit();
    idx.entradas.shrink_to_fit();
    idx.mascaras.shrink_to_fit();
    idx.iniciales.shrink_to_fit();
    idx
}

#[derive(Debug)]
struct Control {
    raiz: Option<PathBuf>,
    construyendo: bool,
    hecho: Option<Instant>,
    /// Cuando empezo el ultimo indice (segundos): lo que cambie despues obliga a rehacerlo.
    hecho_en: Option<u32>,
}

#[derive(Debug)]
struct Comun {
    indice: RwLock<Arc<IndiceArchivos>>,
    control: Mutex<Control>,
    /// Ya se pidio indexar (hay permiso): `c ` busca archivos.
    activo: AtomicBool,
    edad_maxima: Duration,
}

/// El indice de archivos y quien lo rehace. Buscar nunca espera a que se
/// rehaga: toma el que haya.
#[derive(Debug, Clone)]
pub struct Archivos {
    c: Arc<Comun>,
}

impl Default for Archivos {
    fn default() -> Self {
        Archivos::nuevo(EDAD_MAXIMA)
    }
}

impl Archivos {
    pub fn nuevo(edad_maxima: Duration) -> Archivos {
        Archivos {
            c: Arc::new(Comun {
                indice: RwLock::new(Arc::new(IndiceArchivos::default())),
                control: Mutex::new(Control { raiz: None, construyendo: false, hecho: None, hecho_en: None }),
                activo: AtomicBool::new(false),
                edad_maxima,
            }),
        }
    }

    /// Empieza a indexar `raiz` en otro hilo si hace falta (otra raiz, o el
    /// indice es viejo). Vuelve enseguida.
    pub fn indexar(&self, raiz: &Path) {
        let Ok(mut c) = self.c.control.lock() else { return };
        self.c.activo.store(true, Ordering::Relaxed);
        if c.construyendo {
            return;
        }
        let misma = c.raiz.as_deref() == Some(raiz);
        let fresco = c.hecho.is_some_and(|t| t.elapsed() < self.c.edad_maxima)
            && !c.hecho_en.is_some_and(|desde| hay_cambios(raiz, desde));
        if misma && fresco {
            return;
        }
        c.raiz = Some(raiz.to_path_buf());
        c.construyendo = true;
        drop(c);
        let inicio = segundos(SystemTime::now());
        let comun = self.c.clone();
        let raiz = raiz.to_path_buf();
        let lanzado = std::thread::Builder::new().name("flow-archivos".into()).spawn(move || {
            // Un fallo (no deberia) no deja el indice bloqueado para siempre.
            let nuevo = std::panic::catch_unwind(|| construir(&raiz, LIMITE)).ok();
            if let (Some(nuevo), Ok(mut i)) = (nuevo, comun.indice.write()) {
                *i = Arc::new(nuevo);
            }
            if let Ok(mut c) = comun.control.lock() {
                c.construyendo = false;
                c.hecho = Some(Instant::now());
                c.hecho_en = Some(inicio);
            }
        });
        if lanzado.is_err() {
            if let Ok(mut c) = self.c.control.lock() {
                c.construyendo = false;
            }
        }
    }

    /// Lo mismo con la raiz de antes (al buscar con `c`).
    pub fn refrescar(&self) {
        let raiz = self.c.control.lock().ok().and_then(|c| c.raiz.clone());
        if let Some(r) = raiz {
            self.indexar(&r);
        }
    }

    pub fn activo(&self) -> bool {
        self.c.activo.load(Ordering::Relaxed)
    }

    pub fn indexando(&self) -> bool {
        self.c.control.lock().map(|c| c.construyendo).unwrap_or(false)
    }

    fn actual(&self) -> Arc<IndiceArchivos> {
        self.c.indice.read().map(|i| i.clone()).unwrap_or_default()
    }

    pub fn len(&self) -> usize {
        self.actual().len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn buscar(&self, consulta: &str, max: usize) -> Vec<Resultado> {
        let i = self.actual();
        if i.is_empty() && self.indexando() {
            return vec![Resultado {
                titulo: "Indexando los archivos del móvil…".into(),
                subtitulo: "Tarda unos segundos la primera vez; toca para volver a buscar".into(),
                icono: "carpeta".into(),
                puntos: 0,
                accion: format!("consulta:c {consulta}"),
                fuente: "archivos".into(),
                ..Default::default()
            }];
        }
        i.buscar(consulta, max, segundos(SystemTime::now()))
    }

    /// Para las pruebas: espera a que acabe de indexar.
    pub fn esperar(&self, max: Duration) -> bool {
        let t = Instant::now();
        while self.indexando() {
            if t.elapsed() > max {
                return false;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        true
    }
}

#[cfg(test)]
mod pruebas {
    use super::*;
    use std::fs;

    fn temporal(nombre: &str) -> PathBuf {
        use std::sync::atomic::AtomicU32;
        static N: AtomicU32 = AtomicU32::new(0);
        let d = std::env::temp_dir().join(format!(
            "flowcore-archivos-{nombre}-{}-{}",
            std::process::id(),
            N.fetch_add(1, Ordering::SeqCst)
        ));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn crear(raiz: &Path, rel: &str) {
        let p = raiz.join(rel);
        fs::create_dir_all(p.parent().unwrap()).unwrap();
        fs::write(&p, b"hola").unwrap();
    }

    /// Llega al movil ahora (y se espera un segundo, la resolucion del `ctime`, para que el
    /// siguiente llegue despues), con la fecha que traiga.
    fn llega(raiz: &Path, rel: &str, hace_dias: u64) {
        con_fecha(raiz, rel, hace_dias);
        std::thread::sleep(Duration::from_millis(1100));
    }

    fn con_fecha(raiz: &Path, rel: &str, hace_dias: u64) {
        crear(raiz, rel);
        let t = SystemTime::now() - Duration::from_secs(hace_dias * 86_400);
        fs::File::options().write(true).open(raiz.join(rel)).unwrap().set_modified(t).unwrap();
    }

    fn almacenamiento() -> PathBuf {
        let r = temporal("movil");
        crear(&r, "Download/Universidad/Tesis final.pdf");
        crear(&r, "Download/Universidad/notas.txt");
        crear(&r, "Documents/Presentación de tésis.pptx");
        crear(&r, "Android/media/com.whatsapp/WhatsApp/Media/tesis whatsapp.jpg");
        crear(&r, "Android/data/com.app/files/tesis.db");
        crear(&r, "Android/obb/com.juego/tesis.obb");
        crear(&r, ".oculta/tesis oculta.txt");
        crear(&r, "Download/.tesis.txt");
        crear(&r, "Download/cache/tesis.tmp");
        crear(&r, "DCIM/.thumbnails/tesis.jpg");
        crear(&r, "léeme");
        r
    }

    fn titulos(r: &[Resultado]) -> Vec<&str> {
        r.iter().map(|x| x.titulo.as_str()).collect()
    }

    #[test]
    fn encuentra_por_nombre_sin_tildes() {
        let raiz = almacenamiento();
        let i = construir(&raiz, LIMITE);
        let r = i.buscar("tesis", 30, segundos(SystemTime::now()));
        let t = titulos(&r);
        assert_eq!(r[0].titulo, "Tesis final.pdf", "{t:?}");
        assert!(t.contains(&"Presentación de tésis.pptx"), "{t:?}");
        assert!(t.contains(&"tesis whatsapp.jpg"), "{t:?}");
        let pdf = &r[0];
        assert_eq!(pdf.icono, "ext:pdf");
        assert!(pdf.subtitulo.starts_with("Descargas/Universidad"), "{}", pdf.subtitulo);
        let ruta = format!("{}/Download/Universidad/Tesis final.pdf", raiz.display());
        assert_eq!(pdf.accion, format!("archivo:{ruta}"));
        assert_eq!(pdf.copiar.as_deref(), Some(ruta.as_str()));
        assert_eq!(pdf.resaltado, vec![0, 1, 2, 3, 4]);
        // Con tilde en la consulta tambien.
        assert!(!i.buscar("TÉSIS", 30, 0).is_empty());
    }

    #[test]
    fn salta_lo_oculto_android_data_obb_y_caches() {
        let raiz = almacenamiento();
        let i = construir(&raiz, LIMITE);
        let r = i.buscar("tesis", 30, 0);
        let t = titulos(&r);
        assert_eq!(t.len(), 3, "{t:?}");
        for x in ["tesis.db", "tesis.obb", "tesis oculta.txt", ".tesis.txt", "tesis.tmp", "tesis.jpg"] {
            assert!(!t.contains(&x), "{x} en {t:?}");
        }
    }

    #[test]
    fn carpetas_con_su_icono() {
        let raiz = almacenamiento();
        let r = construir(&raiz, LIMITE).buscar("univ", 30, 0);
        assert_eq!(r[0].titulo, "Universidad");
        assert_eq!(r[0].icono, "carpeta");
        assert!(r[0].accion.ends_with("/Download/Universidad/"), "{}", r[0].accion);
        assert_eq!(r[0].subtitulo, "Descargas");
        let raiz_r = construir(&raiz, LIMITE).buscar("leeme", 5, 0);
        assert_eq!(raiz_r[0].subtitulo, "Almacenamiento interno  ·  4 B");
        assert_eq!(raiz_r[0].icono, "ext:");
    }

    #[test]
    fn varias_palabras_y_extension() {
        let raiz = almacenamiento();
        let i = construir(&raiz, LIMITE);
        assert_eq!(i.buscar("final tesis", 5, 0)[0].titulo, "Tesis final.pdf");
        assert_eq!(i.buscar("tesis pdf", 5, 0)[0].titulo, "Tesis final.pdf");
        assert!(i.buscar("tesis xyz", 5, 0).is_empty());
    }

    #[test]
    fn sin_consulta_lo_ultimo_modificado() {
        let raiz = temporal("recientes");
        // En el orden en que llegan.
        llega(&raiz, "viejo.txt", 400);
        llega(&raiz, "a/medio.txt", 10);
        llega(&raiz, "b/c/nuevo.txt", 0);
        let i = construir(&raiz, LIMITE);
        let r = i.buscar("  ", 30, segundos(SystemTime::now()));
        assert_eq!(titulos(&r), vec!["nuevo.txt", "medio.txt", "viejo.txt"]);
        assert_eq!(titulos(&i.buscar("", 1, 0)), vec!["nuevo.txt"]);
    }

    #[test]
    fn lo_mas_reciente_arriba() {
        let raiz = temporal("por-fecha");
        // «Tesis vieja» empieza igual (mejor coincidencia) pero es de hace 900 dias.
        llega(&raiz, "a/Tesis vieja.pdf", 900);
        llega(&raiz, "b/borrador tesis.docx", 0);
        // Letras sueltas en orden: con coincidencias claras, fuera.
        con_fecha(&raiz, "c/tXeXsXiXs.txt", 0);
        let r = construir(&raiz, LIMITE).buscar("tesis", 5, segundos(SystemTime::now()));
        assert_eq!(titulos(&r), vec!["borrador tesis.docx", "Tesis vieja.pdf"]);
        assert!(r[0].puntos > r[1].puntos);
    }

    /// Descargado ahora pero con la fecha del servidor (de hace un ano): sale el primero.
    #[cfg(unix)]
    #[test]
    fn lo_recien_descargado_primero_aunque_traiga_fecha_vieja() {
        let raiz = temporal("descarga");
        llega(&raiz, "Download/factura enero.pdf", 30);
        llega(&raiz, "Download/factura marzo.pdf", 365);
        let r = construir(&raiz, LIMITE).buscar("factura", 5, segundos(SystemTime::now()));
        assert_eq!(titulos(&r), vec!["factura marzo.pdf", "factura enero.pdf"]);
    }

    #[test]
    fn lo_reciente_desempata() {
        let raiz = temporal("desempate");
        con_fecha(&raiz, "x/informe.doc", 900);
        // La fecha de un archivo es la más nueva entre la de modificación y la de llegada (ctime), y las dos se
        // acaban de crear: «y» tiene que llegar de verdad después (las fechas van por segundos).
        std::thread::sleep(Duration::from_millis(1100));
        con_fecha(&raiz, "y/informe.doc", 0);
        let r = construir(&raiz, LIMITE).buscar("informe", 5, segundos(SystemTime::now()));
        assert!(r[0].accion.contains("/y/"), "{:?}", r[0].accion);
        assert!(r[0].puntos > r[1].puntos);
    }

    #[test]
    fn respeta_el_limite() {
        let raiz = temporal("limite");
        for n in 0..50 {
            crear(&raiz, &format!("f{n}.txt"));
        }
        let i = construir(&raiz, 20);
        assert_eq!(i.len(), 20);
        assert!(i.cortado);
    }

    #[test]
    fn se_rehace_en_otro_hilo_y_se_sigue_buscando() {
        let raiz = temporal("refresco");
        crear(&raiz, "uno.txt");
        let a = Archivos::nuevo(Duration::ZERO);
        assert!(!a.activo());
        a.indexar(&raiz);
        assert!(a.activo());
        assert!(a.esperar(Duration::from_secs(10)));
        assert_eq!(a.len(), 1);
        crear(&raiz, "dos.txt");
        a.refrescar();
        assert!(a.esperar(Duration::from_secs(10)));
        assert_eq!(a.buscar("dos", 5)[0].titulo, "dos.txt");

        // Fresco: no se rehace.
        let b = Archivos::nuevo(Duration::from_secs(3600));
        b.indexar(&raiz);
        assert!(b.esperar(Duration::from_secs(10)));
        crear(&raiz, "tres.txt");
        b.refrescar();
        assert!(!b.indexando());
        assert!(b.buscar("tres", 5).is_empty());
    }

    #[test]
    fn formatos() {
        assert_eq!(tamano(512), "512 B");
        assert_eq!(tamano(2048), "2 KB");
        assert_eq!(tamano(2_516_582), "2,4 MB");
        assert_eq!(extension("Tesis.final.PDF"), "pdf");
        assert_eq!(extension("Makefile"), "");
        assert_eq!(carpeta_legible(&["DCIM", "Camera"]), "Cámara/Camera");
        assert_eq!(carpeta_legible(&["Download", "a", "b", "c", "d"]), "Descargas/…/c/d");
    }
}

#[cfg(test)]
mod rendimiento {
    use super::*;

    /// 200 000 nombres (un movil muy lleno): cada consulta, pocos ms.
    #[test]
    fn doscientos_mil_en_pocos_milisegundos() {
        let palabras = [
            "IMG", "Tesis", "factura", "Informe", "notas", "Captura", "VID", "contrato", "música", "Presentación", "datos",
            "WhatsApp", "scan", "apuntes", "foto", "Universidad",
        ];
        let ext = ["jpg", "pdf", "docx", "mp4", "txt", "png", "xlsx", "mp3"];
        let mut i = IndiceArchivos { raiz: "/storage/emulated/0".into(), ..Default::default() };
        let mut buf = Vec::new();
        let mut carpetas = vec![RAIZ];
        for n in 0..200_000u32 {
            let padre = carpetas[(n as usize * 7) % carpetas.len()];
            if n % 50 == 0 {
                let c = i.agregar(&format!("{} {}", palabras[(n / 50) as usize % 16], n), padre, true, 0, 0, &mut buf);
                carpetas.push(c);
                continue;
            }
            let nombre = format!(
                "{} {} {}_{}.{}",
                palabras[n as usize % 16],
                palabras[(n as usize / 16) % 16],
                20200101 + n % 900,
                n,
                ext[n as usize % 8]
            );
            i.agregar(&nombre, padre, false, 1_600_000_000 + n * 100, n as u64 * 37, &mut buf);
        }
        let consultas = ["tesis", "t", "inf 2020", "factura pdf", "musica", "xyz", "presentacion universidad", "img", ""];
        let ahora = segundos(SystemTime::now());
        let mut n = 0;
        let mut peor = Duration::ZERO;
        let t = Instant::now();
        for _ in 0..5 {
            for q in consultas {
                let t1 = Instant::now();
                n += i.buscar(q, 30, ahora).len();
                peor = peor.max(t1.elapsed());
            }
        }
        let media = t.elapsed() / (5 * consultas.len() as u32);
        eprintln!("200000 archivos, consulta media: {media:?}, la peor: {peor:?} ({n})");
        if !cfg!(debug_assertions) {
            assert!(media < Duration::from_millis(10), "{media:?}");
        }
    }

    /// Recorrer el disco de verdad (lo que haya en la carpeta temporal).
    #[test]
    fn construir_es_rapido() {
        let raiz = std::env::temp_dir().join(format!("flowcore-archivos-perf-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&raiz);
        for d in 0..50 {
            let dir = raiz.join(format!("carpeta {d}"));
            std::fs::create_dir_all(&dir).unwrap();
            for f in 0..100 {
                std::fs::write(dir.join(format!("archivo {f}.txt")), b"").unwrap();
            }
        }
        let t = Instant::now();
        let i = construir(&raiz, LIMITE);
        eprintln!("indexar 5050 entradas: {:?}", t.elapsed());
        assert_eq!(i.len(), 5050);
        let _ = std::fs::remove_dir_all(&raiz);
    }
}
