# Generador del paquete maestro DOFPA (`.dofpa`)

Se ejecuta **fuera del repositorio y fuera de CI**, sobre el material del usuario. El material y el paquete
resultante **nunca** se suben a este repositorio público (lo bloquea `.gitignore`).

Requisitos (host): `python3`, `PyMuPDF` (fitz), `tesseract-ocr` + `tesseract-ocr-spa`, `antiword`/`catdoc`, `unrar`.

```bash
# 1) Extraer el RAR multiparte en una carpeta privada (verifica integridad)
unrar t material.part01.rar && unrar x material.part01.rar raw/
# 2) Inventario: hash SHA-256, tipo, duplicados exactos
python3 -I inventory.py raw/ inventario.json
# 3) Extracción de texto por página (texto nativo; OCR en español para páginas escaneadas); reanudable
OMP_THREAD_LIMIT=1 python3 -I extract.py inventario.json raw/ pages/
# 4) Paquete maestro: limpieza, estructura (capítulo/sección/artículo), materia, ley, conceptos, relaciones
python3 -I build_package.py inventario.json pages/ out/ 2026.11.11
# → out/DOFPA_corpus_<versión>.dofpa  (SQLite comprimido con gzip) + out/informe_extraccion.json
```

Formato: SQLite `meta(format=dofpa-corpus, schema=2, corpus_version, content_sha256…)`, `documents`, `passages`
(texto COMPLETO con página, capítulo, sección, artículo), `concepts`, `relations`. El índice de búsqueda (FTS4 +
vocabulario) lo construye la app al importar, con la misma normalización que usa para consultar
(`dofpa/search/Norm.kt`). `content_sha256` = SHA-256 de `id|doc_id|page|text\n` en orden de id; la app lo verifica.
