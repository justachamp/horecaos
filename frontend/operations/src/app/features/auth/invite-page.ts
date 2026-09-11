import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { I18n, Locale } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { InvitationInspection, InvitationsApi } from './invitations-api';

/** The realm's own rule (Keycloak `length(12)`), checked here first so the owner is told before submitting. */
const MIN_PASSWORD_LENGTH = 12;

type Stage = 'loading' | 'invalid' | 'expired' | 'form' | 'done';

/**
 * Where an invited owner sets up their account (ADR 0097).
 *
 * The emailed link is `/invite#token=…`: the token rides in the fragment, which
 * a browser never sends, so it reaches no server or proxy log, and it is posted
 * in a request body to the platform, never put in a URL. Outside the guard for
 * the reason `/login` is: the visitor has no password yet.
 *
 * On success the owner is signed in with the password they just chose and
 * lands on Today; nothing is asked of them twice.
 */
@Component({
  selector: 'q-invite-page',
  imports: [TPipe, RouterLink],
  template: `
    <div class="page">
      <div class="card">
        <span class="q-emphasis wordmark">{{ 'shell.brand' | t }}<span class="dot">.</span></span>

        @switch (stage()) {
          @case ('loading') {
            <p class="q-body muted">{{ 'invite.loading' | t }}</p>
          }
          @case ('invalid') {
            <h1 class="q-title heading">{{ 'invite.invalid.title' | t }}</h1>
            <p class="q-body muted">{{ 'invite.invalid.body' | t }}</p>
            <a class="q-body link" routerLink="/login">{{ 'invite.toSignIn' | t }}</a>
          }
          @case ('expired') {
            <h1 class="q-title heading">{{ 'invite.expired.title' | t }}</h1>
            <p class="q-body muted">{{ 'invite.expired.body' | t }}</p>
          }
          @case ('done') {
            <p class="q-body muted">{{ 'invite.signingIn' | t }}</p>
          }
          @case ('form') {
            @if (inspection(); as invitation) {
              <form (submit)="submit($event)">
                <h1 class="q-title heading">{{ 'invite.title' | t }}</h1>
                <p class="q-body lead">
                  {{ 'invite.lead' | t: { tenant: invitation.tenantName } }}
                </p>
                @if (invitation.emailMasked; as email) {
                  <p class="q-caption muted">{{ 'invite.sentTo' | t: { email } }}</p>
                }

                <label class="q-caption field-label" for="firstName">{{
                  'invite.firstName' | t
                }}</label>
                <input
                  id="firstName"
                  class="q-body field"
                  type="text"
                  autocomplete="given-name"
                  [value]="firstName()"
                  (input)="firstName.set(value($event))"
                  [disabled]="busy()"
                />

                <label class="q-caption field-label" for="lastName">{{
                  'invite.lastName' | t
                }}</label>
                <input
                  id="lastName"
                  class="q-body field"
                  type="text"
                  autocomplete="family-name"
                  [value]="lastName()"
                  (input)="lastName.set(value($event))"
                  [disabled]="busy()"
                />

                <label class="q-caption field-label" for="password">{{
                  'invite.password' | t
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
                  {{ 'invite.passwordRule' | t: { count: minLength } }}
                </p>

                <label class="q-caption field-label" for="confirm">{{
                  'invite.confirm' | t
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
                  <p class="q-caption error-text">{{ 'invite.mismatch' | t }}</p>
                }

                @if (errorKey(); as key) {
                  <p class="q-body-sm error" role="alert">{{ key | t }}</p>
                }

                <button type="submit" class="q-body submit" [disabled]="!canSubmit()">
                  {{ (busy() ? 'invite.submitting' : 'invite.submit') | t }}
                </button>
              </form>
            }
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

    .lead {
      margin: 0 0 4px;
      color: var(--q-ink);
    }

    .muted {
      color: var(--q-ink-muted);
    }

    .link {
      color: var(--q-primary);
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
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InvitePage implements OnInit {
  private readonly invitations = inject(InvitationsApi);
  private readonly auth = inject(Auth);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly i18n = inject(I18n);

  protected readonly minLength = MIN_PASSWORD_LENGTH;
  protected readonly stage = signal<Stage>('loading');
  protected readonly inspection = signal<InvitationInspection | null>(null);
  protected readonly firstName = signal('');
  protected readonly lastName = signal('');
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
      this.firstName().trim().length > 0 &&
      this.lastName().trim().length > 0 &&
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
      const inspection = await this.invitations.inspect(this.token);
      this.i18n.setLocale(localeFor(inspection.locale));
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
      const accepted = await this.invitations.accept(
        this.token,
        this.firstName().trim(),
        this.lastName().trim(),
        this.password(),
      );
      this.stage.set('done');
      try {
        await this.auth.signIn(accepted.signInName, this.password());
        void this.router.navigateByUrl('/today');
      } catch {
        // The account is set up; only the automatic sign-in failed. The
        // sign-in page is where the owner goes next either way.
        void this.router.navigateByUrl('/login');
      }
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

/** Keycloak names the rule a password broke; the owner is told which, in their language. */
function messageFor(failure: unknown): MessageKey {
  if (failure instanceof ApiError) {
    if (failure.code === ApiErrorCode.VALIDATION_FAILED) {
      const policy = String(failure.problem?.['policy'] ?? '');
      if (policy.includes('MinLength')) {
        return 'invite.policy.length';
      }
      if (policy.includes('NotEmail') || policy.includes('NotUsername')) {
        return 'invite.policy.notEmail';
      }
      if (policy.includes('History')) {
        return 'invite.policy.history';
      }
      return 'invite.policy.other';
    }
    if (failure.code === ApiErrorCode.RATE_LIMIT_EXCEEDED) {
      return 'error.RATE_LIMIT_EXCEEDED';
    }
    if (failure.code === ApiErrorCode.NETWORK_UNREACHABLE) {
      return 'error.NETWORK_UNREACHABLE';
    }
  }
  return 'invite.failed';
}

function localeFor(language: string): Locale {
  if (language === 'uz') {
    return 'uz-Latn';
  }
  return language === 'en' ? 'en' : 'ru';
}
