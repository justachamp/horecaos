import { InjectionToken } from '@angular/core';

/**
 * The Screen Wake Lock, behind a seam.
 *
 * It is the only thing that keeps a PWA's page -- and so its location -- alive while a courier
 * rides (ADR 0179 D7). It is released by the browser whenever the page is hidden, which is why
 * the duty store asks again when the page comes back.
 */
export interface WakeLockSource {
  /**
   * Takes the lock. Resolves to a release function, or null when the browser has none or refuses
   * (a low battery, a power saver). `onReleased` fires when the browser lets go on its own.
   */
  request(onReleased: () => void): Promise<(() => Promise<void>) | null>;
}

export const WAKE_LOCK = new InjectionToken<WakeLockSource>('WAKE_LOCK', {
  providedIn: 'root',
  factory: browserWakeLock,
});

function browserWakeLock(): WakeLockSource {
  return {
    async request(onReleased) {
      const wakeLock = (globalThis.navigator as Navigator | undefined)?.wakeLock;
      if (wakeLock === undefined) {
        return null;
      }
      try {
        const sentinel = await wakeLock.request('screen');
        sentinel.addEventListener('release', onReleased, { once: true });
        return () => sentinel.release();
      } catch {
        return null;
      }
    },
  };
}
