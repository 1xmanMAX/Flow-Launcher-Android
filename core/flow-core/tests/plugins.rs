//! El host de plugins contra un plugin de verdad (`flow-texto`) en sus dos
//! modos, y contra uno que no contesta nunca.

use flowcore::indice::{Elemento, Tipo};
use flowcore::Motor;
use serde_json::json;
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

fn programa() -> &'static Path {
    static P: OnceLock<PathBuf> = OnceLock::new();
    P.get_or_init(|| {
        let raiz = Path::new(env!("CARGO_MANIFEST_DIR")).parent().unwrap();
        let ok = std::process::Command::new(env!("CARGO"))
            .args(["build", "-q", "-p", "flow-texto"])
            .current_dir(raiz)
            .status()
            .unwrap()
            .success();
        assert!(ok, "no compila flow-texto");
        raiz.join("target/debug/flow-texto")
    })
}

fn temporal(nombre: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("flowcore-it-{nombre}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn plugin(raiz: &Path, carpeta: &str, id: &str, lenguaje: &str, palabra: &str, exe: &Path) {
    let d = raiz.join(carpeta);
    std::fs::create_dir_all(&d).unwrap();
    let m = json!({ "ID": id, "ActionKeyword": palabra, "Name": format!("Texto {lenguaje}"),
        "Language": lenguaje, "ExecuteFileName": exe, "IcoPath": "Images\\texto.png" });
    std::fs::write(d.join("plugin.json"), m.to_string()).unwrap();
}

fn motor(nombre: &str) -> (Motor, PathBuf) {
    let raiz = temporal(nombre);
    let plugins = raiz.join("plugins");
    plugin(&plugins, "v1", "V1", "Executable", "t1", programa());
    plugin(&plugins, "v2", "V2", "Executable_V2", "t2", programa());
    let m = Motor::nuevo(&raiz.join("datos"), None);
    assert!(m.host.cargar_carpeta(&plugins).is_empty());
    (m, raiz)
}

#[test]
fn los_dos_modos_contestan_igual() {
    let (m, _) = motor("modos");
    let espera = Duration::from_secs(5);
    for palabra in ["t1", "t2"] {
        // Lo local no espera a nadie: solo dice que hay plugins.
        let local = m.consultar(&format!("{palabra} Hola Mundo"), 10);
        assert!(local.plugins && local.resultados.is_empty());
        let r = m.consultar_plugins(&format!("{palabra} Hola Mundo"), 10, espera);
        assert_eq!(r.len(), 6, "{palabra}");
        assert_eq!(r[0].titulo, "HOLA MUNDO");
        assert!(r[0].icono.ends_with("Images/texto.png"));
        assert!(r[0].plugin.is_some());
    }
}

#[test]
fn ejecutar_y_eventos() {
    let (m, _) = motor("ejecutar");
    for (id, palabra) in [("V1", "t1"), ("V2", "t2")] {
        let r = m.consultar_plugins(&format!("{palabra} abc"), 10, Duration::from_secs(5));
        let p = m.host.buscar_id(id).unwrap();
        let cerrar = p.ejecutar(r[0].plugin.as_ref().unwrap(), Duration::from_secs(5), &m.host.eventos);
        assert!(cerrar, "{id}");
        // En v2 el evento llega por el hilo lector: un respiro.
        let t = Instant::now();
        let mut ev = Vec::new();
        while ev.is_empty() && t.elapsed() < Duration::from_secs(2) {
            ev = m.host.tomar_eventos();
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(ev[0]["metodo"], "CopyToClipboard", "{id}");
        assert_eq!(ev[0]["params"][0], "ABC", "{id}");
        // Y el menu contextual.
        let menu = p.menu(r[0].contexto.as_ref().unwrap(), Duration::from_secs(5), &m.host.eventos);
        assert!(menu[0].titulo.contains("ABC"), "{id}");
    }
}

#[test]
fn v2_rapido_tras_arrancar() {
    let (m, _) = motor("rapido");
    m.host.preparar();
    let t = Instant::now();
    for i in 0..50 {
        let r = m.consultar_plugins(&format!("t2 hola {i}"), 10, Duration::from_secs(2));
        assert_eq!(r.len(), 6);
    }
    let media = t.elapsed() / 50;
    eprintln!("consulta v2 media: {media:?}");
    assert!(media < Duration::from_millis(20), "{media:?}");
}

#[cfg(unix)]
#[test]
fn un_plugin_que_no_contesta_no_frena() {
    use std::os::unix::fs::PermissionsExt;
    let raiz = temporal("lento");
    let lento = raiz.join("lento.sh");
    std::fs::write(&lento, "#!/bin/sh\nsleep 30\n").unwrap();
    std::fs::set_permissions(&lento, std::fs::Permissions::from_mode(0o755)).unwrap();
    let plugins = raiz.join("plugins");
    plugin(&plugins, "lento", "LENTO", "Executable_V2", "*", &lento);
    plugin(&plugins, "v2", "V2", "Executable_V2", "*", programa());
    let m = Motor::nuevo(&raiz.join("datos"), None);
    m.host.cargar_carpeta(&plugins);
    m.cargar_elementos(Tipo::App, vec![Elemento::nuevo(Tipo::App, "Hola", "", "app:hola", "", "")]);
    // Arrancar el lento cuesta su `initialize` (hasta 5 s): se hace antes.
    std::thread::scope(|s| {
        s.spawn(|| m.host.preparar());
    });
    let t = Instant::now();
    let r = m.consultar_plugins("hola", 10, Duration::from_millis(300));
    assert!(t.elapsed() < Duration::from_secs(1), "{:?}", t.elapsed());
    // El que si contesta, llega; el lento, no.
    assert_eq!(r.len(), 6);
}

#[test]
fn global_sin_palabra_clave() {
    let raiz = temporal("global");
    let plugins = raiz.join("plugins");
    plugin(&plugins, "g", "G", "Executable_V2", "*", programa());
    let m = Motor::nuevo(&raiz.join("datos"), None);
    m.host.cargar_carpeta(&plugins);
    let local = m.consultar("algo", 10);
    assert!(local.plugins);
    let r = m.consultar_plugins("algo", 10, Duration::from_secs(5));
    assert_eq!(r[0].titulo, "ALGO");
    // Los globales no pasan por encima de lo local muy seguro.
    assert!(r[0].puntos <= 900);
}

/// Un plugin que arranca pero nunca lee ni contesta: tras unos fallos se
/// relanza, y ninguna consulta espera mas de lo que debe.
#[cfg(unix)]
#[test]
fn un_plugin_colgado_se_relanza_y_no_bloquea() {
    use std::os::unix::fs::PermissionsExt;
    let raiz = temporal("colgado");
    let colgado = raiz.join("colgado.sh");
    // Cuenta sus arranques en un fichero y despues no hace nada.
    let cuenta = raiz.join("arranques");
    std::fs::write(&colgado, format!("#!/bin/sh\necho x >> '{}'\nexec sleep 60\n", cuenta.display())).unwrap();
    std::fs::set_permissions(&colgado, std::fs::Permissions::from_mode(0o755)).unwrap();
    let plugins = raiz.join("plugins");
    plugin(&plugins, "c", "C", "Executable_V2", "c", &colgado);
    let m = Motor::nuevo(&raiz.join("datos"), None);
    m.host.cargar_carpeta(&plugins);
    let t = Instant::now();
    for i in 0..12 {
        let t1 = Instant::now();
        assert!(m.consultar_plugins(&format!("c {i}"), 10, Duration::from_millis(100)).is_empty());
        // initialize (100 ms) + la consulta (100 ms), como mucho.
        assert!(t1.elapsed() < Duration::from_millis(400), "{:?}", t1.elapsed());
    }
    assert!(t.elapsed() < Duration::from_secs(4));
    let arranques = std::fs::read_to_string(&cuenta).unwrap().lines().count();
    assert!(arranques >= 2, "se relanzo {arranques} veces");
}

#[test]
fn una_palabra_cedida_a_una_app_apaga_el_plugin() {
    let (m, _) = motor("cedida");
    assert!(m.consultar("t2 hola", 10).plugins);
    m.host.ceder(vec!["T2".into()]);
    assert!(!m.consultar("t2 hola", 10).plugins);
    assert!(m.consultar_plugins("t2 hola", 10, Duration::from_secs(2)).is_empty());
    // Los demas siguen.
    assert!(m.consultar("t1 hola", 10).plugins);
}
