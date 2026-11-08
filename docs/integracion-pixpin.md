# Integración con PixPin Android — manual para modificar PixPin

Objetivo: que `p …` en Flow Buscador haga **lo mismo que el plugin de Flow
Launcher en el PC** (`PIXPIN_PRO_PLUGIN_FLOWLAUNCHER`). Este documento es para
pasárselo a quien trabaje en `PIXPIN_PRO_ANDROID`: dice qué hay, qué falta y
cómo debe quedar cada pieza.

## Cómo se habla con PixPin

Dos caminos, los dos ya existen:

1. **Buscar** → el proveedor `content://com.forge.pixpin.flow/buscar`
   (contrato en [proveedores.md](proveedores.md)). El buscador le pregunta en
   cada tecla tras `p `; tiene que contestar en < 400 ms.
2. **Hacer** → las señas `pindesk://atajo/<ruta>?<datos>`, que abre
   `atajos/AtajoActivity.kt`. Son el equivalente en Android de los pedidos
   `WM_COPYDATA` del PC (`docs/protocolo-pedidos.md` del plugin). Toda ruta nueva va
   ahí, y cada fila del proveedor lleva en `accion` la seña que la abre.

Regla para rutas nuevas: los mismos nombres de campo que el pedido del PC
(`texto`, `proyecto`, `codigo`, `indice`, `hecha`, `nombre`…), en la consulta
de la seña. Un dato que falta = lo mismo que en el PC (sin `proyecto` =
«Mensajes guardados»).

## Estado (8-nov-2026): en PinDesk desde la v0.185.0

La rama `claude/proveedor-flow-buscador` ya está en `main` de PIXPIN_PRO_ANDROID (PinDesk 0.185.0, edición completa
y restringida). Con ella, PinDesk sigue el contrato v2 entero (`call:`, `menu`, `consulta:`), con la gramática y los
pedidos del complemento del PC: tareas completas (apuntar en una lista, marcar, quitar, mover), `@proyecto`,
escribir al chat sin abrir nada, notas, lienzos, grabar con nombre, lecciones y repasar, capturas y pinear la última.
El manual de uso está en `docs/flow-buscador.md` de PIXPIN_PRO_ANDROID.

**Flow Buscador reemplaza al buscador de PinDesk fuera de la app**: si Flow está instalado, «Buscar en PinDesk»
(`pindesk://atajo/buscar`) abre `BuscarActivity` de Flow con el extra `consulta = "p <texto>"`, y PinDesk vacía y
deja de llenar el índice del buscador del teléfono (AppSearch). En la edición restringida de PinDesk es la función
`flow` del catálogo (se abre en el panel del servidor); mientras está cerrada, el proveedor no devuelve filas.

Lo de PinDesk que aún no hace el lanzador (ver «Lo del PC que aquí no está» en `docs/flow-buscador.md`): pegar
imágenes del portapapeles (`[img 01]`), conservar o borrar capturas, y el reproductor flotante de audio.

## Lo que ya estaba (rama `claude/proveedor-flow-buscador` de PIXPIN_PRO_ANDROID)

| PC | Android |
|---|---|
| Buscar en todo (proyectos, mensajes, archivos, listas, lecciones) | ✅ `ProveedorDeFlow` + `FilasDeFlow` (misma búsqueda que la tarjeta «Buscar en PixPin») |
| Cuadro de color de la extensión | ✅ `icono = ext:<ext>`; el buscador lo dibuja con la tabla de `ColorDeExtension` |
| Abrir un archivo con su visor | ✅ `atajo/archivo/<id>` |
| Abrir un proyecto | ✅ `atajo/proyecto/<id>` |
| Abrir el chat en un mensaje | ✅ `atajo/mensaje/<id>?proyecto=<pid>` (nueva) |
| Abrir una lista de tareas | ✅ `atajo/tareas/<id>` (nueva) |
| Abrir una lección | ✅ `atajo/leccion/<id>` (nueva) |
| Escribir en el chat | ✅ `atajo/chat?texto=&proyecto=` |
| Tarea en el Inbox | ✅ `atajo/tarea?texto=` |
| Nota nueva, lista nueva, grabar, capturar, capturas, soltar, lecciones, sincronizar | ✅ (sin todos los datos del PC: ver abajo) |

