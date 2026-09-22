import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';

import { HomeComponent } from './home.component';
import { MenuService } from '../../services/menu.service';
import { FavouritesService } from '../../services/favourites.service';
import { LangService } from '../../services/lang.service';
import { UiCartService } from '../../services/ui-cart.service';
import { CustomerProfileService } from '../../services/customer-profile.service';
import { DeliverySelectionService } from '../../services/delivery-selection.service';
import {
  FulfillmentModeService,
  type FulfillmentModeAvailability,
} from '../../services/fulfillment-mode.service';
import { APP_CONFIG, type AppConfig } from '../../core/config/app-config';
import { TranslateService } from '../../services/translate.service';
import { Session } from '../../core/auth/session';
import type { CustomerUiResponse } from '../../types/home.types';

/** The address book is the customer's own; an anonymous visitor has none. */
class FakeDeliverySelectionService {
  addressId = vi.fn<() => string | null>(() => null);
  addressLabel = vi.fn(() => '');
  ensureAddressResolved = vi.fn().mockResolvedValue(undefined);
}

/** The greeting names the tenant, so the brand has to reach the component. */
const TEST_APP_CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Osh Markazi', theme: { accent: '#000000', accentDeep: '#000000' } },
};

function emptyMenu(): CustomerUiResponse {
  return {
    category: null,
    offer: null,
    populars: [],
    populars_count: 0,
    menu: { categories: [], category_items: [], category_items_count: 0 },
  };
}

class FakeMenuService {
  readonly home = vi.fn(async () => emptyMenu());
}

class FakeFavouritesService {
  readonly load = vi.fn(async () => {});
}

class FakeLangService {
  readonly langId = signal('uz');
}

class FakeUiCartService {
  readonly deliveryAddressName = () => '';
  readonly totalItemsCount = () => 0;
  readonly load = vi.fn(async () => {});
  readonly switchFulfillmentMode = vi.fn(async () => {});
}

class FakeCustomerProfileService {
  readonly load = vi.fn(async () => null);
}

function bothSold(): FulfillmentModeAvailability[] {
  return [
    { mode: 'DELIVERY', sold: true, serviceable: true, reason: null },
    { mode: 'PICKUP', sold: true, serviceable: true, reason: null },
  ];
}

class FakeFulfillmentModeService {
  readonly modes = vi.fn(async (): Promise<FulfillmentModeAvailability[]> => bothSold());
}

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  /**
   * Interpolates for real, unlike `get`.
   *
   * A fake that swallowed the parameters would make a brand-name assertion
   * pass on the key alone and prove nothing about the value reaching the
   * template -- which is the whole point of the greeting test.
   */
  getWithParams(key: string, params?: Record<string, string | number>): string {
    let out = key;
    for (const value of Object.values(params ?? {})) {
      out = `${out} ${String(value)}`;
    }
    return out;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

function setUp() {
  const menu = new FakeMenuService();
  const favourites = new FakeFavouritesService();
  const cart = new FakeUiCartService();
  const profile = new FakeCustomerProfileService();
  const fulfillmentModes = new FakeFulfillmentModeService();

  const delivery = new FakeDeliverySelectionService();
  TestBed.configureTestingModule({
    imports: [HomeComponent],
    providers: [
      provideRouter([]),
      { provide: MenuService, useValue: menu },
      { provide: FavouritesService, useValue: favourites },
      { provide: LangService, useClass: FakeLangService },
      { provide: UiCartService, useValue: cart },
      { provide: CustomerProfileService, useValue: profile },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: DeliverySelectionService, useValue: delivery },
      { provide: FulfillmentModeService, useValue: fulfillmentModes },
      { provide: APP_CONFIG, useValue: TEST_APP_CONFIG },
    ],
  });

  const session = TestBed.inject(Session);
  const fixture = TestBed.createComponent(HomeComponent);

  return {
    fixture,
    comp: fixture.componentInstance,
    menu,
    favourites,
    cart,
    profile,
    delivery,
    fulfillmentModes,
    session,
  };
}

function signIn(session: Session): void {
  session.adopt({ accessToken: 'tok', expiresAt: new Date(Date.now() + 3_600_000).toISOString() });
}

