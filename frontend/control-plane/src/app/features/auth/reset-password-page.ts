import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { I18nService, Locale } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { PasswordResetsApi, ResetInspection } from './password-resets-api';

/** The realm's own rule (Keycloak `length(12)`), checked here first so the operator is told before submitting. */
const MIN_PASSWORD_LENGTH = 12;

type Stage = 'loading' | 'invalid' | 'expired' | 'form' | 'done';

/**
 * Where a platform operator sets the password they asked to reset (ADR 0098).
 *
 * The emailed link is `/reset-password#token=…`: the token rides in the
 * fragment, which a browser never sends, so it reaches no server or proxy log,
 * and it is posted in a request body rather than put in a URL.
 *
 * It asks for the password and nothing else — no name, no address — because a
 * reset is not a setup: the person's own name must not be rewritten, and an
 * address must not become "verified" merely because somebody opened a link.
 * That is the whole difference between the platform's `setPassword` and the
 * `completeSetup` ADR 0097's invitation uses.
 *
 * It does **not** sign the operator in afterwards. It cannot: accepting ends
 * every session the account holds, which is the point of a reset, so a console
 * that signed itself straight back in would be racing the revocation it just
 * asked for.
 */
@Component({
  selector: 'app-reset-password-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="card">
        <span class="q-emphasis wordmark">{{ i18n.t('app.name') }}<span class="dot">.</span></span>

        @switch (stage()) {
          @case ('loading') {
            <p class="q-body muted">{{ i18n.t('resetPassword.loading') }}</p>
          }
          @case ('invalid') {
            <h1 class="q-title heading">{{ i18n.t('resetPassword.invalid.title') }}</h1>
            <p class="q-body muted">{{ i18n.t('resetPassword.invalid.body') }}</p>
            <a class="q-body link" routerLink="/forgot-password">{{
              i18n.t('resetPassword.askAgain')
            }}</a>
          }
          @case ('expired') {
            <h1 class="q-title heading">{{ i18n.t('resetPassword.expired.title') }}</h1>
            <p class="q-body muted">{{ i18n.t('resetPassword.expired.body') }}</p>
            <a class="q-body link" routerLink="/forgot-password">{{
              i18n.t('resetPassword.askAgain')
            }}</a>
          }
          @case ('done') {
            <h1 class="q-title heading">{{ i18n.t('resetPassword.done.title') }}</h1>
            <p class="q-body muted">{{ i18n.t('resetPassword.done.body') }}</p>
            <a class="q-body link" routerLink="/login">{{ i18n.t('resetPassword.toSignIn') }}</a>
          }
          @case ('form') {
            <form (submit)="submit($event)">
              <h1 class="q-title heading">{{ i18n.t('resetPassword.title') }}</h1>
              @if (inspection()?.maskedLogin; as account) {
                <p class="q-caption muted">
                  {{ i18n.t('resetPassword.forAccount', { account }) }}
                </p>
              }

              <label class="q-caption field-label" for="password">{{
                i18n.t('resetPassword.password')
              }}</label>
              <input
                id="password"
                class="q-body field"
                type="password"
                autocomplete="new-password"
                [value]="password()"
                (input)="password.set(value($event))"
                [disabled]="busy()"
              />
              <p class="q-caption muted hint">
                {{ i18n.t('resetPassword.passwordRule', { count: minLength }) }}
              </p>

              <label class="q-caption field-label" for="confirm">{{
                i18n.t('resetPassword.confirm')
              }}</label>
              <input
                id="confirm"
                class="q-body field"
                type="password"
                autocomplete="new-password"
                [value]="confirm()"
                (input)="confirm.set(value($event))"
                [disabled]="busy()"
              />
              @if (mismatch()) {
                <p class="q-caption error-text">{{ i18n.t('resetPassword.mismatch') }}</p>
              }

              @if (errorKey(); as key) {
                <p class="q-body-sm error" role="alert">{{ i18n.t(key) }}</p>
              }

              <button type="submit" class="q-body submit" [disabled]="!canSubmit()">
                {{ busy() ? i18n.t('resetPassword.submitting') : i18n.t('resetPassword.submit') }}
              </button>
            </form>
          }
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
      max-width: 400px;
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

    .link {
      color: var(--q-primary);
      display: inline-block;
      margin-top: 16px;
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

    .hint {
      margin: 4px 0 0;
    }

    .error-text {
      margin: 4px 0 0;
      color: var(--q-error-text);
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
export class ResetPasswordPage implements OnInit {
  private readonly resets = inject(PasswordResetsApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18nService);

  protected readonly minLength = MIN_PASSWORD_LENGTH;
  protected readonly stage = signal<Stage>('loading');
  protected readonly inspection = signal<ResetInspection | null>(null);
  protected readonly password = signal('');
  protected readonly confirm = signal('');
  protected readonly busy = signal(false);
  protected readonly errorKey = signal<MessageKey | null>(null);

  protected readonly mismatch = computed(
    () => this.confirm().length > 0 && this.confirm() !== this.password(),
  );

  protected readonly canSubmit = computed(
    () =>
      !this.busy() &&
      this.password().length >= MIN_PASSWORD_LENGTH &&
      this.confirm() === this.password(),
  );

  private token = '';

  async ngOnInit(): Promise<void> {
    this.token = new URLSearchParams(this.route.snapshot.fragment ?? '').get('token') ?? '';
    if (this.token.length === 0) {
      this.stage.set('invalid');
      return;
    }
    try {
      const inspection = await this.resets.inspect(this.token);
      this.i18n.use(localeFor(inspection.locale));
      this.inspection.set(inspection);
      this.stage.set('form');
    } catch (failure) {
      this.stage.set(reasonOf(failure) === 'EXPIRED' ? 'expired' : 'invalid');
    }
  }

  protected value(event: Event): string {
    this.errorKey.set(null);
    return (event.target as HTMLInputElement).value;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmit()) {
      return;
    }
    this.busy.set(true);
    this.errorKey.set(null);
    try {
      await this.resets.accept(this.token, this.password());
      this.stage.set('done');
      void this.router.navigateByUrl('/login');
    } catch (failure) {
      const reason = reasonOf(failure);
      if (reason === 'EXPIRED' || reason === 'INVALID') {
        this.stage.set(reason === 'EXPIRED' ? 'expired' : 'invalid');
      } else {
        this.errorKey.set(messageFor(failure));
      }
    } finally {
      this.busy.set(false);
    }
  }
}

function reasonOf(failure: unknown): string | null {
  if (failure instanceof ApiError && failure.code === 'RESOURCE_NOT_FOUND') {
    const reason = failure.problem['reason'];
    return typeof reason === 'string' ? reason : 'INVALID';
  }
  return null;
}

/** Keycloak names the rule a password broke; the operator is told which, in their language. */
function messageFor(failure: unknown): MessageKey {
  if (failure instanceof ApiError) {
    if (failure.code === 'VALIDATION_FAILED') {
      const policy = String(failure.problem['policy'] ?? '');
      if (policy.includes('MinLength')) {
        return 'resetPassword.policy.length';
      }
      if (policy.includes('NotEmail') || policy.includes('NotUsername')) {
        return 'resetPassword.policy.notEmail';
      }
      if (policy.includes('History')) {
        return 'resetPassword.policy.history';
      }
      return 'resetPassword.policy.other';
    }
    if (failure.code === 'RATE_LIMIT_EXCEEDED') {
      return 'error.RATE_LIMIT_EXCEEDED';
    }
    if (failure.code === 'NETWORK_UNREACHABLE') {
      return 'error.NETWORK_UNREACHABLE';
    }
  }
  return 'resetPassword.failed';
}

function localeFor(language: string): Locale {
  if (language === 'uz') {
    return 'uz-Latn';
  }
  return language === 'en' ? 'en' : 'ru';
}
