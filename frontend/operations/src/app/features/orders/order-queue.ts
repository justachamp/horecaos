import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatClock, formatTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { ServiceStatus } from '../../shell/service-status';
import {
  DecisionIdRegistry,
  OrderActionResponse,
  actionLabel,
  decisionOutcomeLabel,
  splitInlineOverflow,
} from './order-actions';
import { DecisionResponse, OrderActionsApi } from './order-actions-api';
import { CountableOrder, OrderCounts, TabCounts, zeroTabCounts } from './order-counts';
import { describeApiError, errorReference, mutationErrorNotice } from './order-errors';
import { OrderReasonDialog, OrderReasonSubmission } from './order-reason-dialog';
import {
  OrderRejectReasonDialog,
  OrderRejectSubmission,
  RejectReasonOption,
} from './order-reject-reason-dialog';
import { RejectReasonsApi } from './order-reject-reasons-api';
import {
  OrderSeverity,
  compareNewestFirst,
  compareOrderSeverity,
  computeOrderSeverity,
  formatSeverityCaption,
} from './order-severity';
import { orderStatusLabel } from './order-status';
import { OrderSummaryResponse } from './order-summary';
import {
  DEFAULT_ORDER_TAB,
  ORDER_TABS,
  ORDER_TAB_DEFINITIONS,
  OrderTabId,
  isOrderTabId,
  isOrderTabMember,
} from './order-tabs';

/** §1.6: poll every 10s while the tab is visible, until ADR 0045 live updates exist. */
const POLL_INTERVAL_MS = 10_000;

/**
 * The ceiling on one fetch. The endpoint's own maximum is 500
 * (`horecaos-api.json`); 200 is a first-render compromise between a complete
 * picture for the client-derived tab counts (`order-counts.ts`) and payload
 * size. A busier location than that needs the real `GET .../orders/counts`
 * endpoint, which is exactly the gap that endpoint exists to close.
 */
const FETCH_LIMIT = 200;

/**
 * No location carries a timezone anywhere this board can reach yet — not on
 * `OrderSummaryResponse`, not on `CurrentLocation`'s session-context read.
 * `docs/operations-spec/orders.md` §1.4 requires the *tenant's* zone, never
 * the browser's, so the fallback here is a fixed zone rather than
 * `Intl.DateTimeFormat().resolvedOptions().timeZone` — a wrong-but-consistent
 * clock across every operator's screen is a smaller failure than each
 * operator seeing a different one. HorecaOS operates in Uzbekistan today, so
 * `Asia/Tashkent` is the least-wrong constant available; replace this with
 * `tenant.locations.timezone` the moment a call surfaces it.
 */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

/** One row, decorated with what the table and the sort actually need. */
interface OrderRow {
  readonly order: OrderSummaryResponse;
  readonly createdAt: Date;
  readonly severity: OrderSeverity;
}

/** Which row opened the reason dialog, for which action, at which version. */
interface RowDialogState {
  readonly orderId: string;
  readonly kind: 'reject' | 'cancel';
  readonly version: number;
}

/**
 * The order queue — `docs/operations-spec/orders.md` §2: tabs, the dense
 * table, severity tint/rail/caption, and the §1.6 polling fallback.
 *
 * **Scope, deliberately.** This wave is rendering and liveness, not mutation:
 * no row actions, no bulk actions, no filters, no column picker. Those wait
 * on the server adding `actions[]` to the order response (§4.2) — rendering
 * an action this client invented availability for is exactly the mistake §4.2
 * warns against.
 *
 * **Columns, reduced to the wire.** `OrderSummaryResponse` — see
 * `order-summary.ts` — is short of §2.5's default set: no branch, no
 * customer, no line summary, no payment projection, no courier. What renders
 * here is severity rail, order number + severity caption, time, type/channel,
 * total, and status. `Филиал` is additionally out of place for a different
 * reason: this endpoint is already scoped to one location, which is the
 * spec's own condition for auto-hiding that column.
 */
