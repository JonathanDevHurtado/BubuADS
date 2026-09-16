# BubuADS — Documentación técnica

**Versión:** 1.0 (versionCode 1)
**Plataforma:** Android 7.0+ (minSdk 24, targetSdk 35)
**Lenguaje:** Kotlin 2.0 + JavaScript + CSS

---

## 1. Objetivo

BubuADS es un contenedor (`WebView`) de YouTube móvil con **bloqueo de
anuncios**, **reproducción en segundo plano** y **modo inmersivo**, manteniendo
la **interfaz nativa** de YouTube para garantizar estabilidad.

La regla de oro del proyecto: **no alterar la maquetación de YouTube**. Todos los
bugs de versiones anteriores venían de reestructurar la barra de acciones o de
crear barras propias. Aquí no se hace nada de eso.

---

## 2. Arquitectura general

```
MainActivity (Activity)
├── SplashActivity ......... pantalla de inicio con el logo
├── WebView (MediaWebView) . carga m.youtube.com
│   ├── YtWebViewClient .... bloqueo de red + inyección de módulos
│   └── BubuBridge ........ puente JS → Kotlin (estado de reproducción)
├── AdBlocker .............. motor de filtros (red + CSS cosmético)
└── BackgroundMediaService . servicio en primer plano + MediaSession
```

---

## 3. Bloqueo de anuncios (3 capas)

### Capa 1 — Red
`YtWebViewClient.shouldInterceptRequest()` comprueba cada petición (excepto el
frame principal) contra las reglas compiladas por `AdBlocker`. Si coincide, se
devuelve una respuesta vacía.

> Importante: `shouldInterceptRequest` corre en un hilo de red. **Nunca** se
> llaman métodos de `WebView` ahí; se usa `currentPageUrl` (cacheada con
> `@Volatile`) para conocer la página actual.

Las reglas propias de YouTube (`filtros/youtube.txt`) excepcionan (`@@`) los
endpoints que el reproductor necesita de forma síncrona (`youtubei/v1/player/ad_break`,
`get_midroll_`, `ad_status.js`, `pagead/ads`, `pagead/id`); si se bloquean,
YouTube niega la reproducción.

### Capa 2 — Cosmético (CSS)
`AdBlocker.cosmeticCss()` devuelve los selectores CSS compilados de las listas.
Kotlin los inyecta en un `<style id="uo-css">`.

### Capa 3 — Watchdog (JavaScript)
Dos módulos separados (ver `assets/js/`):

| Módulo | Responsabilidad |
|--------|-----------------|
| `anti-anuncios.js` | `nuke()`, `skipAd()`, `pruneAds()` (parchea `JSON.parse`, `fetch` y `XHR`), `isInlinePlaybackNoAd`, `pruneGlobals()` |
| `reproduccion.js` | Neutraliza `visibilitychange`, parchea `play`/`pause`, fuerza play en segundo plano, reporta el estado por el puente, detecta pantalla completa y fuerza el audio |

`isInlinePlaybackNoAd` es **crítico**: sin él YouTube aplica un "backoff"
(~80 % de la duración del anuncio) y el vídeo se queda cargando. Se inyecta
`"isInlinePlaybackNoAd":true` dentro de `contentPlaybackContext` en el cuerpo de
las peticiones `youtubei/v1/player`, `get_watch` y `reel_watch_sequence`.

---

## 4. Reproducción en segundo plano

- **`BackgroundMediaService`**: servicio en primer plano (`foregroundServiceType="mediaPlayback"`)
  con `MediaSession`, notificación interactiva (pausar / siguiente / anterior) y
  `WakeLock PARTIAL_WAKE_LOCK` **indefinido mientras suena** (se libera al pausar
  o cerrar).
- **`android:stopWithTask="false"` + `onTaskRemoved()`**: la música no se corta
  al cerrar la tarea desde "recientes".
- **Exención de batería**: en MIUI/Doze el sistema mata el proceso en segundo
  plano. La sección **Ajustes → Segundo plano** permite pedir la exención
  (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) y abrir el *autostart* del
  fabricante.
- **`bgKeepAliveRunnable`** (Kotlin, cada 1,5 s): fuerza `play()` con
  `evaluateJavascript` y, como respaldo, `loadUrl("javascript:...")`.

---

## 5. Interfaz

- **Modo inmersivo**: `hideSystemBars()` oculta las barras de estado y de
  navegación (se re-aplica periódicamente).
- **Insets**: `applyInsetsPadding()` evita que el contenido quede bajo las barras
  del sistema (especialmente en Shorts).
- **Ajustes** (botón flotante): interruptor del bloqueador, actualizar listas,
  exención de batería y autostart.
- **Splash**: `SplashActivity` muestra el logo y salta a `MainActivity`.

---

## 6. Seguridad

- `usesCleartextTraffic="false"` y `mixedContentMode = NEVER_ALLOW`.
- `allowFileAccess = false`.
- **Permisos del navegador** (`onPermissionRequest`): solo se conceden a
  `youtube.com` / `google.com`.
- **Puente JS** (`BubuBridge`): solo acepta llamadas si la página actual es de
  YouTube (`isTrustedPage()`).
- Componentes no exportados salvo el lanzador.
- Las credenciales de firma viven en `signing.properties` (no versionado).

---

## 7. Estructura del código

Ver [`Github/ESTRUCTURA.md`](Github/ESTRUCTURA.md).

---

## 8. Compilación y pruebas

```bash
./gradlew :app:testReleaseUnitTest :app:lintRelease assembleRelease
node --check app/src/main/assets/js/anti-anuncios.js
node --check app/src/main/assets/js/reproduccion.js
```

---

## 9. Limitaciones conocidas

- Depende de la maquetación de `m.youtube.com`; si YouTube cambia el DOM, los
  selectores cosméticos pueden necesitar ajustes.
- La reproducción en segundo plano en MIUI requiere activar la exención de
  batería y el autostart (Ajustes → Segundo plano).
- El `User-Agent` está fijado a una versión de Chrome; puede requerir
  actualización con el tiempo.
