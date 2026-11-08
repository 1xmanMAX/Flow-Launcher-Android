# Flow Buscador (Android)

Buscador universal para Android al estilo de [Flow Launcher](https://github.com/Flow-Launcher/Flow.Launcher):
una burbuja flotante (o el botón de ajustes rápidos, o el texto seleccionado)
abre una ventana de búsqueda que encuentra **apps, contactos, ajustes,
cuentas, búsquedas web y lo que aporten los plugins**, mientras escribes.

**Requisito número uno: ligero y rápido.**

| | Objetivo | Hoy |
|---|---|---|
| Búsqueda local (5000 elementos) | < 1 ms | ~0,2 ms (x86, release) |
| Consulta a un plugin v2 ya arrancado | < 20 ms | ~0,5 ms |
| Núcleo nativo (arm64) | < 2 MB | 1,6 MB con el motor JavaScript |
| Android mínimo | 7.0 (API 24) | ✅ |
| Dependencias Java/Kotlin | ninguna | ✅ (ni AndroidX) |

## Arquitectura

```
┌──────────────── Android (Kotlin, capa fina) ─────────────────┐
│ BurbujaService · TileBuscar · BuscarActivity · Fuentes       │
│   (apps, contactos, ajustes)  ──► JNI ──┐                    │
└─────────────────────────────────────────┼────────────────────┘
                                          ▼
┌──────────────── core/flow-core (Rust) ───────────────────────┐
│ índice en memoria · búsqueda difusa sin tildes · historial   │
│ calculadora · búsquedas web (importables de Flow)            │
│ host de plugins: Executable (v1) y Executable_V2 (JSON-RPC)  │
└───────────────┬──────────────────────────────────────────────┘
                ▼  stdin/stdout, un JSON por línea
        plugins (Rust, Go, C, C++… compilados para Android)
```

- **Rust** (`core/`): todo lo que tiene que ser rápido y portable. El mismo
  núcleo servirá para Mac e iOS.
- **Kotlin** (`android/`): solo lo que Android obliga (ventanas, permisos,
  leer apps y contactos). Sin librerías: arranque rápido, APK mínimo.

## Cómo se usa

- **Burbuja flotante**: actívala en la app; tócala desde cualquier sitio.
- **Ajustes rápidos**: añade el botón «Buscar».
- **Texto seleccionado** en cualquier app → «Buscar con Flow».
- **Compartir** texto con la app.
- **Asistente**: elígela como asistente (según el móvil, mantener inicio o la
  tecla lateral).

Lo que se escribe:

| Escribes | Sale |
|---|---|
| `wsp` | WhatsApp (búsqueda difusa, sin tildes, aprende de lo que eliges) |
| `wifi` | Ajustes de Wi‑Fi |
| `12*3+1` | 37 (toca para copiar) |
| `g gatos` · `yt música` · `wiki Lima` · `map café` | Búsqueda web |
| `tx Hola` | Plugin nativo de ejemplo: MAYÚSCULAS, Base64… |
| `cv 10 km a mi` | Plugin JavaScript de ejemplo: conversor de unidades |
| mantener pulsado | Menú del plugin / copiar / ficha de la app |

## Plugins

Compatibles con el **mismo protocolo que Flow Launcher**: el mismo
`plugin.json` y el mismo JSON-RPC. Ver [docs/plugins.md](docs/plugins.md).

| Tipo de plugin de Flow | En Android |
|---|---|
| `Executable` / `Executable_V2` (Rust, Go, C, C++…) | ✅ compilado para Android |
| JavaScript | ✅ con QuickJS dentro del núcleo (sin red de momento) |
| Python | ⚠️ previsto como complemento opcional |
| C# / F# (.NET) | ❌ |

## Compilar

Requisitos: JDK 17+, Android SDK con NDK, Rust con los targets de Android,
`cargo-ndk` y libclang (para generar las cabeceras de QuickJS).

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
export ANDROID_HOME=/ruta/al/sdk
./scripts/compilar-nativo.sh          # libflowcore.so + plugins nativos
cd android && ./gradlew assembleRelease
# APK: android/app/build/outputs/apk/release/app-release.apk
```

Pruebas del núcleo: `cd core && cargo test`.

> El APK de release se firma de momento con la clave de depuración, para
> poder instalarlo y probarlo. Para Google Play hará falta una clave propia.
