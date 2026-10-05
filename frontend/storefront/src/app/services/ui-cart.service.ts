import { Injectable, computed, inject, signal } from '@angular/core';

import { APP_CONFIG } from '../core/config/app-config';
import { HorecaOSApiError, messageKeyFor, reasonMessageKey } from '../core/api/problem-details';
import type {
  CartResponse,
  CartResponseComboComponent,
  CartResponseCommentPresetSelection,
  CartResponseItem,
  CartResponseModifierSelection,
} from '../types/cart.types';
import {
  CartService,
  optionIdsOfLine,
  type CheckoutResult,
  type DeliveryCharge,
  type FulfillmentMode,
  type PlatformCart,
  type PricedCart,
} from './cart.service';
import type { ComboPickWire } from '../utils/combo-selection';
import type { NestedModifierWire } from '../utils/modifier-selection';
import {
  MenuService,
  type PublishedCommentPreset,
  type PublishedModifierGroup,
} from './menu.service';
import {
  discountRowsFor,
  giftOfferGroups,
  noteRowsFor,
  promoOutcomeKey as promoOutcomeKeyOf,
  type GiftOfferGroup,
  type PromotionRow,
} from './applied-promotions';
import { LangService } from './lang.service';
import { DeliverySelectionService } from './delivery-selection.service';
import { TranslateService } from './translate.service';
import { lineAmountMinor, portionStep, type PhysicalFacts } from '../utils/physical';

/** Final -- checkout will accept the fee -- vs. still a refusal. */
function isDeliveryFeeUsable(outcome: string): boolean {
  return outcome === 'RESOLVED' || outcome === 'EXTERNALLY_PRICED';
}

const FALLBACK_IMAGE = '/assets/logo/Logo-sq.png';

