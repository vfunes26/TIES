# Acceso del Usuario UI/UX con Google, proxy por lote e informe continuo

## 1. Acceso con correo de Google + clave de aplicación
| Perfil | Entra con | Cómo se valida |
|---|---|---|
| **Master** | Usuario `master` + contraseña local | Igual que antes |
| **Usuario UI/UX** | **Correo de Google + clave de aplicación de 16 caracteres** | Contra Google (`smtp.gmail.com`): se abre una sesión y se cierra, **no se envía nada** |

- **Cada 30 min** la app le pregunta a Google si esa clave de aplicación sigue existiendo (al abrir la app y cada ~15 min en segundo plano, cuando toca).
- Si Google la **rechaza** (revocada): se cierra la sesión, se borra la clave del teléfono, la cola en marcha se detiene entre una solicitud y la siguiente, la app vuelve a pedir correo y clave, y al master le llega por Telegram «🔒 ACCESO CERRADO».
- **Sin internet** la sesión no se cierra… salvo que pasen **más de 24 h** sin poder verificar.
- El master **no** se ve afectado.
- **Cambiar correo o clave:** en Estadísticas, el botón «Cambiar contraseña» del Usuario UI/UX se convierte en «Cambiar correo y clave de acceso» (se verifica con Google al guardar). Si el master revoca la clave, el login reaparece con el correo ya escrito.
- El login antiguo `uiux` + contraseña local **deja de funcionar**; una sesión antigua se cierra sola y pide el acceso de Google.

### Recomendaciones
- Usa un **Gmail dedicado** solo para TIES: una clave de aplicación da acceso a todo ese correo.
- Crea **una clave de aplicación distinta por teléfono** (Google permite varias) para poder revocar solo la de ese teléfono. Requiere verificación en 2 pasos.
- En `data/AccessPolicy.kt` pon tu Gmail en `ALLOWED_USER_EMAIL`: así **solo** ese correo puede entrar como Usuario UI/UX. Vacío = cualquier correo cuya clave acepte Google.
- Revocar la clave **no** apaga Telegram: para un corte total también revoca el token del bot (BotFather → `/revoke`), lo que afecta a los dos teléfonos.

## 2. Proxy por lote
La rotación usa los **lotes que ya existen** (Ajustes → Procesamiento → «Solicitudes por lote» y «Pausa entre lotes»). Deja «Solicitudes por lote» en **3**.

Ejemplo con 20 proxies y 5 solicitudes:
1. Se prueba y se aplica el proxy 1 → solicitud 1 → espera configurada → solicitud 2 → espera → solicitud 3.
2. Lote completo: se libera el proxy 1 y corre la pausa entre lotes.
3. Se prueba y se aplica el proxy 2 → solicitudes 4 y 5.
4. Con más solicitudes, cada 3 se pasa al siguiente proxy de la lista (la lista da la vuelta).

- Cada proxy nuevo se **prueba antes** de usarse (usuario, clave, IP, país); si falla, salta solo al siguiente.
- Durante la pausa entre lotes el proxy está liberado: los avisos de esa pausa salen directos.
- Las ejecuciones lanzadas por una alerta de cita mantienen un solo proxy.

## 3. Informe de operación continuo (teléfono del Usuario UI/UX)
Reemplaza al informe de una vez al día. Se envía cuando:
- hay un cambio en las solicitudes (se agrupan los cambios seguidos, ~90 s),
- se abre la app,
- queda algo pendiente de informar (se revisa cada ~15 min),
- y siempre 1 vez cada 24 h aunque no haya cambios.

Límites para no inundarte: **Telegram máximo 1 cada 10 min, correo máximo 1 cada 60 min**, y solo si algo cambió. Los avisos de nueva, cita y eliminada siguen saliendo al instante.
