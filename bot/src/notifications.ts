import type { AppConfig } from './config';
import { buildHeader, CitaTag } from './lib/messages';

type NotifyPayload = { message: string; title: string; data?: Record<string, unknown> }

async function callHomeAssistant(cfg: AppConfig, payload: NotifyPayload) {
    const ha = cfg.notifications?.homeassistant
    if (!ha?.url || !ha?.token) return
    const url = ha.url.replace(/\/$/, '') + '/api/services/notify/notify'
    try {
        const res = await fetch(url, {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${ha.token}`,
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(payload),
        })
        if (!res.ok) console.error('HomeAssistant notification failed:', res.status, await res.text())
    } catch (e) {
        console.error('HomeAssistant notification failed:', e)
    }
}

async function callTelegram(cfg: AppConfig, message: string): Promise<void> {
    const telegram = cfg.notifications?.telegram;
    const token = telegram?.botToken;
    const chatId = telegram?.chatId;
    if (!token || !chatId) {
        console.warn('Telegram notification skipped: set notifications.telegram.botToken and chatId in config.json.');
        return;
    }

    try {
        const characters = Array.from(message);
        for (let offset = 0; offset < characters.length; offset += 4000) {
            const text = characters.slice(offset, offset + 4000).join('');
            const res = await fetch(`https://api.telegram.org/bot${token}/sendMessage`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ chat_id: chatId, text, disable_web_page_preview: true }),
            });
            const result = await res.json() as { ok?: boolean; description?: string };
            if (!res.ok || !result.ok) {
                console.error('Telegram notification failed:', res.status, result.description ?? 'Unknown Telegram API error');
                return;
            }
        }
    } catch (e) {
        console.error('Telegram notification failed:', e);
    }
}

export async function notifyFinalPageText(cfg: AppConfig, pageText: string, tag: CitaTag = 'PAGINA_FINAL'): Promise<void> {
    const body = pageText.trim();
    const message = body ? `${buildHeader(cfg, tag)}\n\n${body}` : '';
    if (!message) {
        console.warn('Telegram page notification skipped: the final page contains no visible text.');
        return;
    }
    console.log('TELEGRAM FINAL-PAGE TEXT:', message);
    await callTelegram(cfg, message);
}

export async function notifyAppointmentUnavailable(cfg: AppConfig): Promise<void> {
    const message = `${buildHeader(cfg, 'SIN_CITAS')}\n\nNo hay citas disponibles ahora mismo (reintentando).`;
    console.log('TELEGRAM NOTIFICATION:', message);
    await callTelegram(cfg, message);
}

export async function notifyFailure(cfg: AppConfig, consecutiveFailures: number): Promise<void> {
    const msg = `Cita bot: ${consecutiveFailures} consecutive failures. Proxy/anti-bot may not be working.`
    console.log('NOTIFICATION:', msg)
    await callHomeAssistant(cfg, { message: msg, title: 'Cita Bot - Failure Alert' })
}

export async function notifyFailureResolved(cfg: AppConfig): Promise<void> {
    const msg = 'Cita bot: Consecutive failure issue resolved. Running normally again.'
    console.log('NOTIFICATION:', msg)
    await callHomeAssistant(cfg, { message: msg, title: 'Cita Bot - Resolved' })
}

export async function notifyCitaFound(cfg: AppConfig, citaDetails?: string): Promise<void> {
    const msg = citaDetails
        ? `Cita bot: Calendar options are available:\n${citaDetails}\nPlease complete the booking manually.`
        : 'Cita bot: Calendar options are available. Please check the browser and complete the booking manually.'
    console.log('NOTIFICATION:', msg)
    await callHomeAssistant(cfg, { message: msg, title: 'Cita Bot - Cita Found!' })
}

export async function notifyRecoveryNeeded(cfg: AppConfig): Promise<void> {
    const msg = 'Cita bot: Error at cita selection. Browser kept open for manual recovery.'
    console.log('NOTIFICATION:', msg)
    await callHomeAssistant(cfg, { message: msg, title: 'Cita Bot - Recovery Needed' })
}

/** Critical notification data for Companion app: Android (ttl/priority) and iOS (interruption-level). */
const CRITICAL_DATA = {
    ttl: 0,
    priority: 'high' as const,
    push: { 'interruption-level': 'critical' as const },
}

export async function notifyCritical(cfg: AppConfig): Promise<void> {
    const msg = 'Cita bot: URGENT - Cita found but no response. Complete now!'
    console.log('CRITICAL NOTIFICATION:', msg)
    await callHomeAssistant(cfg, {
        message: msg,
        title: 'Cita Bot - URGENT',
        data: CRITICAL_DATA,
    })
}
