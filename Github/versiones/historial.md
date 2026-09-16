# Historial de versiones — BubuADS

## v1.0 (2026-09-14) — Versión inicial
- **YouTube nativo + sin anuncios**: se elimina toda la interfaz personalizada
  (barra de acciones 2×2, bottom nav propia, botón de Shorts, mini-reproductor,
  buscador propio) para conservar la interfaz original y no heredar bugs.
- **Bloqueo de anuncios en 3 capas**: red (`shouldInterceptRequest`), cosmético
  (CSS compilado) y watchdog JS (`anti-anuncios.js`).
- **Reproducción en segundo plano**: `BackgroundMediaService` en primer plano con
  `MediaSession` + `WakeLock` indefinido mientras suena.
- **Música con la pantalla apagada / jugando**: ajustes con exención de batería y
  autostart del fabricante.
- **Modo inmersivo** y **splash con logo**.
- **Código organizado**: JS dividido por secciones (`anti-anuncios.js`,
  `reproduccion.js`), CSS en `tema.css`, listas en `filtros/`.
- **Seguridad**: permisos del navegador y puente JS restringidos a YouTube.
- versionCode 1 / "1.0"
- APK: `apks/BubuADS-v1.0.apk`
