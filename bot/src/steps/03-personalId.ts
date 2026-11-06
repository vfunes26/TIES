import type { Step } from './types';
import { StepId } from './types';

import {
    move,
    click,
    typeChars,
    moveRandom,
    scroll,
    selectBootstrapOption
} from './actions';

import { rand, sleep } from '../misc';

export const personalIdStep: Step = {
    id: StepId.PersonalId,

    waitFor: '#btnEnviar',

    before: async () => {
        await sleep(rand(1500, 2000));
    },

    actions: (ctx) => {
        const {
            nie,
            nombre,
            paisNac
        } = ctx.config.personalData;

        return [
            moveRandom(),

            click('#txtIdCitado'),
            typeChars(nie),

            click('#txtDesCitado'),
            typeChars(nombre),

            // Selección verificada mediante Bootstrap Select.
            selectBootstrapOption('#txtPaisNac', paisNac),

            scroll('#btnEnviar'),
            move('#btnEnviar'),
            click('#btnEnviar'),
        ];
    },
};