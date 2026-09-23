import { Injectable, Signal, computed, signal } from '@angular/core';

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
 * - **Филиал** is still not a field here, and for a structural reason wave 9
 *   did not remove: `/board`'s path already names one `locationId`, so there
 *   is no second branch to filter *among* inside one call to it — a "branch"
 *   parameter on an endpoint that only ever answers for the branch its own
 *   URL names would either be ignored or duplicate `CurrentLocation`'s own
 *   shell picker. Answering it for real needs a brand-scoped, paginated
 *   board — a new endpoint at a wider `ORDER_READ` scope, the same reason
 *   `OperationsBrandOrderController` exists beside `OperationsOrderController`
 *   for the live board's counters — which is a decision this wave's own gap
 *   map row does not make for us; see wave 9's `notDone`.
 * - **Канал** is `channelCode`, and **Источник** (wave 9, gap map `1.1c`) is
 *   the new, coarser `origin` predicate beside it: `HORECAOS` for the
 *   tenant's own channels, `MARKETPLACE` for anything an aggregator pushed
 *   or an operator keyed in on one's behalf (ADR 0040). The two answer
 *   different questions and are not one control — a tenant can run several
 *   `MARKETPLACE` channels (Wolt, Yandex Eats) that `channelCode` already
 *   tells apart, and `origin` is what "aggregator orders, whichever one"
 *   answers in one click instead of one per channel. Picking a single
 *   aggregator *binding* when a tenant runs two installations of the same
 *   provider is narrower than `origin` reaches and is not offered here.
 * - **Оплата** (payment *status*) has no board predicate at all yet — only
 *   **Способ оплаты** (payment *method*, `paymentMethodCode`) does. This
 *   toolbar's "payment type" filter is the method, matching what the
 *   endpoint can actually answer.
 * - **Только опаздывающие / С проблемой / Требуется звонок / Фискализация**
 *   have no board predicate either (orders.md §2.4's own status line on
 *   each) and are not offered.
 */
export interface OrderQueueFilters {
  readonly dateRange: OrderQueueDateRange | null;
  readonly channelCode: string | null;
  /** «Источник» (wave 9, gap map `1.1c`) — `ordering.orders.origin` (V0038, ADR 0040). */
  readonly origin: 'HORECAOS' | 'MARKETPLACE' | null;
  readonly fulfillmentMode: 'DELIVERY' | 'PICKUP' | 'DINE_IN' | null;
  readonly courierId: string | null;
  readonly paymentMethodCode: string | null;
  readonly mineOnly: boolean;
  /** The exact-match search box (§2.8's built half) — order number or external reference. */
  readonly reference: string;
}

export const EMPTY_ORDER_QUEUE_FILTERS: OrderQueueFilters = {
  dateRange: null,
  channelCode: null,
  origin: null,
  fulfillmentMode: null,
  courierId: null,
  paymentMethodCode: null,
  mineOnly: false,
  reference: '',
};

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
    filters.mineOnly ||
    filters.reference.trim().length > 0
  );
}

/**
 * §2.4: "Сбросить фильтры clears everything except the tab and the period" —
 * so the date range survives a reset and every other field returns to its
 * default.
 */
export function resetFilters(filters: OrderQueueFilters): OrderQueueFilters {
  return { ...EMPTY_ORDER_QUEUE_FILTERS, dateRange: filters.dateRange };
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
): Record<string, string> {
  const params: Record<string, string> = {};
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
 * filter set, and a full page reload restores whichever tab's filters were
 * open.
 *
 * **Not yet a URL parameter.** §2.4 makes query parameters the source of
 * truth and `localStorage` a cold-entry restore only; this wave keeps every
 * filter in `localStorage` on every change instead, the same scope reduction
 * `ReportsFilterState`'s own doc comment already makes for the reports bar —
 * a filtered board is not yet a shareable link. Round-tripping through the
 * URL is follow-up work, not built this wave.
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
