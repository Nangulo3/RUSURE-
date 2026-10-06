# Línea base F1 — validación en dispositivo de la traza del motor

> **Qué es este documento**: el registro de la primera ejecución de los flujos de fricción en un
> dispositivo real, leída desde la traza `RuSure/Engine` introducida en F1. Es **descriptivo**: dice
> lo que el motor hizo el 2026-10-04, no lo que debe hacer. Las decisiones viven en
> `docs/DECISIONS.md`; el análisis estático, en `docs/DEEP_INIT_REPORT.md`.
>
> El log crudo (`baseline/F1_cont.log`, ~3400 líneas) **no se versiona**: `baseline/` está en
> `.gitignore`. Aquí queda solo la secuencia de estados y acciones por objetivo.

**Fecha**: 2026-10-04, 20:19–20:39 (hora local) · **Rama**: `test/engine-harness` · **APK**: commit
`cb3012a` · **Dispositivo**: Samsung físico (One UI), con RuSure como **único** servicio de
accesibilidad activo.

**Configuración durante la prueba** (leída de la propia traza):

| Objetivo | Tipo | `entryAction` | Espera inicial | Límite continuo | Espera de reingreso |
|---|---|---|---|---|---|
| `tiktok_global` | APP_GLOBAL | WAIT | 5 s | 300 s | 5 s |
| `instagram_reels` | SECTION | WAIT | 5 s | 300 s | — |
| `youtube_shorts` | SECTION | **BLOCK** | — | — | — |

`youtube_shorts` estaba en modo Bloqueado, no en Espera como preveía el guion. El paso 7 mide por
tanto el camino `blockTarget`, no el de fricción.

**Constantes vigentes**: `FOREGROUND_EXIT_CONFIRM_MILLIS = 3 s`, `BACKGROUND_GRACE_MILLIS = 7 s`,
`GATE_COOLDOWN_MILLIS = 2,5 s`. El umbral efectivo para que un regreso cuente como apertura nueva es
por tanto **~10 s**, no 7: la gracia empieza a contar *después* de la confirmación.

---

## Resultado por paso

| # | Paso | Esperado | Resultado |
|---|---|---|---|
| 1 | Abrir TikTok desde el inicio | fricción INITIAL | **CUMPLE** en lo observable · pérdida de sesión (H1) |
| 2 | Buscador de TikTok, teclado >10 s | sin fricción (D-002/D-005) | **CUMPLE** |
| 3 | Comentarios de TikTok, teclado >10 s | sin fricción (D-002/D-007) | **CUMPLE** |
| 3B | Recordatorio de uso continuo | — (provocado, fuera de guion) | **CUMPLE** D-008 · interrupción no registrada (H1) |
| 4 | HOME y volver a los ~5 s | reanuda sin fricción (D-006) | **NO VÁLIDO** (volvió a los 10,9 s) → repetido |
| 4b | HOME y volver a los 5,9 s | reanuda sin fricción (D-006) | **CUMPLE** |
| 5 | HOME y volver a los ~25 s | fricción (D-006) | **CUMPLE** · pérdida de sesión (H1) |
| 6 | Instagram Reels + comentarios con teclado | sin fricción al volver (D-001/D-005/D-007) | **CUMPLE** del todo |
| 7 | YouTube Shorts | (previsto fricción; real: bloqueo) | **CUMPLE** el camino BLOCK · confirma S2 y S3 |
| 8 | Bloquear pantalla 20 s | sin fricción (ACTUAL, S8) | **CUMPLE** lo actual · **rechazado por el usuario** (D-011 propuesta) |

### Paso 1 · Abrir TikTok desde el inicio — 20:19:12

```
IDLE  --(evento de TikTok, fresco=true)-->      gate INITIAL
      -->                                       GATING (5 s), sesión 2396
      <--(evento del teclado, +1,0 s)            salida pendiente ANOTADA
      --(+3,3 s, sin ventana de aplicación)-->   salida CONFIRMADA
      -->                                        suspensión dejoPrimerPlano=true -> CLOSE
      -->                                        IDLE, sesión 2396 CERRADA
      <--(Continuar, +1,8 s)                     ALLOWED ... con sessionId = null
```

