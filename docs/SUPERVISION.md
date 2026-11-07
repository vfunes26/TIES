# Supervisión: qué le llega al master

El Usuario UI/UX trabaja en su teléfono con la configuración y el proxy cargados del respaldo del master. Todo lo que ocurre le llega al master por Telegram (y por correo en los casos marcados), con la etiqueta de quién lo hizo.

| Evento | Telegram | Correo | Quién lo envía |
|---|---|---|---|
| Solicitud nueva registrada | Sí | Sí | Ya existía (Reporter) |
| Inicio, captcha/SMS pendiente, sin citas | Sí | No | Ya existía |
| **Cita conseguida** | Sí | Sí | Ya existía |
| Fallida | Sí | Sí | Ya existía |
| **Cancelada** | **Sí (siempre)** | No | Nuevo |
| **Reactivada** | **Sí (siempre)** | No | Nuevo |
| **Vencida (30 días)** | **Sí (siempre)** | No | Nuevo |
| **Eliminada** | **Sí (siempre)** | **Sí** | Nuevo |
| **Informe diario de operación** | **Sí, 1 vez cada 24 h** | No | Nuevo (solo el teléfono del Usuario UI/UX) |
| Respaldo cifrado completo | Sí (cita nueva o 1 vez al día) | No | Ya existía |

«Siempre» = sale aunque estén apagados los interruptores de avisos, porque es supervisión.

## Informe diario
Ejemplo de lo que recibes:

```
📊 INFORME DIARIO
👤 Usuario UI/UX · 08/11/2026 09:00
Estado: ✅ operando con normalidad

Desde el último informe
• Agregadas: 3 · Con cita: 1 · Canceladas: 0
• Vencidas: 0 · Fallidas: 0 · Eliminadas: 1

Activas ahora: 4 (pendientes 2 · en proceso 1 · esperando captcha/SMS 1 · fallidas 0)
Última actividad: 08/11/2026 08:41
Proxy: activado · 5 guardados · 4 verificados
Último respaldo: 08/11/2026 08:42
```
Si hay algo mal, el estado pasa a **⚠️ requiere atención** y se lista (solicitudes fallidas sin atender, avisos del sistema de alertas, error de respaldo).

## Dos teléfonos en paralelo
- Cada teléfono tiene su propia base de solicitudes: lo que tú generas **no aparece** en el teléfono 2, salvo que ella restaure un respaldo tuyo.
- Lo que genera ella te llega como avisos y respaldos; no se mezcla con tus solicitudes.
- Los avisos que haces tú mismo en tu teléfono también te llegan. Si no los quieres, cambia `REPORT_MASTER_TOO` a `false` en `net/ActivityReport.kt`.

## Antes de entregarle el respaldo
Revisa en tu **Ajustes** que estén activados los avisos de Telegram y el correo (y «avisar al registrar» / «avisar resultado»): el teléfono 2 hereda tus interruptores al restaurar.
