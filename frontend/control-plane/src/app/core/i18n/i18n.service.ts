import { Injectable, inject, signal } from '@angular/core';

import { ApiError } from '../api/problem';
import { Money, formatAmount, formatMinorUnits, hasDisplayScale } from '../api/money';
import { APP_CONFIG } from '../config/app-config';
import type { MessageKey, Messages } from './messages.en';
import { ru } from './messages.ru';

/**
 * Runtime locale switching for ru, uz-Latn and en (ADR 0035).
 *
 * Runtime rather than a build per locale, because a staff console is shared:
 * two account managers use the same installed application and one of them
 * reads Russian. Angular's `$localize` produces one bundle per locale and
 * cannot switch without a reload, so the catalogues are plain typed records
 * instead. Completeness is still checked at build time — see messages.en.ts.
 *
 * **Loading model (ADR 0035's dated loading-model addition, the operations
 * console's, applied here).** Russian is the default and ships in the initial
 * bundle; `en` and `uz-Latn` are separate chunks fetched the first time somebody
 * asks for that locale. All three catalogues together were 386 kB of the 643 kB
 * this console's initial bundle had grown to. {@link I18nService.t} stays
 * synchronous — see the class doc for what that costs.
 */
export const LOCALES = ['ru', 'uz-Latn', 'en'] as const;
export type Locale = (typeof LOCALES)[number];

/**
 * Russian by default. Most of this console's users read it, and defaulting to
 * the browser's language would put an English console in front of them because
 * a browser installed in Uzbekistan is usually an English build.
 */
export const DEFAULT_LOCALE: Locale = 'ru';
export const STORAGE_KEY = 'horecaos.control-plane.locale';

type LazyLocale = Exclude<Locale, 'ru'>;

const LOADERS: Record<LazyLocale, () => Promise<Messages>> = {
  en: () => import('./messages.en').then((m) => m.en),
  'uz-Latn': () => import('./messages.uz-latn').then((m) => m.uzLatn),
};

/**
 * Test-only: replaces one lazy locale's loader, e.g. to simulate a failed
 * dynamic import. Returns the function that restores the real one; the loader
 * table is shared with every spec bundled into the same entry point.
 */
export function setLoaderForTesting(
  locale: LazyLocale,
  loader: () => Promise<Messages>,
): () => void {
  const original = LOADERS[locale];
  LOADERS[locale] = loader;
  return () => {
    LOADERS[locale] = original;
  };
}

interface CatalogueCache {
  readonly loaded: Map<Locale, Messages>;
  readonly pending: Map<Locale, Promise<Messages>>;
}

const CACHE_SLOT = Symbol.for('horecaos.control-plane.i18n.catalogue-cache');

/**
 * The load cache, shared by every {@link I18nService} and by
 * {@link preloadLocale}'s callers.
 *
 * It lives in a `globalThis` slot rather than a module-level variable because
 * the unit-test builder bundles every spec file as its own entry point, so this
 * module is instantiated once per spec and a plain module variable would be a
 * different, empty cache in the global setup file (`src/testing/i18n-preload.setup.ts`)
 * and in each spec. In the one production bundle the module exists once and the
 * slot is just an indirection.
 */
function catalogueCache(): CatalogueCache {
  const holder = globalThis as typeof globalThis & { [CACHE_SLOT]?: CatalogueCache };
  return (holder[CACHE_SLOT] ??= {
    loaded: new Map<Locale, Messages>([[DEFAULT_LOCALE, ru]]),
    pending: new Map<Locale, Promise<Messages>>(),
  });
}

