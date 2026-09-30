import { Injectable, Signal, computed, signal } from '@angular/core';
import { ParamMap } from '@angular/router';

import { OrderTabId } from './order-tabs';

/** `q-date-range-picker`'s own shape — kept local rather than imported, since only the two field names matter here. */
export interface OrderQueueDateRange {
  readonly start: string;
  readonly end: string;
}

/**
 * The board's own toolbar filters (orders.md §2.4, wave P07), narrowed to
 * what `GET .../orders/board` (wave P04, ADR 0102) actually reads:
 *
 * - **Филиал** (wave 16, gap map `1.1`/`1.1c`) exists in one mode only. The
 *   branch board's path names one `locationId`, so there is no second branch to
 *   filter *among* inside a call to it. The brand-scoped board
 *   (`OperationsBrandOrderController.board`) is the same statement over a set of
 *   branches: {@link OrderQueueFilters.allBranches} switches the queue onto it
 *   («Все филиалы»), and {@link OrderQueueFilters.locationId} then narrows that
 *   set to one branch. Both are meaningless — and never sent — on the branch
 *   board, where `CurrentLocation`'s shell picker remains the way to change
 *   branch. {@link OrderQueueFilters.allBranches} is a *mode*, not a filter: it
 *   does not count as an active filter and survives «Сбросить фильтры» the way
 *   the period does.
 * - **Канал** is `channelCode`, and **Источник** (wave 9, gap map `1.1c`) is
 *   the new, coarser `origin` predicate beside it: `HORECAOS` for the
 *   tenant's own channels, `MARKETPLACE` for anything an aggregator pushed
 *   or an operator keyed in on one's behalf (ADR 0040). The two answer
 *   different questions and are not one control — a tenant can run several
 *   `MARKETPLACE` channels (Wolt, Yandex Eats) that `channelCode` already
 *   tells apart, and `origin` is what "aggregator orders, whichever one"
 *   answers in one click instead of one per channel. Picking a single
 *   aggregator *binding* when a tenant runs two installations of the same
 *   provider is narrower than `origin` reaches — that is `marketplaceBindingId`
 *   below.
 * - **Оплата** (payment *status*, wave 10 gap map `1.1c`) is
 *   `paymentStatus` — `ordering.orders.payment_status_projection` — beside
 *   **Способ оплаты** (payment *method*, `paymentMethodCode`), which reads
 *   the payment module's own intent instead. The two answer different
 *   questions the same way `channelCode`/`origin` do above.
 * - **Агрегатор** (wave 16, gap map `1.1c`) is `marketplaceBindingId`: one
 *   provider binding rather than `origin`'s coarse toggle. Its options are the
 *   bindings the orders in scope arrived through
 *   (`GET .../orders/marketplace-bindings`), not every binding the tenant owns.
 * - **Только опаздывающие / С проблемой / Требуется звонок / Фискализация**
 *   (wave 16, gap map `1.1c`) are the four secondary toggles orders.md §2.4
 *   marks as not read by ordering, and the board now reads all four: `late`
 *   (the resolved `ordering.lateness` policy applied in the statement),
 *   `problem` (a process needing an operator or failing), `callbackRequested`
 *   and `fiscalStatus` (a fiscal document of the order in that status).
 */
export interface OrderQueueFilters {
  /** «Все филиалы» (wave 16, gap map `1.1`): read the brand-scoped board. A mode, not a filter — see the class doc above. */
  readonly allBranches: boolean;
  /** «Филиал» — one branch of the brand, meaningful only while {@link allBranches} is on. */
  readonly locationId: string | null;
  readonly dateRange: OrderQueueDateRange | null;
  readonly channelCode: string | null;
  /** «Источник» (wave 9, gap map `1.1c`) — `ordering.orders.origin` (V0038, ADR 0040). */
  readonly origin: 'HORECAOS' | 'MARKETPLACE' | null;
  readonly fulfillmentMode: 'DELIVERY' | 'PICKUP' | 'DINE_IN' | null;
  readonly courierId: string | null;
  readonly paymentMethodCode: string | null;
  /** «Оплата» (wave 10, gap map `1.1c`) — `ordering.orders.payment_status_projection`. */
  readonly paymentStatus: string | null;
  /** «Агрегатор» (wave 16, gap map `1.1c`) — `ordering.orders.marketplace_binding_id` (V0038). */
  readonly marketplaceBindingId: string | null;
  /** «Только опаздывающие» — orders.md §2.4/§2.7, resolved server-side against the branch's `ordering.lateness` policy. */
  readonly lateOnly: boolean;
  /** «С проблемой» — a process of the order needs an operator or is failing. */
  readonly problemOnly: boolean;
  /** «Требуется звонок» — the callback amendment's flag, still raised. */
  readonly callbackRequested: boolean;
  /** «Фискализация» — a fiscal document of the order in this `fiscal.fiscal_documents.status`, or `ATTENTION` for FAILED + BLOCKED. */
  readonly fiscalStatus: string | null;
  readonly mineOnly: boolean;
  /** The exact-match search box (§2.8's built half) — order number or external reference. */
  readonly reference: string;
}