@Component({
  selector: 'q-order-queue',
  imports: [TPipe, OrderReasonDialog, OrderRejectReasonDialog],
  templateUrl: './order-queue.html',
  styleUrl: './order-queue.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderQueue implements OnInit {
  private readonly api = inject(ApiClient);
  private readonly location = inject(CurrentLocation);
  private readonly counts = inject(OrderCounts);
  private readonly actionsApi = inject(OrderActionsApi);
  private readonly rejectReasonsApi = inject(RejectReasonsApi);
  private readonly serviceStatus = inject(ServiceStatus);
  private readonly i18n = inject(I18n);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly tabs = ORDER_TABS.map((id) => ORDER_TAB_DEFINITIONS[id]);

  protected readonly activeTab = signal<OrderTabId>(DEFAULT_ORDER_TAB);
  protected readonly tabCounts = signal<TabCounts>(zeroTabCounts());
  protected readonly rows = signal<readonly OrderRow[]>([]);
  protected readonly lastUpdatedAt = signal<Date | null>(null);
  protected readonly firstLoadComplete = signal(false);
  protected readonly refreshing = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly denied = signal(false);

  /** §2.9: inline actions rendered from `actions[]`, plus their busy/dialog/notice state. */
  protected readonly busyOrderIds = signal<ReadonlySet<string>>(new Set());
  protected readonly openOverflowFor = signal<string | null>(null);
  protected readonly actionNotice = signal<string | null>(null);
  protected readonly dialog = signal<RowDialogState | null>(null);
  /** Fetched before the reject dialog opens — see {@link onActionClick}'s REJECT case. */
  protected readonly rejectReasons = signal<readonly RejectReasonOption[]>([]);
  private readonly decisionIds = new DecisionIdRegistry();

  private pollHandle: ReturnType<typeof setInterval> | null = null;
  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') {
      void this.refresh();
    }
  };

  ngOnInit(): void {
    const querySub = this.route.queryParamMap.subscribe((params) => {
      const tab = params.get('tab');
      this.activeTab.set(isOrderTabId(tab) ? tab : DEFAULT_ORDER_TAB);
    });

    document.addEventListener('visibilitychange', this.onVisibilityChange);

    this.pollHandle = setInterval(() => {
      if (document.visibilityState === 'visible') {
        void this.refresh();
      }
    }, POLL_INTERVAL_MS);

    this.destroyRef.onDestroy(() => {
      querySub.unsubscribe();
      document.removeEventListener('visibilitychange', this.onVisibilityChange);
      if (this.pollHandle !== null) {
        clearInterval(this.pollHandle);
      }
    });

    void this.start();
  }

  private async start(): Promise<void> {
    await this.location.ensureLoaded();
    await this.refresh();
  }

  /** Also the manual refresh control (§1.6: "the legacy dashboard's `FaRepeat` button, which staff use"). */
  protected manualRefresh(): void {
    void this.refresh();
  }

  private async refresh(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }

    this.refreshing.set(true);
    try {
      const result = await firstValueFrom(
        this.api.get<OrderSummaryResponse[]>(operationsPaths.orders(scope), {
          params: { limit: FETCH_LIMIT },
        }),
      );
      const orders = result.value ?? [];
      const now = new Date();

      this.rows.set(orders.map((order) => decorate(order, now)));
      this.tabCounts.set(await this.counts.forOrders(scope, orders.map(toCountable), now));
      this.lastUpdatedAt.set(now);
      this.lastError.set(null);
      this.denied.set(false);
      this.serviceStatus.set(deriveServiceStatus(orders, now), now);
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.status === 403) {
          this.denied.set(true);
          this.lastError.set(null);
        } else {
          this.lastError.set(error);
        }
      } else {
        throw error;
      }
    } finally {
      this.refreshing.set(false);
      this.firstLoadComplete.set(true);
    }
  }

  protected selectTab(tab: OrderTabId): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab },
      queryParamsHandling: 'merge',
    });
  }

  protected openOrder(orderId: string): void {
    void this.router.navigate([orderId], {
      relativeTo: this.route,
      queryParamsHandling: 'preserve',
    });
  }

  protected visibleRows(): readonly OrderRow[] {
    const tab = this.activeTab();
    const definition = ORDER_TAB_DEFINITIONS[tab];
    const members = this.rows().filter((row) =>
      isOrderTabMember(tab, { status: row.order.status, severityLevel: row.severity.level }),
    );
    return [...members].sort(
      definition.severityOrdered ? compareOrderSeverity : compareNewestFirst,
    );
  }

  protected tabCount(tab: OrderTabId): number {
    return this.tabCounts()[tab];
  }

  protected statusLabel(status: string): string {
    return orderStatusLabel(status, (key) => this.i18n.t(key));
  }

  protected typeLabel(order: OrderSummaryResponse): string {
    const mode = order.fulfillmentMode ? this.fulfillmentModeLabel(order.fulfillmentMode) : '—';
    return order.channelCode ? `${mode} · ${order.channelCode}` : mode;
  }

  private fulfillmentModeLabel(mode: string): string {
    switch (mode) {
      case 'DELIVERY':
        return this.i18n.t('orders.fulfillmentMode.DELIVERY');
      case 'PICKUP':
        return this.i18n.t('orders.fulfillmentMode.PICKUP');
      case 'DINE_IN':
        return this.i18n.t('orders.fulfillmentMode.DINE_IN');
      default:
        // Unknown fulfilment mode renders harmlessly too, same rule as status.
        return mode;
    }
  }

  protected formatCreated(createdAt: Date): string {
    return formatTime(createdAt, PLACEHOLDER_TIME_ZONE);
  }

  protected formatTotal(order: OrderSummaryResponse): string {
    return formatMoney(
      { amountMinor: order.totalMinor, currency: order.currency },
      this.i18n.locale(),
    );
  }

  protected formatUpdatedAt(): string | null {
    const updated = this.lastUpdatedAt();
    return updated
      ? this.i18n.t('orders.queue.updated', { time: formatClock(updated, PLACEHOLDER_TIME_ZONE) })
      : null;
  }

  protected severityCaption(severity: OrderSeverity): string | null {
    return formatSeverityCaption(severity, (key, values) => this.i18n.t(key, values));
  }

  protected emptyMessage(): string {
    return this.activeTab() === 'attention'
      ? this.i18n.t('orders.queue.empty.attention')
      : this.i18n.t('orders.queue.empty.default');
  }

  protected errorMessage(error: ApiError): string {
    return describeApiError(error, (key, values) => this.i18n.t(key, values));
  }

  /** ADR 0031's errorCode and correlation id, for support (§2.11's error band). */
  protected errorReference(error: ApiError): string {
    return errorReference(error);
  }

  // ------------------------------------------------------------ §2.9 row actions

  /** At most two inline affordances (§2.9); the rest go in the row's overflow menu. */
  protected inlineActions(order: OrderSummaryResponse): readonly OrderActionResponse[] {
    return splitInlineOverflow(order.actions).inline;
  }

  protected overflowActions(order: OrderSummaryResponse): readonly OrderActionResponse[] {
    return splitInlineOverflow(order.actions).overflow;
  }

  protected actionLabel(order: OrderSummaryResponse, action: OrderActionResponse): string {
    return actionLabel(
      action,
      order.fulfillmentMode ?? null,
      (key, values) => this.i18n.t(key, values),
      (status) => this.statusLabel(status),
    );
  }

  protected isRowBusy(orderId: string): boolean {
    return this.busyOrderIds().has(orderId);
  }

  protected toggleOverflow(orderId: string, event: Event): void {
    event.stopPropagation();
    this.openOverflowFor.update((current) => (current === orderId ? null : orderId));
  }

  protected dismissNotice(): void {
    this.actionNotice.set(null);
  }

  /** Dispatches whichever action was clicked, inline or from the overflow menu. */
  protected onActionClick(
    order: OrderSummaryResponse,
    action: OrderActionResponse,
    event: Event,
  ): void {
    event.stopPropagation();
    this.openOverflowFor.set(null);
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    // Java's OrderSummaryResponse.version is a primitive int, always sent; the
    // fallback is only for a fixture or an interim response that omits it.
    const version = order.version ?? 0;

    switch (action.action) {
      case 'APPROVE':
        void this.submitDecision(
          order.orderId,
          this.actionsApi.approve(scope, order.orderId, this.decisionIds.idFor(order.orderId)),
        );
        return;
      case 'REJECT':
        void this.openRejectDialog(order.orderId, version, scope);
        return;
      case 'CANCEL':
        this.dialog.set({ orderId: order.orderId, kind: 'cancel', version });
        return;
      case 'ADVANCE':
        if (action.targetStatus) {
          void this.submitStateMutation(
            order.orderId,
            this.actionsApi.advance(scope, order.orderId, action.targetStatus, version),
          );
        }
        return;
      default:
      // An action code this client does not recognise yet — §4.2 says render
      // it, but there is nothing this client knows how to invoke for it.
    }
  }

  /** Fetch-before-open (wave 24) — see `order-detail-pane.ts`'s identical method for why. */
  private async openRejectDialog(
    orderId: string,
    version: number,
    scope: LocationScope,
  ): Promise<void> {
    try {
      this.rejectReasons.set(await this.rejectReasonsApi.list(scope));
      this.dialog.set({ orderId, kind: 'reject', version });
    } catch (error) {
      if (error instanceof ApiError) {
        this.actionNotice.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    }
  }

  protected dialogBusy(): boolean {
    const state = this.dialog();
    return state !== null && this.isRowBusy(state.orderId);
  }

  protected onDialogDismiss(): void {
    this.dialog.set(null);
  }

  protected onCancelDialogConfirm(submission: OrderReasonSubmission): void {
    const state = this.dialog();
    const scope = this.location.scope();
    if (!state || !scope) {
      return;
    }

    void this.submitStateMutation(
      state.orderId,
      this.actionsApi.cancel(
        scope,
        state.orderId,
        state.version,
        submission.reasonCode,
        submission.note,
      ),
    ).finally(() => this.dialog.set(null));
  }

  protected onRejectDialogConfirm(submission: OrderRejectSubmission): void {
    const state = this.dialog();
    const scope = this.location.scope();
    if (!state || !scope) {
      return;
    }

    void this.submitDecision(
      state.orderId,
      this.actionsApi.reject(
        scope,
        state.orderId,
        this.decisionIds.idFor(state.orderId),
        submission.reasonCode,
        submission.note,
      ),
    ).finally(() => this.dialog.set(null));
  }

  /**
   * `APPROVE`/`REJECT`: settled by `decisionId` compare-and-set, never by
   * version. §4.3: "the response reports the outcome that actually settled
   * the order... a second click gives the same answer as the first rather
   * than an error" — a lost race is not an error, so it renders the settling
   * decision rather than a failure message.
   */
  private async submitDecision(
    orderId: string,
    request: Observable<DecisionResponse>,
  ): Promise<void> {
    this.setRowBusy(orderId, true);
    try {
      const result = await firstValueFrom(request);
      this.decisionIds.settle(orderId);
      if (!result.applied && result.effectiveAction) {
        this.actionNotice.set(
          this.i18n.t('orders.action.lostRace', {
            action: this.decisionActionLabel(result.effectiveAction),
          }),
        );
      }
      void this.refresh();
    } catch (error) {
      this.handleMutationError(orderId, error, { isDecision: true });
    } finally {
      this.setRowBusy(orderId, false);
    }
  }

  /**
   * `ADVANCE`/`CANCEL`: settled by `If-Match` against the order's version.
   * §4.1: a `409 STALE_VERSION` is handled by re-reading and telling the
   * operator what changed, never by retrying.
   */
  private async submitStateMutation(
    orderId: string,
    request: Observable<DecisionResponse>,
  ): Promise<void> {
    this.setRowBusy(orderId, true);
    try {
      await firstValueFrom(request);
      void this.refresh();
    } catch (error) {
      this.handleMutationError(orderId, error, { isDecision: false });
    } finally {
      this.setRowBusy(orderId, false);
    }
  }

  private handleMutationError(
    orderId: string,
    error: unknown,
    options: { readonly isDecision: boolean },
  ): void {
    if (!(error instanceof ApiError)) {
      throw error;
    }

    // A decisionId is only worth keeping for a retryable failure — the
    // transport never delivered this attempt, so the next click is still the
    // same human decision. Anything else is definitive.
    if (options.isDecision && !error.isRetryable) {
      this.decisionIds.settle(orderId);
    }

    const notice = mutationErrorNotice(
      error,
      (key, values) => this.i18n.t(key, values),
      (status) => this.statusLabel(status),
    );
    this.actionNotice.set(notice.text);
    if (notice.shouldReread) {
      void this.refresh();
    }
  }

  private decisionActionLabel(effectiveAction: string): string {
    return decisionOutcomeLabel(effectiveAction, (key) => this.i18n.t(key));
  }

  private setRowBusy(orderId: string, busy: boolean): void {
    this.busyOrderIds.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(orderId);
      } else {
        next.delete(orderId);
      }
      return next;
    });
  }
}

