import { Injectable, Signal, computed, signal } from '@angular/core';

import { MessageCatalogue, MessageKey, messagesEn } from './messages.en';
import { messagesRu } from './messages.ru';
import { messagesUzLatn } from './messages.uz-latn';

/**
 * The three locales this platform supports (ADR 0035).
 *
 * `uz-Latn` carries its script subtag because uz-Latn and uz-Cyrl are different
 * locales and a bare `uz` is ambiguous.
 */
export const LOCALES = ['ru', 'uz-Latn', 'en'] as const;
export type Locale = (typeof LOCALES)[number];

/**
 * Russian is the default, not English.
 *
 * The staff using this console work in Russian and Uzbek. Defaulting to English
 * and letting them switch means every operator's first screen is in a language
 * they did not ask for, and on a shared terminal they will switch it again
 * tomorrow.
 */
export const DEFAULT_LOCALE: Locale = 'ru';

const CATALOGUES: Record<Locale, MessageCatalogue> = {
  ru: messagesRu,
  'uz-Latn': messagesUzLatn,
  en: messagesEn,
};

const STORAGE_KEY = 'qoida.operations.locale';

/**
 * Runtime locale switching over compile-time-complete catalogues.
 *
 * Angular's built-in `$localize` was the obvious choice and is not the right
 * one here. It compiles one bundle per locale, so switching locale means
 * navigating to a different deployment — and ADR 0035 requires the locale to be
 * runtime-switchable, because a shared terminal changes hands between operators
 * who read different languages, mid-shift, without a page load they will wait
 * for.
 *
 * What `$localize` gives up in exchange is the build-time guarantee, and this
 * module gets it back from the type system instead: every catalogue is typed
 * `Record<MessageKey, string>` against the English one, so a missing translation
 * is a `tsc` error and cannot reach a screen. See `messages.en.ts`.
 */
@Injectable({ providedIn: 'root' })
export class I18n {
  private readonly current = signal<Locale>(readStoredLocale());

  readonly locale: Signal<Locale> = this.current.asReadonly();

  /**
   * The active catalogue as a signal, so a component that reads a message
   * through {@link t} re-renders on a locale change without subscribing to
   * anything.
   */
  private readonly catalogue = computed(() => CATALOGUES[this.current()]);

  setLocale(locale: Locale): void {
    this.current.set(locale);
    document.documentElement.lang = locale;
    try {
      globalThis.localStorage?.setItem(STORAGE_KEY, locale);
    } catch {
      // A kiosk profile with storage disabled loses the preference between
      // sessions. That is a worse experience, not a broken application.
    }
  }

  /**
   * Looks up a message, interpolating `{placeholder}` values.
   *
   * There is no "missing key" branch and no fallback string, because
   * {@link MessageKey} makes a missing key impossible to write. If this ever
   * returns undefined at runtime, the type system has been circumvented — with
   * a cast, or a catalogue built from untyped data — and that is the bug.
   */
  t(key: MessageKey, values?: Readonly<Record<string, string | number>>): string {
    return interpolate(this.catalogue()[key], values);
  }
}

/**
 * Replaces `{name}` with `values.name`.
 *
 * An unmatched placeholder is left as-is rather than blanked. `опаздывают: {count}`
 * on screen is an obvious bug that gets reported; `опаздывают: ` is a plausible
 * sentence that does not.
 */
export function interpolate(
  template: string,
  values?: Readonly<Record<string, string | number>>,
): string {
  if (!values) {
    return template;
  }
  return template.replace(/\{(\w+)\}/g, (whole, name: string) =>
    Object.hasOwn(values, name) ? String(values[name]) : whole,
  );
}

/** Narrowing guard, for values arriving from storage or a query string. */
export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (LOCALES as readonly string[]).includes(value);
}

function readStoredLocale(): Locale {
  try {
    const stored = globalThis.localStorage?.getItem(STORAGE_KEY);
    if (isLocale(stored)) {
      return stored;
    }
  } catch {
    // Fall through to the default.
  }
  return DEFAULT_LOCALE;
}
