import { Injectable, Injector, computed, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';

/**
 * The platform's language registry and this brand's own set, read (ADR 0149, Decision 5).
 *
 * Which languages exist, and where each is live, is said once, by the platform
 * (`GET /api/v1/storefront/locales`); which of them *this* restaurant serves is said by
 * `GET .../brands/{brandId}/locales`. This app used to say both itself: a language picker with three
 * hard-coded entries, a label table and an initial language of `uz`. It reads them instead, and keeps
 * only what a client must keep: **the catalogues in its own build** (`src/i18n/*.json`, see
 * {@link BUILD_CATALOGUES}), because a screen cannot be drawn in a language nobody wrote the words for.
 *
 * A language is offered when the registry has it live in the content tier, this build has its
 * catalogue and the brand serves it. A language that is declared and not live is never offered,
 * whatever the build holds.
 */

export type LocaleTier = 'CONTENT' | 'MESSAGES' | 'STAFF_UI';

/** One entry of `GET /api/v1/storefront/locales`. */
export interface PlatformLocaleEntry {
  /** The BCP 47 tag every platform store holds: `ru`, `uz-Latn`, `en`. */
  readonly tag: string;
  /** What the catalog, `?locale=` and this app's catalogue files are named by: `uz` for `uz-Latn`. */
  readonly catalogCode: string;
  /** Spellings the API reads and never stores: `uz` for `uz-Latn`. */
  readonly inputAliases: readonly string[];
  readonly script: string;
  readonly direction: string;
  readonly fallbackRank: number;
  /** Where it is live; empty means declared, not live. */
  readonly tiers: readonly LocaleTier[];
  /** Its name in each language, itself included. */
  readonly names: Readonly<Record<string, string>>;
}

export interface PlatformLocalesResponse {
  readonly locales: readonly PlatformLocaleEntry[];
  readonly fallback: string;
}

/** `GET .../brands/{brandId}/locales`: the languages one brand serves, its default first. */
export interface BrandLocales {
  readonly locales: readonly string[];
  readonly defaultLocale: string;
  /** Whether the brand chose its own set, as opposed to sitting on the platform's content languages. */
  readonly configured: boolean;
}

/**
 * Held on a `globalThis` slot, not in a module variable: the unit-test builder bundles each spec as
 * its own entry point, so a module-level variable would be one copy per spec and the setup file's
 * registry would never reach the code under test. Only a `Symbol.for` key is shared. In production
 * there is one bundle and the slot costs nothing.
 */
const HELD = Symbol.for('horecaos.storefront.platform-locales');

type HeldGlobal = typeof globalThis & { [HELD]?: readonly PlatformLocaleEntry[] };

/** The registry as last read; empty until it has been. Pure helpers (`platformTag`) consult it without DI. */
export function heldPlatformLocales(): readonly PlatformLocaleEntry[] {
  return (globalThis as HeldGlobal)[HELD] ?? [];
}

/** Records the registry for the pure helpers. The service calls it on a successful read; a spec setup file calls it with a fixture. */
export function adoptPlatformLocales(entries: readonly PlatformLocaleEntry[]): void {
  (globalThis as HeldGlobal)[HELD] = entries;
}

@Injectable({ providedIn: 'root' })
export class PlatformLocales {
  /**
   * Asked for only when the registry is read, never when this service is constructed: the language
   * service is injected by half the app, and a service that only needs a label has no business needing HTTP.
   */
  private readonly injector = inject(Injector);

  readonly entries = signal<readonly PlatformLocaleEntry[]>(heldPlatformLocales());

  /** This brand's own set; null until it has been read, or when it could not be. */
  readonly brand = signal<BrandLocales | null>(null);

  readonly isLoaded = computed(() => this.entries().length > 0);

  private loadPromise: Promise<void> | null = null;

  /** Reads both documents once; every later call replays the same promise. Never rejects: a failed read leaves the build's own catalogues on offer. */
  ensureLoaded(): Promise<void> {
    if (this.loadPromise === null) {
      this.loadPromise = this.load();
    }
    return this.loadPromise;
  }

  /** The entry a tag, a registered alias or a catalogue id denotes, ignoring case. */
  entryOf(tagOrAliasOrCatalogCode: string): PlatformLocaleEntry | undefined {
    const wanted = tagOrAliasOrCatalogCode.trim().toLowerCase();
    return this.entries().find(
      (entry) =>
        entry.tag.toLowerCase() === wanted ||
        entry.catalogCode.toLowerCase() === wanted ||
        entry.inputAliases.some((alias) => alias.toLowerCase() === wanted),
    );
  }

  private async load(): Promise<void> {
    let api: ApiClient;
    let config: AppConfig;
    try {
      api = this.injector.get(ApiClient);
      config = this.injector.get(APP_CONFIG);
    } catch (error) {
      // No platform to ask (a screen built without its configuration): the build's catalogues stand.
      console.error(error);
      return;
    }
    try {
      const registry = await api.get<PlatformLocalesResponse>('/storefront/locales', {
        anonymous: true,
        silent: true,
      });
      adoptPlatformLocales(registry.locales);
      this.entries.set(registry.locales);
    } catch (error) {
      // The registry narrows what is offered; it is not what makes the storefront work.
      console.error(error);
      return;
    }
    try {
      this.brand.set(
        await api.get<BrandLocales>(
          `/storefront/tenants/${config.tenantId}/brands/${config.brandId}/locales`,
          { anonymous: true, silent: true },
        ),
      );
    } catch (error) {
      // Without the brand's set every live language this build has a catalogue for is offered.
      console.error(error);
    }
  }
}
