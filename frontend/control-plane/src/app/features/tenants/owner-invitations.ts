import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import {
  InvitationLocale,
  OwnerInvitationFilter,
  OwnerInvitationOverviewRow,
  TenantsApi,
} from './tenants-api';

/** The filters the screen offers, in the order an operator works through them. */
const FILTERS: readonly OwnerInvitationFilter[] = [
  'OUTSTANDING',
  '',
  'NONE',
  'FAILED',
  'EXPIRED',
  'QUEUED',
  'SENT',
  'ACCEPTED',
];

/**
 * IA 2.9 Owner invitations -- onboarding's last mile across every tenant
 * (ADR 0100).
 *
 * The question this screen exists to answer is "which tenants have an owner
 * who never set up an account", and it defaults to exactly that: OUTSTANDING,
 * everything neither accepted nor unnecessary, most urgent first. A tenant
 * whose owner was linked before invitations existed shows in state NONE with
 * no times at all -- it is the one case a list built from invitation rows
 * alone would not contain, and the one most likely to be waiting.
 *
 * The recipient column shows the address whole when the server sends it,
 * which it does only for `TENANT_ONBOARDING_MANAGE` and records when it does.
 * Everyone else sees the mask. The component makes no decision about that; it
 * renders whichever of the two the server chose to send.
 */
@Component({
  selector: 'app-owner-invitations',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  templateUrl: './owner-invitations.html',
  styleUrl: './owner-invitations.css',
})
export class OwnerInvitations {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly tenantsApi = inject(TenantsApi);

  protected readonly filters = FILTERS;
  protected readonly invitationLocales: readonly InvitationLocale[] = ['uz', 'ru', 'en'];

  protected readonly filter = signal<OwnerInvitationFilter>('OUTSTANDING');
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly rows = signal<readonly OwnerInvitationOverviewRow[]>([]);

  /** The tenant whose resend form is open, and what has been typed into it. */
  protected readonly resending = signal<string | null>(null);
  protected readonly resendReason = signal('');
  protected readonly resendLocale = signal<InvitationLocale | ''>('');
  protected readonly resendSubmitting = signal(false);
  protected readonly resendError = signal<string | null>(null);
  protected readonly resent = signal<string | null>(null);

  /** How many tenants on this list still have an owner to chase. */
  protected readonly outstanding = computed(
    () =>
      this.rows().filter((row) => row.state !== 'ACCEPTED' && row.state !== 'NOT_NEEDED').length,
  );

  constructor() {
    void this.load();
  }

  protected async choose(filter: OwnerInvitationFilter): Promise<void> {
    this.filter.set(filter);
    this.closeResend();
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.rows.set(await this.tenantsApi.ownerInvitations(this.filter()));
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
      this.rows.set([]);
    } finally {
      this.loading.set(false);
    }
  }

  protected filterKey(filter: OwnerInvitationFilter): MessageKey {
    return filter === ''
      ? 'ownerInvitations.filter.ALL'
      : (`ownerInvitations.filter.${filter}` as MessageKey);
  }

  protected stateKey(state: string): MessageKey {
    return state === 'NONE'
      ? 'ownerInvitations.state.NONE'
      : (`onboarding.invitation.state.${state}` as MessageKey);
  }

  /** The whole address when the server sent one, the mask when it did not, a dash when neither. */
  protected recipientOf(row: OwnerInvitationOverviewRow): string {
    return row.recipient ?? row.emailMasked ?? '—';
  }

  protected when(instant: string | null): string {
    return instant === null ? '—' : this.i18n.dateTime(asDate(instant));
  }

  /** A resend is only offered where one would do something. */
  protected canResend(row: OwnerInvitationOverviewRow): boolean {
    return row.state !== 'ACCEPTED' && row.state !== 'NOT_NEEDED';
  }

  protected openResend(tenantId: string): void {
    this.resending.set(tenantId);
    this.resendReason.set('');
    this.resendLocale.set('');
    this.resendError.set(null);
    this.resent.set(null);
  }

  protected closeResend(): void {
    this.resending.set(null);
    this.resendError.set(null);
  }

  protected async submitResend(event: Event, row: OwnerInvitationOverviewRow): Promise<void> {
    event.preventDefault();
    const reason = this.resendReason().trim();
    if (reason.length === 0 || this.resendSubmitting()) {
      return;
    }
    this.resendSubmitting.set(true);
    this.resendError.set(null);
    try {
      await this.tenantsApi.resendOwnerInvitation(
        row.tenantId,
        reason,
        this.resendLocale() || undefined,
      );
      this.resending.set(null);
      this.resent.set(row.tenantId);
      await this.load();
    } catch (error) {
      this.resendError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.resendSubmitting.set(false);
    }
  }
}
