/**
 * Resolución de la opción de provincia del desplegable #form del sitio.
 * Acepta código ("08"), nombre ("Barcelona") o ambos formatos mezclados,
 * en lugar de exigir el texto literal como hacía la versión original.
 */
import { normalize } from './text';
import { loadCatalog, findProvince } from './catalog';

export function matchProvinceOption(
    options: { text: string; value: string }[],
    location: string
): string | null {
    const real = options.filter((o) => o.value);
    const raw = (location ?? '').trim();
    if (!raw) return null;

    const catalog = loadCatalog();
    const province = catalog ? findProvince(catalog, raw) : undefined;
    const wantedNames = [raw, province?.name].filter((x): x is string => !!x).map(normalize);

    for (const name of wantedNames) {
        const exact = real.find((o) => normalize(o.text) === name);
        if (exact) return exact.value;
    }
    if (province) {
        const codeNum = String(parseInt(province.code, 10));
        const byValue = real.find((o) => new RegExp(`[?&]p=0*${codeNum}(?:&|$)`).test(o.value));
        if (byValue) return byValue.value;
    }
    for (const name of wantedNames) {
        const partial = real.find((o) => normalize(o.text).includes(name));
        if (partial) return partial.value;
    }
    return null;
}
