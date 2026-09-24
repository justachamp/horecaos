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
import {
  ORDER_PAYMENT_STATUS_PROJECTIONS,
  paymentStatusProjectionLabel,
} from './order-payment-status';
import {
  EMPTY_ORDER_QUEUE_FILTERS,
  OrderQueueFilters,
  OrderQueueFilterState,
  boardQueryParams,
  filtersFromQueryParams,
  filtersToQueryParams,
  hasFilterQueryParams,
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
 * `COMPLETION` reason is eligible for the order's fulfilment mode. `'override'`
 * is the compensating-transition picker (wave 9, gap map row `1.1h`) — its own
 * `targetStatus` is the one the clicked `actions[]` entry already named, since
 * this dialog only ever asks for the mandatory registry reason.
 */
interface RowDialogState {
  readonly orderId: string;
  readonly kind: 'reject' | 'cancel' | 'cancel-reason' | 'complete' | 'override';
  readonly version: number;
  readonly targetStatus?: string;
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
 * `order-summary.ts` — is still short of §2.5's default set: no branch, no
 * customer, no line summary. What renders here is a selection checkbox,
 * severity rail, order number + severity caption, time, type/channel, total,
 * delivery fee, payment status, courier (gap map row 1.1: resolved against
 * the same roster the toolbar's own Курьер filter fetches), and status.
 * `Филиал` is additionally out of place for a different reason: this
 * endpoint is already scoped to one location, which is the spec's own
 * condition for auto-hiding that column.
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

  /**
   * Guards the {@link refresh} vs {@link loadMore} race (fix8 review 2,
   * a-orders): {@link refresh} always starts over from the board's own first
   * page under a brand-new cursor chain, so a {@link loadMore} page-2 fetch
   * that was already in flight when a refresh lands — the 10s poll and every
   * realtime frame call refresh unconditionally, including while an operator
   * is mid-scroll — must not be allowed to append onto (or clobber
   * `pageState`/`hasMore` back onto) rows a newer refresh already replaced.
   * Bumped by every {@link refresh} before it awaits anything; `loadMore`
   * captures the value it started under and discards its own result if the
   * generation has since moved on — the same pattern {@link dialogRequestId}
   * uses for H2 above.
   */
  private pageGeneration = 0;

  /** §2.4: the toolbar's own filters for the active tab. */
  protected readonly filters: Signal<OrderQueueFilters> = this.filterState.current;
  protected readonly paymentMethodCodes = PAYMENT_METHOD_CODES;
  /** «Оплата» (wave 10, gap map row `1.1c`): the board's own seven projections — the same fixed set the Оплата column renders. */
  protected readonly paymentStatusOptions = ORDER_PAYMENT_STATUS_PROJECTIONS;

  /** §2.4's Канал: every channel code this session has observed, only ever growing — see {@link refresh}. */
  protected readonly channelOptions = signal<readonly string[]>([]);
  private readonly observedChannelCodes = new Set<string>();

  /**
   * §2.4's Курьер filter, and gap map row 1.1's Курьер column: fetched once
   * per location, lazily — on first focus of the filter control (see {@link
   * ensureCourierRosterLoaded}), or as soon as {@link refresh} sees a row
   * that actually needs one to resolve (see {@link refresh}'s own call to
   * {@link ensureCourierRosterLoaded}) — never at start-up unconditionally,
   * for a location whose board never shows an assigned courier at all.
   */
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

  /** {@link syncUrlFromFilters}'s own guard against reacting to the navigation it just made. */
  private syncingUrlFromFilters = false;

  ngOnInit(): void {
    const querySub = this.route.queryParamMap.subscribe((params) => {
      const tab = params.get('tab');
      const resolved = isOrderTabId(tab) ? tab : DEFAULT_ORDER_TAB;
      const tabChanged = this.hasStarted && this.activeTab() !== resolved;
      this.activeTab.set(resolved);

      if (this.syncingUrlFromFilters) {
        // order-queue.ts's own filter -> URL push (syncUrlFromFilters): the
        // filter signal already holds this exact value, so there is
        // nothing to reconcile — see that method's own doc.
        this.syncingUrlFromFilters = false;
        return;
      }

      if (hasFilterQueryParams(params)) {
        // Gap map row 1.1c: a pasted link, or the browser's own
        // back/forward — the URL is this load's source of truth, not
        // localStorage's per-tab memory (still written there too, via
        // OrderQueueFilterState.setFilters, so the next cold visit
        // remembers it).
        this.filterState.setFilters(resolved, filtersFromQueryParams(params));
        if (this.hasStarted) {
          void this.refresh();
        }
        return;
      }

      // §2.4: filters are remembered **per tab**, and the board's one fetch
      // is filtered by whichever tab is active — so switching tabs switches
      // which remembered filter set is in effect and re-fetches under it.
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

    // Captured before the first await: a refresh started later (a filter
    // change, another poll tick, another realtime frame) bumps this and must
    // win — this call's own result, and any loadMore() page still chasing
    // the cursor chain it is about to replace, get silently dropped instead.
    const generation = ++this.pageGeneration;
    this.refreshing.set(true);
    try {
      const startState = firstPage(FETCH_LIMIT);
      const page = await this.fetchBoardPage(scope, startState);
      if (generation !== this.pageGeneration) {
        return;
      }
      const orders = page.items;
      const now = new Date();

      this.rows.set(orders.map((order) => decorate(order, now, this.latenessPolicy)));
      this.pageState.set(nextPage(startState, page) ?? startState);
      this.hasMore.set(page.nextCursor !== null);
      const tabCounts = await this.counts.forOrders(
        scope,
        orders.map(toCountable),
        now,
        this.latenessPolicy,
      );
      if (generation !== this.pageGeneration) {
        return;
      }
      this.tabCounts.set(tabCounts);
      this.lastUpdatedAt.set(now);
      this.lastError.set(null);
      this.denied.set(false);
      this.serviceStatus.set(deriveServiceStatus(orders, now, this.latenessPolicy), now);
      this.observeChannelCodes(orders);
      // Gap map row 1.1: the Курьер column needs the roster to resolve a
      // courierId this page actually carries — fetched here rather than
      // unconditionally at start-up, so a location whose board never shows
      // an assigned courier never spends the request. Idempotent against
      // the filter control's own first-focus fetch (courierRosterRequested).
      if (orders.some((candidate) => candidate.courierId)) {
        this.ensureCourierRosterLoaded();
      }
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
      // A superseded call's own `finally` must not flip `refreshing` back to
      // false while the newer call that superseded it is still in flight.
      if (generation === this.pageGeneration) {
        this.refreshing.set(false);
      }
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
   *
   * Guarded against {@link refresh} (fix8 review 2, a-orders): a refresh
   * already in flight is skipped outright (its own fresh first page is
   * coming regardless of whatever page this call would append), and — the
   * race an early check cannot close, since `refreshing()` can flip true
   * *after* this call's own fetch has already started — {@link
   * pageGeneration} is captured before the fetch and checked after it, so a
   * refresh that lands while this fetch is in flight makes its result a
   * silent no-op instead of appending a stale page onto (or clobbering
   * `pageState`/`hasMore` back onto) the board that refresh just replaced.
   */
  protected async loadMore(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.hasMore() || this.loadingMore() || this.refreshing()) {
      return;
    }
    const generation = this.pageGeneration;
    this.loadingMore.set(true);
    try {
      const state = this.pageState();
      const page = await this.fetchBoardPage(scope, state);
      if (generation !== this.pageGeneration) {
        // A refresh() started and landed while this page-2 fetch was in
        // flight and has already replaced rows/pageState/hasMore with its
        // own fresh first page — this page is paged relative to a cursor
        // chain that no longer exists. Drop it; the operator can click
        // "Load more" again under the fresh board if they still want more.
        return;
      }
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

  /**
   * Clears every filter query parameter alongside `tab` (gap map row 1.1c):
   * filters are remembered **per tab**, so switching tabs by clicking one
   * must hand control back to the newly-active tab's own `localStorage`
   * memory (`ngOnInit`'s `loadForTab` branch) rather than carrying the
   * previous tab's URL filters forward through `queryParamsHandling:
   * 'merge'`. Reuses `filtersToQueryParams(EMPTY_ORDER_QUEUE_FILTERS)`,
   * which is exactly "every filter parameter, cleared" since every field is
   * already at its default.
   */
  protected selectTab(tab: OrderTabId): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab, ...filtersToQueryParams(EMPTY_ORDER_QUEUE_FILTERS) },
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

  /**
   * §2.5 column 12 (Курьер), gap map row 1.1: `courierId` resolved against
   * the roster this page already fetches for the toolbar's own filter —
   * exactly the client-side join `order-detail-pane.ts`'s
   * `courierDisplayReference` performs, and `formatFee`'s own dash for "no
   * value" rather than an empty cell.
   */
  protected courierLabel(order: OrderSummaryResponse): string {
    if (!order.courierId) {
      return '—';
    }
    return (
      this.courierRoster().find((courier) => courier.courierId === order.courierId)?.displayReference ??
      order.courierId
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

  /**
   * Every filter handler below routes through this one method (gap map row
   * 1.1c): applies the patch, then pushes the resulting filter state into
   * the URL's own query parameters, so the address bar is always a link
   * that reproduces exactly what the operator sees. Never the fetch itself
   * — the search box's own 300ms debounce needs to delay {@link refresh}
   * without delaying the URL update, so each caller still fires its own
   * {@link refresh} afterward.
   */
  private applyFilterPatch(patch: Partial<OrderQueueFilters>): void {
    this.filterState.update(patch);
    this.syncUrlFromFilters();
  }

  /**
   * Pushes {@link filters}' current value into the URL (gap map row 1.1c),
   * via {@link OrderQueueFilterState}'s own `filtersToQueryParams`. Sets
   * {@link syncingUrlFromFilters} first so the `queryParamMap` subscription
   * in {@link ngOnInit} recognises this as its own navigation and does not
   * treat it as a pasted link to reconcile the filter state *from* (which
   * would be a no-op read of the value this call itself just wrote, but a
   * redundant board re-fetch all the same). `replaceUrl: true`: each filter
   * click replaces the last filter state in browser history rather than
   * adding a new entry — the same reason a debounced search box does not
   * push one history entry per keystroke.
   */
  private syncUrlFromFilters(): void {
    this.syncingUrlFromFilters = true;
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: filtersToQueryParams(this.filters()),
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /** §2.8: 300ms debounce before the search box re-fetches — every other filter refetches immediately on change. */
  protected onSearchInput(text: string): void {
    this.applyFilterPatch({ reference: text });
    if (this.searchDebounceHandle !== null) {
      clearTimeout(this.searchDebounceHandle);
    }
    this.searchDebounceHandle = setTimeout(() => void this.refresh(), SEARCH_DEBOUNCE_MS);
  }

  protected onDateRangeChange(range: DateRange): void {
    this.applyFilterPatch({ dateRange: { start: range.start, end: range.end } });
    void this.refresh();
  }

  protected onChannelChange(channelCode: string): void {
    this.applyFilterPatch({ channelCode: channelCode || null });
    void this.refresh();
  }

  /** «Источник» (wave 9, gap map row `1.1c`): `HORECAOS` vs `MARKETPLACE` — see `order-queue-filter-state.ts`'s own doc for why this is not the same control as Канал. */
  protected onOriginChange(origin: string): void {
    this.applyFilterPatch({ origin: origin ? (origin as OrderQueueFilters['origin']) : null });
    void this.refresh();
  }

  protected onFulfillmentModeChange(mode: string): void {
    this.applyFilterPatch({
      fulfillmentMode: mode ? (mode as OrderQueueFilters['fulfillmentMode']) : null,
    });
    void this.refresh();
  }

  protected onCourierChange(courierId: string): void {
    this.applyFilterPatch({ courierId: courierId || null });
    void this.refresh();
  }

  protected onPaymentMethodChange(code: string): void {
    this.applyFilterPatch({ paymentMethodCode: code || null });
    void this.refresh();
  }

  /** «Оплата» (wave 10, gap map row `1.1c`): `ordering.orders.payment_status_projection`, distinct from {@link onPaymentMethodChange}'s Способ оплаты. */
  protected onPaymentStatusChange(status: string): void {
    this.applyFilterPatch({ paymentStatus: status || null });
    void this.refresh();
  }

  protected onMineOnlyToggle(): void {
    const next = !this.filters().mineOnly;
    this.applyFilterPatch({ mineOnly: next });
    // §2.4: the client supplies its own subject; there is no server-side
    // `me`. Lazily loaded here rather than at start-up, so a session that
    // never touches this toggle never spends the extra request.
    void this.tenant.ensureLoaded().then(() => void this.refresh());
  }

  protected onResetFilters(): void {
    this.filterState.reset();
    this.syncUrlFromFilters();
    void this.refresh();
  }

  protected onChipRemoved(chipId: string): void {
    if (chipId === 'mine') {
      this.applyFilterPatch({ mineOnly: false });
    } else if (chipId === 'paymentMethod') {
      this.applyFilterPatch({ paymentMethodCode: null });
    } else if (chipId === 'paymentStatus') {
      this.applyFilterPatch({ paymentStatus: null });
    } else if (chipId === 'origin') {
      this.applyFilterPatch({ origin: null });
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
    if (filters.paymentStatus) {
      chips.push({
        id: 'paymentStatus',
        label: this.paymentStatusFilterLabel(filters.paymentStatus),
      });
    }
    if (filters.origin) {
      chips.push({ id: 'origin', label: this.originLabel(filters.origin) });
    }
    return chips;
  }

  protected originLabel(origin: 'HORECAOS' | 'MARKETPLACE'): string {
    switch (origin) {
      case 'HORECAOS':
        return this.i18n.t('orders.queue.filter.origin.HORECAOS');
      case 'MARKETPLACE':
        return this.i18n.t('orders.queue.filter.origin.MARKETPLACE');
    }
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

  /**
   * «Оплата»'s own filter option label (wave 10, gap map row `1.1c`) —
   * unlike {@link paymentStatusLabel} (§2.5 column 10), `NOT_REQUIRED` gets
   * a real word here rather than a dash: an operator who explicitly picked
   * it from the filter is choosing a value, not reading an empty cell.
   */
  protected paymentStatusFilterLabel(status: string): string {
    switch (status) {
      case 'NOT_REQUIRED':
        return this.i18n.t('orders.queue.filter.paymentStatus.NOT_REQUIRED');
      case 'PENDING':
        return this.i18n.t('orders.paymentStatus.PENDING');
      case 'AUTHORIZED':
        return this.i18n.t('orders.paymentStatus.AUTHORIZED');
      case 'CAPTURED':
        return this.i18n.t('orders.paymentStatus.CAPTURED');
      case 'FAILED':
        return this.i18n.t('orders.paymentStatus.FAILED');
      case 'VOIDED':
        return this.i18n.t('orders.paymentStatus.VOIDED');
      case 'REFUNDED':
        return this.i18n.t('orders.paymentStatus.REFUNDED');
      default:
        return status;
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
      ? actions.filter(
          (action) => !(action.action === 'ADVANCE' && action.targetStatus === 'COMPLETED'),
        )
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
      case 'OVERRIDE':
        // §0.2/§11.3, wave 9 row 1.1h: the clicked entry already names the
        // one compensating edge it offers (OrderActionsPolicy never emits
        // more than one OVERRIDE entry per status today) — the dialog only
        // asks for the mandatory registry reason, never a target of its own.
        if (action.targetStatus) {
          void this.openOverrideDialog(order.orderId, version, action.targetStatus, scope);
        }
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
      case 'ASSIGN_COURIER':
        // Gap map row 1.1e: the same treatment AMEND already gets, for the
        // same reason. The assign/unassign control (§1.2e) already lives on
        // the order detail pane, fetches the plan it needs
        // (`OrderDeliveryApi.delivery`) and posts to the existing
        // `DispatchController` manual-assignment endpoint — duplicating a
        // second picker here would be a second implementation of the same
        // compare-and-set to keep in sync, not a new capability. Opening the
        // order wires this row action to that existing control rather than
        // leaving `ASSIGN_COURIER` a declared-but-inert code the way it was
        // before this wave.
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
   * Fetch-before-open (wave 9 row `1.1h`), the same H2 rule {@link
   * openCancelReasonDialog} follows: `POST .../state-overrides` reuses the
   * `CANCELLATION` registry for its mandatory `reasonId` — see
   * `OperationsOrderController.StateOverrideRequest`'s own doc — so this
   * shares {@link cancelReasons} rather than fetching a dedicated list that
   * would be identical to it.
   */
  private async openOverrideDialog(
    orderId: string,
    version: number,
    targetStatus: string,
    scope: LocationScope,
  ): Promise<void> {
    const requestId = (this.dialogRequestId += 1);
    try {
      const reasons = await this.referenceDataApi.list(scope, 'CANCELLATION');
      if (requestId !== this.dialogRequestId) {
        return; // superseded by a newer dialog-open click (H2)
      }
      this.cancelReasons.set(reasons);
      this.dialog.set({ orderId, kind: 'override', version, targetStatus });
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

  /** §0.2/§11.3, wave 9 row `1.1h`: `POST .../state-overrides`, with the target {@link openOverrideDialog} captured and the operator's chosen registry reason. */
  protected onOverrideDialogConfirm(submission: OutcomeReasonSubmission): void {
    const state = this.dialog();
    const scope = this.location.scope();
    if (!state || !scope || state.kind !== 'override' || !state.targetStatus) {
      return;
    }

    void this.submitStateMutation(
      state.orderId,
      this.actionsApi.override(scope, state.orderId, state.targetStatus, state.version, submission.reasonId),
    ).finally(() => this.dialog.set(null));
  }

  /** The override dialog's title interpolation — the status it is about to restore, in this operator's own language. */
  protected overrideTitleValues(): Readonly<Record<string, string>> {
    return { status: this.statusLabel(this.dialog()?.targetStatus ?? '') };
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
