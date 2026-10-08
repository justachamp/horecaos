import { Injectable, Signal, computed, effect, inject } from '@angular/core';

import { I18nService } from './i18n.service';
import { PlatformLocales } from './platform-locales';

/**
 * Keeps `<html dir>` next to `<html lang>` (ADR 0149, Decision 6).
 *
 * The direction is the registry's to state, not the browser's default and not a constant here.
 * Every language the platform registers is left-to-right today, so nothing visibly changes; the
 * statement is what makes registering a right-to-left one a decision about order lines, receipts and
 * SMS, not an audit of every screen. New CSS uses logical properties.
 */
@Injectable({ providedIn: 'root' })
export class DocumentDirection {
  private readonly i18n = inject(I18nService);
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
