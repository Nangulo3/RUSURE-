# RuSure — Registro de decisiones de comportamiento

> **Qué es este documento**: la lista de comportamientos que **el usuario ha confirmado
> explícitamente**. Es la especificación; `docs/DEEP_INIT_REPORT.md` describe lo que el código hace
> hoy, que **no** es lo mismo.
>
> Reglas de mantenimiento:
> * Aquí solo entra lo que el usuario ha dicho de forma explícita. **Nunca** se convierte un
>   comportamiento actual en decisión por el hecho de estar implementado.
> * Lo no confirmado permanece como `PREGUNTA ABIERTA` (y su desarrollo vive en §33 del informe).
> * Una decisión confirmada **no** implica implementarla: la implementación se pide aparte.

---

## D-001 · La navegación a los comentarios de Instagram no debe activar la pantalla intermedia

**Estado**: CONFIRMADO

**Comportamiento esperado**
Entrar en los comentarios de una publicación de Instagram es **navegación interna** dentro de
Instagram y, por sí solo, **no debe** provocar la pantalla intermedia de fricción.

**Confirmado por**: Usuario
**Fecha**: 2026-09-13

**Notas**
* Comportamiento actual (para contraste, no es la especificación): reaparece *a veces*; la causa está
  documentada en `DEEP_INIT_REPORT.md` §25 y el mecanismo general en §26-B.
* El usuario pidió explícitamente **no** resolverlo con un caso especial del tipo
  `if Instagram && comentarios: no mostrar`, sino atacando la regla general.
* Queda **sin confirmar** cuál debe ser esa regla general: ver preguntas #1, #2 y #3 del informe.

---

## D-002 · La navegación al buscador de TikTok no debe activar la pantalla intermedia

**Estado**: CONFIRMADO

**Comportamiento esperado**
Entrar al buscador de TikTok es **navegación interna** dentro de TikTok y, por sí solo, **no debe**
provocar la pantalla intermedia de fricción.

**Confirmado por**: Usuario
**Fecha**: 2026-09-13

**Notas**
* Comportamiento actual: reaparece *a veces*; causa documentada en `DEEP_INIT_REPORT.md` §26.
* Misma raíz que D-001; el usuario pidió expresamente no aplicar un parche específico de TikTok.

---

## D-003 · Regla de trabajo permanente: preguntar antes de decidir comportamiento

**Estado**: CONFIRMADO

**Decisión**
Si una interacción del usuario puede cambiar el comportamiento observable de la aplicación y ese
comportamiento **no está definido explícitamente**, hay que **preguntar antes de implementar**, aunque
la solución parezca obvia o exista una implementación parecida.

**Confirmado por**: Usuario
**Fecha**: 2026-09-13

**Notas**
Formulada por el usuario como regla permanente para todo el trabajo futuro en este proyecto. Se
refleja también en `CLAUDE.md`.

---

## D-004 · Regla de trabajo: comprender y documentar antes de modificar

**Estado**: CONFIRMADO

**Decisión**
El orden de trabajo es: **comprender → documentar → decidir → diseñar la solución → implementar**.
Durante una fase de investigación no se aplican correcciones funcionales ni refactorizaciones; los
problemas se documentan (con causa, evidencia, impacto y confianza) y se posponen.

**Confirmado por**: Usuario
**Fecha**: 2026-09-13

---

## D-005 · "Salir de la app objetivo" se confirma con retardo, no con el primer evento

**Estado**: CONFIRMADO

**Comportamiento esperado**
Un evento de accesibilidad con un `packageName` distinto **no** debe bastar para dar por hecho que el
usuario salió de la app objetivo. La salida debe **confirmarse con retardo**: transcurridos X segundos,
comprobar si la app objetivo sigue (o no) siendo la ventana activa, y solo entonces tratarla como
salida.

**Confirmado por**: Usuario
**Fecha**: 2026-09-13

