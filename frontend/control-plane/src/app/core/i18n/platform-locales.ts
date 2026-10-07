import { Injectable, Injector, Signal, computed, inject, signal } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';

/**
 * The platform's language registry, read (ADR 0149).
 *
 * Which languages exist, and where each is live, is said once, in the backend's `PlatformLocales`.
 * This console used to keep its own `['uz', 'ru', 'en']` for the owner's invitation, its own
 * conversion between `uz` and `uz-Latn` in three places, and a switcher over a constant. They read
 * this instead.
 *
 * **What stays compile-time.** The console's own *catalogues* (`messages.*.ts`, typed so a missing
 * key fails the build) are still in the build, and {@link LOCALES} in `i18n.service.ts` is still the
 * set of languages this build has one for. A language is offered in the interface when the build
 * contains its catalogue **and** the registry says its staff-UI tier is live.
 */

/** Where a language can be live: tenant content, messages the platform sends, the staff consoles. */
export type LocaleTier = 'CONTENT' | 'MESSAGES' | 'STAFF_UI';

/** One entry of `GET /api/v1/control-plane/locales`. */
export interface PlatformLocaleEntry {
  /** The BCP 47 tag every store that follows ADR 0035 holds: `ru`, `uz-Latn`, `en`. */
  readonly tag: string;
  /** What the catalog stores for it: `uz` for `uz-Latn`, else the tag. */
  readonly catalogCode: string;
  /** Spellings the API reads and never stores: `uz` for `uz-Latn`. */
  readonly inputAliases: readonly string[];
  readonly script: 'CYRL' | 'LATN' | 'GEOR';
  readonly direction: 'LTR';
  readonly face: string;
  readonly fallbackRank: number;
  /** Where it is live; empty means declared, not live. */
  readonly tiers: readonly LocaleTier[];
  /** Its name in each live language, itself included. */
  readonly names: Readonly<Record<string, string>>;
}

export interface PlatformLocalesResponse {
  readonly locales: readonly PlatformLocaleEntry[];
  /** The tag every reader falls back to when nobody has chosen one: `ru`. */
  readonly fallback: string;
}

/** `GET /api/v1/control-plane/locales`: a read of code, answered to any signed-in principal. */
export const LOCALES_PATH = '/api/v1/control-plane/locales';

/** The registry's tags live in a tier, in the registry's own fallback order. */
export function activeTags(
  entries: readonly PlatformLocaleEntry[],
  tier: LocaleTier,
): readonly string[] {
  return [...entries]
    .filter((entry) => entry.tiers.includes(tier))
    .sort((a, b) => a.fallbackRank - b.fallbackRank)
    .map((entry) => entry.tag);
}

/** The registry's entry for a tag, ignoring case, or for a registered alias (`uz`). */
export function entryOf(
  entries: readonly PlatformLocaleEntry[],
  tagOrAlias: string,
): PlatformLocaleEntry | undefined {
  const wanted = tagOrAlias.trim().toLowerCase();
  return entries.find(
    (entry) =>
      entry.tag.toLowerCase() === wanted ||
      entry.inputAliases.some((alias) => alias.toLowerCase() === wanted),
  );
}

/**
 * Test-only: what every new {@link PlatformLocales} starts with, so the specs that render an
 * editor do not each have to answer `GET .../locales` first. `src/testing/i18n-preload.setup.ts`
 * sets it before every spec file; production never does, and the service then starts empty and reads
 * the registry. Held on a `globalThis` slot for the reason `i18n.service.ts`'s catalogue cache is:
 * the unit-test builder bundles this module once per entry point.
 */
const SEED_SLOT = Symbol.for('horecaos.control-plane.platform-locales.seed');

type SeededGlobal = typeof globalThis & { [SEED_SLOT]?: PlatformLocalesResponse | null };

export function seedPlatformLocalesForTesting(seed: PlatformLocalesResponse | null): void {
  (globalThis as SeededGlobal)[SEED_SLOT] = seed;
}

function seedOrNull(): PlatformLocalesResponse | null {
  return (globalThis as SeededGlobal)[SEED_SLOT] ?? null;
}

@Injectable({ providedIn: 'root' })
export class PlatformLocales {
  /**
   * Asked for only when the registry is read, never when this service is constructed: the
   * direction of the document is read from it by a service that has no business needing HTTP.
   */
  private readonly injector = inject(Injector);

  private readonly held = signal<readonly PlatformLocaleEntry[]>(seedOrNull()?.locales ?? []);
  private readonly fallbackTag = signal<string | null>(seedOrNull()?.fallback ?? null);

  /** Every declared language, live or not; empty until the registry has been read. */
  readonly entries: Signal<readonly PlatformLocaleEntry[]> = this.held.asReadonly();

  /** The tag readers fall back to; the registry's, or `ru` until it has been read. */
  readonly fallback: Signal<string> = computed(() => this.fallbackTag() ?? 'ru');

  private loadPromise: Promise<void> | null = null;

  /** Reads the registry once; every later call replays the same promise. Never rejects. */
  ensureLoaded(): Promise<void> {
    if (this.loadPromise === null) {
      this.loadPromise = this.load();
    }
    return this.loadPromise;
  }

  /** Whether the registry has said anything at all (a failed read is "not loaded"). */
  isLoaded(): boolean {
    return this.held().length > 0;
  }

  /** The tags live in a tier, in fallback order; empty until the registry has been read. */
  active(tier: LocaleTier): readonly string[] {
    return activeTags(this.held(), tier);
  }

  /** The tag stored for what a client or a record names (`uz` is `uz-Latn`), or the input as it was. */
  canonical(tagOrAlias: string): string {
    return entryOf(this.held(), tagOrAlias)?.tag ?? tagOrAlias;
  }

  /** A language's name as a reader of `displayIn` sees it, falling back to its own name, then its tag. */
  nameOf(tag: string, displayIn: string): string {
    const entry = entryOf(this.held(), tag);
    return entry?.names[displayIn] ?? entry?.names[entry.tag] ?? tag;
  }

  /** The text direction a language is written in; every registered language says `LTR`. */
  directionOf(tag: string): 'ltr' | 'rtl' {
    const entry = entryOf(this.held(), tag);
    return entry?.direction === 'LTR' || entry === undefined ? 'ltr' : 'rtl';
  }

  private async load(): Promise<void> {
    try {
      const api = this.injector.get(ApiClient);
      const answer = await firstValueFrom(api.get<PlatformLocalesResponse>(LOCALES_PATH));
      this.held.set(answer.locales);
      this.fallbackTag.set(answer.fallback);
    } catch (error) {
      // The registry narrows what the console offers; it is not what makes the console work.
      console.error(error);
    }
  }
}

/**
 * `canActivate: [platformLocalesGuard]` on the shell: the registry is read before anything beneath
 * it draws. Like the operations console's, it resolves `true` whatever happens.
 */
export const platformLocalesGuard: CanActivateFn = async () => {
  await inject(PlatformLocales).ensureLoaded();
  return true;
};
