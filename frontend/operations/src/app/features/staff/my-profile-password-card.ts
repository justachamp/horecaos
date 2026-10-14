import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { Auth } from '../../core/auth/auth';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { TPipe } from '../../core/i18n/t.pipe';
import { StaffApi } from './staff-api';

/**
 * «Пароль» on «Мой профиль» (ADR 0098, ADR 0139; gap-map row `X.5`): the hand-off to the password reset.
 *
 * **HorecaOS never sees the password.** It lives with the sign-in service (ADR 0139's boundary row), and an
 * in-app "current and new password" form would contradict it and would make this console the place a
 * password is typed. So this card does one thing: it takes the person to the page that sends the reset
 * link, with their own sign-in name already typed.
 *
 * **Two things are said before the button, because both surprise.** The reset ends every sign-in the account
 * holds, the one in this console included (`PasswordResetService`, after `logoutEverywhere`), so the
 * person is told they will have to sign in again. And a reset arrives by email: on a platform with no mail
 * server set up the link would never come, and the reset request itself cannot say so (it answers 202 to
 * everyone, so that it cannot be used to ask who works here). The card asks the one question that can be
 * answered for a signed-in person without telling anyone anything - can this platform send mail at all - and
 * says no instead of letting the person wait for a message that cannot arrive.
 *
 * When that question cannot be answered the button stays available: the reset is still the right way out.
 */
@Component({
  selector: 'q-my-profile-password-card',
  imports: [TPipe],
  templateUrl: './my-profile-password-card.html',
  styleUrl: './my-profile-password-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyProfilePasswordCard {
  private readonly api = inject(StaffApi);
  private readonly tenant = inject(CurrentTenant);
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);

  /** `checking` until the platform answers; `unknown` when it could not be asked. */
  protected readonly mail = signal<'checking' | 'configured' | 'missing' | 'unknown'>('checking');

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.mail.set('unknown');
      return;
    }
    try {
      const answer = await this.api.passwordHandoff(tenantId);
      this.mail.set(answer.emailConfigured ? 'configured' : 'missing');
    } catch {
      this.mail.set('unknown');
    }
  }

  /** Hands the person to the reset page with their own sign-in name; state, never the address bar. */
  protected change(): void {
    const login = this.auth.signInName();
    void this.router.navigate(['/forgot-password'], {
      state: login === null ? {} : { login },
    });
  }
}
