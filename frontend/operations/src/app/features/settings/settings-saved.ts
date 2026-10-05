import { Injectable, inject } from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { localMessages } from '../../core/i18n/local-messages';
import { Toasts } from '../../shared/ui/toast';

/** What happened to the setting. */
export type SavedKind = 'set' | 'reverted' | 'published';

/**
 * Where it was written: the level, and the name of the brand or branch when the level names one.
 * `name` is absent for TENANT and when the scope bar has not resolved the name yet.
 */
export interface SavedTarget {
  readonly level: 'TENANT' | 'BRAND' | 'LOCATION';
  readonly name: string | null;
}

/**
 * The words of a settings confirmation. Local to the settings screens, not in the shared
 * catalogues -- see `LocalMessages` for why the initial bundle cannot take more Russian keys.
 *
 * The target phrase carries its own preposition (or postposition, in Uzbek), because the three
 * languages put the level in different places in the sentence.
 */
const messages = localMessages({
  ru: {
    set: '{setting} — задано {target}',
    reverted: '{setting} — возвращено наследование {target}',
    published: '{setting} — опубликована новая версия {target}',
    on: 'включено',
    off: 'выключено',
    'target.TENANT': 'для всей компании',
    'target.BRAND': 'для бренда «{name}»',
    'target.LOCATION': 'для филиала «{name}»',
    'target.BRAND.unnamed': 'для бренда',
    'target.LOCATION.unnamed': 'для филиала',
  },
  'uz-Latn': {
    set: '{setting} — {target} belgilandi',
    reverted: '{setting} — {target} meros qiymatiga qaytarildi',
    published: '{setting} — {target} yangi versiya joriy etildi',
    on: 'yoqilgan',
    off: 'oʻchirilgan',
    'target.TENANT': 'butun kompaniya uchun',
    'target.BRAND': '«{name}» brendi uchun',
    'target.LOCATION': '«{name}» filiali uchun',
    'target.BRAND.unnamed': 'brend uchun',
    'target.LOCATION.unnamed': 'filial uchun',
  },
  en: {
    set: '{setting} — set {target}',
    reverted: '{setting} — back to the inherited value {target}',
    published: '{setting} — new version published {target}',
    on: 'on',
    off: 'off',
    'target.TENANT': 'for the whole company',
    'target.BRAND': 'for brand “{name}”',
    'target.LOCATION': 'for branch “{name}”',
    'target.BRAND.unnamed': 'for the brand',
    'target.LOCATION.unnamed': 'for the branch',
  },
});

/**
 * Where a saved setting is announced (settings.md §1.3: «A toast confirms with the level named --
 * *Порог опоздания: 15 мин -- задано для филиала Чиланзар*»).
 *
 * A form that saves closes, and a confirmation drawn inside it goes with it; the toast host in
 * the shell outlives both the form and the navigation that often follows (ADR 0101, row
 * `X.17`). The level is named because the commonest settings mistake is changing a thing for
 * thirty branches while believing it was one.
 *
 * Only success is announced here. A failed save stays an inline alert beside the field that
 * failed (settings.md §1.4: «not a toast -- the user must not lose which field failed»).
 *
 * No value that is personal data goes in: the setting's label, an optional formatted value and
 * the name of a brand or branch -- never a customer's or a staff member's (ADR 0029).
 */
@Injectable({ providedIn: 'root' })
export class SettingsSaved {
  private readonly toasts = inject(Toasts);
  private readonly i18n = inject(I18n);

  /** «включено» / «выключено» -- a switch's new state, as the value in a confirmation. */
  onOff(enabled: boolean): string {
    return messages.text(this.i18n.locale(), enabled ? 'on' : 'off');
  }

  /**
   * @param setting the setting's name, already translated
   * @param value   its new value, already formatted, when it reads well in a sentence
   */
  announce(kind: SavedKind, setting: string, target: SavedTarget, value?: string): void {
    const locale = this.i18n.locale();
    const where =
      target.level === 'TENANT'
        ? messages.text(locale, 'target.TENANT')
        : target.name
          ? messages.text(locale, target.level === 'BRAND' ? 'target.BRAND' : 'target.LOCATION', {
              name: target.name,
            })
          : messages.text(
              locale,
              target.level === 'BRAND' ? 'target.BRAND.unnamed' : 'target.LOCATION.unnamed',
            );
    const label = value ? `${setting}: ${value}` : setting;
    this.toasts.show({
      message: messages.text(locale, kind, { setting: label, target: where }),
      tone: 'success',
    });
  }
}
