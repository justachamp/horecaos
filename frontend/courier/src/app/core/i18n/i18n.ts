import { Injectable, computed, signal } from '@angular/core';

import {
  DEFAULT_LOCALE,
  LOCALES,
  localeFromBrowser,
  parseLocale,
  type LocaleCode,
} from './locales';
import type { MessageArea } from './message-areas';
import type { MessageKey } from './message-keys';

type Catalogue = Readonly<Record<string, string>>;

/** One loader per area and locale; see `catalogue-loaders.ts` for why they are registered, not imported. */
export type CatalogueLoaders = Readonly<
  Record<LocaleCode, Readonly<Partial<Record<MessageArea, () => Promise<Catalogue>>>>>
>;

let loaders: CatalogueLoaders | null = null;

/** Hands the loaders to `I18n`. Called once, before the application (or a spec) starts. */
export function registerCatalogueLoaders(registered: CatalogueLoaders): void {
  loaders = registered;
}

const STORAGE_KEY = 'courier.locale';

/** `{name}` placeholders, filled from `values`. A placeholder with no value is left visible so the gap is seen. */
export function interpolate(
  template: string,
  values?: Readonly<Record<string, string | number>>,
): string {
  if (values === undefined) {
    return template;
  }
  return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
    name in values ? String(values[name]) : whole,
  );
}

/**
 * The courier app's translations.
 *
 * Messages are split by feature area and fetched when a route needs them (`messagesGuard`); `core`
 * is always loaded. A locale switch loads the areas already in use for the new language *before*
 * it takes effect, so the screen never flashes keys.
 *
 * A key with no entry in the catalogue renders as the key itself and says so on the console:
 * visible and ugly on purpose, because a silent fallback to another language is a courier reading
 * Russian when they chose Uzbek and nobody finding out.
 */
@Injectable({ providedIn: 'root' })
export class I18n {
  private readonly active = signal<LocaleCode>(DEFAULT_LOCALE);
  private readonly messages = signal<Catalogue>({});
  private readonly requiredAreas = new Set<MessageArea>(['core']);
  private readonly cache = new Map<string, Catalogue>();
  private readonly reported = new Set<string>();

  readonly locale = this.active.asReadonly();
  readonly htmlLang = computed(
    () => LOCALES.find((locale) => locale.code === this.active())?.htmlLang ?? 'ru',
  );

  /** Picks the stored language, else the browser's, and loads `core` for it. Awaited before first paint. */
  async init(): Promise<void> {
    await this.use(this.initialLocale());
  }

  /** Looks a message up. The argument type is what turns a typo in a template into a build error. */
  t(key: MessageKey, values?: Readonly<Record<string, string | number>>): string {
    return this.lookup(key, values);
  }

  /** For a key built at runtime (an API code mapped to a message). Falls back to `fallback`, never to a key. */
  tOr(
    key: string,
    fallback: MessageKey,
    values?: Readonly<Record<string, string | number>>,
  ): string {
    const message = this.messages()[key];
    return message === undefined ? this.t(fallback, values) : interpolate(message, values);
  }

  has(key: string): boolean {
    return key in this.messages();
  }

  /** Makes sure the areas a screen shows are in memory. Never rejects: a failed chunk leaves raw keys and a log line. */
  async require(areas: readonly MessageArea[]): Promise<void> {
    const missing = areas.filter((area) => !this.requiredAreas.has(area));
    missing.forEach((area) => this.requiredAreas.add(area));
    if (missing.length === 0) {
      return;
    }
    await this.rebuild(this.active());
  }

  /** Switches language: loads every area in use for it, then swaps, then remembers the choice. */
  async use(locale: LocaleCode): Promise<void> {
    await this.rebuild(locale);
    this.active.set(locale);
    try {
      globalThis.localStorage.setItem(STORAGE_KEY, locale);
    } catch {
      // A browser that keeps no storage simply forgets the choice at the next start.
    }
  }

  private lookup(key: string, values?: Readonly<Record<string, string | number>>): string {
    const message = this.messages()[key];
    if (message === undefined) {
      if (!this.reported.has(key)) {
        this.reported.add(key);
        console.error(`Missing message "${key}" for ${this.active()}`);
      }
      return key;
    }
    return interpolate(message, values);
  }

  private initialLocale(): LocaleCode {
    try {
      const stored = parseLocale(globalThis.localStorage.getItem(STORAGE_KEY));
      if (stored !== null) {
        return stored;
      }
    } catch {
      // No storage: fall through to the browser's language.
    }
    return localeFromBrowser(globalThis.navigator?.languages ?? []);
  }

  private async rebuild(locale: LocaleCode): Promise<void> {
    const parts = await Promise.all([...this.requiredAreas].map((area) => this.load(locale, area)));
    this.messages.set(Object.assign({}, ...parts) as Catalogue);
  }

  private async load(locale: LocaleCode, area: MessageArea): Promise<Catalogue> {
    const cacheKey = `${locale}:${area}`;
    const cached = this.cache.get(cacheKey);
    if (cached !== undefined) {
      return cached;
    }
    const loader = loaders?.[locale][area];
    if (loader === undefined) {
      console.error(`No catalogue loader registered for ${cacheKey}`);
      return {};
    }
    try {
      const catalogue = await loader();
      this.cache.set(cacheKey, catalogue);
      return catalogue;
    } catch (failure) {
      console.error(`Could not load the ${cacheKey} messages`, failure);
      return {};
    }
  }
}
