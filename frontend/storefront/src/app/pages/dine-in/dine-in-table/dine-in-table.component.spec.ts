import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { ApiClient } from '../../../core/api/api-client';
import { HorecaOSApiError } from '../../../core/api/problem-details';
import { ReturnDestination } from '../../../core/auth/return-destination';
import { Session } from '../../../core/auth/session';
import { CartService, type PlatformCart } from '../../../services/cart.service';
import {
  DineInAdmission,
  DineInBill,
  DineInSeating,
  DineInService,
  type RoundFlush,
} from '../../../services/dine-in.service';
import { LangService } from '../../../services/lang.service';
import { LocationProfileService } from '../../../services/location-profile.service';
import { MenuService, type PublishedMenu } from '../../../services/menu.service';
import { NotificationService } from '../../../services/notification.service';
import { TranslateService } from '../../../services/translate.service';
import { DineInTableComponent } from './dine-in-table.component';

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string, params?: Record<string, unknown>): string {
    return params ? `${key}:${JSON.stringify(params)}` : key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

class FakeSession {
  private authenticated = true;
  isAuthenticated = () => this.authenticated;
  setAuthenticated(value: boolean): void {
    this.authenticated = value;
  }
}

class FakeDineInService {
  private readonly admissionSig = signal<DineInAdmission | null>(null);
  bill = vi.fn<(sessionId: string) => Promise<DineInBill>>();
  requestBill = vi.fn<(sessionId: string) => Promise<DineInBill>>();
  attachRound = vi.fn<(sessionId: string, orderId: string) => Promise<DineInBill>>();
  private readonly queued: { sessionId: string; orderId: string }[] = [];
  queueRound = vi.fn((sessionId: string, orderId: string) => {
    this.queued.push({ sessionId, orderId });
  });
  pendingRoundCount = (sessionId: string) =>
    this.queued.filter((round) => round.sessionId === sessionId).length;
  /** The real service's queue-then-attach, minus its storage and failure classification. */
  flushPendingRounds = vi.fn(async (sessionId: string): Promise<RoundFlush> => {
    let latest: DineInBill | null = null;
    for (const round of this.queued.filter((entry) => entry.sessionId === sessionId)) {
      latest = await this.attachRound(round.sessionId, round.orderId);
      this.queued.splice(this.queued.indexOf(round), 1);
    }
    return { bill: latest, pending: this.pendingRoundCount(sessionId), abandoned: 0 };
  });
  isGuestSessionEnded = vi.fn().mockReturnValue(false);
  clear = vi.fn(() => this.admissionSig.set(null));
  seat = vi.fn<(partySize: number) => Promise<DineInSeating>>();
  markWalkInUnavailable = vi.fn(() => {
    const current = this.admissionSig();
    if (current) {
      this.admissionSig.set({ ...current, walkInAvailable: false });
    }
  });
  sessionEnded = vi.fn(() => {
    const current = this.admissionSig();
    if (current) {
      this.admissionSig.set({ ...current, openSessionId: null, walkInAvailable: true });
    }
  });

  admission = () => this.admissionSig();

  seed(value: DineInAdmission | null): void {
    this.admissionSig.set(value);
  }
}

class FakeCartService {
  readonly cart = signal<PlatformCart | null>(null);
  ensure = vi.fn().mockResolvedValue(null);
  putLine = vi.fn();
  removeLine = vi.fn();
  price = vi.fn();
  paymentMethods = vi
    .fn()
    .mockResolvedValue({ cartId: 'cart-1', currency: 'UZS', methodCodes: [], warnings: [] });
  checkout = vi.fn();
  discard = vi.fn();
  bindTable = vi.fn().mockResolvedValue(null);
  /** ADR 0140: the platform keeps the method on the cart, as the real service adopts it. */
  selectPaymentMethod = vi.fn(async (code: string | null) => {
    const held = this.cart();
    if (held) {
      this.cart.set({ ...held, paymentMethodCode: code });
    }
    return this.cart();
  });
}

class FakeMenuService {
  menu = vi.fn<() => Promise<PublishedMenu>>();
}

class FakeLocationProfileService {
  profile = vi.fn().mockResolvedValue({ displayName: 'Central kitchen' });
}

class FakeNotificationService {
  show = vi.fn();
}

function menu(overrides: Partial<PublishedMenu> = {}): PublishedMenu {
  return {
    publicationId: 'pub-1',
    locale: 'en',
    currency: 'UZS',
    categories: [
      {
        categoryId: 'cat-1',
        code: 'MAIN',
        name: 'Main',
        parentCategoryId: null,
        sortOrder: 0,
        productIds: ['prod-1'],
      },
    ],
    products: [
      {
        productId: 'prod-1',
        code: 'OSH',
        name: 'Osh',
        description: 'Plov',
        mediaAssetIds: [],
        imageUrls: [],
        variants: [
          {
            variantId: 'variant-1',
            sku: null,
            unitCode: null,
            isDefault: true,
            orderable: true,
            amountMinor: 45000,
            onSaleNow: true,
            remainingQuantity: null,
          },
        ],
        modifierGroupIds: [],
        commentPresets: [],
      },
    ],
    modifierGroups: [],
    ...overrides,
  };
}

function admission(overrides: Partial<DineInAdmission> = {}): DineInAdmission {
  return {
    guestToken: 'guest-token-1',
    expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    mode: 'ORDER_AND_PAY',
    tenantId: 'tenant-1',
    brandId: 'brand-1',
    locationId: 'location-1',
    tableCode: 'T1',
    openSessionId: 'session-1',
    channelCode: 'QRTABLE',
    ...overrides,
  };
}

function bill(overrides: Partial<DineInBill> = {}): DineInBill {
  return {
    sessionId: 'session-1',
    status: 'OPEN',
    currency: 'UZS',
    totalMinor: 0,
    roundCount: 0,
    orderIds: [],
    ...overrides,
  };
}

function setUp() {
  const dineIn = new FakeDineInService();
  const cartService = new FakeCartService();
  const menuService = new FakeMenuService();
  const session = new FakeSession();
  const notification = new FakeNotificationService();

  TestBed.configureTestingModule({
    imports: [DineInTableComponent],
    providers: [
      provideRouter([]),
      { provide: DineInService, useValue: dineIn },
      { provide: CartService, useValue: cartService },
      { provide: MenuService, useValue: menuService },
      { provide: Session, useValue: session },
      { provide: LangService, useValue: { langId: () => 'en' } },
      { provide: LocationProfileService, useClass: FakeLocationProfileService },
      { provide: NotificationService, useValue: notification },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });

  const fixture = TestBed.createComponent(DineInTableComponent);
  const router = TestBed.inject(Router);
  return { fixture, dineIn, cartService, menuService, session, notification, router };
}

/**
 * Lets every promise chain the component started run to its end. A macrotask, not a
 * fixed count of microtask ticks: the chains here (bind, then line, then reprice) grow
 * by a tick whenever a step gains an `await`, and a count of three was the reason
 * unrelated changes kept breaking these specs.
 */
async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('DineInTableComponent', () => {
  it('shows the scan-a-table prompt when nothing has been scanned this visit', async () => {
    const { fixture } = setUp();

    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="dine-in-no-admission"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="dine-in-menu"]')).toBeNull();
  });

  describe('VIEW_ONLY -- MENU_ONLY mode', () => {
    it('renders the menu with no cart affordance at all', async () => {
      const { fixture, dineIn, menuService } = setUp();
      dineIn.seed(admission({ mode: 'VIEW_ONLY', openSessionId: null }));
      menuService.menu.mockResolvedValue(menu());

      fixture.detectChanges();
      await flush();
      fixture.detectChanges();

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="dine-in-menu-item"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="dine-in-add"]')).toBeNull();
      expect(host.querySelector('[data-testid="dine-in-cart"]')).toBeNull();
      expect(host.querySelector('[data-testid="dine-in-not-seated"]')).toBeNull();
    });
  });

  describe('ORDER_AND_PAY at a table nobody has seated', () => {
    it('shows the menu with an explanation instead of ordering controls', async () => {
      const { fixture, dineIn, menuService } = setUp();
      dineIn.seed(admission({ mode: 'ORDER_AND_PAY', openSessionId: null }));
      menuService.menu.mockResolvedValue(menu());

      fixture.detectChanges();
      await flush();
      fixture.detectChanges();

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="dine-in-not-seated"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="dine-in-menu-item"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="dine-in-add"]')).toBeNull();
      expect(host.querySelector('[data-testid="dine-in-cart"]')).toBeNull();
    });
  });

  describe('ORDER_AND_PAY, seated, signed out', () => {
    it('sends a tap on add-to-cart straight to sign-in without touching the cart', async () => {
      const { fixture, dineIn, menuService, cartService, session, router } = setUp();
      dineIn.seed(admission());
      dineIn.bill.mockResolvedValue(bill());
      menuService.menu.mockResolvedValue(menu());
      session.setAuthenticated(false);
      const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

      fixture.detectChanges();
      await flush();
      fixture.detectChanges();

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="dine-in-signin"]')).not.toBeNull();

      const addButton = host.querySelector<HTMLButtonElement>('[data-testid="dine-in-add"]');
      addButton?.click();
      await flush();

      expect(navigate).toHaveBeenCalledWith(['/auth', 'login']);
      expect(cartService.ensure).not.toHaveBeenCalled();
    });

    // Batch 14: signing in from the table used to land the guest on /locations,
    // nowhere near their table. The screen now remembers the token-free
    // /dine-in/table so the auth flow can send them back.
    describe('returning to the table after sign-in', () => {
      beforeEach(() => {
        sessionStorage.clear();
      });

      it('remembers /dine-in/table when the sign-in button is tapped', async () => {
        const { fixture, dineIn, menuService, session, router } = setUp();
        dineIn.seed(admission());
        dineIn.bill.mockResolvedValue(bill());
        menuService.menu.mockResolvedValue(menu());
        session.setAuthenticated(false);
        const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

        fixture.detectChanges();
        await flush();
        fixture.detectChanges();
        (fixture.nativeElement as HTMLElement)
          .querySelector<HTMLButtonElement>('[data-testid="dine-in-signin"]')
          ?.click();
        await flush();

        expect(navigate).toHaveBeenCalledWith(['/auth', 'login']);
        expect(TestBed.inject(ReturnDestination).consume()).toBe('/dine-in/table');
      });

      it('remembers it when the guest taps add-to-cart while signed out, too', async () => {
        const { fixture, dineIn, menuService, session, router } = setUp();
        dineIn.seed(admission());
        dineIn.bill.mockResolvedValue(bill());
        menuService.menu.mockResolvedValue(menu());
        session.setAuthenticated(false);
        vi.spyOn(router, 'navigate').mockResolvedValue(true);

        fixture.detectChanges();
        await flush();
        fixture.detectChanges();
        (fixture.nativeElement as HTMLElement)
          .querySelector<HTMLButtonElement>('[data-testid="dine-in-add"]')
          ?.click();
        await flush();

        expect(TestBed.inject(ReturnDestination).consume()).toBe('/dine-in/table');
      });

      it('never puts a table token anywhere the auth flow can read it', async () => {
        const { fixture, dineIn, menuService, session, router } = setUp();
        dineIn.seed(admission({ guestToken: 'guest-token-secret' }));
        dineIn.bill.mockResolvedValue(bill());
        menuService.menu.mockResolvedValue(menu());
        session.setAuthenticated(false);
        const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

        fixture.detectChanges();
        await flush();
        fixture.detectChanges();
        (fixture.nativeElement as HTMLElement)
          .querySelector<HTMLButtonElement>('[data-testid="dine-in-signin"]')
          ?.click();
        await flush();

        // The navigation to the login screen carries no query, no state and no token...
        expect(navigate).toHaveBeenCalledTimes(1);
        expect(navigate).toHaveBeenCalledWith(['/auth', 'login']);
        // ...and nothing this flow stored in sessionStorage mentions the guest token either.
        const stored = Array.from({ length: sessionStorage.length }, (_, i) =>
          sessionStorage.getItem(sessionStorage.key(i) ?? ''),
        ).join('|');
        expect(stored).not.toContain('guest-token-secret');
        expect(stored).toContain('/dine-in/table');
      });
    });
  });

  describe('ORDER_AND_PAY, seated, signed in', () => {
    it('resumes the session: loads the menu and the running bill for the already-open session', async () => {
      const { fixture, dineIn, menuService } = setUp();
      dineIn.seed(admission({ openSessionId: 'session-1' }));
      dineIn.bill.mockResolvedValue(bill({ totalMinor: 45000, roundCount: 1 }));
      menuService.menu.mockResolvedValue(menu());

      fixture.detectChanges();
      await flush();
      fixture.detectChanges();

      expect(dineIn.bill).toHaveBeenCalledWith('session-1');
      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="dine-in-bill-total"]')?.textContent).toContain('45');
      expect(host.querySelector('[data-testid="dine-in-bill-rounds"]')).not.toBeNull();
    });

    it('adds an item, prices the cart, checks out, and attaches the round to the table bill', async () => {
      const { fixture, dineIn, menuService, cartService } = setUp();
      dineIn.seed(admission());
      dineIn.bill.mockResolvedValue(bill());
      menuService.menu.mockResolvedValue(menu());

      const cartAfterAdd: PlatformCart = {
        cartId: 'cart-1',
        locationId: 'location-1',
        status: 'OPEN',
        currency: 'UZS',
        fulfillmentMode: 'DINE_IN',
        version: 2,
        quoteId: null,
        contextHash: null,
        expiresAt: null,
        lines: [
          {
            lineKey: 'variant-1',
            variantId: 'variant-1',
            quantity: 1,
            commentPresetCodes: [],
            hasCustomerNote: false,
          },
        ],
      };
      cartService.ensure.mockResolvedValue(cartAfterAdd);
      cartService.putLine.mockImplementation(async () => {
        cartService.cart.set(cartAfterAdd);
        return cartAfterAdd;
      });
      cartService.price.mockResolvedValue({
        cartId: 'cart-1',
        cartVersion: 2,
        quoteId: 'quote-1',
        contextHash: 'hash',
        currency: 'UZS',
        subtotalMinor: 45000,
        taxMinor: 0,
        discountMinor: 0,
        feeMinor: 0,
        totalMinor: 45000,
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
        delivery: null,
      });
      cartService.paymentMethods.mockResolvedValue({
        cartId: 'cart-1',
        currency: 'UZS',
        methodCodes: ['CASH'],
        warnings: [],
      });
      cartService.checkout.mockResolvedValue({
        orderId: 'order-1',
        publicOrderNumber: '0001',
        status: 'CONFIRMED',
        version: 1,
        outcome: 'CREATED',
        warnings: [],
      });
      dineIn.attachRound.mockResolvedValue(bill({ totalMinor: 45000, roundCount: 1 }));

      fixture.detectChanges();
      await flush();
      fixture.detectChanges();

      const host = fixture.nativeElement as HTMLElement;
      const addButton = host.querySelector<HTMLButtonElement>('[data-testid="dine-in-add"]');
      addButton?.click();
      await flush();
      fixture.detectChanges();
      await flush();
      fixture.detectChanges();

      expect(cartService.ensure).toHaveBeenCalledWith('location-1', 'DINE_IN', true, 'QRTABLE');
      expect(cartService.putLine).toHaveBeenCalledWith(
        expect.objectContaining({ variantId: 'variant-1', quantity: 1 }),
      );

      const checkoutButton = host.querySelector<HTMLButtonElement>(
        '[data-testid="dine-in-checkout"]',
      );
      expect(checkoutButton).not.toBeNull();
      checkoutButton?.click();
      await flush();
      fixture.detectChanges();

      expect(cartService.checkout).toHaveBeenCalledWith(
        expect.objectContaining({ paymentMethodCode: 'CASH' }),
      );
      expect(dineIn.queueRound).toHaveBeenCalledWith('session-1', 'order-1');
      expect(dineIn.attachRound).toHaveBeenCalledWith('session-1', 'order-1');
      expect(cartService.discard).toHaveBeenCalledWith('location-1');
    });
  });

  describe('the payment method is part of the price (ADR 0140)', () => {
    /** A seated guest with one dish in the basket and two ways to pay; card pays 5% less. */
    async function seatedWithTwoMethods() {
      const rig = setUp();
      const { dineIn, menuService, cartService } = rig;
      dineIn.seed(admission());
      dineIn.bill.mockResolvedValue(bill());
      menuService.menu.mockResolvedValue(menu());
      const cart: PlatformCart = {
        cartId: 'cart-1',
        locationId: 'location-1',
        status: 'OPEN',
        currency: 'UZS',
        fulfillmentMode: 'DINE_IN',
        version: 2,
        quoteId: null,
        contextHash: null,
        expiresAt: null,
        lines: [
          {
            lineKey: 'variant-1',
            variantId: 'variant-1',
            quantity: 1,
            commentPresetCodes: [],
            hasCustomerNote: false,
          },
        ],
      };
      cartService.ensure.mockResolvedValue(cart);
      cartService.putLine.mockImplementation(async () => {
        cartService.cart.set(cart);
        return cart;
      });
      cartService.price.mockImplementation(async () => {
        const total = cartService.cart()?.paymentMethodCode === 'CLICK' ? 42750 : 45000;
        return {
          cartId: 'cart-1',
          cartVersion: 2,
          quoteId: `quote-${total}`,
          contextHash: 'hash',
          currency: 'UZS',
          subtotalMinor: 45000,
          taxMinor: 0,
          discountMinor: 45000 - total,
          feeMinor: 0,
          totalMinor: total,
          expiresAt: new Date(Date.now() + 60_000).toISOString(),
          delivery: null,
        };
      });
      cartService.paymentMethods.mockResolvedValue({
        cartId: 'cart-1',
        currency: 'UZS',
        methodCodes: ['CASH', 'CLICK'],
        warnings: [],
      });
      cartService.checkout.mockResolvedValue({
        orderId: 'order-1',
        publicOrderNumber: '0001',
        status: 'CONFIRMED',
        version: 1,
        outcome: 'CREATED',
        warnings: [],
      });
      dineIn.attachRound.mockResolvedValue(bill({ totalMinor: 42750, roundCount: 1 }));

      rig.fixture.detectChanges();
      await flush();
      rig.fixture.detectChanges();
      const host = rig.fixture.nativeElement as HTMLElement;
      host.querySelector<HTMLButtonElement>('[data-testid="dine-in-add"]')?.click();
      await flush();
      rig.fixture.detectChanges();
      await flush();
      rig.fixture.detectChanges();
      return { ...rig, host };
    }

    it('writes the method the guest picks to the cart and prices it again, so checkout spends a quote priced under it', async () => {
      const { fixture, cartService, host } = await seatedWithTwoMethods();

      const options = host.querySelectorAll<HTMLButtonElement>(
        '[data-testid="dine-in-payment-options"] button',
      );
      options[1].click();
      await flush();
      fixture.detectChanges();

      expect(cartService.selectPaymentMethod).toHaveBeenCalledWith('CLICK');

      host.querySelector<HTMLButtonElement>('[data-testid="dine-in-checkout"]')?.click();
      await flush();
      fixture.detectChanges();

      expect(cartService.checkout).toHaveBeenCalledWith(
        expect.objectContaining({
          paymentMethodCode: 'CLICK',
          priced: expect.objectContaining({ totalMinor: 42750 }),
        }),
      );
    });

    it('writes the first method on offer too, so a default the guest never tapped is priced under it', async () => {
      const { cartService } = await seatedWithTwoMethods();

      expect(cartService.selectPaymentMethod).toHaveBeenCalledWith('CASH');
    });
  });

  describe('a guest token the platform no longer recognises', () => {
    it('clears the admission instead of leaving a broken screen up', async () => {
      const { fixture, dineIn, menuService } = setUp();
      dineIn.seed(admission());
      dineIn.bill.mockRejectedValue(new Error('unauthenticated'));
      dineIn.isGuestSessionEnded.mockReturnValue(true);
      menuService.menu.mockResolvedValue(menu());

      fixture.detectChanges();
      await flush();
      fixture.detectChanges();

      expect(dineIn.clear).toHaveBeenCalled();
    });
  });
});

