# DOFPA — Inventario del material (metodología)

> El contenido real NO está en este repositorio ni en el APK. El inventario detallado (nombres,
> hashes) se mantiene en un área privada fuera del repo. Aquí solo el método y un resumen agregado.

## Recepción (sesión de desarrollo)
- Juego RAR multiparte de 14 volúmenes, **completo y consecutivo** (part01…part14), RAR5,
  **sin contraseña**, integridad verificada; extracción comprobada (`unar`).
- Resumen agregado del contenido (solo tipos y cantidades, para dimensionar el importador):
  ~95 archivos; PDF (mayoría), MP4 (videos, NO van a la app), DOCX/DOC, y una carpeta de actas modelo.

## Cómo se usa
- El usuario importa **sus propios archivos desde el teléfono**; DOFPA los procesa e indexa localmente.
- Los videos no se empaquetan: a futuro solo metadatos/URLs autorizadas (DOFPA Visual).
- Formatos soportados hoy: PDF (con texto), DOCX, PPTX, TXT/MD, imágenes (OCR). `.doc` antiguo: convertir.

## Procesamiento completo (revisión integral)
- 95 archivos recibidos, todos íntegros (`unrar t`). 3 duplicados exactos por hash + 1 por mismo texto.
- 15 videos MP4 omitidos (no tienen texto). 76 documentos con texto: 52 completos y 24 parciales
  (alguna página escaneada leída por OCR en español o ilegible; el informe privado dice cuáles).
- Paquete maestro: 6.323 fragmentos con página, capítulo, sección y artículo; 149 conceptos con definición;
  268 relaciones entre leyes citadas juntas. Se conserva el 98,9 % del texto extraído (el resto son encabezados
  y pies de página repetidos y números de página).
- El paquete y el inventario detallado se entregan solo al usuario, de forma privada. No están en este repositorio.
