# Escribir un plugin

Un plugin es una carpeta con un `plugin.json` y un programa. Es **el mismo
formato que Flow Launcher**: un plugin bien hecho funciona en Flow (Windows) y
aquí (Android) cambiando solo cómo se compila. El de ejemplo,
[`core/plugins/texto`](../core/plugins/texto/src/main.rs), funciona en los dos.

## plugin.json

```json
{
  "ID": "5B1F3C1E2A7D4E7C9A2B6D0F8E4C3A21",
  "ActionKeywords": ["tx"],
  "Name": "Texto",
  "Description": "Transforma texto",
  "Author": "Tu nombre",
  "Version": "0.1.0",
  "Language": "Executable_V2",
  "IcoPath": "Images\\texto.png",
  "ExecuteFileName": "flow-texto"
}
```

- `ActionKeywords`: la palabra que lo activa (`tx algo`). `"*"` = global: recibe
  todas las consultas, y lo suyo se mezcla con lo demás.
- `Language`:
  - `Executable_V2`: el programa arranca una vez y se queda vivo. **El más
    rápido**: recomendado.
  - `Executable`: un proceso por consulta (como lo usa Flow 2.1.x).

## Protocolo

### Executable_V2

JSON-RPC 2.0 por stdin/stdout, **un mensaje por línea**. No escribas nada más
en stdout; stderr se descarta.

| El buscador manda | `params` | Se contesta |
|---|---|---|
| `initialize` | `[{"currentPluginMetadata": {...}, "api": {}}]` | `{}` |
| `query` | `[{"search", "rawQuery", "actionKeyword", "searchTerms", "isReQuery"}, {}]` | `{"result": [resultados]}` |
| `context_menu` | `[contextData]` | `{"result": [resultados]}` |
| *tu acción* (`jsonRPCAction.method`) | `[parameters]` | `{"hide": true}` |
| `$/cancelRequest` | `{"id": n}` | (notificación) |
| `close` | `[]` | (y salir) |

`currentPluginMetadata` trae `pluginDirectory` y
`pluginSettingsDirectoryPath` (donde guardar tus datos).

### Executable (v1)

`programa '{"method": "query", "parameters": ["texto"]}'`; la respuesta
(`{"result": [...]}`) por stdout y salir. Las acciones igual, con su
`method` y `parameters`.

### Resultado

```json
{
  "title": "HOLA",
  "subTitle": "MAYÚSCULAS · toca para copiar",
  "icoPath": "Images/texto.png",
  "score": 100,
  "jsonRPCAction": { "method": "copiar", "parameters": ["HOLA"], "dontHideAfterAction": false },
  "contextData": "HOLA",
  "copyText": "HOLA",
  "autoCompleteText": "tx HOLA"
}
```

Las claves valen en camelCase o PascalCase. Si `jsonRPCAction.method`
empieza por `Flow.Launcher.` (por ejemplo `Flow.Launcher.ChangeQuery`), la
hace el buscador directamente.

### Pedirle cosas al buscador

En v2, manda una petición JSON-RPC por stdout (con `id`; se contesta
`null`). En v1, contesta a la acción con `{"method": "Flow.Launcher.X",
"parameters": [...]}`.

| Método | `params` | En Android |
|---|---|---|
| `ChangeQuery` | `[consulta, reconsultar]` | cambia lo escrito |
| `ShowMsg` | `[título, subtítulo, icono]` | aviso (toast) |
| `CopyToClipboard` | `[texto, esFichero, aviso]` | portapapeles |
| `OpenUrl` / `OpenUri` | `[url]` | abre con la app que toque |
| `HideMainWindow` | `[]` | cierra el buscador |

## Compilar para Android

Android solo deja ejecutar programas que vengan dentro del APK, en la carpeta
de librerías nativas. Por eso el programa se empaqueta como
`lib<ExecuteFileName>.so` (sin `.exe`) y la carpeta (`plugin.json`, imágenes)
va en `android/app/src/main/assets/plugins/<nombre>/`.

Para un plugin en Rust, añádelo al workspace de `core/` y a la lista
`plugins=(...)` de [`scripts/compilar-nativo.sh`](../scripts/compilar-nativo.sh).
Para otro lenguaje, compílalo para `aarch64-linux-android` (y `armv7`,
`x86_64`) con el NDK, como ejecutable PIE, y cópialo a
`android/app/src/main/jniLibs/<abi>/lib<nombre>.so`.

Lo que dependa de Windows (registro, `%APPDATA%`, el Explorador) hay que
cambiarlo: en Android no existe.
