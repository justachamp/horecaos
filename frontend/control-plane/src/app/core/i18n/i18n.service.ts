import { Injectable, computed, inject, signal } from '@angular/core';

import { ApiError } from '../api/problem';
import { Money, formatAmount } from '../api/money';
import { APP_CONFIG } from '../config/app-config';
import { MessageKey, Messages, en } from './messages.en';
import { ru } from './messages.ru';
import { uzLatn } from './messages.uz-latn';

/**
 * Runtime locale switching for ru, uz-Latn and en (ADR 0035).
 *
 * Runtime rather than a build per locale, because a staff console is shared:
 * two account managers use the same installed application and one of them
 * reads Russian. Angular's `$localize` produces one bundle per locale and
 * cannot switch without a reload, so the catalogues are plain typed records
 * instead. Completeness is still checked at build time — see messages.en.ts.
 */
export const LOCALES = ['ru', 'uz-Latn', 'en'] as const;
export type Locale = (typeof LOCALES)[number];

const CATALOGUES: Readonly<Record<Locale, Messages>> = {
  ru,
  'uz-Latn': uzLatn,
  en,
};

/**
 * Russian by default. Most of this console's users read it, and defaulting to
 * the browser's language would put an English console in front of them because
 * a browser installed in Uzbekistan is usually an English build.
 */
const DEFAULT_LOCALE: Locale = 'ru';
const STORAGE_KEY = 'horecaos.control-plane.locale';

@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly config = inject(APP_CONFIG);

  private readonly active = signal<Locale>(readStoredLocale());
  readonly locale = this.active.asReadonly();

  private readonly messages = computed(() => CATALOGUES[this.active()]);

  constructor() {
    this.applyDocumentLanguage(this.active());
  }

  use(locale: Locale): void {
    this.active.set(locale);
    // A language preference is not personal data and survives a reload, so it
    // is one of the few things this console stores locally (ADR 0029).
    localStorage.setItem(STORAGE_KEY, locale);
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
    const key = `error.${error.code}` as MessageKey;
    return key in en ? this.t(key) : this.t('error.UNKNOWN');
  }

  /** `84 000 so'm`. The suffix is localised; the grouping never is. */
  money(money: Money): string {
    const amount = formatAmount(money);
    return money.currency === 'UZS' ? `${amount} ${this.t('money.uzsSuffix')}` : `${amount} ${money.currency}`;
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

function readStoredLocale(): Locale {
  const stored = localStorage.getItem(STORAGE_KEY);
  return LOCALES.includes(stored as Locale) ? (stored as Locale) : DEFAULT_LOCALE;
}
