import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { PasswordResetsApi } from './password-resets-api';

/**
 * Where a platform operator who cannot sign in asks for a new password
 * (ADR 0098).
 *
 * Outside the shell and outside `authGuard`, for the reason `/login` is:
 * the visitor has no session, and that is the whole problem.
 *
 * **The screen says the same thing whatever happened.** One sentence — "if the
 * account exists, an email is on its way" — for a login that names an account,
 * one that does not, an account with no address, and an identity provider that
 * is down. The platform answers 202 to all four, so this page has nothing more
 * specific it could honestly say; being more specific would make an
 * unauthenticated endpoint a directory of HorecaOS's own staff, which is
 * exactly what ADR 0062 refuses to give away on sign-in.
 */
@Component({
  selector: 'app-forgot-password-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="card">
        <span class="q-emphasis wordmark">{{ i18n.t('app.name') }}<span class="dot">.</span></span>

        @if (sent()) {
          <h1 class="q-title heading">{{ i18n.t('forgotPassword.sent.title') }}</h1>
          <p class="q-body muted">{{ i18n.t('forgotPassword.sent.body') }}</p>
          <a class="q-body link" routerLink="/login">{{ i18n.t('forgotPassword.toSignIn') }}</a>
        } @else {
          <form (submit)="submit($event)">
            <h1 class="q-title heading">{{ i18n.t('forgotPassword.title') }}</h1>
            <p class="q-body muted lead">{{ i18n.t('forgotPassword.lead') }}</p>

            <label class="q-caption field-label" for="login">{{
              i18n.t('forgotPassword.login')
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
              <p class="q-body-sm error" role="alert">{{ i18n.t(key) }}</p>
            }

            <button type="submit" class="q-body submit" [disabled]="!canSubmit()">
              {{ busy() ? i18n.t('forgotPassword.submitting') : i18n.t('forgotPassword.submit') }}
            </button>
            <a class="q-body link back" routerLink="/login">{{
              i18n.t('forgotPassword.toSignIn')
            }}</a>
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
})
export class ForgotPasswordPage {
  private readonly resets = inject(PasswordResetsApi);
  protected readonly i18n = inject(I18nService);

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
 * branch for "no such account": the platform does not report one.
 */
function messageFor(failure: unknown): MessageKey {
  if (failure instanceof ApiError) {
    if (failure.code === 'RATE_LIMIT_EXCEEDED') {
      return 'error.RATE_LIMIT_EXCEEDED';
    }
    if (failure.code === 'NETWORK_UNREACHABLE') {
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