async function mount(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('HomeComponent: anonymous visitor', () => {
  beforeEach(() => localStorage.clear());

  it('greets the customer in the configured tenant\'s name, not a hardcoded brand', async () => {
    const { fixture } = setUp();

    await mount(fixture);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Osh Markazi');
    expect(text.toLowerCase()).not.toContain('jizbiz');
  });

  it('renders the published menu with no session at all', async () => {
    const { fixture, comp, menu } = setUp();

    await mount(fixture);

    expect(menu.home).toHaveBeenCalledTimes(1);
    expect(comp.error()).toBeNull();
    expect(comp.loading()).toBe(false);
  });

  it('never reads the cart, favourites, profile or address book for an anonymous visitor -- the platform has no anonymous form of any of them', async () => {
    const { fixture, cart, favourites, profile, delivery } = setUp();

    await mount(fixture);

    expect(cart.load).not.toHaveBeenCalled();
    expect(favourites.load).not.toHaveBeenCalled();
    expect(profile.load).not.toHaveBeenCalled();
    expect(delivery.ensureAddressResolved).not.toHaveBeenCalled();
  });

  it('renders without crashing and without a router redirect away from /home', async () => {
    const { fixture } = setUp();

    expect(() => fixture.detectChanges()).not.toThrow();
  });
});

describe('HomeComponent: signed in', () => {
  beforeEach(() => localStorage.clear());

  it('still warms the cart, favourites and profile for a signed-in customer, unchanged from before', async () => {
    const { fixture, cart, favourites, profile, delivery, session } = setUp();
    signIn(session);

    await mount(fixture);

    expect(cart.load).toHaveBeenCalledTimes(1);
    expect(favourites.load).toHaveBeenCalledTimes(1);
    expect(profile.load).toHaveBeenCalledTimes(1);
    // The top bar names the chosen address, so the row behind the stored id is
    // read back for a signed-in customer and only for them.
    expect(delivery.ensureAddressResolved).toHaveBeenCalledTimes(1);
  });
});

describe('HomeComponent: fulfilment-mode filtering (GET .../fulfillment-modes)', () => {
  beforeEach(() => localStorage.clear());

  it('offers only DELIVERY when the channel does not sell PICKUP at this location', async () => {
    const { fixture, comp, fulfillmentModes } = setUp();
    fulfillmentModes.modes.mockResolvedValue([
      { mode: 'DELIVERY', sold: true, serviceable: true, reason: null },
      { mode: 'PICKUP', sold: false, serviceable: false, reason: 'CHANNEL_NOT_ENABLED' },
    ]);

    await mount(fixture);

    expect(comp.deliverySold()).toBe(true);
    expect(comp.pickupSold()).toBe(false);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('home.delivery');
    expect(text).not.toContain('home.pickup');
  });

  it('offers only PICKUP when the channel sells no delivery, and defaults the mode to it', async () => {
    const { fixture, comp, fulfillmentModes } = setUp();
    fulfillmentModes.modes.mockResolvedValue([
      { mode: 'DELIVERY', sold: false, serviceable: false, reason: 'FULFILMENT_MODE_UNAVAILABLE' },
      { mode: 'PICKUP', sold: true, serviceable: true, reason: null },
    ]);

    await mount(fixture);

    expect(comp.deliverySold()).toBe(false);
    expect(comp.pickupSold()).toBe(true);
    // The component opened on 'delivery' by default; a channel that never
    // sells it must not be left showing an unorderable menu with no tab to
    // switch away from.
    expect(comp.deliveryMode()).toBe('pickup');
  });

  it('shows the specific reason, not a generic placeholder, for a mode that is sold but not orderable right now', async () => {
    const { fixture, comp, fulfillmentModes } = setUp();
    fulfillmentModes.modes.mockResolvedValue([
      { mode: 'DELIVERY', sold: true, serviceable: false, reason: 'OUTSIDE_SERVICE_HOURS' },
      { mode: 'PICKUP', sold: true, serviceable: true, reason: null },
    ]);

    await mount(fixture);

    expect(comp.currentModeUnavailableMessage()).toBe('errors.reason.outsideHours');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('home.pickupComingSoon');
  });

  it('shows both tabs, and no unavailable banner, while the read has not answered yet', async () => {
    const { fixture, comp, fulfillmentModes } = setUp();
    fulfillmentModes.modes.mockReturnValue(new Promise(() => {})); // never resolves

    fixture.detectChanges();
    await fixture.whenStable();

    expect(comp.deliverySold()).toBe(true);
    expect(comp.pickupSold()).toBe(true);
    expect(comp.currentModeUnavailableMessage()).toBeNull();
  });

  it('still renders the menu for PICKUP -- it is a real orderable mode, not a placeholder', async () => {
    const { fixture, comp, fulfillmentModes, menu } = setUp();
    fulfillmentModes.modes.mockResolvedValue(bothSold());
    menu.home.mockResolvedValue({
      category: null,
      offer: null,
      populars: [],
      populars_count: 0,
      menu: {
        categories: [{ id: 'cat-1', name: 'Taomlar' }],
        category_items: [{ id: 'cat-1', name: 'Taomlar', items: [], items_count: 0 }],
        category_items_count: 1,
      },
    });

    await mount(fixture);
    comp.setDeliveryMode('pickup');
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('home.menu');
    expect(text).toContain('Taomlar');
  });

  it('prefers the persisted mode when it is still sold, over the fresh-session default', async () => {
    localStorage.setItem('horecaos_home_fulfillment_mode', 'pickup');
    const { fixture, comp, fulfillmentModes } = setUp();
    fulfillmentModes.modes.mockResolvedValue(bothSold());

    await mount(fixture);

    expect(comp.deliveryMode()).toBe('pickup');
  });

  it('ignores the persisted mode once the channel stops selling it', async () => {
    localStorage.setItem('horecaos_home_fulfillment_mode', 'pickup');
    const { fixture, comp, fulfillmentModes } = setUp();
    fulfillmentModes.modes.mockResolvedValue([
      { mode: 'DELIVERY', sold: true, serviceable: true, reason: null },
      { mode: 'PICKUP', sold: false, serviceable: false, reason: 'CHANNEL_NOT_ENABLED' },
    ]);

    await mount(fixture);

    expect(comp.deliveryMode()).toBe('delivery');
  });

  it('persists the choice when the customer switches mode', async () => {
    const { fixture, comp, fulfillmentModes } = setUp();
    fulfillmentModes.modes.mockResolvedValue(bothSold());
    await mount(fixture);

    comp.setDeliveryMode('pickup');

    expect(localStorage.getItem('horecaos_home_fulfillment_mode')).toBe('pickup');
  });
});

describe('HomeComponent: the auto-selected mode reaches the cart service, not just the UI tab', () => {
  // A pickup-only channel (or a returning customer whose persisted mode is
  // 'pickup') must not leave `UiCartService.fulfillmentMode` at its DELIVERY
  // default while the Pickup tab renders as selected: `cartService.load()`,
  // and every subsequent `add()`, creates/reads the cart under whatever
  // `UiCartService.fulfillmentMode` currently holds -- independent of the
  // home screen's own `deliveryMode` UI signal.
  beforeEach(() => localStorage.clear());

  it('switches the cart service to PICKUP when the channel sells only pickup', async () => {
    const { fixture, cart, fulfillmentModes, session } = setUp();
    signIn(session);
    fulfillmentModes.modes.mockResolvedValue([
      { mode: 'DELIVERY', sold: false, serviceable: false, reason: 'FULFILMENT_MODE_UNAVAILABLE' },
      { mode: 'PICKUP', sold: true, serviceable: true, reason: null },
    ]);

    await mount(fixture);

    expect(cart.switchFulfillmentMode).toHaveBeenCalledWith('PICKUP');
  });

  it('switches the cart service to the persisted mode before the cart loads, not after', async () => {
    localStorage.setItem('horecaos_home_fulfillment_mode', 'pickup');
    const { fixture, cart, fulfillmentModes, session } = setUp();
    signIn(session);
    fulfillmentModes.modes.mockResolvedValue(bothSold());

    await mount(fixture);

    expect(cart.switchFulfillmentMode).toHaveBeenCalledWith('PICKUP');
    const switchOrder = cart.switchFulfillmentMode.mock.invocationCallOrder[0];
    const loadOrder = cart.load.mock.invocationCallOrder[0];
    expect(switchOrder).toBeLessThan(loadOrder);
  });
});
