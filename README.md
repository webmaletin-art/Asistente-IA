# AI Quick Assist

Asistente rápido para analizar contenido en Android: navegador integrado + burbuja flotante global.
Kotlin · Jetpack Compose · Android nativo (minSdk 30).

**Flujo:** seleccionar → analizar → ✓ → ver respuesta → cerrar. Prioridad siempre: **TEXTO → OCR → IMAGEN**.

## Motores
| Modo | Qué hace |
|---|---|
| Visión general creada por IA | Consulta fuentes públicas (DuckDuckGo Instant Answer y Wikipedia) por sus APIs abiertas. Sin scraping, sin APIs privadas. Si no hay resumen de IA disponible usa los resultados web. Funciona sin Gemini. |
| Gemini (opcional) | Usa **tu propia** clave y cuota. Sin clave en el repositorio ni en el APK. |
| Mejor respuesta — ambos | Ejecuta los dos, compara y compone una respuesta sin contradicciones; indica incertidumbre si difieren. Si Gemini no está configurado usa solo el motor web y lo avisa. |

"Solo Gemini" nunca cambia a otro motor: si no está configurado muestra *Gemini no está configurado* + botón **Configurar Gemini**.

## Arquitectura (`app/src/main/java/com/aiquickassist`)
- `data/` — modelos, `Settings` (prefs observables), `SecureStore` (Android Keystore AES‑GCM), `HistoryStore` (JSON local).
- `engine/` — `QuestionParser` (tipo + opciones), `WebEngine`, `GeminiEngine`, `Analyzer` (modos, fallback, caché), `Assist` (estado de UI).
- `capture/` — `ScreenReader` (texto vía accesibilidad), `Ocr` (ML Kit local).
- `service/` — `OverlayService` (burbuja, menú, panel), `BubbleView/Renderer`, `SelectionView` (rectángulo OCR), `AssistAccessibilityService`.
- `ui/` — pantallas Compose estilo wireframe (Material, blanco/gris/negro).

## Uso
- **Toque** en la burbuja: ejecuta la herramienta actual (Texto por defecto). **Presión larga**: menú Texto · OCR · Imagen · Buscar 🔍 · Navegador · Configuración.
- **Texto**: lee el texto visible (o la selección) por accesibilidad, detecta pregunta/opciones y consulta. No hay captura ni imagen. Si no hay texto, pasa a OCR.
- **OCR**: captura única, rectángulo con esquinas ajustables, OCR local y envío **solo del texto**.
- **Imagen**: igual, pero envía el recorte a Gemini (solo cuando hace falta o lo eliges). El motor web solo analiza texto.
- **Búsqueda manual (lupa)**: la barra aparece solo al tocar 🔍; parte de la pregunta detectada y se puede editar.
- **Resultado**: `✓ B` + respuesta; toca o desliza hacia arriba para ver pregunta, explicación, fuentes y bloques por motor.
- **Burbuja**: tamaño, transparencia (100 %→5 %, sigue siendo táctil), color/personalizado, 8 estilos, modo discreto, ocultar durante scroll y mostrar al detener (independientes).
- **Navegador**: WebView con URL, atrás/adelante/recargar, pestañas y botón *Analizar* (selección o texto visible; radios/casillas de formularios se tratan como opciones).
- **Historial** local opcional (solo texto); borrar uno / todo. **Privacidad**: borrar historial, credenciales, restablecer.

## Permisos
Mostrar sobre otras apps (burbuja) · Accesibilidad (leer texto y capturar pantalla bajo demanda, y detectar scroll) · Notificaciones (aviso del servicio). No hay captura ni OCR continuos.

## Configurar Gemini
Configuración → Gemini → pega tu clave de [Google AI Studio](https://aistudio.google.com/apikey) → *Guardar* → *Probar conexión*. Se cifra con Android Keystore, se muestra enmascarada y puede eliminarse. El modelo es editable (por defecto `gemini-2.5-flash`).

## Compilar
```
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk (firmado con clave debug, sin secretos)
./gradlew testDebugUnitTest
```
Requiere JDK 17 y Android SDK 35.

## GitHub Actions y descarga del APK
`.github/workflows/build-apk.yml` corre en cada push y manualmente (*Actions → Build APK → Run workflow*). El APK queda como **artifact** (`app-debug`, `app-release`) en la página de la ejecución. No usa secretos.

## Limitaciones reales
- No existe una API pública de la “Visión general creada por IA” de buscadores: el motor web usa DuckDuckGo Instant Answer y Wikipedia y elige la opción por coincidencia con las fuentes (heurístico, menos fiable que Gemini; V/F y traducción son aproximados).
- Extracción de texto: depende de que la app exponga su contenido a accesibilidad; si no, se usa OCR. Algunas apps (DRM/`FLAG_SECURE`) bloquean la captura.
- Captura vía accesibilidad (Android 11+); Android limita a ~1 captura por segundo.
- OCR: ML Kit (alfabeto latino). Con varias preguntas en pantalla se analiza la primera con opciones; usa OCR/selección para acotar.
- El “Modo escudo” es solo una presentación minimalista de la burbuja; no evade controles de terceros.
