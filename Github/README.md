<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="BubuADS"/>
</p>

<h1 align="center">BubuADS</h1>

<p align="center">
  <strong>YouTube sin anuncios, simple y estable, en Android</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Versi%C3%B3n-1.1-blue" alt="Versión 1.1"/>
  <img src="https://img.shields.io/badge/Android-7.0%2B-green" alt="Android 7.0+"/>
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/License-MIT-orange" alt="Licencia MIT"/>
  <img src="https://img.shields.io/badge/PRs-welcome-brightgreen" alt="PRs welcome"/>
</p>

<p align="center">
  <a href="https://github.com/JonathanDevHurtado/BubuADS/releases/latest">
    <img src="https://img.shields.io/badge/Descargar-APK-e11d48?style=for-the-badge" alt="Descargar APK"/>
  </a>
</p>

---

## Qué es

**BubuADS** es una aplicación Android (Kotlin) que abre YouTube móvil
(`m.youtube.com`) en un `WebView` con **bloqueo de anuncios en 3 capas** (basado
en las listas reales de uBlock Origin), **reproducción en segundo plano** y
**modo inmersivo**.

La filosofía de esta versión es la **estabilidad**: se conserva la **interfaz
nativa de YouTube** (sin rediseños ni botones personalizados) para no heredar
bugs de maquetación. Solo se añade lo imprescindible: bloquear anuncios, seguir
sonando en segundo plano y aprovechar toda la pantalla.

- Sin root · Sin suscripciones · Sin servicios de Google innecesarios · Código abierto

---

## Capturas

<p align="center">
  <img src="Github/capturas/inicio.png" width="30%" alt="Pantalla de inicio"/>
  &nbsp;&nbsp;
  <img src="Github/capturas/video.png" width="30%" alt="Página de vídeo"/>
</p>

---

## Características

| Característica | Descripción |
|----------------|-------------|
| **Bloqueo de anuncios en 3 capas** | Red (`shouldInterceptRequest`), cosmético (CSS) y watchdog (JS) |
| **Reproducción en segundo plano** | Notificación interactiva con `MediaSession` y `WakeLock` |
| **Música con la pantalla apagada** | Exención de batería + autostart del fabricante desde los ajustes |
| **Modo inmersivo** | Oculta las barras de estado y de navegación |
| **Interfaz nativa** | Se conserva el diseño original de YouTube (barra glass incluida) |
| **Splash y logotipo** | Pantalla de inicio con el logo de la app |
| **Ajustes** | Interruptor del bloqueador, actualizar listas, exención de batería |
| **Compatible** | Android 7.0+ (minSdk 24, targetSdk 35) |

---

## Cómo funciona

### Capa 1 — Red
Cada petición HTTP se comprueba contra las reglas compiladas de EasyList,
EasyPrivacy, uBlock Filters, quick-fixes y reglas propias de YouTube. Si coincide,
se devuelve una respuesta vacía. Los endpoints que el reproductor necesita de
forma síncrona están excepcionados (`@@`) para no romper la reproducción.

### Capa 2 — Cosmético (CSS)
Kotlin compila los selectores CSS de las listas y los inyecta en un `<style>`
propio (`#uo-css`), acotado a `youtube.com`.

### Capa 3 — Watchdog (JavaScript)
Dos módulos inyectados en cada página:

- **`js/anti-anuncios.js`**: elimina nodos de anuncios (`nuke()`), salta anuncios
  de vídeo (`skipAd()`), poda respuestas JSON (`pruneAds()`) y pide reproducción
  sin anuncios (`isInlinePlaybackNoAd`).
- **`js/reproduccion.js`**: mantiene la reproducción en segundo plano, detecta el
  tipo de página (para acotar el CSS) y fuerza el audio siempre activo.

---

## Arquitectura