/**
 * The bill is the only thing that ties a guest's order to their table: the
 * kitchen ticket's table chip, the order board's row and the running total
 * all read `dinein.session_orders`, and the only writer for a guest order is
 * the second call `checkout()` makes after the order exists. These specs run
 * the real {@link DineInService} against a fake HTTP client, because what
 * they prove is what goes over the wire when that second call is lost.
 */
describe('DineInTableComponent -- a round the platform never confirmed onto the bill', () => {
  const ADMISSION_KEY = 'horecaos_dinein_admission';
  const ROUNDS_PATH = '/storefront/dine-in/sessions/session-1/rounds';

  interface FakeApi {
    get: ReturnType<typeof vi.fn>;
    mutate: ReturnType<typeof vi.fn>;
  }

  function newApi(): FakeApi {
    const api: FakeApi = { get: vi.fn(), mutate: vi.fn() };
    api.get.mockResolvedValue(bill());
    return api;
  }

  function setUpReal(api: FakeApi) {
    localStorage.setItem(ADMISSION_KEY, JSON.stringify(admission()));
    const cartService = new FakeCartService();
    const menuService = new FakeMenuService();
    const notification = new FakeNotificationService();
    const session = new FakeSession();
    menuService.menu.mockResolvedValue(menu());

    TestBed.configureTestingModule({
      imports: [DineInTableComponent],
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: api },
        { provide: CartService, useValue: cartService },
        { provide: MenuService, useValue: menuService },
        { provide: Session, useValue: session },
        { provide: LangService, useValue: { langId: () => 'en' } },
        { provide: LocationProfileService, useClass: FakeLocationProfileService },
        { provide: NotificationService, useValue: notification },
        { provide: TranslateService, useClass: FakeTranslateService },
      ],
    });
    return {
      fixture: TestBed.createComponent(DineInTableComponent),
      cartService,
      notification,
      session,
    };
  }

  async function settle(): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }

  function primeCheckout(cartService: FakeCartService): void {
    const cart: PlatformCart = {
      cartId: 'cart-1',
      locationId: 'location-1',
      status: 'OPEN',
      currency: 'UZS',
      fulfillmentMode: 'DINE_IN',
      version: 2,
      quoteId: null,
      contextHash: null,
      expiresAt: null,
      lines: [
        {
          lineKey: 'variant-1',
          variantId: 'variant-1',
          quantity: 1,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
      ],
    };
    cartService.ensure.mockResolvedValue(cart);
    cartService.putLine.mockImplementation(async () => {
      cartService.cart.set(cart);
      return cart;
    });
    cartService.price.mockResolvedValue({
      cartId: 'cart-1',
      cartVersion: 2,
      quoteId: 'quote-1',
      contextHash: 'hash',
      currency: 'UZS',
      subtotalMinor: 45000,
      taxMinor: 0,
      discountMinor: 0,
      feeMinor: 0,
      totalMinor: 45000,
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      delivery: null,
    });
    cartService.paymentMethods.mockResolvedValue({
      cartId: 'cart-1',
      currency: 'UZS',
      methodCodes: ['CASH'],
      warnings: [],
    });
    cartService.checkout.mockResolvedValue({
      orderId: 'order-1',
      publicOrderNumber: '0001',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'CREATED',
      warnings: [],
    });
  }

  async function placeOrder(
    fixture: ReturnType<typeof setUpReal>['fixture'],
    cartService: FakeCartService,
  ): Promise<void> {
    primeCheckout(cartService);
    const host = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    host.querySelector<HTMLButtonElement>('[data-testid="dine-in-add"]')?.click();
    await settle();
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    host.querySelector<HTMLButtonElement>('[data-testid="dine-in-checkout"]')?.click();
    await settle();
    fixture.detectChanges();
  }

  function roundCalls(api: FakeApi): unknown[][] {
    return api.mutate.mock.calls.filter((call) => call[1] === ROUNDS_PATH);
  }

  const offline = () =>
    new HorecaOSApiError({
      status: 0,
      code: 'NETWORK_UNREACHABLE',
      detail: 'The request did not reach the platform.',
    });

  beforeEach(() => {
    localStorage.clear();
  });

  it('keeps the placed order and attaches it when the guest comes back to the table', async () => {
    const api = newApi();
    api.mutate.mockRejectedValue(offline());
    const first = setUpReal(api);
    await placeOrder(first.fixture, first.cartService);
    expect(roundCalls(api)).toHaveLength(1);

    // The phone reloads the page with signal back: the order id is not in
    // component state any more, only in what the device remembered.
    first.fixture.destroy();
    TestBed.resetTestingModule();
    api.mutate.mockResolvedValue(bill({ totalMinor: 45000, roundCount: 1, orderIds: ['order-1'] }));
    const reloaded = setUpReal(api);
    reloaded.fixture.detectChanges();
    await settle();
    reloaded.fixture.detectChanges();

    expect(roundCalls(api)).toHaveLength(2);
    expect(roundCalls(api)[1]).toEqual([
      'POST',
      ROUNDS_PATH,
      expect.objectContaining({ body: { orderId: 'order-1' } }),
    ]);
    const host = reloaded.fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="dine-in-bill-total"]')?.textContent).toContain('45');
    expect(host.querySelector('[data-testid="dine-in-round-pending"]')).toBeNull();
  });

  it('shows the unattached order and a retry that lands it on the bill without a reload', async () => {
    const api = newApi();
    api.mutate.mockRejectedValue(offline());
    const { fixture, cartService, notification } = setUpReal(api);
    await placeOrder(fixture, cartService);

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="dine-in-round-pending"]')).not.toBeNull();
    expect(notification.show).toHaveBeenCalledWith('dineIn.roundAttachRetry');
    expect(notification.show).not.toHaveBeenCalledWith('dineIn.orderPlaced');

    api.mutate.mockResolvedValue(bill({ totalMinor: 45000, roundCount: 1, orderIds: ['order-1'] }));
    host.querySelector<HTMLButtonElement>('[data-testid="dine-in-round-retry"]')?.click();
    await settle();
    fixture.detectChanges();

    expect(roundCalls(api)).toHaveLength(2);
    expect(host.querySelector('[data-testid="dine-in-round-pending"]')).toBeNull();
    expect(host.querySelector('[data-testid="dine-in-bill-total"]')?.textContent).toContain('45');
    // Success is the notice going away and the bill moving; only the failure spoke.
    expect(notification.show).toHaveBeenCalledTimes(1);
  });

  it('stops retrying an order the platform refuses for good, and tells the guest to ask staff', async () => {
    const api = newApi();
    api.mutate.mockRejectedValue(
      new HorecaOSApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        detail: 'The session is closed.',
      }),
    );
    const first = setUpReal(api);
    await placeOrder(first.fixture, first.cartService);

    expect(first.notification.show).toHaveBeenCalledWith('dineIn.roundAttachFailed');
    expect(
      (first.fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="dine-in-round-lost"]',
      ),
    ).not.toBeNull();
    expect(
      (first.fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="dine-in-round-pending"]',
      ),
    ).toBeNull();

    first.fixture.destroy();
    TestBed.resetTestingModule();
    const reloaded = setUpReal(api);
    reloaded.fixture.detectChanges();
    await settle();

    expect(roundCalls(api)).toHaveLength(1);
  });

  it('leaves the queue alone while the guest is signed out, and sends the notice button to sign-in', async () => {
    const api = newApi();
    api.mutate.mockRejectedValue(offline());
    const first = setUpReal(api);
    await placeOrder(first.fixture, first.cartService);
    first.fixture.destroy();
    TestBed.resetTestingModule();

    const reloaded = setUpReal(api);
    reloaded.session.setAuthenticated(false);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    reloaded.fixture.detectChanges();
    await settle();
    reloaded.fixture.detectChanges();

    const host = reloaded.fixture.nativeElement as HTMLElement;
    expect(roundCalls(api)).toHaveLength(1);
    expect(host.querySelector('[data-testid="dine-in-round-pending"]')).not.toBeNull();

    host.querySelector<HTMLButtonElement>('[data-testid="dine-in-round-retry"]')?.click();
    await settle();

    expect(navigate).toHaveBeenCalledWith(['/auth', 'login']);
    expect(roundCalls(api)).toHaveLength(1);
  });

  it('does not treat a lost signal or a refused sign-in as a refusal: the order stays queued', async () => {
    const api = newApi();
    api.mutate.mockRejectedValue(
      new HorecaOSApiError({ status: 401, code: 'UNAUTHENTICATED', detail: 'Sign in again.' }),
    );
    const first = setUpReal(api);
    await placeOrder(first.fixture, first.cartService);

    expect(
      (first.fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="dine-in-round-pending"]',
      ),
    ).not.toBeNull();
    expect(first.notification.show).not.toHaveBeenCalledWith('dineIn.roundAttachFailed');
  });
});

