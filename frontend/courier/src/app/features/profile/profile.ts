import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { AuthService } from '../../core/auth/auth.service';
import { CourierProfileStore } from '../../core/courier/courier-profile.store';
import { I18n } from '../../core/i18n/i18n';
import { LOCALES, parseLocale } from '../../core/i18n/locales';
import { TPipe } from '../../core/i18n/t.pipe';
import { APP_VERSION } from '../../core/version';

/** The courier's account, their language, and the way out. */
@Component({
  selector: 'app-profile',
  imports: [TPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './profile.html',
  styleUrl: './profile.css',
})
export class ProfilePage {
  protected readonly auth = inject(AuthService);
  protected readonly store = inject(CourierProfileStore);
  protected readonly i18n = inject(I18n);
  protected readonly locales = LOCALES;
  protected readonly version = APP_VERSION;

  protected choose(code: string): void {
    const locale = parseLocale(code);
    if (locale !== null) {
      void this.i18n.use(locale);
    }
  }

  protected signOut(): void {
    void this.auth.signOut();
  }
}
