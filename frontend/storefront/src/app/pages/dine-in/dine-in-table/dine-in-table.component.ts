import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';

import { ReturnDestination } from '../../../core/auth/return-destination';
import { Session } from '../../../core/auth/session';
import { newIdempotencyKey } from '../../../core/api/idempotency';
import { HorecaOSApiError, isNotFound } from '../../../core/api/problem-details';
import { CartService, type PlatformCart, type PricedCart } from '../../../services/cart.service';
import {
  type DineInAdmission,
  DineInBill,
  DineInService,
  type RoundFlush,
} from '../../../services/dine-in.service';
import { LangService } from '../../../services/lang.service';
import { LocationProfileService } from '../../../services/location-profile.service';
import {
  MenuService,
  type PublishedMenu,
  type PublishedProduct,
  comboGroupsOfProduct,
} from '../../../services/menu.service';
import { NotificationService } from '../../../services/notification.service';
import { TranslateService } from '../../../services/translate.service';
import { ComboChoicesComponent } from '../../../shared/combo-choices/combo-choices.component';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import type { MenuItemComboGroup } from '../../../types/home.types';
import {
  type ComboPickWire,
  type ComboPicks,
  canBeSatisfied,
  comboValid,
  picksOnTheWire,
  samePicks,
} from '../../../utils/combo-selection';

/** How often the claim's countdown moves; a minute is the finest thing it says, so this is plenty. */
const CLAIM_CLOCK_MS = 15_000;

/**
 * The longest the page waits between two reads of a claim whose window has passed. The
 * platform decides a claim in a sweep that runs every 30 s, so one read at the expiry
 * usually lands before it; the reads that follow double their gap (15 s, 30 s, 60 s) and
 * then hold at a minute, which also covers a claim kept open by a payment still in flight.
 */
const CLAIM_REREAD_MAX_MS = 60_000;

/** The most a guest can say they are (the platform's own ceiling for a party). */
const MAX_PARTY = 200;
import { PhysicalFactsComponent } from '../../../shared/physical-facts/physical-facts.component';
import {
  formatQuantity,
  formatWeight,
  initialQuantity,
  portionStep,
  type PhysicalFacts,
} from '../../../utils/physical';

interface MenuRow {
  readonly categoryId: string;
  readonly categoryName: string;
  readonly products: readonly PublishedProduct[];
}

/**
 * The table screen: `/dine-in/table`, resumed from the guest token
 * `DineInScanComponent` just minted (ADR 0047, row `10.5`'s dine-in facet).
 *
 * <h2>The three things a mode decides</h2>
 *
 * `VIEW_ONLY` renders the menu and nothing else -- no add-to-cart control is
 * even drawn, matching `QrEntryController`'s own refusal of every ordering
 * endpoint to a `VIEW_ONLY` token. `ORDER_AND_PAY` adds a cart bound to the
 * table's own `DINE_IN` fulfilment mode and location, and a checkout that
 * attaches the resulting order to the table's session
 * (`DineInService.attachRound`) so it shows up on the running bill -- and
 * keeps the order queued on the device until the platform has confirmed that
 * attach, since nothing else binds a guest's order to its table.
 * `SETTLE_OPEN_TICKET` is declared by the platform and never selectable
 * (`QrMode`'s own doc) -- this screen never receives it and does not brace
 * for it.
 *
 * <h2>Why an `ORDER_AND_PAY` table can still have nothing to order onto</h2>
 *
 * A session is what an order is put on, and until one exists
 * `admission.openSessionId` is null: this renders the menu with ordering disabled,
 * rather than a broken cart with nothing to bind to. Two things end that.
 *
 * - **A member of staff seats the table** (`TableSessionController.open`), the path
 *   that was always there and stays: a guest with no phone, a branch that has not
 *   turned the next thing on.
 * - **The guest sits down themselves** (ADR 0143), when the platform said at the scan
 *   that it could (`admission.walkInAvailable`): the screen offers "Sit at this table"
 *   with a party-size stepper, needs the guest signed in first, and opens a *claim* --
 *   a provisional session. The claim is the guest's for a short window and becomes an
 *   ordinary session once an order the restaurant accepts is on it; if nothing follows
 *   it lapses and the table goes back to the room. Until then there is nothing to bill,
 *   so "ask for the bill" is not offered. The platform decides again when the guest
 *   asks and answers every "no" with one sentence, so this screen never explains *why*
 *   a table cannot be taken -- it says to ask a member of staff.
 */
