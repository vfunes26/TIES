import type { Step } from './types';
import type { Action } from '../bot/pipeline';
import { StepId } from './types';
import { move, click, scroll, select, label } from './actions';
import { sleep, rand } from '../misc';

function selectOfficeIfPresent(optionLabel: string): Action {
    return label(async ({ page }) => {
        const selects = page.locator('select');
        for (let index = 0; index < await selects.count(); index++) {
            const field = selects.nth(index);
            const options = await field.locator('option').allTextContents();
            if (options.some((text) => text.trim() === optionLabel)) {
                await field.selectOption({ label: optionLabel });
                return;
            }
        }
        console.log(`[${StepId.Tramite}] Office option not present; continuing with the recorded procedure selection`);
    }, `select office if present (${optionLabel})`);
}

export const tramiteStep: Step = {
    id: StepId.Tramite,
    waitFor: '#btnAceptar', // arrive from region selection
    before: async (ctx) => {
        await sleep(rand(1500, 2000));
    },
    actions: (ctx) => [
            selectOfficeIfPresent(ctx.config.officeLabel ?? 'Cualquier oficina'),
            select('#tramiteGrupo\\[0\\]', { value: ctx.config.tramiteValue ?? '4010' }),
            scroll('#btnAceptar'),
            move('#btnAceptar'),
            click('#btnAceptar'),
        ],
};
