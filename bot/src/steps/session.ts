import type { Page } from 'patchright';

const EXPIRED_SESSION_PATTERN =
    /su\s+sesi[oó]n\s+ha\s+caducado\s+por\s+permanecer\s+demasiado\s+tiempo\s+inactiva/i;

export async function acceptExpiredSessionIfPresent(page: Page): Promise<boolean> {
    const pageText = await page.locator('body').innerText();
    if (!EXPIRED_SESSION_PATTERN.test(pageText)) return false;

    const acceptButton = page.locator(
        '#btnAceptar, button:has-text("Aceptar"), input[type="submit"][value="Aceptar"], input[type="button"][value="Aceptar"]'
    ).first();
    if (await acceptButton.count() === 0 || !await acceptButton.isVisible()) {
        throw new Error('Expired session page detected, but its Aceptar button was not found.');
    }
    await acceptButton.click();

    console.log('[SESSION] Expired-session page detected; clicked Aceptar.');
    return true;
}
