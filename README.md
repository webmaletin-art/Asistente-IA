# AI Quick Assist

Asistente rápido para analizar contenido en Android: navegador integrado + burbuja flotante global.
Kotlin · Jetpack Compose · Android nativo (minSdk 30).

**Flujo:** seleccionar → analizar → ✓ → ver respuesta → cerrar. Prioridad siempre: **TEXTO → OCR → IMAGEN**.

## Motores
| Modo | Qué hace |
|---|---|
| Visión general de Google | Busca la pregunta en Google (WebView, tu sesión) y lee la **verdadera** «Visión general creada por IA» si Google la muestra. Si no existe: «Visión general de Google no disponible» + **Ver resultados de Google**. No usa DuckDuckGo, Wikipedia ni «primer resultado». |
| Google AI Mode | `udm=50`. Con imagen, entrega el recorte real al selector de archivos de la propia página de Google. |
| Gemini (opcional) | Tu propia clave y cuota (Android Keystore). Texto o imagen + pregunta. |
| Mejor respuesta — ambos | Google y Gemini por separado, cada uno con su etiqueta, más una comparación (coinciden / difieren). |

Cada respuesta lleva su fuente (`GOOGLE_AI_OVERVIEW`, `GOOGLE_AI_MODE`, `GOOGLE_SEARCH`, `GEMINI`, `OCR_LOCAL`, `UNKNOWN`) hasta la UI. Si Google no responde no se inventa nada: *«No se pudo obtener una respuesta confiable de Google»* + botón para abrir Google / AI Mode.

## Flujos
- **Texto**: seleccionas la pregunta, tocas «Copiar» y tocas la burbuja → se pega solo (cada copia se usa una vez) → motor elegido. Preguntas, opciones, verdadero/falso y afirmaciones («¿es correcta?»). Si no hay texto nuevo copiado, lo dice; nunca adivina a partir de la página.
- **OCR**: región → OCR local → texto → motor elegido. **Solo texto: nunca envía la imagen.**
- **Imagen**: región → **recorte real** (PNG/JPEG en caché + `content://` por FileProvider) → Google AI Mode (o Gemini). El OCR solo aporta texto auxiliar. Puedes escribir una pregunta opcional en el selector.
- **Imagen + pregunta**: recorte + pregunta → AI Mode / Gemini.

## Arquitectura (`app/src/main/java/com/aiquickassist`)
- `data/` — modelos, `Settings` (prefs observables), `SecureStore` (Android Keystore AES‑GCM), `HistoryStore` (JSON local).
- `engine/` — `QuestionParser`, `GoogleEngine` (WebView + lectura de Visión general / AI Mode), `GoogleText` (consultas y limpieza), `GeminiEngine`, `Analyzer`, `Assist` (estado de UI).
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
- Google no ofrece API para la Visión general ni AI Mode: se leen desde la página en un WebView. Si Google cambia su HTML, muestra CAPTCHA/consentimiento o no ofrece Visión general para esa consulta, la app lo dice y abre Google; nunca evade protecciones. La subida automática de imagen a AI Mode depende de que Google exponga el botón de adjuntar; si falla, «Abrir Google AI Mode» deja el recorte listo para el selector de archivos.
- Extracción de texto: depende de que la app exponga su contenido a accesibilidad; si no, se usa OCR. Algunas apps (DRM/`FLAG_SECURE`) bloquean la captura.
- Captura vía accesibilidad (Android 11+); Android limita a ~1 captura por segundo.
- OCR: ML Kit (alfabeto latino). Con varias preguntas en pantalla se analiza la primera con opciones; usa OCR/selección para acotar.
- El “Modo escudo” es solo una presentación minimalista de la burbuja; no evade controles de terceros.
