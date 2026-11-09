# Cómo interpreta la app los mensajes de la sede (Figuras 1 a 4)

> Fuente: guía de usuarios de un grupo de Facebook sobre la cita previa de extranjería. Es **información de la comunidad, no documentación oficial** de la sede. Por eso la app **reacciona a lo que el portal muestra** en cada momento y no solo a un número fijo.

| Fig. | Qué muestra el portal | ¿Gasta intento? | Qué hace la app | Qué te llega por Telegram |
|---|---|---|---|---|
| **1** | «No hay citas disponibles para la reserva **sin Cl@ve**. SÍ tienen… mediante el uso de Cl@ve» | **Sí** | Pausa de **30 min** con ese NIE y sigue con otros | ℹ️ SIN CITAS SIN CL@VE: solicitud, mensaje, significado, intentos n/5, pausa |
| **2** | Cuadro «INFORMACIÓN: no hay citas disponibles… rellene todos los campos…» (se cierra con X) | **No** (recargar es gratis) | No pulsa «Solicitar cita»; espera y vuelve a mirar | ℹ️ AVISO INFORMATIVO (máx. 1 cada 30 min por NIE) |
| **3** | «No hay citas disponibles. En breve, la Oficina pondrá a su disposición nuevas citas» tras pulsar «Solicitar cita» | **Sí** | Pausa de **30 min** con ese NIE | ⏸ SIN CITAS: intentos n/5 y pausa |
| **4** | Lo mismo + **«Cod. Oper.: …» y fecha/hora** (botón Aceptar) | — (ya superaste el límite) | **DETIENE** ese NIE hasta las **00:00 (hora de España)**; las solicitudes con otros NIE siguen | 🚫 BLOQUEO DEL PORTAL: solicitud, **código y hora**, significado, cuánto falta |

Además:
- **⚠️ Queda 1 intento** y **⚠️ Último intento del día usado** (5/5): aviso antes de llegar al límite.
- Cuando hay citas, el flujo llega al **captcha** (ya te avisa la alerta de «necesita ayuda») y, al final, a «**Tu cita ha sido confirmada**».
- Si aparece una página que no está en esta tabla, llega el aviso «🧩 PÁGINA NO RECONOCIDA» (con la dirección y el paso).

## Reglas de los intentos (según la guía)
- **5 pulsaciones de «Solicitar cita» por NIE y día.** Recargar la página no cuenta.
- A la 6.ª el portal **bloquea ese NIE** hasta las 00:00; aunque haya citas, no deja solicitar.
- La app cuenta un intento cada vez que **rellena la sede y pulsa «Solicitar cita»**. A las 00:00 (hora de España) el contador vuelve a cero.

## Repartir los intentos a lo largo del día
- Tú decides cuándo iniciar la cola o una solicitud. Tras un «sin citas», la app espera **30 minutos** antes de volver a gastar un intento con el mismo NIE.
- Un inicio **manual** de una sola solicitud ignora esa pausa de 30 min, pero **nunca** pasa del límite de 5.
- La app solo cuenta los intentos que hizo ella. Si gastaste alguno **a mano en el navegador**, díselo con `/intentos NIE N` (por ejemplo `/intentos Y1234567X 2`) y lo tiene en cuenta.
- `/intentos` (solo el master) muestra los intentos usados hoy, los NIE en espera y los bloqueados.

## Ajustes en el código (una línea cada uno)
En `data/AttemptBudget.kt`: `DAILY_LIMIT = 5` (intentos por día) y `COOLDOWN_MIN = 30` (espera entre intentos).

## Importante
La app **no intenta saltarse ningún bloqueo**: cuando el portal indica que hay que esperar, espera.
