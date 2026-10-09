import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { MemberMfa, StaffMembersApi } from './staff-members-api';

/**
 * «Способ входа» on the person card's Безопасность tab, and the reset of a lost second factor
 * (ADR 0148, Decision 5 and 6; staff-and-access.md §3 Tab 4 and §11.9).
 *
 * What it shows is Keycloak's own credential list for the person — whether they hold an
 * authenticator and when each was added — read through `GET .../mfa` and never copied into a
 * table of the platform's. A person Keycloak has no account for yet (an invitation not
 * accepted) shows that, and one it could not answer for shows "not known right now" rather than
 * a confident "password only".
 *
 * **The reset is an administrator's act, with a reason, and never a link.** It removes every
 * authenticator, ends the person's sessions, is written to the activity log and emailed to the
 * person. The three refusals the platform makes — your own, a platform account (reset from the
 * control plane with a second signature) and a tenant owner (reset by platform support) — are
 * said in words, not left as a generic failure.
 */
@Component({
  selector: 'q-staff-mfa-panel',
  imports: [TPipe],
  templateUrl: './staff-mfa-panel.html',
  styleUrl: './staff-mfa-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffMfaPanel implements OnInit {
  private readonly api = inject(StaffMembersApi);
  private readonly capabilities = inject(SessionCapabilities);
  protected readonly i18n = inject(I18n);

  readonly tenantId = input.required<string>();
  readonly memberId = input.required<string>();
  /** The member's version, as the card read it: the reset's `If-Match`. */
  readonly memberVersion = input.required<number>();
  /** Whether the person has signed in once: an invitation not yet accepted has no account to ask about. */
  readonly isOwnAccount = input(false);

  protected readonly canRead = computed(() => this.capabilities.has('IAM_STAFF_MFA_READ'));
  protected readonly canReset = computed(
    () => this.capabilities.has('IAM_STAFF_MFA_RESET') && !this.isOwnAccount(),
  );

  /** The words for each requirement: literal keys, so a missing translation is a compile error. */
  protected readonly requirementKey: Readonly<
    Record<'REQUIRED' | 'OFFERED' | 'NOT_REQUIRED', MessageKey>
  > = {
    REQUIRED: 'staff.mfa.requirement.REQUIRED',
    OFFERED: 'staff.mfa.requirement.OFFERED',
    NOT_REQUIRED: 'staff.mfa.requirement.NOT_REQUIRED',
  };

  protected readonly state = signal<'loading' | 'ready' | 'unknown' | 'noAccount'>('loading');
  protected readonly mfa = signal<MemberMfa | null>(null);
  protected readonly resetOpen = signal(false);
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    if (!this.canRead()) {
      return;
    }
    this.state.set('loading');
    try {
      this.mfa.set(await this.api.mfa(this.tenantId(), this.memberId()));
      this.state.set('ready');
    } catch (failure) {
      this.state.set(
        failure instanceof ApiError && failure.code === ApiErrorCode.RESOURCE_NOT_FOUND
          ? 'noAccount'
          : 'unknown',
      );
    }
  }

  protected openReset(): void {
    this.reason.set('');
    this.error.set(null);
    this.notice.set(null);
    this.resetOpen.set(true);
  }

  protected closeReset(): void {
    this.resetOpen.set(false);
  }

  protected onReason(event: Event): void {
    this.reason.set((event.target as HTMLTextAreaElement).value);
    this.error.set(null);
  }

  protected async confirmReset(): Promise<void> {
    const reason = this.reason().trim();
    if (reason === '' || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.api.resetMfa(this.tenantId(), this.memberId(), reason, this.memberVersion());
      this.resetOpen.set(false);
      this.notice.set(this.i18n.t('staff.mfa.resetDone'));
      await this.load();
    } catch (failure) {
      this.error.set(this.describe(failure));
    } finally {
      this.busy.set(false);
    }
  }

  private describe(failure: unknown): string {
    if (failure instanceof ApiError) {
      if (failure.code === ApiErrorCode.STALE_VERSION) {
        return this.i18n.t('staff.mfa.resetStale');
      }
      if (failure.code === ApiErrorCode.INSUFFICIENT_CAPABILITY && failure.status === 403) {
        const detail = failure.problem?.detail ?? '';
        if (detail.includes('owner')) {
          return this.i18n.t('staff.mfa.resetOwner');
        }
      }
      if (failure.code === ApiErrorCode.UNPROCESSABLE_STATE) {
        const detail = failure.problem?.detail ?? '';
        return detail.includes('platform account')
          ? this.i18n.t('staff.mfa.resetPlatform')
          : this.i18n.t('staff.mfa.resetSelf');
      }
    }
    return this.i18n.t('staff.mfa.resetFailed');
  }
}
