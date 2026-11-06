// bot/src/bot/orchestrator.ts
import type { AppConfig, ProxyConfig } from '../config';
import { generateUniqueActor } from './actor-generator';
// Importa aquí tu función principal que ejecuta una solicitud (ej. runSingleRequest o tu BotRunner)
// import { runBotForRequest } from '../index'; 

export interface QueueRequest {
  id: string;
  nie: string;
  nombre: string;
  telefono: string;
  email: string;
  paisNac: string;
  provincia: string;
  tramite: string;
  sede: string;
  minCitaDate: string;
  intentosWorker: number;
  estado: 'pendiente' | 'en_proceso' | 'exitosa' | 'fallida' | 'descartada';
}

export class CitaOrchestrator {
  private queue: QueueRequest[];
  private proxies: ProxyConfig[];
  private baseConfig: AppConfig;
  private readonly MAX_CONCURRENT_WORKERS = 5;
  private readonly MAX_REQUESTS_PER_WORKER = 3;
  private readonly COOLDOWN_MS = 140000; // 140 segundos
  private citaFound = false;

  constructor(queue: QueueRequest[], proxies: ProxyConfig[], baseConfig: AppConfig) {
    this.queue = queue;
    this.proxies = proxies;
    this.baseConfig = baseConfig;
  }

  /**
   * Inicia el procesamiento de la cola seleccionada.
   */
  public async startProcessing(): Promise<void> {
    console.log(`[Orchestrator] Iniciando. Solicitudes en cola: ${this.queue.length}`);

    while (this.queue.some(r => r.estado === 'pendiente') && !this.citaFound) {
      // Obtener hasta MAX_CONCURRENT_WORKERS solicitudes pendientes
      const availableRequests = this.queue.filter(r => r.estado === 'pendiente').slice(0, this.MAX_CONCURRENT_WORKERS);
      
      if (availableRequests.length === 0) break;

      console.log(`[Orchestrator] Lanzando lote de ${availableRequests.length} workers...`);

      // Lanzar workers con staggered start (retraso escalonado de 1.5s entre cada uno)
      const workerPromises = availableRequests.map((request, index) => {
        const delay = index * 1500; // 0ms, 1500ms, 3000ms, etc.
        return this.delay(delay).then(() => this.spawnWorker(request));
      });

      await Promise.all(workerPromises);

      // Si no se encontró cita, esperar antes del siguiente lote (opcional, o dejar que los cooldowns individuales lo manejen)
      if (!this.citaFound && this.queue.some(r => r.estado === 'pendiente')) {
        console.log('[Orchestrator] Pausa global antes del siguiente lote...');
        await this.delay(30000); // 30s de pausa global entre lotes
      }
    }

    console.log('[Orchestrator] Proceso finalizado.');
  }

  private async spawnWorker(request: QueueRequest): Promise<void> {
    if (this.citaFound) return;

    // Asignar un proxy (rotativo simple)
    const proxy = this.proxies.length > 0 
      ? this.proxies[Math.floor(Math.random() * this.proxies.length)] 
      : undefined;

    // Generar actor único para este worker
    const uniqueActor = generateUniqueActor(Math.floor(Math.random() * 10000));

    // Construir config específica para este worker
    const workerConfig: AppConfig = {
      ...this.baseConfig,
      proxy: proxy,
      actors: [uniqueActor],
      location: request.provincia,
      tramiteLabel: request.tramite,
      offices: [request.sede],
      minCitaDate: request.minCitaDate,
      personalData: {
        nie: request.nie,
        nombre: request.nombre,
        paisNac: request.paisNac,
        telefono: request.telefono,
        email: request.email,
      },
      browserProfile: `worker_${request.id}`,
    };

    try {
      console.log(`[Worker] Procesando solicitud: ${request.nie} (Intento ${request.intentosWorker + 1})`);
      request.estado = 'en_proceso';
      request.intentosWorker += 1;

      // AQUÍ LLAMAS A TU LÓGICA EXISTENTE DEL BOT CON ESTA CONFIG
      // const result = await runBotForRequest(workerConfig);
      
      // Simulación de resultado (REEMPLAZAR CON TU LLAMADA REAL)
      const result = await this.simulateBotRun(workerConfig, request.id);

      if (result === 'CITA_ENCONTRADA') {
        console.log(`[Worker] ¡CITA ENCONTRADA para ${request.nie}! Deteniendo todos los workers.`);
        request.estado = 'exitosa';
        this.citaFound = true;
        // Aquí disparas tu notificación de Telegram de ÉXITO
      } else if (result === 'BLOQUEO_PROXY' || result === 'ERROR_GRAVE') {
        console.log(`[Worker] Fallo grave en ${request.nie}. Descartando temporalmente.`);
        request.estado = 'descartada';
        // Notificar que este proxy podría estar quemado
      } else {
        console.log(`[Worker] Sin cita para ${request.nie}. Volviendo a la cola.`);
        request.estado = 'pendiente';
      }

    } catch (error) {
      console.error(`[Worker] Error inesperado en ${request.nie}:`, error);
      request.estado = 'pendiente'; // Reintentar en el futuro
    } finally {
      // Cooldown obligatorio de 140s + jitter antes de que este worker tome otra tarea
      if (!this.citaFound && request.intentosWorker < this.MAX_REQUESTS_PER_WORKER) {
        const jitter = Math.floor(Math.random() * 30000); // 0 a 30s extra
        console.log(`[Worker] Cooldown de ${(this.COOLDOWN_MS + jitter) / 1000}s antes de la siguiente...`);
        await this.delay(this.COOLDOWN_MS + jitter);
      } else if (request.intentosWorker >= this.MAX_REQUESTS_PER_WORKER) {
        console.log(`[Worker] Límite de ${this.MAX_REQUESTS_PER_WORKER} intentos alcanzado para este ciclo.`);
      }
    }
  }

  private delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  // Función placeholder: Reemplazar con tu llamada real al bot
  private async simulateBotRun(config: AppConfig, requestId: string): Promise<string> {
    await this.delay(5000); // Simula tiempo de procesamiento
    return 'SIN_CITA'; // O 'CITA_ENCONTRADA', 'BLOQUEO_PROXY'
  }
}