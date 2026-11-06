# CitaAsistente 🇪🇸

Cola de solicitudes de **cita previa de extranjería** con detección, **autorrelleno** de la sede electrónica y avisos por **Telegram y correo**. Todo dentro de una app Android (APK); el bot de Node sigue como herramienta aparte.

| Carpeta | Qué es |
|---|---|
| `android/` | **App Android**: cola de solicitudes, autorrelleno, alertas, Telegram, correo y estadísticas. Rojo y gualda. |
| `bot/` | **Bot Node/TypeScript** (el que ya funciona) con mejoras de precisión y tus clases `actions.ts` / `03-personalId.ts`. |
| `shared/catalog.json` | Provincias → trámites → sedes (52 provincias). Fuente única de bot y app. |
| `shared/nationalities.json` | **204 nacionalidades oficiales** del portal con su código. Fuente única de bot y app. |
| `.github/workflows/` | Compilación del APK y tests con GitHub Actions. |

> **Aviso:** proyecto no oficial, sin relación con el Ministerio ni con la sede electrónica. Úsalo con el consentimiento de cada persona cuya cita gestiones. El **captcha y el código SMS los resuelve una persona**.

---

## 1. Cómo funciona la app

La app tiene cuatro pestañas, y en cada una los pasos son **menús desplegables**: se abre uno, lo rellenas, pulsas *Guardar* y se contrae con ✔.

### Solicitar
1. **Paso 1 · Datos de la persona:** tipo de documento, número (se valida la letra de control), nombre, **nacionalidad elegida de la lista oficial** (no se escribe libre: si no coincide exactamente con el portal, la sede no procesa la solicitud), teléfono y correo.
2. **Paso 2 · Trámite:** provincia → trámite → sedes por prioridad (catálogo oficial) y **fecha mínima**.
3. Botón **SOLICITAR**: guarda la solicitud en la cola. El paso 2 se conserva para registrar rápido a la siguiente persona. Evita duplicados (mismo documento, provincia y trámite activos).

### Cola
Lista de todas las solicitudes (1 o 50) con su estado: **Pendiente · En proceso · Esperando captcha/SMS · Con cita · Fallida · Cancelada**. Al tocar una:
- ves **el proceso realizado** (cada paso con fecha y hora: inicio, datos rellenados, cita encontrada, captcha superado, SMS, confirmación) y, si hay cita, el texto de confirmación de la sede;
- puedes **iniciarla ahora** de forma manual, reactivarla, cancelarla, reenviar el aviso o eliminarla.

**Procesar cola** las ejecuta **de una en una** y **Solo un lote** procesa un lote (por defecto 5) y se detiene.

### Alertas
- **Detección propia:** al procesar la cola, la app recorre el formulario de cada solicitud; si hay cita, lo detecta sola. Solicitudes del mismo trámite y provincia comparten comprobación: si no hay citas para una, se salta el resto en esa pasada. **No hace falta tener instalada ninguna otra app.**
- **Fuentes externas opcionales:** si quieres, puede leer también las notificaciones de *Extranjería Alerts* y del bot de Telegram (`CITA_DISPONIBLE | provincia | trámite`). Cuando una alerta coincide con solicitudes pendientes, **arranca el autorrelleno sin tocar nada** (requiere el permiso *Mostrar sobre otras apps*, que se concede desde esta pestaña; sin él, suena la alarma y la tocas).
- Estado del sistema, historial de alertas, **Probar interpretación** y registro.

### Ajustes
- **Telegram:** token del bot y Chat ID, con **interruptor general** y tres interruptores por tipo (alertas de cita · avisos del proceso · resultados). Botón de prueba.
- **Correo (SMTP, TLS obligatorio):** envía un informe al **registrar** cada solicitud (con todos sus datos y en formato del bot) y al **conseguir cita o fallar** (con el proceso completo y estadísticas). Botón de prueba. Usa una **contraseña de aplicación** (en Gmail: Cuenta → Seguridad → Contraseñas de aplicaciones).
- **Procesamiento:** tamaño de lote, pausa entre lotes, pausa entre pasadas, separación entre solicitudes, tiempo para captcha/SMS.
- **Estadísticas:** total, con cita, tasa de éxito, intentos, por provincia. Botón para enviarlas por Telegram/correo.

### El flujo automático
`SOLICITAR → cola → detección → autorrelleno → ✋ tú: captcha y SMS → CITA CONFIRMADA → aviso a Telegram/correo → siguiente solicitud`

Pasos que rellena (los mismos que el bot): provincia → trámite → entrar → documento, nombre y nacionalidad → confirmar → sede → teléfono y correo → **cita disponible más próxima a partir de tu fecha mínima** (sea diciembre, enero…). Si no resuelves el captcha/SMS en el tiempo configurado, la solicitud vuelve a la cola y sigue la siguiente.

