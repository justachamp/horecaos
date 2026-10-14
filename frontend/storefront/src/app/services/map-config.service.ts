import { Injectable, inject, signal } from '@angular/core';
import type { YaConfig } from 'angular8-yandex-maps';
import { ReplaySubject } from 'rxjs';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';

/**
 * What this environment's map is, as `GET .../map-config` states it (ADR 0145 decisions 2 and 4).
 * The server key is not representable in it.
 */
export interface MapConfig {
  /** `YANDEX`, `FAKE`, or `NONE` when the platform has no provider set up. */
  readonly provider: string;
  readonly configured: boolean;
  /** The public, referrer-restricted key for the vendor's map script. `null` means no tiles. */
  readonly browserKey: string | null;
  /** Any of `TILES`, `SUGGEST`, `GEOCODE`, `REVERSE`. */
  readonly features: readonly string[];
  /** The provider's own attribution, to be shown on the map unmodified. */
  readonly attribution: string | null;
}

/** What the screen assumes when the platform could not be asked: no map, no lookups, text only. */
export const NO_MAP: MapConfig = {
  provider: 'NONE',
  configured: false,
  browserKey: null,
  features: [],
  attribution: null,
};

/** Whether a map can be drawn at all: the platform has a provider, tiles on offer, and a key for them. */
export function hasTiles(config: MapConfig): boolean {
  return config.configured && config.features.includes('TILES') && !!config.browserKey;
}

/** Whether the platform can search, resolve or name an address for this customer. */
export function canLookUp(config: MapConfig): boolean {
  return (
    config.configured &&
    ['SUGGEST', 'GEOCODE', 'REVERSE'].some((feature) => config.features.includes(feature))
  );
}

/**
 * The configuration `angular8-yandex-maps` loads its script with, pushed here once the platform has
 * said what the key is.
 *
 * The library takes its key from `provideYaConfig`, at bootstrap, and the key used to be a literal in
 * `environment.ts`, committed to git and shared by every deployment (ADR 0145 decision 4). It is now
 * delivered by `GET .../map-config`, so it can be rotated without a build; the library accepts an
 * observable for exactly this, and a screen that draws a map renders `<ya-map>` only after
 * {@link MapConfigService.ensureLoaded} has resolved, which is after this has its first value. Until
 * then the library's own default (no key) is never used, because nothing asks it to load.
 */
export const YA_MAPS_CONFIG = new ReplaySubject<YaConfig>(1);

/**
 * Reads the platform's map configuration once, for the signed-in customer (ADR 0145, row `X.4`).
 *
 * **A failure to ask is not a failure of the page.** The address screen has a defined degraded mode
 * (decision 6: the field becomes plain text and the address saves `NOT_GEOCODED`), so a refused,
 * unreachable or malformed answer is {@link NO_MAP}, quietly, and the next screen to ask tries again.
 */
@Injectable({ providedIn: 'root' })
export class MapConfigService {
  private readonly api = inject(ApiClient);
  private readonly app = inject(APP_CONFIG);

  private pending: Promise<MapConfig> | null = null;

  /** The last answer, for a template; `null` until the platform has been asked. */
  readonly current = signal<MapConfig | null>(null);

  ensureLoaded(): Promise<MapConfig> {
    this.pending ??= this.read();
    return this.pending;
  }

  private async read(): Promise<MapConfig> {
    try {
      const config = await this.api.get<MapConfig>(
        `/storefront/tenants/${this.app.tenantId}/brands/${this.app.brandId}/map-config`,
        { quiet: true },
      );
      const known: MapConfig = {
        provider: config.provider ?? 'NONE',
        configured: config.configured === true,
        browserKey: config.browserKey ?? null,
        features: Array.isArray(config.features) ? config.features : [],
        attribution: config.attribution ?? null,
      };
      if (hasTiles(known)) {
        YA_MAPS_CONFIG.next({ apikey: known.browserKey ?? undefined, lang: 'ru_RU' });
      }
      this.current.set(known);
      return known;
    } catch {
      // Forgotten, so the next screen to open a map tries again once the platform is back.
      this.pending = null;
      this.current.set(NO_MAP);
      return NO_MAP;
    }
  }
}
