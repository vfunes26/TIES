# Qué hace y qué NO hace la aplicación hoy (v0.1.33)

## Qué hace

### Solicitudes y proceso
- Guarda solicitudes (1 a 50) con documento (NIE/DNI/pasaporte validado), nombre, nacionalidad (lista oficial), teléfono, correo, provincia (o «cualquiera»), trámite, oficinas por prioridad y fecha mínima.
- **Procesa la cola de una en una**: abre la sede, rellena provincia, trámite, datos de identidad, oficina, teléfono y correo, busca cita y se detiene en el **captcha** y en el **SMS** para que tú los resuelvas. Al confirmarse, guarda el justificante y avisa.
- Trabaja por **lotes** (tamaño configurable, p. ej. 3) con pausas entre solicitudes y entre lotes; **un proxy por lote** si lo activas.
- Si la solicitud no tiene provincia, prueba **una provincia distinta en cada intento** entre las que ofrecen el trámite.

### Interpretación de la sede (nuevo)
- Reconoce los mensajes de las **Figuras 1–4**, cuenta los **intentos por NIE y día (hora de España)**, hace una **pausa de 30 min** tras un «sin citas», **detiene** el NIE si el portal lo bloquea (hasta las 00:00) y te lo cuenta **al instante por Telegram** (solicitud, mensaje, significado, intentos n/5 y qué hace). Detalle en `PORTAL_FIGURAS.md`.
- Reconoce la confirmación final «Tu cita ha sido confirmada».
- Si aparece una pantalla que no conoce: aviso «🧩 PÁGINA NO RECONOCIDA».

### Telegram
- **Reportes** de cada paso y resultado, con captura de pantalla de la confirmación.
- **Recepción de solicitudes** con `/NUEVA` (varias por mensaje), respuesta con ID, `/estado`, `/cola`, `/cancelar`, `/intentos`.
- **Clientes autorizados** en chats privados (`/autorizar`, `/bloquear`, `/clientes`): cada uno ve solo lo suyo y recibe el resultado de sus solicitudes.

### Perfiles y acceso
- **Master** (contraseña local): todo, incluida la configuración.
- **Usuario UI/UX** (correo de Google + clave de aplicación): crea y procesa solicitudes, historial y estadísticas; sin configuración. La app comprueba con Google cada 30 min que la clave siga existiendo y cierra la sesión si no.

### Datos, respaldo y supervisión
- Respaldo **cifrado** automático en Drive y Telegram; restauración tras reinstalar.
- **Historial de 30 días**, vencimiento de pendientes a los 30 días y **estadísticas por mes** permanentes.
- Supervisión del master: nueva, cancelada, eliminada, vencida, **informe de operación continuo**, fallos de la app, proxies desactivados, accesos nuevos.
- Proxies HTTP/HTTPS con usuario y clave (hasta 50): se prueban antes de usarse, se cambian solos si fallan y se desactivan tras 3 fallos seguidos.
- Correo (SMTP) con informes de nueva, cita y eliminada.
- Compila un **APK release firmado** con nombre `TIES-APK-v0.1.N.apk`.

## Qué NO hace
- **No resuelve el captcha ni el SMS**: siempre los haces tú.
- **No usa Cl@ve ni certificado digital**: entra por la vía «sin clave» (por eso aplican los 5 intentos y puede salir la Figura 1).
- **No se salta bloqueos ni límites**: cuando el portal pide esperar (Fig. 4), espera.
- **No garantiza una cita**: depende de que la sede publique citas y de que tú resuelvas captcha/SMS a tiempo.
- **No sabe los intentos que gastes a mano** en el navegador (díselos con `/intentos NIE N`).
- **No es un servicio 24/7 en un servidor**: corre en el teléfono (con la pantalla encendida durante el proceso y la batería sin límite).
- **No cifra de extremo a extremo** lo que pasa por Telegram.
- **No acepta cualquier mensaje** del bot: solo el master y los clientes que autorizaste.
- **No garantiza que la sede no cambie**: si cambia una pantalla, avisa con «PÁGINA NO RECONOCIDA» y hay que actualizar el autorrelleno.
- **No es oficial**: proyecto independiente sin relación con la Administración; usa los datos solo con el consentimiento de cada persona.
- Las reglas de las figuras (5 intentos/día, bloqueo hasta las 00:00) vienen de **información de la comunidad**, no de documentación oficial.