La fricción se mostró y el usuario la completó: hacia fuera, correcto. Dentro, el gate se cerró solo
antes de que el usuario decidiera.

### Paso 2 · Buscador de TikTok — 20:23:58 (repetición limpia)

Tres ciclos completos, estado `ALLOWED` invariable, **cero gates**:

```
ALLOWED --(teclado)---> ANOTADA --(+3 s, conserva ventana de aplicación)--> DESCARTADA --> ALLOWED
ALLOWED --(teclado)---> ANOTADA --(+3 s)---------------------------------> DESCARTADA --> ALLOWED
ALLOWED --(SystemUI)--> ANOTADA --(+3 s)---------------------------------> DESCARTADA --> ALLOWED
```

El síntoma de `DEEP_INIT_REPORT.md` §26 **no se reproduce**.

> El primer intento de este paso se perdió en parte: el buffer de logcat del dispositivo (5 MiB, ya
> en su máximo) se desbordó con logs de otras apps y evictó el inicio del bloque. Desde aquí la
> captura pasó a ser continua a fichero.

### Paso 3 · Comentarios de TikTok — 20:25:28

Siete ciclos de salida pendiente: **5 descartadas** (se comprobó la ventana a los 3 s) y **2
canceladas** (el usuario volvió antes de la confirmación). Estado `ALLOWED` de principio a fin,
**cero gates**. El síntoma de §25 aplicado a TikTok **no se reproduce**.

### Paso 3B · Recordatorio de uso continuo — 20:27:07

```
ALLOWED (límite 282 s) --(el ticker alcanza 300 s)--> LIMIT_REACHED
                       -->                            GATING RE_ENTRY (5 s)
                       <--(Continuar)                 ALLOWED, límite reiniciado a 0
```

Disparo exacto en el tiempo configurado y reinicio correcto del reloj. **Ningún evento ajeno llegó
durante los 7 s de pantalla**, y por eso este gate *no* sufrió H1: confirma que H1 necesita la
coincidencia de pantalla de fricción **y** evento ajeno, no solo la pantalla.

La llamada `incrementInterruptions` de este RE_ENTRY **no se registró**: `triggerGate` hace
`runtime.sessionId?.let { ... }` y el `sessionId` era nulo desde el paso 1.

### Paso 4 · Salida corta — 20:28:05 (inválido) y 20:30:31 (válido)

Primer intento: HOME a las 20:28:33,878 y regreso a las 20:28:44,803 = **10,9 s fuera**, por encima
del umbral efectivo de ~10 s. La fricción que apareció es el comportamiento correcto de hoy; el paso
no probó lo que pretendía.

Repetición, **5,9 s fuera**:

```
ALLOWED --(HOME, evento del launcher)--> ANOTADA
        --(+3,56 s, sin ventana)------->  CONFIRMADA --> suspensión gracia 7 s -> SUSPEND
        <--(regreso a los 5,9 s)          reingreso en gracia -> reanuda sin fricción
        --(teclado)-------------------->  ANOTADA --(+3 s)--> DESCARTADA --> ALLOWED
```

### Paso 5 · Salida larga — 20:32:04

```
ALLOWED --(HOME)--> ANOTADA --(+3,1 s)--> CONFIRMADA --> SUSPEND (gracia 7 s)
        --(+7,0 s, barrido)------------>  gracia vencida -> cierra -> IDLE
        <--(regreso a los 28 s)           gate INITIAL -> GATING (5 s), sesión 2399
        <--(teclado, +1,0 s)              ANOTADA --(+3 s)--> CONFIRMADA -> CLOSE
        -->                               IDLE, sesión 2399 CERRADA
        <--(Continuar)                    ALLOWED sin sesión            [H1, 5.ª vez]
```

### Paso 6 · Instagram Reels — 20:33:52

