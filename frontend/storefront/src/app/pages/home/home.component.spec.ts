import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Component, computed, input, signal } from '@angular/core';

import { HomeComponent } from './home.component';
import { MenuService } from '../../services/menu.service';
import { ReviewsService } from '../../services/reviews.service';
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
import { ApiClient } from '../../core/api/api-client';
import { ContentService } from '../../services/content.service';
import { EditorialService } from '../../services/editorial.service';
import { ContentEventsService } from '../../services/content-events.service';
import { ContentGroupsComponent } from '../../shared/content-groups/content-groups.component';
import { FoodCardComponent } from '../../shared/food-card/food-card.component';
import { FoodCarouselComponent } from '../../shared/food-carousel/food-carousel.component';
import type { ContentHome } from '../../types/content.types';
import type { EditorialFeatures } from '../../types/editorial.types';
import type { CustomerUiResponse, MenuItem } from '../../types/home.types';

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
  /**
   * Stands in for `UiCartService.fulfillmentMode`'s computed: the real
   * service reports the *cart's own* mode once one has loaded, independent
   * of whatever `switchFulfillmentMode` was last called with. Reassignable
   * so a test can simulate `load()` pulling in an existing cart whose mode
   * disagrees with the tab the customer's persisted preference selected.
   */
  fulfillmentMode = (): 'DELIVERY' | 'PICKUP' => 'DELIVERY';
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

/** The content document the page is given, as the real service would hold it. */
class FakeContentService {
  readonly home = signal<ContentHome | null>(null);
  readonly carousel = computed(() => this.home()?.carousel ?? []);
  readonly promoGrid = computed(() => this.home()?.promoGrid ?? []);
  readonly groups = computed(() => this.home()?.groups ?? []);
  readonly stories = computed(() => this.home()?.stories ?? []);
  readonly popup = computed(() => this.home()?.popup ?? null);
  readonly brandSwitcher = computed(() => this.home()?.brandSwitcher ?? []);
  readonly layout = computed(() => this.home()?.layout ?? 'STANDARD');
  readonly load = vi.fn(async () => {});
}

/** Which editorial kinds are on, as the real service would hold them. */
class FakeEditorialService {
  readonly features = signal<EditorialFeatures>({
    news: false,
    gallery: false,
    recipes: false,
    vacancies: false,
  });
  readonly loadFeatures = vi.fn(async () => {});
}

class FakeContentEventsService {
  impression = vi.fn();
  click = vi.fn();
  dismissal = vi.fn();
  completion = vi.fn();
}

class FakeApiClient {
  get = vi.fn();
  report = vi.fn(async () => {});
}

/** Food cards need the cart, favourites and session; the page under test only has to place them. */
@Component({
  selector: 'app-food-card',
  template: '<span class="menu-dish" [attr.data-id]="item().id">{{ item().name }}</span>',
})
class StubFoodCardComponent {
  readonly item = input.required<MenuItem>();
}

@Component({
  selector: 'app-food-carousel',
  template: `
    @for (dish of items(); track dish.id) {
      <span class="group-dish" [attr.data-id]="dish.id">{{ dish.name }}</span>
    }
  `,
})
class StubFoodCarouselComponent {
  readonly items = input<MenuItem[]>([]);
}