**Recomendación de uso:** de una en una (solo hay una sesión a la vez porque el captcha lo resuelves tú), lotes de **5** y **10 min** de pausa entre lotes (valores por defecto).

---

## 2. Compilar el APK con GitHub Actions

1. Sube el contenido de esta carpeta a la raíz de un repositorio de GitHub.
2. **Actions → «Compilar APK (Android)» → Run workflow** (o *push* a `main`).
3. Descarga el artefacto **CitaAsistente-apk** (`app-debug.apk`) e instálalo.
4. Etiqueta `v0.2.0` → el APK se adjunta en **Releases**.

APK firmado (opcional): secretos `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

El workflow ejecuta antes la prueba del motor de autorrelleno (DOM simulado) y los tests unitarios.

### Primer uso
1. Instala y abre; acepta el permiso de avisos.
2. **Ajustes → Telegram** y **Correo**: rellena, prueba y guarda.
3. **Alertas → Estado y permisos:** concede *Mostrar sobre otras apps* (autoarranque) y quita el límite de batería.
4. Registra solicitudes en **Solicitar** y pulsa **Procesar cola** (o *Iniciar vigilancia* en Alertas).

> **Importante:** el autorrelleno se ejecuta en la pantalla del asistente, que mantiene la pantalla encendida. Para vigilar durante horas deja el móvil **enchufado** con el asistente abierto. Android no permite ejecutar de forma fiable esa navegación en segundo plano; mover el motor a un servicio en primer plano es una de las propuestas de `docs/MEJORAS.md`.

---

## 3. El bot (`bot/`)

`cd bot && cp config.example.json config.json` (rellénalo), `npm ci`, `npm run build`, `npm start`. Mejoras:

- **Nacionalidad exacta**: se adoptan tus `actions.ts` y `03-personalId.ts` (`selectBootstrapOption`: valor o texto idéntico, verificado tras seleccionar). `paisNac` acepta el **código del portal** (PERU = `242`, **no** el ISO `604` que traía el ejemplo original) o el nombre exacto. Nombres ambiguos como «GUINEA» se rechazan.
- **Validación al arrancar** contra el catálogo y las nacionalidades (con «¿quisiste decir…?»): no toca la web si hay errores.
- Provincia por código o nombre; sedes sin falsos fallos (tildes, apóstrofos, mayúsculas).
- Mensajes con etiqueta `CITA_DISPONIBLE` / `SIN_CITAS` / `PAGINA_FINAL`.
- 30 tests (`npm run test:unit`).

---

## 4. Lo que NO incluye (y por qué)

- **Proxies y comportamiento «humano» para evitar la detección.** No los he añadido a la app ni los he ampliado: sirven para esquivar los límites y controles anti-automatización de la sede, y a la escala de decenas de solicitudes eso deja de ser un asistente y pasa a ser acaparamiento de citas. Lo que sí hace la app es **respetar a la sede**: una sola sesión, separación mínima entre solicitudes, pausas entre lotes y esperas crecientes (1, 2, 5, 10, 15 min) si la sede limita o rechaza peticiones. Tu bot conserva su código original de proxies sin tocar; su uso es responsabilidad tuya.
- **Credenciales de la app Extranjería Alerts.** La app no se conecta a su servicio: si quieres esa fuente, lee sus notificaciones locales.
- **Resolver captchas.** Siempre una persona.

## 5. Seguridad y privacidad

- Solicitudes, token de Telegram y contraseña SMTP se guardan **cifrados** (Android Keystore). No hay copia de seguridad en la nube.
- Los datos personales solo salen hacia: la sede oficial (solo `https` de `administracionelectronica.gob.es`), **tu** bot de Telegram (documento enmascarado) y **tu** correo (datos completos al registrar). Usa un buzón dedicado: el correo no es un canal ideal para NIE y teléfonos.
- El `config.json` real del original no se incluye; si un token llegó a un sitio público, revócalo en @BotFather (`/revoke`).

## 6. Limitaciones conocidas
- El motor se ha probado con un DOM simulado y las páginas de ejemplo del bot, no contra la sede real: la primera ejecución real es la verificación.
- Con la variante de **calendario mensual** (la del vídeo) la app te pide elegir día y hora; con el listado clásico (`CITA 1/2/3`) elige sola. Envía el HTML de esa pantalla para automatizarla.
- El texto exacto de las notificaciones de *Extranjería Alerts* no es público: se calibra con **Probar interpretación**.

## 7. Créditos y licencia
El bot parte de *Cita-previa-TIE-Appointment-Alert-on-Telegram* (MIT). Las 204 nacionalidades y los códigos de provincia son **valores públicos** de los desplegables del portal (capturados el 12/08/2026); contrástalos con la web oficial. No se ha copiado código de *extranjeria-cita-helper* (licencia PolyForm Noncommercial) ni de otros proyectos.