```
IDLE --(contenido de Instagram, clips_viewer_* visibles)--> gate INITIAL
     -->                                                    GATING (5 s)
     <--(Continuar, +6,3 s)                                  ALLOWED, sesión intacta
     --(comentarios + teclado >10 s)-->  1 DESCARTADA + 2 CANCELADAS, sin re-gate
```

**Un solo gate de Reels en todo el paso.** El síntoma original de §25 —el que abrió esta
investigación— **no se reproduce**. Además:

* La hoja de comentarios **no oculta el visor**: el último tick marca `suspendido=false`, así que los
  `clips_viewer_*` siguieron visibles detrás y la navegación interna ni llegó a suspender. Es la
  "variante equivalente" descrita en §25.2.
* El *settle scan* siguió ejecutándose **después** del gate (+1000, +1700, +2600 ms), reportando "no
  detectada" y llamando a `suspendTarget` sobre un runtime ya en `GATING` (acción `NONE`). Inofensivo
  pero ruidoso. Quien detectó la sección fue un evento de contenido normal, no el scan.

### Paso 7 · YouTube Shorts en modo Bloqueado — 20:35:31

```
IDLE --(sección detectada)--> gate INITIAL --(entryAction=BLOCK)--> BLOCKED -> HOME
     --(sección ya no detectada, misma app)--> CLOSE --> IDLE
```

* Bastó **una sola pulsación de HOME**: `reEnforceBlock` no llegó a actuar.
* El bloqueo **expulsa de YouTube entero**, no solo de Shorts → confirma S2 / pregunta abierta #5 en
  dispositivo.
* `blockTarget` abrió y cerró una sesión con 1 interrupción → el intento bloqueado **cuenta como
  apertura con 0 minutos** → confirma S3 / pregunta abierta #6 en dispositivo.

### Paso 8 · Pantalla bloqueada 20 s — 20:37:15

```
ALLOWED (límite 12 s) --(bloqueo)--> primer plano IGNORADO (dispositivo inactivo) x2
                      --(24 s)----->  reloj del límite CONGELADO en 12 s
                      <--(desbloqueo)  sin gate; 1 CANCELADA + 1 DESCARTADA por el parpadeo del
                                       launcher y de SystemUI; sigue ALLOWED
```

Comportamiento actual confirmado (S8). **El usuario ha declarado que no es el deseado**: ver la
decisión D-011 propuesta al final.

---

## Hallazgos

### H1 · Un evento ajeno durante el `GATING` cierra el gate y pierde la sesión — **NUEVO, ALTO**

`VERIFICADO EXPERIMENTALMENTE` · 6 ocurrencias en 20 minutos (pasos 1, 2-previo, 4, 5, 8 y una más
en el hueco evictado del buffer).

Cadena, siempre idéntica:

1. `triggerGate` pone `GATING` y lanza la Activity translúcida de fricción sobre la app objetivo.
2. ~1 s después, la ventana del teclado se cierra al perder el foco y el **IME emite un
   `TYPE_WINDOW_STATE_CHANGED` con su propio paquete** → `onForegroundPackageChanged` anota una
   salida pendiente del objetivo.
3. A los 3 s, `confirmPendingExits()` llama a `hasApplicationWindow(objetivo)`. La pantalla de
   fricción **ocluye** la app, el sistema deja de listar su ventana `TYPE_APPLICATION` y la respuesta
   es falsa. No aparece la traza `ventanas no disponibles`, así que la lista existía y simplemente no
   contenía al objetivo.
4. La salida se da por real → `suspendTarget(foregroundLeft = true)` sobre un runtime en `GATING`
   devuelve `SuspendAction.CLOSE` (`service/RuSureAccessibilityService.kt:535-536`) → `closeTarget`
   cierra la sesión y pone `IDLE`, con `sessionId = null`.
5. El usuario pulsa "Continuar" sobre una pantalla cuyo gate ya no existe. `handleDecision` pone
   `ALLOWED` sin comprobar el estado previo ni reabrir sesión.

Consecuencias observadas, todas silenciosas:

* **El tiempo de uso no se mide.** `flushActiveTime` descarta el delta cuando `sessionId == null`. En
  esta tanda, los pasos 2, 3 y 3B (~7 minutos de TikTok real) no se contabilizaron.
