# Checklist de dispositivo

> **Cuándo es obligatoria** (regla 7 de `CLAUDE.md`, D-010): cualquier cambio que afecte a la
> **detección de salida**, al **primer plano** o a la **temporización**. Los tests JVM no la
> sustituyen: el motor infiere la intención del usuario a partir de ventanas reales del sistema, y
> ningún doble reproduce cómo se comportan el teclado, la persiana o la propia pantalla de fricción.
>
> Los tests cubren la lógica; esta checklist cubre las señales.

---

## Preparación

**1. Un solo servicio de accesibilidad.** Comprueba que RuSure es el único:

```powershell
adb -s <serie> shell settings get secure enabled_accessibility_services
```

Si aparece otra app de bienestar digital (otro bloqueador, otro temporizador), **desactívala**: su
pantalla se superpone a la app objetivo, con lo que `hasApplicationWindow` deja de encontrar la
ventana del objetivo y lo que midas será la interacción entre las dos apps, no el comportamiento de
RuSure.

**2. Instala y confirma que el servicio quedó enlazado:**

```powershell
.\gradlew.bat :app:assembleDebug
adb -s <serie> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <serie> shell pidof com.rusure.app
```

**3. Precondición de los objetivos.** Anota de cada objetivo que vayas a usar: activo sí/no, modo
(Espera / Bloqueado) y sus tres temporizadores. Si no te acuerdas, la traza te los dice: el gate
registra `INITIAL <n>s` y cada tick registra `limite=<x>/<y>ms`.

**4. Captura.** El método que funciona, aprendido a base de perderlo tres veces:

```powershell
# Marcador antes de cada paso (las comillas dobles+simples son necesarias: el shell del
# dispositivo se come los paréntesis y las comillas sueltas)
adb -s <serie> shell log -t RuSure/Engine "'=== PASO 1: <nombre> ==='"

# Volcado DESPUÉS de cada paso: a un temporal, y añadir al acumulado solo si tuvo éxito
adb -s <serie> logcat -d -v time -s RuSure/Engine > tmp.log
cat tmp.log >> baseline\<fase>.log
```

Tres cosas que **no** hay que hacer:

* **No limpiar el buffer** (`logcat -c`) entre pasos. El usuario suele actuar antes de que se inserte
  el marcador siguiente, así que limpiar borra justo la evidencia del paso que acabas de pedir.
* **No redirigir (`>`) sobre el fichero acumulado.** Si el volcado se cuelga (móvil desconectado:
  `- waiting for device -`) el `>` ya lo truncó a 0 bytes y pierdes todo lo anterior.
* **No fiarse de un `adb logcat` en segundo plano.** Se muere al cerrarse la sesión y se lleva la
  captura por delante.

El buffer del dispositivo es pequeño (5 MiB en Samsung, y **ya está en su máximo**: `logcat -G` no lo
amplía) y lo comparten todas las apps, así que **vuelca pronto**: un paso de más de un par de minutos
puede perder su inicio, y de un día para otro no queda nada.

---

## Pasos

Cada paso: **acción**, **resultado esperado** con su referencia, y **qué buscar en la traza**. Donde
dice `PREGUNTA ABIERTA #n` el resultado **no es especificación**: solo se registra lo que ocurra.

### 1 · Apertura limpia de una app global

**Acción**: desde el inicio del teléfono, abre la app objetivo. Espera la cuenta atrás y pulsa
**Continuar**. Quédate ~10 s dentro.

**Esperado**: fricción al abrir (`GateMode.INITIAL`), y nada más.

```
APP_GLOBAL <clave> | estado=IDLE fresco=true -> gate INITIAL
transicion <clave> -> GATING | INITIAL <n>s (pantalla de friccion)
decision <clave> | Continuar
transicion <clave> -> ALLOWED | el usuario continuo
tick <clave> | estado=ALLOWED enUso=true suspendido=false limite=...
```

