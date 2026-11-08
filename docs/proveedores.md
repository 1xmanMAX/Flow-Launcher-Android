# Proveedores de búsqueda: integrar tu app en Flow Buscador

Cualquier app de Android puede poner sus resultados **y sus acciones** en el
buscador sin plugins: declara un `ContentProvider` y el buscador le pregunta
mientras se escribe, y le pide que haga las cosas sin abrirse. Es lo que hace
PixPin con `p …`.

Versión del contrato: **2** (la 1 solo tenía `titulo`, `subtitulo`, `icono`,
`puntos`, `accion`, `copiar`; sigue valiendo).

## 1. Declararlo

```xml
<provider
    android:name=".ProveedorDeFlow"
    android:authorities="com.tuapp.flow"
    android:exported="true">
    <intent-filter>
        <action android:name="com.max.flowlauncher.PROVEEDOR" />
    </intent-filter>
    <!-- Palabra clave: «p tesis» solo pregunta aquí. "*" = en todas las consultas. -->
    <meta-data android:name="com.max.flowlauncher.palabra" android:value="p" />
    <meta-data android:name="com.max.flowlauncher.nombre" android:value="PixPin" />
</provider>
```

Si un proveedor tiene una palabra clave, **el buscador ya no usa ningún plugin
con esa misma palabra**: el proveedor manda (así el plugin `p` de reserva no
duplica nada cuando PixPin contesta).

## 2. Buscar

```kotlin
contentResolver.query(
    Uri.parse("content://<authorities>/buscar"),
    null, null, arrayOf(textoBuscado), null, cancellationSignal,
)
```

- `selectionArgs[0]`: lo escrito **sin** la palabra clave (`tesis` de `p tesis`,
  `t comprar pan` de `p t comprar pan`). Vacío = lo que enseñar con `p` a secas.
- Contestar en **< 400 ms** (el buscador cancela si se sigue escribiendo). Desde
  un índice en memoria; nunca recorrer el disco ni la red en cada tecla.
- Como mucho 30 filas.

| Columna | Tipo | Qué es |
|---|---|---|
| `titulo` | texto | Lo que se lee |
| `subtitulo` | texto | Dónde está, de qué chat, cuándo… |
| `icono` | texto | `ext:pdf` → la hoja de color de la extensión; `android.resource://<paquete>/drawable/<nombre>` → un icono de tu app tal cual; con `#RRGGBB` al final (`…/drawable/lista#7048E8`) → el símbolo (de un color, p. ej. blanco) se pinta de ese color sobre un cuadrado suave del mismo color, como los del buscador; vacío → el icono de la app |
| `puntos` | entero 0–1000 | Ordena tus filas entre sí |
| `accion` | texto | Qué pasa al elegirla (ver abajo) |
| `extras` | texto JSON | Los datos de una acción `call:` |
| `copiar` | texto | Lo que copia una pulsación larga (si no hay `menu`) |
| `autocompletar` | texto | Lo que se escribe en la caja al autocompletar (sin la palabra clave) |
| `menu` | texto | Si no está vacío, la pulsación larga pide el menú de esta fila (ver 4) |
| `resaltado` | texto | Posiciones del título que coinciden, en caracteres: `0,1,2,7` |

### `accion`

| Valor | El buscador… |
|---|---|
| una URI (`pindesk://…`, `https://…`, `content://…`) | la abre con `ACTION_VIEW` y se cierra |
| `call:<metodo>` | llama a `contentResolver.call("content://<authorities>", metodo, null, extras)` **en segundo plano, sin abrir tu app**, con `extras` (el JSON de la columna, como `Bundle` de textos, números y booleanos) |
| `consulta:<texto>` | cambia lo escrito a `<palabra> <texto>` y vuelve a buscar (entrar en un proyecto, en una lista de tareas…) |

## 3. Hacer (`call`)

`call(metodo, null, extras)` hace la acción y devuelve un `Bundle` con:

| Clave | Tipo | Qué hace el buscador |
|---|---|---|
| `mensaje` | texto | Lo enseña como aviso corto («Tarea apuntada en Inbox») |
| `cerrar` | booleano (por defecto `true`) | Si se cierra el buscador |
| `consulta` | texto | Si está, cambia lo escrito a `<palabra> <consulta>` y vuelve a buscar (por ejemplo, para ver la lista con la tarea ya marcada) |
| `abrir` | texto | Una URI que abrir después (`ACTION_VIEW`) |
| `error` | texto | Algo salió mal: se enseña y no se cierra |

El buscador llama a `call` desde un hilo propio: puede tardar algo más que una
búsqueda (hasta 5 s), pero conviene contestar ya y terminar el trabajo de disco
en segundo plano.

## 4. Menú de una fila

Con `menu` no vacío, la pulsación larga hace

```kotlin
contentResolver.query(Uri.parse("content://<authorities>/menu"), null, null, arrayOf(menu), null)
```

y enseña esas filas (mismas columnas) en lugar de los resultados: copiar,
abrir la carpeta, quitar la tarea, mover a otra lista…

## 5. Seguridad

El buscador es el paquete **`com.max.flowlauncher`**. Si tus datos son
privados, contesta solo a él:

```kotlin
if (callingPackage != "com.max.flowlauncher") return null
```
