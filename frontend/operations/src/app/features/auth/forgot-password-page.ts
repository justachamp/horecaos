import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { PasswordResetsApi } from './password-resets-api';

/**
 * Where an operator who cannot sign in asks for a new password (ADR 0098).
 *
 * Outside the guard, for the reason `/login` is: the visitor has no session,
 * and that is the whole problem.
 *
 * **The screen says the same thing whatever happened.** One sentence — "if the
 * account exists, an email is on its way" — for a login that names an account,
 * one that does not, an account with no address, and an identity provider that
 * is down. The platform answers 202 to all four (ADR 0098) so this page has
 * nothing more specific to say, and that is deliberate rather than a gap:
 * anything more specific would make an unauthenticated endpoint a directory of
 * who works here, which is precisely what ADR 0062 refuses to give away on
 * sign-in.
 *
 * The only failure it does report is one about the request rather than about
 * the account: too many attempts, or the network.
 */
@Component({
  selector: 'q-forgot-password-page',
  imports: [TPipe, RouterLink],
  template: `
    <div class="page">
      <div class="card">
        <span class="q-emphasis wordmark">{{ 'shell.brand' | t }}<span class="dot">.</span></span>

        @if (sent()) {
          <h1 class="q-title heading">{{ 'forgotPassword.sent.title' | t }}</h1>
          <p class="q-body muted">{{ 'forgotPassword.sent.body' | t }}</p>
          <a class="q-body link" routerLink="/login">{{ 'forgotPassword.toSignIn' | t }}</a>
        } @else {
          <form (submit)="submit($event)">
            <h1 class="q-title heading">{{ 'forgotPassword.title' | t }}</h1>
            <p class="q-body muted lead">{{ 'forgotPassword.lead' | t }}</p>

            <label class="q-caption field-label" for="login">{{
              'forgotPassword.login' | t
            }}</label>
            <input
              id="login"
              class="q-body field"
              type="text"
              autocomplete="username"
              [value]="login()"
              (input)="onLoginInput($event)"
              [disabled]="busy()"
            />

            @if (errorKey(); as key) {
              <p class="q-body-sm error" role="alert">{{ key | t }}</p>
            }

            <button type="submit" class="q-body submit" [disabled]="!canSubmit()">
              {{ (busy() ? 'forgotPassword.submitting' : 'forgotPassword.submit') | t }}
            </button>
            <a class="q-body link back" routerLink="/login">{{ 'forgotPassword.toSignIn' | t }}</a>
          </form>
        }
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      height: 100%;
    }

    .page {
      min-height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--q-surface-1);
      padding: 24px 16px;
    }

    .card {
      width: 100%;
      max-width: 380px;
      padding: 32px;
      background: var(--q-canvas);
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
    }

    form {
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
      margin: 16px 0 8px;
    }

    .muted {
      color: var(--q-ink-muted);
    }

    .lead {
      margin: 0 0 8px;
    }

    .link {
      color: var(--q-primary);
      display: inline-block;
      margin-top: 16px;
    }

    .back {
      text-align: center;
    }

    .field-label {
      color: var(--q-ink-muted);
      margin-top: 16px;
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
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ForgotPasswordPage {
  private readonly resets = inject(PasswordResetsApi);
  private readonly i18n = inject(I18n);

  protected readonly login = signal('');
  protected readonly busy = signal(false);
  protected readonly sent = signal(false);
  protected readonly errorKey = signal<MessageKey | null>(null);

  protected readonly canSubmit = () => !this.busy() && this.login().trim().length > 0;

  protected onLoginInput(event: Event): void {
    this.login.set((event.target as HTMLInputElement).value);
    this.errorKey.set(null);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmit()) {
      return;
    }
    this.busy.set(true);
    this.errorKey.set(null);
    try {
      await this.resets.request(this.login().trim(), localeParameter(this.i18n.locale()));
      this.sent.set(true);
    } catch (failure) {
      this.errorKey.set(messageFor(failure));
    } finally {
      this.busy.set(false);
    }
  }
}

/**
 * Only failures of the request itself are named. There is deliberately no
 * branch here for "no such account": the platform does not report one, and a
 * client that inferred one from a timing or a status would be rebuilding the
 * oracle the uniform answer removes.
 */
function messageFor(failure: unknown): MessageKey {
  if (failure instanceof ApiError) {
    if (failure.code === ApiErrorCode.RATE_LIMIT_EXCEEDED) {
      return 'error.RATE_LIMIT_EXCEEDED';
    }
    if (failure.code === ApiErrorCode.NETWORK_UNREACHABLE) {
      return 'error.NETWORK_UNREACHABLE';
    }
  }
  return 'forgotPassword.failed';
}

/** The catalogue's locale in the three-value shape the platform's email renderer takes. */
function localeParameter(locale: string): string {
  if (locale === 'uz-Latn') {
    return 'uz';
  }
  return locale === 'en' ? 'en' : 'ru';
}
