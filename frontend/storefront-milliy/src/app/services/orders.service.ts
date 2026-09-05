import { Injectable, inject } from '@angular/core';
import { EMPTY, Observable, Subject, from, interval } from 'rxjs';
import { catchError, filter, map, switchMap } from 'rxjs/operators';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { newIdempotencyKey } from '../core/api/idempotency';
import { HorecaOSApiError } from '../core/api/problem-details';
import type { Page } from '../core/api/page';

/**
 * The customer's own orders.
 *
 * Replaces `/customers/orders/`. Three differences shape everything here.
 *
 * **There is no status filter on the wire.** The legacy list took repeated
 * `status` parameters and the server filtered; `GET /orders` returns the
 * caller's own orders newest first, cursor-paginated, and the tabs filter what
 * comes back. The account is a predicate of the server's query and there is no
 * parameter that widens it, so this enumerates one customer's orders and never
 * a brand's — nothing is passed to say whose.
 *
 * **Cancellation needs the version.** It travels as `If-Match`, and the
 * platform answers `STALE_VERSION` with the version it actually holds when the
 * order moved while the customer was looking at it. That is retried exactly
 * once: retrying twice is a loop that fights the kitchen and eventually cancels
 * something the customer never saw. A conflict on the second attempt is the
 * correct answer — an order that reached CONFIRMED is no longer cancellable at
 * any version.
 *
 * **The status vocabulary is the platform's.** The legacy tabs were built on
 * `new`/`accepted`/`cooking`/`ready`/`delivering`; the platform has
 * `RECEIVED`, `CONFIRMED`, `PREPARING`, `READY`, `FULFILLING` and so on. The
 * tab tokens are kept as tab identity and mapped here, so the screens did not
 * have to change and the mapping lives in one readable place.
 */
