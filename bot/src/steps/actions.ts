import type { Action } from '../bot/pipeline';

function withLabel<T extends Action>(action: T, label: string): T {
    (action as Action & { label?: string }).label = label;
    return action;
}

/** Attach a label to a custom action for logging */
export function label(action: Action, name: string): Action {
    return withLabel(action, name);
}

export const move = (selector: string): Action =>
    withLabel(({ cursor }) => cursor.move(selector), `move(${selector})`);

export const click = (selector: string): Action =>
    withLabel(({ cursor }) => cursor.click(selector), `click(${selector})`);

export const scroll = (selector: string): Action =>
    withLabel(({ cursor }) => cursor.scroll(selector), `scroll(${selector})`);

export const select = (
    selector: string,
    opts: { label: string } | { value: string }
): Action =>
    withLabel(
        ({ page }) => page.selectOption(selector, opts),
        `select(${selector}, ${'label' in opts ? opts.label : opts.value})`
    );


/**
 * Selecciona una opción por su valor HTML o por su etiqueta visible.
 * Compatible con select nativo y Bootstrap Select.
 */
export const selectBootstrapOption = (
    selector: string,
    requested: string
): Action =>
    withLabel(async ({ page }) => {

        const result = await page.evaluate(
            ({ selector, requested }) => {

                const element = document.querySelector(selector);

                if (!(element instanceof HTMLSelectElement)) {
                    return {
                        ok: false,
                        error: 'SELECT_NOT_FOUND',
                        value: '',
                        label: ''
                    };
                }

                if (element.disabled) {
                    return {
                        ok: false,
                        error: 'SELECT_DISABLED',
                        value: '',
                        label: ''
                    };
                }

                const normalize = (text: string): string =>
                    text.normalize('NFD')
                        .replace(/[\u0300-\u036f]/g, '')
                        .replace(/\s+/g, ' ')
                        .trim()
                        .toLocaleUpperCase();

                const option = Array.from(element.options).find(item => {
                    if (item.disabled) return false;

                    if (
                        item.parentElement instanceof HTMLOptGroupElement &&
                        item.parentElement.disabled
                    ) return false;

                    return (
                        item.value === requested ||
                        normalize(item.label) === normalize(requested) ||
                        normalize(item.textContent ?? '') === normalize(requested)
                    );
                });

                if (!option) {
                    return {
                        ok: false,
                        error: 'OPTION_NOT_FOUND',
                        value: '',
                        label: ''
                    };
                }

                element.value = option.value;

                element.dispatchEvent(
                    new Event('input', {
                        bubbles: true,
                        composed: true
                    })
                );

                element.dispatchEvent(
                    new Event('change', {
                        bubbles: true
                    })
                );

                return {
                    ok: element.value === option.value,
                    error: '',
                    value: option.value,
                    label: option.label || option.text
                };
            },
            { selector, requested }
        );

        if (!result.ok) {
            throw new Error(
                `Country selection failed: ${result.error}. ` +
                `Requested: ${requested}`
            );
        }

        await page.waitForFunction(
            ({ selector, expected }) => {
                const element = document.querySelector(
                    selector
                ) as HTMLSelectElement | null;

                return element !== null && element.value === expected;
            },
            {
                selector,
                expected: result.value
            },
            {
                timeout: 10000
            }
        );

        console.log(
            `[SELECT] ${result.label} selected and verified ` +
            `(${result.value})`
        );

    }, `selectBootstrapOption(${selector}, ${requested})`);

export const typeChars = (text: string): Action =>
    withLabel(
        ({ keyboard }) => keyboard.type(text),
        `type(${text.length} chars)`
    );

export const press = (key: string): Action =>
    withLabel(
        ({ keyboard }) => keyboard.press(key),
        `press("${key}")`
    );

export const moveRandom = (): Action =>
    withLabel(
        ({ cursor }) => cursor.moveRandom(),
        'moveRandom()'
    );
