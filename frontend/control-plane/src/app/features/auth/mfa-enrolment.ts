import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { MfaApi, MfaEnrolment } from '../../core/auth/mfa-api';
import { StaffSessionResponse } from '../../core/auth/staff-session';
import { I18nService } from '../../core/i18n/i18n.service';
import { OtpInput } from '../../shared/ui/otp-input';
import { QQrCode } from '../../shared/ui/qr-code';

/**
 * Enrolling an authenticator app from the page a refused sign-in sends an account to
 * (ADR 0148, Decision 3): `app-mfa-enrolment`. The control plane's counterpart of
 * `frontend/operations`' form of the same name, which also serves «Мой профиль» — this console
 * has no profile screen, so it has only the ticket path.
 *
 * Two steps, and the first is not a formality: **the password is asked again** before the
 * platform will hand over a secret, so a ticket found in a closed tab enrols nothing. The second
 * shows the secret as a QR code and as text — held in this component's memory and nowhere
 * else — and takes the first code in {@link OtpInput}; the platform registers the authenticator
 * with Keycloak, proves it with a real sign-in and deletes it again if the code does not verify,
 * so "nothing was set up" is a true sentence on a wrong code.
 */
@Component({
  selector: 'app-mfa-enrolment',
  imports: [OtpInput, QQrCode],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section data-testid="mfa-enrolment">
      <p class="q-body lead">{{ i18n.t('mfa.enrol.leadRequired') }}</p>

      @if (step() === 'password') {
        <form (submit)="begin($event)" data-testid="mfa-password-step">
          <p class="q-body-sm hint">{{ i18n.t('mfa.enrol.passwordStep') }}</p>
          <label class="q-caption field-label" for="mfa-password">{{
            i18n.t('mfa.enrol.password')
          }}</label>
          <input
            id="mfa-password"
            class="q-body field"
            type="password"
            autocomplete="current-password"
            [value]="password()"
            (input)="onPassword($event)"
            [disabled]="busy()"
          />
          @if (error(); as message) {
            <p class="q-body-sm error" role="alert">{{ message }}</p>
          }
          <button
            type="submit"
            class="q-body submit"
            [disabled]="busy() || password().length === 0"
          >
            {{ i18n.t('mfa.enrol.continue') }}
          </button>
        </form>
      } @else {
        <div data-testid="mfa-scan-step">
          <p class="q-body-sm hint">{{ i18n.t('mfa.enrol.scanStep') }}</p>
          @if (enrolment(); as current) {
            <div class="qr">
              <q-qr-code
                [value]="current.otpauthUri"
                [size]="176"
                [label]="i18n.t('mfa.enrol.qrLabel')"
                [tooLongText]="i18n.t('mfa.enrol.qrTooLong')"
              />
            </div>
            <p class="q-caption manual">{{ i18n.t('mfa.enrol.cantScan') }}</p>
            <code class="q-mono secret" data-testid="mfa-secret">{{ spacedSecret() }}</code>
          }

          <label class="q-caption field-label" for="mfa-label">{{
            i18n.t('mfa.enrol.label')
          }}</label>
          <input
            id="mfa-label"
            class="q-body field"
            type="text"
            maxlength="64"
            autocomplete="off"
            [placeholder]="i18n.t('mfa.enrol.labelPlaceholder')"
            [value]="label()"
            (input)="onLabel($event)"
            [disabled]="busy()"
          />

          <q-otp-input
            class="otp"
            [value]="code()"
            [disabled]="busy()"
            [ariaLabel]="i18n.t('mfa.enrol.group')"
            [cellLabelPrefix]="i18n.t('mfa.enrol.digit')"
            (valueChange)="onCode($event)"
            (complete)="confirm($event)"
          />
          @if (error(); as message) {
            <p class="q-body-sm error" role="alert">{{ message }}</p>
          }
          <button
            type="button"
            class="q-body submit"
            [disabled]="busy() || code().length !== 6"
            (click)="confirm()"
          >
            {{ i18n.t(busy() ? 'mfa.enrol.confirming' : 'mfa.enrol.confirm') }}
          </button>
        </div>
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
    }

    .lead {
      margin: 0 0 16px;
      color: var(--q-ink);
    }

    .hint {
      margin: 0 0 12px;
      color: var(--q-ink-muted);
    }

    .field-label {
      display: block;
      margin-top: 16px;
      color: var(--q-ink-muted);
    }

    .field {
      display: block;
      width: 100%;
      box-sizing: border-box;
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

    .qr {
      display: flex;
      justify-content: center;
      padding: 12px;
      background: var(--q-canvas);
      border: 1px solid var(--q-hairline);
      border-radius: var(--q-radius);
    }

    .manual {
      margin: 12px 0 4px;
      color: var(--q-ink-muted);
    }

    .secret {
      display: block;
      padding: 8px 12px;
      background: var(--q-surface-1);
      border-radius: var(--q-radius);
      color: var(--q-ink);
      word-break: break-all;
      user-select: all;
    }

    .otp {
      display: block;
      margin-top: 20px;
    }

    .error {
      margin: 16px 0 0;
      color: var(--q-error-text);
      background: var(--q-error-tint);
      padding: 8px 12px;
      border-radius: var(--q-radius);
    }

    .submit {
      display: block;
      width: 100%;
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
export class MfaEnrolmentForm {
  private readonly api = inject(MfaApi);
  protected readonly i18n = inject(I18nService);

  /** The enrolment ticket a refused sign-in answered with. */
  readonly ticket = input.required<string>();

  /** The new session: confirming from a ticket answers with one. */
  readonly completed = output<StaffSessionResponse | null>();

  protected readonly step = signal<'password' | 'scan'>('password');
  protected readonly password = signal('');
  protected readonly label = signal('');
  protected readonly code = signal('');
  protected readonly enrolment = signal<MfaEnrolment | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  /** The secret in groups of four: a key typed by hand is read off a screen, and groups survive that. */
  protected readonly spacedSecret = computed(() =>
    (this.enrolment()?.secret ?? '').replace(/(.{4})/g, '$1 ').trim(),
  );

  protected onPassword(event: Event): void {
    this.password.set((event.target as HTMLInputElement).value);
    this.error.set(null);
  }

  protected onLabel(event: Event): void {
    this.label.set((event.target as HTMLInputElement).value);
  }

  protected onCode(value: string): void {
    this.code.set(value);
    this.error.set(null);
  }

  protected async begin(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy() || this.password().length === 0) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      this.enrolment.set(await this.api.begin(this.password(), this.ticket()));
      this.step.set('scan');
    } catch (failure) {
      this.error.set(this.messageFor(failure));
    } finally {
      this.busy.set(false);
    }
  }

  protected async confirm(code: string = this.code()): Promise<void> {
    const enrolment = this.enrolment();
    if (this.busy() || enrolment === null || code.length !== 6) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      const session = await this.api.confirm({
        sealedSecret: enrolment.sealedSecret,
        code,
        password: this.password(),
        label: this.label(),
        enrolmentTicket: this.ticket(),
      });
      // The secret and the password leave this component's memory the moment they have done their job.
      this.enrolment.set(null);
      this.password.set('');
      this.code.set('');
      this.completed.emit(session);
    } catch (failure) {
      this.code.set('');
      if (failure instanceof ApiError && failure.code === 'INVALID_REQUEST') {
        // The sealed secret is spent or expired: the same password step starts a fresh one.
        this.enrolment.set(null);
        this.step.set('password');
      }
      this.error.set(this.messageFor(failure));
    } finally {
      this.busy.set(false);
    }
  }

  private messageFor(failure: unknown): string {
    if (!(failure instanceof ApiError)) {
      return this.i18n.t('mfa.enrol.failed');
    }
    switch (failure.code) {
      case 'CURRENT_PASSWORD_INVALID':
        return this.i18n.t('mfa.enrol.wrongPassword');
      case 'MFA_CONFIRMATION_CODE_INVALID':
        return this.i18n.t('mfa.enrol.wrongCode');
      case 'INVALID_REQUEST':
        return this.i18n.t('mfa.enrol.expired');
      case 'RESOURCE_CONFLICT':
        return this.i18n.t('mfa.enrol.full');
      case 'RATE_LIMIT_EXCEEDED':
        return this.i18n.t('mfa.enrol.rateLimited', { minutes: minutesOf(failure) });
      default:
        return this.i18n.t('mfa.enrol.failed');
    }
  }
}

/** `retryAfterSeconds` rounded up to whole minutes, at least one. */
export function minutesOf(failure: ApiError): number {
  const seconds = failure.problem['retryAfterSeconds'];
  return typeof seconds === 'number' && seconds > 0 ? Math.max(1, Math.ceil(seconds / 60)) : 1;
}