@Component({
  selector: 'app-dine-in-table',
  standalone: true,
  imports: [CommonModule, RouterLink, ComboChoicesComponent, PhysicalFactsComponent, TranslatePipe],
  templateUrl: './dine-in-table.component.html',
  styleUrl: './dine-in-table.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DineInTableComponent implements OnInit {
  protected readonly dineIn = inject(DineInService);
  protected readonly session = inject(Session);
  protected readonly carts = inject(CartService);
  private readonly menuService = inject(MenuService);
  private readonly lang = inject(LangService);
  private readonly locations = inject(LocationProfileService);
  private readonly notification = inject(NotificationService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);
  private readonly returnDestination = inject(ReturnDestination);

  readonly loading = signal(true);
  readonly branchName = signal<string | null>(null);
  readonly menu = signal<PublishedMenu | null>(null);
  readonly bill = signal<DineInBill | null>(null);

  readonly priced = signal<PricedCart | null>(null);
  readonly paymentOptions = signal<readonly string[]>([]);
  readonly selectedPaymentCode = signal<string | null>(null);
  readonly checkingOut = signal(false);
  readonly checkoutError = signal<string | null>(null);
  readonly billBusy = signal(false);
  /** An order the platform refused to put on the bill for good -- the guest is told to ask staff. */
  readonly roundLost = signal(false);

  private readonly destroyRef = inject(DestroyRef);

  readonly admission = computed(() => this.dineIn.admission());

  readonly canOrder = computed(() => this.admission()?.mode === 'ORDER_AND_PAY');
  readonly isSeated = computed(() => !!this.admission()?.openSessionId);

  // --------------------------------------------- sitting down (ADR 0143)

  /** The platform said, at the scan, that this table could be taken from here. */
  readonly walkInAvailable = computed(() => this.admission()?.walkInAvailable === true);
  /** The invitation to sit: an ordering table nobody sits at, which the platform would let this guest take. */
  readonly canSitHere = computed(
    () => this.canOrder() && !this.isSeated() && this.walkInAvailable(),
  );
  readonly partySize = signal(2);
  readonly seating = signal(false);
  /** Why the guest could not sit down, as a translation key; null when nothing went wrong. */
  readonly seatErrorKey = signal<string | null>(null);
  /** The seat the platform handed back was somebody else's: orders go on their bill. */
  readonly joinedExisting = signal(false);
  /** Ticks, so the claim's countdown follows the clock (a `computed` alone never would). */
  private readonly now = signal(Date.now());
  /** The guest's claim has not been confirmed: nothing the restaurant accepted is on it yet. */
  readonly claimUnconfirmed = computed(() => {
    const bill = this.bill();
    return !!bill && bill.confirmed === false;
  });
  /** Whole minutes until an unconfirmed claim gives the table back; null when there is no claim to lose. */
  readonly claimMinutesLeft = computed(() => {
    const bill = this.bill();
    if (!bill || bill.confirmed !== false || !bill.claimExpiresAt) {
      return null;
    }
    return Math.max(0, Math.ceil((Date.parse(bill.claimExpiresAt) - this.now()) / 60_000));
  });
  private claimClock: ReturnType<typeof setInterval> | null = null;
  /** The expiry of the claim being re-read, so a new claim starts the count again. */
  private claimReadFor: string | null = null;
  private claimReads = 0;
  private claimReadAt = 0;
  /**
   * The table whose hold the platform ended, for the screen that says so once the visit is
   * cleared; null while nothing has lapsed. In memory only: a reload shows the plain prompt.
   */
  readonly lapsedTable = signal<string | null>(null);

  /** Orders placed from this device that the table's bill has not confirmed yet. */
  readonly pendingRounds = computed(() => {
    const sessionId = this.admission()?.openSessionId;
    return sessionId ? this.dineIn.pendingRoundCount(sessionId) : 0;
  });

  private pendingCheckoutKey: string | null = null;
  private pricedCartId: string | null = null;
  private boundCartId: string | null = null;
  private bindInFlight: Promise<void> | null = null;

  constructor() {
    // Reprices whenever the cart's own version moves (a line added, changed
    // or removed) -- the same "price follows the cart" rule
    // `UiCartService.project` keeps, kept here as an effect instead because
    // this screen has no single `project()` call site the way that
    // service's line-mutation methods all funnel through.
    effect(() => {
      const cart = this.carts.cart();
      if (!cart || cart.lines.length === 0) {
        this.priced.set(null);
        this.paymentOptions.set([]);
        this.pricedCartId = null;
        return;
      }
      if (this.pricedCartId === `${cart.cartId}:${cart.version}`) {
        return;
      }
      this.pricedCartId = `${cart.cartId}:${cart.version}`;
      void this.repriceAndLoadPaymentMethods();
    });
  }

  ngOnInit(): void {
    this.claimClock = setInterval(() => this.tick(), CLAIM_CLOCK_MS);
    this.destroyRef.onDestroy(() => {
      if (this.claimClock !== null) {
        clearInterval(this.claimClock);
      }
    });

    const admission = this.admission();
    if (!admission) {
      this.loading.set(false);
      return;
    }

    this.locations
      .profile(admission.locationId)
      .then((profile) => this.branchName.set(profile?.displayName ?? null))
      .catch(() => this.branchName.set(null));

    const menuLoad = this.menuService
      .menu(this.lang.langId(), admission.locationId, admission.channelCode ?? undefined)
      .then((menu) => this.menu.set(menu))
      .catch(() => this.menu.set(null));

    const billLoad =
      admission.mode === 'ORDER_AND_PAY' && admission.openSessionId
        ? this.refreshBill(admission.openSessionId)
        : Promise.resolve();

    const cartLoad =
      admission.mode === 'ORDER_AND_PAY' &&
      admission.openSessionId &&
      this.session.isAuthenticated()
        ? this.carts
            .ensure(admission.locationId, 'DINE_IN', false, admission.channelCode ?? undefined)
            .catch(() => null)
        : Promise.resolve(null);

    // Not part of `loading`: the menu is usable while the basket is being re-pointed
    // at this table, and `bindCartToTable` is single-flight, so a dish tapped in the
    // meantime waits for this rather than racing it.
    void cartLoad.then((cart) => this.rebindExistingCart(admission, cart));

    Promise.all([menuLoad, billLoad, cartLoad]).finally(() => this.loading.set(false));
  }

  /**
   * Moves the countdown on, and once a claim's window has passed keeps reading the bill
   * until the platform has decided: it may have given the table back, and a screen still
   * offering a table that is no longer the guest's would let them order onto a bill that
   * is gone -- or hide "ask for the bill" from a session that has become an ordinary one.
   *
   * One read is not enough. The platform decides a claim in a sweep (every 30 s), not at
   * the instant the window ends, so the first read usually still sees an undecided claim
   * whose expiry is behind it. A read that does decide changes the bill (confirmed) or ends
   * the visit (see {@link refreshBill}), which stops this on its own; until then it asks
   * again, a little less often each time.
   */
  private tick(): void {
    const now = Date.now();
    this.now.set(now);
    const bill = this.bill();
    if (
      bill?.confirmed !== false ||
      !bill.claimExpiresAt ||
      Date.parse(bill.claimExpiresAt) > now ||
      this.billBusy()
    ) {
      return;
    }
    if (this.claimReadFor !== bill.claimExpiresAt) {
      this.claimReadFor = bill.claimExpiresAt;
      this.claimReads = 0;
    }
    const gap = Math.min(
      CLAIM_CLOCK_MS * 2 ** Math.max(0, this.claimReads - 1),
      CLAIM_REREAD_MAX_MS,
    );
    if (this.claimReads > 0 && now - this.claimReadAt < gap) {
      return;
    }
    this.claimReads++;
    this.claimReadAt = now;
    void this.refreshBill();
  }

  stepParty(by: number): void {
    this.partySize.set(Math.min(MAX_PARTY, Math.max(1, this.partySize() + by)));
  }

  /**
   * Sits the guest at the table (ADR 0143). Signed-out guests are sent to sign in first and
   * come back here; the platform needs the customer's own session beside the table's token.
   */
  async sitDown(): Promise<void> {
    if (!this.canSitHere() || this.seating()) {
      return;
    }
    if (!this.session.isAuthenticated()) {
      this.signIn();
      return;
    }
    this.seating.set(true);
    this.seatErrorKey.set(null);
    try {
      const seating = await this.dineIn.seat(this.partySize());
      this.joinedExisting.set(!seating.created);
      this.claimReadFor = null;
      this.lapsedTable.set(null);
      this.bill.set(seating);
      this.now.set(Date.now());
    } catch (failure) {
      this.onSeatFailure(failure);
    } finally {
      this.seating.set(false);
    }
  }

  private onSeatFailure(failure: unknown): void {
    if (!(failure instanceof HorecaOSApiError)) {
      this.seatErrorKey.set('errors.generic');
      return;
    }
    if (failure.status === 401) {
      // Two different 401s: the customer's own session lapsed (sign in again and come
      // back) and the table's guest token is dead (scan the code again). Only the second
      // ends the visit.
      if (
        failure.problem?.reason === 'CUSTOMER_SESSION_REQUIRED' ||
        !this.session.isAuthenticated()
      ) {
        this.signIn();
      } else {
        this.dineIn.clear();
      }
      return;
    }
    if (failure.status === 409 && failure.problem?.conflict === 'TABLE_NOT_AVAILABLE') {
      // One answer for every reason (off, held, a cap, a refused account): ask staff.
      this.dineIn.markWalkInUnavailable();
      this.seatErrorKey.set('dineIn.tableNotAvailable');
      return;
    }
    if (failure.status === 400 && typeof failure.problem?.seats === 'number') {
      this.seatErrorKey.set('dineIn.tooManyForTable');
      this.tooManySeats.set(failure.problem.seats);
      return;
    }
    if (failure.status === 429) {
      this.seatErrorKey.set('dineIn.seatRateLimited');
      return;
    }
    this.seatErrorKey.set('errors.generic');
  }

  /** The seat count the platform reported for "too many for this table". */
  readonly tooManySeats = signal<number | null>(null);

  readonly rows = computed<readonly MenuRow[]>(() => {
    const menu = this.menu();
    if (!menu) {
      return [];
    }
    const byId = new Map(menu.products.map((product) => [product.productId, product]));
    return menu.categories
      .map((category) => ({
        categoryId: category.categoryId,
        categoryName: category.name,
        products: category.productIds
          .map((id) => byId.get(id))
          .filter((product): product is PublishedProduct => !!product),
      }))
      .filter((row) => row.products.length > 0);
  });

  quantityOf(variantId: string): number {
    return this.carts.cart()?.lines.find((line) => line.variantId === variantId)?.quantity ?? 0;
  }

  // ------------------------------------------------------------ ADR 0136: combos

  /** The combo product whose choices are open, if any. */
  readonly openComboProductId = signal<string | null>(null);
  private readonly comboPickState = signal<Readonly<Record<string, ComboPicks>>>({});

  /** The choices a product's combo asks for; empty when the product is no combo. */
  comboGroupsOf(product: PublishedProduct): readonly MenuItemComboGroup[] {
    const menu = this.menu();
    return menu ? comboGroupsOfProduct(menu, product) : [];
  }

  isCombo(product: PublishedProduct): boolean {
    return (product.comboGroupIds ?? []).length > 0;
  }

  /** A group no orderable component can fill makes the combo unorderable for now. */
  comboUnavailable(product: PublishedProduct): boolean {
    return this.comboGroupsOf(product).some((group) => !canBeSatisfied(group));
  }

  toggleCombo(productId: string): void {
    this.openComboProductId.update((open) => (open === productId ? null : productId));
  }

  comboPicksOf(productId: string): ComboPicks {
    return this.comboPickState()[productId] ?? {};
  }

  setComboPicks(productId: string, picks: ComboPicks): void {
    this.comboPickState.update((state) => ({ ...state, [productId]: picks }));
  }

  comboReady(product: PublishedProduct): boolean {
    return comboValid(this.comboGroupsOf(product), this.comboPicksOf(product.productId));
  }

  /**
   * Adds one of this combo with the picks made: a line of its own, so the same combo with other
   * picks is another line, and the same picks again is one more of it.
   */
  async addCombo(product: PublishedProduct, variantId: string): Promise<void> {
    const groups = this.comboGroupsOf(product);
    const picks = picksOnTheWire(groups, this.comboPicksOf(product.productId));
    if (picks.length === 0 || !comboValid(groups, this.comboPicksOf(product.productId))) {
      return;
    }
    const admission = this.admission();
    if (!admission) {
      return;
    }
    if (!this.session.isAuthenticated()) {
      this.signIn();
      return;
    }
    try {
      await this.carts.ensure(
        admission.locationId,
        'DINE_IN',
        true,
        admission.channelCode ?? undefined,
      );
      await this.bindCartToTable(admission);
      const held = this.carts
        .cart()
        ?.lines.find(
          (line) => line.variantId === variantId && samePicks(line.comboPicks ?? [], picks),
        );
      await this.carts.putLine({
        variantId,
        quantity: (held?.quantity ?? 0) + 1,
        comboPicks: picks,
      });
      this.setComboPicks(product.productId, {});
      this.openComboProductId.set(null);
    } catch (failure) {
      if (this.dineIn.isGuestSessionEnded(failure)) {
        this.endVisit();
        return;
      }
      this.notification.show(this.translate.get('errors.generic'));
    }
  }

  /**
   * The basket as the guest reads it back: each line by name and quantity, and under a combo the
   * components it will become on the order. Named from the menu document this screen already holds.
   */
  readonly basketLines = computed(() => {
    const menu = this.menu();
    const cart = this.carts.cart();
    if (!menu || !cart) {
      return [];
    }
    const productByVariant = new Map<string, PublishedProduct>();
    for (const product of menu.products) {
      for (const variant of product.variants) {
        productByVariant.set(variant.variantId, product);
      }
    }
    const componentById = new Map(
      (menu.comboGroups ?? []).flatMap((group) =>
        group.components.map((component) => [component.componentId, component] as const),
      ),
    );
    return cart.lines.map((line) => ({
      lineKey: line.lineKey,
      name: productByVariant.get(line.variantId)?.name ?? '',
      quantity: line.quantity,
      components: (line.comboPicks ?? []).map((pick: ComboPickWire) => {
        const component = componentById.get(pick.componentId);
        const label = component
          ? component.variantName
            ? `${component.name} ${component.variantName}`
            : component.name
          : '';
        const units = pick.quantity * (component?.defaultQuantity ?? 1);
        return units > 1 ? `${label} ×${units}` : label;
      }),
    }));
  });

  /**
   * What the server added by itself to this basket for a dine-in order -- a charge the guest never
   * chose -- itemised, each already inside the total. Named from the menu's modifier options.
   */
  readonly hiddenCharges = computed(() => {
    const menu = this.menu();
    const names = new Map(
      (menu?.modifierGroups ?? []).flatMap((group) =>
        group.options.map((option) => [option.optionId, option.name || option.code || ''] as const),
      ),
    );
    const byOption = new Map<string, number>();
    for (const charge of this.priced()?.hiddenCharges ?? []) {
      byOption.set(charge.optionId, (byOption.get(charge.optionId) ?? 0) + charge.amountMinor);
    }
    return [...byOption.entries()].map(([optionId, amountMinor]) => ({
      optionId,
      label: names.get(optionId) || this.translate.get('cart.hiddenCharge.fallbackLabel'),
      amount: this.formatPrice(amountMinor),
    }));
  });

  /** ADR 0137: what a variant physically is, from the published menu this table is ordering from. */
  physicalOf(variantId: string): PhysicalFacts | null {
    for (const product of this.menu()?.products ?? []) {
      const variant = product.variants.find((candidate) => candidate.variantId === variantId);
      if (variant) {
        return variant.physical ?? null;
      }
    }
    return null;
  }

  /** `0,5`, `2` — the quantity as the guest's language writes it. */
  quantityText(variantId: string): string {
    return formatQuantity(this.quantityOf(variantId), this.lang.langId());
  }

  /**
   * A variant's price as the menu says it: per quantum for a variant sold by weight ("15 000 so'm
   * per 100 g" — the price row is not what one item costs), otherwise the plain price.
   */
  priceLabel(variantId: string, amountMinor: number | null): string {
    const physical = this.physicalOf(variantId);
    if (physical?.catchweight && physical.catchweightQuantumGrams && amountMinor != null) {
      return this.translate.getWithParams('physical.pricePerQuantum', {
        price: this.formatPrice(amountMinor),
        quantum: formatWeight(physical.catchweightQuantumGrams, this.lang.langId()),
      });
    }
    return this.formatPrice(amountMinor);
  }

  /**
   * The basket badge counts plates, not fractions: a half portion is one plate somebody has to
   * make, so each line counts its quantity rounded up (ADR 0137).
   */
  readonly cartCount = computed(
    () => this.carts.cart()?.lines.reduce((sum, line) => sum + Math.ceil(line.quantity), 0) ?? 0,
  );

  formatPrice(amountMinor: number | null): string {
    if (amountMinor == null) {
      return '—';
    }
    const currency = this.translate.get('common.currency') || "so'm";
    return `${amountMinor.toLocaleString('uz-UZ')} ${currency}`;
  }

  get totalFormatted(): string {
    const total = this.priced()?.totalMinor;
    return total != null ? this.formatPrice(total) : this.formatPrice(0);
  }

  /** One portion more — or, for a first tap, one whole portion (or the first quantity the cart accepts for a portion size that does not divide one). */
  async increase(variantId: string): Promise<void> {
    const current = this.quantityOf(variantId);
    const step = portionStep(this.physicalOf(variantId));
    await this.setQuantity(variantId, current === 0 ? initialQuantity(step) : tidy(current + step));
  }

  async decrease(variantId: string): Promise<void> {
    await this.setQuantity(
      variantId,
      tidy(this.quantityOf(variantId) - portionStep(this.physicalOf(variantId))),
    );
  }

  private async setQuantity(variantId: string, quantity: number): Promise<void> {
    const admission = this.admission();
    if (!admission) {
      return;
    }
    if (!this.session.isAuthenticated()) {
      this.signIn();
      return;
    }
    try {
      await this.carts.ensure(
        admission.locationId,
        'DINE_IN',
        true,
        admission.channelCode ?? undefined,
      );
      await this.bindCartToTable(admission);
      const cart = this.carts.cart();
      const lineKey = cart?.lines.find((line) => line.variantId === variantId)?.lineKey;
      if (quantity <= 0 && lineKey) {
        await this.carts.removeLine(lineKey);
      } else if (quantity > 0) {
        await this.carts.putLine({ variantId, quantity });
      }
    } catch (failure) {
      if (this.dineIn.isGuestSessionEnded(failure)) {
        this.endVisit();
        return;
      }
      this.notification.show(this.translate.get('errors.generic'));
    }
  }

  /**
   * Binds a cart the guest already had to the table they have just scanned.
   *
   * The binding lives on the cart, and the cart outlives the visit: a guest who
   * was moved to another table, or who scanned again after their party changed,
   * reloads a basket bound to the table they left. Rebinding is only ever done
   * from `setQuantity`, which a guest who does not touch a line never reaches, so
   * without this an unedited basket is placed against the old table. Checkout now
   * refuses that (`TABLE_BINDING_STALE`), but the guest should not be the one to
   * find out. A basket with no lines has nothing to place; its first line binds it.
   */
  private async rebindExistingCart(
    admission: DineInAdmission,
    cart: PlatformCart | null,
  ): Promise<void> {
    if (!cart || cart.lines.length === 0) {
      return;
    }
    try {
      await this.bindCartToTable(admission);
    } catch (failure) {
      if (this.dineIn.isGuestSessionEnded(failure)) {
        this.endVisit();
      }
    }
  }

  /**
   * Binds the cart to this table once per cart, before its first line.
   *
   * Best-effort on purpose. A bound cart makes the platform put the order on the
   * table's bill inside checkout; an unbound one still works, through the queued
   * attach after checkout (`DineInService.queueRound`), which stays exactly as it
   * was. So a failed bind costs the guest nothing they had before, and is not worth
   * blocking an add-to-cart on -- except a guest token the platform no longer
   * recognises, which nothing on this screen works without.
   */
  private bindCartToTable(admission: DineInAdmission): Promise<void> {
    if (this.bindInFlight) {
      return this.bindInFlight;
    }
    const cart = this.carts.cart();
    if (!cart || this.boundCartId === cart.cartId) {
      return Promise.resolve();
    }
    const inFlight = this.bindNow(admission, cart.cartId).finally(() => {
      this.bindInFlight = null;
    });
    this.bindInFlight = inFlight;
    return inFlight;
  }

  private async bindNow(admission: DineInAdmission, cartId: string): Promise<void> {
    try {
      await this.carts.bindTable(admission.guestToken);
      this.boundCartId = cartId;
    } catch (failure) {
      if (this.dineIn.isGuestSessionEnded(failure)) {
        throw failure;
      }
    }
  }

  private async repriceAndLoadPaymentMethods(): Promise<void> {
    try {
      const priced = await this.carts.price();
      this.priced.set(priced);
      const methods = await this.carts.paymentMethods();
      const codes = methods?.methodCodes ?? [];
      this.paymentOptions.set(codes);
      if (!codes.includes(this.selectedPaymentCode() ?? '')) {
        this.selectedPaymentCode.set(codes[0] ?? null);
      }
    } catch {
      this.priced.set(null);
      this.paymentOptions.set([]);
    }
  }

  selectPayment(code: string): void {
    this.selectedPaymentCode.set(code);
  }

  /**
   * Checks the basket out and attaches the resulting order to the table's
   * bill in the same gesture -- from the guest's point of view, "order".
   * See this class's own doc for why {@link DineInService.attachRound} is a
   * second call rather than something checkout does by itself.
   */
  async checkout(): Promise<void> {
    const admission = this.admission();
    const sessionId = admission?.openSessionId;
    const priced = this.priced();
    if (!admission || !sessionId || !priced || this.checkingOut()) {
      return;
    }
    const paymentMethodCode = this.selectedPaymentCode();
    if (!paymentMethodCode) {
      this.checkoutError.set(this.translate.get('cart.noPaymentMethodSelected'));
      return;
    }
    this.checkingOut.set(true);
    this.checkoutError.set(null);
    this.roundLost.set(false);
    try {
      const result = await this.carts.checkout({
        priced,
        paymentMethodCode,
        idempotencyKey: this.checkoutKey(),
        // The binding is remembered state; this is what proves the guest is still
        // at the table it names.
        guestToken: admission.guestToken,
      });
      if (result.outcome === 'REJECTED') {
        this.checkoutError.set(this.translate.get('cart.orderRejected'));
        this.pendingCheckoutKey = null;
        return;
      }
      this.pendingCheckoutKey = null;
      this.carts.discard(admission.locationId);
      this.priced.set(null);

      // The order id is the only thing tying this order to the table: the
      // kitchen ticket's table chip, the order board and the bill all read
      // the row attaching it writes. Queue it on the device before the call,
      // so a lost response or a reload cannot lose it (see DineInService).
      this.dineIn.queueRound(sessionId, result.orderId);
      const flush = await this.attachPendingRounds(sessionId);
      if (flush.pending === 0 && flush.abandoned === 0) {
        this.notification.show(this.translate.get('dineIn.orderPlaced'));
      }
    } catch (failure) {
      if (this.dineIn.isGuestSessionEnded(failure)) {
        this.endVisit();
        return;
      }
      // A bound cart is refused, before anything is written, when the party has
      // left or a host closed the table while the guest was choosing.
      const reason = failure instanceof HorecaOSApiError ? failure.problem?.reason : undefined;
      const tableEmpty = reason === 'TABLE_NOT_SEATED';
      if (!(failure instanceof HorecaOSApiError) || failure.code !== 'NETWORK_UNREACHABLE') {
        this.pendingCheckoutKey = null;
      }
      if (reason === 'TABLE_TOKEN_ENDED' || reason === 'TABLE_TOKEN_REQUIRED') {
        // The same cue as a guest token the platform stopped recognising: the
        // party this device scanned for is over, so scan the code again.
        this.endVisit();
        return;
      }
      if (reason === 'TABLE_BINDING_STALE') {
        // The cart still says the table the guest left. Bind it to this one (which
        // reprices it) and ask them to look before ordering again.
        this.boundCartId = null;
        try {
          await this.bindCartToTable(admission);
        } catch (rebind) {
          if (this.dineIn.isGuestSessionEnded(rebind)) {
            this.endVisit();
            return;
          }
        }
        this.checkoutError.set(this.translate.get('dineIn.tableChanged'));
        return;
      }
      this.checkoutError.set(
        tableEmpty
          ? this.translate.get('dineIn.notSeated')
          : failure instanceof HorecaOSApiError
            ? this.translate.get('cart.orderError')
            : this.translate.get('errors.generic'),
      );
    } finally {
      this.checkingOut.set(false);
    }
  }

  /**
   * Puts every order this device placed at the table onto the table's bill
   * and reports what is left. Says so when something is still not on it;
   * says nothing on success, because the bill changing is the answer.
   *
   * The order exists and is in the kitchen whatever happens here. What is at
   * stake is the bill and the table chip, so a round that could not be
   * confirmed stays queued -- the notice below the bill offers a retry and
   * the next visit to this screen tries again -- and a round the platform
   * refused for good is said out loud rather than dropped silently.
   */
  private async attachPendingRounds(sessionId: string): Promise<RoundFlush> {
    const flush = await this.dineIn.flushPendingRounds(sessionId);
    if (flush.bill) {
      this.bill.set(flush.bill);
    }
    if (flush.abandoned > 0) {
      this.roundLost.set(true);
      this.notification.show(this.translate.get('dineIn.roundAttachFailed'));
    } else if (flush.pending > 0) {
      this.notification.show(this.translate.get('dineIn.roundAttachRetry'));
    }
    return flush;
  }

  /**
   * The table's guest token is dead: the visit is over, so forget it.
   *
   * When the guest was holding a claim nothing had confirmed, that is the lapse. Giving
   * the table back closes its session, and closing a session revokes every guest token
   * minted at its table, so the claimant's next call is refused as a dead token (401) --
   * never as a missing bill. Nothing here can renew the token (the printed code was spent
   * by the scan and is not kept), so the visit ends; the guest is told the hold is gone
   * and to scan again, rather than shown the bare prompt to scan a table they were
   * sitting at a moment ago.
   */
  private endVisit(): void {
    if (this.claimUnconfirmed()) {
      this.lapsedTable.set(this.admission()?.tableCode ?? '');
      this.bill.set(null);
    }
    this.dineIn.clear();
  }

  async refreshBill(sessionId?: string): Promise<void> {
    const id = sessionId ?? this.admission()?.openSessionId;
    if (!id) {
      return;
    }
    this.billBusy.set(true);
    try {
      // An order that never made it onto the bill goes first: its attach
      // answers with the bill, and a plain read would show the table
      // without it.
      // Attaching needs the signed-in session the order was placed under (see
      // DineInService.attachRound), so a signed-out device leaves the queue
      // alone rather than spend a request on a certain 401; signing in brings
      // the guest back to this screen, which tries again.
      const canAttach = this.session.isAuthenticated() && this.dineIn.pendingRoundCount(id) > 0;
      const flush = canAttach ? await this.attachPendingRounds(id) : null;
      if (!flush?.bill) {
        this.bill.set(await this.dineIn.bill(id));
      }
    } catch (failure) {
      if (this.dineIn.isGuestSessionEnded(failure)) {
        this.endVisit();
      } else if (isNotFound(failure) && this.claimUnconfirmed()) {
        // The token is still live yet the table's live session is not this claim: the
        // table is free again. Offer to sit down; the platform re-decides when the guest asks.
        this.bill.set(null);
        this.dineIn.sessionEnded();
      }
    } finally {
      this.billBusy.set(false);
    }
  }

  /** The notice's button: renew the sign-in the attach needs, or try the attach again. */
  async retryPendingRounds(): Promise<void> {
    if (!this.session.isAuthenticated()) {
      this.signIn();
      return;
    }
    await this.refreshBill();
  }

  async requestBill(): Promise<void> {
    const id = this.admission()?.openSessionId;
    if (!id) {
      return;
    }
    this.billBusy.set(true);
    try {
      this.bill.set(await this.dineIn.requestBill(id));
      this.notification.show(this.translate.get('dineIn.billRequested'));
    } catch (failure) {
      if (this.dineIn.isGuestSessionEnded(failure)) {
        this.endVisit();
      }
    } finally {
      this.billBusy.set(false);
    }
  }

  /**
   * Sends the guest to sign in and remembers to bring them back to this table
   * afterwards, rather than to `/locations` like every other sign-in.
   *
   * What is remembered is the token-free path `/dine-in/table`, never the
   * printed table token: that token was spent by the scan and is not held
   * anywhere, so the auth flow never sees it. The table itself is recovered
   * from the guest token `DineInService` persisted, which is unaffected by
   * signing in.
   */
  signIn(): void {
    this.returnDestination.remember('/dine-in/table');
    void this.router.navigate(['/auth', 'login']);
  }

  private checkoutKey(): string {
    this.pendingCheckoutKey ??= newIdempotencyKey();
    return this.pendingCheckoutKey;
  }
}

/** Thousandths, the scale a quantity is stored at, so `0.2 + 0.1` is `0.3` and not `0.30000000000000004`. */
function tidy(value: number): number {
  return Math.round(value * 1000) / 1000;
}