**Fallo conocido que aparecerá aquí** (B15, no corregido): si entre el `-> GATING` y tu decisión llega
un evento del teclado, verás `salida CONFIRMADA` → `-> CLOSE` → `cierre (sesion=N)` **antes** del
`Continuar`, y el `ALLOWED` resultante no tendrá sesión. Es lo esperado hoy; lo corrige D-012.

### 2 · Buscador con el teclado abierto más de 10 s

**Acción**: abre el buscador de la app objetivo, escribe, y quédate **más de 10 s** con el teclado
abierto. Vuelve al feed.

**Esperado**: **sin fricción** (D-002 + D-005 + D-007).

```
primer plano <objetivo> -> <teclado> | salida pendiente ANOTADA (confirmacion en 3000ms)
salida DESCARTADA | pkg=<objetivo> conserva ventana de aplicacion
```

**Señal de fallo**: cualquier `-> GATING` en este paso. Sería la regresión de §26.

### 3 · Comentarios con el teclado abierto más de 10 s

**Acción**: abre los comentarios de una publicación, toca el campo de texto, espera **más de 10 s**
sin enviar nada, cierra y vuelve.

**Esperado**: **sin fricción** (D-001 + D-005 + D-007). Es el síntoma original de §25.

```
salida DESCARTADA  (una o varias veces)
salida pendiente CANCELADA (regreso)   (si vuelves antes de los 3 s)
```

### 4 · Salida corta: volver **antes** del umbral

**Acción**: pulsa HOME y vuelve **antes de 6 s**. Cronométralo: el umbral efectivo es la confirmación
(3 s) **más** la gracia (7 s) ≈ 10 s, y es fácil pasarse.

**Esperado**: reanuda sin fricción (D-006 con el umbral **actual**, `PREGUNTA ABIERTA #2b`).

```
salida CONFIRMADA -> suspension <clave> | dejoPrimerPlano=true gracia=7000ms -> SUSPEND
reingreso <clave> | en gracia -> reanuda sin friccion
```

### 5 · Salida larga: volver **después** del umbral

**Acción**: pulsa HOME, espera **más de 15 s** en el launcher sin abrir nada, y vuelve.

**Esperado**: fricción, como apertura nueva (D-006, umbral actual).

```
salida CONFIRMADA -> SUSPEND (gracia 7000ms)
barrido: gracia vencida -> cierra [<clave>]
transicion <clave> -> IDLE | cierre (sesion=N)
APP_GLOBAL <clave> | estado=IDLE fresco=true -> gate INITIAL
```

### 6 · Sección: entrar, y luego navegación interna

**Acción**: abre la app con sección vigilada y entra en la sección. Acepta la fricción. Luego abre los
comentarios con el teclado **más de 10 s** y vuelve a la sección.

**Esperado**: un gate al entrar y **ninguno** al volver (D-001 + D-007).

```
SECCION <clave> DETECTADA | estado=IDLE -> gate INITIAL
settle scan programado | pkg=<app>        (puede o no hacer falta)
settle scan +400ms | pkg=<app>
SECCION no detectada en <app> | nav. interna -> suspende [<clave>]
suspension <clave> | dejoPrimerPlano=false gracia=sin expiracion
SECCION <clave> DETECTADA | estado=ALLOWED -> reingreso
```

**Nota**: puede que no aparezca ningún *settle scan*. Es correcto si la sección se detectó en el
primer evento: la guarda solo lo programa si queda alguna sección en `IDLE`.

### 7 · Recordatorio de uso continuo

**Acción**: quédate en la app objetivo hasta cumplir el límite configurado.

**Esperado**: fricción de reingreso en el tiempo configurado, **sin** abrir otra apertura (D-008).

```
tick <clave> | ... limite=<x>/<limite>ms      (acercándose al límite)
transicion <clave> -> LIMIT_REACHED | limite alcanzado -> gate RE_ENTRY
transicion <clave> -> GATING | RE_ENTRY <n>s
```

**Imprecisión conocida** (H5, `docs/baseline/F1_summary.md`): cada aparición de teclado congela ~3 s el
reloj del límite, así que el recordatorio puede llegar algo tarde. Se ve en los ticks como
`enUso=false` con `limite` quieto.