* **Las interrupciones de `RE_ENTRY` no se registran** (paso 3B).
* **La siguiente entrada al objetivo no gatea**: el estado es `ALLOWED`, no `IDLE`.
* La sesión cerrada queda en estadísticas como una apertura de ~4 s.

Relación con lo documentado: la raíz es **anterior a D-005** (el paso 4 de la cadena ya existía:
`GATING` + `foregroundLeft` → `CLOSE`), pero D-005 lo volvió *determinista* al añadir una
comprobación de ventanas que la propia pantalla de fricción invalida. No está descrito en el informe:
B5/E8 describen el caso inverso (`GATING` sin pantalla); aquí hay **pantalla sin `GATING`**.

**Comportamiento esperado: DESCONOCIDO.** No se corrige en esta fase.

### H2 · La decisión de una pantalla huérfana se acepta indefinidamente

`VERIFICADO EXPERIMENTALMENTE` (20:28:49 → 20:30:15). El gate se cerró a las 20:28:49 y el
"Continuar" llegó **86 s más tarde**; `handleDecision` lo aceptó y puso `ALLOWED`. El usuario esperó
una cuenta atrás que ya no servía para nada. Es corolario de H1 pero independiente: `handleDecision`
no valida que el runtime siga en `GATING`.

### H3 · El *settle scan* se aborta si el teclado toma el primer plano

`VERIFICADO EXPERIMENTALMENTE` (paso 7): `settle scan abortado (+1000ms) | primerPlano=<teclado>`. La
condición de corte es `pkg != currentForegroundPackage`, y el teclado *es* un paquete distinto. El
mecanismo que existe precisamente porque el reproductor de Reels/Shorts se infla tarde y luego deja
de emitir eventos **se rinde ante un teclado**. En el paso 7 se salvó porque un evento de contenido
posterior sí detectó la sección. No está documentado en el informe.

### H4 · Reentrada de `evaluateSections` observada en vivo

`VERIFICADO EXPERIMENTALMENTE` (20:34:09,583): dos ejecuciones idénticas en el mismo milisegundo,
hilo principal y corrutina del *settle scan*. Es el riesgo 7 de §17, hasta ahora solo `INFERIDO`. Sin
daño observable: las transiciones están bajo `lock` y el estado era `IDLE` en ambas.

### H5 · Cada ventana auxiliar congela ~3 s el reloj del límite de uso continuo

`VERIFICADO EXPERIMENTALMENTE` (pasos 2 y 3). Mientras la salida está en observación,
`currentForegroundPackage` es el teclado, así que `inUse` es falso y `continuousMillis` no avanza
durante los 3 s de confirmación. Matiza D-008: el "tiempo en la app" pierde 3 s por cada aparición de
teclado. No es un cambio visible, pero sesga la medida.

### H6 · Lo que **sí** funciona

* **D-005 cumple su objetivo en los dos síntomas que lo motivaron**: 17 salidas descartadas y 7
  canceladas en toda la tanda, sin un solo re-gate por ventana auxiliar. Ni §25 (comentarios de
  Instagram) ni §26 (buscador de TikTok) se reprodujeron.
* **D-006 (umbral actual)**: regreso a los 5,9 s reanuda; a los 28 s gatea. El límite real es ~10 s.
* **D-007**: la navegación interna no volvió a gatear en ningún momento.
* **D-008**: el recordatorio salta exacto en el tiempo configurado y reinicia el reloj.
* **Detección**: TikTok por paquete, Reels por `clips_viewer_*` y Shorts por sus matchers, los tres
  sin fallos en esta tanda.

**No cubierto por esta línea base**: la pausa global de 5 minutos (D-009) y el modo Bloqueado sobre
una app global.

---

## Decisión pendiente de confirmar

Durante el paso 8 el usuario declaró que el comportamiento actual (volver a la app tras 20 s de
pantalla bloqueada **sin** fricción) no es el deseado, y que el umbral debería ser menor. Texto
propuesto para `docs/DECISIONS.md`, **a confirmar antes de escribirlo**:

