import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { APP_CONFIG } from '../../../core/config/app-config';
import {
  CustomerOtp,
  CustomerSignInUnavailableError,
  OtpNumberRejectedError,
  OtpRateLimitedError,
  OtpUndeliverableError,
} from '../../../core/session/customer-otp';
import { formatUzPhone, nationalDigits, toE164 } from '../../../core/session/phone';
import type { AuthCodeState } from '../../../auth/auth.state';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';

/**
 * Kirish: the phone number, and the request for an SMS code (ADR 0051).
 *
 * The route `authGuard` has always pointed at and this app never had. It exists
 * for the customer who must have an account before anything the platform will
 * not do anonymously -- a cart, an order -- and it is where a guest at a table
 * lands from «sign in to order» (`DineInTableComponent.signIn`, which remembers
 * the way back in `ReturnDestination`).
 *
 * One way in: phone and SMS code. `frontend/storefront` also offers "continue
 * with Telegram" here; this app's core has the pieces for it (`TelegramSignIn`)
 * but no screen yet, and a guest at a table has a phone in their hand.
 *
 * <h2>The phone number</h2>
 *
 * Typed as nine national digits behind a fixed `+998`, shown grouped, sent in the
 * one canonical E.164 form a gateway takes. It travels to the code screen in the
 * router's navigation *state*, never in the URL: a URL is written to every access
 * log, every proxy and every `Referer` the page emits afterwards (ADR 0029).
 */
@Component({
  selector: 'app-auth-login',
  standalone: true,
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './auth-login.component.html',
  styleUrl: './auth-login.component.scss',
})
export class AuthLoginComponent {
  private readonly otp = inject(CustomerOtp);
  private readonly router = inject(Router);

  /** This deployment's own name and mark, for the screen before anyone has signed in. */
  protected readonly brand = inject(APP_CONFIG).brand;

  /** The nine national digits typed so far. */
  protected readonly phone = signal('');
  protected readonly loading = signal(false);
  protected readonly errorKey = signal<string | null>(null);

  /** `90 123 45 67`, the digits grouped for reading. */
  protected readonly national = computed(() => formatUzPhone(this.phone()).replace(/^\+998\s?/, ''));

  protected readonly canContinue = computed(() => toE164(this.phone()) !== null && !this.loading());

  protected onPhoneInput(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.phone.set(nationalDigits(input.value));
    // Angular does not rewrite a bound value that did not change, so the field
    // is put back to the grouped form by hand.
    input.value = this.national();
    this.errorKey.set(null);
  }

  /**
   * Asks the platform to send a code.
   *
   * Only the challenge is carried on: the platform answers with a `challengeId`,
   * and the code itself exists only in the customer's SMS.
   */
  protected async continue(): Promise<void> {
    const phone = toE164(this.phone());
    if (!phone || this.loading()) {
      return;
    }
    this.errorKey.set(null);
    this.loading.set(true);
    try {
      const challenge = await this.otp.requestCode(phone);
      const state: AuthCodeState = {
        phone,
        challengeId: challenge.challengeId,
        codeLength: challenge.codeLength,
        attemptsAllowed: challenge.attemptsAllowed,
        expiresAt: challenge.expiresAt,
      };
      await this.router.navigate(['/auth/code'], { state });
    } catch (failure) {
      this.errorKey.set(this.messageKeyFor(failure));
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Says what actually happened. The distinctions are the ones a customer can act
   * on: a number that opted out will never receive a code however often they
   * press the button, so that is the one case that names a different remedy.
   */
  private messageKeyFor(failure: unknown): string {
    if (failure instanceof OtpNumberRejectedError) {
      return 'auth.errors.numberRejected';
    }
    if (failure instanceof OtpRateLimitedError) {
      return 'auth.errors.rateLimited';
    }
    if (failure instanceof OtpUndeliverableError) {
      return failure.permanent ? 'auth.errors.undeliverablePermanent' : 'auth.errors.undeliverable';
    }
    if (failure instanceof CustomerSignInUnavailableError) {
      return 'auth.errors.unavailable';
    }
    return 'errors.generic';
  }
}
