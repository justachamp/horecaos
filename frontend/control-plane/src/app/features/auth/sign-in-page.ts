import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { AuthService } from '../../core/auth/auth.service';
import { RETURN_TO_KEY } from '../../core/auth/guards';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';

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
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <form class="card" (submit)="submit($event)">
        <span class="q-emphasis wordmark">{{ i18n.t('app.name') }}<span class="dot">.</span></span>
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

        @if (errorMessage(); as message) {
          <p class="q-body-sm error" role="alert">{{ message }}</p>
        }

        <button type="submit" class="q-body submit" [disabled]="!canSubmit()">
          {{ loading() ? i18n.t('login.submitting') : i18n.t('login.submit') }}
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
})
export class SignInPage {
  private readonly auth = inject(AuthService);
  private readonly session = inject(SessionContextService);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18nService);

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
      // The rail cannot render without knowing what the operator may reach,
      // so this is awaited before navigating rather than left to resolve
      // later and rearrange the navigation under the pointer — the same
      // reasoning the app initializer applied when this load ran there,
      // after a redirect completed, instead of here.
      await this.session.load();
      void this.router.navigateByUrl(takeReturnTo());
    } catch (failure) {
      this.errorMessage.set(this.messageFor(failure));
    } finally {
      this.loading.set(false);
    }
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
