/**
 * Selección de sede (oficina) - exportado para tests.
 *
 * Mejora respecto a la versión original: la comparación ignora tildes,
 * mayúsculas, apóstrofos/backticks y espacios dobles, y a igualdad de prioridad
 * prefiere la coincidencia exacta frente a la parcial. El orden de `officeNames`
 * sigue siendo la prioridad (la primera sede configurada que exista gana).
 */
import { normalize } from './text';

export function matchOffice(
    options: { text: string; value: string }[],
    officeNames: string[]
): string | null {
    const normalizedOptions = options
        .filter((o) => o.value)
        .map((o) => ({ value: o.value, text: normalize(o.text) }));

    for (const officeName of officeNames) {
        const wanted = normalize(officeName);
        if (!wanted) continue;
        const exact = normalizedOptions.find((o) => o.text === wanted);
        if (exact) return exact.value;
        const partial = normalizedOptions.find((o) => o.text.includes(wanted));
        if (partial) return partial.value;
    }
    return null;
}
