# Solicitudes por Telegram: clientes, seguridad y formato

## La mejor opción: el mismo bot, chats PRIVADOS y lista de autorizados
| Opción | Qué pasa | Veredicto |
|---|---|---|
| **A. Todos en el chat actual** | El cliente vería tus reportes y notificaciones | **No** |
| **B. Un segundo bot solo para clientes** | Separa los chats, pero hay 2 tokens, 2 lecturas y más mantenimiento | Posible, sin ventaja real |
| **C. El mismo bot con chats 1 a 1 + lista de autorizados** | Cada cliente solo ve SU conversación con el bot. Tus reportes salen solo a tu chat (tu ID). El bot ignora a quien no autorizaste | **✔ Recomendada (implementada)** |

### Cómo protege
- Los reportes van **únicamente** al Chat ID del master. Un cliente nunca está en ese chat.
- Un cliente solo ve **sus** solicitudes (`/estado`, `/cola`, `/cancelar` filtran por quién las envió).
- Quien no esté autorizado recibe «Este canal es privado» + su ID; a ti te llega «🆕 SOLICITUD DE ACCESO» y decides.
- Los grupos se ignoran.
- Solo el **teléfono del master** lee el bot (el del usuario no).

### Endurece el bot (una vez, en @BotFather)
- `/setjoingroups` → **Disable** (que no lo añadan a grupos).
- Si el token se filtra: `/revoke` y pega el nuevo en tu Ajustes.

### Aviso de privacidad
Los mensajes con los datos (NIE, nombre, teléfono, correo) pasan por los servidores de Telegram; los bots **no** usan cifrado de extremo a extremo. Decide si es aceptable para tus clientes.

## Comandos
| Quién | Comando | Qué hace |
|---|---|---|
| Master | `/clientes` | Lista los clientes autorizados |
| Master | `/autorizar ID [nombre]` | Autoriza a un cliente (su ID te llega en el aviso) |
| Master | `/bloquear ID` | Le quita el acceso (sus solicitudes ya creadas siguen) |
| Todos | `/NUEVA` + datos | Registra una o varias solicitudes |
| Todos | `/estado` `/cola` | El master ve todo; el cliente, solo lo suyo |
| Todos | `/cancelar ID` | ID de 6 caracteres o documento (el cliente, solo las suyas) |
| Todos | `/ayuda` | Muestra el formato |

## Formato
```
/NUEVA
NIE: Y1234567X
NOMBRE: JUAN PEREZ
NACIONALIDAD: NICARAGUA
TELEFONO: +50588888888
CORREO: ejemplo@correo.com
PROVINCIA: NAVARRA
OFICINA: TUDELA
TRAMITE: HUELLAS_INICIAL
FECHA_MINIMA: 2026-12-01
```
- **Varias solicitudes en un mensaje:** separa con una línea `---` o empieza cada una con `/NUEVA`. El cliente puede enviar hasta 10 por mensaje.
- **PROVINCIA vacía:** se prueba **cualquier provincia que ofrezca el trámite**, una distinta en cada intento. Con provincia vacía no se puede indicar oficina.
- **OFICINA:** nombre completo o parcial (sin tildes ni mayúsculas). Si coincide con varias, el bot responde con las opciones; si no existe, con las disponibles. Varias oficinas: sepáralas con `|`.
- **TRAMITE:** `HUELLAS_INICIAL` = Toma de huellas. También vale el código o el nombre. Vacío = huellas.
- **FECHA_MINIMA:** `AAAA-MM-DD` o `DD/MM/AAAA`. Vacía = primera cita disponible desde hoy (hora de España). Con fecha: la primera cita igual o posterior.
- **DOCUMENTO:** si también viene `NIE`, el NIE es el que se usa y el bot avisa de que DOCUMENTO no se usó. Si solo viene `DOCUMENTO`, se detecta si es NIE, DNI o pasaporte.

## Respuestas
- Al registrar: `✅ ID ABC123 · NOMBRE (***567) · Provincia · desde fecha`.
- Al conseguir cita: el cliente recibe `🎉 ¡CITA CONSEGUIDA!` con los datos, justificante y la **captura de pantalla** de la confirmación; tú recibes el reporte completo y la misma captura. También se le avisa si se cancela, vence o falla.
