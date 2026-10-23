import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CourierAccessKind, CourierAccessState, CouriersApi } from './couriers-api';

const STATE_LABELS: Readonly<Record<string, MessageKey>> = {
  NONE: 'couriers.access.state.NONE',
  QUEUED: 'couriers.access.state.QUEUED',
  SENT: 'couriers.access.state.SENT',
  EXPIRED: 'couriers.access.state.EXPIRED',
  ACCEPTED: 'couriers.access.state.ACCEPTED',
  VOIDED: 'couriers.access.state.VOIDED',
  FAILED: 'couriers.access.state.FAILED',
};

const ERROR_LABELS: Readonly<Record<string, MessageKey>> = {
  SMS_NOT_CONNECTED: 'couriers.access.error.SMS_NOT_CONNECTED',
  NO_PHONE_NUMBER: 'couriers.access.error.NO_PHONE_NUMBER',
  SMS_REFUSED: 'couriers.access.error.SMS_REFUSED',
  SMS_PURPOSE_NOT_PERMITTED: 'couriers.access.error.SMS_PURPOSE_NOT_PERMITTED',
  SMS_UNAVAILABLE: 'couriers.access.error.SMS_UNAVAILABLE',
  IDENTITY_UNAVAILABLE: 'couriers.access.error.IDENTITY_UNAVAILABLE',
  COURIER_GONE: 'couriers.access.error.COURIER_GONE',
};

/**
 * The «App account» row of a courier's record: where their first sign-in stands, and the two buttons that move it
 * (couriers.md §6.1, §18; ADR 0178).
 *
 * **The console asks; it never holds a credential.** «Send invite» (a first sign-in) and «Reset access» queue a
 * text to the courier's phone with a one-time link to choose their own password. This card shows whether that
 * happened and, when it did not, why in a stable code the operator can act on: no gateway connected, no number on
 * file, an account not cleared for courier traffic. It never shows a phone number and never a link, because the
 * platform never sends either to a browser. The passport serial that used to be the legacy password is the
 * behaviour this exists to keep removed.
 */
@Component({
  selector: 'q-courier-access-card',
  imports: [TPipe],
  templateUrl: './courier-access-card.html',
  styleUrl: './courier-access-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CourierAccessCard {
  private readonly api = inject(CouriersApi);
  protected readonly i18n = inject(I18n);

  readonly tenantId = input.required<string>();
  readonly courierId = input.required<string>();
  /** False for an archived courier or one with no engagement: access is for someone who works here. */
  readonly eligible = input(true);

  protected readonly state = signal<CourierAccessState | null>(null);
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly stateLabel = computed<MessageKey>(
    () => STATE_LABELS[this.state()?.state ?? 'NONE'] ?? 'couriers.access.state.NONE',
  );

  protected readonly errorLabel = computed<MessageKey | null>(() => {
    const code = this.state()?.lastErrorCode;
    if (!code) {
      return null;
    }
    return ERROR_LABELS[code] ?? 'couriers.access.error.other';
  });

  /** The primary button follows the state: a courier who never set a password is invited, one who did is reset. */
  protected readonly invitePrimary = computed(() => this.state()?.state !== 'ACCEPTED');

  constructor() {
    effect(() => {
      const tenantId = this.tenantId();
      const courierId = this.courierId();
      void this.load(tenantId, courierId);
    });
  }

  private async load(tenantId: string, courierId: string): Promise<void> {
    this.state.set(null);
    this.notice.set(null);
    this.error.set(null);
    try {
      this.state.set(await this.api.accessState(tenantId, courierId));
    } catch (failure) {
      this.error.set(this.describe(failure));
    }
  }

  protected async send(kind: CourierAccessKind): Promise<void> {
    if (this.busy() || !this.eligible()) {
      return;
    }
    this.busy.set(true);
    this.notice.set(null);
    this.error.set(null);
    try {
      await this.api.invite(this.tenantId(), this.courierId(), kind, this.i18n.locale());
      this.notice.set(this.i18n.t('couriers.access.queued'));
      this.state.set(await this.api.accessState(this.tenantId(), this.courierId()));
    } catch (failure) {
      this.error.set(this.describe(failure));
    } finally {
      this.busy.set(false);
    }
  }

  private describe(failure: unknown): string {
    if (failure instanceof ApiError) {
      if (failure.code === ApiErrorCode.RATE_LIMIT_EXCEEDED) {
        const wait = Number(failure.problem?.['retryAfterSeconds'] ?? 60);
        return this.i18n.t('couriers.access.tooSoon', {
          seconds: Number.isFinite(wait) ? wait : 60,
        });
      }
      return describeApiError(failure, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