export const EMPTY_ORDER_QUEUE_FILTERS: OrderQueueFilters = {
  allBranches: false,
  locationId: null,
  dateRange: null,
  channelCode: null,
  origin: null,
  fulfillmentMode: null,
  courierId: null,
  paymentMethodCode: null,
  paymentStatus: null,
  marketplaceBindingId: null,
  lateOnly: false,
  problemOnly: false,
  callbackRequested: false,
  fiscalStatus: null,
  mineOnly: false,
  reference: '',
};

/**
 * «Фискализация»'s "needs attention" pseudo-value: the two statuses an
 * operator actually asks for (a document that failed, or one blocked past its
 * reporting deadline), sent as two `fiscalStatus` parameters.
 */
export const FISCAL_STATUS_ATTENTION = 'ATTENTION';

/** The single statuses «Фискализация» offers beside {@link FISCAL_STATUS_ATTENTION} — `fiscal.fiscal_documents`' own values. */
export const FISCAL_STATUS_OPTIONS = [
  'PENDING',
  'SUBMITTED',
  'ISSUED',
  'FAILED',
  'BLOCKED',
  'NOT_APPLICABLE',
] as const;

const STORAGE_PREFIX = 'horecaos.operations.orderQueue.filters.';

/** True when every field is at its default — governs whether "Сбросить фильтры" and the "filtering" chip state show at all. */
export function hasActiveFilters(filters: OrderQueueFilters): boolean {
  return (
    filters.dateRange !== null ||
    filters.channelCode !== null ||
    filters.origin !== null ||
    filters.fulfillmentMode !== null ||
    filters.courierId !== null ||
    filters.paymentMethodCode !== null ||
    filters.paymentStatus !== null ||
    filters.locationId !== null ||
    filters.marketplaceBindingId !== null ||
    filters.lateOnly ||
    filters.problemOnly ||
    filters.callbackRequested ||
    filters.fiscalStatus !== null ||
    filters.mineOnly ||
    filters.reference.trim().length > 0
  );
}

/**
 * §2.4: "Сбросить фильтры clears everything except the tab and the period" —
 * so the date range survives a reset and every other field returns to its
 * default. The «Все филиалы» mode survives too: it says *which board* is being
 * read, not how it is narrowed, and a reset that silently dropped a
 * supervisor back onto one branch would read as their other branches
 * vanishing.
 */
export function resetFilters(filters: OrderQueueFilters): OrderQueueFilters {
  return {
    ...EMPTY_ORDER_QUEUE_FILTERS,
    dateRange: filters.dateRange,
    allBranches: filters.allBranches,
  };
}

/**
 * `filters` as `GET .../orders/board` query parameters (orders.md §2.4, ADR
 * 0102). `actorId` is the signed-in operator's own subject
 * (`CurrentTenant.subject`) — «Мои заказы» sends nothing when it is not yet
 * known rather than filtering to a `createdByActorId` of `null`, which the
 * endpoint would read as "no filter" and answer with everyone's orders.
 *
 * The date range's two `YYYY-MM-DD` calendar days become the day's own start
 * and end in a fixed `+05:00` offset — the same placeholder-zone reasoning
 * `order-queue.ts`'s own `PLACEHOLDER_TIME_ZONE` constant documents: no
 * location this board can reach carries a timezone yet, and Uzbekistan's
 * single zone has no daylight-saving rule to get wrong.
 */
