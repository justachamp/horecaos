import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  Signal,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { CursorState, Page, firstPage, nextPage, pageParams } from '../../core/api/page';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { LatenessPolicy, PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { LatenessPolicyApi } from '../../core/lateness-policy-api';
import { TimeZone, formatClock, formatTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { RealtimeClient } from '../../core/realtime/realtime-client';
import { startVisibilityPoll } from '../../core/realtime/visibility-poll';
import { ServiceStatus } from '../../shell/service-status';
import { ConnectionStateBanner } from '../../shared/ui/connection-state-banner';
import { DateRange, DateRangePicker } from '../../shared/ui/date-range-picker';
import { FilterBar, FilterBarChip } from '../../shared/ui/filter-bar';
import { StaleIndicator } from '../../shared/ui/stale-indicator';
import { StatusPill } from '../../shared/ui/status-pill';
import { Toasts } from '../../shared/ui/toast';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import { ReasonResponse, ReferenceDataApi } from '../settings/reference-data/reference-data-api';
import {
  DecisionIdRegistry,
  OrderActionResponse,
  actionLabel,
  advanceReasonCode,
  decisionOutcomeLabel,
  requiresCancellationReason,
  splitInlineOverflow,
} from './order-actions';
import { DecisionResponse, OrderActionsApi } from './order-actions-api';
import {
  BulkActionRequest,
  BulkActionResponse,
  BulkOrderRef,
  OrderBulkActionsApi,
} from './order-bulk-actions-api';
import { CountableOrder, OrderCounts, TabCounts, zeroTabCounts } from './order-counts';
import { describeApiError, errorReference, mutationErrorNotice } from './order-errors';
import { OrderOutcomeReasonDialog, OutcomeReasonSubmission } from './order-outcome-reason-dialog';
import { paymentStatusProjectionLabel } from './order-payment-status';
import {
  OrderQueueFilters,
  OrderQueueFilterState,
  boardQueryParams,
} from './order-queue-filter-state';
import {
  bulkAdvanceTarget,
  bulkCancelEligible,
  bulkCancelIneligibleCount,
  pruneSelection,
  toggleOne,
  toggleSelectPage,
} from './order-queue-selection';
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
  formatCountdown,
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

/**
 * §1.6: poll every 10s while the tab is visible — the fallback ADR 0045
 * itself requires every live surface to keep, unconditionally, whether or
 * not the accelerator below is connected. `RealtimeClient`'s own `ORDER_QUEUE`
 * signal shortens the *usual* wait to under a second; this interval is what
 * still runs the shift if it cannot.
 */
const POLL_INTERVAL_MS = 10_000;

/**
 * The page size for one cursor request, first page and every `loadMore` page
 * alike. The board endpoint's own maximum is 500 (`horecaos-api.json`); 200
 * is a first-render compromise between a complete picture for the
 * client-derived tab counts (`order-counts.ts`) and payload size — the same
 * reasoning that picked it before this wave turned the single capped fetch
 * into real cursor paging (X.18's `rows`/`hasMore`/`loadMore` contract,
 * `shared/ui/data-table`). `tabCounts` is still computed only over whatever
 * is loaded so far (the first page, until `loadMore` is clicked), so a
 * location busier than one page still undercounts `attention` until the real
 * `GET .../orders/counts` endpoint replaces this client-side tally (§11's own
 * trap, unchanged by this wave).
 */
const FETCH_LIMIT = 200;

/** §2.8: "300 ms debounce, minimum two characters" — the search box's own delay before it re-fetches. */
const SEARCH_DEBOUNCE_MS = 300;

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

/** §2.4's fixed, platform-wide set — ADR 0055 scopes the pilot to one payment provider, so a tenant-configurable registry is not this toolbar's to read (see `order-queue-filter-state.ts`'s own doc). */
const PAYMENT_METHOD_CODES = ['CASH', 'CLICK', 'PAYME'] as const;

/** One row, decorated with what the table and the sort actually need. */
interface OrderRow {
  readonly order: OrderSummaryResponse;
  readonly createdAt: Date;
  readonly severity: OrderSeverity;
}

/**
 * Which row opened the reason dialog, for which action, at which version.
 *
 * `'cancel'` is the free-text reasonless dialog (before `CONFIRMED`);
 * `'cancel-reason'` is the registry-reasoned picker H2 adds for `CONFIRMED`
 * onward — see {@link requiresCancellationReason}. `'complete'` is the
 * completion-reason picker {@link startCompletion} opens when more than one
 * `COMPLETION` reason is eligible for the order's fulfilment mode.
 */
interface RowDialogState {
  readonly orderId: string;
  readonly kind: 'reject' | 'cancel' | 'cancel-reason' | 'complete';
  readonly version: number;
}

/**
 * The order queue — `docs/operations-spec/orders.md` §2: tabs, the dense
 * table, severity tint/rail/caption, the §1.6 polling fallback, row actions
 * (§2.9), and — wave P07 — the toolbar and the bulk-action bar §2.4/§2.10
 * describe.
 *
 * **The toolbar.** Every filter here is bound to a real `GET
 * .../orders/board` (wave P04, ADR 0102) query parameter — see
 * `order-queue-filter-state.ts`'s own doc for exactly which of §2.4's rows
 * this omits and why (a branch filter the endpoint has no parameter for, an
 * aggregator predicate the ordering module does not read yet, a payment
 * *status* filter with no server predicate at all). Filters persist **per
 * tab** in `localStorage`, restored on every tab switch and on a full reload
 * — not yet round-tripped through the URL, which stays this wave's own open
 * issue rather than a silent gap. Because the board is one filtered fetch
 * rather than one fetch per status, switching tabs re-fetches under the
 * newly active tab's own remembered filters — a manager who filters
 * Внимание to one channel and switches to Готовятся sees that tab's own
 * filters take over, not Внимание's carried forward, and the tab badges
 * reflect whichever filter set is currently in effect. That is a legacy
 * per-status-page's own semantics (§9's "the legacy dashboard did"), read
 * onto a shared-fetch board rather than seven separate ones.
 *
 * **Selection and bulk actions.** A checkbox column, a bulk-action bar that
 * replaces the filter row while anything is selected, and `POST
 * .../orders/bulk-actions` (ADR 0039) — `ADVANCE`/`CANCEL` only.
 * **Bulk courier assignment is explicitly out of scope**: `BulkActionType`
 * has no such member, and the bulk bar says so rather than offering a button
 * that would 400. Every bulk action is offered only when it is valid for
 * *every* selected row (§2.10) — see `order-queue-selection.ts` for the
 * eligibility rules — and a partial failure renders §2.10's result panel,
 * with **Повторить проблемные** resubmitting only the failed items under a
 * fresh intent (never the same `Idempotency-Key` with the same body, which
 * `OrderBulkActionService`'s own doc says changes nothing at all).
 *
 * **Columns, reduced to the wire.** `OrderSummaryResponse` — see
 * `order-summary.ts` — is short of §2.5's default set: no branch, no
 * customer, no line summary, no payment projection, no courier. What renders
 * here is a selection checkbox, severity rail, order number + severity
 * caption, time, type/channel, total, and status. `Филиал` is additionally
 * out of place for a different reason: this endpoint is already scoped to
 * one location, which is the spec's own condition for auto-hiding that
 * column.
 */
@Component({
  selector: 'q-order-queue',
  imports: [
    TPipe,
    OrderReasonDialog,
    OrderRejectReasonDialog,
    OrderOutcomeReasonDialog,
    StatusPill,
    FilterBar,
    DateRangePicker,
    ConnectionStateBanner,
    StaleIndicator,
  ],
  templateUrl: './order-queue.html',
  styleUrl: './order-queue.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderQueue implements OnInit {
  private readonly api = inject(ApiClient);
  private readonly location = inject(CurrentLocation);
  private readonly tenant = inject(CurrentTenant);
  private readonly capabilities = inject(SessionCapabilities);
  private readonly counts = inject(OrderCounts);
  private readonly latenessPolicyApi = inject(LatenessPolicyApi);
  private readonly actionsApi = inject(OrderActionsApi);
  private readonly bulkActionsApi = inject(OrderBulkActionsApi);
  private readonly rejectReasonsApi = inject(RejectReasonsApi);
  private readonly referenceDataApi = inject(ReferenceDataApi);
  private readonly couriersApi = inject(CouriersApi);
  private readonly serviceStatus = inject(ServiceStatus);
  protected readonly realtime = inject(RealtimeClient);
  private readonly toasts = inject(Toasts);
  private readonly i18n = inject(I18n);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly filterState = inject(OrderQueueFilterState);

  protected readonly tabs = ORDER_TABS.map((id) => ORDER_TAB_DEFINITIONS[id]);

  protected readonly activeTab = signal<OrderTabId>(DEFAULT_ORDER_TAB);
  protected readonly tabCounts = signal<TabCounts>(zeroTabCounts());
  protected readonly rows = signal<readonly OrderRow[]>([]);
  protected readonly lastUpdatedAt = signal<Date | null>(null);
  protected readonly firstLoadComplete = signal(false);
  protected readonly refreshing = signal(false);
  protected readonly lastError = signal<ApiError | null>(null);
  protected readonly denied = signal(false);

  /**
   * §1.1/X.18: cursor paging over `GET .../orders/board`, mirroring
   * `products-page.ts`'s own `page`/`hasMore`/`loadMore` loop — the same
   * `rows`/`hasMore`/`loadMore` contract `shared/ui/data-table` documents,
   * driven here by this page's own hand-rolled table rather than that
   * component (the row-action menu, selection column and severity rail this
   * table already renders are not yet what `q-data-table` hosts). `pageState`
   * is always reset to {@link FETCH_LIMIT}'s first page inside {@link
   * refresh} — a filter change, a tab switch, the 10s poll and every realtime
   * frame all call `refresh`, so every one of them starts the board over from
   * its own first page rather than trying to carry a cursor across a filter
   * it was not minted under (`page.ts`'s own `resetOnFilterChange` doc).
   */
  protected readonly pageState = signal<CursorState>(firstPage(FETCH_LIMIT));
  protected readonly hasMore = signal(false);
  protected readonly loadingMore = signal(false);

  /** §2.4: the toolbar's own filters for the active tab. */
  protected readonly filters: Signal<OrderQueueFilters> = this.filterState.current;
  protected readonly paymentMethodCodes = PAYMENT_METHOD_CODES;

  /** §2.4's Канал: every channel code this session has observed, only ever growing — see {@link refresh}. */
  protected readonly channelOptions = signal<readonly string[]>([]);
  private readonly observedChannelCodes = new Set<string>();

  /** §2.4's Курьер: fetched once, lazily, on first interaction with the control — see {@link ensureCourierRosterLoaded}. */
  protected readonly courierRoster = signal<readonly RosterEntryResponse[]>([]);
  private courierRosterRequested = false;

  /** §2.10: the checkbox column's own selection, independent of the fetched rows' identity — survives a page boundary. */
  protected readonly selectedIds = signal<ReadonlySet<string>>(new Set());

  /** §2.10: the bulk bar's own busy/result/dialog state. */
  protected readonly bulkBusy = signal(false);
  protected readonly bulkResult = signal<BulkActionResponse | null>(null);
  protected readonly bulkCancelDialogOpen = signal(false);
  protected readonly bulkCancelReasons = signal<readonly ReasonResponse[]>([]);
  private lastBulkSubmission: BulkActionRequest | null = null;

  /** §2.9: inline actions rendered from `actions[]`, plus their busy/dialog/notice state. */
  protected readonly busyOrderIds = signal<ReadonlySet<string>>(new Set());
  protected readonly openOverflowFor = signal<string | null>(null);
  protected readonly actionNotice = signal<string | null>(null);
  protected readonly dialog = signal<RowDialogState | null>(null);
  /** Fetched before the reject dialog opens — see {@link onActionClick}'s REJECT case. */
  protected readonly rejectReasons = signal<readonly RejectReasonOption[]>([]);
  /**
   * Fetched before the row's own reasoned cancel dialog opens (H2) — see
   * {@link openCancelReasonDialog}. Kept apart from {@link bulkCancelReasons}:
   * the two dialogs can be open at different times for different reasons and
   * neither should clobber the other's already-fetched list.
   */
  protected readonly cancelReasons = signal<readonly ReasonResponse[]>([]);
  /** Fetched before the completion dialog opens, only when more than one reason is eligible — see {@link startCompletion}. */
  protected readonly completionReasons = signal<readonly ReasonResponse[]>([]);
  private readonly decisionIds = new DecisionIdRegistry();

  /**
   * Guards the fetch-before-open race between {@link openRejectDialog} and
   * {@link openCancelReasonDialog} (bug-hunt H2): both await a reference-data
   * call and then set the shared {@link dialog}/reason-list signals with no
   * ordering guarantee between two independent HTTP round trips. Bumped by
   * every attempt to open one of those dialogs (including the synchronous
   * reasonless-cancel path in {@link onActionClick}, which can itself be the
   * "newer" click an in-flight fetch must yield to); an awaited fetch applies
   * its result only when this counter still matches the value it captured
   * before awaiting, so a click superseded by a later click never wins the
   * race just because its own round trip happened to come back first.
   */
  private dialogRequestId = 0;

  /**
   * The resolved `ordering.lateness` policy (wave P06) — fetched once per
   * location in {@link start}, not re-fetched on every 10s poll: a tenant
   * changing its own SLA thresholds mid-shift is rare enough that the next
   * navigation picking it up is an acceptable bound, and every {@link
   * decorate} call this session makes reads the same object, which is the
   * whole point of "one policy" for row `X.39`.
   */
  private latenessPolicy: LatenessPolicy = PLATFORM_DEFAULT_LATENESS_POLICY;

  /** Guards the tab-change refetch below from also firing on the very first route resolution — {@link start} already fetches once. */
  private hasStarted = false;

  ngOnInit(): void {
    const querySub = this.route.queryParamMap.subscribe((params) => {
      const tab = params.get('tab');
      const resolved = isOrderTabId(tab) ? tab : DEFAULT_ORDER_TAB;
      // §2.4: filters are remembered **per tab**, and the board's one fetch
      // is filtered by whichever tab is active — so switching tabs switches
      // which remembered filter set is in effect and re-fetches under it.
      const tabChanged = this.hasStarted && this.activeTab() !== resolved;
      this.activeTab.set(resolved);
      this.filterState.loadForTab(resolved);
      if (tabChanged) {
        void this.refresh();
      }
    });

    // §1.6's own fallback, extracted — see `visibility-poll.ts`'s doc.
    // `immediate: false` because {@link start} below does the real first
    // fetch after its own async prerequisites resolve.
    startVisibilityPoll(() => void this.refresh(), POLL_INTERVAL_MS, this.destroyRef, {
      immediate: false,
    });

    // The accelerator: `ORDER_QUEUE` and `COUNTERS` both change when this
    // board's rows or tab badges do, so either one is worth an immediate
    // re-fetch rather than waiting up to `POLL_INTERVAL_MS` for the poll
    // above to notice. Every frame on this connection is filtered by scope
    // already (`RealtimeClient` reconnects on the operator's own branch);
    // this only additionally checks the channel, since the same connection
    // also carries `ORDER_DETAIL` and `DISPATCH_BOARD` frames this screen
    // does not care about.
    const unsubscribeRealtime = this.realtime.onFrame((frame) => {
      if (
        (frame.kind === 'signal' && frame.channel === 'order_queue') ||
        (frame.kind === 'snapshot' && frame.channel === 'counters') ||
        frame.kind === 'resync'
      ) {
        void this.refresh();
      }
    });

    this.destroyRef.onDestroy(() => {
      querySub.unsubscribe();
      unsubscribeRealtime();
      if (this.searchDebounceHandle !== null) {
        clearTimeout(this.searchDebounceHandle);
      }
    });

    void this.start();
  }

  private async start(): Promise<void> {
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (scope) {
      this.latenessPolicy = await this.latenessPolicyApi.resolve(scope);
    }
    await this.refresh();
    this.hasStarted = true;
  }

  /** Also the manual refresh control (§1.6: "the legacy dashboard's `FaRepeat` button, which staff use"). */
  protected manualRefresh(): void {
    void this.refresh();
  }

  /** One page of `GET .../orders/board` under the toolbar's current filters, cursor-`state`'s own window. */
  private async fetchBoardPage(
    scope: LocationScope,
    state: CursorState,
  ): Promise<Page<OrderSummaryResponse>> {
    const params = boardQueryParams(this.filters(), this.tenant.subject());
    const result = await firstValueFrom(
      this.api.get<Page<OrderSummaryResponse>>(operationsPaths.orderBoard(scope), {
        params: { ...params, ...pageParams(state) },
      }),
    );
    return result.value ?? { items: [], nextCursor: null };
  }

  /** The full reload path: tab switch, filter change, manual refresh, the 10s poll and every realtime frame. Always starts from the board's own first page. */
  private async refresh(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.firstLoadComplete.set(true);
      return;
    }

    this.refreshing.set(true);
    try {
      const startState = firstPage(FETCH_LIMIT);
      const page = await this.fetchBoardPage(scope, startState);
      const orders = page.items;
      const now = new Date();

      this.rows.set(orders.map((order) => decorate(order, now, this.latenessPolicy)));
      this.pageState.set(nextPage(startState, page) ?? startState);
      this.hasMore.set(page.nextCursor !== null);
      this.tabCounts.set(
        await this.counts.forOrders(scope, orders.map(toCountable), now, this.latenessPolicy),
      );
      this.lastUpdatedAt.set(now);
      this.lastError.set(null);
      this.denied.set(false);
      this.serviceStatus.set(deriveServiceStatus(orders, now, this.latenessPolicy), now);
      this.observeChannelCodes(orders);
      this.selectedIds.update((current) =>
        pruneSelection(current, new Set(orders.map((order) => order.orderId))),
      );
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

  /**
   * X.18's `loadMore` half of the `rows`/`hasMore`/`loadMore` contract:
   * appends the next cursor page to what is already loaded, under the same
   * filters {@link refresh} last fetched with — never resets the table, the
   * selection or the scroll position the way a full {@link refresh} does.
   *
   * Not re-derived here: `tabCounts` and `ServiceStatus` stay exactly what
   * the last {@link refresh} computed over its own first page — extending
   * them to every loaded page on every click would make a busy tab's numbers
   * jump as an operator scrolls, which is a worse surprise than the
   * documented "still undercounts above the first page" limitation {@link
   * FETCH_LIMIT} already names.
   */
  protected async loadMore(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.hasMore() || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      const state = this.pageState();
      const page = await this.fetchBoardPage(scope, state);
      const now = new Date();
      const appended = page.items.map((order) => decorate(order, now, this.latenessPolicy));
      this.rows.set([...this.rows(), ...appended]);
      this.pageState.set(nextPage(state, page) ?? state);
      this.hasMore.set(page.nextCursor !== null);
      this.observeChannelCodes(page.items);
    } catch (error) {
      if (error instanceof ApiError) {
        this.actionNotice.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      this.loadingMore.set(false);
    }
  }

  /** §2.4's Канал options: every code this session has actually seen on a row, sorted, never invented. */
  private observeChannelCodes(orders: readonly OrderSummaryResponse[]): void {
    let changed = false;
    for (const order of orders) {
      if (order.channelCode && !this.observedChannelCodes.has(order.channelCode)) {
        this.observedChannelCodes.add(order.channelCode);
        changed = true;
      }
    }
    if (changed) {
      this.channelOptions.set([...this.observedChannelCodes].sort());
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

  /** §2.5's "behind the picker" Доставка column — `0` (a pickup/dine-in order, or a delivery one with no fee) renders as a dash, never `0 сум`. */
  protected formatFee(order: OrderSummaryResponse): string {
    const feeMinor = order.feeMinor ?? 0;
    return feeMinor > 0
      ? formatMoney({ amountMinor: feeMinor, currency: order.currency }, this.i18n.locale())
      : '—';
  }

  /** §2.5 column 10 (Оплата). */
  protected paymentStatusLabel(order: OrderSummaryResponse): string {
    return paymentStatusProjectionLabel(order.paymentStatusProjection, (key) => this.i18n.t(key));
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

  /**
   * The status pill's lateness overlay — a marker beside the status word, never
   * instead of it (ADR 0101, row `X.15`).
   *
   * Short on purpose: the full sentence is already under the order number, and
   * a pill that carries «в очереди 1 ч 20 мин» is a pill that wraps the column.
   * `AWAITING_APPROVAL_DEADLINE` shows its countdown rather than a word,
   * because the number *is* the message at that tier.
   *
   * Derived per render from the same `OrderSeverity` the rail and the caption
   * already use, so the three cannot disagree — which is precisely the gap this
   * row names: "status and lateness read as two unrelated visual systems".
   */
  protected severityOverlay(severity: OrderSeverity): string | null {
    switch (severity.level) {
      case 'BLOCKED':
        return this.i18n.t('orders.severity.pill.blocked');
      case 'AWAITING_APPROVAL_DEADLINE':
        return formatCountdown(severity.remainingMs ?? 0);
      case 'LATE':
        return this.i18n.t('orders.severity.pill.late');
      case 'AT_RISK':
        // The caption under the order number already says so (§2.7); a
        // second pill beside the status word would be the row shouting the
        // same thing twice for the one tier that is a warning, not yet a
        // breach.
        return null;
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
    return describeApiError(error, (key, values) => this.i18n.t(key, values));
  }

  /** ADR 0031's errorCode and correlation id, for support (§2.11's error band). */
  protected errorReference(error: ApiError): string {
    return errorReference(error);
  }

  // ---------------------------------------------------------------- §2.4 filters

  private searchDebounceHandle: ReturnType<typeof setTimeout> | null = null;

  /** §2.8: 300ms debounce before the search box re-fetches — every other filter refetches immediately on change. */
  protected onSearchInput(text: string): void {
    this.filterState.update({ reference: text });
    if (this.searchDebounceHandle !== null) {
      clearTimeout(this.searchDebounceHandle);
    }
    this.searchDebounceHandle = setTimeout(() => void this.refresh(), SEARCH_DEBOUNCE_MS);
  }

  protected onDateRangeChange(range: DateRange): void {
    this.filterState.update({ dateRange: { start: range.start, end: range.end } });
    void this.refresh();
  }

  protected onChannelChange(channelCode: string): void {
    this.filterState.update({ channelCode: channelCode || null });
    void this.refresh();
  }

  protected onFulfillmentModeChange(mode: string): void {
    this.filterState.update({
      fulfillmentMode: mode ? (mode as OrderQueueFilters['fulfillmentMode']) : null,
    });
    void this.refresh();
  }

  protected onCourierChange(courierId: string): void {
    this.filterState.update({ courierId: courierId || null });
    void this.refresh();
  }

  protected onPaymentMethodChange(code: string): void {
    this.filterState.update({ paymentMethodCode: code || null });
    void this.refresh();
  }

  protected onMineOnlyToggle(): void {
    const next = !this.filters().mineOnly;
    this.filterState.update({ mineOnly: next });
    // §2.4: the client supplies its own subject; there is no server-side
    // `me`. Lazily loaded here rather than at start-up, so a session that
    // never touches this toggle never spends the extra request.
    void this.tenant.ensureLoaded().then(() => void this.refresh());
  }

  protected onResetFilters(): void {
    this.filterState.reset();
    void this.refresh();
  }

  protected onChipRemoved(chipId: string): void {
    if (chipId === 'mine') {
      this.filterState.update({ mineOnly: false });
    } else if (chipId === 'paymentMethod') {
      this.filterState.update({ paymentMethodCode: null });
    }
    void this.refresh();
  }

  protected filterChips(): readonly FilterBarChip[] {
    const filters = this.filters();
    const chips: FilterBarChip[] = [];
    if (filters.mineOnly) {
      chips.push({ id: 'mine', label: this.i18n.t('orders.queue.filter.mine.label') });
    }
    if (filters.paymentMethodCode) {
      chips.push({
        id: 'paymentMethod',
        label: this.paymentMethodLabel(filters.paymentMethodCode),
      });
    }
    return chips;
  }

  protected paymentMethodLabel(code: string): string {
    switch (code) {
      case 'CASH':
        return this.i18n.t('orders.queue.filter.paymentMethod.CASH');
      case 'CLICK':
        return this.i18n.t('orders.queue.filter.paymentMethod.CLICK');
      case 'PAYME':
        return this.i18n.t('orders.queue.filter.paymentMethod.PAYME');
      default:
        return code;
    }
  }

  /** §2.4's Курьер: the roster loads once, on first focus of the control — never at start-up, for a session that never opens it. */
  protected ensureCourierRosterLoaded(): void {
    if (this.courierRosterRequested) {
      return;
    }
    this.courierRosterRequested = true;
    const tenantId = this.location.scope()?.tenantId;
    if (!tenantId) {
      return;
    }
    void this.couriersApi
      .roster(tenantId)
      .then((roster) => this.courierRoster.set(roster))
      .catch(() => {
        // A staff-level operator without COURIER_READ simply sees an empty
        // picker rather than a broken toolbar — the same graceful-degrade
        // stance `CurrentLocation.load` and `LatenessPolicyApi` already take.
      });
  }

  // ------------------------------------------------------------ §2.10 selection

  protected pageOrderIds(): readonly string[] {
    return this.visibleRows().map((row) => row.order.orderId);
  }

  protected isAllPageSelected(): boolean {
    const ids = this.pageOrderIds();
    const selected = this.selectedIds();
    return ids.length > 0 && ids.every((id) => selected.has(id));
  }

  protected toggleSelectPage(): void {
    this.selectedIds.update((current) => toggleSelectPage(current, this.pageOrderIds()));
  }

  protected toggleRowSelection(orderId: string, event: Event): void {
    event.stopPropagation();
    this.selectedIds.update((current) => toggleOne(current, orderId));
  }

  protected isRowSelected(orderId: string): boolean {
    return this.selectedIds().has(orderId);
  }

  protected selectionCount(): number {
    return this.selectedIds().size;
  }

  protected clearSelection(): void {
    this.selectedIds.set(new Set());
  }

  private selectedOrders(): readonly OrderSummaryResponse[] {
    const ids = this.selectedIds();
    return this.rows()
      .filter((row) => ids.has(row.order.orderId))
      .map((row) => row.order);
  }

  /**
   * §2.10: "render the bar only when the session context carries it" — and,
   * one step earlier than the brief's own wording, the checkbox column
   * itself: an operator who cannot act on a selection is not offered one to
   * make, rather than being shown a selection mechanism whose only outcome
   * is a bulk bar with nothing enabled in it.
   */
  protected canSelectOrders(): boolean {
    return this.capabilities.has('ORDER_BULK_ACTION');
  }

  protected canBulkCancel(): boolean {
    return bulkCancelEligible(this.selectedOrders());
  }

  protected bulkCancelUnavailableMessage(): string | null {
    if (this.selectionCount() === 0) {
      return null;
    }
    const ineligible = bulkCancelIneligibleCount(this.selectedOrders());
    if (ineligible === 0) {
      return null;
    }
    return this.i18n.t('orders.queue.bulk.cancelUnavailable', {
      ineligible,
      total: this.selectionCount(),
    });
  }

  protected bulkAdvanceTargetStatus(): string | null {
    return bulkAdvanceTarget(this.selectedOrders());
  }

  protected bulkAdvanceLabel(): string {
    const target = this.bulkAdvanceTargetStatus();
    if (!target) {
      return this.i18n.t('orders.queue.bulk.advance');
    }
    return actionLabel(
      { action: 'ADVANCE', targetStatus: target },
      null,
      (key, values) => this.i18n.t(key, values),
      (status) => this.statusLabel(status),
    );
  }

  protected onBulkAdvanceClick(): void {
    const target = this.bulkAdvanceTargetStatus();
    const scope = this.location.scope();
    if (!target || !scope) {
      return;
    }
    void this.submitBulk({
      actionType: 'ADVANCE',
      orders: this.orderRefsOf(this.selectedOrders()),
      targetStatus: target,
      reasonCode: advanceReasonCode(target),
    });
  }

  protected onBulkCancelClick(): void {
    if (!this.canBulkCancel()) {
      return;
    }
    void this.openBulkCancelDialog();
  }

  private async openBulkCancelDialog(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.bulkCancelReasons.set(await this.referenceDataApi.list(scope, 'CANCELLATION'));
      this.bulkCancelDialogOpen.set(true);
    } catch (error) {
      if (error instanceof ApiError) {
        this.actionNotice.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    }
  }

  protected onBulkCancelDialogDismiss(): void {
    this.bulkCancelDialogOpen.set(false);
  }

  protected onBulkCancelDialogConfirm(submission: OutcomeReasonSubmission): void {
    this.bulkCancelDialogOpen.set(false);
    void this.submitBulk({
      actionType: 'CANCEL',
      orders: this.orderRefsOf(this.selectedOrders()),
      cancelReasonId: submission.reasonId,
      cancelNote: submission.note,
    });
  }

  private orderRefsOf(orders: readonly OrderSummaryResponse[]): readonly BulkOrderRef[] {
    return orders.map((order) => ({ orderId: order.orderId, expectedVersion: order.version ?? 0 }));
  }

  private async submitBulk(request: BulkActionRequest): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.bulkBusy.set(true);
    try {
      const result = await firstValueFrom(this.bulkActionsApi.submit(scope, request));
      this.bulkResult.set(result);
      this.lastBulkSubmission = request;
      this.clearSelection();
      void this.refresh();
    } catch (error) {
      if (error instanceof ApiError) {
        this.actionNotice.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      this.bulkBusy.set(false);
    }
  }

  /**
   * §2.10: "**Повторить проблемные** re-running under the same bulk key so
   * successes replay their stored responses instead of executing twice."
   * `OrderBulkActionService`'s own doc reads that literally: a resubmission
   * under the *same* `Idempotency-Key` replays the whole prior result and
   * retries nothing. So this mints a fresh intent — a new key, via {@link
   * OrderBulkActionsApi.submit} — and narrows the request to only the items
   * that failed, which is what actually makes "successes are never executed
   * twice" true: they are simply never resent.
   */
  protected retryFailedBulkItems(): void {
    const result = this.bulkResult();
    const previous = this.lastBulkSubmission;
    if (!result || !previous) {
      return;
    }
    const failedIds = new Set(
      result.items.filter((item) => item.itemStatus === 'FAILED').map((item) => item.orderId),
    );
    if (failedIds.size === 0) {
      return;
    }
    const freshVersionById = new Map(
      this.rows().map((row) => [row.order.orderId, row.order.version ?? 0]),
    );
    const retryOrders = previous.orders
      .filter((ref) => failedIds.has(ref.orderId))
      .map((ref) => ({
        orderId: ref.orderId,
        expectedVersion: freshVersionById.get(ref.orderId) ?? ref.expectedVersion,
      }));
    void this.submitBulk({ ...previous, orders: retryOrders });
  }

  protected dismissBulkResult(): void {
    this.bulkResult.set(null);
    this.lastBulkSubmission = null;
  }

  protected bulkResultItemLabel(orderId: string): string {
    const row = this.rows().find((r) => r.order.orderId === orderId);
    return row ? row.order.publicOrderNumber : orderId.slice(0, 8);
  }

  /**
   * The known `BulkActionItemResponse.itemProblemCode` values
   * (`OrderBulkActionService.fail`/`applyItem`). An additive server release
   * must not blank a row — same "renders harmlessly" rule as an unrecognised
   * order status — so a code this client does not know renders as itself
   * rather than through a fabricated i18n key.
   */
  protected bulkProblemLabel(code: string | null | undefined): string {
    switch (code) {
      case 'STALE_VERSION':
        return this.i18n.t('orders.queue.bulk.problem.STALE_VERSION');
      case 'ILLEGAL_TRANSITION':
        return this.i18n.t('orders.queue.bulk.problem.ILLEGAL_TRANSITION');
      case 'CANCELLATION_NOT_PERMITTED':
        return this.i18n.t('orders.queue.bulk.problem.CANCELLATION_NOT_PERMITTED');
      case 'REASON_NOT_FOUND':
        return this.i18n.t('orders.queue.bulk.problem.REASON_NOT_FOUND');
      case 'VALIDATION_FAILED':
        return this.i18n.t('orders.queue.bulk.problem.VALIDATION_FAILED');
      case 'ORDER_NOT_FOUND_AT_LOCATION':
        return this.i18n.t('orders.queue.bulk.problem.ORDER_NOT_FOUND_AT_LOCATION');
      case 'UNEXPECTED_FAILURE':
        return this.i18n.t('orders.queue.bulk.problem.UNEXPECTED_FAILURE');
      default:
        return code ?? '';
    }
  }

  // ------------------------------------------------------------ §2.9 row actions

  /** At most two inline affordances (§2.9); the rest go in the row's overflow menu. */
  protected inlineActions(order: OrderSummaryResponse): readonly OrderActionResponse[] {
    return splitInlineOverflow(this.rowActions(order)).inline;
  }

  protected overflowActions(order: OrderSummaryResponse): readonly OrderActionResponse[] {
    return splitInlineOverflow(this.rowActions(order)).overflow;
  }

  /**
   * H3 (batch 8 integration note): the server always pairs `ADVANCE`→
   * `COMPLETED` with `COMPLETE` whenever completion is legal
   * (`OrderActionsPolicy`'s own doc: a client built before wave P09 still
   * works against the generic entry) — both render under the identical
   * translated label (`order-actions.ts`'s `actionLabel`). H3 originally
   * dropped `COMPLETE` here because `onActionClick` had no case for it; wave
   * 8's w1 wired `COMPLETE` to the same fulfilment-mode-aware
   * completion-reason flow `order-detail-pane.ts`'s `startCompletion` uses
   * (see {@link startCompletion} below), so this now filters the *other*
   * direction — the same one `order-detail-pane.ts`'s `visibleActions`
   * already uses — preferring `COMPLETE` (it can name the right reason) and
   * dropping the redundant `ADVANCE`→`COMPLETED` entry, rather than
   * rendering two identically-labelled buttons for the same transition.
   */
  private rowActions(order: OrderSummaryResponse): readonly OrderActionResponse[] {
    const actions = order.actions ?? [];
    const hasComplete = actions.some((action) => action.action === 'COMPLETE');
    return hasComplete
      ? actions.filter((action) => !(action.action === 'ADVANCE' && action.targetStatus === 'COMPLETED'))
      : actions;
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

  /**
   * §2.9's «Открыть», from the overflow menu — the same navigation the row's
   * own click already offers, kept here too for a keyboard or screen-reader
   * user working the menu rather than the row.
   */
  protected openFromOverflow(order: OrderSummaryResponse, event: Event): void {
    event.stopPropagation();
    this.openOverflowFor.set(null);
    this.openOrder(order.orderId);
  }

  /**
   * §2.9's «Копировать номер» — a client-only read, never gated by {@code
   * actions[]} or any capability, which is exactly why it (and «Открыть»)
   * belong on every row's overflow menu regardless of what
   * `OrderActionsPolicy` returned: a terminal order still gets a read-only
   * menu, never none (1.1e).
   */
  protected async copyOrderNumber(order: OrderSummaryResponse, event: Event): Promise<void> {
    event.stopPropagation();
    this.openOverflowFor.set(null);
    try {
      await navigator.clipboard.writeText(order.publicOrderNumber);
    } catch {
      // Clipboard access can be denied or unavailable (insecure context,
      // permissions, an older browser) — the number is still visible on the
      // row, so failing silently here costs an operator nothing they could
      // not already read.
    }
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
        if (requiresCancellationReason(order.status)) {
          void this.openCancelReasonDialog(order.orderId, version, scope);
        } else {
          // Synchronous, but still a "newer" dialog-open attempt an
          // in-flight openRejectDialog/openCancelReasonDialog fetch for a
          // different row must yield to (H2) — see dialogRequestId's doc.
          this.dialogRequestId += 1;
          this.dialog.set({ orderId: order.orderId, kind: 'cancel', version });
        }
        return;
      case 'ADVANCE':
        if (action.targetStatus) {
          void this.submitStateMutation(
            order.orderId,
            this.actionsApi.advance(scope, order.orderId, action.targetStatus, version),
          );
        }
        return;
      case 'COMPLETE':
        // §4.6/1.1e: built (wave P09) and, until this wave, only wired on the
        // order detail pane — this row menu fell to the `default` case below
        // and silently did nothing. `startCompletion` is the same
        // reason-resolution `order-detail-pane.ts`'s own `startCompletion`
        // uses: skip the dialog when zero or one reason is eligible, ask only
        // when a real choice exists.
        void this.startCompletion(order.orderId, version, order.fulfillmentMode ?? null, scope);
        return;
      case 'AMEND':
        // The amendment submenu's five dialogs live on the order detail pane
        // (wave P10), not the row — the same reason `order-detail-pane.ts`
        // prefers `COMPLETE` over the generic `ADVANCE` while this list keeps
        // using the simpler entry. Opening the order is a real, working
        // action rather than the silent no-op the `default` case below would
        // otherwise give a code `ORDER_AMEND` already reaches five roles for.
        this.openOrder(order.orderId);
        return;
      default:
      // An action code this client does not recognise yet — §4.2 says render
      // it, but there is nothing this client knows how to invoke for it.
    }
  }

  /**
   * Fetch-before-open (wave 24) — see `order-detail-pane.ts`'s identical
   * method for why. H2: guarded by {@link dialogRequestId} — a second
   * Reject/Cancel click on another row while this fetch is in flight bumps
   * the counter, so this call's result is dropped rather than silently
   * overwriting the dialog the operator's later click is waiting on.
   */
  private async openRejectDialog(
    orderId: string,
    version: number,
    scope: LocationScope,
  ): Promise<void> {
    const requestId = (this.dialogRequestId += 1);
    try {
      const reasons = await this.rejectReasonsApi.list(scope);
      if (requestId !== this.dialogRequestId) {
        return; // superseded by a newer dialog-open click (H2)
      }
      this.rejectReasons.set(reasons);
      this.dialog.set({ orderId, kind: 'reject', version });
    } catch (error) {
      if (requestId !== this.dialogRequestId) {
        return;
      }
      if (error instanceof ApiError) {
        this.actionNotice.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    }
  }

  /**
   * H2, fetch-before-open — the same rule {@link openRejectDialog} follows:
   * the tenant's active `CANCELLATION` reasons need to be on hand before the
   * picker has anything to show. Only reached for `CONFIRMED` and later
   * (see {@link onActionClick}'s CANCEL case) — `OrderActionsPolicy.
   * canCancelWithoutReason` refuses the free-text path from here on, so the
   * reasonless dialog is never offered for these statuses at all.
   *
   * Guarded by {@link dialogRequestId} against the fetch-before-open race:
   * two Cancel clicks on two different rows fire two independent,
   * uncached `GET .../reference-data` round trips, and ordinary network
   * jitter can resolve the first-clicked row's fetch *after* the
   * second-clicked row's. Without the guard, whichever resolves last wins
   * the shared `dialog`/`cancelReasons` signals — silently rebinding the
   * dialog to a row the operator did not just click.
   */
  private async openCancelReasonDialog(
    orderId: string,
    version: number,
    scope: LocationScope,
  ): Promise<void> {
    const requestId = (this.dialogRequestId += 1);
    try {
      const reasons = await this.referenceDataApi.list(scope, 'CANCELLATION');
      if (requestId !== this.dialogRequestId) {
        return; // superseded by a newer dialog-open click (H2)
      }
      this.cancelReasons.set(reasons);
      this.dialog.set({ orderId, kind: 'cancel-reason', version });
    } catch (error) {
      if (requestId !== this.dialogRequestId) {
        return;
      }
      if (error instanceof ApiError) {
        this.actionNotice.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    }
  }

  /**
   * §4.6: resolves how many `COMPLETION` reasons the tenant's registry has
   * active for this order's fulfilment mode, and only asks when there is a
   * real choice — mirrors `order-detail-pane.ts`'s own `startCompletion`
   * exactly, so the two surfaces cannot silently diverge on when a
   * completion dialog is warranted.
   *
   * Guarded by {@link dialogRequestId} against the same fetch-before-open
   * race {@link openRejectDialog}/{@link openCancelReasonDialog} guard:
   * two COMPLETE clicks on two different rows fire two independent,
   * uncached `GET .../reference-data` round trips, and ordinary network
   * jitter can resolve the first-clicked row's fetch *after* the
   * second-clicked row's. Without the guard, whichever resolves last wins
   * the shared `dialog`/`completionReasons` signals — or auto-submits —
   * silently rebinding the dialog to (or completing) a row the operator
   * did not just click.
   */
  private async startCompletion(
    orderId: string,
    version: number,
    fulfillmentMode: string | null,
    scope: LocationScope,
  ): Promise<void> {
    const requestId = (this.dialogRequestId += 1);
    try {
      const reasons = await this.referenceDataApi.list(scope, 'COMPLETION');
      if (requestId !== this.dialogRequestId) {
        return; // superseded by a newer dialog-open click (H2)
      }
      const eligible = reasons.filter(
        (reason) =>
          !reason.allowedFulfillmentModes ||
          reason.allowedFulfillmentModes.includes(fulfillmentMode ?? ''),
      );
      if (eligible.length === 0) {
        void this.submitCompletion(orderId, version);
      } else if (eligible.length === 1) {
        void this.submitCompletion(orderId, version, eligible[0].id);
      } else {
        this.completionReasons.set(eligible);
        this.dialog.set({ orderId, kind: 'complete', version });
      }
    } catch (error) {
      if (requestId !== this.dialogRequestId) {
        return;
      }
      if (error instanceof ApiError) {
        this.actionNotice.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    }
  }

  private async submitCompletion(
    orderId: string,
    version: number,
    reasonId?: string,
  ): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    await this.submitStateMutation(
      orderId,
      this.actionsApi.complete(scope, orderId, version, reasonId),
    );
  }

  protected onCompletionDialogConfirm(submission: OutcomeReasonSubmission): void {
    const state = this.dialog();
    if (!state) {
      return;
    }
    void this.submitCompletion(state.orderId, state.version, submission.reasonId).finally(() =>
      this.dialog.set(null),
    );
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

  /** H2: `CONFIRMED` onward — the registry-reasoned counterpart of {@link onCancelDialogConfirm}. */
  protected onCancelReasonDialogConfirm(submission: OutcomeReasonSubmission): void {
    const state = this.dialog();
    const scope = this.location.scope();
    if (!state || !scope) {
      return;
    }

    void this.submitStateMutation(
      state.orderId,
      this.actionsApi.cancelWithReason(
        scope,
        state.orderId,
        state.version,
        submission.reasonId,
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
      } else {
        this.announceApplied();
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
      this.announceApplied();
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

  /**
   * Says the mutation landed, in the one place the operator is certainly
   * looking — the shell's toast host (ADR 0101, row `X.17`).
   *
   * Not a notice band, because the two settle paths that most need confirming
   * are `Отменить` and `Отклонить`, and both close their dialog on success:
   * a confirmation rendered inside a component that no longer exists is not a
   * confirmation. The board itself re-reads a beat later, so this says only
   * that something applied — the row is the record of *what*.
   *
   * No order number and no customer data in the text (ADR 0029): a toast is
   * transient text on a terminal in a dining room.
   */
  private announceApplied(): void {
    this.toasts.show({ message: this.i18n.t('orders.action.applied'), tone: 'success' });
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

function decorate(order: OrderSummaryResponse, now: Date, policy: LatenessPolicy): OrderRow {
  const createdAt = new Date(order.createdAt);
  return {
    order,
    createdAt,
    severity: computeOrderSeverity(toSeverityFields(order, createdAt), now, policy),
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
    fulfillmentMode: order.fulfillmentMode,
    promisedAt: order.promisedAt ? new Date(order.promisedAt) : null,
    hasBlockedProcess: order.processAttention === 'MANUAL_ACTION_REQUIRED',
  };
}

/**
 * Wires `ServiceStatus` (§1.6, `shell/service-status.ts`) from the same
 * fetch: `open` per that service's own documented definition ("neither
 * completed nor cancelled"), `late` as anything {@link computeOrderSeverity}
 * flags — real LATE/AT_RISK/BLOCKED as of wave P06, resolved against the
 * same policy every other computation on this page now shares.
 */
function deriveServiceStatus(
  orders: readonly OrderSummaryResponse[],
  now: Date,
  policy: LatenessPolicy,
): { open: number; late: number } {
  let open = 0;
  let late = 0;
  for (const order of orders) {
    if (order.status !== 'COMPLETED' && order.status !== 'CANCELLED') {
      open += 1;
    }
    if (computeOrderSeverity(toCountable(order), now, policy).level !== 'NORMAL') {
      late += 1;
    }
  }
  return { open, late };
}
