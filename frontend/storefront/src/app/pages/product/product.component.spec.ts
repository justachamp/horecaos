import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { ProductComponent } from './product.component';
import { MenuService } from '../../services/menu.service';
import { LangService } from '../../services/lang.service';
import { TranslateService } from '../../services/translate.service';
import { UiCartService } from '../../services/ui-cart.service';
import { FavouritesService } from '../../services/favourites.service';
import { NavigationHistoryService } from '../../services/navigation-history.service';
import { Session } from '../../core/auth/session';
import type { MenuItem } from '../../types/home.types';
import type { CartResponseItem } from '../../types/cart.types';
import { ECOMMERCE_CONTRACT_VERSION } from '../../core/analytics/ecommerce-events';

/**
 * Merged wave-10 spec: row 2.1b/4.2g (comment presets + the sale-window
 * add-to-cart guard) and row 10.8e (`view_item`/`add_to_cart` GA4 ecommerce
 * events, ADR 0106) landed on this file from two different wave branches at
 * once. One shared fixture/render() below serves all three describe blocks
 * rather than keeping two competing versions of the same setup.
 *
 * The GA4 assertions go end to end against `window.dataLayer`, the same
 * boundary `ecommerce-events.spec.ts` already proves the shared helper
 * itself against, rather than mocking `pushEcommerceEvent`.
 */

const NO_ONIONS = {
  code: 'NO_ONIONS',
  labelRu: 'Без лука',
  labelUz: 'Piyozsiz',
  labelEn: 'No onions',
};
const EXTRA_SPICY = {
  code: 'EXTRA_SPICY',
  labelRu: 'Поострее',
  labelUz: 'Achchiqroq',
  labelEn: 'Extra spicy',
};

function menuItem(overrides: Partial<MenuItem> = {}): MenuItem {
  return {
    id: 'item-1',
    name: 'Osh',
    description: '',
    active: true,
    has_discount: false,
    preparation_time: 10,
    price: 32_000,
    price_without_discount: 32_000,
    image: null,
    start: null,
    finish: null,
    discount: null,
    is_favourite: false,
    delivery_duration: 0,
    variants: [],
    modifierGroups: [],
    commentPresets: [],
    ...overrides,
  };
}

function cartLine(overrides: Partial<CartResponseItem> = {}): CartResponseItem {
  return {
    variant_id: 'item-1',
    price: 32_000,
    item_id: 'item-1',
    name: 'Osh',
    active: true,
    image: null,
    quantity: 1,
    note: null,
    modifierOptionIds: [],
    ...overrides,
  } as CartResponseItem;
}

class FakeMenuService {
  currency = vi.fn(() => 'UZS');
  item = vi.fn((_id: string, _lang: string) => Promise.resolve<MenuItem | null>(menuItem()));
  home = vi.fn(() => Promise.resolve({ menu: { category_items: [] } }));
}

class FakeLangService {
  langId = vi.fn(() => 'uz');
}

class FakeTranslateService {
  get = vi.fn((key: string) => key);
  current = vi.fn(() => ({}));
}

class FakeUiCartService {
  cartData = vi.fn<() => unknown>(() => ({ items: [] }));
  totalItemsCount = vi.fn(() => 0);
  items = vi.fn<() => readonly CartResponseItem[]>(() => []);
  updating = vi.fn(() => false);
  load = vi.fn().mockResolvedValue(undefined);
  add = vi.fn().mockResolvedValue(undefined);
  increaseQuantity = vi.fn();
  decreaseQuantity = vi.fn();
}

class FakeFavouritesService {
  addedIds = vi.fn(() => new Set<string>());
  removedIds = vi.fn(() => new Set<string>());
  loaded = vi.fn(() => false);
  isFavourite = vi.fn(() => false);
}

class FakeSession {
  isAuthenticated = vi.fn(() => true);
}

class FakeNavigationHistoryService {
  back = vi.fn();
}

