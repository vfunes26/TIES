/**
 * Coincidencia EXACTA de opciones <select> (valor, o texto normalizado idéntico).
 * Sin coincidencias parciales a propósito: "GUINEA" no debe elegir "GUINEA ECUATORIAL".
 * Misma semántica que selectBootstrapOption (steps/actions.ts).
 */
import { normalize } from './text';

export function matchSelectOption(
    options: { text: string; value: string }[],
    wanted: string
): string | null {
    const raw = (wanted ?? '').trim();
    if (!raw) return null;
    const real = options.filter((o) => o.value);
    const byValue = real.find((o) => o.value === raw);
    if (byValue) return byValue.value;
    const n = normalize(raw);
    const exact = real.filter((o) => normalize(o.text) === n);
    return exact.length === 1 ? exact[0].value : null;
}
