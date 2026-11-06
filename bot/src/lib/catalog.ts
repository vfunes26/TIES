/**
 * Catálogo de provincias, trámites y sedes (fuente única: /shared/catalog.json,
 * compartida con la app Android). Sirve para validar la configuración ANTES de
 * lanzar el bot y evitar errores de texto en provincia/trámite/sede.
 */
import * as fs from 'fs';
import * as path from 'path';
import { normalize } from './text';

export interface CatalogOffice { id: string; name: string }
export interface CatalogProcedure { id: string; name: string; offices: CatalogOffice[] }
export interface CatalogProvince { code: string; name: string; procedures: CatalogProcedure[] }
export interface Catalog { version: number; updatedAt: string; provinces: CatalogProvince[] }

let cached: Catalog | null | undefined;

export function loadCatalog(): Catalog | null {
    if (cached !== undefined) return cached;
    const candidates = [
        process.env.CATALOG_PATH,
        path.join(process.cwd(), '..', 'shared', 'catalog.json'),
        path.join(process.cwd(), 'shared', 'catalog.json'),
        path.join(__dirname, '..', '..', '..', 'shared', 'catalog.json'),
    ].filter((p): p is string => !!p);
    for (const file of candidates) {
        try {
            if (fs.existsSync(file)) {
                cached = JSON.parse(fs.readFileSync(file, 'utf-8')) as Catalog;
                return cached;
            }
        } catch { /* probar siguiente ruta */ }
    }
    cached = null;
    return cached;
}

/** Busca provincia por código ("08", "8") o por nombre (sin tildes/mayúsculas). */
export function findProvince(catalog: Catalog, location: string): CatalogProvince | undefined {
    const raw = (location ?? '').trim();
    if (!raw) return undefined;
    if (/^\d+$/.test(raw)) {
        const code = raw.padStart(2, '0');
        return catalog.provinces.find((p) => p.code === code);
    }
    const n = normalize(raw);
    // Estricto a propósito: una provincia ambigua o mal escrita debe fallar con sugerencia, no adivinarse.
    return catalog.provinces.find((p) => normalize(p.name) === n);
}

export interface Nationality { value: string; label: string }
let cachedNat: Nationality[] | null | undefined;

/** Lista oficial de nacionalidades (shared/nationalities.json) con los códigos del portal. */
export function loadNationalities(): Nationality[] | null {
    if (cachedNat !== undefined) return cachedNat;
    const candidates = [
        process.env.NATIONALITIES_PATH,
        path.join(process.cwd(), '..', 'shared', 'nationalities.json'),
        path.join(process.cwd(), 'shared', 'nationalities.json'),
        path.join(__dirname, '..', '..', '..', 'shared', 'nationalities.json'),
    ].filter((p): p is string => !!p);
    for (const file of candidates) {
        try {
            if (fs.existsSync(file)) {
                cachedNat = (JSON.parse(fs.readFileSync(file, 'utf-8')).options ?? []) as Nationality[];
                return cachedNat;
            }
        } catch { /* siguiente ruta */ }
    }
    cachedNat = null;
    return cachedNat;
}

/** Resuelve una nacionalidad escrita como código ("604") o nombre ("Perú"); null si no es exacta. */
export function findNationality(wanted: string): Nationality | null {
    const list = loadNationalities();
    if (!list) return null;
    const raw = (wanted ?? '').trim();
    const byValue = list.find((n) => n.value === raw);
    if (byValue) return byValue;
    const target = normalize(raw);
    const hits = list.filter((n) => normalize(n.label) === target);
    return hits.length === 1 ? hits[0] : null;
}