**Notas**
* Sustituye a la inferencia actual (`onForegroundPackageChanged`, `DEEP_INIT_REPORT.md` §26-B).
* Elegida frente a la alternativa de clasificar por tipo de ventana (`getWindows()` /
  `AccessibilityWindowInfo`): la confirmación temporal es independiente del tipo de ventana, a cambio
  de introducir latencia en la detección de salidas reales.
* Consecuencia esperada: el teclado, la persiana y los diálogos del sistema dejan de provocar la
  cadena descrita en §25/§26, con lo que D-001 y D-002 se cumplen sin casos especiales por app.
* **IMPLEMENTADO el 2026-09-14** en `RuSureAccessibilityService`: `pendingExits` +
  `confirmPendingExits()` (llamado desde el ticker) + `hasApplicationWindow()`.
* **Parámetro aún sin confirmar por el usuario**: X = `FOREGROUND_EXIT_CONFIRM_MILLIS = 3_000` ms es
  un valor **provisional elegido en la implementación** (suficiente para que acabe la animación de
  cambio de app). La comprobación consulta `windows` y busca una ventana `TYPE_APPLICATION` del
  paquete objetivo. Confírmalo o dame otro valor.

---

## D-006 · El umbral de "apertura nueva" tras salir debe ser configurable por el usuario

**Estado**: CONFIRMADO

**Comportamiento esperado**
El tiempo que el usuario debe pasar fuera de la app objetivo para que el regreso cuente como
**apertura nueva** (y vuelva a mostrarse la fricción) deja de ser una constante interna: se expone en
la configuración de cada objetivo, con un valor por defecto.

**Confirmado por**: Usuario
**Fecha**: 2026-09-13

**Notas**
* Hoy es la constante `BACKGROUND_GRACE_MILLIS = 7_000` en el servicio.
* **Parámetros pendientes**: valor por defecto, rango de opciones ofrecidas y etiqueta en la UI.
* Implica ampliar `AppTargetConfig` (migración de Room) y la pantalla de configuración.
* Queda **sin decidir** si ese mismo umbral también cierra la sesión de estadísticas o si ambos
  conceptos se separan (era la alternativa "separar sesión y fricción"); por defecto se asume que
  sigue siendo un único umbral, como hoy. Confirmar antes de implementar.

---

## D-007 · La navegación interna es inmune a la fricción mientras la app siga en primer plano

**Estado**: CONFIRMADO

**Comportamiento esperado**
Salir de la sección vigilada sin salir de la app (comentarios, perfil, feed, buscador, historias,
pestañas internas) **nunca** vuelve a mostrar la pantalla intermedia, dure lo que dure. La única
interrupción posible en ese caso es el límite de uso continuo.

**Confirmado por**: Usuario
**Fecha**: 2026-09-13

**Notas**
Coincide con el comportamiento actual (`suspendTarget(foregroundLeft = false)` con gracia
`NO_EXPIRY`). Queda así **confirmado como intencionado**, no como efecto colateral.

---

## D-008 · El recordatorio de uso continuo mide el tiempo en la app, no solo en la sección

**Estado**: CONFIRMADO

**Comportamiento esperado**
El "recordatorio cada N minutos" cuenta todo el tiempo que la app objetivo está en primer plano,
incluida la navegación interna (comentarios, perfiles). Puede por tanto dispararse mientras el
usuario no está viendo la sección.

**Confirmado por**: Usuario
**Fecha**: 2026-09-13

**Notas**
* Coincide con el comportamiento actual (líneas 556-563 del servicio) y queda confirmado.
* Se mantiene deliberadamente la asimetría con las estadísticas: el **tiempo mostrado** al usuario
  sigue contando solo con la sección visible (línea 566). Son dos relojes con reglas distintas a
  propósito.

---

## D-009 · Pausa global de la protección (5 minutos)

**Estado**: CONFIRMADO — IMPLEMENTADO 2026-09-14

**Comportamiento esperado**
Desde el menú inicial ("Mi tiempo") el usuario puede pausar, con un solo toque y sin diálogo de
confirmación, toda la fricción configurada (espera, recordatorio de uso continuo y modo Bloqueado)
durante 5 minutos fijos. La pausa es **global**: un único control afecta a todos los objetivos. Al
terminar, cada objetivo vuelve exactamente a su configuración anterior porque la pausa nunca
modifica ningún `AppTargetConfig` — es solo un instante "pausado hasta X" que el servicio consulta.