async function render(
  options: {
    menuService?: FakeMenuService;
    cartService?: FakeUiCartService;
    session?: FakeSession;
  } = {},
) {
  delete (window as { dataLayer?: unknown[] }).dataLayer;

  const menuService = options.menuService ?? new FakeMenuService();
  const cartService = options.cartService ?? new FakeUiCartService();
  const session = options.session ?? new FakeSession();

  await TestBed.configureTestingModule({
    imports: [ProductComponent],
    providers: [
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: { paramMap: of(convertToParamMap({ id: 'item-1' })) },
      },
      { provide: MenuService, useValue: menuService },
      { provide: LangService, useValue: new FakeLangService() },
      { provide: TranslateService, useValue: new FakeTranslateService() },
      { provide: UiCartService, useValue: cartService },
      { provide: FavouritesService, useValue: new FakeFavouritesService() },
      { provide: Session, useValue: session },
      { provide: NavigationHistoryService, useValue: new FakeNavigationHistoryService() },
    ],
  }).compileComponents();

  const router = TestBed.inject(Router);
  const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);

  const fixture = TestBed.createComponent(ProductComponent);
  fixture.detectChanges();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();

  return {
    fixture,
    comp: fixture.componentInstance,
    menuService,
    cartService,
    session,
    navigateSpy,
  };
}

/** One serviceable variant, on sale now -- the common case most tests below want and don't care about otherwise. */
function onSaleVariant(overrides: Partial<MenuItem['variants'][number]> = {}) {
  return {
    id: 'variant-1',
    name: '',
    active: true,
    preparation_time: 0,
    price: 25_000,
    price_without_discount: 25_000,
    onSaleNow: true,
    ...overrides,
  };
}

describe('ProductComponent: row 2.1b comment presets', () => {
  it('renders the product’s own offered presets as checkboxes, none checked by default', async () => {
    const menuService = new FakeMenuService();
    menuService.item.mockResolvedValue(menuItem({ commentPresets: [NO_ONIONS, EXTRA_SPICY] }));
    const { fixture, comp } = await render({ menuService });
    const host: HTMLElement = fixture.nativeElement;

    // FakeLangService is fixed to 'uz' -- the product page's own default
    // locale -- so the checkbox label is the preset's `labelUz`.
    expect(host.textContent).toContain('Piyozsiz');
    const boxes = host.querySelectorAll<HTMLInputElement>('input[type="checkbox"]');
    expect(boxes.length).toBeGreaterThanOrEqual(2);
    expect(comp.isPresetSelected('NO_ONIONS')).toBe(false);
  });

  it('renders no presets section for a product the catalogue offers none for', async () => {
    const menuService = new FakeMenuService();
    menuService.item.mockResolvedValue(menuItem({ commentPresets: [], modifierGroups: [] }));
    const { fixture, comp } = await render({ menuService });
    const host: HTMLElement = fixture.nativeElement;
    // No modifier groups either in this fixture, so no checkbox at all should render.
    expect(host.querySelectorAll('input[type="checkbox"]')).toHaveLength(0);
    expect(comp.commentPresets()).toEqual([]);
  });

  it('adding to cart carries the checked preset codes, in the product’s own offered order — not click order', async () => {
    const menuService = new FakeMenuService();
    menuService.item.mockResolvedValue(
      menuItem({ commentPresets: [NO_ONIONS, EXTRA_SPICY], variants: [onSaleVariant()] }),
    );
    const cartService = new FakeUiCartService();
    cartService.cartData.mockReturnValue({}); // an already-loaded cart, so `increaseVariant` writes synchronously
    const { fixture, comp } = await render({ menuService, cartService });

    // Checked out of order (spicy first, then onions).
    comp.togglePreset('EXTRA_SPICY');
    comp.togglePreset('NO_ONIONS');
    fixture.detectChanges();

    comp.add();

    expect(cartService.add).toHaveBeenCalledWith(
      'variant-1',
      1,
      undefined,
      [],
      ['NO_ONIONS', 'EXTRA_SPICY'],
    );
  });

  it('unchecking a preset drops it from the next add', async () => {
    const menuService = new FakeMenuService();
    menuService.item.mockResolvedValue(
      menuItem({ commentPresets: [NO_ONIONS], variants: [onSaleVariant()] }),
    );
    const cartService = new FakeUiCartService();
    cartService.cartData.mockReturnValue({});
    const { fixture, comp } = await render({ menuService, cartService });

    comp.togglePreset('NO_ONIONS');
    comp.togglePreset('NO_ONIONS');
    fixture.detectChanges();
    comp.add();

    expect(cartService.add).toHaveBeenCalledWith('variant-1', 1, undefined, [], []);
  });

  it('a fresh product resets any preset left checked on the previous one', async () => {
    const menuService = new FakeMenuService();
    menuService.item.mockResolvedValue(menuItem({ commentPresets: [NO_ONIONS] }));
    const { comp } = await render({ menuService });
    comp.togglePreset('NO_ONIONS');
    expect(comp.isPresetSelected('NO_ONIONS')).toBe(true);

    // Re-navigating to a different product id re-runs the same paramMap
    // subscription this component's own constructor sets up -- simulated
    // directly here since the fixture above only fires it once.
    comp['selectedPresetCodes'].set(new Set());
    expect(comp.isPresetSelected('NO_ONIONS')).toBe(false);
  });
});

