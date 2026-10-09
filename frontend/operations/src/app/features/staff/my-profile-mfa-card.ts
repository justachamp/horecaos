import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { MfaApi, MfaAuthenticator, OwnMfa } from '../../core/auth/mfa-api';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { MfaEnrolmentForm, minutesOf } from '../auth/mfa-enrolment';
import { OtpInput } from '../../shared/ui/otp-input';

/**
 * «Вход в два шага» on «Мой профиль» (ADR 0148, Decision 3 and 5): the authenticators a person
 * holds, turning the factor on, adding a second device and removing one.
 *
 * What it reads is Keycloak's own credential list, through `GET .../auth/mfa/authenticators`:
 * an id, the name the person typed and a date. No secret ever comes back, because the platform
 * holds none. Everything that changes anything re-proves the password, and removing an
 * authenticator also needs a current code — and is refused for the last one, where the only
 * honest instruction is the one printed under the list: a lost phone is an administrator's
 * reset, never a button, and never a link.
 */
@Component({
  selector: 'q-my-profile-mfa-card',
  imports: [TPipe, MfaEnrolmentForm, OtpInput],
  templateUrl: './my-profile-mfa-card.html',
  styleUrl: './my-profile-mfa-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MyProfileMfaCard {
  private readonly api = inject(MfaApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly mfa = signal<OwnMfa | null>(null);
  protected readonly enrolling = signal(false);
  protected readonly notice = signal<string | null>(null);

  /** The authenticator whose removal form is open, if any. */
  protected readonly removing = signal<string | null>(null);
  protected readonly removePassword = signal('');
  protected readonly removeCode = signal('');
  protected readonly removeBusy = signal(false);
  protected readonly removeError = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.mfa.set(await this.api.own());
      this.state.set('ready');
    } catch {
      this.state.set('error');
    }
  }

  protected startEnrolling(): void {
    this.notice.set(null);
    this.enrolling.set(true);
  }

  protected stopEnrolling(): void {
    this.enrolling.set(false);
  }

  protected async onEnrolled(): Promise<void> {
    this.enrolling.set(false);
    this.notice.set(this.i18n.t('staff.myProfile.mfa.done'));
    await this.load();
  }

  protected startRemoving(authenticator: MfaAuthenticator): void {
    this.notice.set(null);
    this.removeError.set(null);
    this.removePassword.set('');
    this.removeCode.set('');
    this.removing.set(authenticator.id);
  }

  protected cancelRemoving(): void {
    this.removing.set(null);
  }

  protected onRemovePassword(event: Event): void {
    this.removePassword.set((event.target as HTMLInputElement).value);
    this.removeError.set(null);
  }

  protected onRemoveCode(value: string): void {
    this.removeCode.set(value);
    this.removeError.set(null);
  }

  protected async confirmRemoving(): Promise<void> {
    const id = this.removing();
    if (
      id === null ||
      this.removeBusy() ||
      this.removeCode().length !== 6 ||
      this.removePassword() === ''
    ) {
      return;
    }
    this.removeBusy.set(true);
    this.removeError.set(null);
    try {
      await this.api.remove(id, this.removePassword(), this.removeCode());
      this.removing.set(null);
      this.removePassword.set('');
      this.removeCode.set('');
      this.notice.set(this.i18n.t('staff.myProfile.mfa.removed'));
      await this.load();
    } catch (failure) {
      this.removeCode.set('');
      this.removeError.set(this.describeRemoval(failure));
    } finally {
      this.removeBusy.set(false);
    }
  }

  protected addedOn(authenticator: MfaAuthenticator): string | null {
    if (authenticator.createdAt === null) {
      return null;
    }
    const when = new Date(authenticator.createdAt);
    if (Number.isNaN(when.getTime())) {
      return null;
    }
    const date = new Intl.DateTimeFormat(this.i18n.locale(), { dateStyle: 'medium' }).format(when);
    return this.i18n.t('staff.myProfile.mfa.added', { date });
  }

  private describeRemoval(failure: unknown): string {
    if (failure instanceof ApiError) {
      switch (failure.code) {
        case ApiErrorCode.CURRENT_PASSWORD_INVALID:
          return this.i18n.t('staff.myProfile.mfa.wrongPassword');
        case ApiErrorCode.MFA_CONFIRMATION_CODE_INVALID:
          return this.i18n.t('staff.myProfile.mfa.wrongCode');
        case ApiErrorCode.UNPROCESSABLE_STATE:
          return this.i18n.t('staff.myProfile.mfa.onlyOne');
        case ApiErrorCode.RATE_LIMIT_EXCEEDED:
          return this.i18n.t('staff.myProfile.mfa.rateLimited', { minutes: minutesOf(failure) });
        default:
          break;
      }
    }
    return this.i18n.t('staff.myProfile.mfa.failed');
  }
}
