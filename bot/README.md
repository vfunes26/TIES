# Bot de alertas de cita previa (Node + TypeScript)

Bot que recorre el formulario de cita previa de extranjería y te avisa por Telegram. Forma parte de **CitaAsistente** (ver el `README.md` de la raíz); la app Android interpreta sus mensajes.

## Puesta en marcha

```bash
cd bot
cp config.example.json config.json   # rellena tus datos (config.json NO se sube a Git)
npm ci
npm run build
npm start
```

Requiere Node ≥ 20. Al arrancar valida `config.json` contra `../shared/catalog.json` y se detiene, sin tocar la web, si hay errores.

## Campos principales de `config.json`

| Campo | Descripción |
|---|---|
| `location` | Provincia: código (`"08"`) o nombre (`"Barcelona"`), como en `shared/catalog.json`. |
| `tramiteValue` | Id del trámite (p. ej. `4010`). Debe existir en esa provincia. |
| `offices` | Sedes por **orden de prioridad** (texto o parte del texto oficial; sin importar tildes/mayúsculas). |
| `minCitaDate` | `YYYY-MM-DD`: citas anteriores se ignoran. |
| `personalData` | `nie`, `nombre`, `telefono`, `email` y `paisNac`: **nacionalidad exacta del portal**, por código (PERU = `242`) o por nombre exacto (`PERU`). Se valida contra `../shared/nationalities.json`; nombres ambiguos como `GUINEA` se rechazan. |
| `notifications.telegram` | `botToken` y `chatId`. **Nunca los subas a Git.** |

## Mensajes de Telegram (los lee la app)

La primera línea lleva una etiqueta fija: `CITA_DISPONIBLE | provincia | trámite`, `SIN_CITAS | …` o `PAGINA_FINAL | …`.

## Tests

```bash
npm run test:unit          # 30 tests de precisión, sin navegador
npm run test:integration   # flujo contra el servidor simulado (requiere navegador)
```

Más detalles de arquitectura y opciones avanzadas: ver `src/` y `examples/`.
