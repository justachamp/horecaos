import { ChangeDetectionStrategy, Component, OnInit, computed, effect, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink } from '@angular/router';

import { ReturnDestination } from '../../../core/auth/return-destination';
import { Session } from '../../../core/auth/session';
import { newIdempotencyKey } from '../../../core/api/idempotency';
import { HorecaOSApiError } from '../../../core/api/problem-details';
import { CartService, type PricedCart } from '../../../services/cart.service';
import { type DineInAdmission, DineInBill, DineInService, type RoundFlush } from '../../../services/dine-in.service';
import { LangService } from '../../../services/lang.service';
import { LocationProfileService } from '../../../services/location-profile.service';
import { MenuService, type PublishedMenu, type PublishedProduct } from '../../../services/menu.service';
import { NotificationService } from '../../../services/notification.service';
import { TranslateService } from '../../../services/translate.service';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';

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
 * Opening a session is `TableSessionController.open`, and it is capability-
 * gated to an operator at `LOCATION` scope -- there is no guest-facing path
 * to it today, deliberately: creating a real table occupancy from an
 * unauthenticated scan is a product decision about self-seating and its
 * interaction with reservation holds that ADR 0047 does not settle, and this
 * wave does not settle it either (see the wave's own report). Until a host
 * seats the table -- today, only reachable by seating a *confirmed
 * reservation* through the operations reservations page; a pure walk-in has
 * no seating screen anywhere yet -- `admission.openSessionId` is null and
 * this renders the menu with ordering disabled and a plain explanation,
 * rather than a broken cart with nothing to bind to.
 */
@Component({
  selector: 'app-dine-in-table',
  standalone: true,
  imports: [CommonModule, RouterLink, TranslatePipe],
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

  readonly admission = computed(() => this.dineIn.admission());

  readonly canOrder = computed(() => this.admission()?.mode === 'ORDER_AND_PAY');
  readonly isSeated = computed(() => !!this.admission()?.openSessionId);

  /** Orders placed from this device that the table's bill has not confirmed yet. */
  readonly pendingRounds = computed(() => {
    const sessionId = this.admission()?.openSessionId;
    return sessionId ? this.dineIn.pendingRoundCount(sessionId) : 0;
  });

  private pendingCheckoutKey: string | null = null;
  private pricedCartId: string | null = null;
  private boundCartId: string | null = null;

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
      admission.mode === 'ORDER_AND_PAY' && admission.openSessionId && this.session.isAuthenticated()
        ? this.carts
            .ensure(admission.locationId, 'DINE_IN', false, admission.channelCode ?? undefined)
            .catch(() => null)
        : Promise.resolve(null);

    Promise.all([menuLoad, billLoad, cartLoad]).finally(() => this.loading.set(false));
  }

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

  readonly cartCount = computed(
    () => this.carts.cart()?.lines.reduce((sum, line) => sum + line.quantity, 0) ?? 0,
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

  async increase(variantId: string): Promise<void> {
    await this.setQuantity(variantId, this.quantityOf(variantId) + 1);
  }

  async decrease(variantId: string): Promise<void> {
    await this.setQuantity(variantId, this.quantityOf(variantId) - 1);
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
      await this.carts.ensure(admission.locationId, 'DINE_IN', true, admission.channelCode ?? undefined);
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
        this.dineIn.clear();
        return;
      }
      this.notification.show(this.translate.get('errors.generic'));
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
  private async bindCartToTable(admission: DineInAdmission): Promise<void> {
    const cart = this.carts.cart();
    if (!cart || this.boundCartId === cart.cartId) {
      return;
    }
    try {
      await this.carts.bindTable(admission.guestToken);
      this.boundCartId = cart.cartId;
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
        this.dineIn.clear();
        return;
      }
      // A bound cart is refused, before anything is written, when the party has
      // left or a host closed the table while the guest was choosing.
      const tableEmpty = failure instanceof HorecaOSApiError && failure.problem?.reason === 'TABLE_NOT_SEATED';
      this.checkoutError.set(
        tableEmpty
          ? this.translate.get('dineIn.notSeated')
          : failure instanceof HorecaOSApiError
            ? this.translate.get('cart.orderError')
            : this.translate.get('errors.generic'),
      );
      if (!(failure instanceof HorecaOSApiError) || failure.code !== 'NETWORK_UNREACHABLE') {
        this.pendingCheckoutKey = null;
      }
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
        this.dineIn.clear();
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
        this.dineIn.clear();
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
