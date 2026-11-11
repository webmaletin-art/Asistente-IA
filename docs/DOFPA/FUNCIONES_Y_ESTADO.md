# DOFPA — Funciones y estado (registro honesto)

Estados: ✅ implementado y probado · 🟡 implementado, sin probar en un teléfono · 🚧 parcial · ⬜ pendiente (solo diseñado o nada)

## Revisión integral (esta entrega)
| Función | Estado | Cómo se verificó |
|---|---|---|
| Procesamiento del corpus completo (14 RAR → 95 archivos, duplicados, OCR, estructura) | ✅ | `tools/dofpa/`; informe privado de extracción (no está en el repo) |
| Paquete maestro único `.dofpa` (SQLite + metadatos + hash) | ✅ | 76 documentos, 6.323 fragmentos, 1,4 MB; hash verificado con el mismo código que usa la app |
| Importar el paquete en la app (verifica formato/versión/hash, reindexa, copia de seguridad, restaurar, progreso, sin bloquear la UI) | 🟡 | Compila; la verificación y el reindexado se probaron en JVM con SQLite real. Falta probarlo en el teléfono |
| Buscador: leyes y artículos exactos, BM25, títulos/capítulos, frases, sinónimos, alias de leyes, errores de tipeo, seguimiento, "no encontrado" | ✅ | 14 casos de aceptación (corpus ficticio) + 27 contra el corpus real; todos pasan |
| "Explica la 23.737" responde sobre la 23.737 (no sobre la 9169) | ✅ | `RealCorpusEvalTest.e01`, `SearchAcceptanceTest.c01` |
| "¿En qué parte del Manual…?" devuelve la página/sección real, no la portada | ✅ | `e08`, `c03`, `c10` |
| Velocidad < 500 ms en local | ✅ (JVM) | 12–170 ms sobre el corpus real en la JVM; en el teléfono puede ser más lento: 🟡 |
| Gemini: errores reales (clave inválida, permiso, modelo, cuota diaria vs por minuto, servidor, red, TLS, timeout) | ✅ | `GeminiErrorsTest` con respuestas reales capturadas del servidor |
| Gemini: reintentos limitados con espera progresiva; los errores permanentes se muestran enseguida | ✅ | Revisión de código + tests |
| "Probar conexión" real + "Diagnóstico completo" | 🟡 | Hace una petición real; sin una clave válida en este entorno no se pudo ver una respuesta OK |
| Clave Gemini en Android Keystore, nunca en el código/APK | ✅ | Sin cambios respecto de la versión anterior |
| Consulta: respuesta directa + Ver más, fuentes ordenadas con página/capítulo, Copiar/Guardar/Compartir | 🟡 | Compila; no se probó en un teléfono |
| Más fácil / Ejemplo / Localizar / ¿Sigue vigente? / Preguntar a Google (AI Mode) | 🟡 | Compila |
| Sugerencias plegables que se colocan en el campo (no se envían solas) | 🟡 | Compila |
| Leyes, artículos y conceptos tocables (explicación sin salir del chat) | 🟡 | Compila |
| Varias preguntas en un mensaje | ✅ | `c05` |
| Etiqueta de motor usado (Academia / IA / Google / no encontrado) | 🟡 | Compila |
| Mi Cuaderno | 🟡 | Ya existía (Room); sin cambios de fondo |
| AI Quick Assist sin cambios de comportamiento | ✅ | Sus tests unitarios pasan |

## Pendiente (NO está hecho)
| Función | Estado |
|---|---|
| Academia completa (unidades, progreso), exámenes, V/F, opción múltiple, relacionar, artículos | ⬜ (hay una pantalla con la lista de materias) |
| Entrenamiento 5 min, repetición espaciada, conceptos dominados, ¿Sabías que?, juegos | ⬜ |
| Modo Guía, Actas inteligentes, Modo calle | ⬜ |
| DOFPA Visual (proyecto HTML/CSS/JS aparte) | ⬜ |
| Pruebas en un teléfono real (importación, OCR, rendimiento, Gemini con una clave real) | ⬜ |