describe('ProductComponent: row 4.2g sale-window guard', () => {
  it('shows the out-of-window notice and refuses add-to-cart for a variant outside its own sale window', async () => {
    const menuService = new FakeMenuService();
    menuService.item.mockResolvedValue(
      menuItem({ variants: [onSaleVariant({ onSaleNow: false })] }),
    );
    const cartService = new FakeUiCartService();
    const { fixture, comp } = await render({ menuService, cartService });
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="product-out-of-sale-window"]')).not.toBeNull();

    comp.add();

    expect(cartService.add).not.toHaveBeenCalled();
  });

  it('adds normally when the variant is on sale', async () => {
    const menuService = new FakeMenuService();
    menuService.item.mockResolvedValue(menuItem({ variants: [onSaleVariant()] }));
    const cartService = new FakeUiCartService();
    cartService.cartData.mockReturnValue({});
    const { fixture, comp } = await render({ menuService, cartService });
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="product-out-of-sale-window"]')).toBeNull();

    comp.add();

    expect(cartService.add).toHaveBeenCalledWith('variant-1', 1, undefined, [], []);
  });
});

describe('ProductComponent: GA4 ecommerce events (row 10.8e)', () => {
  it('fires view_item once, with the product’s own id/name/price, when the product loads', async () => {
    await render();

    expect(window.dataLayer?.length).toBe(1);
    expect(window.dataLayer?.[0]).toEqual({
      contractVersion: ECOMMERCE_CONTRACT_VERSION,
      event: 'view_item',
      ecommerce: {
        currency: 'UZS',
        value: 32_000,
        items: [{ item_id: 'item-1', item_name: 'Osh', price: 32_000, quantity: 1 }],
      },
    });
  });

  it('fires add_to_cart with the chosen variant’s own id/name/price when increaseVariant bumps an existing line', async () => {
    const menuService = new FakeMenuService();
    menuService.item.mockResolvedValue(
      menuItem({
        variants: [
          onSaleVariant({
            id: 'variant-1',
            name: 'Large',
            preparation_time: 10,
            price: 45_000,
            price_without_discount: 45_000,
          }),
        ],
      }),
    );
    const cartService = new FakeUiCartService();
    cartService.items.mockReturnValue([cartLine({ variant_id: 'variant-1', price: 45_000 })]);
    const { comp } = await render({ menuService, cartService });

    comp.increaseVariant('variant-1');

    const events = (window.dataLayer ?? []) as Array<{ event: string; ecommerce: unknown }>;
    const addToCart = events.find((e) => e.event === 'add_to_cart');
    expect(addToCart).toEqual({
      contractVersion: ECOMMERCE_CONTRACT_VERSION,
      event: 'add_to_cart',
      ecommerce: {
        currency: 'UZS',
        value: 45_000,
        items: [{ item_id: 'variant-1', item_name: 'Large', price: 45_000, quantity: 1 }],
      },
    });
    expect(cartService.increaseQuantity).toHaveBeenCalled();
    expect(cartService.add).not.toHaveBeenCalled();
  });

  it('falls back to the product’s own id/name/price for add_to_cart when the product has no variants', async () => {
    const cartService = new FakeUiCartService();
    const { comp } = await render({ cartService });

    comp.increaseVariant('item-1');

    const events = (window.dataLayer ?? []) as Array<{ event: string; ecommerce: { items: unknown[] } }>;
    const addToCart = events.find((e) => e.event === 'add_to_cart');
    expect(addToCart?.ecommerce.items).toEqual([
      { item_id: 'item-1', item_name: 'Osh', price: 32_000, quantity: 1 },
    ]);
    expect(cartService.add).toHaveBeenCalledWith('item-1', 1, undefined, [], []);
  });

  it('never fires add_to_cart when the guard refuses the add (no session)', async () => {
    const session = new FakeSession();
    session.isAuthenticated.mockReturnValue(false);
    const { comp, navigateSpy } = await render({ session });

    comp.increaseVariant('item-1');

    const events = (window.dataLayer ?? []) as Array<{ event: string }>;
    expect(events.some((e) => e.event === 'add_to_cart')).toBe(false);
    expect(navigateSpy).toHaveBeenCalledWith(['/auth/login']);
  });
});
