import { InjectionToken } from '@angular/core';

/** The handset's battery, when the browser will say. ADR 0045 keeps it on the live row only, so a dispatcher knows a phone will die mid-delivery. */
export interface BatteryReading {
  readonly percent: number;
  readonly charging: boolean;
}

export type BatterySource = () => Promise<BatteryReading | null>;

interface BatteryManager {
  readonly level: number;
  readonly charging: boolean;
}

export const BATTERY = new InjectionToken<BatterySource>('BATTERY', {
  providedIn: 'root',
  factory: () => async () => {
    const getBattery = (globalThis.navigator as { getBattery?: () => Promise<BatteryManager> })
      ?.getBattery;
    if (typeof getBattery !== 'function') {
      return null;
    }
    try {
      const battery = await getBattery.call(globalThis.navigator);
      return { percent: Math.round(battery.level * 100), charging: battery.charging };
    } catch {
      return null;
    }
  },
});