describe('DineInTableComponent -- a cart bound to the table it is eaten at (ADR 0047)', () => {
  const CART: PlatformCart = {
    cartId: 'cart-1',
    locationId: 'location-1',
    status: 'OPEN',
    currency: 'UZS',
    fulfillmentMode: 'DINE_IN',
    version: 2,
    quoteId: null,
    contextHash: null,
    expiresAt: null,
    lines: [
      {
        lineKey: 'variant-1',
        variantId: 'variant-1',
        quantity: 1,
        commentPresetCodes: [],
        hasCustomerNote: false,
      },
    ],
  };

  /**
   * A signed-in guest at a seated table whose menu offers one dish; `ensure` opens
   * the cart. With `existingCart` the guest already had a basket when the screen
   * loaded, which is what `ensure(..., create = false)` finds.
   */
  async function seatedGuest(options: { existingCart?: boolean } = {}) {
    const parts = setUp();
    parts.dineIn.seed(admission());
    parts.dineIn.bill.mockResolvedValue(bill());
    parts.menuService.menu.mockResolvedValue(menu());
    parts.cartService.ensure.mockImplementation(
      async (_location: string, _mode: string, create = true) => {
        if (!create && !options.existingCart) {
          return null;
        }
        parts.cartService.cart.set(create ? { ...CART, lines: [] } : CART);
        return CART;
      },
    );
    parts.cartService.putLine.mockImplementation(async () => {
      parts.cartService.cart.set(CART);
      return CART;
    });
    parts.fixture.detectChanges();
    await flush();
    parts.fixture.detectChanges();
    return parts;
  }

  async function tapAdd(fixture: { detectChanges(): void; nativeElement: unknown }): Promise<void> {
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('[data-testid="dine-in-add"]')
      ?.click();
    await flush();
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  it('binds the cart to the scanned table with the guest token, before its first line', async () => {
    const { fixture, cartService } = await seatedGuest();

    await tapAdd(fixture);

    expect(cartService.bindTable).toHaveBeenCalledTimes(1);
    expect(cartService.bindTable).toHaveBeenCalledWith('guest-token-1');
    expect(cartService.bindTable.mock.invocationCallOrder[0]).toBeLessThan(
      cartService.putLine.mock.invocationCallOrder[0],
    );
  });

  it('binds a cart once, not on every line', async () => {
    const { fixture, cartService } = await seatedGuest();

    await tapAdd(fixture);
    // With a dish in the cart the add button becomes a stepper.
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('[data-testid="dine-in-increase"]')
      ?.click();
    await flush();
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(cartService.putLine).toHaveBeenCalledTimes(2);
    expect(cartService.bindTable).toHaveBeenCalledTimes(1);
  });

  it('a bind the platform refuses costs the guest nothing: the dish is still added, and the queued attach still stands', async () => {
    const { fixture, cartService } = await seatedGuest();
    cartService.bindTable.mockRejectedValue(
      new HorecaOSApiError({ status: 409, code: 'RESOURCE_CONFLICT', detail: 'no' }),
    );

    await tapAdd(fixture);

    expect(cartService.putLine).toHaveBeenCalledWith(
      expect.objectContaining({ variantId: 'variant-1' }),
    );
  });

  it('a guest token the platform no longer recognises, met while binding, clears the visit instead of adding', async () => {
    const { fixture, cartService, dineIn } = await seatedGuest();
    cartService.bindTable.mockRejectedValue(
      new HorecaOSApiError({ status: 401, code: 'UNAUTHENTICATED', detail: 'ended' }),
    );
    dineIn.isGuestSessionEnded.mockReturnValue(true);

    await tapAdd(fixture);

    expect(dineIn.clear).toHaveBeenCalled();
    expect(cartService.putLine).not.toHaveBeenCalled();
  });

  it('a checkout refused because nobody is seated any more tells the guest to ask staff, and queues nothing', async () => {
    const { fixture, cartService, dineIn } = await seatedGuest();
    cartService.price.mockResolvedValue({
      cartId: 'cart-1',
      cartVersion: 3,
      quoteId: 'quote-1',
      contextHash: 'hash',
      currency: 'UZS',
      subtotalMinor: 45000,
      taxMinor: 0,
      discountMinor: 0,
      feeMinor: 0,
      totalMinor: 45000,
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      delivery: null,
    });
    cartService.paymentMethods.mockResolvedValue({
      cartId: 'cart-1',
      currency: 'UZS',
      methodCodes: ['CASH'],
      warnings: [],
    });
    cartService.checkout.mockRejectedValue(
      new HorecaOSApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        detail: 'Nobody is seated at this table',
        problem: { reason: 'TABLE_NOT_SEATED' },
      }),
    );
    await tapAdd(fixture);
    const host = fixture.nativeElement as HTMLElement;

    host.querySelector<HTMLButtonElement>('[data-testid="dine-in-checkout"]')?.click();
    await flush();
    fixture.detectChanges();

    expect(fixture.componentInstance.checkoutError()).toBe('dineIn.notSeated');
    expect(dineIn.queueRound).not.toHaveBeenCalled();
    expect(cartService.discard).not.toHaveBeenCalled();
  });

  /** Prices a one-dish basket so the checkout button is live, as the effect does on a real cart. */
  function priceOneDish(cartService: FakeCartService): void {
    cartService.price.mockResolvedValue({
      cartId: 'cart-1',
      cartVersion: 3,
      quoteId: 'quote-1',
      contextHash: 'hash',
      currency: 'UZS',
      subtotalMinor: 45000,
      taxMinor: 0,
      discountMinor: 0,
      feeMinor: 0,
      totalMinor: 45000,
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      delivery: null,
    });
    cartService.paymentMethods.mockResolvedValue({
      cartId: 'cart-1',
      currency: 'UZS',
      methodCodes: ['CASH'],
      warnings: [],
    });
  }

  async function pressCheckout(fixture: {
    detectChanges(): void;
    nativeElement: unknown;
  }): Promise<void> {
    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLButtonElement>('[data-testid="dine-in-checkout"]')
      ?.click();
    await flush();
    fixture.detectChanges();
  }

  it('binds a basket the guest already had as soon as the table screen loads, without a line being touched', async () => {
    const { cartService } = await seatedGuest({ existingCart: true });

    expect(cartService.bindTable).toHaveBeenCalledTimes(1);
    expect(cartService.bindTable).toHaveBeenCalledWith('guest-token-1');
  });

  it('does not bind on load when the guest has no basket yet: the first line does', async () => {
    const { cartService } = await seatedGuest();

    expect(cartService.bindTable).not.toHaveBeenCalled();
  });

  it('a guest token the platform no longer recognises, met while rebinding on load, clears the visit', async () => {
    const parts = setUp();
    parts.dineIn.seed(admission());
    parts.dineIn.bill.mockResolvedValue(bill());
    parts.menuService.menu.mockResolvedValue(menu());
    parts.cartService.ensure.mockImplementation(async () => {
      parts.cartService.cart.set(CART);
      return CART;
    });
    parts.cartService.bindTable.mockRejectedValue(
      new HorecaOSApiError({ status: 401, code: 'UNAUTHENTICATED', detail: 'ended' }),
    );
    parts.dineIn.isGuestSessionEnded.mockReturnValue(true);

    parts.fixture.detectChanges();
    await flush();
    parts.fixture.detectChanges();

    expect(parts.dineIn.clear).toHaveBeenCalled();
  });

  it('sends the guest token with the checkout, so the platform can re-prove the table the cart is bound to', async () => {
    const { fixture, cartService } = await seatedGuest();
    priceOneDish(cartService);
    cartService.checkout.mockResolvedValue({ orderId: 'order-1', outcome: 'CREATED' });
    await tapAdd(fixture);

    await pressCheckout(fixture);

    expect(cartService.checkout).toHaveBeenCalledWith(
      expect.objectContaining({ guestToken: 'guest-token-1', paymentMethodCode: 'CASH' }),
    );
  });

  it('a checkout refused because the party this device scanned for is over clears the visit: scan again', async () => {
    const { fixture, cartService, dineIn } = await seatedGuest();
    priceOneDish(cartService);
    cartService.checkout.mockRejectedValue(
      new HorecaOSApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        detail: 'This table session has ended. Scan the code again.',
        problem: { reason: 'TABLE_TOKEN_ENDED' },
      }),
    );
    await tapAdd(fixture);

    await pressCheckout(fixture);

    expect(dineIn.clear).toHaveBeenCalled();
    expect(dineIn.queueRound).not.toHaveBeenCalled();
    expect(cartService.discard).not.toHaveBeenCalled();
  });

  it('a checkout refused because the cart is bound to another table rebinds it and asks the guest to look again', async () => {
    const { fixture, cartService, dineIn } = await seatedGuest();
    priceOneDish(cartService);
    cartService.checkout.mockRejectedValue(
      new HorecaOSApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        detail: 'This cart was started at another table.',
        problem: { reason: 'TABLE_BINDING_STALE' },
      }),
    );
    await tapAdd(fixture);
    expect(cartService.bindTable).toHaveBeenCalledTimes(1);

    await pressCheckout(fixture);

    expect(cartService.bindTable).toHaveBeenCalledTimes(2);
    expect(fixture.componentInstance.checkoutError()).toBe('dineIn.tableChanged');
    expect(dineIn.queueRound).not.toHaveBeenCalled();
    expect(cartService.discard).not.toHaveBeenCalled();
  });
});

