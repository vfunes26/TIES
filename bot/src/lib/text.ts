/**
 * Utilidades de texto: normalización para comparar nombres del sitio oficial
 * (que mezcla mayúsculas, tildes, apóstrofos raros y espacios dobles).
 */

/** Minúsculas, sin tildes, sin comillas/apóstrofos/backticks y con espacios colapsados. */
export function normalize(input: string): string {
    return (input ?? '')
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .toLowerCase()
        .replace(/[`´'’‘"“”]/g, '')
        .replace(/[^a-z0-9/.\-\s,]/g, ' ')
        .replace(/\s*,\s*/g, ', ')
        .replace(/\s+/g, ' ')
        .trim();
}

/** Distancia de Levenshtein (para sugerencias "¿quisiste decir...?"). */
export function levenshtein(a: string, b: string): number {
    const m = a.length, n = b.length;
    if (!m) return n;
    if (!n) return m;
    let prev = Array.from({ length: n + 1 }, (_, j) => j);
    for (let i = 1; i <= m; i++) {
        const cur = [i];
        for (let j = 1; j <= n; j++) {
            cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
        }
        prev = cur;
    }
    return prev[n];
}

/** Devuelve hasta `limit` candidatos más parecidos a `query`. */
export function suggest(query: string, candidates: string[], limit = 3): string[] {
    const q = normalize(query);
    return candidates
        .map((c) => {
            const nc = normalize(c);
            const score = nc.includes(q) || q.includes(nc) ? 0 : levenshtein(q, nc);
            return { c, score };
        })
        .sort((x, y) => x.score - y.score)
        .slice(0, limit)
        .map((x) => x.c);
}
