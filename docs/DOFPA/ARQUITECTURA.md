# DOFPA — Arquitectura (paquete `com.aiquickassist.dofpa`)

- `data/` — persistencia local
  - `Entities.kt` — `DofpaDocument`, `DofpaPassage`, `PassageFts` (FTS4), `DofpaNote`, `PassageHit`
  - `Dao.kt` — documentos, pasajes (búsqueda FTS), notas
  - `DofpaDb.kt` — Room DB (`dofpa.db`)
  - `DofpaRepo.kt` — importar / buscar / borrar / seed; único punto de acceso
  - `DemoCorpus.kt` — datos ficticios de prueba
- `ingest/` — extracción en el dispositivo
  - `TextExtractor.kt` — PDF (PDFBox), DOCX/PPTX (XML), TXT/MD, imagen (OCR local)
  - `Chunker.kt` — fragmentos citables con anclaje página/sección
- `engine/`
  - `DofpaBrain.kt` — recupera fragmentos locales → responde (local / Gemini con fragmentos / auto) → cita fuentes
- `ui/` — `DofpaActivity` (navegación propia + importador SAF), Home, Chat, Buscar, Biblioteca,
  Lector de documento, Mi Cuaderno, Ajustes, stubs de secciones futuras.

Reutiliza de AI Quick Assist (sin modificar su comportamiento): `engine.GeminiEngine` (con un
nuevo `complete()` de texto libre), `engine.GoogleText` (URL de AI Mode), `engine.TextUtil`,
`capture.Ocr`, el tema Compose (`ui.*`) y el almacenamiento seguro de la clave Gemini.

Dependencias nuevas: Room 2.6.1 (+KSP), PDFBox-Android 2.0.27. minSdk 30, compileSdk 35.