function decorate(order: OrderSummaryResponse, now: Date): OrderRow {
  const createdAt = new Date(order.createdAt);
  return {
    order,
    createdAt,
    severity: computeOrderSeverity(toSeverityFields(order, createdAt), now),
  };
}

function toCountable(order: OrderSummaryResponse): CountableOrder {
  return toSeverityFields(order, new Date(order.createdAt));
}

function toSeverityFields(order: OrderSummaryResponse, createdAt: Date): CountableOrder {
  return {
    status: order.status,
    createdAt,
    approvalDeadlineAt: order.approvalDeadlineAt ? new Date(order.approvalDeadlineAt) : null,
    // order_process_states is not on OrderSummaryResponse yet — see order-severity.ts.
    hasBlockedProcess: false,
  };
}

/**
 * Wires `ServiceStatus` (§1.6, `shell/service-status.ts`) from the same
 * fetch: `open` per that service's own documented definition ("neither
 * completed nor cancelled"), `late` as anything {@link computeOrderSeverity}
 * flagged — the closest proxy available to §2.7's lateness without ADR 0014.
 */
function deriveServiceStatus(
  orders: readonly OrderSummaryResponse[],
  now: Date,
): { open: number; late: number } {
  let open = 0;
  let late = 0;
  for (const order of orders) {
    if (order.status !== 'COMPLETED' && order.status !== 'CANCELLED') {
      open += 1;
    }
    if (computeOrderSeverity(toCountable(order), now).level !== 'NORMAL') {
      late += 1;
    }
  }
  return { open, late };
}
