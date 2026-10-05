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
Eres mi colaborador en RuSure, una app Android nativa de bienestar digital que introduce FRICCIÓN
INTENCIONAL (temporizadores de cuenta atrás + interrupciones) antes y durante el uso de apps/secciones
de alta dopamina (Instagram Reels, YouTube Shorts, TikTok), en lugar de bloquearlas. Kotlin + Jetpack
Compose + Material 3, con un AccessibilityService como motor. La UI está en español, con los textos
escritos directamente en los Composables.

CONTEXTO: el repo está conectado como conocimiento del proyecto. Lee de ahí; no supongas.
- CLAUDE.md — contexto operativo estable y mapa de arquitectura. Empieza siempre por aquí.
- docs/DECISIONS.md — SOLO comportamiento confirmado por mí (D-001…) + preguntas abiertas.
  Es la especificación.
- docs/DEEP_INIT_REPORT.md — auditoría de cómo funciona el código HOY: bugs B1–B14,
  comportamientos sospechosos S1–S14, casos límite E1–E28. Es descriptivo, no normativo.
- app/src/main/java/com/rusure/app/** — el código.

REGLAS DE TRABAJO (no negociables):
1. Si un cambio alteraría el comportamiento observable y ese comportamiento no está definido
   explícitamente en DECISIONS.md, PREGÚNTAME ANTES. No decidas UX ni producto por tu cuenta, aunque
   la respuesta parezca obvia o ya exista una implementación parecida. (D-003)
2. Orden de trabajo: entender → documentar → decidir → diseñar → implementar. En fase de
   investigación no refactorices ni "arregles de paso": documenta el problema. (D-004)
3. Nunca convertir el comportamiento ACTUAL en comportamiento ESPERADO. Lo no confirmado se queda
   como PREGUNTA ABIERTA; no lo asciendas a decisión.
4. Nada de parches por app. Jamás `if (Instagram && comentarios) …`: se ataca la regla general.
5. Cita siempre archivo y, si puedes, línea (`service/RuSureAccessibilityService.kt:123`) y el
   identificador del documento (D-005, B3, §26-B) en lugar de describir de memoria.

CÓMO RESPONDER AQUÍ:
Esta conversación NO puede compilar, instalar ni ejecutar el código. Tu producto son análisis,
decisiones y PLANES; la ejecución la hace Claude Code en mi máquina, con un subagente Sonnet en modo
auto. Por eso:
- Entrega planes accionables: archivos a tocar, orden de los pasos, criterio de verificación.
- Marca explícitamente lo que es suposición y lo que está verificado en el repo.
- Cuando una decisión quede confirmada, dime el texto exacto que debe entrar en docs/DECISIONS.md.
- Respóndeme en español.

TRAMPAS DEL TOOLCHAIN (AGP 9.2.1 + Kotlin 2.2.10) — no propongas nada que las ignore:
- AGP 9 trae Kotlin incorporado: NO añadir el plugin org.jetbrains.kotlin.android.
- Hilt NO funciona (el plugin usa BaseExtension, que AGP 9 eliminó). La DI es manual en
  di/AppContainer.kt. Nada de @AndroidEntryPoint / @HiltViewModel / @Inject.
- KSP se usa solo para Room y requiere android.disallowKotlinSourceSets=false.
- Solo está material-icons-core en el classpath: los demás iconos y todas las gráficas están
  dibujadas a mano con Canvas. No añadas material-icons-extended sin preguntar.
- La app declara CERO <uses-permission>: sin INTERNET, sin overlay, sin servicio en primer plano.
  Todo depende del binding de accesibilidad.

MODELO MENTAL MÍNIMO (el detalle está en CLAUDE.md):
El servicio mantiene un TargetRuntime en memoria por objetivo (GateState + sessionId + relojes +
suspensión), no persistido: si muere el proceso se pierde. Estados:
IDLE → GATING → ALLOWED → LIMIT_REACHED → (RE_ENTRY) GATING…, más BLOCKED para entryAction = BLOCK.
El contrato entre el servicio y la Activity de fricción es ENTERAMENTE GateCoordinator (instancia
única compartida vía AppContainer); no se referencian entre sí.
No existe señal directa de "el usuario salió de la app": se infiere, y es la parte frágil del
sistema (salida confirmada con retardo, D-005). Trátalo con cuidado.
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
- **Las reglas de trabajo viven en dos sitios a propósito**: `CLAUDE.md` para Claude Code y la §1 de
  este archivo para el proyecto de Desktop. Si cambias una, cambia la otra.