> ### D-011 · Volver tras un bloqueo de pantalla debe poder contar como apertura nueva
>
> **Estado**: PROPUESTA — pendiente de confirmar
>
> **Comportamiento esperado**: el tiempo con la pantalla bloqueada o apagada deja de ser neutro. Si
> el usuario vuelve a la app objetivo tras un bloqueo **más largo que un umbral**, el regreso cuenta
> como apertura nueva y se muestra fricción. 20 s se consideran ya demasiado para pasar sin fricción.
>
> **Parámetros sin confirmar**: el valor del umbral; si es el mismo ajuste de D-006 o uno propio; y
> si el reloj del límite de uso continuo debe seguir congelado durante el bloqueo (hoy lo está).
>
> **Sustituye a**: S8, que `DEEP_INIT_REPORT.md` §29 recoge como comportamiento actual no confirmado.

Mientras no se confirme, S8 sigue siendo comportamiento **actual**, y así lo fijarán los tests de
`current/` de la fase F5.

---

## Verificación F4 (pausa)

**Fecha**: 2026-10-05, 22:50–22:57 · **APK**: commit `39c77b5` · Mismo dispositivo.

F4 introdujo `PauseSource` para que el motor consulte la pausa global con `clock.now()` en lugar de
leer `System.currentTimeMillis()` por su cuenta. Eso toca **temporización**, así que la regla 7 de
`CLAUDE.md` exige dispositivo. La pausa (D-009) no estaba cubierta por la línea base F1.

| Flujo | Esperado | Resultado |
|---|---|---|
| Activar pausa → abrir el objetivo | sin fricción | **CUMPLE** |
| Quedarse dentro hasta que venza (5 min) | fricción al instante (D-009 #1) | **CUMPLE**, flanco a los 4 min 59,6 s |
| Nueva pausa → "Reanudar ahora" → abrir | fricción (D-009 #2) | **CUMPLE** |

```
22:50:41.726  pausa iniciada | cierra bloqueados=[]
22:50:45.325  gate tiktok_global INITIAL | PAUSA GLOBAL -> pasa sin friccion
22:50:45.734  tick | estado=ALLOWED limite=0/300000ms          <- sin pantalla, contando tiempo

22:55:41.315  flanco de pausa | pausado=false
22:55:41.316  pausa terminada | reevalua=[tiktok_global] primerPlano=<objetivo>
22:55:41.316  transicion tiktok_global -> GATING | INITIAL 5s  <- friccion al instante
22:55:47.104  decision Continuar -> ALLOWED

22:56:43.449  flanco de pausa | pausado=true  -> pausa iniciada
22:56:44.451  flanco de pausa | pausado=false -> pausa terminada   (Reanudar ahora)
22:56:48.881  estado=IDLE fresco=true -> gate INITIAL            <- la friccion vuelve
```

Detalles que confirman los supuestos aprobados de D-009:

* **El flanco se detecta con el reloj nuevo y en el tick siguiente al vencimiento**: pausa iniciada
  a las 22:50:41,726 y terminada a las 22:55:41,315, es decir 4 min 59,6 s de los 5 min fijos.
* **El gate del final de la pausa reutiliza la sesión**: entre el inicio de la pausa y el gate no hay
  ningún `cierre (sesion=…)`, así que `getOpenSession` encuentra la de la pausa y no se cuenta una
  apertura extra.
* **Durante la pausa el tiempo sigue contando** (decisión #3): el tick marca `estado=ALLOWED` y el
  reloj del límite avanzando, sin pantalla de fricción.
* En el paso 3, `onPauseEnded` **cerró** el objetivo en vez de gatearlo al instante, y es correcto:
  estaba suspendido y el primer plano era una ventana auxiliar, así que `visible` era falso. La
  fricción llegó en la siguiente apertura.

**B15 sigue reproduciéndose** (paso 3, sesión 2432 cerrada 0,8 s antes del "Continuar"), lo cual es
la señal buscada: F4 no cambió comportamiento.
