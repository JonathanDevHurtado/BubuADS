# BubuADS — Contexto para colaboradores

Guía rápida del proyecto. La documentación completa está en
[`DOCUMENTACION.md`](DOCUMENTACION.md).

## Qué es

App Android (Kotlin) que abre YouTube móvil (`m.youtube.com`) en un `WebView`
con bloqueo de anuncios, reproducción en segundo plano y modo inmersivo,
**conservando la interfaz nativa de YouTube**.

## Comandos

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk use java 17.0.13-tem
export ANDROID_HOME="$HOME/android-sdk"

# Tests + lint + APK release (comando completo)
./gradlew :app:testReleaseUnitTest :app:lintRelease assembleRelease

# Validar la sintaxis de los módulos JS
node --check app/src/main/assets/js/anti-anuncios.js
node --check app/src/main/assets/js/reproduccion.js
```

## Reglas críticas

- **No alterar la maquetación de YouTube.** Todos los bugs de la línea 3.x
  venían de reestructurar la barra de acciones o de crear barras propias. Aquí
  no se toca el DOM de YouTube salvo para **eliminar anuncios**.
- **`shouldInterceptRequest` corre en un hilo de red**: nunca llamar a métodos de
  `WebView` ahí. Usar `currentPageUrl` (`@Volatile`).
- **`WebResourceRequest` no expone el cuerpo del POST**: la reescritura de la API
  (`isInlinePlaybackNoAd`) se hace en JS (`anti-anuncios.js`).
- **No bloquear** los endpoints que el reproductor necesita de forma síncrona
  (`youtubei/v1/player/ad_break`, `get_midroll_`, `ad_status.js`, `pagead/ads`,
  `pagead/id`): están excepcionados con `@@` en `filtros/youtube.txt`.
- **Separación estricta**: código en `java/`, JavaScript en `assets/js/`, CSS en
  `assets/css/`, listas en `assets/filtros/`. Nunca mezclar.
- **Idioma**: comentarios y documentación en español; identificadores de código
  en el idioma del lenguaje.
- **Seguridad**: el puente JS solo acepta llamadas desde YouTube
  (`isTrustedPage()`); `onPermissionRequest` solo concede a YouTube.
- **Versionado**: `versionCode`/`versionName` en `app/build.gradle.kts`.

## Estructura

Ver [`Github/ESTRUCTURA.md`](Github/ESTRUCTURA.md).

## Flujo de una iteración

1. Reproducir el problema en el dispositivo.
2. Investigar (logcat `adb logcat -s BubuADS`).
3. Implementar y validar con `node --check` + `./gradlew ... assembleRelease`.
4. Actualizar `CHANGELOG.md`, `Github/logs/` y `Github/versiones/historial.md`.
5. Copiar el APK a `Github/apks/` y firmarlo.
