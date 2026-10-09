import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { geoPaths } from '../../../core/api/geo-paths';
import { CurrentBrand } from '../../../core/auth/current-brand';

/** What a provider can do here. `TILES` is a map; the rest are address search. */
export type GeoFeature = 'TILES' | 'SUGGEST' | 'GEOCODE' | 'REVERSE';

/**
 * What the platform says about this environment's map (ADR 0145 decision 4, `GET .../map-config`;
 * mirrors `OperationsGeocodeController.MapConfigResponse`).
 *
 * `configured: false` is the platform telling a screen that nothing answers: no key exists, and
 * every lookup will say so. A screen that shows an empty map or an empty suggestion list in that
 * state tells an operator the address does not exist, which is the opposite of the truth.
 */
export interface MapConfig {
  /** `YANDEX`, `FAKE` or `NONE`. Never branched on: only the lazy provider reads it. */
  readonly provider: string;
  readonly configured: boolean;
  /** Public and referrer-restricted by construction; delivered here so it can be rotated without a build. */
  readonly browserKey: string | null;
  readonly features: readonly GeoFeature[];
  readonly attribution: string | null;
}

/** The configuration as one attempt left it: nothing known yet, or the answer, or the failure. */
export type MapConfigState =
  | { readonly status: 'idle' }
  | { readonly status: 'loading' }
  | { readonly status: 'ready'; readonly config: MapConfig }
  | { readonly status: 'failed' };

/**
 * Reads the map configuration once per brand and shares it.
 *
 * A failed read is not cached: a person who reloads a screen after the platform came back should
 * get a map, and a configuration read that failed once and was remembered would blank every map
 * until the page was closed.
 */
@Injectable({ providedIn: 'root' })
export class MapConfigService {
  private readonly api = inject(ApiClient);
  private readonly brand = inject(CurrentBrand);

  private readonly stateSignal = signal<MapConfigState>({ status: 'idle' });
  private inFlight: Promise<MapConfig | null> | null = null;

  readonly state = this.stateSignal.asReadonly();

  readonly config = computed(() => {
    const current = this.stateSignal();
    return current.status === 'ready' ? current.config : null;
  });

  /** Whether the provider offers a feature. False for everything until the configuration is known. */
  has(feature: GeoFeature): boolean {
    return this.config()?.features.includes(feature) ?? false;
  }

  /** The configuration, or `null` when it could not be read. Concurrent callers share one request. */
  ensureLoaded(): Promise<MapConfig | null> {
    const current = this.stateSignal();
    if (current.status === 'ready') {
      return Promise.resolve(current.config);
    }
    this.inFlight ??= this.load().finally(() => (this.inFlight = null));
    return this.inFlight;
  }

  private async load(): Promise<MapConfig | null> {
    this.stateSignal.set({ status: 'loading' });
    try {
      await this.brand.ensureLoaded();
      const scope = this.brand.scope();
      if (scope === null) {
        this.stateSignal.set({ status: 'failed' });
        return null;
      }
      const result = await firstValueFrom(this.api.get<MapConfig>(geoPaths.mapConfig(scope)));
      this.stateSignal.set({ status: 'ready', config: result.value });
      return result.value;
    } catch {
      this.stateSignal.set({ status: 'failed' });
      return null;
    }
  }
}
