import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { I18n, Locale } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { PasswordResetsApi, ResetInspection } from './password-resets-api';

/** The realm's own rule (Keycloak `length(12)`), checked here first so the operator is told before submitting. */
const MIN_PASSWORD_LENGTH = 12;

type Stage = 'loading' | 'invalid' | 'expired' | 'retry' | 'form' | 'done';

/**
 * Where a staff member sets the password they asked to reset (ADR 0098).
 *
 * `invite-page.ts` with the name fields removed, and the removal is the
 * design rather than a simplification: a reset must not rewrite a name the
 * person set themselves, nor mark an address verified because somebody opened
 * a link, so this page has nothing to send but the password.
 *
 * The emailed link is `/reset-password#token=…`: the token rides in the
 * fragment, which a browser never sends, so it reaches no server or proxy log,
 * and it is posted in a request body rather than put in a URL.
 *
 * Unlike the invite page it does **not** sign the operator in afterwards. It
 * cannot: accepting ends every session the account holds, which is the point
 * of a reset, and a console that signed itself straight back in would be
 * racing the revocation it just asked for. It ends this tab's own session
 * instead and stops on a card that says every other session has ended, with a
 * link to sign in with the password they chose.
 */
@Component({
  selector: 'q-reset-password-page',
  imports: [TPipe, RouterLink],
  template: `
    <div class="page">
      <div class="card">
        <span class="q-emphasis wordmark">{{ 'shell.brand' | t }}<span class="dot">.</span></span>

        @switch (stage()) {
          @case ('loading') {
            <p class="q-body muted">{{ 'resetPassword.loading' | t }}</p>
          }
          @case ('invalid') {
            <h1 class="q-title heading">{{ 'resetPassword.invalid.title' | t }}</h1>
            <p class="q-body muted">{{ 'resetPassword.invalid.body' | t }}</p>
            <a class="q-body link" routerLink="/forgot-password">{{
              'resetPassword.askAgain' | t
            }}</a>
          }
          @case ('expired') {
            <h1 class="q-title heading">{{ 'resetPassword.expired.title' | t }}</h1>
            <p class="q-body muted">{{ 'resetPassword.expired.body' | t }}</p>
            <a class="q-body link" routerLink="/forgot-password">{{
              'resetPassword.askAgain' | t
            }}</a>
          }
          @case ('retry') {
            <h1 class="q-title heading">{{ 'resetPassword.retry.title' | t }}</h1>
            <p class="q-body muted">{{ 'resetPassword.retry.body' | t }}</p>
            @if (errorKey(); as key) {
              <p class="q-body-sm error" role="alert">{{ key | t }}</p>
            }
            <button type="button" class="q-body submit" [disabled]="busy()" (click)="retry()">
              {{ 'resetPassword.retry.action' | t }}
            </button>
            <a class="q-body link" routerLink="/forgot-password">{{
              'resetPassword.askAgain' | t
            }}</a>
          }
          @case ('done') {
            @if (sessionsEnded()) {
              <h1 class="q-title heading">{{ 'resetPassword.done.title' | t }}</h1>
              <p class="q-body muted">{{ 'resetPassword.done.body' | t }}</p>
            } @else {
              <h1 class="q-title heading">
                {{ 'resetPassword.doneSessionsNotEnded.title' | t }}
              </h1>
              <p class="q-body warning" role="alert">
                {{ 'resetPassword.doneSessionsNotEnded.body' | t }}
              </p>
            }
            <a class="q-body link" routerLink="/login">{{ 'resetPassword.toSignIn' | t }}</a>
          }
          @case ('form') {
            <form (submit)="submit($event)">
              <h1 class="q-title heading">{{ 'resetPassword.title' | t }}</h1>
              @if (inspection()?.maskedLogin; as account) {
                <p class="q-caption muted">{{ 'resetPassword.forAccount' | t: { account } }}</p>
              }

              <label class="q-caption field-label" for="password">{{
                'resetPassword.password' | t
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
                {{ 'resetPassword.passwordRule' | t: { count: minLength } }}
              </p>

              <label class="q-caption field-label" for="confirm">{{
                'resetPassword.confirm' | t
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
                <p class="q-caption error-text">{{ 'resetPassword.mismatch' | t }}</p>
              }

              @if (errorKey(); as key) {
                <p class="q-body-sm error" role="alert">{{ key | t }}</p>
              }

              <button type="submit" class="q-body submit" [disabled]="!canSubmit()">
                {{ (busy() ? 'resetPassword.submitting' : 'resetPassword.submit') | t }}
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

    .warning {
      margin: 8px 0 0;
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
export class ResetPasswordPage implements OnInit {
  private readonly resets = inject(PasswordResetsApi);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(Auth);
  private readonly i18n = inject(I18n);

  protected readonly minLength = MIN_PASSWORD_LENGTH;
  protected readonly stage = signal<Stage>('loading');
  protected readonly inspection = signal<ResetInspection | null>(null);
  protected readonly password = signal('');
  protected readonly confirm = signal('');
  protected readonly busy = signal(false);
  protected readonly errorKey = signal<MessageKey | null>(null);

  /**
   * What the platform said about the revocation, not what this page assumes.
   *
   * Starts `false` and is set from the response before the card is shown, so
   * that the reassuring sentence has to be earned. The other default reads
   * better and fails the wrong way: a path that forgot to set this would go on
   * telling somebody every other session is gone.
   */
  protected readonly sessionsEnded = signal(false);

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
    await this.inspectToken();
  }

  /** Re-runs the check with the token still in hand; the link was never spent. */
  protected async retry(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.stage.set('loading');
    await this.inspectToken();
  }

  /**
   * Only the platform saying so means the link is dead.
   *
   * A rate-limited request from a venue behind one address, a gateway 502, or
   * a till whose link dropped for one round trip says nothing about the link —
   * and telling the operator it "cannot be used" pushes them to ask for
   * another, which clears the stored hash of the perfectly good link they are
   * holding. `submit()` has always drawn this distinction; `ngOnInit` used to
   * collapse it.
   */
  private async inspectToken(): Promise<void> {
    this.busy.set(true);
    try {
      const inspection = await this.resets.inspect(this.token);
      this.i18n.setLocale(localeFor(inspection.locale));
      this.inspection.set(inspection);
      this.errorKey.set(null);
      this.stage.set('form');
    } catch (failure) {
      const reason = reasonOf(failure);
      if (reason === 'EXPIRED' || reason === 'INVALID') {
        this.stage.set(reason === 'EXPIRED' ? 'expired' : 'invalid');
      } else {
        this.errorKey.set(messageFor(failure));
        this.stage.set('retry');
      }
    } finally {
      this.busy.set(false);
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
      const acceptance = await this.resets.accept(this.token, this.password());
      // Read, never assumed. Keycloak can accept the password and refuse the
      // revocation; the reset is complete either way, and the operator standing
      // here is the only person who can do anything about the difference —
      // sign the other devices out, or tell somebody. A card that says every
      // other session has ended while a dismissed employee's offline token
      // keeps working is the outcome ADR 0098 calls worse than not revoking.
      this.sessionsEnded.set(acceptance.sessionsEnded);
      // The account's sessions were just revoked at Keycloak, but this tab may
      // have restored one at boot — `provideAppInitializer` redeems whatever
      // refresh token sessionStorage holds, on every route including this one
      // — and would otherwise keep a usable access token for the rest of its
      // short life on exactly the shared till the reset exists for. Local
      // only: `logout()` would revoke a refresh token that can belong to
      // whoever else was signed in on this terminal.
      this.auth.forgetLocalSession();
      // No navigation. The 'done' card is where the operator is told every
      // other session has ended (ADR 0098 calls that the surprise), and a
      // redirect on the next tick would make it unreadable; the card carries
      // the link to sign in.
      this.stage.set('done');
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
  if (failure instanceof ApiError && failure.code === ApiErrorCode.RESOURCE_NOT_FOUND) {
    const reason = failure.problem?.['reason'];
    return typeof reason === 'string' ? reason : 'INVALID';
  }
  return null;
}

/** Keycloak names the rule a password broke; the staff member is told which, in their language. */
function messageFor(failure: unknown): MessageKey {
  if (failure instanceof ApiError) {
    if (failure.code === ApiErrorCode.VALIDATION_FAILED) {
      const policy = String(failure.problem?.['policy'] ?? '');
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
    if (failure.code === ApiErrorCode.RATE_LIMIT_EXCEEDED) {
      return 'error.RATE_LIMIT_EXCEEDED';
    }
    if (failure.code === ApiErrorCode.NETWORK_UNREACHABLE) {
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
