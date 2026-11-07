# Perfiles en TIES: master y Usuario UI/UX

Inicio de sesión **sin registro**. El **nombre de usuario** decide el perfil; la contraseña se crea la primera vez y se guarda en el teléfono (solo su huella cifrada, nunca en claro).

| Usuario (se escribe así) | Perfil | Qué puede |
|---|---|---|
| `master` | **Master** | Todo: Telegram, correo, procesamiento, proxy, respaldo (frase y Drive), solicitudes, alertas, historial |
| `uiux` (también vale `UI/UX`, `ui ux`) | **Usuario UI/UX** | Crear solicitudes, verlas y activarlas, personalizar sus alertas, estadísticas por mes, historial, cargar el respaldo del master y respaldar. **No ve** Telegram, correo, procesamiento ni proxy |

Los nombres están en una sola línea de `data/Roles.kt` (`MASTER_NAME` y `USER_NAME`) por si quieres cambiarlos.

## Primera vez
1. Abre la app → pide usuario.
2. Escribe `master` o `uiux`. Si es la primera vez con ese nombre, aparece «Confirmar contraseña»: crea la tuya (mínimo 6 caracteres).
3. Las siguientes veces solo escribes la contraseña. La sesión se mantiene hasta **Cerrar sesión** (Estadísticas).
4. 5 errores seguidos bloquean 60 segundos.

## Cómo trabaja el Usuario UI/UX
1. El master le entrega el archivo `.ties` y la frase del respaldo.
2. El usuario entra como `uiux` → **Resumen → Respaldo, historial y estadísticas por mes → Restaurar desde un archivo**. Se cargan las solicitudes, la configuración (Telegram, correo), los proxies y el uso de proxy.
3. El usuario agrega solicitudes y las procesa a mano; todo corre con la configuración y el proxy ya cargados.
4. El master recibe por Telegram los avisos con la etiqueta «👤 Usuario UI/UX» y los respaldos cifrados que el usuario genera.

## Importante
- El bot que **recibe** solicitudes por Telegram solo corre en el teléfono del master.
- Las contraseñas y la sesión **no** van dentro del respaldo.
- Es un control de acceso de la app personal: protege contra uso casual, no contra alguien con acceso total al teléfono.
- Si el master olvida su contraseña: desinstalar, reinstalar y restaurar el respaldo (con su frase).