**Decisiones confirmadas por el usuario**:
1. Si la pausa termina estando dentro de una app objetivo, la fricción se aplica al instante
   (pantalla de espera, o HOME si el objetivo está en modo Bloqueado).
2. La pausa se puede cancelar antes de tiempo con un botón "Reanudar ahora".
3. Durante la pausa, tiempo y aperturas se siguen contando con normalidad en estadísticas.
4. Persistencia: hora de fin (epoch millis) en `SharedPreferences`, sin migración de Room.

**Supuestos aprobados al aprobar el plan**:
* Activar la pausa es un toque directo, sin diálogo de confirmación; duración fija de 5 min.
* Las tarjetas de objetivos no cambian; solo se añade una tarjeta de pausa bajo "Hoy".
* La fricción al terminar la pausa usa el gate INITIAL, reutilizando la sesión abierta durante la
  pausa (no cuenta como una apertura extra).
* Si el teléfono está bloqueado/con la pantalla apagada al vencer la pausa, la reactivación se
  aplica en cuanto el dispositivo vuelva a estar activo.
* En secciones (Reels/Shorts) en navegación interna al terminar la pausa: no se interrumpe al
  momento; la próxima vez que la sección sea visible, se gatea.

**Confirmado por**: Usuario
**Fecha**: 2026-09-14

**Notas**
* Implementado en `domain/pause/PauseController.kt` (nuevo), `RuSureAccessibilityService`
  (pass-through en `triggerGate`/`allowWithoutFriction`, guarda en `reEnforceBlock`, detección de
  inicio/fin de pausa en el ticker, `onPauseStarted`/`onPauseEnded`) y la tarjeta de pausa del
  dashboard (`DashboardViewModel`/`DashboardScreen`).

---

# Preguntas abiertas (sin confirmar)

Ninguna de las siguientes tiene todavía un comportamiento esperado definido. El detalle (contexto,
comportamiento actual, alternativas) está en `docs/DEEP_INIT_REPORT.md` §33.

| Ref. | Tema | Estado |
|---|---|---|
| #1 | Qué cuenta como "salir de la app objetivo" (teclado, sistema, diálogos) | **RESUELTA → D-005** |
| #1b | Valor del retardo X con el que se confirma la salida | PREGUNTA ABIERTA |
| #2 | Cuánto tiempo fuera convierte el regreso en una "apertura nueva" (hoy 7 s) | **RESUELTA → D-006** |
| #2b | Valor por defecto, rango de opciones y etiqueta del ajuste; y si ese umbral también cierra la sesión de estadísticas | PREGUNTA ABIERTA |
| #3 | Si la navegación interna debe ser inmune a la fricción para siempre | **RESUELTA → D-007** |
| #4 | Qué debe medir el "recordatorio cada N minutos" durante la navegación interna | **RESUELTA → D-008** |
| #5 | Si el modo Bloqueo en una sección debe expulsar de la app entera | PREGUNTA ABIERTA |
| #6 | Si un intento bloqueado cuenta como "apertura" en estadísticas | PREGUNTA ABIERTA |
| #7 | Qué debe pasar al desactivar un objetivo que se está usando | PREGUNTA ABIERTA |
| #8 | Cuándo debe aplicarse un cambio de configuración a una sesión en curso | PREGUNTA ABIERTA |
| #9 | Qué hacer si la pantalla de fricción desaparece sin decisión del usuario | PREGUNTA ABIERTA |
| #10 | Promedios y comparativas cuando hay pocos días de datos | PREGUNTA ABIERTA |
| #11 | Qué hacer con las sesiones huérfanas tras la muerte del proceso | PREGUNTA ABIERTA |
| #12 | Qué debe significar "aperturas evitadas" en el dashboard | PREGUNTA ABIERTA |