describe('DineInTableComponent -- sitting down at a free table (ADR 0143)', () => {
  const FREE = { mode: 'ORDER_AND_PAY', openSessionId: null, walkInAvailable: true } as const;

  function seatingOf(overrides: Partial<DineInSeating> = {}): DineInSeating {
    return {
      ...bill({ sessionId: 'session-new' }),
      origin: 'GUEST_QR',
      claimExpiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
      confirmed: false,
      created: true,
      ...overrides,
    };
  }

  /** The platform seated the guest: the stored admission now names the session, as the real service does. */
  function platformSeats(dineIn: FakeDineInService, seating: DineInSeating): void {
    dineIn.seat.mockImplementation(async () => {
      dineIn.seed(admission({ openSessionId: seating.sessionId, walkInAvailable: false }));
      return seating;
    });
  }

  async function render(admissionOverrides: Partial<DineInAdmission> = FREE, signedIn = true) {
    const parts = setUp();
    parts.session.setAuthenticated(signedIn);
    parts.dineIn.seed(admission(admissionOverrides));
    parts.menuService.menu.mockResolvedValue(menu());
    parts.fixture.detectChanges();
    await flush();
    parts.fixture.detectChanges();
    return { ...parts, host: parts.fixture.nativeElement as HTMLElement };
  }

  async function press(
    parts: { fixture: { detectChanges(): void }; host: HTMLElement },
    testId: string,
  ) {
    parts.host.querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)!.click();
    await flush();
    parts.fixture.detectChanges();
  }

  it('invites the guest to sit when the platform said at the scan that the table could be taken', async () => {
    const { host } = await render();

    expect(host.querySelector('[data-testid="dine-in-sit"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="dine-in-not-seated"]')).toBeNull();
    // Still no ordering controls: nothing is sat at yet.
    expect(host.querySelector('[data-testid="dine-in-add"]')).toBeNull();
  });

  it.each([
    ['the branch has not turned it on', { walkInAvailable: false }],
    ['an admission stored by an earlier build', { walkInAvailable: undefined }],
  ])('keeps the old "ask a member of staff" notice when %s', async (_name, overrides) => {
    const { host } = await render({ mode: 'ORDER_AND_PAY', openSessionId: null, ...overrides });

    expect(host.querySelector('[data-testid="dine-in-sit"]')).toBeNull();
    expect(host.querySelector('[data-testid="dine-in-not-seated"]')).not.toBeNull();
  });

  it('never offers it at a table that is already seated, or on a menu-only code', async () => {
    const seated = await render({
      mode: 'ORDER_AND_PAY',
      openSessionId: 'session-1',
      walkInAvailable: true,
    });
    seated.dineIn.bill.mockResolvedValue(bill());
    expect(seated.host.querySelector('[data-testid="dine-in-sit"]')).toBeNull();

    TestBed.resetTestingModule();
    const menuOnly = await render({
      mode: 'VIEW_ONLY',
      openSessionId: null,
      walkInAvailable: true,
    });
    expect(menuOnly.host.querySelector('[data-testid="dine-in-sit"]')).toBeNull();
  });

  it('counts the party from two, never below one', async () => {
    const parts = await render();

    expect(parts.host.querySelector('[data-testid="dine-in-party-size"]')?.textContent).toContain(
      '2',
    );
    await press(parts, 'dine-in-party-increase');
    expect(parts.host.querySelector('[data-testid="dine-in-party-size"]')?.textContent).toContain(
      '3',
    );
    await press(parts, 'dine-in-party-decrease');
    await press(parts, 'dine-in-party-decrease');
    expect(parts.host.querySelector('[data-testid="dine-in-party-size"]')?.textContent).toContain(
      '1',
    );
    expect(
      parts.host.querySelector<HTMLButtonElement>('[data-testid="dine-in-party-decrease"]')!
        .disabled,
    ).toBe(true);
  });

  it('sends a signed-out guest to sign in first and remembers to come back to the table', async () => {
    sessionStorage.clear();
    const parts = await render(FREE, false);
    const navigate = vi.spyOn(parts.router, 'navigate').mockResolvedValue(true);

    expect(parts.host.querySelector('[data-testid="dine-in-sit-button"]')?.textContent).toContain(
      'dineIn.signInToSit',
    );
    await press(parts, 'dine-in-sit-button');

    expect(navigate).toHaveBeenCalledWith(['/auth', 'login']);
    expect(parts.dineIn.seat).not.toHaveBeenCalled();
    expect(TestBed.inject(ReturnDestination).consume()).toBe('/dine-in/table');
  });

  it('sits a signed-in guest down with their party size, and then shows the table, the hold and no bill to ask for', async () => {
    const parts = await render();
    platformSeats(parts.dineIn, seatingOf());
    await press(parts, 'dine-in-party-increase');

    await press(parts, 'dine-in-sit-button');

    expect(parts.dineIn.seat).toHaveBeenCalledWith(3);
    expect(parts.host.querySelector('[data-testid="dine-in-sit"]')).toBeNull();
    expect(parts.host.querySelector('[data-testid="dine-in-cart"]')).not.toBeNull();
    expect(parts.host.querySelector('[data-testid="dine-in-claim-held"]')?.textContent).toContain(
      '"minutes":10',
    );
    // There is nothing the restaurant has accepted to bill, so the control is not offered.
    expect(parts.host.querySelector('[data-testid="dine-in-bill"]')).not.toBeNull();
    expect(parts.host.querySelector('[data-testid="dine-in-request-bill"]')).toBeNull();
    expect(parts.host.querySelector('[data-testid="dine-in-joined-existing"]')).toBeNull();
  });

  it('offers "ask for the bill" again once the claim is confirmed', async () => {
    const parts = await render();
    platformSeats(
      parts.dineIn,
      seatingOf({ confirmed: true, claimExpiresAt: null, roundCount: 1, totalMinor: 45000 }),
    );

    await press(parts, 'dine-in-sit-button');

    expect(parts.host.querySelector('[data-testid="dine-in-claim-held"]')).toBeNull();
    expect(parts.host.querySelector('[data-testid="dine-in-request-bill"]')).not.toBeNull();
  });

  it("says so when the seat the platform handed back is somebody else's: orders go on their bill", async () => {
    const parts = await render();
    platformSeats(
      parts.dineIn,
      seatingOf({ created: false, confirmed: true, claimExpiresAt: null }),
    );

    await press(parts, 'dine-in-sit-button');

    expect(parts.host.querySelector('[data-testid="dine-in-joined-existing"]')).not.toBeNull();
  });

  it('answers every "no" with one sentence and stops offering the table', async () => {
    const parts = await render();
    parts.dineIn.seat.mockRejectedValue(
      new HorecaOSApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        detail: 'This table cannot be taken from here.',
        problem: { conflict: 'TABLE_NOT_AVAILABLE' },
      }),
    );

    await press(parts, 'dine-in-sit-button');

    expect(parts.dineIn.markWalkInUnavailable).toHaveBeenCalled();
    expect(parts.host.querySelector('[data-testid="dine-in-sit"]')).toBeNull();
    expect(parts.host.querySelector('[data-testid="dine-in-not-seated"]')).not.toBeNull();
    expect(parts.host.querySelector('[data-testid="dine-in-seat-error"]')?.textContent).toContain(
      'dineIn.tableNotAvailable',
    );
  });

  it('tells a guest whose party is too big how many the table seats', async () => {
    const parts = await render();
    parts.dineIn.seat.mockRejectedValue(
      new HorecaOSApiError({
        status: 400,
        code: 'VALIDATION_FAILED',
        detail: 'This table seats 4.',
        problem: { seats: 4 },
      }),
    );

    await press(parts, 'dine-in-sit-button');

    const error = parts.host.querySelector('[data-testid="dine-in-seat-error"]')?.textContent ?? '';
    expect(error).toContain('dineIn.tooManyForTable');
    expect(error).toContain('"seats":4');
    // The invitation stays: a smaller party is a fair retry.
    expect(parts.host.querySelector('[data-testid="dine-in-sit"]')).not.toBeNull();
  });

  it('sends a guest whose own sign-in lapsed to sign in, without ending the table visit', async () => {
    sessionStorage.clear();
    const parts = await render();
    const navigate = vi.spyOn(parts.router, 'navigate').mockResolvedValue(true);
    parts.dineIn.seat.mockRejectedValue(
      new HorecaOSApiError({
        status: 401,
        code: 'UNAUTHENTICATED',
        detail: 'Sit at this table once you have signed in',
        problem: { reason: 'CUSTOMER_SESSION_REQUIRED' },
      }),
    );

    await press(parts, 'dine-in-sit-button');

    expect(navigate).toHaveBeenCalledWith(['/auth', 'login']);
    expect(parts.dineIn.clear).not.toHaveBeenCalled();
  });

  it("ends the visit when the table's own guest token is what the platform no longer knows", async () => {
    const parts = await render();
    parts.dineIn.seat.mockRejectedValue(
      new HorecaOSApiError({
        status: 401,
        code: 'UNAUTHENTICATED',
        detail: 'This table session has ended. Scan the code again.',
      }),
    );

    await press(parts, 'dine-in-sit-button');

    expect(parts.dineIn.clear).toHaveBeenCalled();
  });

  describe('the hold runs out', () => {
    afterEach(() => {
      vi.restoreAllMocks();
    });

    /**
     * Captures the countdown's own timer (15 s) and drives it by hand, so the clock the
     * component reads is a clock this test moves.
     */
    function claimClock() {
      const timers: Array<() => void> = [];
      const realSetInterval = globalThis.setInterval;
      vi.spyOn(globalThis, 'setInterval').mockImplementation(((
        handler: () => void,
        delay?: number,
      ) => {
        if (delay === 15_000) {
          timers.push(handler);
          return 0 as unknown as ReturnType<typeof setInterval>;
        }
        return realSetInterval(handler, delay);
      }) as typeof setInterval);
      return async (parts: { fixture: { detectChanges(): void } }) => {
        timers.forEach((tick) => tick());
        await flush();
        parts.fixture.detectChanges();
      };
    }

    /** The answer the real service's predicate gives: a 401 `UNAUTHENTICATED` is a dead guest token. */
    function realGuestTokenCheck(parts: { dineIn: FakeDineInService }) {
      parts.dineIn.isGuestSessionEnded.mockImplementation((failure: unknown) =>
        DineInService.prototype.isGuestSessionEnded.call(undefined, failure),
      );
    }

    it('reads the bill once the window has passed, and offers to sit again when the session is gone behind a live token', async () => {
      const tick = claimClock();

      const parts = await render();
      const claim = seatingOf({ claimExpiresAt: new Date(Date.now() + 60_000).toISOString() });
      platformSeats(parts.dineIn, claim);
      await press(parts, 'dine-in-sit-button');
      expect(parts.host.querySelector('[data-testid="dine-in-claim-held"]')).not.toBeNull();
      expect(parts.dineIn.bill).not.toHaveBeenCalled();

      // Two minutes later the window is behind us: the next tick reads the bill.
      const later = Date.now() + 2 * 60_000;
      vi.spyOn(Date, 'now').mockReturnValue(later);
      parts.dineIn.bill.mockRejectedValue(
        new HorecaOSApiError({
          status: 404,
          code: 'RESOURCE_NOT_FOUND',
          detail: 'No open bill at this table',
        }),
      );
      await tick(parts);

      expect(parts.dineIn.bill).toHaveBeenCalledTimes(1);
      expect(parts.dineIn.bill).toHaveBeenCalledWith('session-new');
      expect(parts.dineIn.sessionEnded).toHaveBeenCalled();
      expect(parts.host.querySelector('[data-testid="dine-in-sit"]')).not.toBeNull();

      // And a further tick does not read it again: there is no claim left to watch.
      await tick(parts);
      expect(parts.dineIn.bill).toHaveBeenCalledTimes(1);
    });

    it('says the hold ended, and asks to scan again, when the lapse took the guest token with it (a 401, not a 404)', async () => {
      // A lapse closes the session, and closing a session revokes every guest token minted
      // at its table (TableSessionService.doMove): the claimant's next read is refused as
      // a dead token -- 401 UNAUTHENTICATED -- never as a missing bill.
      const tick = claimClock();

      const parts = await render();
      realGuestTokenCheck(parts);
      platformSeats(
        parts.dineIn,
        seatingOf({ claimExpiresAt: new Date(Date.now() + 60_000).toISOString() }),
      );
      await press(parts, 'dine-in-sit-button');

      vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 2 * 60_000);
      parts.dineIn.bill.mockRejectedValue(
        new HorecaOSApiError({
          status: 401,
          code: 'UNAUTHENTICATED',
          detail: 'This table session has ended. Scan the code again.',
        }),
      );
      await tick(parts);

      expect(parts.dineIn.clear).toHaveBeenCalled();
      expect(parts.host.querySelector('[data-testid="dine-in-claim-lapsed"]')).not.toBeNull();
      expect(
        parts.host.querySelector('[data-testid="dine-in-no-admission"]')?.textContent,
      ).toContain('"code":"T1"');
      // The token is dead, so "Sit at this table" would only be refused again.
      expect(parts.host.querySelector('[data-testid="dine-in-sit"]')).toBeNull();
      expect(parts.host.querySelector('[data-testid="dine-in-table-code"]')).toBeNull();
    });

    it('does not mistake a dead token on a confirmed session for a lapsed claim', async () => {
      const parts = await render({ openSessionId: 'session-1', walkInAvailable: false });
      realGuestTokenCheck(parts);
      parts.dineIn.bill.mockResolvedValueOnce(bill({ confirmed: true }));
      await parts.fixture.componentInstance.refreshBill();

      parts.dineIn.bill.mockRejectedValue(
        new HorecaOSApiError({ status: 401, code: 'UNAUTHENTICATED', detail: 'ended' }),
      );
      await parts.fixture.componentInstance.refreshBill();
      parts.fixture.detectChanges();

      expect(parts.dineIn.clear).toHaveBeenCalled();
      expect(parts.host.querySelector('[data-testid="dine-in-claim-lapsed"]')).toBeNull();
      expect(parts.host.querySelector('[data-testid="dine-in-no-admission"]')).not.toBeNull();
    });

    it('keeps reading while the platform has not decided: the sweeper may not have run when the window passes', async () => {
      const tick = claimClock();

      const parts = await render();
      const claimExpiresAt = new Date(Date.now() + 60_000).toISOString();
      platformSeats(parts.dineIn, seatingOf({ claimExpiresAt }));
      await press(parts, 'dine-in-sit-button');

      // The first read after the window lands before the sweeper has run (it runs every
      // 30 s, the page ticks every 15): the claim is still undecided, its expiry behind us.
      const windowPassed = Date.now() + 2 * 60_000;
      const clock = vi.spyOn(Date, 'now').mockReturnValue(windowPassed);
      const undecided = bill({ sessionId: 'session-new', confirmed: false, claimExpiresAt });
      parts.dineIn.bill.mockResolvedValue(undecided);
      await tick(parts);
      expect(parts.dineIn.bill).toHaveBeenCalledTimes(1);
      expect(parts.host.querySelector('[data-testid="dine-in-claim-ending"]')).not.toBeNull();
      expect(parts.host.querySelector('[data-testid="dine-in-request-bill"]')).toBeNull();

      // The sweeper then confirms the claim. The page must find out without a reload.
      clock.mockReturnValue(windowPassed + 15_000);
      parts.dineIn.bill.mockResolvedValue(
        bill({ sessionId: 'session-new', confirmed: true, claimExpiresAt: null, roundCount: 1 }),
      );
      await tick(parts);

      expect(parts.dineIn.bill).toHaveBeenCalledTimes(2);
      expect(parts.host.querySelector('[data-testid="dine-in-claim-ending"]')).toBeNull();
      expect(parts.host.querySelector('[data-testid="dine-in-request-bill"]')).not.toBeNull();

      // Decided: nothing left to watch, so the clock reads no more.
      clock.mockReturnValue(windowPassed + 5 * 60_000);
      await tick(parts);
      expect(parts.dineIn.bill).toHaveBeenCalledTimes(2);
    });

    it('finds the lapse on a later read when the first one still saw an undecided claim', async () => {
      const tick = claimClock();

      const parts = await render();
      realGuestTokenCheck(parts);
      const claimExpiresAt = new Date(Date.now() + 60_000).toISOString();
      platformSeats(parts.dineIn, seatingOf({ claimExpiresAt }));
      await press(parts, 'dine-in-sit-button');

      const windowPassed = Date.now() + 2 * 60_000;
      const clock = vi.spyOn(Date, 'now').mockReturnValue(windowPassed);
      parts.dineIn.bill.mockResolvedValueOnce(
        bill({ sessionId: 'session-new', confirmed: false, claimExpiresAt }),
      );
      await tick(parts);
      expect(parts.dineIn.bill).toHaveBeenCalledTimes(1);

      clock.mockReturnValue(windowPassed + 15_000);
      parts.dineIn.bill.mockRejectedValue(
        new HorecaOSApiError({ status: 401, code: 'UNAUTHENTICATED', detail: 'ended' }),
      );
      await tick(parts);

      expect(parts.dineIn.bill).toHaveBeenCalledTimes(2);
      expect(parts.host.querySelector('[data-testid="dine-in-claim-lapsed"]')).not.toBeNull();
    });

    it('backs off while the claim stays undecided, rather than reading on every tick', async () => {
      const tick = claimClock();

      const parts = await render();
      const claimExpiresAt = new Date(Date.now() + 60_000).toISOString();
      platformSeats(parts.dineIn, seatingOf({ claimExpiresAt }));
      await press(parts, 'dine-in-sit-button');

      const t0 = Date.now() + 2 * 60_000;
      const clock = vi.spyOn(Date, 'now').mockReturnValue(t0);
      parts.dineIn.bill.mockResolvedValue(
        bill({ sessionId: 'session-new', confirmed: false, claimExpiresAt }),
      );
      await tick(parts);
      expect(parts.dineIn.bill).toHaveBeenCalledTimes(1);

      clock.mockReturnValue(t0 + 15_000);
      await tick(parts);
      expect(parts.dineIn.bill).toHaveBeenCalledTimes(2);

      // 15 s after the second read is too soon for the third (the gap has doubled to 30 s).
      clock.mockReturnValue(t0 + 30_000);
      await tick(parts);
      expect(parts.dineIn.bill).toHaveBeenCalledTimes(2);

      clock.mockReturnValue(t0 + 45_000);
      await tick(parts);
      expect(parts.dineIn.bill).toHaveBeenCalledTimes(3);

      // The gap stops growing at a minute, so a claim held back by a slow payment is
      // still found within a minute of the platform deciding.
      clock.mockReturnValue(t0 + 45_000 + 60_000);
      await tick(parts);
      expect(parts.dineIn.bill).toHaveBeenCalledTimes(4);
      clock.mockReturnValue(t0 + 45_000 + 60_000 + 60_000);
      await tick(parts);
      expect(parts.dineIn.bill).toHaveBeenCalledTimes(5);
    });
  });
});

