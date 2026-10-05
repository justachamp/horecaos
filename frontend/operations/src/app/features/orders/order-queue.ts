import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  Signal,
  computed,
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
import { LatenessPolicyTracker } from '../../core/lateness-policy-tracker';
import { TimeZone, formatClock, formatTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { RealtimeClient } from '../../core/realtime/realtime-client';
import { startVisibilityPoll } from '../../core/realtime/visibility-poll';
import { ServiceStatus } from '../../shell/service-status';
import { ShortcutRegistry } from '../../shared/keyboard/shortcut-registry';
import { DateRange, DateRangePicker } from '../../shared/ui/date-range-picker';
import { FilterBar, FilterBarChip } from '../../shared/ui/filter-bar';
import { OrderTableChip } from '../../shared/ui/order-table-chip/order-table-chip';
import { StatusPill } from '../../shared/ui/status-pill';
import { Toasts } from '../../shared/ui/toast';
import { CouriersApi, RosterEntryResponse } from '../couriers/couriers-api';
import { CustomerLabelResponse, OrderCrmLogApi } from '../reports/order-crm-log-api';
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
import { CountableOrder, OrderCounts, PolicyFor, TabCounts, zeroTabCounts } from './order-counts';
import { describeApiError, errorReference, mutationErrorNotice } from './order-errors';
import { OrderOutcomeReasonDialog, OutcomeReasonSubmission } from './order-outcome-reason-dialog';
import { BoardKeyAction, OrderBoardKeys, orderBoardScope } from './order-board-shortcuts';
import { OrderQueueBulkBar } from './order-queue-bulk-bar';
import { OrderQueueBulkResult } from './order-queue-bulk-result';
import { OrderQueueToolbar } from './order-queue-toolbar';
import { OrderRowActions, LabelledAction } from './order-row-actions';
import {
  ORDER_PAYMENT_STATUS_PROJECTIONS,
  paymentStatusProjectionLabel,
} from './order-payment-status';
import {
  EMPTY_ORDER_QUEUE_FILTERS,
  FISCAL_STATUS_ATTENTION,
  FISCAL_STATUS_OPTIONS,
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

/**
 * `GET .../orders/marketplace-bindings` (and its brand-wide twin) — one
 * aggregator binding the orders in scope arrived through (gap map row `1.1c`,
 * wave 16). The provider and installation name are integration's to give and
 * are null when it no longer resolves the binding.
 */
interface MarketplaceBindingOption {
  readonly bindingId: string;
  readonly providerType?: string | null;
  readonly displayName?: string | null;
  readonly orderCount: number;
}

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
  /**
   * The branch this row's order belongs to, captured when the dialog opened
   * (wave 16). On the «Все филиалы» board a row's branch is not the shell's
   * current one, and the mutation the dialog ends in must go to the row's own.
   */
  readonly scope: LocationScope;
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
 *
 * **«Все филиалы» (wave 16, gap map `1.1`).** For a principal who reads the
 * whole brand (the shell's branch picker has two or more options, and
 * `GET .../brands/{b}/orders/board` answers rather than refusing), a mode
 * select puts the queue on the brand-scoped board — the branch board's own
 * statement over every branch, or over the one the Филиал filter names. The
 * Филиал column then appears (§2.5 hides it for a single branch), and every
 * row's actions, dialogs and «open» act on the **row's own branch**, not the
 * shell's current one. Bulk selection stays a single-branch feature: its
 * endpoint is per branch, and a mixed selection would need one request per
 * branch with its own partial-failure panel. A location-scoped principal never
 * sees the mode; a 403 from the brand board switches it off for the session
 * rather than breaking the board.
 */
@Component({
  selector: 'q-order-queue',
  imports: [
    TPipe,
    OrderTableChip,
    OrderReasonDialog,
    OrderRejectReasonDialog,
    OrderOutcomeReasonDialog,
    StatusPill,
    FilterBar,
    DateRangePicker,
    OrderQueueBulkBar,
    OrderQueueBulkResult,
    OrderQueueToolbar,
    OrderRowActions,
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
  private readonly crmLogApi = inject(OrderCrmLogApi);
  private readonly serviceStatus = inject(ServiceStatus);
  protected readonly realtime = inject(RealtimeClient);
  private readonly toasts = inject(Toasts);
  private readonly i18n = inject(I18n);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly shortcuts = inject(ShortcutRegistry);
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

  /**
   * Set once the brand-scoped board answers 403 for this session: the principal's
   * grant stops at a branch (ADR 0025 — scopes cover downwards, never up), so
   * «Все филиалы» is withdrawn and the queue keeps the branch board. Not
   * remembered across reloads — a grant can be widened while a wallboard is
   * open, and the next visit simply asks again.
   */
  private readonly brandBoardRefused = signal(false);

  /**
   * Every branch the operator may switch to — `CurrentLocation`'s own picker
   * options, populated only along its brand-resolution path (an operator whose
   * scope is one `LOCATION` grant gets none).
   */
  protected readonly branchOptions = computed(() => this.location.options());

  /** «Все филиалы» is offered to a multi-branch principal the brand board has not refused. */
  protected readonly canViewAllBranches = computed(
    () => this.branchOptions().length > 1 && !this.brandBoardRefused(),
  );

  /** The queue is reading the brand-scoped board right now. */
  protected readonly allBranchesActive = computed(
    () => this.canViewAllBranches() && this.filters().allBranches,
  );
  protected readonly paymentMethodCodes = PAYMENT_METHOD_CODES;
  /** «Фискализация» (wave 16, gap map `1.1c`): `ATTENTION` first, then `fiscal.fiscal_documents`' own statuses. */
  protected readonly fiscalStatusOptions: readonly string[] = [
    FISCAL_STATUS_ATTENTION,
    ...FISCAL_STATUS_OPTIONS,
  ];
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

  /**
   * §2.4's «Агрегатор» options (gap map `1.1c`, wave 16): the provider bindings
   * the orders in scope arrived through, fetched lazily on first focus of the
   * control — or as soon as a binding filter is already set (a pasted link), so
   * its chip can be named — and again whenever the scope they were read for
   * changes (the branch, or «Все филиалы» and its branch filter).
   */
  protected readonly bindingOptions = signal<readonly MarketplaceBindingOption[]>([]);
  private bindingOptionsScopeKey: string | null = null;

  /**
   * The lateness policy of the shell's branch and of every branch this board has
   * shown a row of (wave 16): on «Все филиалы» a row is judged late by **its
   * branch's** policy, the same one the «Только опаздывающие» filter applied
   * server-side, so a row's tint and the filter cannot disagree. The board stays
   * open all shift, so {@link refreshPolicy} and {@link ensureBranchPolicies}
   * re-read them on the poll (each at most once a minute) rather than holding the
   * start-up copy: an owner's edit reaches an open board without a reload, and a
   * read that failed is asked for again instead of pinning the platform default.
   */
  private readonly policies = new LatenessPolicyTracker(this.latenessPolicyApi);

  /**
   * Gap map row 1.1's Клиент column: name in full and masked phone for the
   * orders currently loaded, keyed by `orderId` — `POST
   * .../orders/crm-log/labels` (7.2a's own capability, `ORDER_READ`),
   * batched per page after {@link refresh}/{@link loadMore} rather than
   * baked into `OrderSummaryResponse` the way the Курьер column is: unlike
   * `courierId` (an opaque, non-PII id `fulfillment` already hands back),
   * this is a second, PII-carrying read, kept out of the board query itself
   * the same way the reports page's own CRM log join stays a second request
   * (`order-crm-log-api.ts`'s own doc). A staff subject who can see the
   * board but holds only LOCATION-scope ORDER_READ (this endpoint is
   * TENANT-scoped, matching GET .../orders/crm-log) simply sees every row's
   * Клиент cell render `—` rather than a broken board — the same
   * graceful-degrade `ensureCourierRosterLoaded` already takes.
   */
  protected readonly customerLabels = signal<ReadonlyMap<string, CustomerLabelResponse>>(new Map());

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
   * The shell branch's resolved `ordering.lateness` policy (wave P06), as of the
   * last {@link refreshPolicy}. Every {@link decorate} call in one refresh reads
   * the same object, which is the whole point of "one policy" for row `X.39`;
   * the next refresh past the tracker's max age swaps in what the owner
   * published since.
   */
  private latenessPolicy: LatenessPolicy = PLATFORM_DEFAULT_LATENESS_POLICY;

  /**
   * The tenant's own `#rrggbb` for a late order (row `X.39`), or null for the
   * design-system `--q-sla-late` token. Applied to `LATE` rows only — see
   * {@link lateColourFor}.
   */
  protected readonly lateColour = signal<string | null>(null);

  /** Guards the tab-change refetch below from also firing on the very first route resolution — {@link start} already fetches once. */
  private hasStarted = false;

  /** {@link syncUrlFromFilters}'s own guard against reacting to the navigation it just made. */
  private syncingUrlFromFilters = false;

  ngOnInit(): void {
    // orders.md §2.12: the board is keyboard-first. Registered while the board is on screen, so the
    // cheat-sheet over it names these keys and over any other screen does not.
    this.shortcuts.register(orderBoardScope(this.boardKeys), this.destroyRef);

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
    await this.refresh();
    this.hasStarted = true;
  }

  /**
   * The tenant's late colour for this row, or null. Only a row that is `LATE`
   * takes it: `BLOCKED` and the approval deadline share the danger step but mean
   * something else, and a tenant who picks green for late has not asked to
   * repaint them. The row sets it inline as `--q-sla-late` (rail, caption text)
   * and `order-row--late-custom` (global `styles.css`) derives the row tint from the same colour, so the
   * rail, the caption and the background agree rather than leaving the design
   * system's red tint behind another colour's rail.
   */
  protected lateColourFor(row: OrderRow): string | null {
    return row.severity.level === 'LATE' ? this.lateColour() : null;
  }

  /** Also the manual refresh control (§1.6: "the legacy dashboard's `FaRepeat` button, which staff use"). */
  protected manualRefresh(): void {
    void this.refresh();
  }

  // --------------------------------------------------------------- §2.12 keyboard

  /** Every focusable row, in table order. `data-order-id` is what ties a row to its order. */
  private rowElements(): readonly HTMLElement[] {
    return [...this.host.nativeElement.querySelectorAll<HTMLElement>('tr[data-order-id]')];
  }

  /** The row that has focus, or the row a focused control inside it (a checkbox, an action button) belongs to. */
  private focusedRowElement(): HTMLElement | null {
    const active = document.activeElement;
    if (!(active instanceof HTMLElement) || !this.host.nativeElement.contains(active)) {
      return null;
    }
    return active.closest<HTMLElement>('tr[data-order-id]');
  }

  /** The order the keyboard is pointing at: the focused row's. Null when focus is anywhere else. */
  private focusedOrder(): OrderSummaryResponse | null {
    const orderId = this.focusedRowElement()?.dataset['orderId'];
    return orderId
      ? (this.rows().find((row) => row.order.orderId === orderId)?.order ?? null)
      : null;
  }

  /**
   * The server-supplied action a key stands for on the focused order. `x` is the cancel dialog; an
   * order still awaiting approval offers a refusal instead of a cancellation, and that is the dialog
   * `x` opens for it.
   */
  private keyActionFor(
    order: OrderSummaryResponse,
    action: BoardKeyAction,
  ): OrderActionResponse | null {
    const offered = this.rowActions(order);
    const find = (code: string): OrderActionResponse | null =>
      offered.find((candidate) => candidate.action === code) ?? null;
    return action === 'CANCEL' ? (find('CANCEL') ?? find('REJECT')) : find(action);
  }

  private readonly boardKeys: OrderBoardKeys = {
    locale: () => this.i18n.locale(),
    hasRows: () => this.visibleRows().length > 0,
    menuOpen: () => this.openOverflowFor() !== null,
    canAct: (action) => {
      const order = this.focusedOrder();
      return (
        order !== null &&
        !this.isRowBusy(order.orderId) &&
        this.keyActionFor(order, action) !== null
      );
    },
    canToggleSelection: () => this.canSelectOrders() && this.focusedOrder() !== null,
    hasSelection: () => this.selectionCount() > 0,
    searchField: () =>
      this.host.nativeElement.querySelector<HTMLInputElement>(
        '[data-testid="order-queue-filter-search"]',
      ),
    move: (delta) => this.moveRowFocus(delta),
    act: (action, event) => {
      const order = this.focusedOrder();
      const offered = order ? this.keyActionFor(order, action) : null;
      if (order && offered) {
        this.onActionClick(order, offered, event);
      }
    },
    toggleSelection: (event) => {
      const order = this.focusedOrder();
      if (order) {
        this.toggleRowSelection(order.orderId, event);
      }
    },
    selectTab: (position) => {
      const tab = this.tabs[position - 1];
      if (tab) {
        this.selectTab(tab.id);
      }
    },
    clearSelection: () => this.clearSelection(),
    clearSearch: (field) => {
      field.value = '';
      this.onSearchInput('');
      field.blur();
    },
    newOrder: () => void this.router.navigateByUrl('/orders/new'),
    refresh: () => this.manualRefresh(),
  };

  /** `j`/`k` and the arrows: moves real focus, so Enter, Space and the action keys then act on that row. */
  private moveRowFocus(delta: 1 | -1): void {
    const rows = this.rowElements();
    if (rows.length === 0) {
      return;
    }
    const current = this.focusedRowElement();
    const index = current ? rows.indexOf(current) : -1;
    const target =
      index < 0
        ? delta === 1
          ? 0
          : rows.length - 1
        : Math.min(rows.length - 1, Math.max(0, index + delta));
    rows[target].focus();
    rows[target].scrollIntoView?.({ block: 'nearest' });
  }

  /**
   * The lateness policy for an order (wave 16): its own branch's on «Все
   * филиалы» — the one the «Только опаздывающие» filter applied server-side —
   * otherwise the branch's, resolved at start-up. An arrow property rather than
   * a method so it can be handed to {@link decorate} and {@link OrderCounts}
   * as-is.
   */
  private readonly policyFor = (order: { readonly locationId?: string | null }): LatenessPolicy =>
    order.locationId && this.policies.isLoaded(order.locationId)
      ? this.policies.policy(order.locationId)
      : this.latenessPolicy;

  /**
   * Re-reads the shell branch's policy (at most once a minute — the tracker
   * holds a younger read) and keeps {@link latenessPolicy} and {@link
   * lateColour} on it. Never rejects; a failed read leaves the last policy read,
   * or the platform default before the first one.
   */
  private async refreshPolicy(scope: LocationScope): Promise<void> {
    await this.policies.refresh(scope);
    this.latenessPolicy = this.policies.policy(scope.locationId);
    this.lateColour.set(this.latenessPolicy.lateColour ?? null);
  }

  /**
   * Re-reads the policy of every branch on this page (each at most once a minute,
   * and a branch never read before at once). `LatenessPolicyApi.read` swallows a
   * failure, so this never throws: an unread branch is judged by the shell
   * branch's policy until a read succeeds, and the next refresh asks again.
   */
  private async ensureBranchPolicies(
    scope: LocationScope,
    orders: readonly OrderSummaryResponse[],
  ): Promise<void> {
    const locationIds = [
      ...new Set(orders.map((order) => order.locationId).filter((id): id is string => !!id)),
    ];
    await Promise.all(
      locationIds.map((locationId) => this.policies.refresh({ ...scope, locationId })),
    );
  }

  /**
   * One page of the board under the toolbar's current filters, cursor-`state`'s
   * own window — the branch board, or (on «Все филиалы») the brand-scoped one.
   *
   * A 403 on the brand board's **first** page means the principal's grant stops
   * at a branch: «Все филиалы» is withdrawn for the session and the same page
   * is read from the branch board instead. Only the first page, because a
   * cursor minted by one board is meaningless to the other.
   */
  private async fetchBoardPage(
    scope: LocationScope,
    state: CursorState,
  ): Promise<Page<OrderSummaryResponse>> {
    const allBranches = this.allBranchesActive();
    const params = boardQueryParams({ ...this.filters(), allBranches }, this.tenant.subject());
    const path = allBranches
      ? operationsPaths.brandOrderBoard(scope)
      : operationsPaths.orderBoard(scope);
    try {
      const result = await firstValueFrom(
        this.api.get<Page<OrderSummaryResponse>>(path, {
          params: { ...params, ...pageParams(state) },
        }),
      );
      return result.value ?? { items: [], nextCursor: null };
    } catch (error) {
      if (
        allBranches &&
        state.cursor === null &&
        error instanceof ApiError &&
        error.status === 403
      ) {
        this.brandBoardRefused.set(true);
        return this.fetchBoardPage(scope, state);
      }
      throw error;
    }
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
      // The policy read rides alongside the board's own: past the tracker's max
      // age it is one more request in flight, never one more wait in a row.
      const [page] = await Promise.all([
        this.fetchBoardPage(scope, startState),
        this.refreshPolicy(scope),
      ]);
      if (generation !== this.pageGeneration) {
        return;
      }
      const orders = page.items;
      const allBranches = this.allBranchesActive();
      if (allBranches) {
        await this.ensureBranchPolicies(scope, orders);
        if (generation !== this.pageGeneration) {
          return;
        }
      }
      const now = new Date();

      this.rows.set(orders.map((order) => decorate(order, now, this.policyFor)));
      this.pageState.set(nextPage(startState, page) ?? startState);
      this.hasMore.set(page.nextCursor !== null);
      // «Все филиалы» with one branch narrowed counts that branch; with none it
      // reads the brand's totals. The branch board is unchanged.
      const narrowedTo = allBranches ? this.filters().locationId : null;
      const tabCounts = await this.counts.forOrders(
        narrowedTo ? { ...scope, locationId: narrowedTo } : scope,
        orders.map(toCountable),
        now,
        this.policyFor,
        allBranches && !narrowedTo,
      );
      if (generation !== this.pageGeneration) {
        return;
      }
      this.tabCounts.set(tabCounts);
      this.lastUpdatedAt.set(now);
      this.lastError.set(null);
      this.denied.set(false);
      // The shell's service indicator is one branch's ("open / late now"); a
      // page of every branch's orders is not that, so it is left alone.
      if (!allBranches) {
        this.serviceStatus.set(deriveServiceStatus(orders, now, this.policyFor), now);
      }
      this.observeChannelCodes(orders);
      // A binding filter that arrived in a link needs its options to be
      // named in the chip; nothing else reads them until the control is focused.
      if (this.filters().marketplaceBindingId) {
        this.ensureBindingOptionsLoaded();
      }
      // Gap map row 1.1: the Курьер column needs the roster to resolve a
      // courierId this page actually carries — fetched here rather than
      // unconditionally at start-up, so a location whose board never shows
      // an assigned courier never spends the request. Idempotent against
      // the filter control's own first-focus fetch (courierRosterRequested).
      if (orders.some((candidate) => candidate.courierId)) {
        this.ensureCourierRosterLoaded();
      }
      // Merged, never reset: a repeat 10s poll's own first page overlaps the
      // previous one far more often than not, and re-decrypting a name
      // already in hand on every tick would be pure waste. An entry for an
      // orderId that has since scrolled off the board lingers harmlessly —
      // nothing ever reads it back out except by that same orderId, the same
      // "only ever grows" trade-off `observedChannelCodes` already makes.
      void this.loadCustomerLabels(scope.tenantId, orders);
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
      if (this.allBranchesActive()) {
        await this.ensureBranchPolicies(scope, page.items);
        if (generation !== this.pageGeneration) {
          return;
        }
      }
      const now = new Date();
      const appended = page.items.map((order) => decorate(order, now, this.policyFor));
      this.rows.set([...this.rows(), ...appended]);
      this.pageState.set(nextPage(state, page) ?? state);
      this.hasMore.set(page.nextCursor !== null);
      this.observeChannelCodes(page.items);
      // Appended, never reset -- unlike refresh()'s own fresh first page,
      // loadMore extends what is already on screen, so the labels already
      // resolved for it must survive.
      void this.loadCustomerLabels(scope.tenantId, page.items);
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

  /**
   * Opens the order detail. The detail pane reads its order through the
   * shell's current branch, so an order of another branch — reachable only on
   * «Все филиалы» — first switches the console to that branch (the same switch
   * the shell's picker makes), and only then navigates. Without it the pane
   * would ask the wrong branch for the order and answer "not found".
   */
  protected openOrder(orderId: string, locationId?: string | null): void {
    const current = this.location.scope();
    if (locationId && current && locationId !== current.locationId) {
      this.location.selectLocation(locationId);
    }
    void this.router.navigate([orderId], {
      relativeTo: this.route,
      queryParamsHandling: 'preserve',
    });
  }

  /**
   * The scope a row's own mutation must go to: the row's branch, which on the
   * branch board is the shell's and on «Все филиалы» may be any other. Null
   * only when the console has no scope at all.
   */
  private rowScope(order: OrderSummaryResponse): LocationScope | null {
    const scope = this.location.scope();
    if (!scope) {
      return null;
    }
    return order.locationId && order.locationId !== scope.locationId
      ? { ...scope, locationId: order.locationId }
      : scope;
  }

  /** The Филиал column's text: the branch's display name from the shell's roster, else the id's head, never blank. */
  protected branchLabel(order: OrderSummaryResponse): string {
    if (!order.locationId) {
      return '—';
    }
    return (
      this.branchOptions().find((option) => option.id === order.locationId)?.displayName ??
      order.locationId.slice(0, 8)
    );
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
      this.courierRoster().find((courier) => courier.courierId === order.courierId)
        ?.displayReference ?? order.courierId
    );
  }

  /**
   * Gap map row 1.1's own Клиент column: the account's or guest's name in
   * full, never masked (orders.md §1.5, §3.7) — the identical rule
   * `order-rows-table.ts`'s own `customerLabel` states for the reports
   * page's CRM column, mirrored here rather than shared, since that one
   * reads a `Map` built from a date-range fetch and this one from a
   * per-page batch. A row whose label has not resolved yet (still in
   * flight, or the caller lacks the capability) renders `—`, matching
   * {@link courierLabel}'s own "no value" dash.
   */
  protected customerLabel(order: OrderSummaryResponse): string {
    const label = this.customerLabels().get(order.orderId);
    if (!label) {
      return '—';
    }
    if (label.customerType === 'GUEST') {
      return this.i18n.t('reports.orders.column.customer.guest');
    }
    return label.customerName ?? this.i18n.t('reports.orders.column.customer.account');
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

  /**
   * «Все филиалы» vs this branch (wave 16, gap map `1.1`). Switching board
   * drops the branch filter (it only means something on the brand board), the
   * selection (its endpoint is per branch) and the binding options (they were
   * read for the other scope), then re-reads from the first page — a cursor
   * minted by one board is meaningless to the other.
   */
  protected onBranchModeChange(mode: string): void {
    this.applyFilterPatch({ allBranches: mode === 'ALL', locationId: null });
    this.clearSelection();
    this.bindingOptionsScopeKey = null;
    void this.refresh();
  }

  /** «Филиал» (wave 16): narrows the brand board to one branch. */
  protected onBranchChange(locationId: string): void {
    this.applyFilterPatch({ locationId: locationId || null });
    this.bindingOptionsScopeKey = null;
    void this.refresh();
  }

  /** «Агрегатор» (wave 16, gap map `1.1c`): one provider binding, finer than {@link onOriginChange}. */
  protected onBindingChange(bindingId: string): void {
    this.applyFilterPatch({ marketplaceBindingId: bindingId || null });
    void this.refresh();
  }

  protected onLateOnlyToggle(): void {
    this.applyFilterPatch({ lateOnly: !this.filters().lateOnly });
    void this.refresh();
  }

  protected onProblemOnlyToggle(): void {
    this.applyFilterPatch({ problemOnly: !this.filters().problemOnly });
    void this.refresh();
  }

  protected onCallbackRequestedToggle(): void {
    this.applyFilterPatch({ callbackRequested: !this.filters().callbackRequested });
    void this.refresh();
  }

  /** «Фискализация» (wave 16): a fiscal document of the order in this status, or `ATTENTION` for failed + blocked. */
  protected onFiscalStatusChange(status: string): void {
    this.applyFilterPatch({ fiscalStatus: status || null });
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
    } else if (chipId === 'branch') {
      this.applyFilterPatch({ locationId: null });
      this.bindingOptionsScopeKey = null;
    } else if (chipId === 'binding') {
      this.applyFilterPatch({ marketplaceBindingId: null });
    } else if (chipId === 'late') {
      this.applyFilterPatch({ lateOnly: false });
    } else if (chipId === 'problem') {
      this.applyFilterPatch({ problemOnly: false });
    } else if (chipId === 'callback') {
      this.applyFilterPatch({ callbackRequested: false });
    } else if (chipId === 'fiscal') {
      this.applyFilterPatch({ fiscalStatus: null });
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
    if (this.allBranchesActive() && filters.locationId) {
      chips.push({ id: 'branch', label: this.branchName(filters.locationId) });
    }
    if (filters.marketplaceBindingId) {
      chips.push({ id: 'binding', label: this.bindingLabel(filters.marketplaceBindingId) });
    }
    if (filters.lateOnly) {
      chips.push({ id: 'late', label: this.i18n.t('orders.queue.filter.late.label') });
    }
    if (filters.problemOnly) {
      chips.push({ id: 'problem', label: this.i18n.t('orders.queue.filter.problem.label') });
    }
    if (filters.callbackRequested) {
      chips.push({ id: 'callback', label: this.i18n.t('orders.queue.filter.callback.label') });
    }
    if (filters.fiscalStatus) {
      chips.push({ id: 'fiscal', label: this.fiscalStatusLabel(filters.fiscalStatus) });
    }
    return chips;
  }

  /** A branch's display name from the shell's roster, or the id's head when the roster does not list it. */
  protected branchName(locationId: string): string {
    return (
      this.branchOptions().find((option) => option.id === locationId)?.displayName ??
      locationId.slice(0, 8)
    );
  }

  /** «Фискализация»'s option label — `fiscal.fiscal_documents`' own status words, plus the failed + blocked shortcut. */
  protected fiscalStatusLabel(status: string): string {
    switch (status) {
      case FISCAL_STATUS_ATTENTION:
        return this.i18n.t('orders.queue.filter.fiscal.ATTENTION');
      case 'PENDING':
        return this.i18n.t('orders.queue.filter.fiscal.PENDING');
      case 'SUBMITTED':
        return this.i18n.t('orders.queue.filter.fiscal.SUBMITTED');
      case 'ISSUED':
        return this.i18n.t('orders.queue.filter.fiscal.ISSUED');
      case 'FAILED':
        return this.i18n.t('orders.queue.filter.fiscal.FAILED');
      case 'BLOCKED':
        return this.i18n.t('orders.queue.filter.fiscal.BLOCKED');
      case 'NOT_APPLICABLE':
        return this.i18n.t('orders.queue.filter.fiscal.NOT_APPLICABLE');
      default:
        return status;
    }
  }

  /**
   * An aggregator binding by the name integration gave it — installation name
   * and provider — falling back to the id's head for a binding the options do
   * not (or no longer) name, never a blank chip.
   */
  protected bindingLabel(bindingId: string): string {
    const option = this.bindingOptions().find((candidate) => candidate.bindingId === bindingId);
    return option ? bindingOptionLabel(option) : bindingId.slice(0, 8);
  }

  protected bindingOptionLabel(option: MarketplaceBindingOption): string {
    return bindingOptionLabel(option);
  }

  /** Whether the loaded options already name this binding — so a filter set from a link still shows as selected before they load. */
  protected bindingKnown(bindingId: string): boolean {
    return this.bindingOptions().some((candidate) => candidate.bindingId === bindingId);
  }

  /**
   * §2.4's «Агрегатор» options: read lazily — on first focus of the control, or
   * as soon as a binding filter is already set so its chip can be named — for
   * the scope currently on screen (this branch, or the brand and optionally the
   * one branch narrowed). A refusal or failure leaves the list empty rather
   * than breaking the toolbar, the stance {@link ensureCourierRosterLoaded}
   * takes too.
   */
  protected ensureBindingOptionsLoaded(): void {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    const allBranches = this.allBranchesActive();
    const narrowedTo = allBranches ? this.filters().locationId : null;
    const key = allBranches ? `brand:${narrowedTo ?? '*'}` : `branch:${scope.locationId}`;
    if (this.bindingOptionsScopeKey === key) {
      return;
    }
    this.bindingOptionsScopeKey = key;
    const path = allBranches
      ? operationsPaths.brandOrderMarketplaceBindings(scope)
      : operationsPaths.orderMarketplaceBindings(scope);
    void firstValueFrom(
      this.api.get<{ readonly items: readonly MarketplaceBindingOption[] }>(path, {
        params: narrowedTo ? { locationId: narrowedTo } : {},
      }),
    )
      .then((result) => {
        if (this.bindingOptionsScopeKey === key) {
          this.bindingOptions.set(result.value?.items ?? []);
        }
      })
      .catch(() => {
        // Forget the key so the next focus tries again; an empty list is the degrade.
        if (this.bindingOptionsScopeKey === key) {
          this.bindingOptionsScopeKey = null;
        }
      });
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

  /**
   * Gap map row 1.1's Клиент column: fetches the label for every order in
   * `orders` not already resolved and merges it into {@link customerLabels}.
   * Gated client-side on `ORDER_READ` — the same capability {@code
   * OrderCrmLogController.labels} itself requires — purely to skip a call a
   * denied caller would only get refused; the server re-checks it
   * regardless (ADR 0025). A refusal (a staff subject with only
   * LOCATION-scope `ORDER_READ`, this endpoint being TENANT-scoped) is
   * swallowed exactly like {@link ensureCourierRosterLoaded}'s own catch —
   * every Клиент cell simply renders `—` rather than breaking the board.
   */
  private async loadCustomerLabels(
    tenantId: string,
    orders: readonly OrderSummaryResponse[],
  ): Promise<void> {
    if (!this.capabilities.has('ORDER_READ')) {
      return;
    }
    const known = this.customerLabels();
    const orderIds = [...new Set(orders.map((order) => order.orderId))].filter(
      (id) => !known.has(id),
    );
    if (orderIds.length === 0) {
      return;
    }
    try {
      const labels = await this.crmLogApi.customerLabels(tenantId, orderIds);
      this.customerLabels.update((current) => {
        const next = new Map(current);
        for (const label of labels) {
          next.set(label.orderId, label);
        }
        return next;
      });
    } catch {
      // Graceful degrade — see this method's own doc.
    }
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
    return this.capabilities.has('ORDER_BULK_ACTION') && !this.allBranchesActive();
  }

  /**
   * «Все филиалы» shows no selection column: `POST .../orders/bulk-actions` is
   * one branch's endpoint, and a selection mixing branches would need a request
   * per branch and a merged partial-failure panel. Said in a line under the
   * toolbar — for an operator who could bulk-act on one branch — rather than
   * leaving the missing checkboxes unexplained.
   */
  protected bulkUnavailableOnAllBranches(): boolean {
    return this.capabilities.has('ORDER_BULK_ACTION') && this.allBranchesActive();
  }

  /** The table's column count, for the empty row's `colspan`. */
  protected columnCount(): number {
    return 11 + (this.canSelectOrders() ? 1 : 0) + (this.allBranchesActive() ? 1 : 0);
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

  /** Handed to the bulk-result panel as functions, which must keep this instance as `this`. */
  protected readonly bulkItemLabeller = (orderId: string): string =>
    this.bulkResultItemLabel(orderId);
  protected readonly bulkProblemLabeller = (code: string | null | undefined): string =>
    this.bulkProblemLabel(code);

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
      case 'CATCHWEIGHT_NOT_RECONCILED':
        return this.i18n.t('orders.queue.bulk.problem.CATCHWEIGHT_NOT_RECONCILED');
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

  /** The inline buttons of a row's actions cell, each with the words the operator reads for it. */
  protected inlineActionItems(order: OrderSummaryResponse): readonly LabelledAction[] {
    return this.inlineActions(order).map((action) => ({
      action,
      label: this.actionLabel(order, action),
    }));
  }

  protected overflowActionItems(order: OrderSummaryResponse): readonly LabelledAction[] {
    return this.overflowActions(order).map((action) => ({
      action,
      label: this.actionLabel(order, action),
    }));
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
    this.openOrder(order.orderId, order.locationId);
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
    const scope = this.rowScope(order);
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
          this.dialog.set({ orderId: order.orderId, kind: 'cancel', version, scope });
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
        this.openOrder(order.orderId, order.locationId);
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
        this.openOrder(order.orderId, order.locationId);
        return;
      case 'RESOLVE':
        // Gap map row 1.1e: the same treatment AMEND/ASSIGN_COURIER already
        // get. `q-order-amendment-confirm-dialog` needs the pending
        // amendment's own id and version, which this row does not carry —
        // only `order-detail-pane.ts`'s `onActionClick` fetches the
        // amendment history and opens it, so this row action's job is
        // exactly the same as those two: get the operator to the order.
        this.openOrder(order.orderId, order.locationId);
        return;
      case 'ISSUE_INVOICE':
        // Gap map row 1.1e, «Выставить счёт» (orders.md §4.9): the re-issue
        // form — link or invoice push, and the result with the payable link —
        // lives in the order detail's payment panel (`q-order-payment-panel`,
        // wave P12), over the existing `POST .../payment/re-presentations`.
        // A second form here would be a second implementation of the same
        // idempotent call, so this row action takes the operator to the order
        // and `order-detail-pane.ts`'s own handler opens that form.
        this.openOrder(order.orderId, order.locationId);
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
      this.dialog.set({ orderId, kind: 'reject', version, scope });
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
      this.dialog.set({ orderId, kind: 'cancel-reason', version, scope });
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
      this.dialog.set({ orderId, kind: 'override', version, targetStatus, scope });
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
        void this.submitCompletion(orderId, version, scope);
      } else if (eligible.length === 1) {
        void this.submitCompletion(orderId, version, scope, eligible[0].id);
      } else {
        this.completionReasons.set(eligible);
        this.dialog.set({ orderId, kind: 'complete', version, scope });
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
    scope: LocationScope,
    reasonId?: string,
  ): Promise<void> {
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
    void this.submitCompletion(
      state.orderId,
      state.version,
      state.scope,
      submission.reasonId,
    ).finally(() => this.dialog.set(null));
  }

  /** §0.2/§11.3, wave 9 row `1.1h`: `POST .../state-overrides`, with the target {@link openOverrideDialog} captured and the operator's chosen registry reason. */
  protected onOverrideDialogConfirm(submission: OutcomeReasonSubmission): void {
    const state = this.dialog();
    if (!state || state.kind !== 'override' || !state.targetStatus) {
      return;
    }
    const scope = state.scope;

    void this.submitStateMutation(
      state.orderId,
      this.actionsApi.override(
        scope,
        state.orderId,
        state.targetStatus,
        state.version,
        submission.reasonId,
      ),
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
    if (!state) {
      return;
    }
    const scope = state.scope;

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
    if (!state) {
      return;
    }
    const scope = state.scope;

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
    if (!state) {
      return;
    }
    const scope = state.scope;

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

/** "Wolt Chilonzor · WOLT" — the installation's name and the provider, whichever integration gave. */
function bindingOptionLabel(option: MarketplaceBindingOption): string {
  const name = option.displayName ?? '';
  const provider = option.providerType ?? '';
  if (name && provider && name.toUpperCase() !== provider.toUpperCase()) {
    return `${name} · ${provider}`;
  }
  return name || provider || option.bindingId.slice(0, 8);
}

function decorate(
  order: OrderSummaryResponse,
  now: Date,
  policyFor: (order: OrderSummaryResponse) => LatenessPolicy,
): OrderRow {
  const createdAt = new Date(order.createdAt);
  return {
    order,
    createdAt,
    severity: computeOrderSeverity(toSeverityFields(order, createdAt), now, policyFor(order)),
  };
}

function toCountable(order: OrderSummaryResponse): CountableOrder {
  return toSeverityFields(order, new Date(order.createdAt));
}

function toSeverityFields(order: OrderSummaryResponse, createdAt: Date): CountableOrder {
  return {
    locationId: order.locationId ?? null,
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
  policyFor: (order: OrderSummaryResponse) => LatenessPolicy,
): { open: number; late: number } {
  let open = 0;
  let late = 0;
  for (const order of orders) {
    if (order.status !== 'COMPLETED' && order.status !== 'CANCELLED') {
      open += 1;
    }
    if (computeOrderSeverity(toCountable(order), now, policyFor(order)).level !== 'NORMAL') {
      late += 1;
    }
  }
  return { open, late };
}