function ensureLoaded(locale: Locale): Promise<Messages> {
  const cache = catalogueCache();
  const cached = cache.loaded.get(locale);
  if (cached) {
    return Promise.resolve(cached);
  }
  let inFlight = cache.pending.get(locale);
  if (!inFlight) {
    inFlight = LOADERS[locale as LazyLocale]().then(
      (catalogue) => {
        cache.loaded.set(locale, catalogue);
        cache.pending.delete(locale);
        return catalogue;
      },
      (failure: unknown) => {
        // A rejected promise must not stay cached: one flaky fetch would
        // otherwise disable that locale until the tab is reloaded.
        cache.pending.delete(locale);
        throw failure;
      },
    );
    cache.pending.set(locale, inFlight);
  }
  return inFlight;
}

/**
 * Fetches a locale's catalogue without switching to it. `main.ts` awaits this
 * for the stored locale before bootstrapping, so a returning `en` or `uz-Latn`
 * reader's first paint is already in their language, and the unit-test setup
 * warms every locale so specs can switch and assert in the same tick.
 */
export function preloadLocale(locale: Locale): Promise<void> {
  return ensureLoaded(locale).then(() => undefined);
}

/** Test-only: forgets every loaded catalogue, `ru` included, so a spec can exercise a cold start. */
export function resetCatalogueCacheForTesting(): void {
  delete (globalThis as typeof globalThis & { [CACHE_SLOT]?: CatalogueCache })[CACHE_SLOT];
}

export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (LOCALES as readonly string[]).includes(value);
}

/**
 * The locale this browser last chose, readable before Angular has an injector
 * (`main.ts`) and without throwing when storage is unavailable.
 */
export function peekStoredLocale(): Locale {
  try {
    const stored = globalThis.localStorage?.getItem(STORAGE_KEY);
    if (isLocale(stored)) {
      return stored;
    }
  } catch {
    // Storage disabled: the language preference is lost, the console still opens.
  }
  return DEFAULT_LOCALE;
}

/**
 * `t()` is synchronous and always has a catalogue to read, because a locale is
 * only ever switched to once its catalogue is in memory:
 *
 *  - {@link use} is a request. If the catalogue is cached the switch is
 *    immediate, exactly as when all three were bundled. If not, the import
 *    starts and `locale()` and every rendered string keep showing the previous
 *    locale until it resolves; then both change together, so the selected
 *    locale and the rendered text never disagree.
 *  - A later request supersedes an in-flight one.
 *  - A failed import leaves the previous locale in place and is reported to the
 *    console; asking again retries.
 *  - A cold start on a stored locale nobody has warmed opens in Russian and
 *    switches when the catalogue arrives. `main.ts` awaits that catalogue before
 *    bootstrapping, so in the shipped application this is not observable.
 */
