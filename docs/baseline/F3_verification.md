# Verificación F3 en dispositivo — la extracción del motor no cambia el comportamiento

> **Qué es este documento**: la comprobación de que mover la máquina de estados del servicio a
> `domain/engine/GateEngine` no alteró nada observable. Se contrasta contra `F1_summary.md` (línea
> base) y `F2_verification.md`. Descriptivo, no especificación.

**Fecha**: 2026-10-04, 21:31–21:37 (pasos 1-6) y 2026-10-05, 21:25–21:28 (paso 7) · **Rama**:
`test/engine-harness` · **APK**: commit `f230df6` · Mismo dispositivo y configuración que F1 y F2.

F3 es la única fase que mueve lógica, así que la comprobación cubrió **los cinco puntos de entrada
del motor y sus dos efectos**, no solo las costuras.

## Resultado por paso

| # | Paso | Qué del motor prueba | Resultado |
|---|---|---|---|
| 1 | TikTok → fricción → Continuar | `onWindowStateChanged`, `onDecision(Continue)` | **igual** a F1/F2, B15 incluido |
| 2 | Teclado abierto >10 s | `onTick` → `confirmPendingExits` (descarte) | **igual**: 2 ciclos `ANOTADA`→`DESCARTADA`, 0 gates |
| 3 | HOME y volver tras >15 s | `onTick` → confirmación, gracia, barrido | **igual**: `CONFIRMADA` +3,99 s, barrido +7,01 s, gate nuevo |
| 4 | Instagram Reels → Continuar | `SectionProbe`, `SectionScan` | **igual**: 1 gate, sesión intacta |
| 5 | YouTube Shorts (BLOCK) | `EngineEffects.goHome`, *settle scan* | **igual**: 3 bloqueos, 0 reimposiciones |
| 6 | TikTok → "No quiero continuar" | `onDecision(Leave)` → `goHome` | **igual**: cierra y va al inicio |
| 7 | Bloquear pantalla 20 s | `DeviceState` dentro de `onTick` | **igual**: 3 `IGNORADO`, reloj congelado, sin gate |

### Detalle de las secuencias

**Paso 1** — idéntico a F1 y F2 en estructura y cronometraje:

```
gate INITIAL -> GATING 5s, sesión 2406
teclado, +1,0 s   -> salida pendiente ANOTADA
+3,85 s           -> salida CONFIRMADA -> suspension gracia=7000ms -> CLOSE
                  -> IDLE, sesión 2406 cerrada
Continuar         -> ALLOWED sin sesión            [B15, igual que antes]
```

**Paso 3** — los dos plazos encadenados, medidos por el reloj inyectado dentro del motor:
`CONFIRMADA` a +3,99 s, `SUSPEND` con gracia de 7 s, `barrido: gracia vencida` a +7,01 s, `IDLE`, y
`gate INITIAL` al volver.

**Paso 4 — el más informativo de F3.** Es el único que ejercita `SectionProbe`, la pieza que **hubo
que rediseñar** al sacar `rootInActiveWindow` del motor: antes `detectSection` devolvía `null` tanto
si el árbol no estaba disponible como si no había sección visible, y el servicio distinguía ambos
casos por separado. Ahora el adaptador devuelve `SectionScan.Unavailable` / `NotDetected` /
`Detected` y el motor decide. Resultado en dispositivo: **61 `Detected`, 1 `NotDetected`, 0
`Unavailable`**, un solo gate de Reels y la sesión intacta. La distinción se preserva.

No hubo *settle scan* en este paso, y es correcto: la sección se detectó en el primer evento, así que
al llegar a `scheduleSectionSettleScan` ya no había ninguna sección en `IDLE` y la guarda lo
descartó — misma condición que antes del refactor.

**Paso 5** — el *settle scan* sí se ejercita aquí, en sus tres caminos (`programado`, `+400ms`,
`abortado`), 15 líneas. Tres bloqueos `entryAction=BLOCK -> BLOCKED -> HOME` y **0 reimposiciones**:
una sola pulsación de HOME basta, igual que en F1.

**Paso 7** — `primer plano IGNORADO (dispositivo inactivo)` ×3, reloj del límite congelado en 3000 ms
durante los 20 s de bloqueo (`enUso=false`), reanudando al desbloquear, y **ningún gate**: D-011
sigue sin implementar.

## Alcance nuevo de B15

El paso 6 añadió una víctima que la línea base no había visto: al pulsar "No quiero continuar", la
sesión ya se había cerrado 1,5 s antes por la cadena de B15, así que `incrementCancelled` recibió
`sessionId = null` y **la cancelación no se registró**. Es la misma raíz, pero significa que B15 se
lleva también el contador de accesos cancelados, el que el dashboard presenta como aperturas
evitadas. No es un bug nuevo: queda anotado en B15.

## Prueba de que el movimiento fue mecánico

Además del dispositivo, dos comprobaciones estáticas:

1. **Mensajes de traza idénticos.** Extraídos y normalizados de ambas versiones, las únicas
   diferencias son el renombrado `pkg` → `packageName`, dos literales partidos en distinto punto (la
   cadena concatenada es la misma) y `runtimesSnapshot()` → `engine.runtimesSnapshot()`.
2. **Comparación normalizada de las líneas de lógica.** Lo que solo está en el servicio antiguo es
   Android (imports, `onServiceConnected`, `onAccessibilityEvent`, `onUnbind`,
   `hasApplicationWindow`, `launchInterruptionScreen`, el bucle del ticker, las costuras, el helper de
   traza, los accesores del container) más tres sustituciones esperadas: `handleDecision` →
   `onDecision`, el `rootInActiveWindow ?: run {}` y `detector.detectSection(...)` que pasaron al
   `SectionProbe` del adaptador, y `?: continue` → `?: return` porque el bucle salió del cuerpo. Lo
   que solo está en el motor nuevo son los cinco puntos de entrada, los parámetros del constructor y
   las tres ramas de `SectionScan`. **Ninguna línea de decisión queda sin justificar.**

El servicio baja de 1066 a **254 líneas**; `GateEngine` son 979 con toda su documentación.

## Nota de método

El volcado del paso 7 se perdió dos veces: el móvil se desconectó a mitad y el `>` de un volcado
abortado truncó el fichero acumulado a 0 bytes. El patrón correcto, y el que recoge
`docs/DEVICE_CHECKLIST.md`: volcar a un temporal y **añadir** al acumulado solo si el volcado tuvo
éxito, nunca redirigir sobre el fichero bueno. El buffer del dispositivo (5 MiB, ya en su máximo) no
conserva nada de un día para otro, así que un paso perdido hay que repetirlo.
