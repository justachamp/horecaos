import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { operationsPaths } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { TimeZone, formatClock, formatDuration, formatTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { ServiceStatus } from '../../shell/service-status';
import { CountableOrder, OrderCounts, TabCounts, zeroTabCounts } from './order-counts';
import {
  OrderSeverity,
  compareNewestFirst,
  compareOrderSeverity,
  computeOrderSeverity,
  formatCountdown,
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

const ERROR_MESSAGE_KEYS: Readonly<Partial<Record<string, MessageKey>>> = {
  [ApiErrorCode.NETWORK_UNREACHABLE]: 'error.NETWORK_UNREACHABLE',
  [ApiErrorCode.UNAUTHENTICATED]: 'error.UNAUTHENTICATED',
  [ApiErrorCode.INSUFFICIENT_CAPABILITY]: 'error.INSUFFICIENT_CAPABILITY',
  [ApiErrorCode.ENTITLEMENT_REQUIRED]: 'error.ENTITLEMENT_REQUIRED',
  [ApiErrorCode.STALE_VERSION]: 'error.STALE_VERSION',
  [ApiErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS]: 'error.IDEMPOTENCY_KEY_IN_PROGRESS',
  [ApiErrorCode.RESOURCE_NOT_FOUND]: 'error.RESOURCE_NOT_FOUND',
  [ApiErrorCode.RATE_LIMIT_EXCEEDED]: 'error.RATE_LIMIT_EXCEEDED',
};

/** One row, decorated with what the table and the sort actually need. */
interface OrderRow {
  readonly order: OrderSummaryResponse;
  readonly createdAt: Date;
  readonly severity: OrderSeverity;
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
  imports: [TPipe],
  templateUrl: './order-queue.html',
  styleUrl: './order-queue.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderQueue implements OnInit {
  private readonly api = inject(ApiClient);
  private readonly location = inject(CurrentLocation);
  private readonly counts = inject(OrderCounts);
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
      this.tabCounts.set(this.counts.forOrders(orders.map(toCountable), now));
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
    switch (severity.level) {
      case 'BLOCKED':
        return this.i18n.t('orders.severity.blocked');
      case 'AWAITING_APPROVAL_DEADLINE':
        return this.i18n.t('orders.severity.approvalDeadline', {
          mmss: formatCountdown(severity.remainingMs ?? 0),
        });
      case 'NO_PROMISE_FALLBACK':
        return this.i18n.t('orders.severity.noPromiseFallback', {
          duration: formatDuration(Math.floor((severity.elapsedMs ?? 0) / 60_000), {
            hour: this.i18n.t('orders.duration.hour'),
            minute: this.i18n.t('orders.duration.minute'),
          }),
        });
      case 'NORMAL':
        return null;
    }
  }

  protected emptyMessage(): string {
    return this.activeTab() === 'attention'
      ? this.i18n.t('orders.queue.empty.attention')
      : this.i18n.t('orders.queue.empty.default');
  }

  protected errorMessage(error: ApiError): string {
    const key = ERROR_MESSAGE_KEYS[error.code];
    if (key) {
      return this.i18n.t(key);
    }
    return error.correlationId
      ? this.i18n.t('error.unknown', { correlationId: error.correlationId })
      : this.i18n.t('error.unknown.noReference');
  }

  /** ADR 0031's errorCode and correlation id, for support (§2.11's error band). */
  protected errorReference(error: ApiError): string {
    return error.correlationId ? `${error.code} · ${error.correlationId}` : error.code;
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
