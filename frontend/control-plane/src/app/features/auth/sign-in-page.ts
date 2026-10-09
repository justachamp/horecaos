import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { MfaTicket } from '../../core/auth/mfa-ticket';
import { AuthService } from '../../core/auth/auth.service';
import { RETURN_TO_KEY } from '../../core/auth/guards';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { OtpInput } from '../../shared/ui/otp-input';
import { minutesOf } from './mfa-enrolment';

/**
 * This console's own sign-in page (ADR 0062).
 *
 * Replaces the redirect to Keycloak's login form: the operator's password
 * never leaves this origin. Submitting POSTs the two fields to
 * {@link AuthService#signIn}, which hands them to the platform backend; the
 * backend is the only thing that ever talks to Keycloak, over a confidential
 * client this bundle cannot see, because a browser application cannot keep a
 * secret (ADR 0028).
 *
 * Wrong password and unknown username answer identically —
 * `error.UNAUTHENTICATED` — so this screen shows the same sentence for both
 * and does not attempt to be more specific than the platform is willing to
 * be: a more specific message here would reintroduce the enumeration the
 * uniform answer exists to prevent.
 */
@Component({
  selector: 'app-sign-in-page',
  imports: [RouterLink, OtpInput],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <form class="card" (submit)="submit($event)">
        <span class="q-emphasis wordmark">{{ i18n.t('app.name') }}<span class="dot">.</span></span>
        @if (step() === 'credentials') {
          <h1 class="q-title heading">{{ i18n.t('login.title') }}</h1>

          <label class="q-caption field-label" for="username">{{ i18n.t('login.username') }}</label>
          <input
            id="username"
            class="q-body field"
            type="text"
            autocomplete="username"
            [value]="username()"
            (input)="onUsernameInput($event)"
            [disabled]="loading()"
          />

          <label class="q-caption field-label" for="password">{{ i18n.t('login.password') }}</label>
          <input
            id="password"
            class="q-body field"
            type="password"
            autocomplete="current-password"
            [value]="password()"
            (input)="onPasswordInput($event)"
            [disabled]="loading()"
          />
        } @else {
          <!--
            ADR 0148. The password was right and the account holds a second factor: the page
            asks for the code instead of saying so in a failure, because the platform only
            answers MFA_REQUIRED to somebody who already knows the password.
          -->
          <h1 class="q-title heading">{{ i18n.t('login.mfa.title') }}</h1>
          <p class="q-body-sm lead">{{ i18n.t('login.mfa.lead') }}</p>
          <q-otp-input
            class="otp"
            [value]="code()"
            [disabled]="loading()"
            [ariaLabel]="i18n.t('login.mfa.group')"
            [cellLabelPrefix]="i18n.t('login.mfa.digit')"
            (valueChange)="onCodeInput($event)"
            (complete)="submitCode($event)"
          />
        }

        @if (errorMessage(); as message) {
          <p class="q-body-sm error" role="alert">{{ message }}</p>
        }

        <button type="submit" class="q-body submit" [disabled]="!canSubmit()">
          {{
            loading()
              ? i18n.t(step() === 'code' ? 'login.mfa.submitting' : 'login.submitting')
              : i18n.t(step() === 'code' ? 'login.mfa.submit' : 'login.submit')
          }}
        </button>

        @if (step() === 'code') {
          <button type="button" class="q-body-sm forgot link" (click)="backToCredentials()">
            {{ i18n.t('login.mfa.back') }}
          </button>
        } @else {
          <!--
            ADR 0098. Below the button rather than beside the password field: an
            operator reaches for it after a failed attempt, not before one.
          -->
          <a class="q-body-sm forgot" routerLink="/forgot-password">{{
            i18n.t('login.forgotPassword')
          }}</a>
        }
      </form>
    </div>
  `,
  styles: `
    :host {
      display: block;
      height: 100%;
    }

    .page {
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--q-surface-1);
    }

    .card {
      width: 100%;
      max-width: 360px;
      padding: 32px;
      background: var(--q-canvas);
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
      display: flex;
      flex-direction: column;
    }

    .wordmark {
      color: var(--q-ink);
    }

    .dot {
      color: var(--q-primary);
    }

    .heading {
      margin: 16px 0 24px;
    }

    .field-label {
      color: var(--q-ink-muted);
      margin-top: 16px;
    }

    .field-label:first-of-type {
      margin-top: 0;
    }

    .field {
      margin-top: 4px;
      height: 40px;
      padding: 0 12px;
      border: 1px solid var(--q-surface-2);
      border-radius: var(--q-radius);
      background: var(--q-canvas);
      color: var(--q-ink);
    }

    .field:focus {
      outline: 2px solid var(--q-primary);
      outline-offset: -1px;
    }

    .field:disabled {
      background: var(--q-surface-1);
    }

    .error {
      margin: 16px 0 0;
      color: var(--q-error-text);
      background: var(--q-error-tint);
      padding: 8px 12px;
      border-radius: var(--q-radius);
    }

    .submit {
      margin-top: 24px;
      height: 40px;
      border: none;
      border-radius: var(--q-radius);
      background: var(--q-primary);
      color: var(--q-inverse-ink);
      cursor: pointer;
    }

    .submit:hover:not(:disabled) {
      background: var(--q-primary-hover);
    }

    .submit:disabled {
      background: var(--q-surface-2);
      color: var(--q-ink-subtle);
      cursor: default;
    }

    .forgot {
      margin-top: 16px;
      color: var(--q-primary);
      text-align: center;
    }

    .link {
      background: none;
      border: none;
      cursor: pointer;
      padding: 0;
    }

    .lead {
      margin: 0 0 20px;
      color: var(--q-ink-muted);
    }

    .otp {
      display: block;
    }
  `,
})
export class SignInPage {
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionContextService);
  private readonly router = inject(Router);
  private readonly mfaTicket = inject(MfaTicket);
  protected readonly i18n = inject(I18nService);

  protected readonly username = signal('');
  protected readonly password = signal('');
  /** ADR 0148: the second step. The code is held only here, for the one attempt, and never stored. */
  protected readonly code = signal('');
  protected readonly step = signal<'credentials' | 'code'>('credentials');
  protected readonly loading = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly canSubmit = () =>
    !this.loading() &&
    (this.step() === 'code'
      ? this.code().length === 6
      : this.username().trim().length > 0 && this.password().length > 0);

  protected onUsernameInput(event: Event): void {
    this.username.set((event.target as HTMLInputElement).value);
    this.errorMessage.set(null);
  }

  protected onPasswordInput(event: Event): void {
    this.password.set((event.target as HTMLInputElement).value);
    this.errorMessage.set(null);
  }

  protected onCodeInput(value: string): void {
    this.code.set(value);
    this.errorMessage.set(null);
  }

  protected backToCredentials(): void {
    this.step.set('credentials');
    this.code.set('');
    this.password.set('');
    this.errorMessage.set(null);
  }

  /** The sixth digit submits by itself: the field's own `complete` event, once. */
  protected submitCode(code: string): void {
    this.code.set(code);
    void this.attempt();
  }

  protected submit(event: Event): Promise<void> {
    event.preventDefault();
    return this.attempt();
  }

  private async attempt(): Promise<void> {
    if (!this.canSubmit()) {
      return;
    }
    const sendingCode = this.step() === 'code';
    this.loading.set(true);
    this.errorMessage.set(null);
    try {
      await this.auth.signIn(
        this.username().trim(),
        this.password(),
        sendingCode ? this.code() : undefined,
      );
      this.password.set('');
      this.code.set('');
      // The rail cannot render without knowing what the operator may reach,
      // so this is awaited before navigating rather than left to resolve
      // later and rearrange the navigation under the pointer — the same
      // reasoning the app initializer applied when this load ran there,
      // after a redirect completed, instead of here.
      await this.session.load();
      void this.router.navigateByUrl(takeReturnTo());
    } catch (failure) {
      this.onRefusal(failure, sendingCode);
    } finally {
      this.loading.set(false);
    }
  }

  private onRefusal(failure: unknown, sendingCode: boolean): void {
    if (failure instanceof ApiError) {
      if (failure.code === 'MFA_REQUIRED') {
        // The password was right. Not an error: the next thing to ask for.
        this.step.set('code');
        this.code.set('');
        return;
      }
      if (failure.code === 'MFA_ENROLMENT_REQUIRED') {
        const ticket = failure.problem['enrolmentTicket'];
        if (typeof ticket === 'string' && ticket.length > 0) {
          this.mfaTicket.hold(ticket);
          this.password.set('');
          this.code.set('');
          void this.router.navigateByUrl('/enrol-second-factor');
          return;
        }
      }
      if (sendingCode) {
        if (failure.code === 'MFA_CODE_INVALID') {
          this.code.set('');
          this.errorMessage.set(this.i18n.t('login.mfa.invalid'));
          return;
        }
        if (failure.code === 'RATE_LIMIT_EXCEEDED') {
          this.code.set('');
          this.errorMessage.set(
            this.i18n.t('login.mfa.rateLimited', { minutes: minutesOf(failure) }),
          );
          return;
        }
        if (failure.code === 'UNAUTHENTICATED') {
          // The password stopped being accepted between the two steps: start again rather than
          // ask for a code that cannot work.
          this.backToCredentials();
          this.errorMessage.set(this.i18n.t('login.mfa.expiredPassword'));
          return;
        }
      }
    }
    this.errorMessage.set(this.messageFor(failure));
  }

  private messageFor(failure: unknown): string {
    if (failure instanceof ApiError) {
      // UNAUTHENTICATED is deliberately not run through i18n.describe() here:
      // that catalogue entry is written for an expired bearer on a screen
      // that was already signed in, and this screen never was. See
      // messages.en.ts's own note on `login.invalidCredentials`.
      return failure.code === 'UNAUTHENTICATED'
        ? this.i18n.t('login.invalidCredentials')
        : this.i18n.describe(failure);
    }
    return this.i18n.t('error.UNKNOWN');
  }
}

/**
 * Reads and clears the deep link `authGuard` (or `sessionRefreshInterceptor`,
 * after a mid-session 401 outlives a silent refresh) saved before sending
 * the operator to sign in.
 *
 * The platform's `PROMPT` phase of ADR 0148 (offer a second factor, do not require it) has no
 * screen to land on in this console: it has no profile page, and a platform account is taken to
 * enrolment by the refused sign-in once the phase reaches `REQUIRED`. So the answer to "was it
 * offered" is not read here.
 */
function takeReturnTo(): string {
  let target = '/';
  try {
    const saved = globalThis.sessionStorage?.getItem(RETURN_TO_KEY);
    // Only same-document paths. A stored absolute URL would make this an
    // open redirect operated by whatever could write to sessionStorage.
    if (saved && saved.startsWith('/') && !saved.startsWith('//')) {
      target = saved;
    }
    globalThis.sessionStorage?.removeItem(RETURN_TO_KEY);
  } catch {
    // No storage: land on the default.
  }
  return target;
}
