import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, skip } from 'rxjs';

import { AuthService } from './core/auth/auth.service';
import { AppUpdates } from './core/app-updates';
import { I18n } from './core/i18n/i18n';
import { LOCALES, parseLocale } from './core/i18n/locales';
import { TPipe } from './core/i18n/t.pipe';
import { WorkSession } from './core/work/work-session';
import { DutyIndicatorBar } from './features/duty/duty-indicator';

/**
 * The shell: a top bar with the app's name and the language switch, the screen, and -- once
 * signed in -- the bottom navigation a thumb can reach.
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe, DutyIndicatorBar],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthService);
  protected readonly updates = inject(AppUpdates);
  private readonly router = inject(Router);
  private readonly work = inject(WorkSession);

  protected readonly locales = LOCALES;

  constructor() {
    effect(() => {
      document.documentElement.lang = this.i18n.htmlLang();
    });

    // Signing in starts the courier's working state (the record, a duty session still open);
    // signing out ends it, so nothing keeps collecting for somebody who has left.
    let wasSignedIn = false;
    effect(() => {
      const signedIn = this.auth.signedIn();
      if (signedIn) {
        void this.work.begin();
      } else if (wasSignedIn) {
        this.work.end();
      }
      wasSignedIn = signedIn;
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
