# Respaldo, historial y estadísticas en TIES

Pensado para una APK personal: si **actualizas, desinstalas o cambias de teléfono**, recuperas tus solicitudes, citas, ajustes y proxies.

## Cómo se guarda (gratis, sin crear cuentas ni claves de Google)
- **Google Drive:** eliges un archivo (`TIES-respaldo.ties`) con el selector de archivos de Android. La app lo reescribe sola unos segundos después de cualquier cambio y cada ~15 min si cambió algo más (ajustes, proxies).
- **Telegram:** la app manda el mismo archivo a tu bot cuando se **confirma una cita** y como máximo **1 vez al día**.
- **Cifrado con tu frase** (AES-256). Sin la frase nadie puede leerlo, ni siquiera tú: **anótala**.

## Reglas de 30 días
| Qué | Cuánto vive | Qué pasa después |
|---|---|---|
| Solicitud **activa** (pendiente o fallida) | 30 días desde que se registró | Si no la procesas ni la cancelas, pasa sola al historial como **«Vencida»** |
| **Cancelada** a mano, **con cita**, **vencida** | 30 días en el historial | Se borra del historial |
| **Contadores por mes** | Para siempre | No se pierden aunque se borre el historial |

Contadores por mes: solicitudes **agregadas**, **con cita**, **canceladas**, **vencidas** y **fallidas**.

## Enviarte el historial
En la pantalla nueva: **Enviar historial por Telegram** (archivo .txt) o **por correo**. Tú decides cuándo; la app no lo manda sola.

## Recuperar después de reinstalar
1. Instala el APK y abre **Estadísticas → Respaldo, historial y estadísticas por mes**.
2. **Restaurar desde un archivo…** y elige el `.ties` (en Drive, o el que te llegó por Telegram).
3. Escribe tu frase y confirma. Vuelven solicitudes, historial, contadores, ajustes (Telegram, correo) y proxies.
4. La app vuelve a usar ese mismo archivo de Drive para los respaldos siguientes.
