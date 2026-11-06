import { loadConfig } from './config';
import { validateConfig } from './lib/validate';
import { BotRunner } from './bot/runner';
import { BotWalker, defaultSteps } from './steps';

(async () => {
    const config = loadConfig();
    const { errors, warnings } = validateConfig(config);
    warnings.forEach((w) => console.warn(`[config] AVISO: ${w}`));
    if (errors.length) {
        errors.forEach((e) => console.error(`[config] ERROR: ${e}`));
        console.error('Corrige config.json y vuelve a ejecutar (nada se ha enviado al sitio).');
        process.exit(1);
    }
    const walker = new BotWalker(defaultSteps, config);
    const runner = new BotRunner(config, (page, actor, stepRef, runOptions) =>
        walker.run(page, actor, stepRef, runOptions)
    );
    await runner.run();
})();
