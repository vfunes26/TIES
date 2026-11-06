import {Step, StepDataKey} from './types';
import type { Action } from '../bot/pipeline';
import { StepId } from './types';
import {move, click, label, select} from './actions';
import { RetryError } from '../bot/errors';
import {rand, sleep} from "../misc";
import { matchProvinceOption } from '../lib/province';

/** Selecciona la provincia aceptando código ("08") o nombre ("Barcelona"). */
function selectProvince(location: string): Action {
    return label(async ({ page }) => {
        const opts = await page.$$eval('#form option', (os) =>
            os.map((o) => ({ text: (o.textContent ?? '').trim(), value: (o as HTMLOptionElement).value }))
        );
        const value = matchProvinceOption(opts, location);
        if (!value) {
            throw new Error(`Provincia "${location}" no encontrada en el desplegable. Opciones: ${opts.map((o) => o.text).join(', ')}`);
        }
        await page.selectOption('#form', { value });
    }, `select province (${location})`);
}

export const selectRegionStep: Step = {
    id: StepId.RegionSelect,
    waitFor: undefined, // before handles navigation + wait
    before: async (ctx) => {
        const base = ctx.config.baseUrl ?? 'https://icp.administracionelectronica.gob.es';
        const path = ctx.config.regionSelectPath ?? '/icpco/citar';
        const url = `${base}${path}`;
        console.log(`[${StepId.RegionSelect}] Navigating to ${url}`);
        const gotoRes = await ctx.page.goto(url);
        if (gotoRes?.status() === 403) throw new RetryError('Proxy forbidden (403)', 5000, true, true);
        await ctx.page.waitForSelector('#btnAceptar', { timeout: 90000 });
        const visible = await ctx.page.locator('#cookie_action_close_header').first()
            .isVisible({ timeout: 2000 }).catch(() => false);
        ctx.data[StepDataKey.CookieBannerVisible] = visible;
        console.log(`[${StepId.Tramite}] Cookie banner ${visible ? 'visible, will dismiss' : 'not visible'}`);
        await sleep(rand(1500, 2000));
    },
    actions: (ctx) => {
        const pipeline = [
            selectProvince(ctx.config.location),
            move('#btnAceptar'),
            click('#btnAceptar'),
        ]
        if (ctx.data[StepDataKey.CookieBannerVisible]) {
            const cookieClick = label(
                (({ page }) => page.locator('#cookie_action_close_header').first().click({ timeout: 3000 })) as Action,
                'click(#cookie_action_close_header)'
            );
            pipeline.unshift(cookieClick);
        }

        return pipeline;
    },
};
