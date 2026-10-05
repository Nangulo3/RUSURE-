# RuSure — Proyecto de Claude Desktop

> **Qué es este archivo**: la configuración del *Proyecto* de Claude (app de escritorio / claude.ai)
> dedicado a RuSure. Vive en el repo a propósito: así entra por el mismo conector de GitHub que el
> resto del código y nunca se desincroniza de las reglas reales del proyecto.
>
> El conocimiento del proyecto se alimenta **conectando el repo** `Nangulo3/RUSURE-` (conector de
> GitHub), no subiendo copias de los archivos.

---

## 1 · Instrucciones personalizadas (copiar tal cual en el proyecto)

```text
Eres mi colaborador en RuSure (app Android de fricción intencional; Kotlin + Compose +
AccessibilityService). El repo está conectado como conocimiento del proyecto y es la ÚNICA fuente
de reglas, contexto y especificación.

ANTES DE RESPONDER cualquier cosa sobre el proyecto, en cada conversación:
1. Busca en el conocimiento del proyecto y lee las secciones "Working rules" y "Toolchain gotchas"
   de CLAUDE.md. Son obligatorias y prevalecen sobre tu criterio.
2. Busca en docs/DECISIONS.md las D-xxx relevantes para la pregunta.
Si no encuentras esas secciones, dímelo y pídeme que pulse Sync; no trabajes de memoria.
Si algo de aquí contradice a CLAUDE.md, manda CLAUDE.md.

Tu papel aquí: no puedes compilar ni ejecutar. Entregas análisis, decisiones y planes que ejecuta
Claude Code. Marca qué está verificado en el repo y qué es suposición. Respóndeme en español.
```

---

## 2 · Montaje del proyecto en Claude Desktop

1. **Crear el proyecto**: barra lateral → *Projects* → *New project*. Nombre: `RuSure — Android`.
   Descripción: `App Android de fricción intencional ante apps de alta dopamina (Kotlin/Compose +
   AccessibilityService).`
2. **Instrucciones**: pegar el bloque de la §1 en las instrucciones personalizadas del proyecto.
3. **Conectar el repo**: en *Project knowledge* → botón **`+`** → **GitHub** → buscar
   `Nangulo3/RUSURE-` (o pegar `https://github.com/Nangulo3/RUSURE-`) → rama `main` → en el
   explorador de archivos seleccionar:

   | Seleccionar | Motivo |
   |---|---|
   | `CLAUDE.md` | contexto operativo |
   | `docs/` (los tres `.md`) | especificación + auditoría |
   | `app/src/main/java/com/rusure/app/` | todo el código fuente |
   | `app/src/main/AndroidManifest.xml` | declaración del servicio y de la Activity translúcida |
   | `app/src/main/res/xml/` | `accessibility_service_config.xml` |
   | `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `settings.gradle.kts` | el toolchain, que es parte del problema |
   | `app/src/test/` | el único test JVM existente |

   **No** seleccionar: `build/`, `.gradle/`, `.idea/` (hay un `deviceStreaming.xml` de 114 KB que
   solo gasta contexto), `local.properties` (rutas de la máquina), `app/src/main/res/drawable/` y
   `mipmap*` (iconos vectoriales).

4. **La sincronización es manual.** El conector trae nombres y contenidos de los archivos de una
   rama; no trae historial, PRs ni metadatos. Después de cada `git push` hay que pulsar el icono
   **Sync** del conocimiento del proyecto para que el proyecto vea el código nuevo.

---

## 3 · Rutina de uso

- **Antes de una sesión en Desktop**: `git push` y *Sync* en el proyecto. Si no, Claude razonará sobre
  código viejo y lo hará con total seguridad.
- **Reparto de roles**: en Desktop se piensa, se decide y se planifica; en Claude Code se ejecuta,
  se compila y se prueba en el dispositivo.
- **Después de una decisión**: anotarla en `docs/DECISIONS.md` con su `D-0xx`, hacer commit y push.
  Ese archivo es el que mantiene alineados Desktop y Claude Code.
- **Las reglas viven solo en `CLAUDE.md`.** Las instrucciones del Proyecto únicamente obligan a
  leerlas; solo hay que tocarlas si cambian nombres de archivos.
- **Desktop lee solo `main`.** El trabajo va en ramas; cada fase verificada se integra en `main`
  (fast-forward) antes de pulsar *Sync*. `main` nunca recibe trabajo a medias.