@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly config = inject(APP_CONFIG);

  private readonly active = signal<Locale>(DEFAULT_LOCALE);
  readonly locale = this.active.asReadonly();

  private readonly messages = signal<Messages>(ru);

  /** The last locale asked for, so a superseded load knows not to apply. */
  private requested: Locale = DEFAULT_LOCALE;

  constructor() {
    const stored = peekStoredLocale();
    this.requested = stored;
    const cached = catalogueCache().loaded.get(stored);
    if (cached) {
      this.apply(stored, cached);
    } else {
      this.apply(DEFAULT_LOCALE, ru);
      void this.applyOnceLoaded(stored);
    }
  }

  use(locale: Locale): void {
    this.requested = locale;
    // A language preference is not personal data and survives a reload, so it
    // is one of the few things this console stores locally (ADR 0029). It is
    // written before the catalogue arrives, so a reload mid-load still asks for
    // this locale.
    try {
      localStorage.setItem(STORAGE_KEY, locale);
    } catch {
      // Storage disabled: the preference is lost on reload, nothing else.
    }
    const cached = catalogueCache().loaded.get(locale);
    if (cached) {
      this.apply(locale, cached);
      return;
    }
    void this.applyOnceLoaded(locale);
  }

  /** Whether `key` names a catalogued message; every catalogue has the same keys (the type sees to it). */
  hasMessage(key: string): key is MessageKey {
    return Object.hasOwn(ru, key);
  }

  private async applyOnceLoaded(locale: Locale): Promise<void> {
    try {
      const catalogue = await ensureLoaded(locale);
      if (this.requested === locale) {
        this.apply(locale, catalogue);
      }
    } catch (failure) {
      // Nothing awaits this promise, so an unreported rejection would vanish.
      // The previous locale stays on screen.
      console.error(failure);
    }
  }

  private apply(locale: Locale, catalogue: Messages): void {
    this.active.set(locale);
    this.messages.set(catalogue);
    this.applyDocumentLanguage(locale);
  }

  /**
   * Looks up a message.
   *
   * Reads the locale signal, so calling it inside a template registers that
   * template as a consumer and re-renders on a language change. In a dense
   * table, hoist the labels into a `computed()` rather than calling this per
   * cell: one call per header beats one per row.
   */
  t(key: MessageKey, parameters?: Readonly<Record<string, string | number>>): string {
    const message = this.messages()[key];
    if (parameters === undefined) {
      return message;
    }
    return message.replace(/\{(\w+)\}/g, (whole, name: string) => {
      const value = parameters[name];
      return value === undefined ? whole : String(value);
    });
  }

  /**
   * The user-facing text for a failed call.
   *
   * Keyed on the server's stable error code, never on its `detail`, which is
   * written for a developer reading a response and may name internals. An
   * unknown code — which ADR 0031 permits within a major version — falls back
   * to a generic sentence rather than showing the raw code.
   */
  describe(error: ApiError): string {
    const key = `error.${error.code}`;
    return this.hasMessage(key) ? this.t(key) : this.t('error.UNKNOWN');
  }

  /** `84 000 so'm`. The suffix is localised; the grouping never is. */
  money(money: Money): string {
    const amount = formatAmount(money);
    return money.currency === 'UZS'
      ? `${amount} ${this.t('money.uzsSuffix')}`
      : `${amount} ${money.currency}`;
  }

  /**
   * The same, except that a currency with no declared display scale is
   * rendered in stored minor units and said to be, rather than throwing.
   *
   * <p>For an amount that arrives from the server in whatever currency a
   * tenant holds — the approvals queue's subject, above all. `money()` throws
   * on an undeclared currency, and a throw during change detection truncates
   * the table at that row: one tenant billed in a currency this console has no
   * scale for stopped every approver deciding every platform request under it.
   * A reader can decide on an unscaled figure that says it is unscaled; a
   * reader cannot decide on a row that never drew.
   */
  moneyOrRaw(money: Money): string {
    if (hasDisplayScale(money.currency)) {
      return this.money(money);
    }
    return this.t('money.unscaled', { amount: formatMinorUnits(money), currency: money.currency });
  }

  /** `21.08.2026`, in the console's timezone rather than the browser's. */
  day(instant: Date): string {
    const parts = this.dateParts(instant);
    return `${parts['day']}.${parts['month']}.${parts['year']}`;
  }

  /** `21.08 13:12`. Dense on purpose: a table row has no space for a year. */
  dateTime(instant: Date): string {
    const parts = this.dateParts(instant);
    return `${parts['day']}.${parts['month']} ${parts['hour']}:${parts['minute']}`;
  }

  private dateParts(instant: Date): Record<string, string> {
    // Formatted through Intl only to resolve the timezone offset correctly,
    // including its history. The arrangement of the parts is ours, because
    // DD.MM and a 24-hour clock is what all three locales read here and en-US
    // would render 8/21/2026 1:12 PM.
    const formatter = new Intl.DateTimeFormat('en-GB', {
      timeZone: this.config.displayTimeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    });

    const parts: Record<string, string> = {};
    for (const part of formatter.formatToParts(instant)) {
      parts[part.type] = part.value;
    }
    return parts;
  }

  private applyDocumentLanguage(locale: Locale): void {
    // Screen readers pick a voice from this, and `uz-Latn` tells one not to
    // read Latin Uzbek with a Cyrillic pronunciation.
    document.documentElement.lang = locale;
  }
}
