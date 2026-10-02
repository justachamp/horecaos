import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { DineInTableComponent } from './dine-in-table.component';
import { ApiClient } from '../../../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../../../core/config/app-config';
import { ReturnDestination } from '../../../core/auth/return-destination';
import { Session } from '../../../core/auth/session';
import { HorecaOSApiError } from '../../../core/api/problem-details';
import {
  lineKeyFor,
  type PlatformCart,
  type PlatformCartLine,
  type PricedCart,
} from '../../../services/cart.service';
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
import type {
  CustomerUiResponse,
  MenuItem,
  MenuItemModifierGroup,
  MenuItemVariant,
} from '../../../types/home.types';

const LOCATION = 'location-1';
const SESSION = 'session-1';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}(${JSON.stringify(params)})` : key;
  current = (): Record<string, unknown> => ({});
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
  admission = () => this.admissionSig();
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
  /** The real service hands the cart the guest token as a header; the fake hands it a stand-in. */
  bindCartToTable = vi.fn(
    async (carts: {
      bindTable(headers: Readonly<Record<string, string>>): Promise<PlatformCart>;
    }) => carts.bindTable({ 'X-Dine-In-Token': 'guest-token' }),
  );
  isGuestSessionEnded = vi.fn().mockReturnValue(false);
  clear = vi.fn(() => this.admissionSig.set(null));

  seed(value: DineInAdmission | null): void {
    this.admissionSig.set(value);
  }
}

/** An in-memory stand-in for the table basket: it keeps lines and bumps a version, like the platform. */
class FakeCartService {
  readonly cart = signal<PlatformCart | null>(null);
  private version = 1;
  private preloaded: readonly PlatformCartLine[] | null = null;

  preload(lines: readonly PlatformCartLine[]): void {
    this.preloaded = lines;
  }

  private open(lines: readonly PlatformCartLine[]): PlatformCart {
    const cart = cartOf(lines, this.version);
    this.cart.set(cart);
    return cart;
  }

  ensure = vi.fn(async (_location: string, _mode: string, create = true) => {
    if (this.cart()) {
      return this.cart();
    }
    if (this.preloaded) {
      return this.open(this.preloaded);
    }
    return create ? this.open([]) : null;
  });

  /** What the platform's quote says it added by itself (ADR 0136); empty unless a test sets it. */
  hiddenCharges: { lineKey: string; optionId: string; amountMinor: number }[] = [];

  putLine = vi.fn(
    async (input: {
      variantId: string;
      quantity: number;
      modifierOptionIds?: readonly string[];
      comboPicks?: readonly { componentId: string; quantity: number }[];
    }) => {
      // Keyed as the real service keys it: the variant and its exact selection.
      const lineKey = lineKeyFor(input.variantId, input.modifierOptionIds ?? [], input.comboPicks);
      const held = this.cart()?.lines ?? [];
      const line: PlatformCartLine = {
        lineKey,
        variantId: input.variantId,
        quantity: input.quantity,
        hasCustomerNote: false,
        ...(input.comboPicks ? { comboPicks: input.comboPicks } : {}),
      };
      this.version++;
      return this.open(
        held.some((entry) => entry.lineKey === lineKey)
          ? held.map((entry) => (entry.lineKey === lineKey ? line : entry))
          : [...held, line],
      );
    },
  );

  removeLine = vi.fn(async (lineKey: string) => {
    this.version++;
    return this.open((this.cart()?.lines ?? []).filter((line) => line.lineKey !== lineKey));
  });

  price = vi.fn(async (): Promise<PricedCart> => {
    const cart = this.cart()!;
    const count = cart.lines.reduce((sum, line) => sum + line.quantity, 0);
    return {
      cartId: cart.cartId,
      cartVersion: cart.version,
      quoteId: 'quote-1',
      contextHash: 'hash-1',
      currency: 'UZS',
      subtotalMinor: 45_000 * count,
      taxMinor: 0,
      totalMinor: 45_000 * count,
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      discountMinor: 0,
      ...(this.hiddenCharges.length > 0 ? { hiddenCharges: this.hiddenCharges } : {}),
    };
  });

  paymentMethods = vi.fn(async () => ({
    cartId: 'cart-1',
    currency: 'UZS',
    methodCodes: ['CASH'],
    warnings: [],
  }));

  checkout = vi.fn(
    async (_input?: { priced: PricedCart; paymentMethodCode: string; idempotencyKey: string }) => ({
      orderId: 'order-1',
      publicOrderNumber: '0001',
      status: 'CONFIRMED',
      version: 1,
      outcome: 'CREATED',
      warnings: [],
    }),
  );

  bindTable = vi.fn(async (_headers: Readonly<Record<string, string>>) => {
    this.version++;
    return this.open(this.cart()?.lines ?? []);
  });

  clear = vi.fn(async () => {
    this.version++;
    return this.open([]);
  });

  discard = vi.fn((_location: string, _scope?: string) => {
    this.cart.set(null);
    this.preloaded = null;
  });
}

function cartOf(lines: readonly PlatformCartLine[], version = 1): PlatformCart {
  return {
    cartId: 'cart-1',
    locationId: LOCATION,
    status: 'OPEN',
    currency: 'UZS',
    fulfillmentMode: 'DINE_IN',
    version,
    quoteId: null,
    contextHash: null,
    expiresAt: null,
    lines,
  };
}

function line(variantId: string, quantity: number): PlatformCartLine {
  return { lineKey: variantId, variantId, quantity, hasCustomerNote: false };
}

/** A line ordered with options: the platform keys it by the variant and the exact selection. */
function chosenLine(
  variantId: string,
  optionIds: readonly string[],
  quantity: number,
): PlatformCartLine {
  return { lineKey: lineKeyFor(variantId, optionIds), variantId, quantity, hasCustomerNote: false };
}

class FakeMenuService {
  readonly currency = signal<string | null>('UZS');
  readonly optionLabels = signal<ReadonlyMap<string, string>>(new Map());
  home = vi.fn<(...args: unknown[]) => Promise<CustomerUiResponse>>();
}

class FakePaymentSessionService {
  open = vi.fn();
}

function variant(overrides: Partial<MenuItemVariant> = {}): MenuItemVariant {
  return {
    id: 'variant-1',
    name: '',
    active: true,
    onSaleNow: true,
    preparation_time: 0,
    price: 45_000,
    price_without_discount: 45_000,
    remainingQuantity: null,
    ...overrides,
  };
}

function dish(
  id: string,
  name: string,
  variants: MenuItemVariant[] = [variant()],
  modifierGroups: MenuItemModifierGroup[] = [],
): MenuItem {
  return {
    id,
    name,
    description: '',
    active: variants.some((entry) => entry.active),
    has_discount: false,
    preparation_time: 0,
    price: variants[0]?.price ?? 0,
    price_without_discount: variants[0]?.price ?? 0,
    image: null,
    start: null,
    finish: null,
    discount: null,
    is_favourite: false,
    delivery_duration: 0,
    variants,
    modifierGroups,
  };
}

function modifierGroup(overrides: Partial<MenuItemModifierGroup> = {}): MenuItemModifierGroup {
  return {
    id: 'size',
    name: 'Size',
    required: true,
    minimumSelections: 1,
    maximumSelections: 1,
    allowSameOptionMultipleTimes: false,
    options: [
      { id: 'opt-small', label: 'Small', amountMinor: null, maximumQuantity: 1 },
      { id: 'opt-large', label: 'Large', amountMinor: 8_000, maximumQuantity: 1 },
    ],
    ...overrides,
  };
}

/** A menu whose one dish must be chosen from: Osh, whose Size is required. */
function menuWithRequiredSize(extra: Partial<MenuItemModifierGroup>[] = []): CustomerUiResponse {
  return menu([
    dish(
      'p1',
      'Osh',
      [variant()],
      [modifierGroup(), ...extra.map((entry) => modifierGroup(entry))],
    ),
  ]);
}

function menu(items: MenuItem[] = [dish('p1', 'Osh')]): CustomerUiResponse {
  return {
    category: null,
    offer: null,
    populars: [],
    populars_count: 0,
    menu: {
      categories: [{ id: 'c1', name: 'Main' }],
      category_items: [{ id: 'c1', name: 'Main', items, items_count: items.length }],
      category_items_count: 1,
    },
  };
}

function admission(overrides: Partial<DineInAdmission> = {}): DineInAdmission {
  return {
    expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    mode: 'ORDER_AND_PAY',
    tenantId: 'tenant-1',
    brandId: 'brand-1',
    locationId: LOCATION,
    tableCode: 'T7',
    openSessionId: SESSION,
    channelCode: 'QRTABLE',
    ...overrides,
  };
}

function bill(overrides: Partial<DineInBill> = {}): DineInBill {
  return {
    sessionId: SESSION,
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
  const carts = new FakeCartService();
  const menuService = new FakeMenuService();
  const session = new FakeSession();
  const payments = new FakePaymentSessionService();
  menuService.home.mockResolvedValue(menu());
  dineIn.bill.mockResolvedValue(bill());

  TestBed.configureTestingModule({
    imports: [DineInTableComponent],
    providers: [
      provideRouter([]),
      { provide: DineInService, useValue: dineIn },
      { provide: DineInCartService, useValue: carts },
      { provide: MenuService, useValue: menuService },
      { provide: Session, useValue: session },
      { provide: PaymentSessionService, useValue: payments },
      { provide: LangService, useValue: { langId: () => 'uz' } },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(DineInTableComponent);
  const router = TestBed.inject(Router);
  const host = fixture.nativeElement as HTMLElement;
  return {
    fixture,
    host,
    dineIn,
    carts,
    menuService,
    session,
    payments,
    router,
    q: (testId: string) => host.querySelector<HTMLElement>(`[data-testid="${testId}"]`),
    all: (testId: string) =>
      Array.from(host.querySelectorAll<HTMLElement>(`[data-testid="${testId}"]`)),
    click: async (testId: string) => {
      host.querySelector<HTMLElement>(`[data-testid="${testId}"]`)?.click();
      await settle(fixture);
    },
  };
}

type View = ReturnType<typeof setUp>;

async function settle(fixture: View['fixture']): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

function optionButton(view: View, label: string): HTMLElement {
  const found = view.all('modifier-option').find((entry) => entry.textContent?.includes(label));
  expect(found, `no option labelled ${label} in the picker`).toBeDefined();
  return found!;
}

/** Taps an option in the open picker. */
function pick(view: View, label: string): void {
  optionButton(view, label).click();
  view.fixture.detectChanges();
}

function refusal(reason: string): HorecaOSApiError {
  return new HorecaOSApiError({
    status: 422,
    code: 'VALIDATION_FAILED',
    detail: 'refused',
    problem: { status: 422, code: 'VALIDATION_FAILED', reason },
  });
}

describe('DineInTableComponent', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('asks the visitor to scan a table when nothing has been scanned this visit', async () => {
    const view = setUp();

    await settle(view.fixture);

    expect(view.q('dine-in-no-admission')).not.toBeNull();
    expect(view.q('dish-card')).toBeNull();
    expect(view.menuService.home).not.toHaveBeenCalled();
    expect(view.carts.ensure).not.toHaveBeenCalled();
  });

  describe('VIEW_ONLY -- the menu-only mode', () => {
    it("shows the table and its menu, read on the table's own location and QR channel", async () => {
      const view = setUp();
      view.dineIn.seed(admission({ mode: 'VIEW_ONLY', openSessionId: null }));
      view.menuService.home.mockResolvedValue(menu([dish('p1', 'Osh'), dish('p2', 'Norin')]));

      await settle(view.fixture);

      // The branch and channel come from the admission, never from this build's
      // own config: a table's QR_TABLE channel is resolved per scan.
      expect(view.menuService.home).toHaveBeenCalledWith('uz', LOCATION, 'QRTABLE');
      expect(view.q('dine-in-table-code')?.textContent).toContain('"code":"T7"');
      expect(view.all('dish-card').length).toBe(2);
      expect(view.host.textContent).toContain('Norin');
    });

    it('offers no way to order: no link into the delivery product page, no control, no basket, no bill, no sign-in prompt', async () => {
      const view = setUp();
      view.dineIn.seed(admission({ mode: 'VIEW_ONLY', openSessionId: null }));

      await settle(view.fixture);

      const card = view.q('dish-card')!;
      expect(card.tagName).not.toBe('A');
      expect(card.getAttribute('href')).toBeNull();
      expect(view.host.querySelector('a[href^="/product"]')).toBeNull();
      expect(view.q('dine-in-add')).toBeNull();
      expect(view.q('dine-in-order')).toBeNull();
      expect(view.q('dine-in-bill')).toBeNull();
      expect(view.q('dine-in-signin')).toBeNull();
      expect(view.q('dine-in-not-seated')).toBeNull();
    });

    it("touches neither the basket, the bill, nor the platform's ordering calls", async () => {
      const view = setUp();
      view.dineIn.seed(admission({ mode: 'VIEW_ONLY', openSessionId: null }));

      await settle(view.fixture);

      expect(view.carts.ensure).not.toHaveBeenCalled();
      expect(view.dineIn.bill).not.toHaveBeenCalled();
      expect(view.dineIn.flushPendingRounds).not.toHaveBeenCalled();
    });

    it('even a session id on a menu-only admission opens nothing', async () => {
      const view = setUp();
      view.dineIn.seed(admission({ mode: 'VIEW_ONLY', openSessionId: SESSION }));

      await settle(view.fixture);

      expect(view.q('dine-in-add')).toBeNull();
      expect(view.carts.ensure).not.toHaveBeenCalled();
      expect(view.dineIn.bill).not.toHaveBeenCalled();
    });

    it('marks a sold-out dish and a dish outside its sale window, so a guest is not shown a dish they cannot have as if they could', async () => {
      const view = setUp();
      view.dineIn.seed(admission({ mode: 'VIEW_ONLY', openSessionId: null }));
      view.menuService.home.mockResolvedValue(
        menu([
          dish('p1', 'Osh', [variant({ active: false })]),
          dish('p2', 'Nonushta', [variant({ onSaleNow: false })]),
          dish('p3', 'Norin'),
        ]),
      );

      await settle(view.fixture);

      expect(view.all('dish-sold-out').length).toBe(1);
      expect(view.all('dish-out-of-window').length).toBe(1);
    });

    it("omits the channel when the tenant registered none, so the build's own channel applies", async () => {
      const view = setUp();
      view.dineIn.seed(admission({ mode: 'VIEW_ONLY', openSessionId: null, channelCode: null }));

      await settle(view.fixture);

      expect(view.menuService.home).toHaveBeenCalledWith('uz', LOCATION, undefined);
    });

    it('says the menu is unavailable when it cannot be read, instead of an empty page', async () => {
      const view = setUp();
      view.dineIn.seed(admission({ mode: 'VIEW_ONLY', openSessionId: null }));
      view.menuService.home.mockRejectedValue(new Error('offline'));

      await settle(view.fixture);

      expect(view.q('dine-in-menu-unavailable')).not.toBeNull();
      expect(view.q('dish-card')).toBeNull();
    });

    it('says the menu is unavailable when the branch has no dishes', async () => {
      const view = setUp();
      view.dineIn.seed(admission({ mode: 'VIEW_ONLY', openSessionId: null }));
      view.menuService.home.mockResolvedValue(menu([]));

      await settle(view.fixture);

      expect(view.q('dine-in-menu-unavailable')).not.toBeNull();
    });
  });

  describe('ORDER_AND_PAY at a table nobody has seated', () => {
    it('shows the menu with an explanation instead of ordering controls, and opens nothing', async () => {
      const view = setUp();
      view.dineIn.seed(admission({ openSessionId: null }));

      await settle(view.fixture);

      expect(view.q('dine-in-not-seated')).not.toBeNull();
      expect(view.q('dish-card')).not.toBeNull();
      expect(view.q('dine-in-add')).toBeNull();
      expect(view.q('dine-in-order')).toBeNull();
      expect(view.q('dine-in-signin')).toBeNull();
      expect(view.carts.ensure).not.toHaveBeenCalled();
      expect(view.dineIn.bill).not.toHaveBeenCalled();
    });
  });

  describe('ORDER_AND_PAY, seated, signed out', () => {
    it('shows the menu with add controls and a way to sign in, and touches no cart', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.session.setAuthenticated(false);

      await settle(view.fixture);

      expect(view.q('dine-in-add')).not.toBeNull();
      expect(view.q('dine-in-signin')).not.toBeNull();
      expect(view.carts.ensure).not.toHaveBeenCalled();
    });

    it('sends a tap on add straight to sign-in without touching the basket', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.session.setAuthenticated(false);
      const navigate = vi.spyOn(view.router, 'navigate').mockResolvedValue(true);

      await settle(view.fixture);
      await view.click('dine-in-add');

      expect(navigate).toHaveBeenCalledWith(['/auth', 'login']);
      expect(view.carts.ensure).not.toHaveBeenCalled();
      expect(view.carts.putLine).not.toHaveBeenCalled();
    });

    it('sends a tap on Choose to sign-in as well, and opens no picker', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.menuService.home.mockResolvedValue(menuWithRequiredSize());
      view.session.setAuthenticated(false);
      const navigate = vi.spyOn(view.router, 'navigate').mockResolvedValue(true);

      await settle(view.fixture);
      await view.click('dine-in-choose');

      expect(navigate).toHaveBeenCalledWith(['/auth', 'login']);
      expect(view.q('modifier-picker')).toBeNull();
      expect(view.carts.ensure).not.toHaveBeenCalled();
    });

    describe('returning to the table after sign-in', () => {
      it('remembers /dine-in/table when the sign-in button is tapped', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.session.setAuthenticated(false);
        const navigate = vi.spyOn(view.router, 'navigate').mockResolvedValue(true);

        await settle(view.fixture);
        await view.click('dine-in-signin');

        expect(navigate).toHaveBeenCalledWith(['/auth', 'login']);
        expect(TestBed.inject(ReturnDestination).consume()).toBe('/dine-in/table');
      });

      it('remembers it when the guest taps add while signed out, too', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.session.setAuthenticated(false);
        vi.spyOn(view.router, 'navigate').mockResolvedValue(true);

        await settle(view.fixture);
        await view.click('dine-in-add');

        expect(TestBed.inject(ReturnDestination).consume()).toBe('/dine-in/table');
      });

      it('carries nothing but the path: no query, no state, no token', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.session.setAuthenticated(false);
        const navigate = vi.spyOn(view.router, 'navigate').mockResolvedValue(true);

        await settle(view.fixture);
        await view.click('dine-in-signin');

        expect(navigate).toHaveBeenCalledTimes(1);
        expect(navigate.mock.calls[0]).toEqual([['/auth', 'login']]);
        const stored = Array.from({ length: sessionStorage.length }, (_, i) =>
          sessionStorage.getItem(sessionStorage.key(i) ?? ''),
        ).join('|');
        expect(stored).toContain('/dine-in/table');
        expect(stored).not.toContain('printed');
      });
    });

    it("still shows the running bill: it is the table's, read with the guest token alone", async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.session.setAuthenticated(false);
      view.dineIn.bill.mockResolvedValue(bill({ totalMinor: 90_000, roundCount: 2 }));

      await settle(view.fixture);

      expect(view.dineIn.bill).toHaveBeenCalledWith(SESSION);
      expect(view.q('dine-in-bill-total')?.textContent).toContain('90 000');
    });
  });

  describe('ORDER_AND_PAY, seated, signed in', () => {
    it("resumes the table: the menu, the running bill, and the table's own basket -- opened only if it exists", async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.dineIn.bill.mockResolvedValue(bill({ totalMinor: 45_000, roundCount: 1 }));

      await settle(view.fixture);

      expect(view.dineIn.bill).toHaveBeenCalledWith(SESSION);
      expect(view.q('dine-in-bill-total')?.textContent).toContain('45 000');
      expect(view.q('dine-in-bill-rounds')?.textContent).toContain('"count":1');
      // A browse mints no cart: create=false. The cart is the table's own --
      // DINE_IN, on the table's QR channel, remembered against its session.
      expect(view.carts.ensure).toHaveBeenCalledWith(
        LOCATION,
        'DINE_IN',
        false,
        'QRTABLE',
        SESSION,
      );
      expect(view.q('dine-in-order')).toBeNull();
    });

    it('shows what the basket already holds and prices it', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.carts.preload([line('variant-1', 2)]);

      await settle(view.fixture);

      expect(view.q('dine-in-quantity')?.textContent).toContain('2');
      expect(view.q('dine-in-cart-count')?.textContent).toContain('"count":2');
      expect(view.q('dine-in-cart-total')?.textContent).toContain('90 000');
      expect(view.carts.price).toHaveBeenCalled();
    });

    it('adds a dish: opens the basket, puts the line, prices it, and offers the order', async () => {
      const view = setUp();
      view.dineIn.seed(admission());

      await settle(view.fixture);
      await view.click('dine-in-add');

      expect(view.carts.ensure).toHaveBeenLastCalledWith(
        LOCATION,
        'DINE_IN',
        true,
        'QRTABLE',
        SESSION,
      );
      expect(view.carts.putLine).toHaveBeenCalledWith({ variantId: 'variant-1', quantity: 1 });
      expect(view.carts.price).toHaveBeenCalled();
      expect(view.q('dine-in-cart-count')?.textContent).toContain('"count":1');
      expect(view.q('dine-in-cart-total')?.textContent).toContain('45 000');
      expect((view.q('dine-in-checkout') as HTMLButtonElement).disabled).toBe(false);
    });

    it('steps a dish up and down, and taking the last one out removes the line and the order panel', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      await settle(view.fixture);
      await view.click('dine-in-add');

      await view.click('dine-in-increase');
      expect(view.carts.putLine).toHaveBeenLastCalledWith({ variantId: 'variant-1', quantity: 2 });
      expect(view.q('dine-in-cart-total')?.textContent).toContain('90 000');

      await view.click('dine-in-decrease');
      expect(view.carts.putLine).toHaveBeenLastCalledWith({ variantId: 'variant-1', quantity: 1 });

      await view.click('dine-in-decrease');
      expect(view.carts.removeLine).toHaveBeenCalledWith('variant-1');
      expect(view.q('dine-in-order')).toBeNull();
      expect(view.q('dine-in-add')).not.toBeNull();
    });

    it('takes one write at a time: a second tap while the first is in flight is not sent', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      await settle(view.fixture);
      let release!: () => void;
      const gate = new Promise<void>((resolve) => (release = resolve));
      const original = view.carts.putLine.getMockImplementation()!;
      view.carts.putLine.mockImplementation(async (input) => {
        await gate;
        return original(input);
      });

      view.q('dine-in-add')!.click();
      await Promise.resolve();
      view.fixture.detectChanges();
      view.q('dine-in-add')?.click();
      release();
      await settle(view.fixture);

      expect(view.carts.putLine).toHaveBeenCalledTimes(1);
    });

    it('when the platform refuses the dish, says why and leaves the basket as it was', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      await settle(view.fixture);
      view.carts.putLine.mockRejectedValue(
        new HorecaOSApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          detail: 'closed',
          problem: { status: 422, code: 'UNPROCESSABLE_STATE', reason: 'ITEM_OUT_OF_SALE_WINDOW' },
        }),
      );

      await view.click('dine-in-add');

      expect(view.q('dine-in-basket-error')?.textContent).toContain(
        'errors.reason.itemOutOfSaleWindow',
      );
      expect(view.q('dine-in-order')).toBeNull();
      expect(view.q('dine-in-add')).not.toBeNull();
    });

    it('a basket the platform will not price says why, and cannot be ordered', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      await settle(view.fixture);
      view.carts.price.mockRejectedValue(
        new HorecaOSApiError({
          status: 422,
          code: 'VALIDATION_FAILED',
          detail: 'unpriced',
          problem: { status: 422, code: 'VALIDATION_FAILED', reason: 'ITEM_OUT_OF_SALE_WINDOW' },
        }),
      );

      await view.click('dine-in-add');

      expect(view.q('dine-in-pricing-error')?.textContent).toContain(
        'errors.reason.itemOutOfSaleWindow',
      );
      expect((view.q('dine-in-checkout') as HTMLButtonElement).disabled).toBe(true);
      // The total reads as unknown, never as free.
      expect(view.q('dine-in-cart-total')?.textContent).toContain('—');
    });

    describe('a dish with a required group of options', () => {
      async function seated(menuResponse: CustomerUiResponse = menuWithRequiredSize()) {
        const view = setUp();
        view.dineIn.seed(admission());
        view.menuService.home.mockResolvedValue(menuResponse);
        await settle(view.fixture);
        return view;
      }

      it('offers to choose its options in place of a plain add, and opens no basket until a choice is confirmed', async () => {
        const view = await seated();

        expect(view.q('dine-in-add')).toBeNull();
        expect(view.q('dine-in-needs-staff')).toBeNull();
        expect(view.q('dine-in-choose')).not.toBeNull();
        expect(view.q('modifier-picker')).toBeNull();

        await view.click('dine-in-choose');

        expect(view.q('modifier-picker-title')?.textContent).toContain('Osh');
        // Reading the basket on arrival never creates one; opening the picker does not either.
        expect(view.carts.ensure.mock.calls.every((call) => call[2] === false)).toBe(true);
        expect(view.carts.putLine).not.toHaveBeenCalled();
      });

      it('refuses to add until the required group is chosen, says which group is missing, and writes nothing', async () => {
        const view = await seated();
        await view.click('dine-in-choose');

        expect((view.q('modifier-picker-add') as HTMLButtonElement).disabled).toBe(true);
        expect(view.q('modifier-picker-missing')?.textContent).toContain('Size');

        await view.click('modifier-picker-add');

        expect(view.carts.putLine).not.toHaveBeenCalled();
        expect(view.q('modifier-picker')).not.toBeNull();
      });

      it('adds the dish with the options chosen: binds the basket, writes the line keyed by dish and selection, prices it', async () => {
        const view = await seated();
        await view.click('dine-in-choose');

        pick(view, 'Large');
        await view.click('modifier-picker-add');

        expect(view.carts.ensure).toHaveBeenLastCalledWith(
          LOCATION,
          'DINE_IN',
          true,
          'QRTABLE',
          SESSION,
        );
        expect(view.dineIn.bindCartToTable).toHaveBeenCalledTimes(1);
        expect(view.carts.putLine).toHaveBeenCalledWith({
          variantId: 'variant-1',
          quantity: 1,
          modifierOptionIds: ['opt-large'],
        });
        expect(view.carts.cart()?.lines.map((entry) => entry.lineKey)).toEqual([
          'variant-1+opt-large',
        ]);
        expect(view.carts.price).toHaveBeenCalled();
        // The picker is done; the order is offered; the dish is listed with what was chosen.
        expect(view.q('modifier-picker')).toBeNull();
        expect(view.q('dine-in-cart-count')?.textContent).toContain('"count":1');
        expect(view.q('dine-in-cart-total')?.textContent).toContain('45 000');
        expect((view.q('dine-in-checkout') as HTMLButtonElement).disabled).toBe(false);
        expect(view.q('dine-in-custom-line')?.textContent).toContain('Osh');
        expect(view.q('dine-in-custom-line-options')?.textContent).toContain('Large');
      });

      it('sends the quantity the guest set in the picker', async () => {
        const view = await seated();
        await view.click('dine-in-choose');

        pick(view, 'Small');
        await view.click('modifier-picker-increase');
        await view.click('modifier-picker-increase');
        await view.click('modifier-picker-add');

        expect(view.carts.putLine).toHaveBeenCalledWith({
          variantId: 'variant-1',
          quantity: 3,
          modifierOptionIds: ['opt-small'],
        });
      });

      it('choosing the same options again raises that one line: a replace writes what is held plus what was asked for', async () => {
        const view = await seated();
        for (let round = 0; round < 2; round++) {
          await view.click('dine-in-choose');
          pick(view, 'Large');
          await view.click('modifier-picker-add');
        }

        expect(view.carts.putLine).toHaveBeenLastCalledWith({
          variantId: 'variant-1',
          quantity: 2,
          modifierOptionIds: ['opt-large'],
        });
        expect(view.all('dine-in-custom-line').length).toBe(1);
        expect(view.q('dine-in-custom-quantity')?.textContent).toContain('2');
      });

      it('choosing other options is another line of the same dish', async () => {
        const view = await seated();
        await view.click('dine-in-choose');
        pick(view, 'Large');
        await view.click('modifier-picker-add');
        await view.click('dine-in-choose');
        pick(view, 'Small');
        await view.click('modifier-picker-add');

        expect(view.all('dine-in-custom-line').length).toBe(2);
        expect(view.q('dine-in-cart-count')?.textContent).toContain('"count":2');
        expect(
          view.all('dine-in-custom-line-options').map((entry) => entry.textContent?.trim()),
        ).toEqual(['Large', 'Small']);
      });

      it('leaves an optional group out of the line when the guest does not touch it', async () => {
        const view = await seated(
          menuWithRequiredSize([
            {
              id: 'extras',
              name: 'Extras',
              required: false,
              minimumSelections: 0,
              maximumSelections: 2,
              options: [{ id: 'opt-meat', label: 'Meat', amountMinor: null, maximumQuantity: 1 }],
            },
          ]),
        );
        await view.click('dine-in-choose');

        pick(view, 'Small');
        await view.click('modifier-picker-add');

        expect(view.carts.putLine).toHaveBeenCalledWith({
          variantId: 'variant-1',
          quantity: 1,
          modifierOptionIds: ['opt-small'],
        });
      });

      it("when the platform refuses the selection, says why inside the picker, keeps it open and keeps the guest's choice", async () => {
        const view = await seated();
        await view.click('dine-in-choose');
        pick(view, 'Large');
        view.carts.putLine.mockRejectedValueOnce(refusal('MODIFIER_GROUP_MINIMUM_NOT_MET'));

        await view.click('modifier-picker-add');

        expect(view.q('modifier-picker')).not.toBeNull();
        expect(view.q('modifier-picker-error')?.textContent).toContain(
          'errors.reason.modifierMinimum',
        );
        expect(
          view.all('modifier-option').map((entry) => entry.classList.contains('is-active')),
        ).toEqual([false, true]);
        expect(view.q('dine-in-order')).toBeNull();

        // The guest tries again and it goes through.
        await view.click('modifier-picker-add');

        expect(view.q('modifier-picker')).toBeNull();
        expect(view.q('dine-in-cart-count')?.textContent).toContain('"count":1');
      });

      it('checks the selection again where the line is written, and sends nothing that is short of a minimum', async () => {
        const view = await seated();
        await view.click('dine-in-choose');

        await (
          view.fixture.componentInstance as unknown as {
            addChosen(selection: {
              variantId: string;
              quantity: number;
              modifierOptionIds: string[];
            }): Promise<void>;
          }
        ).addChosen({ variantId: 'variant-1', quantity: 1, modifierOptionIds: [] });
        await settle(view.fixture);

        expect(view.carts.putLine).not.toHaveBeenCalled();
        expect(view.q('modifier-picker-error')?.textContent).toContain('dineIn.chooseRequired');
      });

      it('closing the picker adds nothing and takes its message with it', async () => {
        const view = await seated();
        await view.click('dine-in-choose');
        pick(view, 'Large');
        view.carts.putLine.mockRejectedValueOnce(refusal('MODIFIER_NOT_OFFERED'));
        await view.click('modifier-picker-add');
        expect(view.q('modifier-picker-error')).not.toBeNull();

        await view.click('modifier-picker-close');

        expect(view.q('modifier-picker')).toBeNull();
        expect(view.q('dine-in-basket-error')).toBeNull();
        expect(view.carts.cart()?.lines ?? []).toEqual([]);
      });

      it("is taken away if the table's visit ends while it is open: a dish put on a closed session would be refused", async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.menuService.home.mockResolvedValue(menuWithRequiredSize());
        view.dineIn.bill.mockResolvedValue(bill({ totalMinor: 45_000, roundCount: 1 }));
        await settle(view.fixture);
        await view.click('dine-in-choose');
        expect(view.q('modifier-picker')).not.toBeNull();
        view.dineIn.requestBill.mockRejectedValue(
          new HorecaOSApiError({
            status: 404,
            code: 'RESOURCE_NOT_FOUND',
            detail: 'no such session',
          }),
        );

        await view.click('dine-in-request-bill');

        expect(view.q('dine-in-session-ended')).not.toBeNull();
        expect(view.q('modifier-picker')).toBeNull();
      });

      it('takes one write at a time: a second confirm while the first is in flight is not sent', async () => {
        const view = await seated();
        await view.click('dine-in-choose');
        pick(view, 'Large');
        let release!: () => void;
        const gate = new Promise<void>((resolve) => (release = resolve));
        const original = view.carts.putLine.getMockImplementation()!;
        view.carts.putLine.mockImplementation(async (input) => {
          await gate;
          return original(input);
        });

        view.q('modifier-picker-add')!.click();
        await Promise.resolve();
        view.fixture.detectChanges();
        expect((view.q('modifier-picker-add') as HTMLButtonElement).disabled).toBe(true);
        view.q('modifier-picker-add')?.click();
        release();
        await settle(view.fixture);

        expect(view.carts.putLine).toHaveBeenCalledTimes(1);
      });

      it('orders a basket that holds a dish with options like any other', async () => {
        const view = await seated();
        await view.click('dine-in-choose');
        pick(view, 'Large');
        await view.click('modifier-picker-add');

        await view.click('dine-in-checkout');

        expect(view.carts.checkout).toHaveBeenCalledTimes(1);
        expect(view.carts.checkout.mock.calls[0][0]?.paymentMethodCode).toBe('CASH');
        expect(view.dineIn.queueRound).toHaveBeenCalledWith(SESSION, 'order-1');
      });

      describe('the dishes already in the basket with options', () => {
        it('are listed, by name, with what was chosen -- read back from the menu, since the line carries only ids', async () => {
          const view = setUp();
          view.dineIn.seed(admission());
          view.menuService.home.mockResolvedValue(menuWithRequiredSize());
          view.carts.preload([chosenLine('variant-1', ['opt-large'], 2)]);

          await settle(view.fixture);

          expect(view.all('dine-in-custom-line').length).toBe(1);
          expect(view.q('dine-in-custom-line')?.textContent).toContain('Osh');
          expect(view.q('dine-in-custom-line-options')?.textContent).toContain('Large');
          expect(view.q('dine-in-custom-quantity')?.textContent).toContain('2');
          expect(view.q('dine-in-cart-count')?.textContent).toContain('"count":2');
        });

        it('can be raised, lowered and taken out, each keeping the options they were ordered with', async () => {
          const view = setUp();
          view.dineIn.seed(admission());
          view.menuService.home.mockResolvedValue(menuWithRequiredSize());
          view.carts.preload([chosenLine('variant-1', ['opt-large'], 2)]);
          await settle(view.fixture);

          await view.click('dine-in-custom-increase');
          expect(view.carts.putLine).toHaveBeenLastCalledWith({
            variantId: 'variant-1',
            quantity: 3,
            modifierOptionIds: ['opt-large'],
          });

          await view.click('dine-in-custom-decrease');
          await view.click('dine-in-custom-decrease');
          expect(view.carts.putLine).toHaveBeenLastCalledWith({
            variantId: 'variant-1',
            quantity: 1,
            modifierOptionIds: ['opt-large'],
          });

          await view.click('dine-in-custom-decrease');
          expect(view.carts.removeLine).toHaveBeenCalledWith('variant-1+opt-large');
          expect(view.q('dine-in-custom-lines')).toBeNull();
          expect(view.q('dine-in-order')).toBeNull();
        });

        it("are not counted by the dish's own stepper, which counts its plain portion only", async () => {
          const view = setUp();
          view.dineIn.seed(admission());
          view.menuService.home.mockResolvedValue(
            menu([
              dish(
                'p1',
                'Osh',
                [variant()],
                [modifierGroup({ required: false, minimumSelections: 0 })],
              ),
            ]),
          );
          view.carts.preload([chosenLine('variant-1', ['opt-large'], 2)]);

          await settle(view.fixture);

          expect(view.q('dine-in-quantity')).toBeNull();
          expect(view.q('dine-in-add')).not.toBeNull();
          expect(view.all('dine-in-custom-line').length).toBe(1);
        });

        it('keep only a way out once the dish has sold out: it cannot be raised, and the platform will not price it', async () => {
          const view = setUp();
          view.dineIn.seed(admission());
          view.menuService.home.mockResolvedValue(
            menu([dish('p1', 'Osh', [variant({ active: false })], [modifierGroup()])]),
          );
          view.carts.preload([chosenLine('variant-1', ['opt-large'], 1)]);
          await settle(view.fixture);

          expect(view.q('dine-in-custom-line-unavailable')).not.toBeNull();
          expect((view.q('dine-in-custom-increase') as HTMLButtonElement).disabled).toBe(true);

          await view.click('dine-in-custom-decrease');

          expect(view.carts.removeLine).toHaveBeenCalledWith('variant-1+opt-large');
        });

        it('for a dish the menu no longer has are still listed, so they can be taken out', async () => {
          const view = setUp();
          view.dineIn.seed(admission());
          view.carts.preload([chosenLine('gone-variant', ['x'], 1)]);
          await settle(view.fixture);

          expect(view.q('dine-in-custom-line')?.textContent).toContain('dineIn.lineGone');
          expect((view.q('dine-in-custom-increase') as HTMLButtonElement).disabled).toBe(true);

          await view.click('dine-in-custom-decrease');

          expect(view.carts.removeLine).toHaveBeenCalledWith('gone-variant+x');
        });

        it('say why when a change could not be made, and keep the list', async () => {
          const view = setUp();
          view.dineIn.seed(admission());
          view.menuService.home.mockResolvedValue(menuWithRequiredSize());
          view.carts.preload([chosenLine('variant-1', ['opt-large'], 1)]);
          await settle(view.fixture);
          view.carts.putLine.mockRejectedValueOnce(refusal('SOLD_OUT'));

          await view.click('dine-in-custom-increase');

          expect(view.q('dine-in-basket-error')?.textContent).toContain(
            'errors.reason.itemUnavailable',
          );
          expect(view.all('dine-in-custom-line').length).toBe(1);
        });
      });
    });

    describe('binding the basket to the table (PUT .../carts/{id}/table)', () => {
      it('binds the basket before its first line, and only once per basket', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        await settle(view.fixture);

        await view.click('dine-in-add');
        await view.click('dine-in-increase');

        expect(view.dineIn.bindCartToTable).toHaveBeenCalledTimes(1);
        expect(view.dineIn.bindCartToTable).toHaveBeenCalledWith(view.carts);
        expect(view.carts.bindTable).toHaveBeenCalledTimes(1);
        // Binding clears the quote, so it has to precede both the line and the pricing.
        const [bound] = view.dineIn.bindCartToTable.mock.invocationCallOrder;
        expect(bound).toBeLessThan(view.carts.putLine.mock.invocationCallOrder[0]);
        expect(bound).toBeLessThan(view.carts.price.mock.invocationCallOrder[0]);
      });

      it('binds a basket found after a reload before pricing it, so an earlier basket is atomic too', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.carts.preload([line('variant-1', 1)]);

        await settle(view.fixture);

        expect(view.dineIn.bindCartToTable).toHaveBeenCalledTimes(1);
        const [bound] = view.dineIn.bindCartToTable.mock.invocationCallOrder;
        expect(bound).toBeLessThan(view.carts.price.mock.invocationCallOrder[0]);
        expect(view.q('dine-in-order')).not.toBeNull();
      });

      it('does not bind a browse: no basket is opened, so there is nothing to bind', async () => {
        const view = setUp();
        view.dineIn.seed(admission());

        await settle(view.fixture);

        expect(view.dineIn.bindCartToTable).not.toHaveBeenCalled();
      });

      it('a basket that cannot be bound gets no line, says why, and tries again on the next add', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        await settle(view.fixture);
        view.dineIn.bindCartToTable.mockRejectedValueOnce(
          new HorecaOSApiError({
            status: 409,
            code: 'RESOURCE_CONFLICT',
            detail: 'elsewhere',
            problem: { status: 409, code: 'RESOURCE_CONFLICT', reason: 'TABLE_NOT_AT_THIS_BRANCH' },
          }),
        );

        await view.click('dine-in-add');

        expect(view.carts.putLine).not.toHaveBeenCalled();
        expect(view.q('dine-in-basket-error')?.textContent).toContain(
          'errors.reason.tableNotAtBranch',
        );

        await view.click('dine-in-add');

        expect(view.dineIn.bindCartToTable).toHaveBeenCalledTimes(2);
        expect(view.carts.putLine).toHaveBeenCalledTimes(1);
      });

      it('a basket restored after a reload that cannot be bound is still priced: the queued attach covers it', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.carts.preload([line('variant-1', 1)]);
        view.dineIn.bindCartToTable.mockRejectedValue(
          new HorecaOSApiError({ status: 0, code: 'NETWORK_UNREACHABLE', detail: 'offline' }),
        );

        await settle(view.fixture);

        expect(view.carts.price).toHaveBeenCalled();
        expect(view.q('dine-in-order')).not.toBeNull();
      });
    });

    describe('a basket line the menu can no longer sell', () => {
      it("keeps the dish's stepper when it has sold out since, so the line can be taken out", async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.menuService.home.mockResolvedValue(
          menu([dish('p1', 'Osh', [variant({ active: false })])]),
        );
        view.carts.preload([line('variant-1', 1)]);

        await settle(view.fixture);

        expect(view.q('dish-sold-out')).not.toBeNull();
        expect(view.q('dine-in-decrease')).not.toBeNull();
        await view.click('dine-in-decrease');

        expect(view.carts.removeLine).toHaveBeenCalledWith('variant-1');
        expect(view.q('dine-in-order')).toBeNull();
      });

      it('takes a held plain line out whole when its dish has since gained a required group: one fewer would be refused', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.menuService.home.mockResolvedValue(menuWithRequiredSize());
        view.carts.preload([line('variant-1', 2)]);
        // The platform checks a dish's selection rules on every write of a line, so a
        // plain line of a dish that must be chosen from is refused at any quantity.
        view.carts.putLine.mockRejectedValue(refusal('MODIFIER_GROUP_MINIMUM_NOT_MET'));

        await settle(view.fixture);
        await view.click('dine-in-decrease');

        expect(view.carts.putLine).not.toHaveBeenCalled();
        expect(view.carts.removeLine).toHaveBeenCalledWith('variant-1');
        expect(view.q('dine-in-quantity')).toBeNull();
        expect(view.q('dine-in-order')).toBeNull();
      });

      it('offers to clear the order when the platform will not price it, and ordering works again afterwards', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        // A line for a portion that is not on the menu at all: no card carries it.
        view.carts.preload([line('variant-1', 1), line('gone-variant', 1)]);
        const priceable = view.carts.price.getMockImplementation()!;
        view.carts.price.mockImplementation(async () => {
          if (view.carts.cart()?.lines.some((entry) => entry.variantId === 'gone-variant')) {
            throw new HorecaOSApiError({
              status: 422,
              code: 'VALIDATION_FAILED',
              detail: 'unpriced',
              problem: { status: 422, code: 'VALIDATION_FAILED', reason: 'SOLD_OUT' },
            });
          }
          return priceable();
        });

        await settle(view.fixture);

        expect(view.q('dine-in-pricing-error')).not.toBeNull();
        expect((view.q('dine-in-checkout') as HTMLButtonElement).disabled).toBe(true);
        expect(view.q('dine-in-clear')).not.toBeNull();

        await view.click('dine-in-clear');

        expect(view.carts.clear).toHaveBeenCalledTimes(1);
        expect(view.q('dine-in-order')).toBeNull();
        expect(view.q('dine-in-pricing-error')).toBeNull();

        await view.click('dine-in-add');
        expect((view.q('dine-in-checkout') as HTMLButtonElement).disabled).toBe(false);
      });

      it('offers no clear button while the basket prices fine: an order is never one tap from being thrown away', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.carts.preload([line('variant-1', 1)]);

        await settle(view.fixture);

        expect(view.q('dine-in-order')).not.toBeNull();
        expect(view.q('dine-in-clear')).toBeNull();
      });

      it('says why when the basket could not be cleared, and keeps the order panel', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.carts.preload([line('variant-1', 1)]);
        view.carts.price.mockRejectedValue(
          new HorecaOSApiError({
            status: 422,
            code: 'VALIDATION_FAILED',
            detail: 'unpriced',
            problem: { status: 422, code: 'VALIDATION_FAILED', reason: 'SOLD_OUT' },
          }),
        );
        view.carts.clear.mockRejectedValue(
          new HorecaOSApiError({ status: 503, code: 'INTERNAL_ERROR', detail: 'down' }),
        );
        await settle(view.fixture);

        await view.click('dine-in-clear');

        expect(view.q('dine-in-basket-error')).not.toBeNull();
        expect(view.q('dine-in-order')).not.toBeNull();
      });
    });

    it('offers a choice of payment when the cart has several, and orders with the one chosen', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.carts.paymentMethods.mockResolvedValue({
        cartId: 'cart-1',
        currency: 'UZS',
        methodCodes: ['CASH', 'CLICK'],
        warnings: [],
      });
      view.dineIn.attachRound.mockResolvedValue(bill({ totalMinor: 45_000, roundCount: 1 }));
      vi.spyOn(
        view.fixture.componentInstance as unknown as { redirectTo(url: string): void },
        'redirectTo',
      ).mockImplementation(() => {});
      view.payments.open.mockResolvedValue({ checkoutUrl: 'https://pay.example/c' });
      await settle(view.fixture);
      await view.click('dine-in-add');

      expect(
        view.all('dine-in-payment-option').map((option) => option.textContent?.trim()),
      ).toEqual(['cart.cash', 'cart.click']);
      view.all('dine-in-payment-option')[1].click();
      await settle(view.fixture);
      await view.click('dine-in-checkout');

      expect(view.carts.checkout).toHaveBeenCalledWith(
        expect.objectContaining({ paymentMethodCode: 'CLICK' }),
      );
    });

    it('shows no payment choice when there is only one way to pay, and uses it', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.dineIn.attachRound.mockResolvedValue(bill({ totalMinor: 45_000, roundCount: 1 }));
      await settle(view.fixture);
      await view.click('dine-in-add');

      expect(view.q('dine-in-payment-options')).toBeNull();
      await view.click('dine-in-checkout');

      expect(view.carts.checkout).toHaveBeenCalledWith(
        expect.objectContaining({ paymentMethodCode: 'CASH' }),
      );
    });

    it('says so when no way to pay is offered, and does not send an order without one', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.carts.paymentMethods.mockResolvedValue({
        cartId: 'cart-1',
        currency: 'UZS',
        methodCodes: [],
        warnings: [],
      });
      await settle(view.fixture);
      await view.click('dine-in-add');

      await view.click('dine-in-checkout');

      expect(view.carts.checkout).not.toHaveBeenCalled();
      expect(view.q('dine-in-checkout-error')?.textContent).toContain(
        'cart.noPaymentMethodSelected',
      );
    });
  });

  describe("placing the order -- checkout, then the round on the table's bill", () => {
    async function withBasket(configure: (view: View) => void = () => {}): Promise<View> {
      const view = setUp();
      view.dineIn.seed(admission());
      view.dineIn.attachRound.mockResolvedValue(
        bill({ totalMinor: 45_000, roundCount: 1, orderIds: ['order-1'] }),
      );
      configure(view);
      await settle(view.fixture);
      await view.click('dine-in-add');
      return view;
    }

    it('checks the priced basket out, queues the order for the table, attaches it, and clears the basket', async () => {
      const view = await withBasket();

      await view.click('dine-in-checkout');

      expect(view.carts.checkout).toHaveBeenCalledWith({
        priced: expect.objectContaining({ quoteId: 'quote-1', contextHash: 'hash-1' }),
        paymentMethodCode: 'CASH',
        idempotencyKey: expect.any(String),
      });
      expect(view.dineIn.queueRound).toHaveBeenCalledWith(SESSION, 'order-1');
      expect(view.dineIn.attachRound).toHaveBeenCalledWith(SESSION, 'order-1');
      // Remembered against the session, so the next evening's basket is a new one.
      expect(view.carts.discard).toHaveBeenCalledWith(LOCATION, SESSION);
      expect(view.q('dine-in-order')).toBeNull();
      expect(view.q('dine-in-bill-total')?.textContent).toContain('45 000');
      // Said on the screen: this app renders no toasts.
      expect(view.q('dine-in-order-placed')?.textContent).toContain('dineIn.orderPlaced');
    });

    it('does not say the order is placed while it is not yet on the table bill', async () => {
      const view = await withBasket((v) => {
        v.dineIn.flushPendingRounds.mockResolvedValue({ bill: null, pending: 1, abandoned: 0 });
      });

      await view.click('dine-in-checkout');

      expect(view.q('dine-in-order-placed')).toBeNull();
    });

    it('does not say the order is placed when the platform refused to put it on the bill', async () => {
      const view = await withBasket((v) => {
        v.dineIn.flushPendingRounds.mockResolvedValue({ bill: null, pending: 0, abandoned: 1 });
      });

      await view.click('dine-in-checkout');

      expect(view.q('dine-in-order-placed')).toBeNull();
    });

    it('takes the message away as soon as the guest starts another order', async () => {
      const view = await withBasket();
      await view.click('dine-in-checkout');
      expect(view.q('dine-in-order-placed')).not.toBeNull();

      await view.click('dine-in-add');

      expect(view.q('dine-in-order-placed')).toBeNull();
    });

    it('queues the order BEFORE the attach is tried, so a lost response cannot lose it', async () => {
      const order: string[] = [];
      const view = await withBasket((v) => {
        v.dineIn.queueRound.mockImplementation(() => void order.push('queue'));
        v.dineIn.flushPendingRounds.mockImplementation(async () => {
          order.push('flush');
          return { bill: null, pending: 1, abandoned: 0 };
        });
      });

      await view.click('dine-in-checkout');

      expect(order).toEqual(['queue', 'flush']);
    });

    it('keeps the same idempotency key across a retry after the network failed', async () => {
      const view = await withBasket();
      view.carts.checkout.mockRejectedValueOnce(
        new HorecaOSApiError({ status: 0, code: 'NETWORK_UNREACHABLE', detail: 'offline' }),
      );

      await view.click('dine-in-checkout');
      await view.click('dine-in-checkout');

      const keys = view.carts.checkout.mock.calls.map((call) => call[0]?.idempotencyKey);
      expect(keys).toHaveLength(2);
      expect(keys[0]).toBe(keys[1]);
    });

    it('takes a new key after the platform really refused the first attempt', async () => {
      const view = await withBasket();
      view.carts.checkout.mockRejectedValueOnce(
        new HorecaOSApiError({ status: 422, code: 'UNPROCESSABLE_STATE', detail: 'no' }),
      );

      await view.click('dine-in-checkout');
      await view.click('dine-in-checkout');

      const keys = view.carts.checkout.mock.calls.map((call) => call[0]?.idempotencyKey);
      expect(keys[0]).not.toBe(keys[1]);
    });

    it('a refused checkout says why, queues nothing, and keeps the basket', async () => {
      const view = await withBasket();
      view.carts.checkout.mockRejectedValue(
        new HorecaOSApiError({
          status: 422,
          code: 'UNPROCESSABLE_STATE',
          detail: 'closed',
          problem: { status: 422, code: 'UNPROCESSABLE_STATE', reason: 'ITEM_UNAVAILABLE' },
        }),
      );
      // ITEM_UNAVAILABLE is not a reason this client maps; the ADR 0031 code is.

      await view.click('dine-in-checkout');

      expect(view.q('dine-in-checkout-error')).not.toBeNull();
      expect(view.dineIn.queueRound).not.toHaveBeenCalled();
      expect(view.carts.discard).not.toHaveBeenCalled();
      expect(view.q('dine-in-order')).not.toBeNull();
    });

    it('an order refused because nobody is seated at the table any more says so, before anything is written', async () => {
      const view = await withBasket();
      view.carts.checkout.mockRejectedValue(
        new HorecaOSApiError({
          status: 409,
          code: 'RESOURCE_CONFLICT',
          detail: 'Nobody is seated at this table',
          problem: { status: 409, code: 'RESOURCE_CONFLICT', reason: 'TABLE_NOT_SEATED' },
        }),
      );

      await view.click('dine-in-checkout');

      expect(view.q('dine-in-checkout-error')?.textContent).toContain('dineIn.notSeated');
      expect(view.dineIn.queueRound).not.toHaveBeenCalled();
      expect(view.carts.discard).not.toHaveBeenCalled();
      expect(view.q('dine-in-order')).not.toBeNull();
    });

    describe('a price that has gone stale before the guest presses Order', () => {
      const PAST = () => new Date(Date.now() - 60_000).toISOString();

      /** The first price of the basket is already expired; later ones are fresh, at `laterTotalMinor`. */
      async function withExpiredQuote(laterTotalMinor = 45_000): Promise<View> {
        return withBasket((v) => {
          const fresh = v.carts.price.getMockImplementation()!;
          v.carts.price
            .mockImplementationOnce(async () => ({ ...(await fresh()), expiresAt: PAST() }))
            .mockImplementation(async () => ({ ...(await fresh()), totalMinor: laterTotalMinor }));
        });
      }

      it('reprices an expired quote instead of sending it, and orders when the total has not moved', async () => {
        const view = await withExpiredQuote(45_000);
        expect(view.carts.price).toHaveBeenCalledTimes(1);

        await view.click('dine-in-checkout');

        expect(view.carts.price).toHaveBeenCalledTimes(2);
        expect(view.carts.checkout).toHaveBeenCalledTimes(1);
        const sent = view.carts.checkout.mock.calls[0][0]!.priced;
        expect(Date.parse(sent.expiresAt)).toBeGreaterThan(Date.now());
        expect(view.dineIn.queueRound).toHaveBeenCalledWith(SESSION, 'order-1');
      });

      it('shows the new total instead of ordering when the repriced basket costs something else', async () => {
        const view = await withExpiredQuote(50_000);

        await view.click('dine-in-checkout');

        expect(view.carts.checkout).not.toHaveBeenCalled();
        expect(view.q('dine-in-checkout-error')?.textContent).toContain('dineIn.priceRefreshed');
        expect(view.q('dine-in-cart-total')?.textContent).toContain('50\u00a0000');
        expect((view.q('dine-in-checkout') as HTMLButtonElement).disabled).toBe(false);

        await view.click('dine-in-checkout');

        expect(view.carts.checkout).toHaveBeenCalledTimes(1);
        expect(view.carts.checkout.mock.calls[0][0]!.priced.totalMinor).toBe(50_000);
        expect(view.q('dine-in-checkout-error')).toBeNull();
      });

      it('says why and orders nothing when the expired basket can no longer be priced', async () => {
        const view = await withExpiredQuote();
        view.carts.price.mockRejectedValue(
          new HorecaOSApiError({
            status: 422,
            code: 'VALIDATION_FAILED',
            detail: 'unpriced',
            problem: { status: 422, code: 'VALIDATION_FAILED', reason: 'SOLD_OUT' },
          }),
        );

        await view.click('dine-in-checkout');

        expect(view.carts.checkout).not.toHaveBeenCalled();
        expect(view.q('dine-in-pricing-error')?.textContent).toContain(
          'errors.reason.itemUnavailable',
        );
        expect(view.q('dine-in-checkout-error')).toBeNull();
        expect((view.q('dine-in-checkout') as HTMLButtonElement).disabled).toBe(true);
      });

      it.each([
        ["the quote expired on the platform's clock", 409, 'RESOURCE_CONFLICT', 'QUOTE_EXPIRED'],
        ['the price changed', 409, 'PRICE_CHANGED', 'PRICE_CHANGED'],
        ['the basket moved under the quote', 409, 'STALE_VERSION', 'CART_VERSION_STALE'],
        ['the quote is gone', 404, 'RESOURCE_NOT_FOUND', 'QUOTE_NOT_FOUND'],
      ])(
        'when %s, prices the basket again, says so, and the next press uses the new quote',
        async (_name, status, code, reason) => {
          const view = await withBasket();
          view.carts.checkout.mockRejectedValueOnce(
            new HorecaOSApiError({
              status,
              code,
              detail: 'stale',
              problem: { status, code, reason },
            }),
          );
          const pricedBefore = view.carts.price.mock.calls.length;

          await view.click('dine-in-checkout');

          expect(view.carts.price.mock.calls.length).toBe(pricedBefore + 1);
          expect(view.q('dine-in-checkout-error')?.textContent).toContain('dineIn.priceRefreshed');
          expect(view.dineIn.queueRound).not.toHaveBeenCalled();
          expect((view.q('dine-in-checkout') as HTMLButtonElement).disabled).toBe(false);

          await view.click('dine-in-checkout');

          expect(view.carts.checkout).toHaveBeenCalledTimes(2);
          expect(view.dineIn.queueRound).toHaveBeenCalledWith(SESSION, 'order-1');
          // A new quote is a new request: it never reuses the refused attempt's key.
          const keys = view.carts.checkout.mock.calls.map((call) => call[0]?.idempotencyKey);
          expect(keys[0]).not.toBe(keys[1]);
        },
      );

      it('does not reprice for a failure that is not about the quote', async () => {
        const view = await withBasket();
        view.carts.checkout.mockRejectedValueOnce(
          new HorecaOSApiError({ status: 503, code: 'INTERNAL_ERROR', detail: 'down' }),
        );
        const pricedBefore = view.carts.price.mock.calls.length;

        await view.click('dine-in-checkout');

        expect(view.carts.price.mock.calls.length).toBe(pricedBefore);
        expect(view.q('dine-in-checkout-error')?.textContent).not.toContain(
          'dineIn.priceRefreshed',
        );
      });
    });

    it('an order the platform REJECTED is not an order: nothing is queued, nothing is discarded', async () => {
      const view = await withBasket();
      view.carts.checkout.mockResolvedValue({
        orderId: '',
        publicOrderNumber: '',
        status: 'REJECTED',
        version: 0,
        outcome: 'REJECTED',
        warnings: [],
      });

      await view.click('dine-in-checkout');

      expect(view.q('dine-in-checkout-error')?.textContent).toContain('cart.orderRejected');
      expect(view.dineIn.queueRound).not.toHaveBeenCalled();
      expect(view.carts.discard).not.toHaveBeenCalled();
    });

    it('a customer session that ended mid-checkout does not throw the guest out of their table', async () => {
      const view = await withBasket();
      view.carts.checkout.mockRejectedValue(
        new HorecaOSApiError({ status: 401, code: 'UNAUTHENTICATED', detail: 'session ended' }),
      );
      view.dineIn.isGuestSessionEnded.mockReturnValue(true);

      await view.click('dine-in-checkout');

      // The guest token was not involved in that call; only a call made with it
      // may end the table visit.
      expect(view.dineIn.clear).not.toHaveBeenCalled();
      expect(view.q('dine-in-table-code')).not.toBeNull();
      expect(view.q('dine-in-checkout-error')).not.toBeNull();
    });

    it("sends the guest to the provider's checkout for an online method -- after the round is on the bill", async () => {
      const order: string[] = [];
      const view = await withBasket((v) => {
        v.carts.paymentMethods.mockResolvedValue({
          cartId: 'cart-1',
          currency: 'UZS',
          methodCodes: ['CLICK'],
          warnings: [],
        });
        v.dineIn.flushPendingRounds.mockImplementation(async () => {
          order.push('attach');
          return { bill: bill({ totalMinor: 45_000, roundCount: 1 }), pending: 0, abandoned: 0 };
        });
        v.payments.open.mockImplementation(async () => {
          order.push('payment-session');
          return { checkoutUrl: 'https://pay.example/checkout' };
        });
      });
      const redirect = vi
        .spyOn(
          view.fixture.componentInstance as unknown as { redirectTo(url: string): void },
          'redirectTo',
        )
        .mockImplementation(() => {});

      await view.click('dine-in-checkout');

      expect(view.payments.open).toHaveBeenCalledWith('order-1');
      expect(redirect).toHaveBeenCalledWith('https://pay.example/checkout');
      expect(order).toEqual(['attach', 'payment-session']);
    });

    it('says the order was placed but the payment page did not open, and does not redirect', async () => {
      const view = await withBasket((v) => {
        v.carts.paymentMethods.mockResolvedValue({
          cartId: 'cart-1',
          currency: 'UZS',
          methodCodes: ['PAYME'],
          warnings: [],
        });
        v.payments.open.mockRejectedValue(new Error('offline'));
      });
      const redirect = vi
        .spyOn(
          view.fixture.componentInstance as unknown as { redirectTo(url: string): void },
          'redirectTo',
        )
        .mockImplementation(() => {});

      await view.click('dine-in-checkout');

      expect(redirect).not.toHaveBeenCalled();
      expect(view.q('dine-in-payment-error')?.textContent).toContain('cart.paymentSessionError');
      // The order is with the kitchen and on the bill whatever happened to the payment page.
      expect(view.dineIn.attachRound).toHaveBeenCalledWith(SESSION, 'order-1');
    });

    it('opens no payment session for cash', async () => {
      const view = await withBasket();

      await view.click('dine-in-checkout');

      expect(view.payments.open).not.toHaveBeenCalled();
    });
  });

  describe('the bill', () => {
    it('asks for the bill and shows, on the bill, that it has been asked for', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.dineIn.bill.mockResolvedValue(bill({ totalMinor: 45_000, roundCount: 1 }));
      view.dineIn.requestBill.mockResolvedValue(
        bill({ status: 'BILL_REQUESTED', totalMinor: 45_000, roundCount: 1 }),
      );
      await settle(view.fixture);
      expect(view.q('dine-in-bill-requested')).toBeNull();

      await view.click('dine-in-request-bill');

      expect(view.dineIn.requestBill).toHaveBeenCalledWith(SESSION);
      expect(view.q('dine-in-bill-requested')?.textContent).toContain('dineIn.billRequested');
      // Asking again is pointless and the platform would only answer with the same bill.
      expect(view.q('dine-in-request-bill')).toBeNull();
    });

    it('shows a bill already asked for as asked for, on arrival', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.dineIn.bill.mockResolvedValue(
        bill({ status: 'BILL_REQUESTED', totalMinor: 45_000, roundCount: 1 }),
      );

      await settle(view.fixture);

      expect(view.q('dine-in-bill-requested')).not.toBeNull();
      expect(view.q('dine-in-request-bill')).toBeNull();
    });

    it('cannot ask for a bill with nothing on it', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.dineIn.bill.mockResolvedValue(bill({ totalMinor: 0, roundCount: 0 }));

      await settle(view.fixture);

      expect((view.q('dine-in-request-bill') as HTMLButtonElement).disabled).toBe(true);
    });

    it('does not offer to ask for a bill that is not there yet', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.dineIn.bill.mockRejectedValue(new Error('offline'));

      await settle(view.fixture);

      expect(view.q('dine-in-request-bill')).toBeNull();
    });

    describe('when the bill cannot be reached', () => {
      const offlineFailure = () =>
        new HorecaOSApiError({ status: 0, code: 'NETWORK_UNREACHABLE', detail: 'offline' });
      const noSuchSession = () =>
        new HorecaOSApiError({
          status: 404,
          code: 'RESOURCE_NOT_FOUND',
          detail: 'no such session',
        });

      it('asking for the bill that fails says so, keeps the button, and clears the message on the next try', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.dineIn.bill.mockResolvedValue(bill({ totalMinor: 45_000, roundCount: 1 }));
        await settle(view.fixture);
        view.dineIn.requestBill.mockRejectedValueOnce(offlineFailure());

        await view.click('dine-in-request-bill');

        expect(view.q('dine-in-bill-error')?.textContent).toContain('errors.offline');
        expect(view.q('dine-in-bill-requested')).toBeNull();
        expect((view.q('dine-in-request-bill') as HTMLButtonElement).disabled).toBe(false);

        view.dineIn.requestBill.mockResolvedValue(
          bill({ status: 'BILL_REQUESTED', totalMinor: 45_000, roundCount: 1 }),
        );
        await view.click('dine-in-request-bill');

        expect(view.q('dine-in-bill-error')).toBeNull();
        expect(view.q('dine-in-bill-requested')).not.toBeNull();
      });

      it('a bill that cannot be read on arrival says so and offers to try again', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.dineIn.bill.mockRejectedValueOnce(offlineFailure());

        await settle(view.fixture);

        expect(view.q('dine-in-bill')).toBeNull();
        expect(view.q('dine-in-bill-error')?.textContent).toContain('errors.offline');
        expect(view.q('dine-in-bill-retry')).not.toBeNull();

        view.dineIn.bill.mockResolvedValue(bill({ totalMinor: 45_000, roundCount: 1 }));
        await view.click('dine-in-bill-retry');

        expect(view.q('dine-in-bill-error')).toBeNull();
        expect(view.q('dine-in-bill-retry')).toBeNull();
        expect(view.q('dine-in-bill-total')?.textContent).toContain('45\u00a0000');
      });

      it('keeps the last bill it read on screen when a refresh fails, and says so', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.dineIn.bill.mockResolvedValue(bill({ totalMinor: 45_000, roundCount: 1 }));
        await settle(view.fixture);
        view.dineIn.requestBill.mockRejectedValue(offlineFailure());

        await view.click('dine-in-request-bill');

        expect(view.q('dine-in-bill-total')?.textContent).toContain('45\u00a0000');
        expect(view.q('dine-in-bill-error')).not.toBeNull();
        // The ask has its own button; a second "try again" beside it would be two answers to one question.
        expect(view.q('dine-in-bill-retry')).toBeNull();
      });

      it('a session the platform no longer knows ends ordering and says why, leaving the menu', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.dineIn.bill.mockRejectedValue(noSuchSession());

        await settle(view.fixture);

        expect(view.q('dine-in-session-ended')?.textContent).toContain('dineIn.sessionEnded');
        expect(view.q('dine-in-add')).toBeNull();
        expect(view.q('dine-in-order')).toBeNull();
        expect(view.q('dine-in-signin')).toBeNull();
        expect(view.q('dish-card')).not.toBeNull();
        // Not a guest token problem: the visit is kept, so the menu stays readable.
        expect(view.dineIn.clear).not.toHaveBeenCalled();
      });

      it('asking for the bill of a session the platform no longer knows ends ordering too', async () => {
        const view = setUp();
        view.dineIn.seed(admission());
        view.dineIn.bill.mockResolvedValue(bill({ totalMinor: 45_000, roundCount: 1 }));
        await settle(view.fixture);
        view.dineIn.requestBill.mockRejectedValue(noSuchSession());

        await view.click('dine-in-request-bill');

        expect(view.q('dine-in-session-ended')).not.toBeNull();
        expect(view.q('dine-in-add')).toBeNull();
      });
    });

    it('a guest token the platform no longer recognises clears the visit instead of leaving a broken screen up', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.dineIn.bill.mockRejectedValue(new Error('unauthenticated'));
      view.dineIn.isGuestSessionEnded.mockReturnValue(true);

      await settle(view.fixture);

      expect(view.dineIn.clear).toHaveBeenCalled();
      expect(view.q('dine-in-no-admission')).not.toBeNull();
    });

    it('asking for the bill with a token that has ended clears the visit too', async () => {
      const view = setUp();
      view.dineIn.seed(admission());
      view.dineIn.bill.mockResolvedValue(bill({ totalMinor: 45_000, roundCount: 1 }));
      await settle(view.fixture);
      view.dineIn.requestBill.mockRejectedValue(new Error('unauthenticated'));
      view.dineIn.isGuestSessionEnded.mockReturnValue(true);

      await view.click('dine-in-request-bill');

      expect(view.dineIn.clear).toHaveBeenCalled();
    });
  });
});

/**
 * The bill is the only thing that ties a guest's order to their table: the
 * kitchen ticket's table chip, the order board's row and the running total all
 * read `dinein.session_orders`, and the only writer for a guest order is the
 * second call checkout makes after the order exists. These specs run the real
 * {@link DineInService} against a fake HTTP client, because what they prove is
 * what goes over the wire when that second call is lost -- and that the guest
 * token goes nowhere but its header.
 */
describe('DineInTableComponent -- combos at the table (ADR 0136)', () => {
  const component = (id: string, name: string, amountMinor: number | null, active = true) => ({
    id,
    name,
    variantName: null,
    defaultQuantity: 1,
    active,
    amountMinor,
  });
  const combo = (): MenuItem => ({
    ...dish('p-lunch', 'Lunch box', [variant({ id: 'v-lunch', price: 22_000 })]),
    comboGroups: [
      {
        id: 'g-main',
        name: 'Main',
        minimumSelections: 1,
        maximumSelections: 1,
        allowSameComponentMultipleTimes: false,
        components: [component('c-burger', 'Burger', 25_000), component('c-wrap', 'Wrap', 22_000)],
      },
    ],
  });

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  async function seated() {
    const view = setUp();
    view.dineIn.seed(admission());
    view.menuService.home.mockResolvedValue(menu([combo()]));
    await settle(view.fixture);
    return view;
  }

  it('offers a combo as a choice to make, never as a dish added with a plus', async () => {
    const view = await seated();

    expect(view.q('dine-in-add')).toBeNull();
    expect(view.q('dine-in-choose')).not.toBeNull();
  });

  it('opens the combo’s choices and refuses to add until the minimum is met, writing nothing', async () => {
    const view = await seated();
    await view.click('dine-in-choose');

    expect(view.q('picker-combo')).not.toBeNull();
    expect((view.q('modifier-picker-add') as HTMLButtonElement).disabled).toBe(true);

    await view.click('modifier-picker-add');

    expect(view.carts.putLine).not.toHaveBeenCalled();
  });

  it('adds the container with the picks made, keyed by the container and a hash of the picks', async () => {
    const view = await seated();
    await view.click('dine-in-choose');

    view.all('combo-component')[1].click();
    view.fixture.detectChanges();
    await view.click('modifier-picker-add');

    const picks = [{ componentId: 'c-wrap', quantity: 1 }];
    expect(view.carts.putLine).toHaveBeenCalledWith({
      variantId: 'v-lunch',
      quantity: 1,
      modifierOptionIds: [],
      comboPicks: picks,
    });
    expect(view.carts.cart()?.lines.map((entry) => entry.lineKey)).toEqual([
      lineKeyFor('v-lunch', [], picks),
    ]);
  });

  it('lists the line with the components it will become, and raises the same combo rather than adding another', async () => {
    const view = await seated();
    for (let round = 0; round < 2; round++) {
      await view.click('dine-in-choose');
      view.all('combo-component')[1].click();
      view.fixture.detectChanges();
      await view.click('modifier-picker-add');
    }

    expect(view.all('dine-in-custom-line').length).toBe(1);
    expect(view.q('dine-in-custom-line')?.textContent).toContain('Lunch box');
    expect(view.q('dine-in-custom-line-options')?.textContent).toContain('Wrap');
    expect(view.q('dine-in-custom-quantity')?.textContent).toContain('2');
    expect(view.carts.putLine).toHaveBeenLastCalledWith(
      expect.objectContaining({ variantId: 'v-lunch', quantity: 2 }),
    );
  });

  it('resends a held combo’s picks when its quantity is stepped, or the step would strip them', async () => {
    const view = await seated();
    await view.click('dine-in-choose');
    view.all('combo-component')[0].click();
    view.fixture.detectChanges();
    await view.click('modifier-picker-add');

    await view.click('dine-in-custom-increase');

    expect(view.carts.putLine).toHaveBeenLastCalledWith(
      expect.objectContaining({
        variantId: 'v-lunch',
        quantity: 2,
        comboPicks: [{ componentId: 'c-burger', quantity: 1 }],
      }),
    );
  });

  it('itemises a charge the server added to the table’s basket, named from the menu, among the totals', async () => {
    const view = setUp();
    view.dineIn.seed(admission());
    view.menuService.home.mockResolvedValue(menu([combo()]));
    view.menuService.optionLabels.set(new Map([['o-box', 'Table service']]));
    view.carts.hiddenCharges = [
      { lineKey: 'v-lunchcabc~0', optionId: 'o-box', amountMinor: 2_000 },
    ];
    view.carts.preload([line('variant-1', 1)]);

    await settle(view.fixture);

    const charges = view.all('dine-in-hidden-charge');
    expect(charges).toHaveLength(1);
    expect(charges[0].textContent).toContain('Table service');
    expect(charges[0].textContent).toMatch(/2.000/);
  });

  it('says nothing of the kind when the server added nothing', async () => {
    const view = setUp();
    view.dineIn.seed(admission());
    view.carts.preload([line('variant-1', 1)]);

    await settle(view.fixture);

    expect(view.q('dine-in-hidden-charges')).toBeNull();
  });
});

describe('DineInTableComponent -- against the real DineInService', () => {
  const ADMISSION_KEY = 'horecaos_dinein_admission';
  const ROUNDS_PATH = `/storefront/dine-in/sessions/${SESSION}/rounds`;

  interface FakeApi {
    get: ReturnType<typeof vi.fn>;
    mutate: ReturnType<typeof vi.fn>;
  }

  function newApi(): FakeApi {
    const api: FakeApi = { get: vi.fn(), mutate: vi.fn() };
    api.get.mockResolvedValue(bill());
    return api;
  }

  function setUpReal(api: FakeApi, guestToken = 'guest-token-1') {
    localStorage.setItem(ADMISSION_KEY, JSON.stringify({ ...admission(), guestToken }));
    const carts = new FakeCartService();
    const menuService = new FakeMenuService();
    const session = new FakeSession();
    menuService.home.mockResolvedValue(menu());

    TestBed.configureTestingModule({
      imports: [DineInTableComponent],
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: api },
        { provide: DineInCartService, useValue: carts },
        { provide: MenuService, useValue: menuService },
        { provide: Session, useValue: session },
        { provide: PaymentSessionService, useValue: new FakePaymentSessionService() },
        { provide: LangService, useValue: { langId: () => 'uz' } },
        { provide: TranslateService, useClass: FakeTranslateService },
      ],
    });
    const fixture = TestBed.createComponent(DineInTableComponent);
    return { fixture, carts, session, host: fixture.nativeElement as HTMLElement };
  }

  async function tick(fixture: { detectChanges(): void }): Promise<void> {
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  async function placeOrder(view: ReturnType<typeof setUpReal>): Promise<void> {
    view.fixture.detectChanges();
    await tick(view.fixture);
    view.host.querySelector<HTMLButtonElement>('[data-testid="dine-in-add"]')?.click();
    await tick(view.fixture);
    view.host.querySelector<HTMLButtonElement>('[data-testid="dine-in-checkout"]')?.click();
    await tick(view.fixture);
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
    sessionStorage.clear();
  });

  it("attaches the round with the guest token in the header only, beside the customer's own session", async () => {
    const api = newApi();
    api.mutate.mockResolvedValue(
      bill({ totalMinor: 45_000, roundCount: 1, orderIds: ['order-1'] }),
    );
    const view = setUpReal(api, 'guest-token-secret');

    await placeOrder(view);

    const [method, path, options] = roundCalls(api)[0] as [string, string, Record<string, unknown>];
    expect(method).toBe('POST');
    expect(path).toBe(ROUNDS_PATH);
    expect(options['headers']).toEqual({ 'X-Dine-In-Token': 'guest-token-secret' });
    expect(options['body']).toEqual({ orderId: 'order-1' });
    expect(options['anonymous']).not.toBe(true);
    expect(path).not.toContain('guest-token-secret');
    expect(view.host.textContent).not.toContain('guest-token-secret');
  });

  it('binds the basket to the table with the guest token in a header only, and never on the screen', async () => {
    const api = newApi();
    api.mutate.mockResolvedValue(
      bill({ totalMinor: 45_000, roundCount: 1, orderIds: ['order-1'] }),
    );
    const view = setUpReal(api, 'guest-token-secret');

    await placeOrder(view);

    expect(view.carts.bindTable).toHaveBeenCalledTimes(1);
    expect(view.carts.bindTable).toHaveBeenCalledWith({ 'X-Dine-In-Token': 'guest-token-secret' });
    expect(view.host.textContent).not.toContain('guest-token-secret');
  });

  it("reads the bill anonymously: the table's own token, never the customer's bearer", async () => {
    const api = newApi();
    const view = setUpReal(api);

    view.fixture.detectChanges();
    await tick(view.fixture);

    expect(api.get).toHaveBeenCalledWith(`/storefront/dine-in/sessions/${SESSION}`, {
      anonymous: true,
      headers: { 'X-Dine-In-Token': 'guest-token-1' },
    });
  });

  it('never lets the guest token reach the sign-in flow or its storage', async () => {
    const api = newApi();
    const view = setUpReal(api, 'guest-token-secret');
    view.session.setAuthenticated(false);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    view.fixture.detectChanges();
    await tick(view.fixture);

    view.host.querySelector<HTMLButtonElement>('[data-testid="dine-in-signin"]')?.click();
    await tick(view.fixture);

    expect(JSON.stringify(navigate.mock.calls)).not.toContain('guest-token-secret');
    const session = Array.from({ length: sessionStorage.length }, (_, i) =>
      sessionStorage.getItem(sessionStorage.key(i) ?? ''),
    ).join('|');
    expect(session).not.toContain('guest-token-secret');
    expect(session).toContain('/dine-in/table');
  });

  it('keeps the placed order and attaches it when the guest comes back to the table', async () => {
    const api = newApi();
    api.mutate.mockRejectedValue(offline());
    const first = setUpReal(api);
    await placeOrder(first);
    expect(roundCalls(api)).toHaveLength(1);

    // The phone reloads the page with signal back: the order id is not in
    // component state any more, only in what the device remembered.
    first.fixture.destroy();
    TestBed.resetTestingModule();
    api.mutate.mockResolvedValue(
      bill({ totalMinor: 45_000, roundCount: 1, orderIds: ['order-1'] }),
    );
    const reloaded = setUpReal(api);
    reloaded.fixture.detectChanges();
    await tick(reloaded.fixture);

    expect(roundCalls(api)).toHaveLength(2);
    expect(roundCalls(api)[1]).toEqual([
      'POST',
      ROUNDS_PATH,
      expect.objectContaining({ body: { orderId: 'order-1' } }),
    ]);
    expect(
      reloaded.host.querySelector('[data-testid="dine-in-bill-total"]')?.textContent,
    ).toContain('45 000');
    expect(reloaded.host.querySelector('[data-testid="dine-in-round-pending"]')).toBeNull();
  });

  it('shows the unattached order, and a retry that lands it on the bill without a reload', async () => {
    const api = newApi();
    api.mutate.mockRejectedValue(offline());
    const view = setUpReal(api);
    await placeOrder(view);

    expect(view.host.querySelector('[data-testid="dine-in-round-pending"]')).not.toBeNull();
    expect(view.host.querySelector('[data-testid="dine-in-order-placed"]')).toBeNull();

    api.mutate.mockResolvedValue(
      bill({ totalMinor: 45_000, roundCount: 1, orderIds: ['order-1'] }),
    );
    view.host.querySelector<HTMLButtonElement>('[data-testid="dine-in-round-retry"]')?.click();
    await tick(view.fixture);

    expect(roundCalls(api)).toHaveLength(2);
    expect(view.host.querySelector('[data-testid="dine-in-round-pending"]')).toBeNull();
    expect(view.host.querySelector('[data-testid="dine-in-bill-total"]')?.textContent).toContain(
      '45 000',
    );
    // Success is the notice going away and the bill moving; only the failure spoke.
    expect(view.host.querySelector('[data-testid="dine-in-round-lost"]')).toBeNull();
    expect(view.host.querySelector('[data-testid="dine-in-order-placed"]')).toBeNull();
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
    await placeOrder(first);

    expect(first.host.querySelector('[data-testid="dine-in-round-lost"]')?.textContent).toContain(
      'dineIn.roundAttachFailed',
    );
    expect(first.host.querySelector('[data-testid="dine-in-round-pending"]')).toBeNull();

    first.fixture.destroy();
    TestBed.resetTestingModule();
    const reloaded = setUpReal(api);
    reloaded.fixture.detectChanges();
    await tick(reloaded.fixture);

    expect(roundCalls(api)).toHaveLength(1);
  });

  it('leaves the queue alone while the guest is signed out, and sends the notice button to sign-in', async () => {
    const api = newApi();
    api.mutate.mockRejectedValue(offline());
    const first = setUpReal(api);
    await placeOrder(first);
    first.fixture.destroy();
    TestBed.resetTestingModule();

    const reloaded = setUpReal(api);
    reloaded.session.setAuthenticated(false);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    reloaded.fixture.detectChanges();
    await tick(reloaded.fixture);

    expect(roundCalls(api)).toHaveLength(1);
    expect(reloaded.host.querySelector('[data-testid="dine-in-round-pending"]')).not.toBeNull();

    reloaded.host.querySelector<HTMLButtonElement>('[data-testid="dine-in-round-retry"]')?.click();
    await tick(reloaded.fixture);

    expect(navigate).toHaveBeenCalledWith(['/auth', 'login']);
    expect(roundCalls(api)).toHaveLength(1);
  });

  it('does not treat a lost signal or a refused sign-in as a refusal: the order stays queued', async () => {
    const api = newApi();
    api.mutate.mockRejectedValue(
      new HorecaOSApiError({ status: 401, code: 'UNAUTHENTICATED', detail: 'Sign in again.' }),
    );
    api.get.mockResolvedValue(bill());
    const view = setUpReal(api);

    await placeOrder(view);

    expect(view.host.querySelector('[data-testid="dine-in-round-pending"]')).not.toBeNull();
    expect(view.host.querySelector('[data-testid="dine-in-round-lost"]')).toBeNull();
  });

  it('a guest token the platform refuses ends the visit, and the unattached order waits for a re-scan', async () => {
    const api = newApi();
    api.mutate.mockRejectedValue(
      new HorecaOSApiError({ status: 401, code: 'UNAUTHENTICATED', detail: 'ended' }),
    );
    const view = setUpReal(api);
    await placeOrder(view);
    expect(view.host.querySelector('[data-testid="dine-in-round-pending"]')).not.toBeNull();

    // The retry reads the bill with the guest token, and is told it has ended.
    api.get.mockRejectedValue(
      new HorecaOSApiError({ status: 401, code: 'UNAUTHENTICATED', detail: 'ended' }),
    );
    view.host.querySelector<HTMLButtonElement>('[data-testid="dine-in-round-retry"]')?.click();
    await tick(view.fixture);

    expect(view.host.querySelector('[data-testid="dine-in-no-admission"]')).not.toBeNull();
    expect(localStorage.getItem(ADMISSION_KEY)).toBeNull();
    expect(localStorage.getItem('horecaos_dinein_pending_rounds')).toContain('order-1');
  });
});

describe('DineInTableComponent -- a dish with options against the real DineInCartService', () => {
  const TENANT = '10000000-0000-0000-0000-000000000001';
  const BRAND = '10000000-0000-0000-0000-000000000002';
  const BRAND_PATH = `/storefront/tenants/${TENANT}/brands/${BRAND}`;
  const CONFIG: AppConfig = {
    apiBaseUrl: '/api/v1',
    tenantId: TENANT,
    brandId: BRAND,
    defaultLocationId: LOCATION,
    channel: 'STOREFRONT',
    yandexMapsApiKey: '',
    brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
  };

  /** The platform's side of a table basket, scripted by path: what the real service would be answered. */
  function scriptedPlatform() {
    let version = 1;
    let lines: PlatformCartLine[] = [];
    const cart = () => cartOf(lines, version);
    return {
      get: vi.fn(async (path: string) => {
        if (path.endsWith('/payment-methods')) {
          return { cartId: 'cart-1', currency: 'UZS', methodCodes: ['CASH'], warnings: [] };
        }
        if (path.startsWith(`${BRAND_PATH}/carts/`)) {
          return cart();
        }
        return bill();
      }),
      mutate: vi.fn(
        async (method: string, path: string, options?: { body?: Record<string, unknown> }) => {
          if (method === 'POST' && path === `${BRAND_PATH}/carts`) {
            return cart();
          }
          if (method === 'PUT' && path.endsWith('/table')) {
            version++;
            return cart();
          }
          if (method === 'PUT' && path.includes('/lines/')) {
            version++;
            lines = [
              {
                lineKey: decodeURIComponent(path.split('/lines/')[1]),
                variantId: String(options?.body?.['variantId']),
                quantity: Number(options?.body?.['quantity']),
                hasCustomerNote: false,
              },
            ];
            return cart();
          }
          if (method === 'POST' && path.endsWith('/pricing')) {
            return {
              cartId: 'cart-1',
              cartVersion: version,
              quoteId: 'quote-1',
              contextHash: 'hash-1',
              currency: 'UZS',
              subtotalMinor: 53_000,
              taxMinor: 0,
              totalMinor: 53_000,
              expiresAt: new Date(Date.now() + 60_000).toISOString(),
              discountMinor: 0,
            };
          }
          throw new Error(`unscripted ${method} ${path}`);
        },
      ),
    };
  }

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('sends the platform the line it keys by the dish and its exact options, with the options in the body', async () => {
    const api = scriptedPlatform();
    localStorage.setItem(
      'horecaos_dinein_admission',
      JSON.stringify({ ...admission(), guestToken: 'guest-token-1' }),
    );
    const menuService = new FakeMenuService();
    menuService.home.mockResolvedValue(menuWithRequiredSize());
    TestBed.configureTestingModule({
      imports: [DineInTableComponent],
      providers: [
        provideRouter([]),
        { provide: ApiClient, useValue: api },
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: MenuService, useValue: menuService },
        { provide: Session, useValue: new FakeSession() },
        { provide: PaymentSessionService, useValue: new FakePaymentSessionService() },
        { provide: LangService, useValue: { langId: () => 'uz' } },
        { provide: TranslateService, useClass: FakeTranslateService },
      ],
    });
    const fixture = TestBed.createComponent(DineInTableComponent);
    const host = fixture.nativeElement as HTMLElement;
    const q = (testId: string) => host.querySelector<HTMLElement>(`[data-testid="${testId}"]`);

    await settle(fixture);
    q('dine-in-choose')!.click();
    await settle(fixture);
    [...host.querySelectorAll<HTMLElement>('[data-testid="modifier-option"]')]
      .find((entry) => entry.textContent?.includes('Large'))!
      .click();
    fixture.detectChanges();
    q('modifier-picker-add')!.click();
    await settle(fixture);

    const [, path, options] = api.mutate.mock.calls.find(
      (call) => call[0] === 'PUT' && String(call[1]).includes('/lines/'),
    ) as [string, string, { body: Record<string, unknown>; expectedVersion: number }];
    expect(path).toBe(
      `${BRAND_PATH}/carts/cart-1/lines/${encodeURIComponent('variant-1+opt-large')}`,
    );
    expect(options.body).toMatchObject({
      variantId: 'variant-1',
      quantity: 1,
      modifierOptionIds: ['opt-large'],
    });
    expect(typeof options.expectedVersion).toBe('number');
    // The order can be placed and shows what was priced.
    expect(q('modifier-picker')).toBeNull();
    expect(q('dine-in-cart-total')?.textContent).toContain('53\u00a0000');
    expect(q('dine-in-custom-line-options')?.textContent).toContain('Large');
  });
});
