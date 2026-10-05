import { Injectable, computed, inject, signal } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { CustomerApi } from '../core/api/customer-api';
import { HorecaOSApiError, messageKeyFor, reasonMessageKey } from '../core/api/problem-details';
import type {
  CartResponse,
  CartResponseComboComponent,
  CartResponseItem,
  CartResponseModifierSelection,
} from '../types/cart.types';
import {
  CartService,
  optionIdsOfLine,
  type CheckoutResult,
  type FulfillmentMode,
  type PlatformCart,
  type PricedCart,
} from './cart.service';
import { MenuService, type PublishedModifierGroup } from './menu.service';
import {
  discountLines as discountLinesOf,
  noteLines as noteLinesOf,
  promoOutcomeKey as promoOutcomeKeyOf,
} from './applied-promotions';
import { LangService } from './lang.service';
import { DeliverySelectionService } from './delivery-selection.service';
import { TranslateService } from './translate.service';
import type { ComboPickWire } from '../utils/combo-selection';
import type { NestedModifierWire } from '../utils/modifier-selection';
import { variantAvailability } from '../utils/item-availability';
import { lineAmountMinor, portionStep, type PhysicalFacts } from '../utils/physical';

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
  /**
   * The translation key of the last cart failure, or null. Set from the platform's
   * own answer -- a refusal's business `reason` first, then its ADR 0031 code
   * (see {@link failureKey}) -- so a screen can name the specific problem and a
   * caller that only has a boolean can ask "why" afterwards.
   */
  readonly errorKey = signal<string | null>(null);

  /** {@link errorKey}, in the customer's language; recomputed when the language changes. */
  readonly error = computed<string | null>(() => {
    this.translate.current();
    const key = this.errorKey();
    return key ? this.translate.get(key) : null;
  });
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
   * The platform's own total, or a dash while the cart holds no price -- never
   * a zero. A basket with lines and a total of 0 reads as free, so a cart the
   * platform refused to price (a dish gone out of its sale window, a variant
   * off the menu, a dropped connection) shows "unknown" and, beside it,
   * {@link priceRefusalKey} says why.
   */
  readonly totalAmount = computed(() => {
    this.translate.current();
    const total = this.priced()?.totalMinor;
    return total != null ? this.formatPrice(total) : UNRESOLVED;
  });

  /** The platform's own subtotal, or a dash when the cart holds no price (see {@link totalAmount}). */
  readonly subtotalFormatted = computed(() => {
    this.translate.current();
    const subtotal = this.priced()?.subtotalMinor;
    return subtotal != null ? this.formatPrice(subtotal) : UNRESOLVED;
  });

  /**
   * The translation key of why the basket could not be priced, or null when it
   * was priced (or has nothing to price).
   *
   * Deliberately not {@link errorKey}: a basket the platform will not price is
   * still a basket the customer must see, and the screens read a non-null
   * `errorKey` after `load()` as "the basket could not be read at all". The
   * reason comes from the refusal itself, so a line that has left its sale
   * window reads as that and not as "something went wrong".
   */
  readonly priceRefusalKey = signal<string | null>(null);

  readonly totalWithDelivery = computed(() => this.totalAmount());

  /** Option id to what the customer reads on it (its name, else its code), read off the menu as the basket was last projected. */
  private readonly optionLabels = signal<ReadonlyMap<string, string>>(new Map());

  /**
   * ADR 0136: what the server added to this order by itself -- a delivery box the customer never
   * chose -- itemised, one row per option with the amount summed over the lines it was applied to.
   *
   * Already inside the lines and the total above, never on top of them: the disclosure the record
   * asks the storefront to carry, so the total can be read against what was chosen. Empty for a cart
   * of another fulfilment mode. The wording around it is product and legal's to settle (the
   * record's open input), so the screen states only what is true: what it is called, what it
   * costs, and that it is already counted.
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
   * The code applied to the cart right now, ADR 0072, or null.
   *
   * Read from the last {@link project}ed cart rather than from what {@link
   * applyPromoCode} was last called with: the server is the only party that
   * knows whether a code actually stuck, and a cart rebuilt for a mode switch
   * (see {@link switchFulfillmentMode}) carries no code forward at all.
   */
  readonly appliedPromoCode = computed(() => this.cartData()?.promo_code ?? null);

  /** Busy flag for the promo field alone, so applying a code does not grey out the whole basket. */
  readonly promoBusy = signal(false);

  /** The last promo refusal, as a customer-facing message -- never a raw code. */
  readonly promoError = signal<string | null>(null);

  /**
   * What the applied code discounted, straight from the last price
   * (`PricedCart.discountMinor`) and never computed here. Zero -- shown as no
   * discount at all -- when the cart has not been priced yet, or the applied
   * code is not eligible right now (ADR 0072: a code can be presented and
   * still discount nothing, e.g. once its capacity runs out between apply and
   * price).
   */
  readonly discountMinor = computed(() => this.priced()?.discountMinor ?? 0);
  readonly hasDiscount = computed(() => this.discountMinor() > 0);
  readonly discountFormatted = computed(() => {
    this.translate.current();
    return this.formatPrice(this.discountMinor());
  });

  /**
   * The discounts behind {@link discountMinor}, one line per kind, each with the
   * platform's own amount (ADR 0140). Their sum is the discount. A discount the
   * platform reports without saying where it came from (an answer that predates
   * the breakdown) is one line, labelled by whether the customer has a code on the
   * cart, so the total is never left with an unexplained gap.
   */
  readonly discountRows = computed<readonly PromotionRow[]>(() => {
    this.translate.current();
    const code = this.appliedPromoCode();
    const lines = discountLinesOf(this.priced()?.appliedPromotions);
    if (lines.length === 0) {
      return this.hasDiscount()
        ? [
            {
              labelKey: code ? 'cart.promoCode' : 'cart.offerDiscount',
              code,
              amount: this.discountFormatted(),
            },
          ]
        : [];
    }
    return lines.map((line) => ({
      labelKey: line.labelKey,
      code: line.source === 'PROMO_CODE' ? code : null,
      amount: this.formatPrice(line.amountMinor),
    }));
  });

  /**
   * Benefits already inside the delivery price or the goods (a delivery offer, a
   * surcharge), as captions with the platform's amount. Not added to the sum.
   */
  readonly promotionNotes = computed<readonly PromotionNote[]>(() => {
    this.translate.current();
    return noteLinesOf(this.priced()?.appliedPromotions).map((line) => ({
      labelKey: line.labelKey,
      amount: this.formatPrice(line.amountMinor),
    }));
  });

  /**
   * The sentence for a code on the cart that did not move the price, or null: no
   * code, or one that applied. Read from the platform's verdict, never guessed from
   * the total (ADR 0140: a code never combines with an automatic offer, so a smaller
   * code can lose to one and the customer is owed the reason).
   */
  readonly promoOutcomeKey = computed(() =>
    this.appliedPromoCode() ? promoOutcomeKeyOf(this.priced()?.promoCodeOutcome) : null,
  );

  /**
   * A preview of what delivery will cost, from `POST .../delivery-fee`
   * (`DeliveryFeeController.quote`) -- unauthenticated, like the menu, and
   * priced against a point rather than against the cart, so it is available
   * before the cart's own destination is ever set.
   *
   * Two states, and neither is a zero:
   * - no price to show -- not a delivery cart, no destination chosen yet, or
   *   the platform said no (outside every zone, no tariff, past the tariff's
   *   reach, ...): a dash, because a zero here would read as free delivery.
   *   *Why* there is no price is {@link deliveryUnresolvedMessage}, read
   *   separately so a template can show it as an explanation beside the line
   *   -- the platform's own reason, never re-homed to one generic "delivery
   *   unavailable";
   * - resolved: the fee itself.
   */
  readonly deliveryFee = computed(() => {
    this.translate.current();
    if (this.fulfillmentMode() !== 'DELIVERY') {
      return UNRESOLVED;
    }
    const quote = this.deliveryFeeQuote();
    if (!quote || !quote.available) {
      return UNRESOLVED;
    }
    return this.formatPrice(quote.feeMinor ?? 0);
  });

  /**
   * Why the delivery fee is not a price, in the customer's language, or `null`
   * when there is nothing to explain -- not a delivery cart, no preview to read
   * (no destination chosen, an address with no marker, or the read failed), or
   * the fee is resolved.
   *
   * Read from the preview's `outcome` (`DeliveryFeeOutcome`: `OUT_OF_ZONE`,
   * `OUTSIDE_CATCHMENT`, `NO_TARIFF`, `BEYOND_MAX_DISTANCE`,
   * `LOCATION_NOT_LOCATED`), which is the stable code the controller documents
   * as the one a storefront branches on. Its `reasonCode` is the resolver's
   * granular evidence string (`NO_ZONE_COVERS_ADDRESS`, `NO_TARIFF_CONFIGURED`,
   * ...) and is not in the vocabulary a checkout refusal uses -- mapping it
   * would read every real refusal as "we couldn't work out the fee".
   *
   * The outcome goes through the same map a checkout refusal uses, so the
   * sentence beside the delivery line and the one under the order button agree.
   * The preview never reports a below-minimum basket: the resolver does not
   * compare the basket against the zone's floor, the pricing engine does, at
   * checkout, and a refusal there arrives as `DELIVERY_MINIMUM_BASKET_NOT_MET`.
   * An outcome this build has no sentence for reads as the honest "we couldn't
   * work out the fee", never as the raw code.
   */
  readonly deliveryUnresolvedMessage = computed<string | null>(() => {
    this.translate.current();
    if (this.fulfillmentMode() !== 'DELIVERY') {
      return null;
    }
    const quote = this.deliveryFeeQuote();
    if (!quote || quote.available) {
      return null;
    }
    return this.translate.get(
      reasonMessageKey(quote.outcome) ?? 'errors.reason.deliveryFeeUnresolved',
    );
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
        modifierOptionIds: optionIdsOfLine(line),
        // ADR 0136: and a combo's picks, or its container would be put back with nothing chosen.
        ...(line.comboPicks && line.comboPicks.length > 0 ? { comboPicks: line.comboPicks } : {}),
        // ... and the second-level answers, or the options that asked for them would be refused.
        ...(line.nestedModifiers && line.nestedModifiers.length > 0
          ? { nestedModifiers: line.nestedModifiers }
          : {}),
      })) ?? [];

    this.fulfillmentMode.set(mode);
    if (!existing) {
      return;
    }

    this.updating.set(true);
    this.errorKey.set(null);
    try {
      const location = this.locationId();
      this.carts.discard(location);
      await this.carts.create(location, mode);
      for (const line of carried) {
        await this.carts.putLine({
          variantId: line.variantId,
          quantity: line.quantity,
          modifierOptionIds: line.modifierOptionIds,
          ...('comboPicks' in line ? { comboPicks: line.comboPicks } : {}),
          ...('nestedModifiers' in line ? { nestedModifiers: line.nestedModifiers } : {}),
        });
      }
      await this.project(this.carts.cart());
    } catch (failure) {
      this.fail(failure);
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
    this.errorKey.set(null);
    try {
      const cart = await this.carts.ensure(this.locationId(), this.fulfillmentMode(), false);
      await this.project(cart);
    } catch (failure) {
      this.fail(failure);
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
   * @param comboPicks ADR 0136: what the customer picked inside a combo, set exactly when
   *        `variantId` is a combo's container. Part of the line's identity, like the modifiers: the
   *        same combo with other picks is another line.
   * @param nestedModifiers ADR 0136: the second-level answers, each under the first-level option
   *        that opened it. Part of the line's identity as well.
   * @returns whether the platform took the line. On false, {@link errorKey}
   *          names why -- a sale-window or sold-out refusal, an expired basket,
   *          a dropped connection -- so the caller can say so instead of
   *          carrying on as if the dish were in the basket.
   */
  async add(
    variantId: string,
    quantity = 1,
    note?: string,
    modifierOptionIds?: readonly string[],
    comboPicks?: readonly ComboPickWire[],
    nestedModifiers?: readonly NestedModifierWire[],
  ): Promise<boolean> {
    this.updating.set(true);
    this.errorKey.set(null);
    try {
      await this.carts.ensure(this.locationId(), this.fulfillmentMode(), true);
      const cart = await this.carts.putLine({
        variantId,
        quantity,
        customerNote: note,
        modifierOptionIds,
        comboPicks,
        nestedModifiers,
      });
      await this.project(cart);
      return true;
    } catch (failure) {
      this.fail(failure);
      return false;
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
    this.errorKey.set(null);
    try {
      const cart =
        quantity <= 0
          ? await this.carts.removeLine(item.item_id)
          : await this.carts.putLine({
              variantId: item.variant_id,
              quantity,
              modifierOptionIds: item.modifierOptionIds,
              // ADR 0136: resent whole, or a quantity change would strip a combo's picks
              // and the second-level answers under the options.
              comboPicks: item.comboPicks,
              nestedModifiers: item.nestedModifiers,
            });
      await this.project(cart);
    } catch (failure) {
      this.fail(failure);
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
    this.errorKey.set(null);
    try {
      const cart = await this.carts.clear();
      await this.project(cart);
    } catch (failure) {
      this.fail(failure);
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
      this.priceRefusalKey.set(null);
      return priced;
    } catch (failure) {
      this.fail(failure);
      return null;
    }
  }

  /**
   * Applies a promo code, ADR 0072.
   *
   * Never subtracts anything itself: it writes the code to the cart, then
   * re-prices through {@link project} so `discountMinor`/`totalAmount` are the
   * platform's own answer. A refusal is translated to a customer-facing
   * message keyed off `problem.reason` (an unknown or currently-unusable
   * code), never shown as the raw ADR 0031 code.
   *
   * @returns false on refusal, without touching the cart's existing state --
   *          a customer who typed a bad code keeps whatever was priced before.
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

  /** ADR 0072's refusal reasons, each named as `problem.reason` by the platform. */
  private promoErrorMessage(failure: unknown): string {
    if (failure instanceof HorecaOSApiError) {
      const reason = failure.problem?.reason;
      const key = reason ? PROMO_REASON_KEYS[reason] : undefined;
      if (key) {
        return this.translate.get(key);
      }
    }
    return this.translate.get(failureKey(failure));
  }

  /** Records a failure as the specific sentence the customer should read. */
  private fail(failure: unknown): void {
    this.errorKey.set(failureKey(failure));
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
    this.priceRefusalKey.set(null);
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
      this.priceRefusalKey.set(null);
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
    // basket. The total then reads as unknown (a dash, see `totalAmount`) and
    // the refusal's own reason is kept in `priceRefusalKey` for the screens.
    this.priceRefusalKey.set(null);
    try {
      this.priced.set(await this.carts.price());
    } catch (failure) {
      this.priced.set(null);
      this.priceRefusalKey.set(failureKey(failure));
    }

    const menu = await this.menu.menu(this.lang.langId(), cart.locationId);
    const byVariant = new Map<
      string,
      {
        name: string;
        image: string | null;
        price: number;
        orderable: boolean;
        onSaleNow: boolean;
        physical: PhysicalFacts | null;
      }
    >();
    for (const product of menu.products) {
      for (const variant of product.variants) {
        byVariant.set(variant.variantId, {
          name: product.name,
          image: product.imageUrls[0] ?? null,
          price: variant.amountMinor ?? 0,
          orderable: variant.orderable,
          // See MenuService.toMenuItem: absent means on sale.
          onSaleNow: variant.onSaleNow !== false,
          physical: variant.physical ?? null,
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
        // Row 4.2g / rows 4.4c-d: the menu was just read, so this is the
        // platform's current word on whether this line can still be sold.
        // A line the customer added earlier can have gone out of its window or
        // sold out since; it stays visible, marked, rather than vanishing.
        const availability = variantAvailability({
          active: known.orderable,
          onSaleNow: known.onSaleNow,
        });
        // ADR 0136: a combo line's price is what one combo costs, the sum of what its picks cost
        // inside it; the container has no price of its own. An unpriced pick has no price to add.
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
          active: availability === 'AVAILABLE',
          ...(availability === 'AVAILABLE' ? {} : { unavailableReason: availability }),
          quantity: line.quantity,
          // Write-only on the platform; only its existence is reported.
          note: null,
          modifierOptionIds,
          modifiers,
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
      // POST since 2026-09-21 (audit follow-up (b)): the point travels in the
      // body, not the query string (ADR 0029) -- `anonymous: true` because
      // this preview still has no principal, same as before.
      const view = await this.api.mutate<DeliveryFeeView>(
        'POST',
        `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}` +
          `/locations/${cart.locationId}/delivery-fee`,
        {
          body: {
            lat: address.latitude,
            lon: address.longitude,
            currency: cart.currency,
            subtotalMinor: this.priced()?.subtotalMinor ?? 0,
          },
          anonymous: true,
        },
      );
      this.deliveryFeeQuote.set({
        available: view.available,
        feeMinor: view.feeMinor,
        outcome: view.outcome,
      });
    } catch {
      this.deliveryFeeQuote.set(null);
    }
  }

  /** Public: also used by screens that render a line total or a discount amount. */
  formatPrice(value: number): string {
    const currency = this.translate.get('common.currency') || "so'm";
    // Minor units, and for UZS that is whole som -- nothing divides by a hundred.
    return `${value.toLocaleString('uz-UZ')} ${currency}`;
  }
}

/**
 * One discount line of the money block: the translation key of its label, the
 * customer's own code when the line is theirs to remove, and the amount as the
 * platform reported it, already formatted.
 */
export interface PromotionRow {
  readonly labelKey: string;
  readonly code: string | null;
  readonly amount: string;
}

/** A benefit already inside the delivery price or the goods, to be read as a caption. */
export interface PromotionNote {
  readonly labelKey: string;
  readonly amount: string;
}

/**
 * What a screen needs from `DeliveryFeeController.DeliveryFeeView`: whether and
 * how much, and -- when not -- why. The why is the `outcome` (`OUT_OF_ZONE`,
 * `NO_TARIFF`, ...), a machine code that is only ever mapped to a sentence,
 * never shown (see {@link UiCartService.deliveryUnresolvedMessage}). The view's
 * own `reasonCode` is deliberately not carried: it is the resolver's granular
 * evidence string and no sentence is keyed on it.
 */
export interface DeliveryFeeQuote {
  readonly available: boolean;
  readonly feeMinor: number | null;
  readonly outcome: string;
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

/**
 * The translation key for a cart failure.
 *
 * A platform answer resolves through {@link messageKeyFor} -- its business
 * `reason` first (`ITEM_OUT_OF_SALE_WINDOW`, `SOLD_OUT`, `CART_EXPIRED`, ...),
 * then its ADR 0031 code -- so the customer reads what actually went wrong.
 * Anything that is not a platform answer (a thrown `Error`, a programming
 * slip) is the one generic sentence: there is nothing more honest to say.
 */
function failureKey(failure: unknown): string {
  return failure instanceof HorecaOSApiError ? messageKeyFor(failure) : 'errors.generic';
}

/**
 * ADR 0072's `PromoCodeEligibilityService.Eligibility.Reason` names, exactly as
 * `StorefrontOrderingController.refusal` puts them on `problem.reason`, mapped
 * to a customer-facing key. An unlisted reason (there should not be one) falls
 * back to {@link messageKeyFor}'s generic reading of the ADR 0031 code.
 */
const PROMO_REASON_KEYS: Readonly<Record<string, string>> = {
  CODE_NOT_FOUND: 'checkout.promoNotFound',
  CODE_NOT_ACTIVE: 'checkout.promoNotActive',
  CODE_NOT_YET_ACTIVE: 'checkout.promoNotYetActive',
  CODE_EXPIRED: 'checkout.promoExpired',
  REDEMPTION_LIMIT_REACHED: 'checkout.promoLimitReached',
  PER_CUSTOMER_LIMIT_REACHED: 'checkout.promoAlreadyUsed',
};

/** Thousandths, the scale a quantity is stored at, so `0.2 + 0.1` is `0.3` and not `0.30000000000000004`. */
function tidy(value: number): number {
  return Math.round(value * 1000) / 1000;
}
