import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { ReturnDestination } from '../../../core/auth/return-destination';
import { Session } from '../../../core/auth/session';
import { CartService, type PlatformCart } from '../../../services/cart.service';
import { DineInAdmission, DineInBill, DineInService } from '../../../services/dine-in.service';
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
  isGuestSessionEnded = vi.fn().mockReturnValue(false);
  clear = vi.fn(() => this.admissionSig.set(null));

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
  paymentMethods = vi.fn().mockResolvedValue({ cartId: 'cart-1', currency: 'UZS', methodCodes: [], warnings: [] });
  checkout = vi.fn();
  discard = vi.fn();
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

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
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
        lines: [{ lineKey: 'variant-1', variantId: 'variant-1', quantity: 1, commentPresetCodes: [], hasCustomerNote: false }],
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

      const checkoutButton = host.querySelector<HTMLButtonElement>('[data-testid="dine-in-checkout"]');
      expect(checkoutButton).not.toBeNull();
      checkoutButton?.click();
      await flush();
      fixture.detectChanges();

      expect(cartService.checkout).toHaveBeenCalledWith(
        expect.objectContaining({ paymentMethodCode: 'CASH' }),
      );
      expect(dineIn.attachRound).toHaveBeenCalledWith('session-1', 'order-1');
      expect(cartService.discard).toHaveBeenCalledWith('location-1');
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