```
BubuADS/
├── app/src/main/java/com/bubuads/app/
│   ├── Config.kt                     # URLs, User-Agent, constantes
│   ├── SplashActivity.kt             # Splash animado con logo
│   ├── MainActivity.kt               # UI, WebView, inmersivo, ajustes
│   ├── CrashCatcher.kt               # Captura y reporte de crashes
│   ├── adblock/
│   │   ├── Filter.kt                 # Regla de red compilada
│   │   ├── FilterCompiler.kt         # Compilador de listas
│   │   └── AdBlocker.kt              # Gestor principal
│   ├── media/
│   │   └── BackgroundMediaService.kt # Servicio en 2º plano + MediaSession
│   └── web/
│       └── YtWebViewClient.kt        # Bloqueo de red + inyección de módulos
├── app/src/main/assets/
│   ├── js/
│   │   ├── anti-anuncios.js          # Bloqueo de anuncios (JS)
│   │   └── reproduccion.js           # Segundo plano, página y audio (JS)
│   ├── css/
│   │   └── tema.css                  # Ajustes visuales mínimos (CSS)
│   └── filtros/                      # Listas de uBlock Origin + reglas YouTube
└── app/src/test/                     # Tests unitarios (FilterCompiler)
```

---

## Instalación

### Opción 1 — Descargar el APK
Descarga la última versión desde **[Releases](https://github.com/JonathanDevHurtado/BubuADS/releases/latest)**
([BubuADS-v1.1.apk](https://github.com/JonathanDevHurtado/BubuADS/releases/download/v1.1/BubuADS-v1.1.apk))
o usa el APK firmado de [`Github/apks/`](Github/apks/).

### Opción 2 — Compilar desde el código fuente

**Requisitos:** JDK 17, Android SDK (compileSdk 35) y Gradle (wrapper incluido).

```bash
git clone https://github.com/JonathanDevHurtado/BubuADS.git
cd BubuADS
./gradlew :app:assembleRelease
# APK en: app/build/outputs/apk/release/app-release.apk
```

### Instalar en el teléfono

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

---

## Desarrollo

```bash
# Tests + lint + APK release (comando completo)
./gradlew :app:testReleaseUnitTest :app:lintRelease assembleRelease

# Validar la sintaxis de los módulos JS
node --check app/src/main/assets/js/anti-anuncios.js
node --check app/src/main/assets/js/reproduccion.js
```

La firma se lee de `signing.properties` (no versionado) y
`keystore/utubeorigin.jks`.

---

## Privacidad

- No se envía ningún dato a servidores propios.
- Las listas de bloqueo se descargan de sus fuentes oficiales (EasyList y
  uBlock Origin) para actualizarse.

---

## Estructura del repositorio

| Ruta | Descripción |
|------|-------------|
| `app/` | Código fuente Android |
| `Github/apks/` | APK firmado por versión |
| `Github/logs/` | Cambios detallados por versión |
| `Github/versiones/` | Historial de versiones |
| `Github/ESTRUCTURA.md` | Estructura y organización del código |
| `DOCUMENTACION.md` | Documentación técnica completa |
| `AGENTS.md` | Contexto para colaboradores/agentes |

---

## Historial de versiones

### v1.1 — Fix de memoria al compilar filtros
- Corregido el `OutOfMemoryError` al compilar las listas: más heap (512 MB) y
  compilación en streaming (menos memoria).

### v1.0 — Versión estable inicial
- Bloqueo de anuncios en 3 capas.
- Reproducción en segundo plano (pantalla apagada o jugando).
- Modo inmersivo y splash con logo.
- Interfaz nativa de YouTube (sin rediseños).
- Revisión de seguridad: permisos del navegador y puente JS restringidos a YouTube.

El detalle está en [`CHANGELOG.md`](CHANGELOG.md) y
[`Github/logs/`](Github/logs/).

---

## Tecnologías

| Componente | Tecnología |
|------------|------------|
| Lenguaje | Kotlin 2.0 |
| Target / Min SDK | 35 (Android 15) / 24 (Android 7.0) |
| WebView | Chromium (del sistema) |
| Filtros | uBlock Origin lists |
| Build | Gradle + JDK 17 |

---

## Contribuir

1. Abre un issue para discutir el cambio.
2. Crea una rama (`git checkout -b feature/nueva-funcionalidad`).
3. Haz commit (`git commit -m 'Agregar nueva funcionalidad'`).
4. Push (`git push origin feature/nueva-funcionalidad`).
5. Abre un Pull Request.

---

## Licencia

Este proyecto está bajo la licencia **MIT**. Ver [`LICENSE`](LICENSE).

---

## Contacto

- **Autor:** [Jonathan Hurtado](https://github.com/JonathanDevHurtado)
- **Email:** JonathanHurtadoDev@proton.me

---

<p align="center">
  Hecho con Kotlin y pasión por YouTube sin anuncios
</p>
