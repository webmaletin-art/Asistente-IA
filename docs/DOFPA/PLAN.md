# DOFPA — Plan y arquitectura de desarrollo

DOFPA (Diccionario del Operador FPA) es un **módulo nuevo dentro de AI Quick Assist**, para
estudiar y consultar material académico/documental. AI Quick Assist sigue funcionando igual.

## Decisión de distribución (definitiva)
- La APK se compila **siempre con GitHub Actions**. No depende de compilaciones manuales.
- El **repositorio público NO contiene corpus ni documentos institucionales**. Nunca.
- La app se publica **con datos ficticios de prueba**. El usuario carga su material real
  **desde el teléfono** (administrador de documentos). DOFPA lo indexa **en el dispositivo**
  y permite consultarlo **sin conexión**.
- Por eso "todo el texto en la APK" se cumple como "todo en el dispositivo": el corpus vive en
  el almacenamiento privado de la app tras importarlo el usuario, no en el repo ni en el APK.

## Motores (como se acordó)
1. **Gemini API** (clave del usuario, cifrada con Android Keystore) — recibe SOLO los fragmentos
   relevantes, nunca el corpus completo.
2. **Google AI Mode** — alternativa web **no automática**: se ofrece "abrir con la consulta
   preparada". No se elude ninguna restricción del servicio.
3. **Consulta local** — respaldo sin conexión ni créditos, por búsqueda FTS citada.
- Conmutación honesta: Gemini → si falla (cuota/sin conexión) → material local. Siempre se
  muestra la fuente usada.

## Privacidad
- El material del usuario se procesa en el teléfono y no sale de él al importar.
- Gemini/AI Mode requieren conexión y envían solo fragmentos; el usuario decide (Ajustes).
- DOFPA es apoyo documental de estudio, **no autoridad**. No decide requisas, detenciones, uso de
  fuerza ni genera tácticas. Verificación y revisión humana antes de cualquier uso real.
