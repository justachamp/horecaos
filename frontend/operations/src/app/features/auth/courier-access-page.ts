import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { DEFAULT_LOCALE, I18n, Locale, isLocale } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { CourierAccessApi, CourierAccessInspection } from './courier-access-api';

/** The realm's own rule (Keycloak `length(12)`), checked here first so the courier is told before submitting. */
const MIN_PASSWORD_LENGTH = 12;

type Stage = 'loading' | 'invalid' | 'expired' | 'retry' | 'form' | 'done';

/**
 * Where a courier chooses the password for the courier app (ADR 0178, couriers.md §18).
 *
 * The texted link is `/courier-access#token=...`: the token rides in the fragment, which a browser never sends,
 * so it reaches no server or proxy log, and it is posted in a request body rather than put in a URL. The page
 * lives in the operations console only until the courier client carries it; the link's origin is configuration.
 *
 * It does not sign anyone in: the courier opens their own app and signs in with the password they chose. It ends
 * this tab's local session first, because the page is reachable on a shared handset or till and a session
 * restored at boot would otherwise outlive the reset.
 */
@Component({
  selector: 'q-courier-access-page',
  imports: [TPipe],
  template: `
    <div class="page">
      <div class="card">
        <span class="q-emphasis wordmark">{{ 'shell.brand' | t }}<span class="dot">.</span></span>

        @switch (stage()) {
          @case ('loading') {
            <p class="q-body muted">{{ 'courierAccess.loading' | t }}</p>
          }
          @case ('invalid') {
            <h1 class="q-title heading">{{ 'courierAccess.invalid.title' | t }}</h1>
            <p class="q-body muted">{{ 'courierAccess.invalid.body' | t }}</p>
          }
          @case ('expired') {
            <h1 class="q-title heading">{{ 'courierAccess.expired.title' | t }}</h1>
            <p class="q-body muted">{{ 'courierAccess.expired.body' | t }}</p>
          }
          @case ('retry') {
            <h1 class="q-title heading">{{ 'courierAccess.retry.title' | t }}</h1>
            <p class="q-body muted">{{ 'courierAccess.retry.body' | t }}</p>
            @if (errorKey(); as key) {
              <p class="q-body-sm error" role="alert">{{ key | t }}</p>
            }
            <button type="button" class="q-body submit" [disabled]="busy()" (click)="retry()">
              {{ 'courierAccess.retry.action' | t }}
            </button>
          }
          @case ('done') {
            @if (sessionsEnded()) {
              <h1 class="q-title heading" data-testid="courier-access-done">
                {{ 'courierAccess.done.title' | t }}
              </h1>
              <p class="q-body muted">{{ 'courierAccess.done.body' | t }}</p>
            } @else {
              <h1 class="q-title heading" data-testid="courier-access-done">
                {{ 'courierAccess.done.title' | t }}
              </h1>
              <p class="q-body warning" role="alert">
                {{ 'courierAccess.doneSessionsNotEnded.body' | t }}
              </p>
            }
          }
          @case ('form') {
            <form (submit)="submit($event)">
              <h1 class="q-title heading">
                {{ (isReset() ? 'courierAccess.title.reset' : 'courierAccess.title.invite') | t }}
              </h1>

              <label class="q-caption field-label" for="password">{{
                'courierAccess.password' | t
              }}</label>
              <input
                id="password"
                class="q-body field"
                type="password"
                autocomplete="new-password"
                data-testid="courier-access-password"
                [value]="password()"
                (input)="password.set(value($event))"
                [disabled]="busy()"
              />
              <p class="q-caption muted hint">
                {{ 'courierAccess.passwordRule' | t: { count: minLength } }}
              </p>

              <label class="q-caption field-label" for="confirm">{{
                'courierAccess.confirm' | t
              }}</label>
              <input
                id="confirm"
                class="q-body field"
                type="password"
                autocomplete="new-password"
                data-testid="courier-access-confirm"
                [value]="confirm()"
                (input)="confirm.set(value($event))"
                [disabled]="busy()"
              />
              @if (mismatch()) {
                <p class="q-caption error-text">{{ 'courierAccess.mismatch' | t }}</p>
              }

              @if (errorKey(); as key) {
                <p class="q-body-sm error" role="alert" data-testid="courier-access-error">
                  {{ key | t }}
                </p>
              }

              <button
                type="submit"
                class="q-body submit"
                data-testid="courier-access-submit"
                [disabled]="!canSubmit()"
              >
                {{ (busy() ? 'courierAccess.submitting' : 'courierAccess.submit') | t }}
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

    .field-label {
      color: var(--q-ink-muted);
      margin-top: 16px;
    }

    .field {
      margin-top: 4px;
      height: 44px;
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

    .error,
    .warning {
      margin: 16px 0 0;
      color: var(--q-error-text);
      background: var(--q-error-tint);
      padding: 8px 12px;
      border-radius: var(--q-radius);
    }

    .submit {
      margin-top: 24px;
      height: 44px;
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
export class CourierAccessPage implements OnInit {
  private readonly links = inject(CourierAccessApi);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(Auth);
  private readonly i18n = inject(I18n);

  protected readonly minLength = MIN_PASSWORD_LENGTH;
  protected readonly stage = signal<Stage>('loading');
  protected readonly inspection = signal<CourierAccessInspection | null>(null);
  protected readonly password = signal('');
  protected readonly confirm = signal('');
  protected readonly busy = signal(false);
  protected readonly errorKey = signal<MessageKey | null>(null);

  /** Read from the platform's answer, never assumed: starts false so the reassuring sentence has to be earned. */
  protected readonly sessionsEnded = signal(false);

  protected readonly isReset = computed(() => this.inspection()?.kind === 'RESET');
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

  private async inspectToken(): Promise<void> {
    this.busy.set(true);
    try {
      const inspection = await this.links.inspect(this.token);
      this.i18n.setLocale(localeFor(inspection.locale));
      this.inspection.set(inspection);
      this.errorKey.set(null);
      this.stage.set('form');
    } catch (failure) {
      const reason = reasonOf(failure);
      if (reason === 'EXPIRED' || reason === 'INVALID') {
        this.stage.set(reason === 'EXPIRED' ? 'expired' : 'invalid');
      } else {
        // A rate limit, a gateway error or a dropped connection says nothing about the link, which is still good.
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
      const acceptance = await this.links.accept(this.token, this.password());
      this.sessionsEnded.set(acceptance.sessionsEnded);
      // Local only: `logout()` would revoke a refresh token that can belong to whoever else uses this device.
      this.auth.forgetLocalSession();
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

/** The identity provider names the rule a password broke; the courier is told which, in their language. */
function messageFor(failure: unknown): MessageKey {
  if (failure instanceof ApiError) {
    if (failure.code === ApiErrorCode.VALIDATION_FAILED) {
      const policy = String(failure.problem?.['policy'] ?? '');
      return policy.includes('MinLength') || policy.includes('length')
        ? 'courierAccess.policy.length'
        : 'courierAccess.policy.other';
    }
    if (failure.code === ApiErrorCode.RATE_LIMIT_EXCEEDED) {
      return 'error.RATE_LIMIT_EXCEEDED';
    }
    if (failure.code === ApiErrorCode.NETWORK_UNREACHABLE) {
      return 'error.NETWORK_UNREACHABLE';
    }
  }
  return 'courierAccess.failed';
}

/** A language this build has no catalogue for opens in the default. */
function localeFor(language: string): Locale {
  return isLocale(language) ? language : DEFAULT_LOCALE;
}