export function boardQueryParams(
  filters: OrderQueueFilters,
  actorId: string | null,
): Record<string, string | readonly string[]> {
  const params: Record<string, string | readonly string[]> = {};
  if (filters.dateRange) {
    params['from'] = `${filters.dateRange.start}T00:00:00+05:00`;
    params['to'] = `${filters.dateRange.end}T23:59:59+05:00`;
  }
  if (filters.channelCode) {
    params['channelCode'] = filters.channelCode;
  }
  if (filters.origin) {
    params['origin'] = filters.origin;
  }
  if (filters.fulfillmentMode) {
    params['fulfillmentMode'] = filters.fulfillmentMode;
  }
  if (filters.courierId) {
    params['courierId'] = filters.courierId;
  }
  if (filters.paymentMethodCode) {
    params['paymentMethodCode'] = filters.paymentMethodCode;
  }
  if (filters.paymentStatus) {
    params['paymentStatus'] = filters.paymentStatus;
  }
  // The branch is a parameter of the brand-scoped board only — the branch
  // board's path already names its one location, so it is never sent there.
  if (filters.allBranches && filters.locationId) {
    params['locationId'] = filters.locationId;
  }
  if (filters.marketplaceBindingId) {
    params['marketplaceBindingId'] = filters.marketplaceBindingId;
  }
  if (filters.lateOnly) {
    params['late'] = 'true';
  }
  if (filters.problemOnly) {
    params['problem'] = 'true';
  }
  if (filters.callbackRequested) {
    params['callbackRequested'] = 'true';
  }
  if (filters.fiscalStatus) {
    params['fiscalStatus'] =
      filters.fiscalStatus === FISCAL_STATUS_ATTENTION
        ? ['FAILED', 'BLOCKED']
        : filters.fiscalStatus;
  }
  if (filters.mineOnly && actorId) {
    params['createdByActorId'] = actorId;
  }
  // §2.8: exact match, minimum two characters — a shorter query is not sent
  // as a filter at all rather than narrowed silently to a huge, surprising
  // result.
  const reference = filters.reference.trim();
  if (reference.length >= 2) {
    params['reference'] = reference;
  }
  return params;
}

/**
 * The URL query parameter names the board's filters round-trip through
 * (wave 10, gap map `1.1c`) — deliberately distinct from `boardQueryParams`'
 * own server-facing names in the two spots where they'd otherwise collide
 * with a *different* meaning: `dateStart`/`dateEnd` are the raw `YYYY-MM-DD`
 * calendar days the picker holds, never `boardQueryParams`' own derived
 * `+05:00` timestamps, and `mine`/`q` read shorter than `mineOnly`/
 * `reference` for a link an operator might actually paste somewhere. No
 * value here is personal data (ADR 0029): every one is a code, an id, a
 * plain boolean flag or a calendar day — `reference`/`q` is an order number
 * or an external reference, the same non-PII search text §2.8 already sends
 * as a query parameter to the board endpoint itself.
 */
const FILTER_PARAM_NAMES = [
  'branches',
  'branch',
  'channelCode',
  'origin',
  'binding',
  'late',
  'problem',
  'callback',
  'fiscal',
  'fulfillmentMode',
  'courierId',
  'paymentMethodCode',
  'paymentStatus',
  'mine',
  'q',
  'dateStart',
  'dateEnd',
] as const;

/** Whether the URL carries any of this board's own filter parameters — the signal that a link, not `localStorage`, is this load's source of truth. */
export function hasFilterQueryParams(params: ParamMap): boolean {
  return FILTER_PARAM_NAMES.some((name) => params.has(name));
}

/** The filter state a URL's query parameters describe — {@link EMPTY_ORDER_QUEUE_FILTERS} for any field the URL leaves out. */
export function filtersFromQueryParams(params: ParamMap): OrderQueueFilters {
  const dateStart = params.get('dateStart');
  const dateEnd = params.get('dateEnd');
  return {
    allBranches: params.get('branches') === 'all',
    locationId: params.get('branch'),
    dateRange: dateStart && dateEnd ? { start: dateStart, end: dateEnd } : null,
    channelCode: params.get('channelCode'),
    origin: params.get('origin') as OrderQueueFilters['origin'],
    marketplaceBindingId: params.get('binding'),
    lateOnly: params.get('late') === '1',
    problemOnly: params.get('problem') === '1',
    callbackRequested: params.get('callback') === '1',
    fiscalStatus: params.get('fiscal'),
    fulfillmentMode: params.get('fulfillmentMode') as OrderQueueFilters['fulfillmentMode'],
    courierId: params.get('courierId'),
    paymentMethodCode: params.get('paymentMethodCode'),
    paymentStatus: params.get('paymentStatus'),
    mineOnly: params.get('mine') === '1',
    reference: params.get('q') ?? '',
  };
}

/**
 * `filters` as a `Router.navigate` `queryParams` patch — `null` for a
 * default-valued field, which `queryParamsHandling: 'merge'` reads as
 * "remove this parameter" rather than writing it as the literal string
 * `"null"`. The mirror of {@link filtersFromQueryParams}: applying one then
 * the other is the identity on every field both functions carry.
 */
