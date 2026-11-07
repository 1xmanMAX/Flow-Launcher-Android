//! Los plugins de JavaScript: el conversor que va con la app y unos cuantos
//! scripts hechos para romper cosas (bucles infinitos, memoria, pila...).
#![cfg(feature = "js")]

use flowcore::plugins::{Host, Plugin};
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

const ESPERA: Duration = Duration::from_secs(5);

fn temporal(nombre: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("flowcore-js-{nombre}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

/// Un host con el conversor de `assets/plugins/conversor`.
fn conversor(nombre: &str) -> (Host, Arc<Plugin>, PathBuf) {
    let raiz = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../android/app/src/main/assets/plugins/conversor");
    let ajustes = temporal(nombre);
    let host = Host::nuevo(ajustes.clone(), None);
    host.cargar(&raiz).unwrap();
    let p = host.lista().pop().unwrap();
    assert!(p.compatible(), "{}", p.estado());
    (host, p, ajustes)
}

/// Un plugin con este `main.js` y palabra clave `js`.
fn script(nombre: &str, codigo: &str) -> (Host, Arc<Plugin>) {
    let raiz = temporal(nombre);
    let dir = raiz.join("plugin");
    std::fs::create_dir_all(&dir).unwrap();
    let m = json!({ "ID": "JS", "ActionKeyword": "js", "Name": "Prueba", "Language": "JavaScript", "ExecuteFileName": "main.js" });
    std::fs::write(dir.join("plugin.json"), m.to_string()).unwrap();
    std::fs::write(dir.join("main.js"), codigo).unwrap();
    let host = Host::nuevo(raiz.join("ajustes"), None);
    host.cargar(&dir).unwrap();
    let p = host.lista().pop().unwrap();
    (host, p)
}

fn consultar(p: &Plugin, h: &Host, busqueda: &str, espera: Duration) -> Vec<flowcore::resultado::Resultado> {
    p.consultar(&format!("cv {busqueda}"), "cv", busqueda, espera, &h.eventos)
}

#[test]
fn conversor_resultados() {
    let (h, p, _) = conversor("resultados");
    let r = consultar(&p, &h, "10 km a mi", ESPERA);
    assert_eq!(r.len(), 1);
    assert_eq!(r[0].titulo, "6.213712 mi");
    assert!(r[0].subtitulo.contains("10 km = 6.213712 mi"));
    assert!(r[0].icono.ends_with("Images/conversor.png"), "{}", r[0].icono);
    assert_eq!(r[0].accion, format!("plugin:{}", p.m.id));
    assert_eq!(r[0].plugin.as_ref().unwrap()["method"], "copiar");

    assert_eq!(consultar(&p, &h, "100 f c", ESPERA)[0].titulo, "37.77778 °C");
    assert_eq!(consultar(&p, &h, "2,5 tazas en ml", ESPERA)[0].titulo, "591,4706 ml");
    assert_eq!(consultar(&p, &h, "1 milla nautica a km", ESPERA)[0].titulo, "1.852 km");
    assert_eq!(consultar(&p, &h, "3 pulgadas cm", ESPERA)[0].titulo, "7.62 cm");
    // Sin destino: a todas las de su tipo.
    let kg = consultar(&p, &h, "5 kg", ESPERA);
    assert!(kg.len() >= 5 && kg.iter().any(|r| r.titulo == "11.02311 lb"));
    // Unidades de tipos distintos: un aviso, sin accion.
    let malo = consultar(&p, &h, "5 kg a km", ESPERA);
    assert_eq!(malo.len(), 1);
    assert!(malo[0].plugin.is_none());
}

#[test]
fn conversor_acciones_eventos_y_almacen() {
    let (h, p, ajustes) = conversor("acciones");
    let r = consultar(&p, &h, "10 km a mi", ESPERA);
    let antes = r[0].puntos;
    assert!(p.ejecutar(r[0].plugin.as_ref().unwrap(), ESPERA, &h.eventos));
    let ev = h.tomar_eventos();
    assert_eq!(ev, vec![json!({ "metodo": "CopyToClipboard", "params": ["6.213712"] })]);
    // Lo usado se guarda (Flow.storage) y sube.
    let guardado: Value = serde_json::from_str(&std::fs::read_to_string(ajustes.join(p.m.nombre.trim()).join("storage.json")).unwrap()).unwrap();
    assert_eq!(guardado["usos"]["km>mi"], 1);
    assert!(consultar(&p, &h, "10 km a mi", ESPERA)[0].puntos > antes);
}

#[test]
fn conversor_menu_contextual() {
    let (h, p, _) = conversor("menu");
    let r = consultar(&p, &h, "10 km a mi", ESPERA);
    let menu = p.menu(r[0].contexto.as_ref().unwrap(), ESPERA, &h.eventos);
    assert_eq!(menu.len(), 3);
    assert_eq!(menu[0].titulo, "Copiar «6.213712 mi»");
    // «Al revés» cambia la consulta y no cierra el buscador.
    let cerrar = p.ejecutar(menu[2].plugin.as_ref().unwrap(), ESPERA, &h.eventos);
    assert!(!cerrar);
    let ev = h.tomar_eventos();
    assert_eq!(ev[0]["metodo"], "ChangeQuery");
    assert_eq!(ev[0]["params"], json!(["cv 6.213712 mi a km", true]));
}

#[test]
fn api_flow_completa() {
    let (h, p) = script(
        "api",
        r#"
        var veces = 0;
        module.exports = {
          query: async function (q) {
            veces++;
            return { result: [{ Title: q.search.toUpperCase(), SubTitle: q.rawQuery + '|' + q.actionKeyword + '|' + veces,
              Score: 10.7, IcoPath: 'https://x/i.png', JsonRPCAction: { method: 'avisar', parameters: ['a', 2] } }] };
          },
          avisar: function (t, n) {
            Flow.showMsg(t, 'n=' + n);
            Flow.openUrl('https://example.com');
            Flow.call('Flow.Launcher.HideMainWindow');
            return { hide: false };
          },
          initialize: function (c) { Flow.storage.set('id', c.currentPluginMetadata.id); },
        };
        "#,
    );
    let r = consultar(&p, &h, "hola", ESPERA);
    assert_eq!(r.len(), 1);
    assert_eq!(r[0].titulo, "HOLA");
    assert_eq!(r[0].subtitulo, "cv hola|cv|1");
    assert_eq!(r[0].icono, "https://x/i.png");
    assert_eq!(r[0].puntos, 10 + 2000);
    assert!(!p.ejecutar(r[0].plugin.as_ref().unwrap(), ESPERA, &h.eventos));
    let ev = h.tomar_eventos();
    assert_eq!(ev[0], json!({ "metodo": "ShowMsg", "params": ["a", "n=2", ""] }));
    assert_eq!(ev[1], json!({ "metodo": "OpenUrl", "params": ["https://example.com"] }));
    assert_eq!(ev[2]["metodo"], "HideMainWindow");
    // El motor se reutiliza: el estado del script sigue ahi.
    assert_eq!(consultar(&p, &h, "otra", ESPERA)[0].subtitulo, "cv otra|cv|2");
    assert_eq!(p.estado(), "Listo");
}

#[test]
fn bucle_infinito_se_corta_rapido() {
    let (h, p) = script(
        "bucle",
        "function query(q) { if (q.search === 'bucle') { while (true) {} } return [{ title: 'ok' }]; }",
    );
    let t = Instant::now();
    let r = consultar(&p, &h, "bucle", Duration::from_millis(200));
    let tardo = t.elapsed();
    assert!(r.is_empty());
    assert!(tardo < Duration::from_millis(600), "{tardo:?}");
    // Y el plugin sigue vivo.
    assert_eq!(consultar(&p, &h, "x", ESPERA)[0].titulo, "ok");
}

#[test]
fn promesa_que_nunca_acaba_tampoco_cuelga() {
    let (h, p) = script(
        "promesas",
        "function query(q) { function otra() { return Promise.resolve().then(otra); } return otra(); }",
    );
    let t = Instant::now();
    assert!(consultar(&p, &h, "x", Duration::from_millis(200)).is_empty());
    assert!(t.elapsed() < Duration::from_millis(800), "{:?}", t.elapsed());
}

#[test]
fn limite_de_memoria() {
    let (h, p) = script(
        "memoria",
        r#"function query(q) {
            if (q.search === 'comer') { var a = []; while (true) a.push(new Array(100000).fill(q.search + a.length)); }
            return [{ title: 'ok' }];
        }"#,
    );
    let t = Instant::now();
    assert!(consultar(&p, &h, "comer", ESPERA).is_empty());
    // Se queda sin memoria mucho antes de que pase el tiempo.
    assert!(t.elapsed() < Duration::from_secs(3), "{:?}", t.elapsed());
    assert_eq!(consultar(&p, &h, "x", ESPERA)[0].titulo, "ok");
}

#[test]
fn recursion_infinita() {
    let (h, p) = script("pila", "function f(n) { return f(n + 1) + 1; } function query() { return f(0); }");
    assert!(consultar(&p, &h, "x", ESPERA).is_empty());
}

#[test]
fn errores_al_cargar() {
    let (h, p) = script("sintaxis", "function query( {");
    assert!(consultar(&p, &h, "x", ESPERA).is_empty());
    assert!(p.estado().starts_with("Error en el script"), "{}", p.estado());

    // Un bucle al cargar se corta tambien (en `CARGA`).
    let (h, p) = script("bucle-carga", "while (true) {}");
    let t = Instant::now();
    assert!(consultar(&p, &h, "x", Duration::from_millis(100)).is_empty());
    assert!(t.elapsed() < Duration::from_secs(3), "{:?}", t.elapsed());
    assert!(p.estado().starts_with("Error en el script"), "{}", p.estado());
}

#[test]
fn desde_varios_hilos() {
    let (h, p) = script("hilos", "function query(q) { var s = 0; for (var i = 0; i < 20000; i++) s += i; return [{ title: q.search + ':' + s }]; }");
    std::thread::scope(|s| {
        let hilos: Vec<_> = (0..8)
            .map(|i| {
                let (h, p) = (&h, &p);
                s.spawn(move || {
                    for j in 0..20 {
                        let r = consultar(p, h, &format!("{i}-{j}"), ESPERA);
                        assert_eq!(r[0].titulo, format!("{i}-{j}:199990000"));
                    }
                })
            })
            .collect();
        for x in hilos {
            x.join().unwrap();
        }
    });
}

#[test]
fn consulta_rapida() {
    let (h, p, _) = conversor("rapida");
    h.preparar();
    let t = Instant::now();
    for i in 0..200 {
        assert_eq!(consultar(&p, &h, &format!("{i} km a m"), ESPERA)[0].titulo, format!("{} m", i * 1000));
    }
    let media = t.elapsed() / 200;
    eprintln!("consulta js media: {media:?}");
    assert!(media < Duration::from_millis(5), "{media:?}");
}