async function setUp(options: { realContent?: boolean; realEditorial?: boolean } = {}) {
  const menu = new FakeMenuService();
  const favourites = new FakeFavouritesService();
  const cart = new FakeUiCartService();
  const profile = new FakeCustomerProfileService();
  const fulfillmentModes = new FakeFulfillmentModeService();

  const delivery = new FakeDeliverySelectionService();
  const content = new FakeContentService();
  const api = new FakeApiClient();
  const editorial = new FakeEditorialService();
  const editorialProviders = options.realEditorial
    ? []
    : [{ provide: EditorialService, useValue: editorial }];
  const contentProviders = options.realContent
    ? [{ provide: ApiClient, useValue: api }]
    : [
        { provide: ContentService, useValue: content },
        { provide: ContentEventsService, useValue: new FakeContentEventsService() },
        { provide: ApiClient, useValue: api },
      ];
  TestBed.configureTestingModule({
    imports: [HomeComponent],
    providers: [
      ...contentProviders,
      ...editorialProviders,
      provideRouter([]),
      { provide: MenuService, useValue: menu },
      // The public rating block is not under test here: the platform reports nothing to show.
      {
        provide: ReviewsService,
        useValue: {
          publicSummary: vi.fn(async () => ({
            visibility: 'NONE',
            ready: false,
            count: null,
            average: null,
            distribution: null,
          })),
          publicComments: vi.fn(),
        },
      },
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

  TestBed.overrideComponent(HomeComponent, {
    remove: { imports: [FoodCardComponent, FoodCarouselComponent] },
    add: { imports: [StubFoodCardComponent, StubFoodCarouselComponent] },
  });
  TestBed.overrideComponent(ContentGroupsComponent, {
    remove: { imports: [FoodCarouselComponent] },
    add: { imports: [StubFoodCarouselComponent] },
  });
  // The pop-up is behind @defer on the page, so its dependencies resolve asynchronously.
  await TestBed.compileComponents();

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
    content,
    api,
    editorial,
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

  it("greets the customer in the configured tenant's name, not a hardcoded brand", async () => {
    const { fixture } = await setUp();

    await mount(fixture);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Osh Markazi');
    expect(text.toLowerCase()).not.toContain('jizbiz');
  });

  it('renders the published menu with no session at all', async () => {
    const { fixture, comp, menu } = await setUp();

    await mount(fixture);

    expect(menu.home).toHaveBeenCalledTimes(1);
    expect(comp.error()).toBeNull();
    expect(comp.loading()).toBe(false);
  });

  it('never reads the cart, favourites, profile or address book for an anonymous visitor -- the platform has no anonymous form of any of them', async () => {
    const { fixture, cart, favourites, profile, delivery } = await setUp();

    await mount(fixture);

    expect(cart.load).not.toHaveBeenCalled();
    expect(favourites.load).not.toHaveBeenCalled();
    expect(profile.load).not.toHaveBeenCalled();
    expect(delivery.ensureAddressResolved).not.toHaveBeenCalled();
  });

  it('renders without crashing and without a router redirect away from /home', async () => {
    const { fixture } = await setUp();

    expect(() => fixture.detectChanges()).not.toThrow();
  });
});

describe('HomeComponent: signed in', () => {
  beforeEach(() => localStorage.clear());

  it('still warms the cart, favourites and profile for a signed-in customer, unchanged from before', async () => {
    const { fixture, cart, favourites, profile, delivery, session } = await setUp();
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
    const { fixture, comp, fulfillmentModes } = await setUp();
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
    const { fixture, comp, fulfillmentModes } = await setUp();
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
    const { fixture, comp, fulfillmentModes } = await setUp();
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
    const { fixture, comp, fulfillmentModes } = await setUp();
    fulfillmentModes.modes.mockReturnValue(new Promise(() => {})); // never resolves

    fixture.detectChanges();
    await fixture.whenStable();

    expect(comp.deliverySold()).toBe(true);
    expect(comp.pickupSold()).toBe(true);
    expect(comp.currentModeUnavailableMessage()).toBeNull();
  });

  it('still renders the menu for PICKUP -- it is a real orderable mode, not a placeholder', async () => {
    const { fixture, comp, fulfillmentModes, menu } = await setUp();
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
    const { fixture, comp, fulfillmentModes } = await setUp();
    fulfillmentModes.modes.mockResolvedValue(bothSold());

    await mount(fixture);

    expect(comp.deliveryMode()).toBe('pickup');
  });

  it('ignores the persisted mode once the channel stops selling it', async () => {
    localStorage.setItem('horecaos_home_fulfillment_mode', 'pickup');
    const { fixture, comp, fulfillmentModes } = await setUp();
    fulfillmentModes.modes.mockResolvedValue([
      { mode: 'DELIVERY', sold: true, serviceable: true, reason: null },
      { mode: 'PICKUP', sold: false, serviceable: false, reason: 'CHANNEL_NOT_ENABLED' },
    ]);

    await mount(fixture);

    expect(comp.deliveryMode()).toBe('delivery');
  });

  it('persists the choice when the customer switches mode', async () => {
    const { fixture, comp, fulfillmentModes } = await setUp();
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
  // `UiCartService.fulfillmentMode` currently holds. (Once a cart has
  // actually loaded, the reverse sync -- the tab following the cart's own
  // mode -- is covered separately below.)
  beforeEach(() => localStorage.clear());

  it('switches the cart service to PICKUP when the channel sells only pickup', async () => {
    const { fixture, cart, fulfillmentModes, session } = await setUp();
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
    const { fixture, cart, fulfillmentModes, session } = await setUp();
    signIn(session);
    fulfillmentModes.modes.mockResolvedValue(bothSold());

    await mount(fixture);

    expect(cart.switchFulfillmentMode).toHaveBeenCalledWith('PICKUP');
    const switchOrder = cart.switchFulfillmentMode.mock.invocationCallOrder[0];
    const loadOrder = cart.load.mock.invocationCallOrder[0];
    expect(switchOrder).toBeLessThan(loadOrder);
  });
});

describe('HomeComponent: the tab is reconciled with an existing cart, not just the persisted preference', () => {
  // applyDefaultMode() picks `deliveryMode` from the persisted preference and
  // the sold set alone, *before* the cart is known -- `cartService.load()`
  // only runs afterward (`void modesReady.then(() => this.cartService.load())`).
  // `CartService.ensure()` reads by the locally stored cart id and returns
  // whatever mode that cart actually has, ignoring the mode `load()` was
  // called with when a cart already exists. So a channel that stops selling
  // a customer's persisted mode can default the tab to the other mode while
  // the customer's still-open cart -- fetched moments later -- disagrees:
  // `UiCartService.fulfillmentMode()` correctly flips to the cart's own mode
  // (ui-cart.service.ts:122), but nothing pulled `deliveryMode` back into
  // step with it.
  beforeEach(() => localStorage.clear());

  it('pulls the tab onto an existing PICKUP cart even when the persisted/sold-set default picked delivery', async () => {
    localStorage.setItem('horecaos_home_fulfillment_mode', 'pickup');
    const { fixture, comp, cart, fulfillmentModes, session } = await setUp();
    signIn(session);
    // The channel no longer sells PICKUP on this device's persisted path, so
    // applyDefaultMode() falls back to 'delivery' and the tab opens there.
    fulfillmentModes.modes.mockResolvedValue([
      { mode: 'DELIVERY', sold: true, serviceable: true, reason: null },
      { mode: 'PICKUP', sold: false, serviceable: false, reason: 'CHANNEL_NOT_ENABLED' },
    ]);
    // But the customer's own basket, once `load()` fetches it, is still an
    // open PICKUP cart -- `UiCartService.fulfillmentMode()` reports it.
    cart.load.mockImplementation(async () => {
      cart.fulfillmentMode = () => 'PICKUP';
    });

    await mount(fixture);

    expect(comp.deliveryMode()).toBe('pickup');
    expect(localStorage.getItem('horecaos_home_fulfillment_mode')).toBe('pickup');
  });

  it('leaves the tab alone when the loaded cart agrees with the default', async () => {
    const { fixture, comp, cart, fulfillmentModes, session } = await setUp();
    signIn(session);
    fulfillmentModes.modes.mockResolvedValue(bothSold());
    cart.load.mockImplementation(async () => {
      cart.fulfillmentMode = () => 'DELIVERY';
    });

    await mount(fixture);

    expect(comp.deliveryMode()).toBe('delivery');
  });
});

// ---------------------------------------------------------------------------------------------
// ADR 0157: the brand's own content on the home screen.
// ---------------------------------------------------------------------------------------------

const DISH_1 = '10000000-0000-0000-0000-0000000000a1';
const DISH_2 = '10000000-0000-0000-0000-0000000000a2';
const DISH_3 = '10000000-0000-0000-0000-0000000000a3';
const CATEGORY_1 = '10000000-0000-0000-0000-0000000000c1';

function dish(id: string, name: string): MenuItem {
  return { id, name } as MenuItem;
}

/** A menu with two dishes in one category, as `MenuService.home` shapes it. */
function menuWithDishes(offer: CustomerUiResponse['offer'] = null): CustomerUiResponse {
  return {
    category: null,
    offer,
    populars: [],
    populars_count: 0,
    menu: {
      categories: [{ id: CATEGORY_1, name: 'Mains' }],
      category_items: [
        {
          id: CATEGORY_1,
          name: 'Mains',
          items: [dish(DISH_1, 'Plov'), dish(DISH_2, 'Lagman')],
          items_count: 2,
        },
      ],
      category_items_count: 1,
    },
  };
}

function image(alt: string) {
  return {
    kind: 'IMAGE' as const,
    alt,
    renditions: [{ widthPx: 800, url: `/api/v1/storefront/media/${alt}?variant=w800` }],
  };
}

function contentHome(overrides: Partial<ContentHome> = {}): ContentHome {
  return {
    locale: 'uz-Latn',
    carousel: [
      {
        id: 'banner-1',
        kind: 'BANNER',
        revisionNo: 1,
        title: 'Summer offer',
        textLocale: 'uz-Latn',
        media: image('Summer'),
        link: { type: 'NONE' },
      },
    ],
    groups: [],
    stories: [],
    promoGrid: [],
    popup: null,
    brandSwitcher: [],
    layout: 'STANDARD',
    ...overrides,
  };
}

const text = (fixture: ComponentFixture<unknown>) =>
  (fixture.nativeElement as HTMLElement).textContent ?? '';
const query = (fixture: ComponentFixture<unknown>, selector: string) =>
  (fixture.nativeElement as HTMLElement).querySelector(selector);
const queryAll = (fixture: ComponentFixture<unknown>, selector: string) =>
  Array.from((fixture.nativeElement as HTMLElement).querySelectorAll(selector));

describe('HomeComponent: brand content (ADR 0157)', () => {
  beforeEach(() => localStorage.clear());

  it('starts the content read once, as a request of its own beside the menu read', async () => {
    const { fixture, content, menu } = await setUp();

    await mount(fixture);

    expect(content.load).toHaveBeenCalledTimes(1);
    expect(menu.home).toHaveBeenCalledTimes(1);
  });

  it('never makes the menu wait for the content read', async () => {
    const { fixture, comp, content } = await setUp();
    content.load.mockReturnValue(new Promise<void>(() => {})); // never answers

    await mount(fixture);

    expect(comp.uiData()).not.toBeNull();
    expect(comp.loading()).toBe(false);
    expect(comp.error()).toBeNull();
  });

  it('draws no content section while there is no content', async () => {
    const { fixture } = await setUp();

    await mount(fixture);

    expect(query(fixture, '[aria-label="content.carousel"]')).toBeNull();
    expect(query(fixture, '[aria-label="content.promoGrid"]')).toBeNull();
    expect(query(fixture, '[aria-label="content.stories"]')).toBeNull();
    expect(query(fixture, '[role="dialog"]')).toBeNull();
  });

  it('draws the carousel from the document', async () => {
    const { fixture, content } = await setUp();
    await mount(fixture);

    content.home.set(contentHome());
    fixture.detectChanges();

    expect(query(fixture, '[aria-label="content.carousel"]')).not.toBeNull();
    expect(query(fixture, '[aria-label="content.carousel"] img')?.getAttribute('alt')).toBe(
      'Summer',
    );
    expect(text(fixture)).toContain('Summer offer');
  });

  it('draws the promo grid and the stories rail from the document', async () => {
    const { fixture, content } = await setUp();
    await mount(fixture);

    content.home.set(
      contentHome({
        carousel: [],
        promoGrid: [
          {
            id: 'card-1',
            kind: 'PROMO_CARD',
            revisionNo: 1,
            title: 'Two for one',
            textLocale: 'uz-Latn',
            media: image('Card'),
            link: { type: 'CART' },
          },
        ],
        stories: [
          {
            id: 'story-1',
            revisionNo: 1,
            title: 'New menu',
            textLocale: 'uz-Latn',
            slides: [{ media: image('Slide'), durationSeconds: 5, link: { type: 'NONE' } }],
          },
        ],
      }),
    );
    fixture.detectChanges();

    expect(query(fixture, '[aria-label="content.promoGrid"] a')?.getAttribute('href')).toBe(
      '/cart',
    );
    expect(query(fixture, '[aria-label="content.stories"] button')).not.toBeNull();
  });

  it('joins a manual group against the menu the page already holds, skipping an id the menu lacks', async () => {
    const { fixture, content, menu } = await setUp();
    menu.home.mockResolvedValue(menuWithDishes());
    await mount(fixture);

    content.home.set(
      contentHome({
        carousel: [],
        groups: [
          {
            id: 'group-1',
            revisionNo: 1,
            title: 'Chef picks',
            textLocale: 'uz-Latn',
            source: { kind: 'MANUAL', productIds: [DISH_2, DISH_3, DISH_1] },
          },
        ],
      }),
    );
    fixture.detectChanges();

    expect(text(fixture)).toContain('Chef picks');
    expect(queryAll(fixture, '.group-dish').map((d) => d.getAttribute('data-id'))).toEqual([
      DISH_2,
      DISH_1,
    ]);
  });

  it('draws a group by category from the same menu and caps it at the limit', async () => {
    const { fixture, content, menu } = await setUp();
    menu.home.mockResolvedValue(menuWithDishes());
    await mount(fixture);

    content.home.set(
      contentHome({
        carousel: [],
        groups: [
          {
            id: 'group-2',
            revisionNo: 1,
            title: 'Fresh mains',
            textLocale: 'uz-Latn',
            source: { kind: 'BY_CATEGORY', categoryId: CATEGORY_1, order: 'NEWEST', limit: 1 },
          },
        ],
      }),
    );
    fixture.detectChanges();

    expect(queryAll(fixture, '.group-dish').map((d) => d.getAttribute('data-id'))).toEqual([
      DISH_1,
    ]);
  });

  it('draws nothing for a group the menu has none of', async () => {
    const { fixture, content, menu } = await setUp();
    menu.home.mockResolvedValue(menuWithDishes());
    await mount(fixture);

    content.home.set(
      contentHome({
        carousel: [],
        groups: [
          {
            id: 'group-3',
            revisionNo: 1,
            title: 'Ghost group',
            textLocale: 'uz-Latn',
            source: { kind: 'MANUAL', productIds: [DISH_3] },
          },
        ],
      }),
    );
    fixture.detectChanges();

    expect(text(fixture)).not.toContain('Ghost group');
  });

  it('shows the pop-up once its delay is up', async () => {
    const { fixture, content } = await setUp();
    await mount(fixture);

    vi.useFakeTimers();
    try {
      content.home.set(
        contentHome({
          carousel: [],
          popup: {
            id: 'pop-1',
            revisionNo: 1,
            title: 'Welcome back',
            textLocale: 'uz-Latn',
            link: { type: 'NONE' },
            showAfterSeconds: 5,
            cap: { mode: 'ONCE_PER_DAY', n: 1 },
          },
        }),
      );
      fixture.detectChanges();
      // The dialog's code is behind @defer: let it arrive before the delay is counted.
      await vi.dynamicImportSettled();
      fixture.detectChanges();
      expect(query(fixture, '[role="dialog"]')).toBeNull();

      vi.advanceTimersByTime(5000);
      fixture.detectChanges();

      expect(query(fixture, '[role="dialog"]')?.textContent).toContain('Welcome back');
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('HomeComponent: the hard-coded promo tile and ADR 0112 personalised offer slot', () => {
  beforeEach(() => localStorage.clear());

  const offer: CustomerUiResponse['offer'] = {
    id: 1,
    name: 'Your offer',
    items: [{ id: 'o1', name: 'Offer', image: 'https://img.example/offer.jpg', priority: 1 }],
    items_count: 1,
  };

  it('no longer carries the home-promo.jpg tile', async () => {
    const { fixture, menu } = await setUp();
    menu.home.mockResolvedValue(menuWithDishes(offer));

    await mount(fixture);

    expect((fixture.nativeElement as HTMLElement).innerHTML).not.toContain('home-promo');
  });

  it('draws nothing for the offer slot while the offer is null, as it does today', async () => {
    const { fixture, comp } = await setUp();

    await mount(fixture);

    expect(comp.offerData()).toBeNull();
    expect(query(fixture, 'section.grid')).toBeNull();
  });

  it("still draws a personalised offer's own tile when there is one", async () => {
    const { fixture, comp, menu } = await setUp();
    menu.home.mockResolvedValue(menuWithDishes(offer));

    await mount(fixture);

    expect(comp.offerData()?.items[0].image).toBe('https://img.example/offer.jpg');
    const tile = query(fixture, 'section.grid a') as HTMLElement | null;
    expect(tile?.getAttribute('style')).toContain('https://img.example/offer.jpg');
  });
});

describe('HomeComponent: a failing content read leaves the menu and the page untouched', () => {
  beforeEach(() => localStorage.clear());

  const failures: [string, () => Promise<unknown>][] = [
    ['a network failure', () => Promise.reject(new Error('offline'))],
    ['a 404', () => Promise.reject({ status: 404 })],
    ['a 500', () => Promise.reject({ status: 500 })],
    ['a body that is not a document', () => Promise.resolve('<html>bad gateway</html>')],
  ];

  it.each(failures)(
    '%s: the menu still renders and nothing else changes',
    async (_label, answer) => {
      const { fixture, comp, menu, api } = await setUp({ realContent: true });
      menu.home.mockResolvedValue(menuWithDishes());
      api.get.mockImplementation(answer);

      await mount(fixture);

      expect(api.get).toHaveBeenCalledTimes(1);
      expect(comp.error()).toBeNull();
      expect(comp.loading()).toBe(false);
      expect(queryAll(fixture, '.menu-dish').map((d) => d.getAttribute('data-id'))).toEqual([
        DISH_1,
        DISH_2,
      ]);
      expect(query(fixture, '[aria-label="content.carousel"]')).toBeNull();
      expect(query(fixture, '[role="dialog"]')).toBeNull();
      expect(text(fixture)).toContain('home.welcome');
    },
  );

  it('the content read is its own request: it carries no menu and asks for the content document', async () => {
    const { fixture, api } = await setUp({ realContent: true });
    api.get.mockResolvedValue({ locale: 'uz-Latn', carousel: [] });

    await mount(fixture);

    expect(api.get).toHaveBeenCalledTimes(1);
    expect(String(api.get.mock.calls[0][0])).toContain('/content/home');
  });

  it('draws the content when the real service reads a good document', async () => {
    const { fixture, api } = await setUp({ realContent: true });
    api.get.mockResolvedValue({
      locale: 'uz-Latn',
      carousel: [
        {
          id: 'banner-9',
          kind: 'BANNER',
          revisionNo: 2,
          title: 'From the server',
          textLocale: 'uz-Latn',
          media: {
            kind: 'IMAGE',
            alt: 'Server banner',
            renditions: [{ widthPx: 800, url: '/api/v1/storefront/media/x?variant=w800' }],
          },
          link: { type: 'NONE' },
        },
      ],
    });

    await mount(fixture);
    fixture.detectChanges();

    expect(text(fixture)).toContain('From the server');
  });
});

describe('HomeComponent: entry points to the editorial pages (ADR 0157, Decision 7)', () => {
  beforeEach(() => localStorage.clear());

  const entryLinks = (fixture: ComponentFixture<unknown>) =>
    queryAll(fixture, 'nav[aria-label="content.editorialSection"] a').map((a) =>
      a.getAttribute('href'),
    );

  it('asks which kinds are on, once, as a read of its own', async () => {
    const { fixture, editorial } = await setUp();

    await mount(fixture);

    expect(editorial.loadFeatures).toHaveBeenCalledTimes(1);
  });

  it('draws no entry points while every kind is off', async () => {
    const { fixture } = await setUp();

    await mount(fixture);

    expect(query(fixture, 'nav[aria-label="content.editorialSection"]')).toBeNull();
  });

  it('draws an entry point for each kind that is on', async () => {
    const { fixture, editorial } = await setUp();
    await mount(fixture);

    editorial.features.set({ news: true, gallery: false, recipes: true, vacancies: true });
    fixture.detectChanges();

    expect(entryLinks(fixture)).toEqual(['/news', '/recipes', '/careers']);
  });

  it('a failed features read means no entry points, and the menu is untouched', async () => {
    const { fixture, comp, menu, api } = await setUp({ realEditorial: true });
    menu.home.mockResolvedValue(menuWithDishes());
    api.get.mockRejectedValue(new Error('offline'));

    await mount(fixture);

    expect(entryLinks(fixture)).toEqual([]);
    expect(comp.error()).toBeNull();
    expect(queryAll(fixture, '.menu-dish')).toHaveLength(2);
  });
});

/** A menu of `count` categories named `Category 1`, `Category 2`, ... */
function menuOf(count: number) {
  const items = Array.from({ length: count }, (_, i) => ({
    id: `cat-${i + 1}`,
    name: `Category ${i + 1}`,
    items: [],
    items_count: 0,
  }));
  return {
    category: null,
    offer: null,
    populars: [],
    populars_count: 0,
    menu: {
      categories: items.map(({ id, name }) => ({ id, name })),
      category_items: items,
      category_items_count: count,
    },
  };
}

/** The pills in the sticky menu strip, by their text, «All» and the overlay opener left out. */
function stripNames(fixture: ComponentFixture<unknown>): string[] {
  return queryAll(fixture, 'button')
    .map((button) => (button.textContent ?? '').trim())
    .filter((name) => name.startsWith('Category '));
}

/** The overlay's code is behind @defer: it is drawn once the chunk has loaded, not in the click's own turn. */
async function openedOverlay(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await vi.waitFor(() => {
    fixture.detectChanges();
    expect(query(fixture, '[role="dialog"]')).not.toBeNull();
  });
}

describe('HomeComponent: the brand switcher and the marketplace layout (ADR 0183 D17)', () => {
  beforeEach(() => localStorage.clear());

  it('draws no switcher and the whole category strip when the brand uses neither', async () => {
    const { fixture, content, menu } = await setUp();
    menu.home.mockResolvedValue(menuOf(8));
    content.home.set(contentHome());

    await mount(fixture);

    expect(query(fixture, '[data-testid="brand-switcher"]')).toBeNull();
    expect(query(fixture, '[data-testid="all-categories-open"]')).toBeNull();
    expect(stripNames(fixture)).toHaveLength(8);
  });

  it('draws the other brands as links to their own websites', async () => {
    const { fixture, content } = await setUp();
    content.home.set(
      contentHome({
        brandSwitcher: [
          { targetBrandId: 'b-2', name: 'Pizza House', url: 'https://pizza.example.uz/' },
          { targetBrandId: 'b-3', name: 'Sushi Bar', url: 'https://sushi.example.uz/' },
        ],
      }),
    );

    await mount(fixture);

    const links = queryAll(fixture, '[data-testid="brand-switcher"] a');
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      'https://pizza.example.uz/',
      'https://sushi.example.uz/',
    ]);
    expect(links.map((link) => (link.textContent ?? '').trim())).toEqual([
      expect.stringContaining('Pizza House'),
      expect.stringContaining('Sushi Bar'),
    ]);
  });

  it('shows a compact strip and an «all categories» button in the marketplace layout', async () => {
    const { fixture, content, menu } = await setUp();
    menu.home.mockResolvedValue(menuOf(8));
    content.home.set(contentHome({ layout: 'MARKETPLACE' }));

    await mount(fixture);

    expect(stripNames(fixture)).toEqual([
      'Category 1',
      'Category 2',
      'Category 3',
      'Category 4',
      'Category 5',
    ]);
    expect(query(fixture, '[data-testid="all-categories-open"]')).not.toBeNull();
    expect(query(fixture, '[role="dialog"]')).toBeNull();
  });

  it('lists every category in the overlay, and a choice made there applies and closes it', async () => {
    const { fixture, comp, content, menu } = await setUp();
    menu.home.mockResolvedValue(menuOf(8));
    content.home.set(contentHome({ layout: 'MARKETPLACE' }));
    await mount(fixture);

    (query(fixture, '[data-testid="all-categories-open"]') as HTMLButtonElement).click();
    await openedOverlay(fixture);

    const dialog = query(fixture, '[role="dialog"]') as HTMLElement;
    expect(dialog).not.toBeNull();
    expect(dialog.querySelectorAll('li')).toHaveLength(9);

    (query(fixture, '[data-testid="categories-overlay-cat-7"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(comp.selectedCategoryId()).toBe('cat-7');
    expect(query(fixture, '[role="dialog"]')).toBeNull();
    // The strip keeps the chosen category in view, in place of the last compact one.
    expect(stripNames(fixture)).toEqual([
      'Category 1',
      'Category 2',
      'Category 3',
      'Category 4',
      'Category 7',
    ]);
  });

  it('closes the overlay with Escape and with the backdrop, choosing nothing', async () => {
    const { fixture, comp, content, menu } = await setUp();
    menu.home.mockResolvedValue(menuOf(8));
    content.home.set(contentHome({ layout: 'MARKETPLACE' }));
    await mount(fixture);

    (query(fixture, '[data-testid="all-categories-open"]') as HTMLButtonElement).click();
    await openedOverlay(fixture);
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(query(fixture, '[role="dialog"]')).toBeNull();

    (query(fixture, '[data-testid="all-categories-open"]') as HTMLButtonElement).click();
    await openedOverlay(fixture);
    (query(fixture, '[data-testid="categories-overlay-backdrop"]') as HTMLElement).click();
    fixture.detectChanges();
    expect(query(fixture, '[role="dialog"]')).toBeNull();
    expect(comp.selectedCategoryId()).toBeNull();
  });

  it("keeps today's strip when the layout is one this client does not know", async () => {
    const { fixture, content, menu } = await setUp();
    menu.home.mockResolvedValue(menuOf(8));
    content.home.set(contentHome({ layout: 'STANDARD' }));

    await mount(fixture);

    expect(stripNames(fixture)).toHaveLength(8);
  });
});
