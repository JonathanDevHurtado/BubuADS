# Changelog

Todos los cambios notables de **BubuADS** se documentan en este archivo.

El formato se basa en [Keep a Changelog](https://keepachangelog.com/es/1.0.0/),
y este proyecto adhiere a [Versionado Semántico](https://semver.org/lang/es/).

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
