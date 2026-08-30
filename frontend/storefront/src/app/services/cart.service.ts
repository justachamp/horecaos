import { Injectable, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { newIdempotencyKey } from '../core/api/idempotency';
import { HorecaOSApiError, isNotFound } from '../core/api/problem-details';

/**
 * The platform cart, which is a different thing from the legacy one.
 *
 * The old backend had exactly one cart per customer and every call addressed it
 * implicitly: `GET /customers/carts/`, `PUT` to add, `DELETE` to remove. The
 * platform has none of that. A cart is a resource this client **creates**, holds
 * the id of, and presents a version for on every write.
 *
 * <h2>What that changes, and what this class exists to absorb</h2>
 *
 * **The cart id has to survive a reload.** There is no "my cart" endpoint to ask
 * for, so losing the id loses the basket. It is kept in local storage against
 * the location it belongs to, because ADR 0019 never carries a cart across
 * locations — the prices shown would be prices that do not apply.
 *
 * **Every write carries the version it expects**, as `If-Match`. A second tab,
 * or a retry after a timeout that actually succeeded, loses with `STALE_VERSION`
 * rather than quietly overwriting. This class reloads and retries once on that,
 * and once only: retrying in a loop fights whatever is moving the cart.
 *
 * **A line is addressed by a key this client chooses.** The key is derived from
 * the variant and the exact set of modifier options, so "osh with extra meat" and
 * "osh" are two lines rather than one line whose modifiers depend on which
 * request landed last.
 *
 * **There is no clear-cart endpoint**, so clearing is deleting every line in
 * turn — sequentially, because each delete bumps the version the next one needs.
 *
 * **The cart carries no money at all.** `CartResponse` is lines, quantities and
 * a version; there is not even a line total. Prices come from the menu (which
 * carries them now) and the cart total comes from `POST /pricing`, which is also
 * what binds the quote checkout will accept.
 *
 * **The customer's note never comes back.** A line reports `hasCustomerNote` and
 * never the text: it is personal data revealed only through an endpoint that
 * records a purpose. A note can be written and cannot be read back for display.
 */
@Injectable({ providedIn: 'root' })
export class CartService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  /** The cart as the platform last reported it, or null when there is none. */
  readonly cart = signal<PlatformCart | null>(null);

  private get brandPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}`;
  }

  /**
   * The cart for this location, reloaded or created.
   *
   * @param create when false, a customer with no cart yet gets null rather than
   *        an empty cart. Browsing must not mint a cart per visit.
   */
  async ensure(locationId: string, fulfillmentMode: FulfillmentMode,
      create = true): Promise<PlatformCart | null> {

    const stored = readCartId(locationId);
    if (stored) {
      try {
        const cart = await this.api.get<PlatformCart>(`${this.brandPath}/carts/${stored}`);
        // A cart the server has since abandoned or checked out is not this
        // customer's open basket any more.
        if (cart.status === 'OPEN' || cart.status === 'ACTIVE') {
          this.cart.set(cart);
          return cart;
        }
        forgetCartId(locationId);
      } catch (failure) {
        // Not found covers "checked out", "expired" and "never existed". All
        // three mean the same thing here: start again.
        if (!isNotFound(failure)) {
          throw failure;
        }
        forgetCartId(locationId);
      }
    }
    if (!create) {
      this.cart.set(null);
      return null;
    }
    return this.create(locationId, fulfillmentMode);
  }

  async create(locationId: string, fulfillmentMode: FulfillmentMode): Promise<PlatformCart> {
    const cart = await this.api.mutate<PlatformCart>('POST', `${this.brandPath}/carts`, {
      body: { locationId, channel: this.config.channel, fulfillmentMode },
      idempotencyKey: newIdempotencyKey(),
    });
    rememberCartId(locationId, cart.cartId);
    this.cart.set(cart);
    return cart;
  }

  /**
   * Adds or replaces one line.
   *
   * A replace and not a patch, which is the platform's own semantics for
   * `PUT /lines/{lineKey}`: `modifierOptionIds` must carry the whole selection
   * every time, including on a quantity change, because sending an empty list
   * would strip what the customer chose and they would find out at the counter.
   */
  async putLine(input: {
    variantId: string;
    quantity: number;
    modifierOptionIds?: readonly string[];
    customerNote?: string;
  }): Promise<PlatformCart> {
    const lineKey = lineKeyFor(input.variantId, input.modifierOptionIds ?? []);
    return this.withVersion((cart, version) =>
      this.api.mutate<PlatformCart>(
        'PUT',
        `${this.brandPath}/carts/${cart.cartId}/lines/${encodeURIComponent(lineKey)}`,
        {
          body: {
            variantId: input.variantId,
            quantity: input.quantity,
            modifierOptionIds: input.modifierOptionIds ?? [],
            customerNote: input.customerNote,
          },
          expectedVersion: version,
          idempotencyKey: newIdempotencyKey(),
        },
      ),
    );
  }

  async removeLine(lineKey: string): Promise<PlatformCart> {
    return this.withVersion((cart, version) =>
      this.api.mutate<PlatformCart>(
        'DELETE',
        `${this.brandPath}/carts/${cart.cartId}/lines/${encodeURIComponent(lineKey)}`,
        { expectedVersion: version, idempotencyKey: newIdempotencyKey() },
      ),
    );
  }

  /**
   * Empties the cart, one line at a time.
   *
   * Sequential and not parallel: each delete bumps the version the next one has
   * to present, so firing them together makes every request after the first
   * fail with `STALE_VERSION`.
   */
  async clear(): Promise<PlatformCart | null> {
    let cart = this.cart();
    while (cart && cart.lines.length > 0) {
      cart = await this.removeLine(cart.lines[0].lineKey);
    }
    return cart;
  }

  /**
   * Prices the cart and binds the quote to it.
   *
   * The returned `contextHash` must be carried through to checkout unchanged: it
   * is what stops a client presenting a quote priced for a different, cheaper
   * basket. The quote is good for fifteen minutes.
   */
  async price(): Promise<PricedCart> {
    // Concurrent callers share one request. Pricing is a mutation that bumps the
    // cart version, so two in flight means the second presents a version the
    // first has already spent -- answered 409 STALE_VERSION, recovered by the
    // retry, and entirely avoidable. The cart screen legitimately asks from more
    // than one place at once (a load and a line change landing together).
    this.pricing ??= this.priceOnce().finally(() => {
      this.pricing = null;
    });
    return this.pricing;
  }

  private pricing: Promise<PricedCart> | null = null;

  private async priceOnce(): Promise<PricedCart> {
    return this.withVersion((cart, version) =>
      this.api.mutate<PricedCart>('POST', `${this.brandPath}/carts/${cart.cartId}/pricing`, {
        expectedVersion: version,
        idempotencyKey: newIdempotencyKey(),
      }),
    ) as unknown as Promise<PricedCart>;
  }

  /**
   * Says where a delivery cart is going.
   *
   * Checkout refuses a delivery cart without this, and the refusal is a
   * conflict rather than a validation error: the cart is complete, it simply
   * cannot become an order yet.
   *
   * The address itself is never sent -- the request names one of the caller's
   * own saved addresses by id and the platform copies it across server-side, so
   * a customer who later edits or archives that address cannot move an order
   * already in flight. The recipient's name and phone *are* sent, in the body
   * and never in a path or query, and the platform envelope-encrypts them onto
   * the cart.
   *
   * Setting a destination clears the attached quote and bumps the version,
   * because ADR 0037 prices delivery from the destination and a basket priced
   * to one door is not priced to another. The cart must be priced again before
   * it can be checked out.
   */
  async setDestination(input: {
    addressId: string;
    recipientName: string;
    recipientPhone: string;
    deliveryNote?: string;
  }): Promise<PlatformCart> {
    return this.withVersion((cart, version) =>
      this.api.mutate<PlatformCart>(
        'PUT',
        `${this.brandPath}/carts/${cart.cartId}/destination`,
        {
          body: {
            addressId: input.addressId,
            recipientName: input.recipientName,
            recipientPhone: input.recipientPhone,
            deliveryNote: input.deliveryNote,
          },
          expectedVersion: version,
          idempotencyKey: newIdempotencyKey(),
        },
      ),
    );
  }

  /** What this cart may be paid with, as the platform resolves it today. */
  async paymentMethods(): Promise<PaymentMethods | null> {
    const cart = this.cart();
    if (!cart) {
      return null;
    }
    return this.api.get<PaymentMethods>(`${this.brandPath}/carts/${cart.cartId}/payment-methods`);
  }

  /**
   * Turns a priced cart into an order.
   *
   * @param idempotencyKey formed when the customer pressed the button and reused
   *        on every retry of that one intent. A fresh key on a retry is how one
   *        press becomes two orders.
   */
  async checkout(input: {
    priced: PricedCart;
    paymentMethodCode?: string;
    idempotencyKey: string;
  }): Promise<CheckoutResult> {
    return this.api.mutate<CheckoutResult>('POST', `${this.brandPath}/checkouts`, {
      body: {
        cartId: input.priced.cartId,
        cartVersion: input.priced.cartVersion,
        quoteId: input.priced.quoteId,
        contextHash: input.priced.contextHash,
        paymentMethodCode: input.paymentMethodCode,
      },
      idempotencyKey: input.idempotencyKey,
    });
  }

  /** Forgets this location's cart entirely. Called after a successful checkout. */
  discard(locationId: string): void {
    forgetCartId(locationId);
    this.cart.set(null);
  }

  /**
   * Runs a write with the version this client holds, and retries once if the
   * cart moved underneath it.
   *
   * Once, and not in a loop. A stale version means something else changed the
   * cart — another tab, a retry that actually landed — and reloading and trying
   * again is a fair reading of "the customer still means it". Trying repeatedly
   * is a fight with whatever is moving the cart, and it eventually wins by
   * accident.
   */
  private async withVersion<T extends { version?: number; cartVersion?: number }>(
    call: (cart: PlatformCart, version: number) => Promise<T>,
  ): Promise<T> {
    const cart = this.cart();
    if (!cart) {
      throw new Error('There is no cart to write to.');
    }
    try {
      const result = await call(cart, cart.version);
      this.adopt(result);
      return result;
    } catch (failure) {
      if (!(failure instanceof HorecaOSApiError) || !failure.isStaleVersion) {
        throw failure;
      }
      const fresh = await this.api.get<PlatformCart>(`${this.brandPath}/carts/${cart.cartId}`);
      this.cart.set(fresh);
      const result = await call(fresh, fresh.version);
      this.adopt(result);
      return result;
    }
  }

  /** Keeps the held cart in step with whatever a write returned. */
  private adopt(result: unknown): void {
    const candidate = result as Partial<PlatformCart>;
    if (candidate && typeof candidate.cartId === 'string' && Array.isArray(candidate.lines)) {
      this.cart.set(candidate as PlatformCart);
      return;
    }
    // Pricing returns a quote rather than a cart, and it bumps the version.
    const priced = result as Partial<PricedCart>;
    const held = this.cart();
    if (held && typeof priced?.cartVersion === 'number') {
      this.cart.set({ ...held, version: priced.cartVersion });
    }
  }
}

/**
 * The key that identifies one line.
 *
 * Derived from the variant and the exact modifier selection, sorted, so the same
 * choice always produces the same key and two different choices never collide.
 * Without the modifiers in it, adding "osh with extra meat" to a cart already
 * holding plain osh would replace the plain one.
 */
export function lineKeyFor(variantId: string, modifierOptionIds: readonly string[]): string {
  return modifierOptionIds.length === 0
    ? variantId
    : `${variantId}+${[...modifierOptionIds].sort().join('.')}`;
}

const STORAGE_PREFIX = 'horecaos_cart_';

function readCartId(locationId: string): string | null {
  try {
    return localStorage.getItem(STORAGE_PREFIX + locationId);
  } catch {
    return null;
  }
}

function rememberCartId(locationId: string, cartId: string): void {
  try {
    localStorage.setItem(STORAGE_PREFIX + locationId, cartId);
  } catch {
    // The basket lives for this page only. Better than refusing to sell.
  }
}

function forgetCartId(locationId: string): void {
  try {
    localStorage.removeItem(STORAGE_PREFIX + locationId);
  } catch {
    // Nothing stored.
  }
}

export type FulfillmentMode = 'DELIVERY' | 'PICKUP' | 'DINE_IN';

export interface PlatformCartLine {
  readonly lineKey: string;
  readonly variantId: string;
  readonly quantity: number;
  /** Whether a note exists. Never the note itself. */
  readonly hasCustomerNote: boolean;
}

export interface PlatformCart {
  readonly cartId: string;
  readonly locationId: string;
  readonly status: string;
  readonly currency: string;
  /** The server's fact, not this client's guess. */
  readonly fulfillmentMode: FulfillmentMode;
  readonly version: number;
  readonly quoteId: string | null;
  readonly contextHash: string | null;
  readonly expiresAt: string | null;
  readonly lines: readonly PlatformCartLine[];
}

export interface PricedCart {
  readonly cartId: string;
  readonly cartVersion: number;
  readonly quoteId: string;
  readonly contextHash: string;
  readonly currency: string;
  readonly subtotalMinor: number;
  readonly taxMinor: number;
  readonly totalMinor: number;
  readonly expiresAt: string;
}

export interface PaymentMethods {
  readonly cartId: string;
  readonly currency: string;
  readonly methodCodes: readonly string[];
  readonly warnings: readonly string[];
}

export interface CheckoutResult {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly status: string;
  readonly version: number;
  /**
   * What the platform actually sends is `CREATED` for a new order — not
   * `ACCEPTED`, which is what the earlier hand-written client claimed and what
   * a reader would reasonably code against. Verified against a live checkout:
   * `{"outcome":"CREATED","status":"CONFIRMED"}`.
   *
   * Callers must therefore branch on `REJECTED` and treat everything else as
   * success, rather than testing for one success value and quietly failing a
   * customer whose order was created perfectly well.
   */
  readonly outcome: 'CREATED' | 'REPLAYED' | 'REJECTED' | (string & {});
  readonly warnings: readonly string[];
}
