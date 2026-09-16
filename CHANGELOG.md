# Changelog

Todos los cambios notables de **BubuADS** se documentan en este archivo.

El formato se basa en [Keep a Changelog](https://keepachangelog.com/es/1.0.0/),
y este proyecto adhiere a [Versionado Semántico](https://semver.org/lang/es/).

## [1.1] - 2026-09-15

### Corregido
- **`OutOfMemoryError` al compilar las listas de filtros** (al volver a la app
  tras un uso intensivo). El bloqueador quedaba sin reglas hasta el siguiente
  intento. Ahora hay más heap, la compilación es en streaming (menos memoria) y
  el OOM se maneja sin romper.

### Cambiado
- `android:largeHeap="true"`: el límite de memoria sube de 256 MB a 512 MB.
- `FilterCompiler.compile()`: compila red y cosmético en una sola pasada, leyendo
  las listas línea a línea (sin guardarlas todas en memoria).

### Añadido
- Test `compilacionEnStreamingEquivaleALaPorListas` (13 tests en total).

## [1.0] - 2026-09-14

### Añadido
- Bloqueo de anuncios en 3 capas (red, cosmético y watchdog JS).
- Reproducción en segundo plano con `MediaSession` y notificación interactiva.
- `WakeLock` indefinido mientras suena: música con la pantalla apagada o jugando.
- Modo inmersivo permanente (oculta las barras del sistema).
- Splash animado con el logotipo.
- Sección **Ajustes → Segundo plano**: exención de batería y autostart.
- Tests unitarios del compilador de filtros (12).

### Cambiado
- Interfaz **nativa** de YouTube (se retiran los rediseños de la línea 3.x).
- Código reorganizado: JavaScript dividido en `anti-anuncios.js` y
  `reproduccion.js`; CSS en `tema.css`; listas en `filtros/`.

### Seguridad
- `onPermissionRequest` solo concede cámara/micrófono a YouTube.
- El puente JS (`BubuBridge`) solo acepta llamadas desde páginas de YouTube.
