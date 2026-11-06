/**
 * Formato de mensajes estructurados para Telegram. La primera línea lleva una
 * etiqueta fija que la app Android (CitaAsistente) interpreta de forma fiable:
 *   CITA_DISPONIBLE | provincia | trámite
 *   SIN_CITAS | provincia | trámite
 *   PAGINA_FINAL | provincia | trámite   (estado no reconocido: revisar)
 */
import type { AppConfig } from '../config';
import { loadCatalog, findProvince } from './catalog';

export type CitaTag = 'CITA_DISPONIBLE' | 'SIN_CITAS' | 'PAGINA_FINAL';

export function buildHeader(cfg: AppConfig, tag: CitaTag): string {
    const catalog = loadCatalog();
    const province = catalog ? findProvince(catalog, cfg.location) : undefined;
    const proc = province?.procedures.find((x) => x.id === (cfg.tramiteValue ?? '4010'));
    return `${tag} | ${province?.name ?? cfg.location} | ${proc?.name ?? cfg.tramiteLabel}`;
}
