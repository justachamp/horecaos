import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  type OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { newIdempotencyKey } from '../../../core/api/idempotency';
import { HorecaOSApiError, isNotFound, messageKeyFor } from '../../../core/api/problem-details';
import { ReturnDestination } from '../../../core/auth/return-destination';
import { Session } from '../../../core/auth/session';
import { formatMoney, money } from '../../../core/money/money';
import { lineKeyFor, optionIdsOfLine, type PricedCart } from '../../../services/cart.service';
import { DineInCartService } from '../../../services/dine-in-cart.service';
import {
  DineInService,
  type DineInAdmission,
  type DineInBill,
  type RoundFlush,
} from '../../../services/dine-in.service';
import { LangService } from '../../../services/lang.service';
import { MenuService } from '../../../services/menu.service';
import { PaymentSessionService } from '../../../services/payment-session.service';
import { TranslateService } from '../../../services/translate.service';
import {
  ChosenLinesComponent,
  type ChosenLine,
} from '../../../shared/chosen-lines/chosen-lines.component';
import { MenuGridComponent } from '../../../shared/menu-grid/menu-grid.component';
import {
  ModifierPickerComponent,
  type ModifierSelection,
} from '../../../shared/modifier-picker/modifier-picker.component';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import type {
  CategoryItem,
  MenuCategory,
  MenuItem,
  MenuItemVariant,
} from '../../../types/home.types';
import { variantAvailability } from '../../../utils/item-availability';
import { type ComboPicks, comboValid } from '../../../utils/combo-selection';
import {
  groupsForVariant,
  unsatisfiedGroupsFor,
  unsatisfiedNestedFor,
} from '../../../utils/modifier-selection';
import { portionStep } from '../../../utils/physical';
import { DineInOrderBarComponent } from './order-bar/dine-in-order-bar.component';

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

/** U+2014. Shown where the platform has not priced the basket, so a zero is never read as free. */
const UNRESOLVED = '—';

/** The path a sign-in from this screen returns to: token-free, and the only one `ReturnDestination` allows. */
const THIS_SCREEN = '/dine-in/table';

/**
 * A quote is good for about fifteen minutes. One that ends within this margin is
 * repriced rather than sent: a request in flight when the deadline passes is
 * answered `QUOTE_EXPIRED`, and the guest would have nothing to act on.
 */
const QUOTE_MARGIN_MS = 5_000;

/**
 * The checkout refusals that mean "the price you are holding is no longer the
 * platform's": the quote lapsed or was cleared, the price moved, or the basket
 * changed under it. All are cured by pricing again, none by pressing Order again.
 */
const STALE_QUOTE_REASONS: ReadonlySet<string> = new Set([
  'QUOTE_EXPIRED',
  'QUOTE_NOT_FOUND',
  'PRICE_CHANGED',
  'CART_VERSION_STALE',
]);

/**
 * The table screen: `/dine-in/table`, resumed from the admission
 * `DineInScanComponent` just minted (ADR 0047).
 *
 * <h2>What a mode decides</h2>
 *
 * **`VIEW_ONLY`** shows the table's menu -- read at the table's own location, on
 * its own `QR_TABLE` channel, every dish with its sold-out and sale-window state
 * -- and nothing else: no add control is drawn, no basket is opened, no bill is
 * read, and no ordering call is made, matching the platform's own refusal of
 * every one of them to a menu-only token. A dish is not a link (the product page
 * adds to the *delivery* basket).
 *
 * **`ORDER_AND_PAY`** at a seated table adds a basket bound to the table's own
 * `DINE_IN` fulfilment mode, location and channel (`DineInCartService`,
 * remembered against the table's session), a checkout, the running bill, and the
 * ask-for-the-bill action. Checkout is the ordinary one, placed by the guest's own
 * signed-in customer session -- there is no anonymous cart.
 *
 * The basket is *bound to the table* before its first line
 * ({@link DineInService.bindCartToTable}, `PUT .../carts/{id}/table`). A bound
 * basket is put on the table's bill by checkout itself, in the transaction that
 * creates the order, so a lost response or a reload cannot leave a cooking order on
 * no bill, and checkout refuses it before writing anything when nobody is seated
 * any more (`TABLE_NOT_SEATED`). The order id is nevertheless queued on the device
 * and attached with {@link DineInService.attachRound} straight after: a safety net
 * for a basket that was never bound (opened by an earlier build, or a bind that
 * could not be made after a reload), and harmless for a bound one -- the platform
 * answers the attach of an order already on the bill with the bill unchanged. When
 * the attach cannot be confirmed the screen says so and keeps the order queued,
 * rather than telling the guest all is well.
 *
 * <h2>Why an `ORDER_AND_PAY` table can still have nothing to order onto</h2>
 *
 * A session is what an order is put on, and until one exists
 * `admission.openSessionId` is null: this renders the menu with ordering
 * disabled, rather than a basket with nothing to bind to. Two things end that.
 *
 * - **A member of staff seats the table** (`TableSessionController.open`), the
 *   path that was always there and stays: a guest with no phone, a branch that has
 *   not turned the next thing on.
 * - **The guest sits down themselves** (ADR 0143), when the platform said at the
 *   scan that it could (`admission.walkInAvailable`): the screen offers "Sit at this
 *   table" with a party-size stepper, needs the guest signed in first, and opens a
 *   *claim* -- a provisional session. The claim is the guest's for a short window
 *   and becomes an ordinary session once an order the restaurant accepts is on it;
 *   if nothing follows it lapses and the table goes back to the room. Until then
 *   there is nothing to bill, so "ask for the bill" is not offered. The platform
 *   decides again when the guest asks and answers every "no" with one sentence, so
 *   this screen never explains *why* a table cannot be taken -- it says to ask a
 *   member of staff.
 *
 * <h2>Dishes with options to choose</h2>
 *
 * A dish whose modifier group must be chosen from (`DishCardComponent`) carries a
 * Choose button instead of Add. It opens the option picker
 * (`ModifierPickerComponent`) for that portion, which holds back a selection short
 * of any group's minimum and says which group is missing; a confirmed selection is
 * written to the table's basket as a line with its options (`CartService.putLine`
 * keys it by variant and selection, so "Osh, extra meat" and plain "Osh" are two
 * lines). Those lines have no card stepper -- a card counts only its plain
 * portion -- so they are listed above the menu (`ChosenLinesComponent`), where they
 * can be raised, lowered or taken out. A platform refusal (`MODIFIER_*`, a dish
 * that just sold out) is said inside the picker, which stays open.
 *
 * Only a mandatory group that offers fewer options than it demands (a menu
 * published with an empty group) still says a member of staff will help.
 */
