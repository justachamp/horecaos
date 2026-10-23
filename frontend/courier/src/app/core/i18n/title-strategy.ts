import { Injectable, effect, inject, signal } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';

import { I18n } from './i18n';
import type { MessageKey } from './message-keys';

/**
 * The document title (WCAG 2.4.2): the screen's name, in the courier's language, then the app's.
 *
 * A route declares `title: 'home.title'` -- a message key, which the strategy translates -- and the
 * title follows a language switch because it is recomputed from the key, not stored as text.
 */
@Injectable({ providedIn: 'root' })
export class AppTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly i18n = inject(I18n);
  private readonly key = signal<MessageKey | null>(null);

  constructor() {
    super();
    effect(() => {
      const key = this.key();
      const app = this.i18n.t('app.title');
      this.title.setTitle(key === null ? app : `${this.i18n.t(key)} · ${app}`);
    });
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.key.set((this.buildTitle(snapshot) as MessageKey | undefined) ?? null);
  }
}
