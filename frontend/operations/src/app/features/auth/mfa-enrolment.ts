import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { MfaApi, MfaEnrolment } from '../../core/auth/mfa-api';
import { StaffSessionResponse } from '../../core/auth/staff-session';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { OtpInput } from '../../shared/ui/otp-input';
import { QQrCode } from '../../shared/ui/qr-code';

/**
 * Enrolling an authenticator app (ADR 0148, Decision 3): `q-mfa-enrolment`.
 *
 * One component for the two places a person does it. From a **ticket**, on the page a refused
 * sign-in sends them to, because the account must hold a factor and has none; and from a
 * **session**, on «Мой профиль», to protect themselves or to add a second device. The
 * difference is only where the credential comes from — the ticket, or the bearer the interceptor
 * attaches — and what happens afterwards ({@link completed} carries the new session for the
 * first, and `null` for the second).
 *
 * Two steps, and the first is not a formality. The **password is asked again** before the
 * platform will hand over a secret, so a session left open on a shared terminal, or a ticket
 * found in a closed tab, enrols nothing. The second step shows the secret as a QR code and as
 * text — held in this component's memory and nowhere else, never a URL, a store or a log — and
 * takes the first code in {@link OtpInput}; the platform registers the authenticator with
 * Keycloak and proves it with a real sign-in, and deletes it again if the code does not verify.
 * So **"nothing was set up" is a true sentence** on a wrong code, and the person can try again.
 */
@Component({
  selector: 'q-mfa-enrolment',
  imports: [TPipe, OtpInput, QQrCode],
  templateUrl: './mfa-enrolment.html',
  styleUrl: './mfa-enrolment.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MfaEnrolmentForm {
  private readonly api = inject(MfaApi);
  private readonly i18n = inject(I18n);

  /** The enrolment ticket, when the person has no session; `null` signed in. */
  readonly ticket = input<string | null>(null);
  /** Whether to say the account is required to do this (the ticket case), or simply offer it. */
  readonly required = input(false);

  /** The new session when the enrolment began from a ticket, `null` from a session. */
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
      if (failure instanceof ApiError && failure.code === ApiErrorCode.INVALID_REQUEST) {
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
      case ApiErrorCode.CURRENT_PASSWORD_INVALID:
        return this.i18n.t('mfa.enrol.wrongPassword');
      case ApiErrorCode.MFA_CONFIRMATION_CODE_INVALID:
        return this.i18n.t('mfa.enrol.wrongCode');
      case ApiErrorCode.INVALID_REQUEST:
        return this.i18n.t('mfa.enrol.expired');
      case ApiErrorCode.RESOURCE_CONFLICT:
        return this.i18n.t('mfa.enrol.full');
      case ApiErrorCode.RATE_LIMIT_EXCEEDED:
        return this.i18n.t('mfa.enrol.rateLimited', { minutes: minutesOf(failure) });
      default:
        return this.i18n.t('mfa.enrol.failed');
    }
  }
}

/** `retryAfterSeconds` rounded up to whole minutes, at least one. */
export function minutesOf(failure: ApiError): number {
  const seconds = failure.problem?.['retryAfterSeconds'];
  return typeof seconds === 'number' && seconds > 0 ? Math.max(1, Math.ceil(seconds / 60)) : 1;
}
