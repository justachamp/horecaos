import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ApiError } from '../../../core/api/problem-details';
import { describeApiError } from '../../orders/order-errors';
import {
  CourierFinanceApi,
  CourierLedgerView,
  PayoutMethod,
  SettlementPeriodView,
} from '../courier-finance-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

const STATUS_KEYS: Readonly<Record<SettlementPeriodView['status'], MessageKey>> = {
  OPEN: 'finance.payouts.periodStatus.OPEN',
  CLOSING: 'finance.payouts.periodStatus.CLOSING',
  CLOSED: 'finance.payouts.periodStatus.CLOSED',
  SETTLED: 'finance.payouts.periodStatus.SETTLED',
};

/**
 * 8.5 Courier payouts (`frontend-information-architecture.md` §8.5) —
 * tier 2. "The settlement run — salary report … with an exact, immutable
 * on-time definition; the courier balance as an append-only ledger."
 *
 * Reads `JdbcCourierLedgerStore.listPeriods` (new this wave) for the
 * fleet-wide worklist, and calls the closing/payout actions ADR 0042 already
 * built (`CourierSettlementService.close`/`.authorisePayout`) — this screen
 * is their first console. `К оплате` (`amountPayableMinor`) is read back
 * from the stored period row, never recomputed here, matching the
 * `courier_settlement_periods.ck_period_payable` constraint's own
 * discipline: two screens computing the same figure independently is
 * precisely how they come to disagree.
 *
 * A per-courier ledger lookup (`GET .../couriers/{id}/ledger`, built before
 * this wave) is offered below the worklist for the one case the worklist
 * cannot answer on its own — "why is this figure what it is" — the same
 * "order lookup beside the worklist" shape `payments-page.ts` uses for 8.1.
 */
@Component({
  selector: 'q-courier-payouts-page',
  imports: [TPipe],
  templateUrl: './courier-payouts-page.html',
  styleUrl: './courier-payouts-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CourierPayoutsPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(CourierFinanceApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly periods = signal<readonly SettlementPeriodView[]>([]);
  protected readonly loadErrorText = signal<string | null>(null);

  protected readonly actingPeriodId = signal<string | null>(null);
  protected readonly actionKind = signal<'close' | 'payout' | null>(null);
  protected readonly actionReason = signal('');
  protected readonly payoutMethod = signal<PayoutMethod>('CASH_AT_BRANCH');
  protected readonly actionBusy = signal(false);
  protected readonly actionError = signal<string | null>(null);

  protected readonly courierIdInput = signal('');
  protected readonly ledgerLoading = signal(false);
  protected readonly ledgerError = signal<string | null>(null);
  protected readonly ledger = signal<CourierLedgerView | null>(null);

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
      this.periods.set(await this.api.settlementPeriods(tenantId));
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

  protected startClose(period: SettlementPeriodView): void {
    this.actingPeriodId.set(period.periodId);
    this.actionKind.set('close');
    this.actionReason.set('');
    this.actionError.set(null);
  }

  protected startPayout(period: SettlementPeriodView): void {
    this.actingPeriodId.set(period.periodId);
    this.actionKind.set('payout');
    this.payoutMethod.set('CASH_AT_BRANCH');
    this.actionReason.set('');
    this.actionError.set(null);
  }

  protected cancelAction(): void {
    this.actingPeriodId.set(null);
    this.actionKind.set(null);
  }

  protected canSubmitAction(): boolean {
    return !this.actionBusy() && this.actionReason().trim().length > 0;
  }

  protected async submitAction(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const periodId = this.actingPeriodId();
    const kind = this.actionKind();
    if (!tenantId || !periodId || !kind || !this.canSubmitAction()) {
      return;
    }
    this.actionBusy.set(true);
    this.actionError.set(null);
    try {
      if (kind === 'close') {
        await this.api.closeSettlementPeriod(tenantId, periodId, this.actionReason().trim());
      } else {
        await this.api.authorisePayout(tenantId, periodId, this.payoutMethod(), this.actionReason().trim());
      }
      this.actingPeriodId.set(null);
      this.actionKind.set(null);
      await this.load();
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.actionBusy.set(false);
    }
  }

  protected async lookupLedger(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const courierId = this.courierIdInput().trim();
    if (!tenantId || !courierId) {
      return;
    }
    this.ledgerLoading.set(true);
    this.ledgerError.set(null);
    this.ledger.set(null);
    try {
      this.ledger.set(await this.api.courierLedger(tenantId, courierId));
    } catch (error) {
      this.ledgerError.set(this.describe(error));
    } finally {
      this.ledgerLoading.set(false);
    }
  }

  protected money(amountMinor: number, currency: string): string {
    return formatMoney({ amountMinor, currency }, this.i18n.locale(), { withUnit: true });
  }

  protected statusLabel(status: SettlementPeriodView['status']): string {
    return this.i18n.t(STATUS_KEYS[status]);
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
