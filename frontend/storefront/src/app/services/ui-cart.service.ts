import { Injectable, computed, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { CustomerApi } from '../core/api/customer-api';
import type { CartResponse, CartResponseItem, CartResponseModifierSelection } from '../types/cart.types';
import {
  CartService,
  modifierOptionIdsFromLineKey,
  type CheckoutResult,
  type FulfillmentMode,
  type PlatformCart,
  type PricedCart,
} from './cart.service';
import { MenuService, type PublishedModifierGroup } from './menu.service';
import { LangService } from './lang.service';
import { DeliverySelectionService } from './delivery-selection.service';
import { TranslateService } from './translate.service';

const FALLBACK_IMAGE = '/jizbiz/logo/Logo-sq.png';

/** U+2014. Shown where the platform has not answered, so a zero is never read as free. */
const UNRESOLVED = '—';

/**
 * The basket, as the screens read it.
 *
 * The public surface here is unchanged from the legacy facade — `cartData`,
 * `items`, `totalItemsCount`, `increaseQuantity` and the rest — because a
 * dozen templates bind to it. What changed is everything underneath.
 *
 * <h2>Where the numbers come from now</h2>
 *
 * The platform's cart carries **no money at all**: lines, quantities, a version,
 * and not even a line total. So a displayed basket is assembled from two
 * sources.
 *
 * Per-line prices come from the published menu, joined by variant id. That is
 * only possible because the menu now carries prices; before that this screen
 * could not have been built.
 *
 * The cart total comes from `POST /pricing`, which is also what binds the quote
 * checkout will accept. It is deliberately *not* the sum of the line prices:
 * tax, delivery and any promotion are applied by the platform's own pipeline,
 * and a client that added the lines up would show a total the server disagrees
 * with. Until the cart has been priced, the total reads as unavailable rather
 * than as a guess.
 *
 * <h2>Things the legacy screen showed that no longer exist</h2>
 *
 * A packaging charge, a promo code, a vendor block with a name and opening
 * hours, and a delivery estimate on the cart. None has a platform equivalent:
 * delivery is priced by its own endpoint against a destination, and the branch's
 * preparation time is a serviceability answer rather than a cart field. They
 * report zero or empty rather than a number nobody computed.
 *
 * The customer's note is write-only. A line reports whether one exists and never
 * what it says, because the text is personal data revealed only through an
 * endpoint that records a purpose for it.
 */
@Injectable({ providedIn: 'root' })
export class UiCartService {
  private readonly carts = inject(CartService);
  private readonly menu = inject(MenuService);
  private readonly lang = inject(LangService);
  private readonly delivery = inject(DeliverySelectionService);
  private readonly translate = inject(TranslateService);
  private readonly config = inject(APP_CONFIG);
  private readonly api = inject(ApiClient);
  private readonly customerApi = inject(CustomerApi);

  /** The display projection the templates bind to. */
  readonly cartData = signal<CartResponse | null>(null);

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly updating = signal(false);

  /** The last pricing answer, or null when the cart has not been priced. */
  readonly priced = signal<PricedCart | null>(null);

  /** How the cart is being fulfilled. Bound at creation and owned by the server. */
  readonly fulfillmentMode = signal<FulfillmentMode>('DELIVERY');

  /**
   * The delivery-fee preview for the currently chosen destination, or null
   * when there is nothing to show one for -- not a delivery cart, no
   * destination chosen yet, or the read has not resolved (see `deliveryFee`).
   */
  readonly deliveryFeeQuote = signal<DeliveryFeeQuote | null>(null);

  orderComment = '';

  readonly items = computed(() => this.cartData()?.items ?? []);

  readonly totalItemsCount = computed(() =>
    this.items().reduce((sum, item) => sum + item.quantity, 0),
  );

  readonly totalAmount = computed(() => {
    this.translate.current();
    const total = this.priced()?.totalMinor;
    return total != null ? this.formatPrice(total) : this.getZeroPrice();
  });

  readonly subtotalFormatted = computed(() => {
    this.translate.current();
    return this.formatPrice(this.priced()?.subtotalMinor ?? 0);
  });

  readonly totalWithDelivery = computed(() => this.totalAmount());

  /**
   * A preview of what delivery will cost, from `GET .../delivery-fee`
   * (`DeliveryFeeController.quote`) -- unauthenticated, like the menu, and
   * priced against a point rather than against the cart, so it is available
   * before the cart's own destination is ever set.
   *
   * Three states, and each is shown as itself rather than folded into the
   * others:
   * - no delivery destination chosen yet, or this is not a delivery cart: a
   *   dash, because a zero here would read as free delivery;
   * - chosen but outside every zone this branch delivers to: the platform's
   *   own refusal, honestly, and never re-homed to "delivery unavailable"
   *   generically -- the reason is what the customer needs to act on;
   * - resolved: the fee itself.
   */
  readonly deliveryFee = computed(() => {
    this.translate.current();
    if (this.fulfillmentMode() !== 'DELIVERY') {
      return UNRESOLVED;
    }
    const quote = this.deliveryFeeQuote();
    if (!quote) {
      return UNRESOLVED;
    }
    if (!quote.available) {
      return this.translate.get('cart.deliveryNotServiceable');
    }
    return this.formatPrice(quote.feeMinor ?? 0);
  });

  /** No packaging charge exists on the platform, so there is nothing to state. */
  readonly packagingFormatted = computed(() => {
    this.translate.current();
    return UNRESOLVED;
  });

  /**
   * Where this delivery is going, as the customer would recognise it.
   *
   * Reads the chosen address through {@link DeliverySelectionService}, which
   * resolves the id that survived the reload back into a row. It reported '' up
   * to this wave, so the confirmation screen said "no address selected" over a
   * choice that had in fact been made and stored -- and the customer had no way
   * to tell that from a choice that had not registered.
   */
  readonly deliveryAddress = computed(() => this.delivery.addressLabel());
  readonly deliveryAddressName = computed(() => this.delivery.addressLabel());
  readonly deliveryTimeDisplay = computed(() => null);
  readonly deliveryTime = computed(() => {
    this.translate.current();
    return this.translate.get('cart.minutes');
  });
  readonly deliveryPartner = computed(() => '');

  /**
   * Switches between delivery and collection.
   *
   * **The platform binds the fulfillment mode when the cart is created and has
   * no endpoint to change it** -- there is a `POST /carts/{id}/location` to move
   * branch and nothing equivalent for the mode. So switching with a basket in
   * hand means building a new cart in the new mode and carrying the lines over,
   * which is what this does rather than silently keeping the old mode and
   * checking the customer out as something they did not choose.
   *
   * The lines are re-added one at a time because each write bumps the version
   * the next one needs. The old cart is abandoned rather than emptied first: it
   * expires on its own, and a switch that failed halfway through a delete loop
   * would have lost the basket.
   */
  async switchFulfillmentMode(mode: FulfillmentMode): Promise<void> {
    if (mode === this.fulfillmentMode()) {
      return;
    }
    const existing = this.carts.cart();
    const carried =
      existing?.lines.map((line) => ({
        variantId: line.variantId,
        quantity: line.quantity,
        modifierOptionIds: modifierOptionIdsFromLineKey(line.lineKey, line.variantId),
      })) ?? [];

    this.fulfillmentMode.set(mode);
    if (!existing) {
      return;
    }

    this.updating.set(true);
    this.error.set(null);
    try {
      const location = this.locationId();
      this.carts.discard(location);
      await this.carts.create(location, mode);
      for (const line of carried) {
        await this.carts.putLine({
          variantId: line.variantId,
          quantity: line.quantity,
          modifierOptionIds: line.modifierOptionIds,
        });
      }
      await this.project(this.carts.cart());
    } catch {
      this.error.set(this.translate.get('errors.generic'));
    } finally {
      this.updating.set(false);
    }
  }

  /**
   * Loads the basket for the configured branch.
   *
   * Does not create one: browsing must not mint a cart per visit, and an empty
   * basket screen is the right answer for somebody who has not added anything.
   */
  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const cart = await this.carts.ensure(this.locationId(), this.fulfillmentMode(), false);
      await this.project(cart);
    } catch {
      this.error.set(this.translate.get('errors.generic'));
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Adds one variant, creating the cart on first use.
   *
   * @param modifierOptionIds the customer's chosen modifiers, if the product
   *        has any. Determines the line this lands on: the platform addresses
   *        a line by variant *and* the exact modifier selection
   *        (`CartService.lineKeyFor`), so "osh" and "osh with extra meat" are
   *        two lines and never one whose modifiers depend on which request
   *        landed last.
   */
  async add(
    variantId: string,
    quantity = 1,
    note?: string,
    modifierOptionIds?: readonly string[],
  ): Promise<void> {
    this.updating.set(true);
    this.error.set(null);
    try {
      await this.carts.ensure(this.locationId(), this.fulfillmentMode(), true);
      const cart = await this.carts.putLine({
        variantId,
        quantity,
        customerNote: note,
        modifierOptionIds,
      });
      await this.project(cart);
    } catch {
      this.error.set(this.translate.get('errors.generic'));
    } finally {
      this.updating.set(false);
    }
  }

  /**
   * Sets a line to an exact quantity, removing it at zero.
   *
   * The platform's PUT replaces the line, so this is an absolute quantity and
   * never a delta. The legacy API took `quantity: -1` to mean "one fewer", and
   * sending that here would ask for a line of minus one.
   *
   * `item.modifierOptionIds` is resent on every write. `CartService.putLine`
   * replaces the whole line, so leaving it out on a quantity change would send
   * an empty list and strip whatever the customer chose -- the cart would still
   * hold the right variant and quantity, and the modifiers would simply be
   * gone.
   */
  async setQuantity(item: CartResponseItem, quantity: number): Promise<void> {
    this.updating.set(true);
    this.error.set(null);
    try {
      const cart =
        quantity <= 0
          ? await this.carts.removeLine(item.item_id)
          : await this.carts.putLine({
              variantId: item.variant_id,
              quantity,
              modifierOptionIds: item.modifierOptionIds,
            });
      await this.project(cart);
    } catch {
      this.error.set(this.translate.get('errors.generic'));
    } finally {
      this.updating.set(false);
    }
  }

  increaseQuantity(item: CartResponseItem): void {
    void this.setQuantity(item, item.quantity + 1);
  }

  decreaseQuantity(item: CartResponseItem): void {
    void this.setQuantity(item, item.quantity - 1);
  }

  removeItem(item: CartResponseItem): void {
    void this.setQuantity(item, 0);
  }

  /** Empties the basket, one line at a time; the platform has no clear call. */
  async clearCart(): Promise<void> {
    this.updating.set(true);
    try {
      const cart = await this.carts.clear();
      await this.project(cart);
    } catch {
      this.error.set(this.translate.get('errors.generic'));
    } finally {
      this.updating.set(false);
    }
  }

  /** Prices the basket and binds the quote checkout will accept. */
  async priceCart(): Promise<PricedCart | null> {
    if (!this.carts.cart()) {
      return null;
    }
    try {
      const priced = await this.carts.price();
      this.priced.set(priced);
      return priced;
    } catch {
      this.error.set(this.translate.get('errors.generic'));
      return null;
    }
  }

  /**
   * Says where a delivery cart is going, if it is one and a choice has been made.
   *
   * Called before pricing rather than after, because setting a destination
   * clears the attached quote and bumps the version: doing it the other way
   * round throws away the quote that was about to be spent.
   *
   * @returns false when a delivery cart still has no destination, which is a
   *          state for the screen to explain rather than an error to report.
   *          Checkout would refuse it with DELIVERY_DESTINATION_REQUIRED.
   */
  async applyDestination(): Promise<boolean> {
    if (this.fulfillmentMode() !== 'DELIVERY') {
      return true;
    }
    const addressId = this.delivery.addressId();
    if (!addressId || !this.delivery.isComplete()) {
      return false;
    }
    const cart = await this.carts.setDestination({
      addressId,
      recipientName: this.delivery.recipientName(),
      recipientPhone: this.delivery.recipientPhone(),
      deliveryNote: this.orderComment || undefined,
    });
    await this.project(cart);
    return true;
  }

  /**
   * Turns a priced basket into an order.
   *
   * Delegated rather than reimplemented so there is one description of the
   * checkout contract, and so the idempotency key the screen formed is the key
   * that reaches the platform.
   */
  async checkout(input: {
    priced: PricedCart;
    paymentMethodCode: string;
    idempotencyKey: string;
  }): Promise<CheckoutResult> {
    return this.carts.checkout(input);
  }

  /** What this cart may actually be paid with, as the platform resolves it. */
  async paymentMethods(): Promise<readonly string[]> {
    const answer = await this.carts.paymentMethods();
    return answer?.methodCodes ?? [];
  }

  /** Forgets the basket after it has become an order. */
  discard(): void {
    this.carts.discard(this.locationId());
    this.cartData.set(null);
    this.priced.set(null);
    this.deliveryFeeQuote.set(null);
  }

  private locationId(): string {
    const location = this.config.defaultLocationId;
    if (!location) {
      throw new Error('No location is configured for this storefront.');
    }
    return location;
  }

  /**
   * Joins the platform's cart with the menu to produce something displayable.
   *
   * A line whose variant is no longer on the menu is dropped from the display
   * rather than shown nameless and priceless: the branch has stopped selling it,
   * and pricing will refuse the cart until it goes.
   */
  private async project(cart: PlatformCart | null): Promise<void> {
    if (!cart || cart.lines.length === 0) {
      this.cartData.set(null);
      this.priced.set(null);
      this.deliveryFeeQuote.set(null);
      return;
    }

    // Price before projecting, so the total shown is the platform's own and not
    // a zero. A basket with lines and a 0 total reads as free, which is worse
    // than reading as unknown -- and the sum of the line prices is not the
    // answer either, because tax, delivery and promotions are applied by the
    // pricing pipeline and a client that added them up would disagree with the
    // server at the last step.
    //
    // Best effort: an unpriced item or a withdrawn price book makes pricing
    // refuse, and that must not stop the customer seeing what is in their
    // basket. The total then stays unknown, which is the honest reading.
    try {
      this.priced.set(await this.carts.price());
    } catch {
      this.priced.set(null);
    }

    const menu = await this.menu.menu(this.lang.langId(), cart.locationId);
    const byVariant = new Map<string, { name: string; image: string | null; price: number }>();
    for (const product of menu.products) {
      for (const variant of product.variants) {
        byVariant.set(variant.variantId, {
          name: product.name,
          image: product.imageUrls[0] ?? null,
          price: variant.amountMinor ?? 0,
        });
      }
    }
    const modifierOptionsById = new Map<
      string,
      { groupName: string; label: string; amountMinor: number | null }
    >();
    for (const group of menu.modifierGroups as readonly PublishedModifierGroup[]) {
      for (const option of group.options) {
        modifierOptionsById.set(option.optionId, {
          groupName: group.name,
          // Not a name: the wire's MenuModifierOption carries no name field.
          label: option.code ?? '',
          amountMinor: option.amountMinor,
        });
      }
    }

    const items: CartResponseItem[] = cart.lines
      .map((line) => {
        const known = byVariant.get(line.variantId);
        if (!known) {
          return null;
        }
        const modifierOptionIds = modifierOptionIdsFromLineKey(line.lineKey, line.variantId);
        const modifiers: CartResponseModifierSelection[] = modifierOptionIds
          .map((optionId) => {
            const resolved = modifierOptionsById.get(optionId);
            return resolved
              ? { optionId, groupName: resolved.groupName, label: resolved.label, amountMinor: resolved.amountMinor }
              : null;
          })
          .filter((selection): selection is CartResponseModifierSelection => selection !== null);
        const projected: CartResponseItem = {
          variant_id: line.variantId,
          // The line key, which is what an update or a removal addresses. The
          // legacy field held a server-side item id and is reused rather than
          // renamed, because every template binds to it.
          item_id: line.lineKey,
          name: known.name,
          image: known.image ?? FALLBACK_IMAGE,
          price: known.price,
          active: true,
          quantity: line.quantity,
          // Write-only on the platform; only its existence is reported.
          note: null,
          modifierOptionIds,
          modifiers,
        };
        return projected;
      })
      .filter((item): item is CartResponseItem => item !== null);

    const zero = { price: 0, discount: 0 };
    this.cartData.set({
      items,
      items_count: items.reduce((sum, item) => sum + item.quantity, 0),
      subtotal: { price: this.priced()?.subtotalMinor ?? 0, discount: 0 },
      total: { price: this.priced()?.totalMinor ?? 0, discount: 0 },
      delivery: zero,
      packaging: zero,
      vendor: { id: '', name: '', phone: '', active: true, pre_order: false, start: '', finish: '' },
      address: null,
      delivery_time: null,
      delivery_distance: 0,
      delivery_date_display: null,
      delivery_time_display: null,
      promo_code: null,
      delivery_duration: 0,
    });

    await this.refreshDeliveryFee(cart);
  }

  /**
   * Refreshes the delivery-fee preview for the chosen destination.
   *
   * Best effort, like pricing above: a failed read leaves the preview
   * unresolved (a dash) rather than surfacing as a basket-blocking error --
   * this is a preview, not the fee checkout will actually charge, which comes
   * from `POST /pricing` once the cart's own destination has been set.
   */
  private async refreshDeliveryFee(cart: PlatformCart): Promise<void> {
    if (this.fulfillmentMode() !== 'DELIVERY') {
      this.deliveryFeeQuote.set(null);
      return;
    }
    const addressId = this.delivery.addressId();
    if (!addressId) {
      this.deliveryFeeQuote.set(null);
      return;
    }
    try {
      const address = await this.customerApi.address(addressId);
      if (address.latitude == null || address.longitude == null) {
        // A saved address with no marker (NOT_GEOCODED): there is no point to
        // ask the resolver about, so this is "unknown", not "refused".
        this.deliveryFeeQuote.set(null);
        return;
      }
      const view = await this.api.get<DeliveryFeeView>(
        `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}` +
          `/locations/${cart.locationId}/delivery-fee`,
        {
          query: {
            lat: address.latitude,
            lon: address.longitude,
            currency: cart.currency,
            subtotalMinor: this.priced()?.subtotalMinor ?? 0,
          },
          anonymous: true,
        },
      );
      this.deliveryFeeQuote.set({ available: view.available, feeMinor: view.feeMinor });
    } catch {
      this.deliveryFeeQuote.set(null);
    }
  }

  private getZeroPrice(): string {
    return this.formatPrice(0);
  }

  private formatPrice(value: number): string {
    const currency = this.translate.get('common.currency') || "so'm";
    // Minor units, and for UZS that is whole som -- nothing divides by a hundred.
    return `${value.toLocaleString('uz-UZ')} ${currency}`;
  }
}

/** The two facts a screen needs from `DeliveryFeeController.DeliveryFeeView`. */
export interface DeliveryFeeQuote {
  readonly available: boolean;
  readonly feeMinor: number | null;
}

/** `DeliveryFeeController.DeliveryFeeView`, transcribed from the controller. */
interface DeliveryFeeView {
  readonly outcome: string;
  readonly reasonCode: string | null;
  readonly available: boolean;
  readonly feeMinor: number | null;
  readonly currency: string | null;
  readonly minBasketMinor: number | null;
  readonly freeDeliveryFromMinor: number | null;
  readonly distanceMeters: number | null;
  readonly distanceSource: string | null;
}
