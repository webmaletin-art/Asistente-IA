# DOFPA — Diagnóstico y correcciones (buscador y Gemini)

## 1. Buscador: qué fallaba y por qué
| Síntoma | Causa | Corrección |
|---|---|---|
| "Explica la 23.737" respondía sobre la Ley 9169 | El índice FTS no distinguía números de ley: "23.737" se partía en "23" y "737", y sin orden por estructura ganaba cualquier texto con palabras parecidas | Normalización única (`Norm`): "23.737" → `23737`; columna `refs` con `ley23737`/`art14`; el texto propio de la ley pedida suma mucho; los textos que solo la citan, poco; los de otra ley restan |
| "¿En qué parte del Manual…?" devolvía la portada | El nombre del documento coincidía con la portada y el índice, y el filtro por documento se aplicaba después de recortar a los mejores candidatos de todo el corpus | El documento nombrado como contenedor se vuelve un filtro aplicado ANTES de ordenar; portadas e índices restan; se usa la frase del tema ("fuero de lucha contra el narcotráfico") |
| Artículos mal ubicados | Los fragmentos no guardaban el número de artículo | Cada artículo es un fragmento con `section`/`article`; ante varios "Art. 1" se prefiere el del texto principal, no el de un anexo |
| Inventaba respuestas cuando no había nada | No había umbral | Si ningún fragmento cubre lo esencial de la consulta, se dice "No encontré esto en el material cargado" |

Orden de prioridad: ley/artículo exactos → frase exacta → definición → título/capítulo → BM25 (texto ×1, títulos ×2,
referencias ×3; k1=1,2, b=0,75) → sinónimos (peso menor) → correcciones de tipeo (Damerau-Levenshtein contra el
vocabulario del corpus). Máximo 4 fragmentos por documento. Caché LRU de 64 consultas.

## 2. Calidad medida (corpus real, 76 documentos, 6.323 fragmentos)
27 casos, todos correctos. Ejemplos:
- "Explica la 23.737" → Ley 23.737, Art. 1
- "¿Qué dice el artículo 14 de la ley 23.737?" / "art 5 inc c ley 23737" → el artículo exacto
- "qué dice la ley de drogas sobre la tenencia para consumo personal" → Ley 23.737 Art. 14 y, segundo, la explicación del Manual (7.3.4 Tenencia simple)
- "¿y el artículo 5?" después de "Explica la 23.737" → Art. 5 de la 23.737 (seguimiento)
- "artículo 79 código penal" → Código Penal (no el Procesal); "artículo 1 del código procesal penal de córdoba" → Art. 1 de la Ley 8123
- "que es el aquaplanin" (con error) → Conducción Segura, "Hidroplaneo o Aquaplanning"
- "receta de pizza napolitana…", "ley 99999" → no encontrado (no inventa)

Tiempos (JVM, sin caché): 12–170 ms por consulta; índice construido en ~2 s. En el teléfono falta medirlo.

## 3. Gemini: diagnóstico
Respuestas reales del servidor (`generativelanguage.googleapis.com/v1beta/models/{modelo}:generateContent`, encabezado `x-goog-api-key`):
- Sin clave → HTTP 403 `PERMISSION_DENIED`. Clave inválida → HTTP 400 `API_KEY_INVALID`.
- En Android, un 401 aparece como `IOException("…authentication challenge…")`.

Causa del "Sin conexión" que se veía antes: la app convertía TODA excepción y todo 400/401/403 en "sin conexión".
En realidad casi siempre era una clave inválida, restringida o sin la API habilitada.

Ahora (`GeminiErrors.kt`): clave inválida, permiso/restricción de la clave, API no habilitada, región no admitida,
facturación, modelo inexistente (prueba el siguiente modelo disponible), petición mal formada, cuota diaria agotada
(no se reintenta), límite por minuto (espera lo que indica el servidor), servidor caído, tiempo de espera agotado,
sin Internet, error TLS, respuesta vacía o bloqueada. Hasta 2 reintentos solo en errores transitorios, con espera
progresiva. "Probar conexión" hace una generación real y solo marca "Conectado" si llega texto. "Diagnóstico
completo" revisa paso a paso: formato de la clave, servidor alcanzable, lista de modelos, generación real.

Gemini recibe solo los fragmentos relevantes, marcados como DATOS (no instrucciones), y debe citar [n].
