import {
  ChangeDetectionStrategy,
  Component,
  type OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { newIdempotencyKey } from '../../../core/api/idempotency';
import { HorecaOSApiError, messageKeyFor } from '../../../core/api/problem-details';
import { ReturnDestination } from '../../../core/auth/return-destination';
import { Session } from '../../../core/auth/session';
import { formatMoney, money } from '../../../core/money/money';
import type { PricedCart } from '../../../services/cart.service';
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
import { MenuGridComponent } from '../../../shared/menu-grid/menu-grid.component';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import type { CategoryItem, MenuCategory } from '../../../types/home.types';

/** U+2014. Shown where the platform has not priced the basket, so a zero is never read as free. */
const UNRESOLVED = '—';

/** The path a sign-in from this screen returns to: token-free, and the only one `ReturnDestination` allows. */
const THIS_SCREEN = '/dine-in/table';

/** The payment codes this deployment has a label for; any other shows its own code. */
const PAYMENT_LABEL_KEYS: Readonly<Record<string, string>> = {
  CASH: 'cart.cash',
  CLICK: 'cart.click',
  PAYME: 'cart.payme',
};

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
 * Opening a session is `TableSessionController.open`, capability-gated to an
 * operator at `LOCATION` scope; there is no guest-facing path to it. Creating a
 * real table occupancy from an unauthenticated scan is a product decision about
 * self-seating (ADR 0143, Proposed) that this screen does not make. Until a host
 * seats the table, `admission.openSessionId` is null, and this renders the menu
 * with an explanation rather than a basket with nothing to bind to.
 *
 * <h2>What is not offered</h2>
 *
 * A dish whose modifier group must be chosen from (`DishCardComponent`) says a
 * member of staff will help; choosing needs the product page's picker, which adds
 * to the delivery basket.
 */
@Component({
  selector: 'app-dine-in-table',
  standalone: true,
  imports: [MenuGridComponent, RouterLink, TranslatePipe],
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

  protected readonly admission = computed(() => this.dineIn.admission());
  /** Only `ORDER_AND_PAY` orders; `VIEW_ONLY` -- and any mode this build does not know -- is a menu. */
  protected readonly canOrder = computed(() => this.admission()?.mode === 'ORDER_AND_PAY');
  protected readonly isSeated = computed(() => !!this.admission()?.openSessionId);
  /** The table takes orders and has a session to put them on. */
  protected readonly ordering = computed(() => this.canOrder() && this.isSeated());

  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly categories = signal<readonly MenuCategory[]>([]);
  protected readonly sections = signal<readonly CategoryItem[]>([]);
  protected readonly currency = this.menuService.currency;

  protected readonly hasDishes = computed(() =>
    this.sections().some((section) => section.items.length > 0),
  );

  protected readonly bill = signal<DineInBill | null>(null);
  protected readonly billBusy = signal(false);
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

  protected readonly cartCount = computed(
    () => this.carts.cart()?.lines.reduce((sum, line) => sum + line.quantity, 0) ?? 0,
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
   */
  private async writeBasket(write: () => Promise<void>): Promise<void> {
    this.updating.set(true);
    this.basketErrorKey.set(null);
    this.checkoutErrorKey.set(null);
    this.orderPlaced.set(false);
    try {
      await write();
      await this.reprice();
    } catch (failure) {
      this.basketErrorKey.set(failureKey(failure));
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
    }
  }

  protected selectPayment(code: string): void {
    this.selectedPayment.set(code);
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
      const result = await this.carts.checkout({
        priced,
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

  protected async refreshBill(sessionId: string | null | undefined = this.admission()?.openSessionId): Promise<void> {
    if (!sessionId) {
      return;
    }
    this.billBusy.set(true);
    try {
      // An order that never made it onto the bill goes first: its attach answers
      // with the bill, and a plain read would show the table without it.
      // Attaching needs the signed-in session the order was placed under (see
      // DineInService.attachRound), so a signed-out device leaves the queue alone
      // rather than spend a request on a certain 401; signing in brings the guest
      // back to this screen, which tries again.
      const canAttach = this.session.isAuthenticated() && this.dineIn.pendingRoundCount(sessionId) > 0;
      const flush = canAttach ? await this.attachPendingRounds(sessionId) : null;
      if (!flush?.bill) {
        this.bill.set(await this.dineIn.bill(sessionId));
      }
    } catch (failure) {
      if (this.dineIn.isGuestSessionEnded(failure)) {
        this.dineIn.clear();
      }
    } finally {
      this.billBusy.set(false);
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
    try {
      this.bill.set(await this.dineIn.requestBill(sessionId));
    } catch (failure) {
      if (this.dineIn.isGuestSessionEnded(failure)) {
        this.dineIn.clear();
      }
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

  protected paymentLabelKey(code: string): string | null {
    return PAYMENT_LABEL_KEYS[code] ?? null;
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

/** A platform answer resolves to its own sentence; anything else is the one generic one. */
function failureKey(failure: unknown): string {
  return failure instanceof HorecaOSApiError ? messageKeyFor(failure) : 'errors.generic';
}
