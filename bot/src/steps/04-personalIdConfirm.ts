import type { Step } from './types';
import { StepDataKey, StepId } from './types';
import { move, click, moveRandom } from './actions';

export const personalIdConfirmStep: Step = {
    id: StepId.PersonalIdConfirm,
    waitFor: '#btnEnviar',
    before: async (ctx) => {
        ctx.data[StepDataKey.PreviousPageText] = await ctx.page.locator('body').innerText();
    },
    actions: () => [
        moveRandom(),
        move('#btnEnviar'),
        click('#btnEnviar'),
    ],
};
