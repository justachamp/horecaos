import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from './order-errors';
import { ChallengeState, OrderHandoverApi } from './order-handover-api';

const STATUS_LABEL_KEYS: Readonly<Record<ChallengeState['status'], MessageKey>> = {
  PENDING: 'orders.detail.handover.status.PENDING',
  VERIFIED: 'orders.detail.handover.status.VERIFIED',
  BYPASSED: 'orders.detail.handover.status.BYPASSED',
  FAILED: 'orders.detail.handover.status.FAILED',
  EXPIRED: 'orders.detail.handover.status.EXPIRED',
};

const TYPE_LABEL_KEYS: Readonly<Record<ChallengeState['type'], MessageKey>> = {
  CODE: 'orders.detail.handover.type.CODE',
  QR: 'orders.detail.handover.type.QR',
  SIGNATURE: 'orders.detail.handover.type.SIGNATURE',
  NONE: 'orders.detail.handover.type.NONE',
};

/**
 * Код выдачи — the handover-code state (orders.md §3.8, row `1.2m`, wave
 * P09). A courier or an aggregator rider proves custody server-side; this
 * panel is the console's only view of what {@code
 * ordering.order_handover_challenges} decided, and its own capability-gated
 * verify/bypass actions over the two endpoints ADR 0040 already built —
 * `MarketplaceOperationsController.verify`/`bypass` — plus the read
 * projection this wave adds.
 *
 * A `null` challenge (never issued — a fulfilment path with no handover proof
 * configured) renders nothing but a quiet caption, the same "absence has an
 * honest answer" rule the money and timeline bands already follow.
 *
 * **Never shows the expected value.** Nothing here could: the server's own
 * doc on every handover response says so, and this panel only ever renders
 * what it is given.
 */
@Component({
  selector: 'q-order-handover-panel',
  imports: [TPipe],
  templateUrl: './order-handover-panel.html',
  styleUrl: './order-handover-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderHandoverPanel {
  private readonly api = inject(OrderHandoverApi);
  private readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly orderId = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly challenge = signal<ChallengeState | null>(null);
  protected readonly loadError = signal(false);

  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly code = signal('');
  protected readonly bypassOpen = signal(false);
  protected readonly bypassReasonCode = signal('');
  protected readonly supervisorName = signal('');

  protected readonly statusLabel = computed(() => {
    const current = this.challenge();
    return current ? this.i18n.t(STATUS_LABEL_KEYS[current.status]) : null;
  });

  protected readonly typeLabel = computed(() => {
    const current = this.challenge();
    return current ? this.i18n.t(TYPE_LABEL_KEYS[current.type]) : null;
  });

  protected readonly canVerify = computed(() => this.challenge()?.status === 'PENDING');

  /** «Available after attempts are exhausted as well as before it» — the server's own doc on {@code bypass}. */
  protected readonly canBypass = computed(() => {
    const status = this.challenge()?.status;
    return status === 'PENDING' || status === 'FAILED';
  });

  constructor() {
    effect(() => {
      const orderId = this.orderId();
      const scope = this.scope();
      void this.load(scope, orderId);
    });
  }

  private async load(scope: LocationScope, orderId: string): Promise<void> {
    this.loading.set(true);
    this.loadError.set(false);
    try {
      this.challenge.set(await firstValueFrom(this.api.challenge(scope, orderId)));
    } catch (error) {
      if (error instanceof ApiError) {
        this.loadError.set(true);
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }

  protected setCode(value: string): void {
    this.code.set(value);
  }

  protected async submitVerification(): Promise<void> {
    const code = this.code().trim();
    if (!code) {
      return;
    }
    this.busy.set(true);
    this.notice.set(null);
    try {
      const result = await firstValueFrom(this.api.verify(this.scope(), this.orderId(), code));
      this.code.set('');
      this.challenge.update((current) =>
        current
          ? { ...current, status: result.status, attemptsRemaining: result.attemptsRemaining }
          : current,
      );
      this.notice.set(
        result.verified
          ? this.i18n.t('orders.detail.handover.verified')
          : this.i18n.t('orders.detail.handover.wrongCode', {
              attemptsRemaining: result.attemptsRemaining,
            }),
      );
    } catch (error) {
      this.handleError(error);
    } finally {
      this.busy.set(false);
    }
  }

  protected toggleBypass(): void {
    this.bypassOpen.update((open) => !open);
  }

  protected setBypassReasonCode(value: string): void {
    this.bypassReasonCode.set(value);
  }

  protected setSupervisorName(value: string): void {
    this.supervisorName.set(value);
  }

  protected async submitBypass(): Promise<void> {
    const reasonCode = this.bypassReasonCode().trim();
    const supervisorName = this.supervisorName().trim();
    if (!reasonCode || !supervisorName) {
      return;
    }
    this.busy.set(true);
    this.notice.set(null);
    try {
      await firstValueFrom(
        this.api.bypass(this.scope(), this.orderId(), reasonCode, supervisorName),
      );
      this.bypassOpen.set(false);
      this.bypassReasonCode.set('');
      this.supervisorName.set('');
      await this.load(this.scope(), this.orderId());
    } catch (error) {
      this.handleError(error);
    } finally {
      this.busy.set(false);
    }
  }

  private handleError(error: unknown): void {
    if (error instanceof ApiError) {
      this.notice.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
    } else {
      throw error;
    }
  }
}
