# Proveedores de búsqueda: integrar tu app en Flow Buscador

Cualquier app de Android puede poner sus resultados en el buscador (sus
archivos, notas, chats…) **sin plugins**: declara un `ContentProvider` y el
buscador le pregunta mientras se escribe. Es lo que hace PixPin.

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
    <!-- Nombre que se enseña (opcional; si no, el de la app). -->
    <meta-data android:name="com.max.flowlauncher.nombre" android:value="PixPin" />
</provider>
```

El buscador encuentra los proveedores con
`PackageManager.queryIntentContentProviders(Intent("com.max.flowlauncher.PROVEEDOR"), GET_META_DATA)`.

## 2. Contestar

El buscador llama a

```kotlin
contentResolver.query(
    Uri.parse("content://<authorities>/buscar"),
    null, null, arrayOf(textoBuscado), null, cancellationSignal,
)
```

- `selectionArgs[0]`: lo buscado, **sin** la palabra clave (`tesis` de `p tesis`).
- Hay que contestar **rápido**: el buscador espera como mucho **400 ms** y
  cancela (`cancellationSignal`) si se sigue escribiendo. Busca en un índice en
  memoria, no recorras el disco en cada consulta.
- Como mucho 30 filas.

Columnas del `Cursor` (las que falten se toman vacías):

| Columna | Tipo | Qué es |
|---|---|---|
| `titulo` | texto | Lo que se lee: `Tesis final.pdf` |
| `subtitulo` | texto | Dónde está o qué es: `Proyecto Tesis · 12 oct` |
| `icono` | texto | `ext:pdf` para el cuadro de color de la extensión (los colores de PixPin); vacío = el icono de la app |
| `puntos` | entero 0–1000 | Más es mejor; ordena tus resultados entre sí |
| `accion` | texto | Una URI que se abre con `ACTION_VIEW` al elegirlo: `pindesk://atajo/archivo/<id>` |
| `copiar` | texto | Opcional: lo que se copia con una pulsación larga |

## 3. Seguridad

El buscador se identifica como el paquete **`com.max.flowlauncher`**. Si tus
datos son privados, comprueba quién llama y contesta vacío a cualquier otro:

```kotlin
override fun query(...): Cursor? {
    if (callingPackage != "com.max.flowlauncher") return null
    ...
}
```
