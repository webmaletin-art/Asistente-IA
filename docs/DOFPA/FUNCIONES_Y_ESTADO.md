# DOFPA — Matriz de funciones y estado

Estados: ✅ implementado · 🧪 probado (compila/test) · 🚧 en progreso · ⬜ pendiente · 🔒 bloqueado

## Etapa 1 (esta entrega)
| Función | Estado | Notas |
|---|---|---|
| Base de conocimiento local (Room + FTS4) | ✅ 🧪 | Todo en el dispositivo. `dofpa/data` |
| Importador de documentos (SAF, elige el usuario) | ✅ | PDF, DOCX, PPTX, TXT/MD, imágenes |
| Extracción de texto en el teléfono | ✅ | PDF (PDFBox), DOCX/PPTX (XML), TXT, imagen (OCR local) |
| `.doc` antiguo / sin capa de texto | 🔒 | Reporta error honesto; pide convertir a PDF/DOCX |
| Segmentación con anclaje (página/sección/artículo) | ✅ 🧪 | `Chunker` detecta ARTÍCULO/TÍTULO/CAPÍTULO/UNIDAD |
| Buscador universal local (FTS, prefijos, sinónimos básicos) | ✅ | `DofpaSearchScreen` |
| Chat de Consulta con citas | ✅ | Respuesta cita documento·página·sección |
| Enrutamiento local / Gemini / auto + fallback honesto | ✅ | `DofpaBrain`; Gemini recibe solo fragmentos |
| Respuesta rápida + ampliar | ✅ | Acordeón "Ver más" |
| Preguntas sugeridas plegables (se colocan, no se envían) | ✅ | Derivadas localmente, sin gastar IA |
| Acciones: Copiar / Guardar en Cuaderno / Abrir AI Mode | ✅ | AI Mode solo si el usuario activa Internet |
| Mi Cuaderno (notas locales) | ✅ | Crear desde el chat, borrar |
| Biblioteca (gestión, borrar demo) | ✅ | Estado por documento |
| Ajustes DOFPA (motor, Internet, respuesta rápida) | ✅ | |
| Datos ficticios de prueba | ✅ | `DemoCorpus`; borrables |
| Entrada desde AI Quick Assist | ✅ | Botón "DOFPA" en la pantalla principal |
| AI Quick Assist intacto (regresión) | 🧪 | Tests unitarios verdes |

## Próximas etapas (pendientes)
| Función | Estado |
|---|---|
| Conceptos tocables dentro del texto (modal sin salir del chat) | ⬜ |
| Modo Guía (checklist con fundamento y estados) | ⬜ |
| Actas inteligentes (ayuda por campo, borrador DOCX/PDF) | ⬜ |
| Academia (Enseñame, unidades, progreso) | ⬜ |
| Exámenes / Entrenamiento 5 min / preguntas al azar | ⬜ |
| Niveles de conocimiento y repaso espaciado ("Lo quiero recordar") | ⬜ |
| Contraste con Internet lado a lado + vigencia | ⬜ |
| Recursos visuales (catálogo, solo autorizados) / DOFPA Visual | ⬜ |
| Ranking de búsqueda mejor (bm25/FTS5), sinónimos/glosario | ⬜ |
| Pruebas instrumentadas en dispositivo | ⬜ (requiere dispositivo) |

## Verificado realmente
- `./gradlew assembleDebug assembleRelease` → OK (APK ~81 MB).
- `./gradlew testDebugUnitTest` → OK (tests de AI Quick Assist).
- NO probado en dispositivo físico (importación real, OCR de escaneos, rendimiento): pendiente.
