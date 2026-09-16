# Estructura del código — BubuADS

Organización del proyecto, sección por sección. **Regla del proyecto:** el
código (Kotlin), los estilos (CSS) y la lógica de página (JavaScript) viven
**separados** en archivos y carpetas distintas; nunca se mezclan.

```
BubuADS/
│
├── app/
│   ├── build.gradle.kts                 # Configuración del módulo (applicationId, versión)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # Permisos y componentes
│       │   │
│       │   ├── java/com/bubuads/app/   # CÓDIGO (Kotlin)
│       │   │   ├── Config.kt            # Constantes: URLs, User-Agent, TAG
│       │   │   ├── SplashActivity.kt    # Pantalla de inicio
│       │   │   ├── MainActivity.kt      # WebView, inmersivo, insets, ajustes
│       │   │   ├── CrashCatcher.kt      # Registro de fallos
│       │   │   ├── adblock/             # Bloqueo de anuncios
│       │   │   │   ├── Filter.kt        #   Regla compilada
│       │   │   │   ├── FilterCompiler.kt#   Compilador de listas
│       │   │   │   └── AdBlocker.kt     #   Motor y estadísticas
│       │   │   ├── media/
│       │   │   │   └── BackgroundMediaService.kt  # Servicio + MediaSession
│       │   │   └── web/
│       │   │       └── YtWebViewClient.kt         # Red + inyección
│       │   │
│       │   ├── assets/                  # RECURSOS WEB
│       │   │   ├── js/                  # JavaScript (lógica de página)
│       │   │   │   ├── anti-anuncios.js #   Capa 3: bloqueo
│       │   │   │   └── reproduccion.js  #   Capa 3: segundo plano/página/audio
│       │   │   ├── css/                 # CSS (estilos)
│       │   │   │   └── tema.css         #   Ajustes visuales mínimos
│       │   │   └── filtros/             # Listas de bloqueo (.txt)
│       │   │       ├── easylist.txt
│       │   │       ├── easyprivacy.txt
│       │   │       ├── ublock-filters.txt
│       │   │       ├── quick-fixes.txt
│       │   │       └── youtube.txt
│       │   │
│       │   └── res/                     # Recursos Android
│       │       ├── drawable/            #   Logo del splash, icono de ajustes
│       │       ├── mipmap-*/            #   Iconos de la app
│       │       └── values/              #   Cadenas y temas
│       │
│       └── test/java/.../adblock/
│           └── FilterCompilerTest.kt    # Tests del compilador (12)
│
├── Github/                              # Material para publicar en GitHub
│   ├── README.md                        #   README listo para copiar y pegar
│   ├── DOCUMENTACION.md                 #   Documentación
│   ├── ESTRUCTURA.md                    #   Este archivo
│   ├── apks/                            #   APK firmado por versión
│   ├── capturas/                        #   Capturas de pantalla
│   ├── logs/                            #   Cambios por versión
│   └── versiones/                       #   Historial de versiones
│
├── .github/workflows/build.yml          # CI: tests + lint + build
├── README.md                            # README del repositorio
├── DOCUMENTACION.md                     # Documentación principal
├── CHANGELOG.md                         # Historial de cambios
├── AGENTS.md                            # Contexto para colaboradores
├── LICENSE                              # Licencia MIT
└── .gitignore
```

## Convenciones

| Tipo | Dónde | Idioma |
|------|-------|--------|
| Lógica de app | `java/com/bubuads/app/` | Kotlin |
| Lógica de página | `assets/js/` | JavaScript |
| Estilos | `assets/css/` | CSS |
| Listas de bloqueo | `assets/filtros/` | Texto plano |
| Documentación y comentarios | `*.md` y comentarios | Español |

- **Nunca** se escribe CSS dentro de HTML/JS ni JavaScript dentro del CSS.
- Cada sección (bloqueo, segundo plano, tema) tiene su propio archivo.
- Los identificadores de código siguen las convenciones del lenguaje
  (inglés); los **comentarios y la documentación van en español**.