**Instalar esa versión sin perder datos:** tiene que ir firmada con la misma
clave que el PixPin que ya está en el móvil. En GitHub: *Actions → «APK de
prueba» → Run workflow* sobre la rama `claude/proveedor-flow-buscador` (usa el
secreto `CLAVE_DE_DEPURACION`). Un APK compilado en otra máquina con otra
clave **no** se instala encima.

## Lo que falta para igualar al PC

Por orden de utilidad. Cada punto: la seña nueva (o el cambio) y lo que el
buscador hará con ella.

### 1. Tareas completas
- `atajo/tarea?texto=&proyecto=&codigo=`: como `anadir_tarea` del PC (con
  `codigo` → a esa lista; con `proyecto` sin `codigo` → a la última lista de
  ese chat). Hoy solo va al Inbox.
- `atajo/marcar_tarea?proyecto=&codigo=&indice=&hecha=true|false` (sin
  abrir nada: hecho y un aviso corto).
- `atajo/quitar_tarea?proyecto=&codigo=&indice=` y
  `atajo/mover_tarea?proyecto=&codigo=&indice=&a_proyecto=&a_codigo=`.
- **Proveedor**: con la consulta `tareas <lista> >` (o `tareas <texto>`),
  devolver **una fila por tarea**: `titulo` = la tarea, `subtitulo` = «☐
  pendiente · Lista · Proyecto» o «☑ hecha…», `accion` = la seña
  `marcar_tarea` que la cambia. Así se marcan desde el buscador, como en el PC.

### 2. `@proyecto` en lo que se crea
En el PC, `p chat hola @Tesis` lo manda al chat «Tesis». Hace falta que las
rutas acepten el **nombre** además del id: `proyecto_nombre=Tesis` en `chat`,
`tarea`, `nota`, `lista`, `lienzo`, `grabar` (PixPin busca el proyecto por
nombre, sin tildes ni mayúsculas, y si no hay, «Mensajes guardados»). Y el
proveedor, con `@tes` al final de la consulta, puede devolver los proyectos que
empiezan así (`accion` = la misma consulta con el nombre entero), para
autocompletarlo.

### 3. Lo que en el PC lleva más datos
- `atajo/lienzo?nombre=&proyecto=`: lienzo nuevo (no existe).
- `atajo/grabar?nombre=&proyecto=`: el nombre de la nota de voz.
- `atajo/leccion?texto=`: lección nueva con el texto ya puesto.
- `atajo/repasar`: las lecciones que tocan hoy.
- `atajo/pinear_ultima`, `atajo/conservar_captura?ruta=`, `atajo/borrar_captura?ruta=`.
- `atajo/reproducir?id=<mensaje>`: el reproductor flotante de un audio.

### 4. Pegar imágenes (`[img 01]`)
En el PC, lo pegado en el buscador se manda al chat o a una tarea con sus
imágenes. En Android: el buscador abrirá la seña con las imágenes en el
`ClipData` del intent (`content://…` con `FLAG_GRANT_READ_URI_PERMISSION`).
`AtajoActivity` tiene que leer `intent.clipData` en `chat`, `tarea` y `nota` y
tratar cada imagen como la `imagenes[n]` del pedido del PC.

### 5. Proveedor: filas de acción como en el PC
Para que el buscador no tenga que adivinar, el proveedor puede devolver también
las funciones que encajan con lo escrito (ya lo hace con grabar, capturas…).
Añadir:
- con un texto cualquiera: «Escribir «x» en el chat», «Apuntar «x» como
  tarea», «Nota con «x»» con `puntos` bajos (el plugin `p` del buscador ya
  las ofrece; si el proveedor las da, el plugin dejará de duplicarlas);
- `copiar` en cada fila de mensaje (el texto) y de archivo (el nombre): ya está.

## Del lado del buscador (Flow Buscador)

- Plugin `p` (`core/plugins/pixpin`): comandos y señas. Cuando estén las rutas
  de arriba, se añaden aquí los verbos que faltan (`@proyecto`, `tareas
  <lista> >`, `lienzo`, `repasar`…).
- Proveedores (`android/.../Proveedores.kt`): pregunta a PixPin en paralelo con
  el plugin; sus filas salen por encima de los comandos.
- Imágenes: el buscador pondrá en el `ClipData` lo que haya en el portapapeles
  cuando la acción sea `chat`, `tarea` o `nota` (pendiente, cuando PixPin lo lea).