@Injectable({ providedIn: 'root' })
export class OrdersService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);
  private readonly ordersLoaded$ = new Subject<OrdersLoadedEvent>();

  /** Versions seen on the list, so a cancellation can present one. */
  private readonly versions = new Map<string, number>();

  private get brandPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}`;
  }

  get onOrdersLoaded(): Observable<OrdersLoadedEvent> {
    return this.ordersLoaded$.asObservable();
  }

  emitOrdersLoaded(statuses: string[], orders: ApiOrder[]): void {
    this.ordersLoaded$.next({ statuses, orders });
  }

  /**
   * The caller's own orders, filtered to the tab's statuses.
   *
   * @param statuses legacy tab tokens. An empty list means every order.
   */
  getOrders(statuses: string[], limit = 50): Observable<ApiOrder[]> {
    const wanted = new Set(statuses.flatMap((token) => PLATFORM_STATUSES[token] ?? []));
    return from(
      this.api.list<OrderSummaryResponse>(`${this.brandPath}/orders`, { limit }),
    ).pipe(
      map((page: Page<OrderSummaryResponse>) => {
        for (const row of page.items) {
          this.versions.set(row.orderId, row.version);
        }
        return page.items
          .filter((row) => wanted.size === 0 || wanted.has(row.status))
          .map((row) => this.toApiOrder(row));
      }),
    );
  }

  /**
   * Repeats `source` every `intervalMs`, so a screen can show a status that
   * stays true without the customer having to pull to refresh.
   *
   * Skipped entirely while the tab is hidden (`document.hidden`): a screen
   * nobody is looking at has no reason to keep spending the customer's data
   * plan or the platform's request budget on an answer nothing will show. A
   * failed tick is swallowed rather than propagated, so one dropped request
   * does not end the whole polling subscription -- the next tick tries again.
   *
   * Does **not** emit immediately; pair this with a normal one-shot read for
   * the first paint and use this for the refreshes after it.
   */
  poll<T>(intervalMs: number, source: () => Observable<T>): Observable<T> {
    return interval(intervalMs).pipe(
      filter(() => typeof document === 'undefined' || !document.hidden),
      switchMap(() => source().pipe(catchError(() => EMPTY))),
    );
  }

  getOrderDetail(id: string | number): Observable<ApiOrderDetail> {
    return from(this.api.get<OrderResponse>(`${this.brandPath}/orders/${id}`)).pipe(
      map((order) => {
        this.versions.set(order.orderId, order.version);
        return this.toApiOrderDetail(order);
      }),
    );
  }

  /**
   * Cancels an order that has not been confirmed.
   *
   * The idempotency key is formed once, outside the retry, and reused: the whole
   * point of the header is that one intent produces one effect however many
   * times the request is sent. A fresh key on the retry makes the retry a second
   * cancellation.
   */
  cancelOrder(id: string | number, reasonCode = 'CUSTOMER_REQUESTED'): Observable<unknown> {
    return from(this.cancel(String(id), reasonCode));
  }

  private async cancel(orderId: string, reasonCode: string): Promise<OrderStateResponse> {
    const idempotencyKey = newIdempotencyKey();
    const path = `${this.brandPath}/orders/${orderId}/cancellations`;
    const body = { reasonCode };

    let version = this.versions.get(orderId);
    if (version === undefined) {
      // Nothing has read this order in this session. One read beats guessing a
      // version, which would be answered STALE_VERSION and read to a customer
      // as a random failure.
      const order = await this.api.get<OrderResponse>(`${this.brandPath}/orders/${orderId}`);
      version = order.version;
      this.versions.set(orderId, version);
    }

    try {
      return await this.api.mutate<OrderStateResponse>('POST', path, {
        body,
        expectedVersion: version,
        idempotencyKey,
      });
    } catch (failure) {
      const current = staleVersionFrom(failure);
      if (current === null) {
        throw failure;
      }
      // The one retry. Not in a loop, and not wrapped in another try.
      this.versions.set(orderId, current);
      return this.api.mutate<OrderStateResponse>('POST', path, {
        body,
        expectedVersion: current,
        idempotencyKey,
      });
    }
  }

  private toApiOrder(row: OrderSummaryResponse): ApiOrder {
    return {
      id: row.orderId as unknown as number,
      status: { id: row.status, name: row.status },
      total: row.totalMinor,
      total_price: row.totalMinor,
      order_number: row.publicOrderNumber as unknown as number,
      number: row.publicOrderNumber as unknown as number,
      // `OrderSummaryResponse` carries no line items and no distance -- see
      // its Javadoc ("no lines... open one order to read those"). These used
      // to be hardcoded to 0, which rendered as the very wrong "0 ta" on
      // every order card; left unset, the card shows nothing rather than a
      // fabricated count.
      items_count: undefined,
      delivery_distance: undefined,
      image_url: null,
      created_date: row.placedAt,
      created_time: row.placedAt,
      actions: isCancellable(row.status) ? ['cancel'] : [],
    };
  }

  private toApiOrderDetail(order: OrderResponse): ApiOrderDetail {
    return {
      id: order.orderId,
      order_number: order.publicOrderNumber as unknown as number,
      status: { id: order.status, name: order.status },
      created_date: order.createdAt,
      created_time: order.createdAt,
      items_count: order.lines.length,
      items: order.lines.map((line) => ({
        variant_id: undefined,
        item_id: line.lineNumber,
        name: [line.productName, line.variantName].filter(Boolean).join(' '),
        quantity: line.quantity,
        price: line.unitAmountMinor,
        image: null,
        note: null,
      })),
      // The destination is a sub-resource with its own purposed reveal, and is
      // not on the order. Showing "no address" beats fetching a doorstep to
      // paint on a history screen.
      address: undefined,
      payment: undefined,
      subtotal: { price: order.subtotalMinor, discount: 0 },
      // Delivery and packaging are not order fields. Zero is "not stated here".
      delivery: { price: 0, discount: 0 },
      packaging: { price: 0, discount: 0 },
      total: { price: order.totalMinor, discount: 0 },
      actions: isCancellable(order.status) ? ['cancel'] : [],
    };
  }
}

/**
 * Legacy tab token to the platform statuses it covers.
 *
 * `PAYMENT_FAILED`, `REJECTED` and `EXPIRED` are grouped with cancelled rather
 * than given tabs of their own: to a customer they are all "this did not
 * happen", and the order detail says which.
 */
const PLATFORM_STATUSES: Readonly<Record<string, readonly string[]>> = {
  new: ['RECEIVED', 'PAYMENT_AUTHORIZING', 'AWAITING_APPROVAL'],
  accepted: ['CONFIRMED'],
  cooking: ['PREPARING'],
  ready: ['READY'],
  delivering: ['FULFILLING'],
  completed: ['COMPLETED'],
  cancelled: ['CANCELLED', 'REJECTED', 'EXPIRED', 'PAYMENT_FAILED'],
};

/**
 * Statuses `POST .../cancellations` would still accept with no registry
 * reason, mirroring the server's own read model,
 * `OrderActionsPolicy.canCancelWithoutReason` (`ordering.application`,
 * combined there with `OrderStateMachine.permits(status, CANCELLED)` into
 * `canCancel`): once an order is `CONFIRMED` the kitchen owns it, and every
 * status from there on refuses a reasonless cancellation.
 *
 * The storefront order responses carry no server-computed `actions[]` today
 * -- that read model is wired only into `OperationsOrderController`, the
 * staff-facing surface -- so this is what decides whether `toApiOrder` and
 * `toApiOrderDetail` below offer a cancel button at all. Getting it wrong in
 * either direction is visible: too narrow, and a cancellable order shows no
 * button; too wide, and a customer taps cancel on an order the platform then
 * refuses with a conflict.
 */
const CANCELLABLE_ORDER_STATUSES: ReadonlySet<string> = new Set([
  'RECEIVED',
  'PAYMENT_AUTHORIZING',
  'AWAITING_APPROVAL',
]);

function isCancellable(status: string): boolean {
  return CANCELLABLE_ORDER_STATUSES.has(status);
}

/** The version the server actually holds, or null when this was not a stale one. */
function staleVersionFrom(failure: unknown): number | null {
  if (!(failure instanceof HorecaOSApiError) || !failure.isStaleVersion) {
    return null;
  }
  const current = failure.problem?.currentVersion;
  return typeof current === 'number' ? current : null;
}

// --------------------------------------------------------------- wire shapes

export interface OrderSummaryResponse {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly locationId: string;
  readonly fulfillmentMode: string;
  readonly status: string;
  readonly paymentStatus: string | null;
  readonly fulfillmentStatus: string | null;
  readonly currency: string;
  readonly totalMinor: number;
  readonly promisedAt: string | null;
  readonly version: number;
  readonly placedAt: string;
}

export interface OrderLineResponse {
  readonly lineNumber: number;
  readonly productName: string;
  readonly variantName: string;
  readonly quantity: number;
  readonly unitAmountMinor: number;
  readonly finalAmountMinor: number;
  readonly modifiers: readonly string[];
}

export interface OrderResponse {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly status: string;
  readonly currency: string;
  readonly subtotalMinor: number;
  readonly taxMinor: number;
  readonly totalMinor: number;
  readonly version: number;
  readonly createdAt: string;
  readonly confirmedAt: string | null;
  readonly lines: readonly OrderLineResponse[];
  readonly warnings: readonly string[];
}

export interface OrderStateResponse {
  readonly orderId: string;
  readonly status: string;
  readonly version: number;
  readonly applied: boolean;
}

// ------------------------------------------- display shapes the screens read

export interface ApiOrderLineItem {
  variant_id?: string;
  item_id?: number;
  name?: string;
  quantity?: number;
  price?: number;
  image?: string | null;
  note?: string | null;
  [key: string]: unknown;
}

export interface ApiPriceObject {
  price?: number;
  discount?: number;
}

export interface ApiOrderDetail {
  id: number | string;
  order_number?: number;
  status?: { id: string; name: string };
  created_date?: string;
  created_time?: string;
  items_count?: number;
  items?: ApiOrderLineItem[];
  address?: { id: string; name: string; address: string; latitude?: number; longitude?: number };
  payment?: { id: number; name: string; status?: string };
  subtotal?: ApiPriceObject | number;
  delivery?: ApiPriceObject | number;
  packaging?: ApiPriceObject | number;
  total?: ApiPriceObject | number;
  actions?: string[];
  [key: string]: unknown;
}

export interface ApiOrder {
  id: number;
  status?: { id: string; name: string };
  total?: number;
  total_price?: number;
  order_number?: number;
  number?: number;
  items_count?: number;
  delivery_distance?: number;
  image_url?: string | null;
  created_date?: string;
  created_time?: string;
  actions?: string[];
  [key: string]: unknown;
}

export interface OrdersLoadedEvent {
  statuses: string[];
  orders: ApiOrder[];
}