### 8 · Modo Bloqueado

**Acción**: con un objetivo en modo Bloqueado, intenta abrirlo.

**Esperado**: sin pantalla de espera; vuelta al inicio.

```
gate <clave> INITIAL | entryAction=BLOCK -> bloqueo
transicion <clave> -> BLOCKED | INITIAL -> HOME
```

`PREGUNTA ABIERTA #5`: si el objetivo es una **sección**, observa que te saca de la app **entera**.
`PREGUNTA ABIERTA #6`: el intento queda en estadísticas como una apertura de 0 minutos.
Si aparece `reimposicion de bloqueo | HOME=true` más de una vez, anótalo: en la línea base bastó una
sola pulsación.

### 9 · Pausa global: activar

**Acción**: activa la pausa de 5 minutos y abre un objetivo.

**Esperado**: **sin fricción**, pero la apertura y el tiempo **sí** se cuentan (D-009, decisión #3).

```
flanco de pausa | pausado=true
pausa iniciada | cierra bloqueados=[...]
gate <clave> INITIAL | PAUSA GLOBAL -> pasa sin friccion
transicion <clave> -> ALLOWED | sin friccion (pausa global, INITIAL)
```

### 10 · Pausa global: fin dentro de la app

**Acción**: quédate dentro del objetivo hasta que venza la pausa (5 min).

**Esperado**: fricción **al instante** (D-009, decisión #1), reutilizando la sesión abierta durante la
pausa.

```
flanco de pausa | pausado=false
pausa terminada | reevalua=[<clave>] primerPlano=<objetivo>
transicion <clave> -> GATING | INITIAL <n>s
```

Comprueba que **no** hay ningún `cierre (sesion=N)` entre el inicio de la pausa y este gate: es lo que
demuestra que no se cuenta una apertura extra.

### 11 · Pausa global: "Reanudar ahora"

**Acción**: activa la pausa y cancélala con "Reanudar ahora". Abre el objetivo.

**Esperado**: fricción (D-009, decisión #2). Para el motor es el mismo flanco que el vencimiento.

### 12 · Pantalla bloqueada

**Acción**: dentro del objetivo, bloquea la pantalla **20 s**, desbloquea y vuelve.

**Esperado hoy**: **sin fricción**, y el reloj del límite congelado (S8).

```
primer plano IGNORADO (dispositivo inactivo) | pkg=...
tick <clave> | ... enUso=false ... limite=<x>   (el mismo x durante todo el bloqueo)
```

**`PREGUNTA ABIERTA` / D-011 (confirmada, no implementada)**: el usuario ha decidido que esto debe
cambiar —pasado el umbral de D-006, desbloquear debe contar como apertura nueva—. Hasta que se
implemente, lo correcto en este paso es que **no** haya fricción.

---

## Qué hacer con lo que encuentres

* **Si un paso contradice su `D-xxx`**: es una regresión. Repórtala con las líneas de traza; no la
  arregles en la misma fase (D-004).
* **Si un paso marcado `PREGUNTA ABIERTA` sale distinto de la última vez**: el comportamiento cambió
  sin decisión. Pregunta antes de tocar nada (D-003), y revisa si hay un test de `current/` que
  debería haberlo detectado.
* **Si encuentras algo que no está en esta checklist**: anótalo como hallazgo en
  `docs/baseline/`, con la cadena de traza completa, y si procede añádelo al informe como bug `B` o
  caso límite `E`. Así entraron B15 y E29.
* **No mezcles el arreglo con el diagnóstico.** Primero se documenta, después se decide, después se
  implementa.

## Privacidad de las trazas

La traza `RuSure/Engine` contiene nombres de paquete, claves de catálogo, estados y tiempos.
**Nunca** texto ni descripciones de contenido de los nodos. Los logs crudos **no se versionan**
(`baseline/` está en `.gitignore`); lo que se commitea es el resumen, y en él solo se nombran los
paquetes de las apps objetivo y las ventanas auxiliares del sistema.
