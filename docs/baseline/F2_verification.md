# Verificación F2 en dispositivo — las costuras no cambian el comportamiento

> **Qué es este documento**: la comprobación de que introducir `Clock`, `WindowProbe` y `DeviceState`
> (`domain/engine/Seams.kt`) no alteró nada observable. Se contrasta contra `F1_summary.md`, que es la
> línea base. Descriptivo, no especificación.

**Fecha**: 2026-10-04, 21:13–21:23 · **Rama**: `test/engine-harness` · **APK**: commit `8145d5d` ·
Mismo dispositivo y misma configuración que la línea base F1.

El criterio de la fase era doble: que el diff contuviera **solo sustituciones** (14 relojes,
`hasApplicationWindow` y `isDeviceActive`) y que el dispositivo se comportara igual. Ambos se cumplen.

## Qué se ejercitó

Cuatro flujos en lugar de los ocho de F1: Instagram, Shorts y el recordatorio de uso continuo no
ejercitan nada que estos no cubran, porque el `tick` con el reloj del límite aparece en todos.

| Costura | Magnitud medida | F1 (línea base) | F2 | |
|---|---|---|---|---|
| `Clock` | confirmación de salida (`FOREGROUND_EXIT_CONFIRM_MILLIS`, 3 s) | 3,3 s | 3,2 s · 3,0 s · 3,28 s | = |
| `Clock` | gracia hasta el barrido (`BACKGROUND_GRACE_MILLIS`, 7 s) | 7,0 s | **7,01 s** | = |
| `Clock` | tick del ticker y reloj del límite | 1000 ms/s | 1000 ms/s | = |
| `WindowProbe` | ventana auxiliar encima → `salida DESCARTADA` | sí | sí, ×3 | = |
| `WindowProbe` | sin ventana de aplicación → `salida CONFIRMADA` | sí | sí, ×3 | = |
| `DeviceState` | pantalla bloqueada → `primer plano IGNORADO` | sí | sí | = |
| `DeviceState` | reloj del límite congelado durante el bloqueo | sí | sí (105 000 ms fijos) | = |

## Secuencias comparadas

**Apertura con fricción** (F1 paso 1 ↔ F2 paso 1 bis) — idénticas, incluida la cadena de B15:

```
gate INITIAL -> GATING 5s
teclado, +1,0 s          -> salida pendiente ANOTADA
+3,3 s / +3,2 s          -> salida CONFIRMADA
                         -> suspension gracia=7000ms -> CLOSE
                         -> IDLE, sesión cerrada   (F1: 2396 · F2: 2404)
Continuar                -> ALLOWED sin sesión
```

**Ventana auxiliar** (F1 paso 2 ↔ F2 paso 2) — `ANOTADA` → +3 s → `DESCARTADA | conserva ventana de
aplicacion`, estado `ALLOWED` invariable, cero gates.

**Ausencia larga** (F1 paso 5 ↔ F2 paso 3 bis) — `CONFIRMADA` → `SUSPEND` → `barrido: gracia vencida`
→ `IDLE` → al volver, `gate INITIAL`.

**Bloqueo de pantalla** (F1 paso 8 ↔ F2 paso 4) — `primer plano IGNORADO (dispositivo inactivo)` ×2,
reloj del límite detenido, y al desbloquear ningún gate (D-011 sigue sin implementar). El desbloqueo
por huella genera un evento de otro paquete que se **cancela** al instante por el regreso, y uno de
SystemUI que se **descarta** a los 3 s.

## Lo que deliberadamente NO cambió

* **B15 se reproduce tres veces con el mismo cronometraje.** Era la señal buscada: si hubiera
  desaparecido, el refactor habría cambiado comportamiento. Se corrige al implementar D-012, no aquí.
* **El bloqueo de pantalla sigue sin provocar fricción**, pendiente de D-011.
* `PauseController` conserva su propio `System.currentTimeMillis()`: ya acepta un `now` inyectable y
  en F3 se inyecta el controlador completo en el motor.

## Camino no re-verificado, y por qué

El reingreso **dentro** de la ventana de gracia (volver entre los 3 s de la confirmación y los 10 s
del final) no se reprodujo en F2: los dos intentos cayeron fuera de esa franja (3,27 s → `CANCELADA`;
y una ausencia más larga → `gate INITIAL`). No se insistió porque la aritmética ya está demostrada:
`sweepExpiredSuspensions` disparó a los **7,01 s exactos** y evalúa la misma expresión
`clock.now() - suspendedAtMillis > suspendGraceMillis` que `handleAllowedReentry`. Queda cubierto por
un test de `spec/` en F4, donde el reloj es inyectable y la franja es exacta.

## Nota de método

El primer intento de captura (proceso `adb logcat` en segundo plano) volvió a morirse, y limpiar el
buffer del dispositivo antes de cada paso borró dos veces la evidencia del propio paso, porque el
usuario actuaba antes de que se insertara el marcador. El método que funciona: **no limpiar nunca el
buffer**, volcar de forma acumulativa tras cada paso y delimitar con marcadores
(`adb shell log -t RuSure/Engine`). Queda recogido en `docs/DEVICE_CHECKLIST.md` (fase F5).