@Component({
  selector: 'app-dine-in-table',
  standalone: true,
  imports: [
    ChosenLinesComponent,
    DineInOrderBarComponent,
    MenuGridComponent,
    ModifierPickerComponent,
    RouterLink,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dine-in-table.component.html',
  styleUrl: './dine-in-table.component.scss',
})
export class DineInTableComponent implements OnInit {
  private readonly dineIn = inject(DineInService);
  private readonly carts = inject(DineInCartService);
  protected readonly session = inject(Session);
  private readonly menuService = inject(MenuService);
  private readonly lang = inject(LangService);
  private readonly paymentSession = inject(PaymentSessionService);
  private readonly translate = inject(TranslateService);
  private readonly router = inject(Router);
  private readonly returnDestination = inject(ReturnDestination);

  private readonly destroyRef = inject(DestroyRef);

  protected readonly admission = computed(() => this.dineIn.admission());
  /** Only `ORDER_AND_PAY` orders; `VIEW_ONLY` -- and any mode this build does not know -- is a menu. */
  protected readonly canOrder = computed(() => this.admission()?.mode === 'ORDER_AND_PAY');
  protected readonly isSeated = computed(() => !!this.admission()?.openSessionId);
  /**
   * The platform answered that the session this screen was opened with is not the
   * table's live one any more (closed, and perhaps the table seated afresh). A
   * round put on it would be refused, so nothing is offered to put on it.
   */
  protected readonly sessionEnded = signal(false);
  /** The table takes orders and has a session to put them on. */
  protected readonly ordering = computed(
    () => this.canOrder() && this.isSeated() && !this.sessionEnded(),
  );

  // --------------------------------------------- sitting down (ADR 0143)

  /** The platform said, at the scan, that this table could be taken from here. */
  protected readonly walkInAvailable = computed(() => this.admission()?.walkInAvailable === true);
  /** The invitation to sit: an ordering table nobody sits at, which the platform would let this guest take. */
  protected readonly canSitHere = computed(
    () => this.canOrder() && !this.isSeated() && this.walkInAvailable(),
  );
  protected readonly partySize = signal(2);
  protected readonly seating = signal(false);
  /** Why the guest could not sit down, as a translation key; null when nothing went wrong. */
  protected readonly seatErrorKey = signal<string | null>(null);
  /** The seat the platform handed back was somebody else's: orders go on their bill. */
  protected readonly joinedExisting = signal(false);
  /** The seat count the platform reported for "too many for this table". */
  protected readonly tooManySeats = signal<number | null>(null);
  /** Ticks, so the claim's countdown follows the clock (a `computed` alone never would). */
  private readonly now = signal(Date.now());
  /** The guest's claim has not been confirmed: nothing the restaurant accepted is on it yet. */
  protected readonly claimUnconfirmed = computed(() => {
    const bill = this.bill();
    return !!bill && bill.confirmed === false;
  });
  /** Whole minutes until an unconfirmed claim gives the table back; null when there is no claim to lose. */
  protected readonly claimMinutesLeft = computed(() => {
    const bill = this.bill();
    if (!bill || bill.confirmed !== false || !bill.claimExpiresAt) {
      return null;
    }
    return Math.max(0, Math.ceil((Date.parse(bill.claimExpiresAt) - this.now()) / 60_000));
  });
  /** The expiry of the claim being re-read, so a new claim starts the count again. */
  private claimReadFor: string | null = null;
  private claimReads = 0;
  private claimReadAt = 0;
  /**
   * The table whose hold the platform ended, for the screen that says so once the visit is
   * cleared; null while nothing has lapsed. In memory only: a reload shows the plain prompt.
   */
  protected readonly lapsedTable = signal<string | null>(null);

  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly categories = signal<readonly MenuCategory[]>([]);
  protected readonly sections = signal<readonly CategoryItem[]>([]);
  protected readonly currency = this.menuService.currency;

  protected readonly hasDishes = computed(() =>
    this.sections().some((section) => section.items.length > 0),
  );

  /** The dish and portion whose options the guest is choosing in the picker, or null when it is closed. */
  protected readonly picking = signal<{ item: MenuItem; variantId: string } | null>(null);

  protected readonly bill = signal<DineInBill | null>(null);
  protected readonly billBusy = signal(false);
  /** Why the bill could not be read or asked for; said on the screen, cleared by the next try. */
  protected readonly billErrorKey = signal<string | null>(null);
  /** An order the platform refused to put on the bill for good -- the guest is told to ask staff. */
  protected readonly roundLost = signal(false);
  /**
   * The last order is in the kitchen *and* on the bill. Said on the screen, not
   * in a toast: this app renders no toasts, so a message sent to
   * `NotificationService` would never be seen by the guest.
   */
  protected readonly orderPlaced = signal(false);

  protected readonly priced = signal<PricedCart | null>(null);
  protected readonly paymentOptions = signal<readonly string[]>([]);
  protected readonly selectedPayment = signal<string | null>(null);
  protected readonly updating = signal(false);
  protected readonly checkingOut = signal(false);
  /** Why the basket could not be changed (a dish that just went out of its window, ...). */
  protected readonly basketErrorKey = signal<string | null>(null);
  /** Why the basket could not be priced; the total reads as unknown while this is set. */
  protected readonly priceRefusalKey = signal<string | null>(null);
  protected readonly checkoutErrorKey = signal<string | null>(null);
  /** The order was placed and the provider's payment page could not be opened. */
  protected readonly paymentErrorKey = signal<string | null>(null);

  /** What the basket holds of each plain portion, by variant id -- the stepper on each dish. */
  protected readonly quantities = computed<Readonly<Record<string, number>>>(() => {
    const held: Record<string, number> = {};
    for (const line of this.carts.cart()?.lines ?? []) {
      // A line with modifiers has a longer key; this screen never writes one.
      if (line.lineKey === line.variantId) {
        held[line.variantId] = (held[line.variantId] ?? 0) + line.quantity;
      }
    }
    return held;
  });

  /** Every portion the menu offers, by variant id, with the dish it belongs to. */
  private readonly portions = computed(() => {
    const index = new Map<string, { item: MenuItem; variant: MenuItemVariant }>();
    for (const section of this.sections()) {
      for (const item of section.items) {
        for (const variant of item.variants) {
          index.set(variant.id, { item, variant });
        }
      }
    }
    return index;
  });

  /**
   * The basket's lines that carry options, shown by name: what the guest chose is
   * read back from the menu the screen already holds, because the platform's line
   * carries the option ids and never their names. A dish the menu has dropped is
   * still listed, so it can be taken out.
   */
  protected readonly chosenLines = computed<readonly ChosenLine[]>(() => {
    this.translate.current();
    const index = this.portions();
    const lines = (this.carts.cart()?.lines ?? []).filter(
      (line) => line.lineKey !== line.variantId,
    );
    return lines.map((line): ChosenLine => {
      const found = index.get(line.variantId);
      if (!found) {
        return {
          lineKey: line.lineKey,
          name: this.translate.get('dineIn.lineGone'),
          portion: null,
          options: [],
          quantity: line.quantity,
          available: false,
        };
      }
      const chosen = new Set(optionIdsOfLine(line));
      // The groups this portion is offered (ADR 0136): its own list when the menu published one.
      const groups = groupsForVariant(found.item, line.variantId);
      const modifierNames = groups
        .flatMap((group) => group.options)
        .filter((option) => chosen.has(option.id))
        .map((option) => option.label || this.translate.get('dineIn.pickerOptionUnnamed'));
      // ... and the answers given under them, named from the groups those options opened.
      const nestedLabels = new Map(
        groups
          .flatMap((group) => group.options)
          .flatMap((option) => option.nestedGroups ?? [])
          .flatMap((group) => group.options)
          .map((option) => [option.id, option.label] as const),
      );
      const nestedNames = (line.nestedModifiers ?? []).map(
        (pair) =>
          nestedLabels.get(pair.optionId) || this.translate.get('dineIn.pickerOptionUnnamed'),
      );
      // ADR 0136: a combo line is read back as the components it will become on the order, named
      // from the menu this screen already holds, with the units a combo puts on the order.
      const componentsById = new Map(
        (found.item.comboGroups ?? []).flatMap((group) =>
          group.components.map((component) => [component.id, component] as const),
        ),
      );
      const comboNames = (line.comboPicks ?? []).map((pick) => {
        const component = componentsById.get(pick.componentId);
        const label = component
          ? component.variantName
            ? `${component.name} ${component.variantName}`
            : component.name
          : this.translate.get('dineIn.pickerOptionUnnamed');
        const units = pick.quantity * (component?.defaultQuantity ?? 1);
        return units > 1 ? `${label} ×${units}` : label;
      });
      const options = [...comboNames, ...modifierNames, ...nestedNames];
      return {
        lineKey: line.lineKey,
        name: found.item.name,
        portion: found.item.variants.length > 1 ? found.variant.name || null : null,
        options,
        quantity: line.quantity,
        // ADR 0137: a splittable portion moves by its portion size.
        step: portionStep(found.variant.physical),
        available: variantAvailability(found.variant) === 'AVAILABLE',
      };
    });
  });

  /**
   * ADR 0136: what the server added to the table's basket by itself -- a charge the guest never
   * chose -- itemised, one row per option, each already inside the total. Named from the menu's
   * options, which include the groups a screen never offers.
   */
  protected readonly hiddenCharges = computed(() => {
    this.translate.current();
    const labels = this.menuService.optionLabels();
    const byOption = new Map<string, number>();
    for (const charge of this.priced()?.hiddenCharges ?? []) {
      byOption.set(charge.optionId, (byOption.get(charge.optionId) ?? 0) + charge.amountMinor);
    }
    return [...byOption.entries()].map(([optionId, amountMinor]) => ({
      optionId,
      label: labels.get(optionId) || this.translate.get('cart.hiddenCharge.fallbackLabel'),
      amount: this.formatMinor(amountMinor, this.priced()?.currency ?? 'UZS'),
    }));
  });

  /** Plates, not fractions: a half portion is one plate somebody has to make (ADR 0137). */
  protected readonly cartCount = computed(
    () => this.carts.cart()?.lines.reduce((sum, line) => sum + Math.ceil(line.quantity), 0) ?? 0,
  );

  /** Orders placed from this device that the table's bill has not confirmed yet. */
  protected readonly pendingRounds = computed(() => {
    const sessionId = this.admission()?.openSessionId;
    return sessionId ? this.dineIn.pendingRoundCount(sessionId) : 0;
  });

  private pendingCheckoutKey: string | null = null;
  /** The basket this screen has bound to the table -- once per basket, before its first line. */
  private boundCartId: string | null = null;

  async ngOnInit(): Promise<void> {
    const clock = setInterval(() => this.tick(), CLAIM_CLOCK_MS);
    this.destroyRef.onDestroy(() => clearInterval(clock));

    const admission = this.admission();
    if (!admission) {
      this.loading.set(false);
      return;
    }
    const sessionId = this.ordering() ? admission.openSessionId : null;

    const work: Promise<unknown>[] = [this.loadMenu(admission)];
    if (sessionId) {
      work.push(this.refreshBill(sessionId));
      if (this.session.isAuthenticated()) {
        work.push(this.loadBasket(admission, sessionId));
      }
    }
    await Promise.all(work);
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
   * the visit (see {@link failBill}), which stops this on its own; until then it asks
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

  protected stepParty(by: number): void {
    this.partySize.set(Math.min(MAX_PARTY, Math.max(1, this.partySize() + by)));
  }

  /**
   * Sits the guest at the table (ADR 0143). A signed-out guest is sent to sign in first
   * and comes back here; the platform needs the customer's own session beside the
   * table's token.
   */
  protected async sitDown(): Promise<void> {
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
      this.sessionEnded.set(false);
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
      // back) and the table's guest token is dead (scan the code again). Only the
      // second ends the visit.
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
      this.tooManySeats.set(failure.problem.seats);
      this.seatErrorKey.set('dineIn.tooManyForTable');
      return;
    }
    if (failure.status === 429) {
      this.seatErrorKey.set('dineIn.seatRateLimited');
      return;
    }
    this.seatErrorKey.set(failureKey(failure));
  }

  private async loadMenu(admission: DineInAdmission): Promise<void> {
    try {
      const menu = await this.menuService.home(
        this.lang.langId(),
        admission.locationId,
        // The table's own channel, when the tenant registered exactly one.
        admission.channelCode ?? undefined,
      );
      this.categories.set(menu.menu.categories);
      this.sections.set(menu.menu.category_items);
    } catch {
      this.failed.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Picks up a basket this session already has. Never creates one: a browse must
   * not mint a cart per visit, and the first add will.
   */
  private async loadBasket(admission: DineInAdmission, sessionId: string): Promise<void> {
    try {
      const cart = await this.carts.ensure(
        admission.locationId,
        'DINE_IN',
        false,
        admission.channelCode ?? undefined,
        sessionId,
      );
      if (cart && cart.lines.length > 0) {
        // A basket found after a reload was bound when it was opened -- or was
        // opened before baskets were bound. Binding again is harmless and clears
        // its quote, so it comes before the price this is about to ask for.
        // Best effort: an unbound basket is still put on the bill by the queued
        // attach after checkout, and the next change to it tries again.
        await this.bindToTable(cart.cartId).catch(() => undefined);
        await this.reprice();
      }
    } catch {
      // A basket that cannot be read now is found again by the first add, which
      // says why if it cannot be opened either.
    }
  }

  /** The guest asked for a portion in a new quantity: the basket follows. */
  protected async changeQuantity(change: { variantId: string; quantity: number }): Promise<void> {
    const admission = this.admission();
    const sessionId = admission?.openSessionId;
    if (!admission || !sessionId || this.updating()) {
      return;
    }
    if (!this.session.isAuthenticated()) {
      this.signIn();
      return;
    }
    await this.writeBasket(async () => {
      await this.carts.ensure(
        admission.locationId,
        'DINE_IN',
        true,
        admission.channelCode ?? undefined,
        sessionId,
      );
      const cartId = this.carts.cart()?.cartId;
      if (cartId) {
        // Strict here, unlike a reload: a basket that cannot be bound gets no line,
        // so what the guest orders is what checkout puts on the table's bill.
        await this.bindToTable(cartId);
      }
      if (change.quantity <= 0) {
        const held = this.carts.cart()?.lines.find((line) => line.lineKey === change.variantId);
        if (held) {
          await this.carts.removeLine(held.lineKey);
        }
      } else {
        await this.carts.putLine({ variantId: change.variantId, quantity: change.quantity });
      }
    });
  }

  /** The guest wants to choose the options for a portion: open the picker on it. */
  protected openPicker(request: { item: MenuItem; variantId: string }): void {
    if (!this.session.isAuthenticated()) {
      this.signIn();
      return;
    }
    this.basketErrorKey.set(null);
    this.picking.set(request);
  }

  protected closePicker(): void {
    this.picking.set(null);
    this.basketErrorKey.set(null);
  }

  /**
   * The guest confirmed a selection in the picker: it becomes one line of the
   * basket, keyed by the portion and its exact options.
   *
   * The picker already holds back a selection short of a group's minimum; the same
   * rule is applied again here, before any request, because this is where the line
   * is written and the platform's refusal is the last line, not the first. Choosing
   * the same options twice raises that line's quantity (a PUT replaces, so the
   * quantity written is what is held plus what was asked for). The picker closes
   * only when the write succeeded -- on a refusal it stays open, showing why.
   */
  protected async addChosen(selection: ModifierSelection): Promise<void> {
    const admission = this.admission();
    const sessionId = admission?.openSessionId;
    const request = this.picking();
    if (!admission || !sessionId || !request || this.updating()) {
      return;
    }
    if (!this.session.isAuthenticated()) {
      this.closePicker();
      this.signIn();
      return;
    }
    // ADR 0136: a combo's own choices are its components, and the container's modifier groups are
    // never asked about; the rule is applied again here before any request, like the modifiers'.
    const comboPicks = selection.comboPicks ?? [];
    const nested = selection.nestedModifiers ?? [];
    const comboGroups = request.item.comboGroups ?? [];
    const groups = groupsForVariant(request.item, selection.variantId);
    const pickRecord: ComboPicks = Object.fromEntries(
      comboPicks.map((pick) => [pick.componentId, pick.quantity]),
    );
    if (
      comboGroups.length > 0
        ? !comboValid(comboGroups, pickRecord)
        : unsatisfiedGroupsFor(groups, selection.modifierOptionIds).length > 0 ||
          unsatisfiedNestedFor(groups, selection.modifierOptionIds, nested).length > 0
    ) {
      this.basketErrorKey.set('dineIn.chooseRequired');
      return;
    }
    const lineKey = lineKeyFor(
      selection.variantId,
      selection.modifierOptionIds,
      comboPicks,
      nested,
    );
    const written = await this.writeBasket(async () => {
      await this.carts.ensure(
        admission.locationId,
        'DINE_IN',
        true,
        admission.channelCode ?? undefined,
        sessionId,
      );
      const cartId = this.carts.cart()?.cartId;
      if (cartId) {
        // Strict, as for a plain add: a basket that cannot be bound gets no line.
        await this.bindToTable(cartId);
      }
      const held = this.carts.cart()?.lines.find((line) => line.lineKey === lineKey);
      await this.carts.putLine({
        variantId: selection.variantId,
        quantity: (held?.quantity ?? 0) + selection.quantity,
        modifierOptionIds: selection.modifierOptionIds,
        ...(comboPicks.length > 0 ? { comboPicks } : {}),
        ...(nested.length > 0 ? { nestedModifiers: nested } : {}),
      });
    });
    if (written) {
      this.picking.set(null);
    }
  }

  /** The guest raised, lowered or removed a dish that was ordered with options. */
  protected async changeChosenLine(change: { lineKey: string; quantity: number }): Promise<void> {
    const held = this.carts.cart()?.lines.find((line) => line.lineKey === change.lineKey);
    if (!held || this.updating()) {
      return;
    }
    if (!this.session.isAuthenticated()) {
      this.signIn();
      return;
    }
    await this.writeBasket(async () => {
      if (change.quantity <= 0) {
        await this.carts.removeLine(held.lineKey);
      } else {
        await this.carts.putLine({
          variantId: held.variantId,
          quantity: change.quantity,
          modifierOptionIds: optionIdsOfLine(held),
          // ADR 0136: resent whole, or a quantity change would strip a combo's picks.
          ...(held.comboPicks && held.comboPicks.length > 0 ? { comboPicks: held.comboPicks } : {}),
          // ... and the second-level answers, or the options that asked for them would be refused.
          ...(held.nestedModifiers && held.nestedModifiers.length > 0
            ? { nestedModifiers: held.nestedModifiers }
            : {}),
        });
      }
    });
  }

  /**
   * Takes every line out of the basket.
   *
   * The way out of a basket the platform will not price. A line the menu no longer
   * sells has a stepper on its card only while the card is on screen and the menu
   * still knows the portion; a portion the menu has dropped altogether has no card
   * at all. The platform refuses to price the whole basket while any one line is
   * unavailable, so without this the guest could be left unable to order for the
   * rest of the evening (the basket is remembered against the table's session).
   * Offered only once pricing has been refused -- never beside a basket that
   * prices, where one stray tap would throw an order away.
   */
  protected async clearOrder(): Promise<void> {
    if (this.updating() || this.checkingOut()) {
      return;
    }
    await this.writeBasket(async () => {
      await this.carts.clear();
    });
  }

  /**
   * Runs one write to the basket, one at a time, and prices what is left.
   * A failure is said on the screen and leaves the basket as the platform holds it.
   * Returns whether the write went through.
   */
  private async writeBasket(write: () => Promise<void>): Promise<boolean> {
    this.updating.set(true);
    this.basketErrorKey.set(null);
    this.checkoutErrorKey.set(null);
    this.orderPlaced.set(false);
    try {
      await write();
      await this.reprice();
      return true;
    } catch (failure) {
      this.basketErrorKey.set(failureKey(failure));
      return false;
    } finally {
      this.updating.set(false);
    }
  }

  /**
   * Binds the basket to this table (`PUT .../carts/{id}/table`), once per basket.
   *
   * A bound basket is put on the table's bill by checkout itself, in the
   * transaction that creates the order: a response lost on the way back, or a
   * page reloaded before the second call, cannot leave a cooking order on no
   * table's bill; and checkout refuses the order before anything is written when
   * nobody is seated any more (`TABLE_NOT_SEATED`) rather than creating an order
   * the attach must then fail to place.
   *
   * The write clears any quote the basket holds, so callers bind before they
   * price. The guest token is not seen here: {@link DineInService.bindCartToTable}
   * hands the cart service the header.
   */
  private async bindToTable(cartId: string): Promise<void> {
    if (this.boundCartId === cartId) {
      return;
    }
    await this.dineIn.bindCartToTable(this.carts);
    this.boundCartId = cartId;
  }

  /**
   * Prices the basket as it now stands and asks what it may be paid with.
   *
   * The total is the platform's own answer, never a sum of the dishes' prices:
   * tax and promotions are applied by the pricing pipeline. A basket it will not
   * price (a dish gone out of its window) reads as unknown, with the reason.
   */
  private async reprice(): Promise<void> {
    const cart = this.carts.cart();
    if (!cart || cart.lines.length === 0) {
      this.priced.set(null);
      this.priceRefusalKey.set(null);
      this.paymentOptions.set([]);
      this.selectedPayment.set(null);
      return;
    }
    this.priceRefusalKey.set(null);
    try {
      this.priced.set(await this.carts.price());
    } catch (failure) {
      this.priced.set(null);
      this.priceRefusalKey.set(failureKey(failure));
    }
    try {
      const codes = (await this.carts.paymentMethods())?.methodCodes ?? [];
      this.paymentOptions.set(codes);
      if (!codes.includes(this.selectedPayment() ?? '')) {
        this.selectedPayment.set(codes[0] ?? null);
      }
    } catch {
      this.paymentOptions.set([]);
      this.selectedPayment.set(null);
      return;
    }
    await this.priceUnderSelectedMethod();
  }

  protected async selectPayment(code: string): Promise<void> {
    this.selectedPayment.set(code);
    await this.priceUnderSelectedMethod();
  }

  /**
   * Puts the chosen method on the cart and prices it again (ADR 0140): "5% off when paying by Click"
   * is in the total the guest sees, and in the quote checkout accepts, only once the platform has
   * been told the method. Nothing is written when the cart already carries it. A refusal is left for
   * {@link checkout}, which writes the method again and says why.
   */
  private async priceUnderSelectedMethod(): Promise<void> {
    const code = this.selectedPayment();
    if (!code || this.carts.cart()?.paymentMethodCode === code) {
      return;
    }
    try {
      await this.carts.selectPaymentMethod(code);
      this.priced.set(await this.carts.price());
    } catch {
      // Retried, and reported, by checkout().
    }
  }

  /**
   * Checks the basket out and puts the resulting order on the table's bill in the
   * same gesture -- from the guest's point of view, "order".
   *
   * Checkout is the customer's own. A basket bound to the table
   * ({@link bindToTable}) is put on the table's bill by checkout itself; the
   * {@link DineInService.attachRound} that follows is the net under a basket that
   * was never bound, and it is a second call that can fail on its own. The order
   * exists and is in the kitchen whatever happens to that call, so the outcome is
   * reported in two parts and the second is never folded into the first: "order
   * sent" is said only when the bill has it too.
   *
   * A customer session that has ended mid-checkout is a matter for the sign-in
   * prompt, not for the table visit: the guest token is not involved in the
   * calls made here, and only a call made with it may end the visit.
   */
  protected async checkout(): Promise<void> {
    const admission = this.admission();
    const sessionId = admission?.openSessionId;
    const priced = this.priced();
    if (!admission || !sessionId || !priced || this.checkingOut()) {
      return;
    }
    const paymentMethodCode = this.selectedPayment();
    if (!paymentMethodCode) {
      this.checkoutErrorKey.set('cart.noPaymentMethodSelected');
      return;
    }
    this.checkingOut.set(true);
    this.checkoutErrorKey.set(null);
    this.paymentErrorKey.set(null);
    this.roundLost.set(false);
    this.orderPlaced.set(false);
    try {
      let quote = priced;
      const methodMissing = this.carts.cart()?.paymentMethodCode !== paymentMethodCode;
      if (methodMissing || this.quoteHasExpired(quote)) {
        // The party sat over the menu past the quote's life, or the method never reached the cart
        // (ADR 0140: checkout refuses a method the quote was not priced under). Price again first: a
        // request the platform is certain to refuse tells the guest nothing.
        if (methodMissing) {
          await this.carts.selectPaymentMethod(paymentMethodCode);
        }
        const fresh = await this.requote();
        if (!fresh) {
          return; // Why it cannot be priced is on the screen (priceRefusalKey).
        }
        if (fresh.totalMinor !== quote.totalMinor || fresh.currency !== quote.currency) {
          // The guest agreed to another total; it is theirs to accept again.
          this.checkoutErrorKey.set('dineIn.priceRefreshed');
          return;
        }
        quote = fresh;
      }
      // The binding is remembered state; the guest token is what proves the guest is
      // still at the table it names, and checkout refuses a bound basket without it.
      const result = await this.dineIn.checkoutAtTable(this.carts, {
        priced: quote,
        paymentMethodCode,
        idempotencyKey: this.checkoutKey(),
      });
      this.pendingCheckoutKey = null;
      if (result.outcome === 'REJECTED') {
        this.checkoutErrorKey.set('cart.orderRejected');
        return;
      }
      this.carts.discard(admission.locationId, sessionId);
      this.priced.set(null);
      this.paymentOptions.set([]);
      this.selectedPayment.set(null);

      // The order id is the only thing tying this order to the table. Queue it on
      // the device before the call, so a lost response or a reload cannot lose it.
      this.dineIn.queueRound(sessionId, result.orderId);
      const flush = await this.attachPendingRounds(sessionId);
      this.orderPlaced.set(flush.pending === 0 && flush.abandoned === 0);

      if (PaymentSessionService.requiresOnlineSession(paymentMethodCode)) {
        await this.openPaymentSession(result.orderId);
      }
    } catch (failure) {
      const reason = failure instanceof HorecaOSApiError ? failure.problem?.reason : undefined;
      if (reason === 'TABLE_TOKEN_ENDED' || reason === 'TABLE_TOKEN_REQUIRED') {
        // The same cue as a guest token the platform stopped recognising: the party this
        // device scanned for is over, so scan the code again.
        this.pendingCheckoutKey = null;
        this.endVisit();
        return;
      }
      if (reason === 'TABLE_BINDING_STALE') {
        // The basket still says the table the guest left: bind it to this one (which
        // reprices it) and ask them to look before ordering again.
        this.pendingCheckoutKey = null;
        this.boundCartId = null;
        try {
          await this.bindToTable(this.carts.cart()?.cartId ?? '');
        } catch {
          // The next change to the basket tries again.
        }
        this.checkoutErrorKey.set('dineIn.tableChanged');
        return;
      }
      if (isStaleQuote(failure)) {
        // The platform no longer honours the price the guest was shown. Price the
        // basket again so the screen holds the platform's number, and say so:
        // pressing Order on the old quote would fail the same way every time.
        const fresh = await this.requote();
        this.checkoutErrorKey.set(fresh ? 'dineIn.priceRefreshed' : null);
        return;
      }
      this.checkoutErrorKey.set(failureKey(failure));
      // A retry after a dropped connection must reuse the key so the platform
      // replays the first attempt; after a real answer it is a new intent.
      if (!(failure instanceof HorecaOSApiError) || failure.code !== 'NETWORK_UNREACHABLE') {
        this.pendingCheckoutKey = null;
      }
    } finally {
      this.checkingOut.set(false);
    }
  }

  private quoteHasExpired(priced: PricedCart): boolean {
    const deadline = Date.parse(priced.expiresAt);
    return Number.isFinite(deadline) && deadline - QUOTE_MARGIN_MS <= Date.now();
  }

  /**
   * Prices the basket again after its quote went stale, and returns the new quote
   * -- or null when the basket cannot be priced now, in which case the screen
   * already says why. A new quote is a new request, so it never rides on the key
   * of the attempt it replaces.
   */
  private async requote(): Promise<PricedCart | null> {
    this.pendingCheckoutKey = null;
    await this.reprice();
    return this.priced();
  }

  /**
   * Puts every order this device placed at the table onto the table's bill and
   * reports what is left, on the screen. Says nothing on success, because the
   * bill changing is the answer.
   *
   * What is at stake is the bill and the table chip, so a round that could not be
   * confirmed stays queued -- the notice above the bill (`pendingRounds`) offers a
   * retry and the next visit to this screen tries again -- and a round the
   * platform refused for good is said out loud (`roundLost`) rather than dropped
   * silently.
   */
  private async attachPendingRounds(sessionId: string): Promise<RoundFlush> {
    const flush = await this.dineIn.flushPendingRounds(sessionId);
    if (flush.bill) {
      this.bill.set(flush.bill);
    }
    if (flush.abandoned > 0) {
      this.roundLost.set(true);
    }
    return flush;
  }

  /**
   * Opens the provider's payment page for an order placed with an online method.
   * Runs after the round is on the bill: leaving the page is the last thing done.
   */
  private async openPaymentSession(orderId: string): Promise<void> {
    try {
      const session = await this.paymentSession.open(orderId);
      if (session.checkoutUrl) {
        this.redirectTo(session.checkoutUrl);
        return;
      }
    } catch {
      // Falls through to the message: the order stands either way.
    }
    this.paymentErrorKey.set('cart.paymentSessionError');
  }

  protected async refreshBill(
    sessionId: string | null | undefined = this.admission()?.openSessionId,
  ): Promise<void> {
    if (!sessionId) {
      return;
    }
    this.billBusy.set(true);
    this.billErrorKey.set(null);
    try {
      // An order that never made it onto the bill goes first: its attach answers
      // with the bill, and a plain read would show the table without it.
      // Attaching needs the signed-in session the order was placed under (see
      // DineInService.attachRound), so a signed-out device leaves the queue alone
      // rather than spend a request on a certain 401; signing in brings the guest
      // back to this screen, which tries again.
      const canAttach =
        this.session.isAuthenticated() && this.dineIn.pendingRoundCount(sessionId) > 0;
      const flush = canAttach ? await this.attachPendingRounds(sessionId) : null;
      if (!flush?.bill) {
        this.bill.set(await this.dineIn.bill(sessionId));
      }
    } catch (failure) {
      this.failBill(failure);
    } finally {
      this.billBusy.set(false);
    }
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

  /**
   * What a failed bill call means for the guest, said on the screen.
   *
   * A guest token the platform no longer recognises ends the visit. A session it
   * no longer knows (404: closed, or the table seated afresh) ends *ordering* --
   * the menu stays -- because a round would be refused. Anything else (no
   * connection, a fault) is a message and a way to try again; the button that was
   * pressed is never left looking as if it did something.
   */
  private failBill(failure: unknown): void {
    if (this.dineIn.isGuestSessionEnded(failure)) {
      this.endVisit();
    } else if (isNotFound(failure) && this.claimUnconfirmed()) {
      // The token is still live yet the table's live session is not this claim: the table
      // is free again. Offer to sit down; the platform re-decides when the guest asks.
      this.bill.set(null);
      this.dineIn.sessionEnded();
    } else if (isNotFound(failure)) {
      this.sessionEnded.set(true);
    } else {
      this.billErrorKey.set(failureKey(failure));
    }
  }

  /** The notice's button: renew the sign-in the attach needs, or try the attach again. */
  protected async retryPendingRounds(): Promise<void> {
    if (!this.session.isAuthenticated()) {
      this.signIn();
      return;
    }
    await this.refreshBill();
  }

  protected async requestBill(): Promise<void> {
    const sessionId = this.admission()?.openSessionId;
    if (!sessionId) {
      return;
    }
    this.billBusy.set(true);
    this.billErrorKey.set(null);
    try {
      this.bill.set(await this.dineIn.requestBill(sessionId));
    } catch (failure) {
      this.failBill(failure);
    } finally {
      this.billBusy.set(false);
    }
  }

  /**
   * Sends the guest to sign in and remembers to bring them back to this table
   * afterwards, rather than to the front door like every other sign-in.
   *
   * What is remembered is the token-free path `/dine-in/table`, never the printed
   * table token (spent by the scan and held nowhere) and never the guest token
   * (which this screen cannot read). The table itself is recovered from the
   * admission `DineInService` persisted, which signing in does not touch.
   */
  protected signIn(): void {
    this.returnDestination.remember(THIS_SCREEN);
    void this.router.navigate(['/auth', 'login']);
  }

  /** The platform's total for the basket, or a dash while it holds no price -- never a zero. */
  protected totalLabel(): string {
    const total = this.priced();
    return total ? this.formatMinor(total.totalMinor, total.currency) : UNRESOLVED;
  }

  protected billTotalLabel(bill: DineInBill): string {
    return this.formatMinor(bill.totalMinor, bill.currency);
  }

  private formatMinor(amountMinor: number, currency: string): string {
    const unit = this.translate.get('common.currency') || "so'm";
    return formatMoney(money(amountMinor, currency), unit);
  }

  private checkoutKey(): string {
    this.pendingCheckoutKey ??= newIdempotencyKey();
    return this.pendingCheckoutKey;
  }

  /**
   * The one line that leaves this application, isolated so a test can replace it:
   * a real assignment sends jsdom off to load another document.
   */
  private redirectTo(url: string): void {
    window.location.href = url;
  }
}

/** True for a checkout refusal that is about the quote the guest holds, not about the order. */
function isStaleQuote(failure: unknown): boolean {
  return (
    failure instanceof HorecaOSApiError &&
    (failure.code === 'PRICE_CHANGED' ||
      failure.code === 'STALE_VERSION' ||
      STALE_QUOTE_REASONS.has(failure.problem?.reason ?? ''))
  );
}

/** A platform answer resolves to its own sentence; anything else is the one generic one. */
function failureKey(failure: unknown): string {
  return failure instanceof HorecaOSApiError ? messageKeyFor(failure) : 'errors.generic';
}
