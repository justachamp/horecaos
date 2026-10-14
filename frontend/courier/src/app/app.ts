import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, skip } from 'rxjs';

import { AuthService } from './core/auth/auth.service';
import { AppUpdates } from './core/app-updates';
import { I18n } from './core/i18n/i18n';
import { LOCALES, parseLocale } from './core/i18n/locales';
import { TPipe } from './core/i18n/t.pipe';

/**
 * The shell: a top bar with the app's name and the language switch, the screen, and -- once
 * signed in -- the bottom navigation a thumb can reach.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthService);
  protected readonly updates = inject(AppUpdates);
  private readonly router = inject(Router);

  protected readonly locales = LOCALES;

  constructor() {
    effect(() => {
      document.documentElement.lang = this.i18n.htmlLang();
    });

    // After every navigation but the first, put focus on the new screen so a screen reader reads
    // it; the first load already starts at the top of the document.
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        skip(1),
      )
      .subscribe(() => document.getElementById('main')?.focus());
  }

  protected chooseLocale(event: Event): void {
    const locale = parseLocale((event.target as HTMLSelectElement).value);
    if (locale !== null) {
      void this.i18n.use(locale);
    }
  }
}
