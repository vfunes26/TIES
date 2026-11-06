// bot/src/bot/actor-generator.ts
import type { ActorConfig } from '../config';

const random = (min: number, max: number) => Math.floor(Math.random() * (max - min + 1)) + min;

/**
 * Genera un perfil de comportamiento humano único para cada worker.
 * Esto evita que 5 solicitudes simultáneas tengan el mismo patrón de tecleo o movimiento.
 */
export function generateUniqueActor(workerId: number): ActorConfig {
  // Variación base para que no sean idénticos
  const baseDelay = random(80, 150);
  const variance = random(30, 70);
  
  return {
    id: `worker_${workerId}_human_${Date.now()}`,
    typing: {
      baseDelay: baseDelay,
      delayVariance: variance,
      mistakeChance: Math.random() * 0.04, // 0% a 4% de probabilidad de error y corrección
    },
    typingPressDelay: [random(50, 90), random(90, 140)],
    cursor: {
      overshootSpread: random(3, 8),
      overshootRadius: random(3, 8),
      waitBeforeMove: [random(40, 100), random(150, 300)],
      waitBeforeClick: [random(100, 200), random(250, 400)],
      moveRandomRange: [random(100, 150), random(200, 300)],
    },
    pipeline: {
      delayBetweenActions: [random(1000, 1500), random(2000, 3000)],
      delayBeforeFirstAction: [random(500, 1000), random(1500, 2500)],
    },
  };
}