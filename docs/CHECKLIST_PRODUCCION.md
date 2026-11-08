# Lista de verificación antes de entregar / pasar a producción

## Compilación y versión
- [ ] Action en verde y artefacto `TIES-APK-v0.1.N` con el archivo `TIES-APK-v0.1.N.apk` dentro (solo release).
- [ ] El APK nuevo se instala **encima** del anterior sin desinstalar.
- [ ] En Estadísticas se ve «Versión 0.1.N».

## Master (tu teléfono)
- [ ] Telegram: botón «Probar» OK. Correo: botón «Probar» OK.
- [ ] Interruptores de avisos (registrar, resultado) activados.
- [ ] «Solicitudes por lote» = 3; pausa entre lotes configurada.
- [ ] Proxies: «Probar conexiones» muestra los buenos con IP de España.
- [ ] Respaldo: frase guardada, archivo de Drive elegido, «Respaldar ahora» OK.
- [ ] **Prueba de recuperación hecha** (restaurar el `.ties` en otro teléfono o tras reinstalar).
- [ ] En Estadísticas: botón **Quitar límite de batería** pulsado.
- [ ] BotFather: `/setjoingroups` → Disable.

## Usuario UI/UX (teléfono 2)
- [ ] Gmail dedicado con clave de aplicación propia para ese teléfono.
- [ ] (Recomendado) tu Gmail en `ALLOWED_USER_EMAIL` si quieres restringirlo a un solo correo.
- [ ] Entra con correo + clave; te llega «🔑 NUEVO ACCESO».
- [ ] Restaura el respaldo del master; botón de batería pulsado; permiso de notificaciones concedido.
- [ ] Revoca la clave en Google y comprueba que la sesión se cierra.

## Clientes de Telegram
- [ ] Un cliente de prueba escribe al bot → te llega la solicitud de acceso → `/autorizar` → registra una solicitud de prueba → recibe ID.
- [ ] Prueba con provincia vacía y con una oficina ambigua.
- [ ] Una cita real: llega la captura al master y al cliente.

## Supervisión
- [ ] Llegan: nueva, cancelada, eliminada, informe de operación y «Proxy activo» en cada lote.
- [ ] Prueba un fallo (por ejemplo, proxies malos): llega «🔌 PROXY DESACTIVADO» tras 3 fallos.
