import type { Step } from './types';
import { StepDataKey, StepId } from './types';
import { ExpiredSessionError, ManualInterventionRequired, RestartFromBeginning } from '../bot/errors';
import { SELECTOR_TIMEOUT_MS } from './wait';
import { notifyAppointmentUnavailable, notifyFinalPageText } from '../notifications';
import { acceptExpiredSessionIfPresent } from './session';

const APPOINTMENT_UNAVAILABLE_PATTERN =
    /S[IÍ]\s+TIENEN\s+A\s+SU\s+DISPOSICI[ÓO]N\s+MEDIANTE\s+EL\s+USO\s+DE\s+CL@VE,\s*CITAS\s+DISPONIBLES\s+PARA\s+SU\s+RESERVA/i;

export const citaSelectStep: Step = {
    id: StepId.CitaSelect,
    waitFor: undefined,
    before: async (ctx) => {
        console.log(`[${StepId.CitaSelect}] Waiting for the final page text or calendar options...`);
        const previousPageText = ctx.data[StepDataKey.PreviousPageText];
        if (typeof previousPageText !== 'string') {
            throw new Error('Previous page text was not captured before final submission.');
        }
        const state = await Promise.race([
            ctx.page.waitForSelector('[id^="cita_"]', { timeout: SELECTOR_TIMEOUT_MS }).then(() => 'calendar'),
            ctx.page.waitForFunction(
                (previousText) => {
                    const currentText = document.body?.innerText.trim() ?? '';
                    return currentText.length > 0 && currentText !== previousText;
                },
                previousPageText,
                { timeout: SELECTOR_TIMEOUT_MS }
            ).then(() => 'message'),
        ]);
        const pageText = await ctx.page.locator('body').innerText();
        if (await acceptExpiredSessionIfPresent(ctx.page)) throw new ExpiredSessionError();
        if (APPOINTMENT_UNAVAILABLE_PATTERN.test(pageText.replace(/\s+/g, ' '))) {
            console.log(`[${StepId.CitaSelect}] Appointment-unavailable phrase detected on final page`);
            await notifyAppointmentUnavailable(ctx.config);
            throw new RestartFromBeginning('Appointment is not available');
        }

        console.log(`[${StepId.CitaSelect}] Final page reached via ${state}; sending its text and pausing for manual action`);
        await notifyFinalPageText(ctx.config, pageText, state === 'calendar' ? 'CITA_DISPONIBLE' : 'PAGINA_FINAL');
        throw new ManualInterventionRequired();
    },
    actions: () => [],
};