export function filtersToQueryParams(filters: OrderQueueFilters): Record<string, string | null> {
  const reference = filters.reference.trim();
  return {
    branches: filters.allBranches ? 'all' : null,
    branch: filters.locationId,
    channelCode: filters.channelCode,
    origin: filters.origin,
    binding: filters.marketplaceBindingId,
    late: filters.lateOnly ? '1' : null,
    problem: filters.problemOnly ? '1' : null,
    callback: filters.callbackRequested ? '1' : null,
    fiscal: filters.fiscalStatus,
    fulfillmentMode: filters.fulfillmentMode,
    courierId: filters.courierId,
    paymentMethodCode: filters.paymentMethodCode,
    paymentStatus: filters.paymentStatus,
    mine: filters.mineOnly ? '1' : null,
    q: reference.length > 0 ? reference : null,
    dateStart: filters.dateRange?.start ?? null,
    dateEnd: filters.dateRange?.end ?? null,
  };
}

function readStored(tab: OrderTabId): OrderQueueFilters {
  try {
    const raw = globalThis.localStorage?.getItem(STORAGE_PREFIX + tab);
    if (!raw) {
      return EMPTY_ORDER_QUEUE_FILTERS;
    }
    const parsed = JSON.parse(raw) as Partial<OrderQueueFilters>;
    // A shallow merge over the defaults, not a bare cast: a filter this
    // version no longer knows (an older or newer release's own field) is
    // dropped rather than trusted, the same defensive read `CurrentLocation`
    // and `I18n` already use for their own stored values.
    return { ...EMPTY_ORDER_QUEUE_FILTERS, ...parsed };
  } catch {
    return EMPTY_ORDER_QUEUE_FILTERS;
  }
}

function writeStored(tab: OrderTabId, filters: OrderQueueFilters): void {
  try {
    globalThis.localStorage?.setItem(STORAGE_PREFIX + tab, JSON.stringify(filters));
  } catch {
    // Lost between sessions, not lost now — same tolerant stance as
    // `CurrentLocation.selectLocation` and `I18n.setLocale`.
  }
}

/**
 * The toolbar's own filter values, persisted **per tab** (orders.md §1.1c):
 * switching from Внимание to Завершены and back restores each tab's own last
 * filter set, and a full page reload with no filters in the URL restores
 * whichever tab's filters were open.
 *
 * **Round-trips through the URL** (wave 10, gap map `1.1c`): {@code
 * order-queue.ts} pushes every filter change into the URL's own query
 * parameters (via {@link filtersToQueryParams}) so a filtered board is a
 * link an operator can paste to a colleague, and reads them back out (via
 * {@link filtersFromQueryParams}/{@link setFilters}) when a URL already
 * carries any — a pasted link, or the browser's own back/forward. `
 * localStorage` stays the cold-entry source of truth for a plain reload or
 * a fresh visit with no filter parameters in the URL at all: {@link
 * setFilters} still persists to it, so opening a shared link once means the
 * next cold visit remembers it too.
 */
@Injectable({ providedIn: 'root' })
export class OrderQueueFilterState {
  private readonly tab = signal<OrderTabId | null>(null);
  private readonly filters = signal<OrderQueueFilters>(EMPTY_ORDER_QUEUE_FILTERS);

  readonly current: Signal<OrderQueueFilters> = this.filters.asReadonly();
  readonly hasActive: Signal<boolean> = computed(() => hasActiveFilters(this.filters()));

  /** Loads (or re-loads) the filters remembered for this tab. A no-op re-read when the tab has not actually changed. */
  loadForTab(tab: OrderTabId): void {
    if (this.tab() === tab) {
      return;
    }
    this.tab.set(tab);
    this.filters.set(readStored(tab));
  }

  /**
   * Sets this tab's filters directly, from a URL a link just carried in —
   * never merged with what {@code localStorage} remembered, the same way a
   * pasted link overrides a browser's own autofill. Still persisted (this
   * link is now what the next cold visit to this tab remembers too).
   */
  setFilters(tab: OrderTabId, filters: OrderQueueFilters): void {
    this.tab.set(tab);
    this.filters.set(filters);
    this.persist();
  }

  update(patch: Partial<OrderQueueFilters>): void {
    const next = { ...this.filters(), ...patch };
    this.filters.set(next);
    this.persist();
  }

  reset(): void {
    const next = resetFilters(this.filters());
    this.filters.set(next);
    this.persist();
  }

  private persist(): void {
    const tab = this.tab();
    if (tab !== null) {
      writeStored(tab, this.filters());
    }
  }
}
