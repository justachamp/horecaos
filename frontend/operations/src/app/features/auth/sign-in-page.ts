import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { RETURN_TO_KEY } from '../../core/auth/auth.guard';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';

/**
 * This console's own sign-in page (ADR 0062).
 *
 * Replaces the redirect to Keycloak's login form and the `/auth/callback`
 * route that used to complete it — the one the owner reported broken in
 * practice (see the ADR's own Context). The operator's password never leaves
 * this origin: submitting POSTs the two fields to {@link Auth#signIn}, which
 * hands them to the platform backend, and the backend is the only thing that
 * ever talks to Keycloak, over a confidential client this bundle cannot see
 * (ADR 0028).
 *
 * Wrong password and unknown username answer identically —
 * `error.UNAUTHENTICATED` — so this screen shows the same sentence for both.
 */
@Component({
  selector: 'q-sign-in-page',
  imports: [TPipe],
  template: `
    <div class="page">
      <form class="card" (submit)="submit($event)">
        <span class="q-emphasis wordmark">{{ 'shell.brand' | t }}<span class="dot">.</span></span>
        <h1 class="q-title heading">{{ 'login.title' | t }}</h1>

        <label class="q-caption field-label" for="username">{{ 'login.username' | t }}</label>
        <input
          id="username"
          class="q-body field"
          type="text"
          autocomplete="username"
          [value]="username()"
          (input)="onUsernameInput($event)"
          [disabled]="loading()"
        />

        <label class="q-caption field-label" for="password">{{ 'login.password' | t }}</label>
        <input
          id="password"
          class="q-body field"
          type="password"
          autocomplete="current-password"
          [value]="password()"
          (input)="onPasswordInput($event)"
          [disabled]="loading()"
        />

        @if (errorMessage(); as message) {
          <p class="q-body-sm error" role="alert">{{ message }}</p>
        }

        <button type="submit" class="q-body submit" [disabled]="!canSubmit()">
          {{ (loading() ? 'login.submitting' : 'login.submit') | t }}
        </button>
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
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SignInPage {
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18n);

  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly loading = signal(false);
  protected readonly errorMessage = signal<string | null>(null);

  protected readonly canSubmit = () =>
    !this.loading() && this.username().trim().length > 0 && this.password().length > 0;

  protected onUsernameInput(event: Event): void {
    this.username.set((event.target as HTMLInputElement).value);
    this.errorMessage.set(null);
  }

  protected onPasswordInput(event: Event): void {
    this.password.set((event.target as HTMLInputElement).value);
    this.errorMessage.set(null);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmit()) {
      return;
    }
    this.loading.set(true);
    this.errorMessage.set(null);
    try {
      await this.auth.signIn(this.username().trim(), this.password());
      void this.router.navigateByUrl(takeReturnTo());
    } catch (failure) {
      this.errorMessage.set(this.messageFor(failure));
    } finally {
      this.loading.set(false);
    }
  }

  private messageFor(failure: unknown): string {
    if (failure instanceof ApiError) {
      if (failure.code === ApiErrorCode.UNAUTHENTICATED) {
        // Deliberately not error.UNAUTHENTICATED's own text ("The session has
        // ended. Sign in again.") — that copy is written for an expired
        // bearer on an already-signed-in screen, and this screen never was
        // one. The platform answers a wrong password and an unknown username
        // identically with this code (ADR 0062).
        return this.i18n.t('login.invalidCredentials');
      }
      const key = ERROR_MESSAGE_KEYS[failure.code];
      if (key) {
        return this.i18n.t(key);
      }
      return failure.correlationId
        ? this.i18n.t('error.unknown', { correlationId: failure.correlationId })
        : this.i18n.t('error.unknown.noReference');
    }
    return this.i18n.t('error.unknown.noReference');
  }
}

const ERROR_MESSAGE_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  [ApiErrorCode.RATE_LIMIT_EXCEEDED]: 'error.RATE_LIMIT_EXCEEDED',
  [ApiErrorCode.ACCOUNT_ACTION_REQUIRED]: 'error.ACCOUNT_ACTION_REQUIRED',
  [ApiErrorCode.NETWORK_UNREACHABLE]: 'error.NETWORK_UNREACHABLE',
};

/** Reads and clears the deep link the guard saved before sending the operator to sign in. */
function takeReturnTo(): string {
  let target = '/today';
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
