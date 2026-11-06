/**
 * Validación de config.json antes de arrancar. Detecta los errores más
 * habituales (provincia mal escrita, trámite que no existe en esa provincia,
 * sede inexistente, fecha/teléfono/correo mal formados) y propone correcciones.
 */
import type { AppConfig } from '../config';
import { loadCatalog, findProvince, loadNationalities, findNationality } from './catalog';
import { normalize, suggest } from './text';

export interface ValidationResult { errors: string[]; warnings: string[] }

const PLACEHOLDERS = /^(tu_|your_|nombre apellidos|tu@|test@)/i;

export function validateConfig(cfg: AppConfig): ValidationResult {
    const errors: string[] = [];
    const warnings: string[] = [];

    // --- Datos personales ---
    const p = cfg.personalData;
    if (!p) {
        errors.push('Falta el bloque "personalData".');
    } else {
        if (!p.nie?.trim()) errors.push('personalData.nie está vacío.');
        if (!p.nombre?.trim() || PLACEHOLDERS.test(p.nombre)) errors.push('personalData.nombre está vacío o es de ejemplo.');
        if (!p.paisNac?.trim()) {
            errors.push('personalData.paisNac está vacío (nacionalidad: código o nombre EXACTO del portal).');
        } else if (loadNationalities() && !findNationality(p.paisNac)) {
            const names = (loadNationalities() ?? []).map((n) => n.label);
            errors.push(`Nacionalidad "${p.paisNac}" no coincide exactamente con la lista del portal. ¿Quisiste decir: ${suggest(p.paisNac, names).join(', ')}?`);
        }
        if (!/^\+?[\d\s]{9,15}$/.test(p.telefono ?? '')) errors.push(`personalData.telefono no parece válido: "${p.telefono ?? ''}".`);
        if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(p.email ?? '') || PLACEHOLDERS.test(p.email ?? '')) errors.push(`personalData.email no es válido o es de ejemplo: "${p.email ?? ''}".`);
    }

    // --- Fecha mínima ---
    if (!/^\d{4}-\d{2}-\d{2}$/.test(cfg.minCitaDate ?? '') || Number.isNaN(Date.parse(cfg.minCitaDate))) {
        errors.push(`minCitaDate debe ser YYYY-MM-DD y válida (actual: "${cfg.minCitaDate ?? ''}").`);
    }

    // --- Telegram ---
    const tg = cfg.notifications?.telegram;
    if (!tg?.botToken || !tg?.chatId || /^(TU_|your_)/i.test(tg.botToken)) {
        warnings.push('Telegram sin configurar: no recibirás avisos (notifications.telegram.botToken/chatId).');
    }

    // --- Catálogo: provincia / trámite / sedes ---
    const catalog = loadCatalog();
    if (!catalog) {
        warnings.push('No se encontró shared/catalog.json: se omite la validación de provincia/trámite/sedes.');
        return { errors, warnings };
    }
    const province = findProvince(catalog, cfg.location);
    if (!province) {
        const names = catalog.provinces.map((x) => x.name);
        errors.push(`Provincia "${cfg.location}" no encontrada. ¿Quisiste decir: ${suggest(cfg.location, names).join(', ')}?`);
        return { errors, warnings };
    }
    const procId = cfg.tramiteValue ?? '4010';
    const proc = province.procedures.find((x) => x.id === procId);
    if (!proc) {
        const sample = province.procedures.map((x) => `${x.id} = ${x.name}`).slice(0, 5).join(' | ');
        errors.push(`El trámite ${procId} no existe en ${province.name}. Algunos válidos: ${sample} ...`);
        return { errors, warnings };
    }
    if (cfg.tramiteLabel && normalize(cfg.tramiteLabel) !== normalize(proc.name)) {
        warnings.push(`tramiteLabel no coincide con el nombre oficial de ${procId}: "${proc.name}".`);
    }
    for (const wanted of cfg.offices ?? []) {
        const w = normalize(wanted);
        const found = proc.offices.some((o) => normalize(o.name).includes(w) || w.includes(normalize(o.name)));
        if (!found) {
            warnings.push(`Sede no reconocida para ${province.name}/${procId}: "${wanted}". Parecidas: ${suggest(wanted, proc.offices.map((o) => o.name)).join(' | ')}`);
        }
    }
    if (!cfg.offices?.length) errors.push('offices está vacío: indica al menos una sede en orden de prioridad.');
    return { errors, warnings };
}
