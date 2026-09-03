import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ApiError } from '../../../core/api/problem-details';
import { describeApiError } from '../../orders/order-errors';
import { CashHandoverView, CourierFinanceApi } from '../courier-finance-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

const STATUS_KEYS: Readonly<Record<CashHandoverView['status'], MessageKey>> = {
  PENDING: 'finance.cash.status.PENDING',
  DECLARED: 'finance.cash.status.DECLARED',
  CONFIRMED: 'finance.cash.status.CONFIRMED',
  VARIANCE_RAISED: 'finance.cash.status.VARIANCE_RAISED',
  OVERRIDDEN: 'finance.cash.status.OVERRIDDEN',
};

/**
 * 8.3 Cash reconciliation (`frontend-information-architecture.md` §8.3) —
 * tier 2. "Courier daily/shift totals by payment method; cash acceptance and
 * shortfall recording — HorecaOS addition, Delever's courier hands in a
 * report with nowhere to record it."
 *
 * Reads `OperationsCourierController.cashHandovers` (new this wave, over the
 * ADR 0042 `fulfillment.courier_cash_handovers` table that already existed) —
 * every courier's cash handover across the fleet, worst-first. Confirming one
 * is the same `confirm` action §2's cash-declaration flow already uses;
 * `COURIER_CASH_READ` is a new, separate capability from `COURIER_CASH_CONFIRM`
 * (`Capability.java`), so a finance operator who can see this worklist may not
 * always be the person who may act on a row — the confirm button is offered
 * regardless and a refusal renders the shared denied state, the same
 * no-client-side-gate convention `payments-page.ts` documents.
 */
@Component({
  selector: 'q-cash-reconciliation-page',
  imports: [TPipe],
  templateUrl: './cash-reconciliation-page.html',
  styleUrl: './cash-reconciliation-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CashReconciliationPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(CourierFinanceApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly handovers = signal<readonly CashHandoverView[]>([]);
  protected readonly loadErrorText = signal<string | null>(null);

  protected readonly confirmingId = signal<string | null>(null);
  protected readonly confirmAmount = signal('');
  protected readonly confirmReason = signal('');
  protected readonly confirmBusy = signal(false);
  protected readonly confirmError = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    try {
      this.handovers.set(await this.api.cashHandovers(tenantId));
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else {
        this.loadErrorText.set(this.describe(error));
        this.state.set('error');
      }
    }
  }

  protected startConfirm(handover: CashHandoverView): void {
    this.confirmingId.set(handover.handoverId);
    this.confirmAmount.set(
      handover.declaredMinor !== null ? String(handover.declaredMinor) : String(handover.expectedMinor),
    );
    this.confirmReason.set('');
    this.confirmError.set(null);
  }

  protected cancelConfirm(): void {
    this.confirmingId.set(null);
  }

  protected canConfirm(): boolean {
    const amount = Number(this.confirmAmount());
    return !this.confirmBusy() && Number.isInteger(amount) && amount >= 0;
  }

  protected async submitConfirm(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const handoverId = this.confirmingId();
    if (!tenantId || !handoverId || !this.canConfirm()) {
      return;
    }
    this.confirmBusy.set(true);
    this.confirmError.set(null);
    try {
      await this.api.confirmCash(tenantId, handoverId, {
        confirmedMinor: Number(this.confirmAmount()),
        reason: this.confirmReason().trim() || this.i18n.t('finance.cash.confirm.defaultReason'),
      });
      this.confirmingId.set(null);
      await this.load();
    } catch (error) {
      this.confirmError.set(this.describe(error));
    } finally {
      this.confirmBusy.set(false);
    }
  }

  protected money(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }

  protected statusLabel(status: CashHandoverView['status']): string {
    return this.i18n.t(STATUS_KEYS[status]);
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