describe('DineInTableComponent -- combos at the table (ADR 0136)', () => {
  const comboMenu = (): PublishedMenu => {
    const base = menu();
    return {
      ...base,
      categories: [{ ...base.categories[0], productIds: ['prod-lunch'] }],
      products: [
        {
          ...base.products[0],
          productId: 'prod-lunch',
          name: 'Lunch box',
          variants: [{ ...base.products[0].variants[0], variantId: 'v-lunch', amountMinor: null }],
          comboGroupIds: ['g-main'],
        },
      ],
      comboGroups: [
        {
          comboGroupId: 'g-main',
          containerVariantId: 'v-lunch',
          code: 'MAIN',
          name: 'Main',
          minimumSelections: 1,
          maximumSelections: 1,
          allowSameComponentMultipleTimes: false,
          sortOrder: 0,
          components: [
            {
              componentId: 'c-burger',
              variantId: 'burger-v',
              productId: null,
              name: 'Burger',
              variantName: null,
              defaultQuantity: 1,
              sortOrder: 0,
              orderable: true,
              amountMinor: 25_000,
            },
            {
              componentId: 'c-wrap',
              variantId: 'wrap-v',
              productId: null,
              name: 'Wrap',
              variantName: null,
              defaultQuantity: 1,
              sortOrder: 1,
              orderable: true,
              amountMinor: 22_000,
            },
          ],
        },
      ],
      modifierGroups: [
        {
          modifierGroupId: 'g-box',
          code: 'BOX',
          name: 'Box',
          required: true,
          minimumSelections: 1,
          maximumSelections: 1,
          allowSameOptionMultipleTimes: false,
          options: [
            {
              optionId: 'o-box',
              code: 'BOX',
              maximumQuantity: 1,
              amountMinor: 2_000,
              name: 'Table service',
            },
          ],
        },
      ],
    };
  };

  async function seated() {
    const harness = setUp();
    harness.dineIn.seed(admission());
    harness.dineIn.bill.mockResolvedValue(bill());
    harness.menuService.menu.mockResolvedValue(comboMenu());
    harness.fixture.detectChanges();
    await flush();
    harness.fixture.detectChanges();
    return harness;
  }

  it('offers a combo as a choice to make, not as a dish with a price and a plus', async () => {
    const { fixture } = await seated();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="dine-in-combo-choose"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="dine-in-add"]')).toBeNull();
    expect(host.querySelector('.table__item-price')?.textContent).toContain('dineIn.comboChoose');
  });

  it('opens the combo’s choices, and adds the container with the picks once they are complete', async () => {
    const { fixture, cartService } = await seated();
    const host = fixture.nativeElement as HTMLElement;
    cartService.cart.set({
      cartId: 'cart-1',
      locationId: 'location-1',
      status: 'OPEN',
      currency: 'UZS',
      fulfillmentMode: 'DINE_IN',
      version: 1,
      quoteId: null,
      contextHash: null,
      expiresAt: null,
      lines: [],
    });
    cartService.ensure.mockResolvedValue(cartService.cart());
    cartService.putLine.mockResolvedValue(cartService.cart());

    (host.querySelector('[data-testid="dine-in-combo-choose"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const add = () => host.querySelector('[data-testid="dine-in-combo-add"]') as HTMLButtonElement;
    expect(add().disabled).toBe(true);

    (host.querySelectorAll('[data-testid="combo-radio"]')[1] as HTMLInputElement).click();
    fixture.detectChanges();
    expect(add().disabled).toBe(false);
    add().click();
    await flush();

    expect(cartService.putLine).toHaveBeenCalledWith({
      variantId: 'v-lunch',
      quantity: 1,
      comboPicks: [{ componentId: 'c-wrap', quantity: 1 }],
    });
  });

  it('adds one more of a combo already in the basket with the same picks', async () => {
    const { fixture, cartService } = await seated();
    const host = fixture.nativeElement as HTMLElement;
    const withLine = {
      cartId: 'cart-1',
      locationId: 'location-1',
      status: 'OPEN',
      currency: 'UZS',
      fulfillmentMode: 'DINE_IN' as const,
      version: 2,
      quoteId: null,
      contextHash: null,
      expiresAt: null,
      lines: [
        {
          lineKey: 'v-lunchcabc',
          variantId: 'v-lunch',
          quantity: 2,
          commentPresetCodes: [],
          hasCustomerNote: false,
          comboPicks: [{ componentId: 'c-wrap', quantity: 1 }],
        },
      ],
    };
    cartService.cart.set(withLine);
    cartService.ensure.mockResolvedValue(withLine);
    cartService.putLine.mockResolvedValue(withLine);

    (host.querySelector('[data-testid="dine-in-combo-choose"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (host.querySelectorAll('[data-testid="combo-radio"]')[1] as HTMLInputElement).click();
    fixture.detectChanges();
    (host.querySelector('[data-testid="dine-in-combo-add"]') as HTMLButtonElement).click();
    await flush();

    expect(cartService.putLine).toHaveBeenCalledWith(
      expect.objectContaining({ variantId: 'v-lunch', quantity: 3 }),
    );
  });

  it('lists the basket’s lines and, under a combo, the components it will become', async () => {
    const { fixture, cartService } = await seated();
    cartService.cart.set({
      cartId: 'cart-1',
      locationId: 'location-1',
      status: 'OPEN',
      currency: 'UZS',
      fulfillmentMode: 'DINE_IN',
      version: 2,
      quoteId: null,
      contextHash: null,
      expiresAt: null,
      lines: [
        {
          lineKey: 'v-lunchcabc',
          variantId: 'v-lunch',
          quantity: 2,
          commentPresetCodes: [],
          hasCustomerNote: false,
          comboPicks: [{ componentId: 'c-wrap', quantity: 1 }],
        },
      ],
    });
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    const line = host.querySelector('[data-testid="dine-in-basket-line"]') as HTMLElement;
    expect(line.textContent).toContain('Lunch box');
    expect(line.textContent).toContain('×2');
    expect(host.querySelector('[data-testid="dine-in-basket-combo"]')?.textContent).toContain(
      'Wrap',
    );
  });

  it('itemises a charge the server added to the table’s basket, named from the menu, already in the total', async () => {
    const { fixture, cartService } = await seated();
    const cart = {
      cartId: 'cart-1',
      locationId: 'location-1',
      status: 'OPEN',
      currency: 'UZS',
      fulfillmentMode: 'DINE_IN' as const,
      version: 3,
      quoteId: null,
      contextHash: null,
      expiresAt: null,
      lines: [
        {
          lineKey: 'v-lunchcabc',
          variantId: 'v-lunch',
          quantity: 1,
          commentPresetCodes: [],
          hasCustomerNote: false,
          comboPicks: [{ componentId: 'c-wrap', quantity: 1 }],
        },
      ],
    };
    cartService.price.mockResolvedValue({
      cartId: 'cart-1',
      cartVersion: 3,
      quoteId: 'q',
      contextHash: 'h',
      currency: 'UZS',
      subtotalMinor: 22_000,
      taxMinor: 0,
      discountMinor: 0,
      feeMinor: 0,
      totalMinor: 24_000,
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      delivery: null,
      hiddenCharges: [
        { lineKey: 'v-lunchcabc~0', optionId: 'o-box', amountMinor: 2_000 },
        { lineKey: 'v-lunchcabc~1', optionId: 'o-box', amountMinor: 2_000 },
      ],
    });
    cartService.cart.set(cart);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    const charge = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="dine-in-hidden-charge"]',
    );
    expect(charge?.textContent).toContain('Table service');
    // One row per option, the amount summed over the lines it was applied to.
    expect(charge?.textContent).toMatch(/4.000/);
  });
});

describe('DineInTableComponent -- portions and weighed items (ADR 0137)', () => {
  const NBSP = ' ';

  function menuWith(physical: unknown, amountMinor = 45000): PublishedMenu {
    const base = menu();
    return {
      ...base,
      products: [
        {
          ...base.products[0],
          variants: [
            {
              ...base.products[0].variants[0],
              amountMinor,
              physical,
            } as PublishedMenu['products'][number]['variants'][number],
          ],
        },
      ],
    };
  }

  async function open(physical: unknown, quantity?: number) {
    const parts = setUp();
    parts.dineIn.seed(admission());
    parts.dineIn.bill.mockResolvedValue(bill());
    parts.menuService.menu.mockResolvedValue(menuWith(physical));
    if (quantity !== undefined) {
      parts.cartService.cart.set({
        cartId: 'cart-1',
        locationId: 'location-1',
        status: 'OPEN',
        currency: 'UZS',
        fulfillmentMode: 'DINE_IN',
        version: 1,
        quoteId: null,
        contextHash: null,
        expiresAt: null,
        lines: [
          {
            lineKey: 'variant-1',
            variantId: 'variant-1',
            quantity,
            commentPresetCodes: [],
            hasCustomerNote: false,
          },
        ],
      });
    }
    parts.cartService.putLine.mockImplementation(async () => parts.cartService.cart());
    parts.cartService.price.mockResolvedValue({
      cartId: 'cart-1',
      cartVersion: 1,
      quoteId: 'q',
      contextHash: 'h',
      currency: 'UZS',
      subtotalMinor: 0,
      taxMinor: 0,
      discountMinor: 0,
      feeMinor: 0,
      totalMinor: 0,
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      delivery: null,
    });
    parts.fixture.detectChanges();
    await flush();
    parts.fixture.detectChanges();
    const host = parts.fixture.nativeElement as HTMLElement;
    const click = async (testId: string) => {
      host.querySelector<HTMLButtonElement>(`[data-testid="${testId}"]`)?.click();
      await flush();
      parts.fixture.detectChanges();
      await flush();
    };
    return { ...parts, host, click };
  }

  it('starts a splittable item at one whole portion and steps it by its portion size', async () => {
    const { cartService, click } = await open({
      catchweight: false,
      splittable: true,
      portionSize: 0.5,
    });

    await click('dine-in-add');

    expect(cartService.putLine).toHaveBeenCalledWith(
      expect.objectContaining({ variantId: 'variant-1', quantity: 1 }),
    );
  });

  it('adds one portion more to a line already in the basket', async () => {
    const { cartService, click } = await open(
      { catchweight: false, splittable: true, portionSize: 0.5 },
      1,
    );

    await click('dine-in-increase');

    expect(cartService.putLine).toHaveBeenCalledWith(expect.objectContaining({ quantity: 1.5 }));
  });

  it('takes one portion off, and the last portion off removes the line', async () => {
    const { cartService, click } = await open(
      { catchweight: false, splittable: true, portionSize: 0.5 },
      0.5,
    );

    await click('dine-in-decrease');

    expect(cartService.removeLine).toHaveBeenCalledWith('variant-1');
  });

  it('keeps a plain item in whole units, as before', async () => {
    const { cartService, click } = await open(null, 2);

    await click('dine-in-increase');

    expect(cartService.putLine).toHaveBeenCalledWith(expect.objectContaining({ quantity: 3 }));
  });

  it('writes a half portion with the language’s decimal mark', async () => {
    const { host } = await open({ catchweight: false, splittable: true, portionSize: 0.5 }, 0.5);

    expect(host.querySelector('[data-testid="dine-in-quantity"]')?.textContent?.trim()).toBe('0.5');
  });

  it('prices a weighed item per quantum on the menu, and shows its weight', async () => {
    const { host } = await open({
      catchweight: true,
      catchweightQuantumGrams: 100,
      catchweightNominalGrams: 1_200,
      splittable: false,
      netWeightGrams: 1_200,
    });

    const item = host.querySelector('[data-testid="dine-in-menu-item"]')!;
    expect(item.textContent).toContain('physical.pricePerQuantum');
    expect(item.textContent).toContain(`1.2${NBSP}kg`);
  });

  it('shows a fixed unit’s price as before', async () => {
    const { host } = await open(null);

    expect(host.querySelector('.table__item-price')?.textContent).toContain('45');
    expect(host.querySelector('.table__item-price')?.textContent).not.toContain('physical');
  });
});
