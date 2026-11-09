# Tutorial: bot de Telegram para recibir solicitudes (paso a paso)

Con esto el bot **recibe solicitudes** (tuyas y de tus clientes), te manda los **reportes** y responde comandos. Va de principio a fin; si ya hiciste un paso, sáltalo.

## 1. Crear el bot (solo si aún no existe)
1. En Telegram abre **@BotFather**.
2. Escribe `/newbot` → elige un nombre (por ejemplo «Cita Extranjería Bot») → elige un usuario que termine en `bot` (por ejemplo `funes_citas_bot`).
3. BotFather te da el **token** (algo como `123456:ABC…`). **Cópialo y no lo compartas** (ni en capturas): quien lo tenga controla el bot. Si se filtra: `/revoke` en @BotFather.

## 2. Configurar el bot en @BotFather
Estás aquí si ves «Choose a bot to change group membership settings».
1. **Impedir que lo añadan a grupos:** `/setjoingroups` → toca tu bot (`@funes_citas_bot`) → toca **Disable**.
2. **Lista de comandos** (aparece al escribir «/»): `/setcommands` → toca tu bot → pega esto y envía:
```
nueva - Registrar una solicitud
ayuda - Ver el formato
estado - Estado de tus solicitudes
cola - Solicitudes pendientes
cancelar - Cancelar una solicitud (ID)
intentos - Intentos del día por NIE (administrador)
clientes - Clientes autorizados (administrador)
autorizar - Autorizar a un cliente (administrador)
bloquear - Quitar acceso a un cliente (administrador)
```
3. Opcionales: `/setdescription` y `/setabouttext` (lo que ve un cliente al abrir el bot, por ejemplo «Envía aquí tus datos para registrar tu cita»), `/setuserpic`.
4. `/setprivacy` **no hace falta** tocarlo (el bot solo trabaja en chats privados).

## 3. Obtener tu Chat ID (tu número en Telegram)
**Opción fácil:** abre **@userinfobot** y escribe `/start`: te responde con tu **Id** (un número).
**Opción con tu bot:** escríbele `/start` a tu bot y abre en el navegador `https://api.telegram.org/bot<TU_TOKEN>/getUpdates`; busca `"chat":{"id":NÚMERO`. (Hazlo **antes** de activar la recepción en la app: si la app ya lee el bot, consume los mensajes.)

## 4. Configurar la app (teléfono del master)
1. Abre la pestaña **Ajustes** → **Telegram (alertas y avisos)**.
2. Pega el **token** y tu **Chat ID** (solo el número).
3. Activa **Enviar notificaciones a Telegram**. (Debajo hay tres interruptores por tipo: alertas de cita, avisos del proceso, resultados.)
4. Activa **Recibir solicitudes desde mi chat de Telegram**.
5. Pulsa **Guardar y cerrar**. Acepta el permiso de notificaciones si Android lo pide.
6. En Estadísticas pulsa **Quitar límite de batería** (para que el bot siga leyendo con la pantalla apagada).

## 5. Probar que funciona
1. En tu bot escribe `/ayuda` → debe responder con el formato.
2. Envía una solicitud de prueba:
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
   Debe responder `✅ ID ABC123 · JUAN PEREZ (***567X) · Navarra · …`. Si algo está mal, te dice **qué campo** y las opciones (por ejemplo, las oficinas disponibles).
3. `/cola` muestra las pendientes; `/estado`, las cifras; `/cancelar ABC123` la cancela.
4. Para varias en un mensaje: separa con una línea `---` o empieza cada una con `/NUEVA`.

## 6. Dar acceso a un cliente
1. El cliente abre tu bot y escribe cualquier cosa (o `/start`). Recibe «🔒 Este canal es privado… Tu ID es 123456789».
2. A ti te llega «🆕 SOLICITUD DE ACCESO … Para autorizarlo: /autorizar 123456789».
3. Tú respondes `/autorizar 123456789` (puedes añadir un nombre: `/autorizar 123456789 Ana`). Él recibe «✅ Ya estás autorizado».
4. Desde ahí el cliente envía `/NUEVA …` y solo ve **sus** solicitudes. Recibe el ID al registrar y el resultado (cita conseguida con captura, cancelada, vencida o fallida).
5. `/clientes` los lista; `/bloquear 123456789` le quita el acceso.

## 7. Qué recibes tú
Reportes de cada solicitud (nueva, inicio, ayuda humana, cita, fallida), **interpretación de la sede** (figuras 1–4, intentos n/5), avisos de supervisión (cancelada, eliminada, vencida, informe de operación), proxies, fallos de la app y respaldos cifrados.

## 8. Si algo no responde
| Síntoma | Causa y solución |
|---|---|
| El bot no contesta a `/ayuda` | La recepción está apagada, falta el permiso de notificaciones o Android limitó la app. Revisa los pasos 4.4 a 4.6 y reabre la app |
| «Conflict» o responde a ratos | **Dos teléfonos** leen el mismo bot. Solo el del master debe leerlo; en el del usuario no se activa la recepción |
| No me llega nada | Token o Chat ID mal copiados (el Chat ID es solo el número) |
| Un cliente dice que no puede | Aún no lo autorizaste (`/autorizar ID`) |
| La oficina «no se reconoce» | Escribe una parte más larga del nombre o copia una de las que te lista el bot |
| Cambié de teléfono | Restaura el respaldo (trae el token y los clientes) y vuelve a activar la recepción |

## 9. Seguridad en dos líneas
El token nunca se comparte y los clientes escriben **en su propio chat** con el bot: no ven tus reportes ni las solicitudes de otros. Los datos pasan por los servidores de Telegram (los bots no usan cifrado de extremo a extremo).
