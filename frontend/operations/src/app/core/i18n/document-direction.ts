import { Injectable, Signal, computed, effect, inject } from '@angular/core';

import { I18n } from './i18n';
import { PlatformLocales } from './platform-locales';

/**
 * Keeps `<html dir>` next to `<html lang>` (ADR 0149, Decision 6).
 *
 * `lang` is set by {@link I18n} when a locale is applied; the direction is the registry's to state,
 * not the browser's default and not a constant here. Every language the platform registers is
 * left-to-right today, so nothing visibly changes, and that is the point: registering a
 * right-to-left one becomes a decision about order lines, receipts and SMS (its own record), not an
 * audit of every screen's `left` and `right`. New and touched CSS uses logical properties
 * (`margin-inline-start` and its siblings) so that day is cheap.
 *
 * A separate service from {@link I18n} on purpose: `I18n` is constructed by hand in places with no
 * injector, and a message lookup has no business needing the registry.
 */
@Injectable({ providedIn: 'root' })
export class DocumentDirection {
  private readonly i18n = inject(I18n);
  private readonly registry = inject(PlatformLocales);

  /** The direction the active language is written in. */
  readonly direction: Signal<'ltr' | 'rtl'> = computed(() =>
    this.registry.directionOf(this.i18n.locale()),
  );

  constructor() {
    effect(() => {
      document.documentElement.dir = this.direction();
    });
  }
}