/** ADR 0136: one charge the server added by itself, for display. */
export interface HiddenChargeRow {
  readonly optionId: string;
  /** What the option is called, in the customer's language. */
  readonly label: string;
  readonly amountMinor: number;
  readonly amount: string;
}

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
 * <h2>Delivery is priced with the cart, not against a coordinate</h2>
 *
 * `POST /pricing`'s own response carries a `delivery` block (ADR 0037) once a
 * `DELIVERY` cart's destination has been set -- see {@link deliveryCharge}.
 * There used to also be a separate preview call,
 * `GET .../delivery-fee?lat=..&lon=..`, that priced a raw coordinate before a
 * destination was ever chosen; it is gone from here on purpose; the
 * customer's coordinates in a URL query string are personal data the
 * platform's own logging and error-reporting rules refuse to carry, and the
 * priced cart is both the more honest number (it is what checkout will
 * actually charge) and the only one that needs no second endpoint.
 *
 * <h2>Things the legacy screen showed that no longer exist</h2>
 *
 * A packaging charge and a vendor block with a name and opening hours. Neither
 * has a platform equivalent, and the branch's preparation time is a
 * serviceability answer rather than a cart field. They report zero or empty
 * rather than a number nobody computed. (A promo code used to be on this list;
 * ADR 0072 gave it a real source, `PlatformCart.appliedPromoCode`.)
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

  /** The display projection the templates bind to. */
  readonly cartData = signal<CartResponse | null>(null);

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly updating = signal(false);

  /** The last pricing answer, or null when the cart has not been priced. */
  readonly priced = signal<PricedCart | null>(null);

  /**
   * The mode to build or read a cart in before any cart exists for this
   * location -- what `switchFulfillmentMode` sets, and what a not-yet-created
   * basket reads. Once a cart exists, {@link fulfillmentMode} ignores this
   * entirely and reports the cart's own fact instead.
   */
  private readonly fulfillmentModeDefault = signal<FulfillmentMode>('DELIVERY');

  /**
   * How the cart is being fulfilled.
   *
   * The server's own fact (`carts.cart().fulfillmentMode`) whenever a cart
   * exists -- **never** a client guess -- because the platform binds the mode
   * at creation and this client has no way to change it after the fact (see
   * {@link switchFulfillmentMode}). Before this was derived from the cart, a
   * page opened by a fresh load of `/cart` or `/cart/confirmation` -- rather
   * than reached by an in-app navigation that had already called
   * `switchFulfillmentMode` -- read whatever this signal's stale default
   * still was, which was always `'DELIVERY'`: a PICKUP cart's own
   * confirmation screen showed the delivery address block, an unresolvable
   * "choose an address" message, and a button disabled on a fee that was
   * never going to resolve because there was nothing to deliver.
   *
   * Falls back to {@link fulfillmentModeDefault} only when there is no cart
   * yet to have an opinion.
   */
  readonly fulfillmentMode = computed<FulfillmentMode>(
    () => this.carts.cart()?.fulfillmentMode ?? this.fulfillmentModeDefault(),
  );

  orderComment = '';

  readonly items = computed(() => this.cartData()?.items ?? []);

  /**
   * How many items the basket holds, for the badge: a half portion is one plate somebody has to
   * make (ADR 0137), so each line counts its quantity rounded up, never as a fraction of one.
   */
  readonly totalItemsCount = computed(() =>
    this.items().reduce((sum, item) => sum + Math.ceil(item.quantity), 0),
  );

  /**
   * Whether the basket holds an item sold by weight (ADR 0137): its amount is an estimate at the
   * item's nominal weight, and the final weight and total are determined at handover.
   */
  readonly hasProvisionalLines = computed(() =>
    this.items().some((item) => item.physical?.catchweight),
  );

  /**
   * One line's amount in minor units: the price row times the quantity for a portion, or for a
   * weighed item its price per quantum at the estimated weight. The platform prices the cart; this
   * is what a line reads before that, rounded the way the platform rounds.
   */
  lineAmount(item: CartResponseItem): number {
    return lineAmountMinor(item.price, item.quantity, item.physical);
  }

  /** The step a line's quantity moves in: its portion size, or one. */
  stepOf(item: { physical?: PhysicalFacts | null }): number {
    return portionStep(item.physical);
  }

  /**
   * The priced cart's own total -- goods, tax and (once resolved) delivery,
   * already summed server-side. This is deliberately the one number the
   * order button ever charges: a client-side sum of the lines below would
   * disagree with the platform at the last decimal the moment a promotion or
   * a rounding rule applies.
   */
  readonly totalAmount = computed(() => {
    this.translate.current();
    const total = this.priced()?.totalMinor;
    return total != null ? this.formatPrice(total) : this.getZeroPrice();
  });

  /** The goods line -- net of tax. Shown beside {@link taxFormatted} so the
   * two together, plus delivery, reconcile to {@link totalAmount}. */
  readonly subtotalFormatted = computed(() => {
    this.translate.current();
    return this.formatPrice(this.priced()?.subtotalMinor ?? 0);
  });

  /**
   * The tax line, when the priced cart reports one.
   *
   * Shown as its own row rather than folded silently into the goods line --
   * under an INCLUSIVE tax profile the platform's `subtotalMinor` is net of
   * tax, and a screen that showed goods + delivery as the whole story never
   * summed to `totalMinor`. `null` (not a dash) when there is genuinely
   * nothing to tax yet, so a template can hide the row instead of printing
   * "0 so'm" beside every other line.
   */
  readonly taxFormatted = computed(() => {
    this.translate.current();
    const tax = this.priced()?.taxMinor;
    return tax ? this.formatPrice(tax) : null;
  });

  /** The discount line, when the priced cart carries one. `null` hides the row. */
  readonly discountFormatted = computed(() => {
    this.translate.current();
    const discount = this.priced()?.discountMinor;
    return discount ? this.formatPrice(discount) : null;
  });

  /**
   * The discounts behind {@link discountFormatted}, one line per kind, each with
   * the platform's own amount (ADR 0140). Their sum is the discount. A discount the
   * platform reports without saying where it came from (an answer that predates the
   * breakdown) is one generic line, so the total is never left with an unexplained gap.
   */
  readonly discountRows = computed<readonly PromotionRow[]>(() => {
    this.translate.current();
    return discountRowsFor(
      this.priced()?.appliedPromotions,
      this.priced()?.discountMinor ?? 0,
      (minor) => this.formatPrice(minor),
    );
  });

  /**
   * Benefits already inside the delivery price or the goods (a delivery offer, a
   * surcharge), as captions with the platform's amount. Not added to the sum.
   */
  readonly promotionNotes = computed<readonly PromotionRow[]>(() => {
    this.translate.current();
    return noteRowsFor(this.priced()?.appliedPromotions, (minor) => this.formatPrice(minor));
  });

  readonly totalWithDelivery = computed(() => this.totalAmount());

  /**
   * The code on the cart: the server's own fact, as {@link project} last read it, never what
   * {@link applyPromoCode} was last called with. Null when the cart carries none.
   */
  readonly appliedPromoCode = computed(() => this.cartData()?.promo_code ?? null);

  /** Busy flag for the promo field alone, so applying a code does not grey out the whole basket. */
  readonly promoBusy = signal(false);

  /** The last promo refusal, as a customer-facing sentence -- never a raw code. */
  readonly promoError = signal<string | null>(null);

  /**
   * What the typed code itself took off, apart from the offers that applied without it, from the
   * platform's own breakdown. Null when the code took nothing off (it did not apply, or the offers
   * beat it): the code's chip must not claim a saving that belongs to an automatic offer.
   */
  readonly promoCodeDiscountFormatted = computed(() => {
    this.translate.current();
    const amountMinor = (this.priced()?.appliedPromotions ?? [])
      .filter((entry) => entry.source === 'PROMO_CODE' && entry.effect === 'DISCOUNT')
      .reduce((sum, entry) => sum + entry.amountMinor, 0);
    return amountMinor > 0 ? this.formatPrice(amountMinor) : null;
  });

  /**
   * The sentence for a code that is on the cart and did not move the price ("your offers are
   * already better"), from the platform's own verdict at the last price. Null when there is
   * nothing to say, which includes a stale verdict about a code that is no longer on the cart.
   */
  readonly promoOutcomeKey = computed(() =>
    this.appliedPromoCode() ? promoOutcomeKeyOf(this.priced()?.promoCodeOutcome) : null,
  );

  /**
   * Variant id to what the customer reads on it, for the variants the menu says can be ordered
   * right now, as the basket was last projected.
   */
  private readonly offerableVariants = signal<
    ReadonlyMap<string, { readonly name: string; readonly image: string | null }>
  >(new Map());

  /**
   * The free gifts the cart could take up (ADR 0140): offers, never lines. Each is named from the
   * menu, and a gift the menu does not carry or cannot sell right now is not offered. Nothing here has been added to the
   * basket or discounted; {@link addGift} adds one through the ordinary cart call and the platform
   * prices it free on the next price.
   */
  readonly giftOffers = computed<readonly GiftOfferGroup[]>(() =>
    giftOfferGroups(this.priced()?.giftOffers, this.offerableVariants()),
  );

  /** Option id to what the customer reads on it (its name, else its code), read off the menu as the basket was last projected. */
  private readonly optionLabels = signal<ReadonlyMap<string, string>>(new Map());

  /**
   * ADR 0136: what the server added to this order by itself -- a delivery box the customer never
   * chose -- itemised, one row per option with the amount summed over the lines it was applied to.
   *
   * Already inside the lines and the total above, never on top of them: this is the disclosure the
   * record asks the storefront to carry, so the total can be read against what was chosen. Empty for
   * a cart of another fulfilment mode, which the server adds nothing to. The wording around it is
   * product and legal's to settle (the record's open input), so the screen states only what is
   * true: what it is called, what it costs, and that it is already counted.
   */
  readonly hiddenCharges = computed<readonly HiddenChargeRow[]>(() => {
    this.translate.current();
    const byOption = new Map<string, number>();
    for (const charge of this.priced()?.hiddenCharges ?? []) {
      byOption.set(charge.optionId, (byOption.get(charge.optionId) ?? 0) + charge.amountMinor);
    }
    const labels = this.optionLabels();
    return [...byOption.entries()].map(([optionId, amountMinor]) => ({
      optionId,
      label: labels.get(optionId) || this.translate.get('cart.hiddenCharge.fallbackLabel'),
      amountMinor,
      amount: this.formatPrice(amountMinor),
    }));
  });

  /**
   * The ADR 0037 delivery charge, straight from the priced cart's own
   * `delivery` block -- never from a separate coordinate-bearing preview
   * call. `POST /pricing` is what checkout will actually charge, so this is
   * the one figure that can never disagree with the total above it.
   *
   * `null` for a `PICKUP`/`DINE_IN` cart (no delivery to price) and for a
   * `DELIVERY` cart whose destination has not been priced yet -- both read
   * as "not applicable" rather than as a refusal.
   */
  readonly deliveryCharge = computed<DeliveryCharge | null>(() => {
    if (this.fulfillmentMode() !== 'DELIVERY') {
      return null;
    }
    return this.priced()?.delivery ?? null;
  });

  /** True once a `DELIVERY` cart's fee is final and checkout will accept it.
   * Always true for a non-delivery cart, which has no fee to resolve. */
  readonly deliveryFeeResolved = computed(() => {
    if (this.fulfillmentMode() !== 'DELIVERY') {
      return true;
    }
    const charge = this.deliveryCharge();
    return !!charge && isDeliveryFeeUsable(charge.outcome);
  });

  /**
   * The delivery-fee line, for display.
   *
   * Three states, each shown as itself rather than folded into the others:
   * - not a delivery cart: a dash, because a zero here would read as free
   *   delivery on an order that never had a delivery line to begin with;
   * - a delivery cart whose fee is not yet final (no destination chosen, out
   *   of zone, below the zone's minimum basket, ...): a dash -- the reason
   *   is {@link deliveryUnresolvedMessage}, read separately so a template can
   *   show it as an explanation rather than in place of a price;
   * - resolved: the fee itself, exactly what `totalAmount` already includes.
   */
  readonly deliveryFee = computed(() => {
    this.translate.current();
    if (this.fulfillmentMode() !== 'DELIVERY') {
      return UNRESOLVED;
    }
    const charge = this.deliveryCharge();
    if (!charge || !isDeliveryFeeUsable(charge.outcome)) {
      return UNRESOLVED;
    }
    return this.formatPrice(charge.feeMinor);
  });

  /**
   * Why the delivery fee is not final yet, in the customer's language, or
   * `null` when there is nothing to explain (not a delivery cart, or the fee
   * is already resolved). Drives both the inline explanation under the
   * delivery line and the order button's disabled state.
   */
  readonly deliveryUnresolvedMessage = computed(() => {
    this.translate.current();
    if (this.fulfillmentMode() !== 'DELIVERY' || this.deliveryFeeResolved()) {
      return null;
    }
    const charge = this.deliveryCharge();
    if (!charge) {
      // No destination chosen yet -- pricing was never asked to resolve one.
      return this.translate.get('cart.deliveryChooseAddress');
    }
    if (charge.reasonCode === 'BELOW_MINIMUM_BASKET' && charge.minBasketMinor != null) {
      return this.translate.getWithParams('errors.reason.minimumBasketAmount', {
        amount: this.formatPrice(charge.minBasketMinor),
      });
    }
    const key = reasonMessageKey(charge.reasonCode);
    return this.translate.get(key ?? 'errors.reason.deliveryFeeUnresolved');
  });

  /**
   * True once this basket may be checked out: a `PICKUP`/`DINE_IN` cart
   * always may, and a `DELIVERY` cart only once its fee has resolved.
   * `CheckoutEligibilityGuard` refuses the alternative server-side
   * (`DELIVERY_FEE_UNRESOLVED` / `DELIVERY_MINIMUM_BASKET_NOT_MET`) -- this
   * is what stops the customer discovering that only after pressing the
   * button.
   */
  readonly canPlaceOrder = computed(() => this.deliveryFeeResolved());

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
        modifierOptionIds: optionIdsOfLine(line),
        // Row 2.1b: carried across the same as the modifiers above -- a mode
        // switch rebuilds every line from scratch and must not drop what the
        // customer already picked.
        commentPresetCodes: line.commentPresetCodes,
        // ADR 0136: and a combo's picks, or its container would be put back with nothing chosen.
        ...(line.comboPicks && line.comboPicks.length > 0 ? { comboPicks: line.comboPicks } : {}),
        // ... and the second-level answers, or the options that asked for them would be refused.
        ...(line.nestedModifiers && line.nestedModifiers.length > 0
          ? { nestedModifiers: line.nestedModifiers }
          : {}),
      })) ?? [];

    this.fulfillmentModeDefault.set(mode);
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
          commentPresetCodes: line.commentPresetCodes,
          ...('comboPicks' in line ? { comboPicks: line.comboPicks } : {}),
          ...('nestedModifiers' in line ? { nestedModifiers: line.nestedModifiers } : {}),
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
   * @param commentPresetCodes row 2.1b: the coded presets the customer picked
   *        from the product's own offered subset. Unlike `modifierOptionIds`,
   *        never part of the line's own identity -- checking a different
   *        preset for the same variant and modifiers replaces the line's
   *        presets the same way a second note replaces the first (`CartService
   *        .putLine`'s own doc).
   * @param comboPicks ADR 0136: what the customer picked inside a combo, set exactly when
   *        `variantId` is a combo's container. Part of the line's identity, like the modifiers: the
   *        same combo with other picks is another line.
   * @param nestedModifiers ADR 0136: the second-level answers, each under the first-level option
   *        that opened it. Part of the line's identity as well.
   */
  async add(
    variantId: string,
    quantity = 1,
    note?: string,
    modifierOptionIds?: readonly string[],
    commentPresetCodes?: readonly string[],
    comboPicks?: readonly ComboPickWire[],
    nestedModifiers?: readonly NestedModifierWire[],
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
        commentPresetCodes,
        comboPicks,
        nestedModifiers,
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
   * gone. `item.commentPresetCodes` (row 2.1b) is resent for the identical
   * reason.
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
              commentPresetCodes: item.commentPresetCodes,
              // ADR 0136: resent whole, or a quantity change would strip a combo's picks
              // and the second-level answers under the options.
              comboPicks: item.comboPicks,
              nestedModifiers: item.nestedModifiers,
            });
      await this.project(cart);
    } catch {
      this.error.set(this.translate.get('errors.generic'));
    } finally {
      this.updating.set(false);
    }
  }

  increaseQuantity(item: CartResponseItem): void {
    void this.setQuantity(item, tidy(item.quantity + this.stepOf(item)));
  }

  decreaseQuantity(item: CartResponseItem): void {
    void this.setQuantity(item, tidy(item.quantity - this.stepOf(item)));
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

  /**
   * Applies a promo code, ADR 0072.
   *
   * Never subtracts anything itself: it writes the code to the cart, then re-prices through
   * {@link project} so the discount and the total are the platform's own answer. A refusal is
   * translated to a customer-facing sentence keyed off `problem.reason`, never shown as the raw
   * ADR 0031 code.
   *
   * @returns false on refusal, without touching the cart's existing state -- a customer who
   *          typed a bad code keeps whatever was priced before.
   */
  async applyPromoCode(code: string): Promise<boolean> {
    const trimmed = code.trim();
    if (!trimmed) {
      return false;
    }
    this.promoBusy.set(true);
    this.promoError.set(null);
    try {
      const cart = await this.carts.applyPromoCode(trimmed);
      await this.project(cart);
      return true;
    } catch (failure) {
      this.promoError.set(this.promoErrorMessage(failure));
      return false;
    } finally {
      this.promoBusy.set(false);
    }
  }

  /** Removes the applied promo code, then re-prices without it. */
  async removePromoCode(): Promise<void> {
    this.promoBusy.set(true);
    this.promoError.set(null);
    try {
      const cart = await this.carts.removePromoCode();
      await this.project(cart);
    } catch (failure) {
      this.promoError.set(this.promoErrorMessage(failure));
    } finally {
      this.promoBusy.set(false);
    }
  }

  private promoErrorMessage(failure: unknown): string {
    return this.translate.get(
      failure instanceof HorecaOSApiError ? messageKeyFor(failure) : 'errors.generic',
    );
  }

  /**
   * Takes up a free gift the platform offered (ADR 0140): puts it in the basket through the
   * ordinary line write, then prices again, so it is free because the platform priced it so and
   * for no other reason.
   *
   * Adds only what is still missing from the rule's allowance. A line for the same variant that
   * the basket already holds is topped up, not duplicated, and is written back whole -- its
   * modifiers, presets and picks -- because the platform's PUT replaces the line.
   *
   * Does nothing for a variant that is not on offer, so a stale button cannot add a dish the
   * platform no longer gives away.
   */
  async addGift(variantId: string): Promise<void> {
    const group = this.giftOffers().find((candidate) =>
      candidate.choices.some((choice) => choice.variantId === variantId),
    );
    if (!group) {
      return;
    }
    const held = this.items().find(
      (item) =>
        item.variant_id === variantId &&
        item.modifierOptionIds.length === 0 &&
        (item.comboPicks?.length ?? 0) === 0,
    );
    await this.add(
      variantId,
      tidy((held?.quantity ?? 0) + group.toAdd),
      undefined,
      held?.modifierOptionIds,
      held?.commentPresetCodes,
      held?.comboPicks,
    );
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
   * The exact destination this client last wrote to *this* cart -- compared
   * before writing again so an unchanged retry (the same address, recipient,
   * phone and note, against the cart that already has them) skips the PUT
   * entirely.
   *
   * Setting a destination always bumps the cart's version and clears its
   * quote (ADR 0037; see {@link applyDestination}'s own doc). Before this,
   * `submitOrder()` re-ran `applyDestination()` on every retry regardless --
   * so a checkout rejected for an unrelated reason (a stale quote, a moved
   * cart version, an expired cart, ...) still spent that bump on a
   * destination that had not actually changed, and the retry's own re-price
   * then diverged from the one the rejected attempt sent under the same
   * idempotency key. See `CartConfirmationComponent`'s key-rotation doc for
   * the other half of that fix.
   */
  private lastAppliedDestination: {
    readonly cartId: string;
    readonly addressId: string;
    readonly recipientName: string;
    readonly recipientPhone: string;
    readonly deliveryNote: string | undefined;
  } | null = null;

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
    const recipientName = this.delivery.recipientName();
    const recipientPhone = this.delivery.recipientPhone();
    const deliveryNote = this.orderComment || undefined;

    const cartId = this.carts.cart()?.cartId;
    const last = this.lastAppliedDestination;
    if (
      cartId &&
      last?.cartId === cartId &&
      last.addressId === addressId &&
      last.recipientName === recipientName &&
      last.recipientPhone === recipientPhone &&
      last.deliveryNote === deliveryNote
    ) {
      return true;
    }

    const cart = await this.carts.setDestination({
      addressId,
      recipientName,
      recipientPhone,
      deliveryNote,
    });
    this.lastAppliedDestination = {
      cartId: cart.cartId,
      addressId,
      recipientName,
      recipientPhone,
      deliveryNote,
    };
    await this.project(cart);
    return true;
  }

  /**
   * Tells the platform how the customer means to pay, so the cart is priced with it (ADR 0140).
   *
   * A promotion can read the payment method ("5% off when paying by Click"), so the method is an
   * input to the price and has to be on the cart before it is priced: checkout refuses a method
   * the quote was not priced under (`PRICE_CHANGED`) whenever such a promotion exists. Writing it
   * clears the attached quote, so the basket is re-priced here through {@link project} and the
   * total on screen is the platform's own answer for the chosen method.
   *
   * Does nothing when the cart already carries this method, which keeps the call free to repeat
   * ahead of every checkout. A refusal is thrown to the caller, who owns the screen's error.
   */
  async selectPaymentMethod(paymentMethodCode: string): Promise<void> {
    const held = this.carts.cart();
    if (!held || held.paymentMethodCode === paymentMethodCode) {
      return;
    }
    await this.project(await this.carts.selectPaymentMethod(paymentMethodCode));
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
    this.lastAppliedDestination = null;
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
    const byVariant = new Map<
      string,
      {
        name: string;
        image: string | null;
        price: number;
        commentPresets: readonly PublishedCommentPreset[];
        physical: PhysicalFacts | null;
      }
    >();
    const offerable = new Map<string, { name: string; image: string | null }>();
    for (const product of menu.products) {
      for (const variant of product.variants) {
        byVariant.set(variant.variantId, {
          name: product.name,
          image: product.imageUrls[0] ?? null,
          price: variant.amountMinor ?? 0,
          commentPresets: product.commentPresets,
          physical: variant.physical ?? null,
        });
        // A gift that cannot be ordered right now (sold out, stopped, outside its sale window)
        // is not offered: adding it would only be refused.
        if (variant.orderable !== false && variant.onSaleNow !== false) {
          offerable.set(variant.variantId, {
            name: product.name,
            image: product.imageUrls[0] ?? null,
          });
        }
      }
    }
    this.offerableVariants.set(offerable);
    const modifierOptionsById = new Map<
      string,
      { groupName: string; label: string; amountMinor: number | null }
    >();
    for (const group of menu.modifierGroups as readonly PublishedModifierGroup[]) {
      for (const option of group.options) {
        modifierOptionsById.set(option.optionId, {
          groupName: group.name,
          // The option's name in the customer's language when the menu carries one (ADR 0136),
          // else the authoring code a menu published before options were named still sends.
          label: option.name || option.code || '',
          amountMinor: option.amountMinor,
        });
      }
    }
    this.optionLabels.set(
      new Map([...modifierOptionsById].map(([optionId, option]) => [optionId, option.label])),
    );
    // ADR 0136: a combo component, by the id a pick names.
    const comboComponentsById = new Map<
      string,
      {
        name: string;
        variantName: string | null;
        defaultQuantity: number;
        amountMinor: number | null;
      }
    >();
    for (const group of menu.comboGroups ?? []) {
      for (const component of group.components) {
        comboComponentsById.set(component.componentId, {
          name: component.name,
          variantName: component.variantName ?? null,
          defaultQuantity: component.defaultQuantity,
          amountMinor: component.amountMinor,
        });
      }
    }

    const items: CartResponseItem[] = cart.lines
      .map((line) => {
        const known = byVariant.get(line.variantId);
        if (!known) {
          return null;
        }
        const modifierOptionIds = optionIdsOfLine(line);
        const nestedModifiers = line.nestedModifiers ?? [];
        // The first-level choices, then the answers under them (each marked with its parent).
        const modifiers: CartResponseModifierSelection[] = [
          ...modifierOptionIds.map((optionId) => ({ optionId, parentOptionId: null })),
          ...nestedModifiers,
        ]
          .map(({ optionId, parentOptionId }) => {
            const resolved = modifierOptionsById.get(optionId);
            return resolved
              ? {
                  optionId,
                  groupName: resolved.groupName,
                  label: resolved.label,
                  amountMinor: resolved.amountMinor,
                  ...(parentOptionId ? { parentOptionId } : {}),
                }
              : null;
          })
          .filter((selection): selection is CartResponseModifierSelection => selection !== null);
        // Row 2.1b: the line's own codes, resolved to the product's offered
        // presets for display -- a code the product no longer offers (the
        // catalogue changed since the line was added) still round-trips on
        // the wire, but is dropped from the label list rather than shown as
        // a raw code, the same rule `modifierOptionsById` above already
        // applies to a modifier option that vanished from the menu.
        const presetsByCode = new Map(known.commentPresets.map((preset) => [preset.code, preset]));
        const commentPresets: CartResponseCommentPresetSelection[] = line.commentPresetCodes
          .map((code) => presetsByCode.get(code))
          .filter((preset): preset is PublishedCommentPreset => preset !== undefined);
        // ADR 0136: a combo line's price is what one combo costs, the sum of what its picks cost
        // inside it; the container has no price of its own. A pick the menu no longer carries has
        // no price to add, and an unpriced one makes the combo unpriced (shown as such) rather
        // than cheaper.
        const comboPicks = line.comboPicks ?? [];
        const comboComponents: CartResponseComboComponent[] = comboPicks.map((pick) => {
          const component = comboComponentsById.get(pick.componentId);
          return {
            componentId: pick.componentId,
            name: component?.name ?? '',
            variantName: component?.variantName ?? null,
            quantity: pick.quantity * (component?.defaultQuantity ?? 1),
            amountMinor: component?.amountMinor ?? null,
          };
        });
        const comboPrice = comboComponents.reduce(
          (sum, component) => sum + (component.amountMinor ?? 0) * component.quantity,
          0,
        );
        const projected: CartResponseItem = {
          variant_id: line.variantId,
          // The line key, which is what an update or a removal addresses. The
          // legacy field held a server-side item id and is reused rather than
          // renamed, because every template binds to it.
          item_id: line.lineKey,
          name: known.name,
          image: known.image ?? FALLBACK_IMAGE,
          price: comboPicks.length > 0 ? comboPrice : known.price,
          physical: known.physical,
          active: true,
          quantity: line.quantity,
          // Write-only on the platform; only its existence is reported.
          note: null,
          modifierOptionIds,
          modifiers,
          commentPresetCodes: line.commentPresetCodes,
          commentPresets,
          comboPicks,
          comboComponents,
          nestedModifiers,
        };
        return projected;
      })
      .filter((item): item is CartResponseItem => item !== null);

    const zero = { price: 0, discount: 0 };
    this.cartData.set({
      items,
      items_count: items.reduce((sum, item) => sum + Math.ceil(item.quantity), 0),
      subtotal: { price: this.priced()?.subtotalMinor ?? 0, discount: 0 },
      total: { price: this.priced()?.totalMinor ?? 0, discount: 0 },
      delivery: zero,
      packaging: zero,
      vendor: {
        id: '',
        name: '',
        phone: '',
        active: true,
        pre_order: false,
        start: '',
        finish: '',
      },
      address: null,
      delivery_time: null,
      delivery_distance: 0,
      delivery_date_display: null,
      delivery_time_display: null,
      promo_code: cart.appliedPromoCode ?? null,
      delivery_duration: 0,
    });
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

/** Thousandths, the scale a quantity is stored at, so `0.2 + 0.1` is `0.3` and not `0.30000000000000004`. */
function tidy(value: number): number {
  return Math.round(value * 1000) / 1000;
}
