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
  getWithParams = vi.fn((key: string) => key);
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
  lineAmount = vi.fn((item: CartResponseItem) => item.price * item.quantity);
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
    remainingQuantity: null,
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

describe('ProductComponent: no orderable variant (row 4.2g, product-id fallback)', () => {
  it('resolves variantId() to null, disables the add button, and never sends the product id as a variant id', async () => {
    const menuService = new FakeMenuService();
    // Every variant the product has is 86'd -- `active: false` is exactly
    // `MenuItemVariant.active`'s doc ("the platform already drops...", i.e.
    // `orderable`) at false for the only variant, the real-world shape of
    // "every variant is unorderable" (the platform never publishes a product
    // with a genuinely empty `variants` array -- see
    // `StorefrontCatalogQuery.menuFor`'s own `variants.isEmpty()` continue).
    menuService.item.mockResolvedValue(menuItem({ variants: [onSaleVariant({ active: false })] }));
    const cartService = new FakeUiCartService();
    cartService.cartData.mockReturnValue({});
    const { fixture, comp } = await render({ menuService, cartService });
    const host: HTMLElement = fixture.nativeElement;

    expect(comp.variantId()).toBeNull();

    const addButton = host.querySelector<HTMLButtonElement>('[data-testid="product-add-to-cart"]');
    expect(addButton).not.toBeNull();
    expect(addButton!.disabled).toBe(true);

    // Clicking (or calling the handler directly) must be a no-op -- the
    // product's own id ('item-1') must never reach the cart as a variant id.
    comp.add();

    expect(cartService.add).not.toHaveBeenCalled();
  });
});

describe('ProductComponent: rows 4.4c/4.4d low-stock display', () => {
  it('shows the low-stock notice when the server reports a remaining count', async () => {
    const menuService = new FakeMenuService();
    menuService.item.mockResolvedValue(
      menuItem({ variants: [onSaleVariant({ remainingQuantity: 2 })] }),
    );
    const { fixture } = await render({ menuService });
    const host: HTMLElement = fixture.nativeElement;

    const notice = host.querySelector('[data-testid="product-low-stock"]');
    expect(notice).not.toBeNull();
    expect(notice?.textContent).toContain('product.lowStock');
  });

  it('shows no low-stock notice when the server sent no remaining count (plenty of stock, or untracked)', async () => {
    const menuService = new FakeMenuService();
    menuService.item.mockResolvedValue(
      menuItem({ variants: [onSaleVariant({ remainingQuantity: null })] }),
    );
    const { fixture } = await render({ menuService });
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="product-low-stock"]')).toBeNull();
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

    const events = (window.dataLayer ?? []) as Array<{
      event: string;
      ecommerce: { items: unknown[] };
    }>;
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

describe('ProductComponent: a combo (ADR 0136)', () => {
  const component = (
    id: string,
    name: string,
    amountMinor: number | null,
    overrides: Partial<NonNullable<MenuItem['comboGroups']>[number]['components'][number]> = {},
  ) => ({
    id,
    name,
    variantName: null,
    defaultQuantity: 1,
    active: true,
    amountMinor,
    ...overrides,
  });

  const comboItem = (groups?: MenuItem['comboGroups']) =>
    menuItem({
      id: 'item-1',
      name: 'Lunch box',
      price: 22_000,
      variants: [onSaleVariant({ id: 'v-lunch', price: 22_000 })],
      modifierGroups: [
        {
          id: 'g-mod',
          name: 'Extras',
          required: false,
          minimumSelections: 0,
          maximumSelections: 2,
          allowSameOptionMultipleTimes: false,
          options: [],
        },
      ],
      comboGroups: groups ?? [
        {
          id: 'g-main',
          name: 'Main',
          minimumSelections: 1,
          maximumSelections: 1,
          allowSameComponentMultipleTimes: false,
          components: [
            component('c-burger', 'Burger', 25_000),
            component('c-wrap', 'Wrap', 22_000),
          ],
        },
        {
          id: 'g-drink',
          name: 'Drink',
          minimumSelections: 0,
          maximumSelections: 1,
          allowSameComponentMultipleTimes: false,
          components: [component('c-cola', 'Cola', 3_000)],
        },
      ],
    });

  async function renderCombo(groups?: MenuItem['comboGroups'], lines: CartResponseItem[] = []) {
    const menuService = new FakeMenuService();
    menuService.item.mockResolvedValue(comboItem(groups));
    const cartService = new FakeUiCartService();
    cartService.cartData.mockReturnValue({});
    cartService.items.mockReturnValue(lines);
    return { ...(await render({ menuService, cartService })), cartService };
  }

  it('asks for the combo’s choices, and not for modifiers the container carries', async () => {
    const { fixture, comp } = await renderCombo();
    const host: HTMLElement = fixture.nativeElement;

    expect(comp.isCombo()).toBe(true);
    expect(host.querySelector('[data-testid="product-combo"]')).not.toBeNull();
    expect(host.querySelectorAll('[data-testid="combo-group"]')).toHaveLength(2);
    expect(comp.modifierGroups()).toEqual([]);
    expect(host.textContent).not.toContain('Extras');
  });

  it('will not add the container until every choice is within its range', async () => {
    const { fixture, comp, cartService } = await renderCombo();
    const host: HTMLElement = fixture.nativeElement;

    comp.add();
    expect(cartService.add).not.toHaveBeenCalled();
    expect(host.querySelector('[data-testid="product-combo-incomplete"]')).not.toBeNull();

    comp.setComboPicks({ 'c-burger': 1 });
    fixture.detectChanges();
    expect(host.querySelector('[data-testid="product-combo-incomplete"]')).toBeNull();
    expect(comp.modifiersValid()).toBe(true);
  });

  it('adds the container with the picks made, never the components as lines of their own', async () => {
    const { fixture, comp, cartService } = await renderCombo();

    comp.setComboPicks({ 'c-wrap': 1, 'c-cola': 1 });
    fixture.detectChanges();
    comp.add();

    expect(cartService.add).toHaveBeenCalledWith(
      'v-lunch',
      1,
      undefined,
      [],
      [],
      [
        { componentId: 'c-cola', quantity: 1 },
        { componentId: 'c-wrap', quantity: 1 },
      ],
    );
  });

  it('reads the add button as what this combo costs once complete, and as the least it costs before', async () => {
    const { fixture, comp } = await renderCombo();
    const variant = comp.variants()[0];

    expect(comp.variantPriceLabel(variant)).toContain('product.fromPrice');

    comp.setComboPicks({ 'c-burger': 1, 'c-cola': 1 });
    fixture.detectChanges();

    expect(comp.variantPriceLabel(variant)).toContain('28');
  });

  it('says a picked component with no price has none, rather than showing a smaller total', async () => {
    const { fixture, comp } = await renderCombo([
      {
        id: 'g-main',
        name: 'Main',
        minimumSelections: 1,
        maximumSelections: 1,
        allowSameComponentMultipleTimes: false,
        components: [component('c-burger', 'Burger', null)],
      },
    ]);

    comp.setComboPicks({ 'c-burger': 1 });
    fixture.detectChanges();

    expect(comp.variantPriceLabel(comp.variants()[0])).toBe('product.comboNotPriced');
  });

  it('bumps the line with exactly these picks and no other: another choice is another line', async () => {
    const line: CartResponseItem = cartLine({
      variant_id: 'v-lunch',
      item_id: 'v-lunchcabc',
      quantity: 1,
      comboPicks: [{ componentId: 'c-burger', quantity: 1 }],
    });
    const { fixture, comp, cartService } = await renderCombo(undefined, [line]);

    comp.setComboPicks({ 'c-burger': 1 });
    fixture.detectChanges();
    comp.add();
    expect(cartService.increaseQuantity).toHaveBeenCalledWith(line);
    expect(cartService.add).not.toHaveBeenCalled();

    comp.setComboPicks({ 'c-wrap': 1 });
    fixture.detectChanges();
    comp.add();
    expect(cartService.add).toHaveBeenCalledWith(
      'v-lunch',
      1,
      undefined,
      [],
      [],
      [{ componentId: 'c-wrap', quantity: 1 }],
    );
  });

  it('cannot be added while a choice has nothing orderable to fill it, and says so', async () => {
    const { fixture, comp, cartService } = await renderCombo([
      {
        id: 'g-main',
        name: 'Main',
        minimumSelections: 1,
        maximumSelections: 1,
        allowSameComponentMultipleTimes: false,
        components: [component('c-burger', 'Burger', 25_000, { active: false })],
      },
    ]);
    const host: HTMLElement = fixture.nativeElement;

    expect(comp.comboUnavailable()).toBe(true);
    expect(host.querySelector('[data-testid="product-combo-unavailable"]')).not.toBeNull();
    comp.add();
    expect(cartService.add).not.toHaveBeenCalled();
  });

  it('starts a fresh product with nothing picked', async () => {
    const { comp } = await renderCombo();

    expect(comp.comboPicks()).toEqual({});
    expect(comp.comboTouched()).toBe(false);
  });
});

describe('ProductComponent: portions and weighed items (ADR 0137)', () => {
  const NBSP = '\u00a0';

  const SPLITTABLE = { catchweight: false, splittable: true, portionSize: 0.5 } as const;
  const CAKE = {
    catchweight: true,
    catchweightQuantumGrams: 100,
    catchweightNominalGrams: 1_200,
    splittable: false,
  } as const;

  async function open(
    variant: ReturnType<typeof onSaleVariant>,
    lines: readonly CartResponseItem[] = [],
    configureCart: (cart: FakeUiCartService) => void = () => undefined,
  ) {
    const menuService = new FakeMenuService();
    menuService.item.mockResolvedValue(menuItem({ variants: [variant] }));
    const cartService = new FakeUiCartService();
    cartService.cartData.mockReturnValue({});
    cartService.items.mockReturnValue(lines);
    configureCart(cartService);
    const rendered = await render({ menuService, cartService });
    return { ...rendered, cartService };
  }

  const normalised = (text: string | null | undefined) => (text ?? '').replace(/\s+/g, ' ');

  it('shows a variant’s weight and nutrition beside its price', async () => {
    const { fixture } = await open(
      onSaleVariant({
        physical: {
          catchweight: false,
          splittable: false,
          netWeightGrams: 350,
          nutrition: { caloriesKcalPer100: 215 },
        },
      }),
    );
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="physical-measure"]')?.textContent?.trim()).toBe(
      `350${NBSP}g`,
    );
    expect(host.querySelector('[data-testid="physical-nutrition"]')).not.toBeNull();
  });

  it('shows nothing physical for a fixed unit', async () => {
    const { fixture } = await open(onSaleVariant());

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="physical-facts"]'),
    ).toBeNull();
  });

  it('offers a weighed variant at its estimated price, marked as an estimate, and says the final weight is set at handover', async () => {
    const { fixture } = await open(onSaleVariant({ price: 15_000, physical: CAKE }));
    const host: HTMLElement = fixture.nativeElement;

    const addButton = host.querySelector('button[aria-label="cart.addToCart"]') as HTMLElement;
    expect(normalised(addButton.textContent)).toContain('≈');
    expect(normalised(addButton.textContent)).toContain('180 000');
    expect(host.querySelector('[data-testid="physical-final-weight-notice"]')).not.toBeNull();
  });

  it('starts a splittable variant at one whole portion', async () => {
    const { comp, cartService } = await open(onSaleVariant({ physical: SPLITTABLE }));

    comp.increaseVariant('variant-1');

    expect(cartService.add).toHaveBeenCalledWith('variant-1', 1, undefined, [], []);
  });

  it('starts a variant whose portion does not divide one at the first quantity the cart accepts', async () => {
    const { comp, cartService } = await open(
      onSaleVariant({ physical: { catchweight: false, splittable: true, portionSize: 0.3 } }),
    );

    comp.increaseVariant('variant-1');

    expect(cartService.add).toHaveBeenCalledWith('variant-1', 1.2, undefined, [], []);
  });

  it('hands a line already in the basket to the cart, which knows its portion step', async () => {
    const line = cartLine({
      variant_id: 'variant-1',
      quantity: 0.5,
      physical: SPLITTABLE,
    });
    const { comp, cartService } = await open(onSaleVariant({ physical: SPLITTABLE }), [line]);

    comp.increaseVariant('variant-1');

    expect(cartService.increaseQuantity).toHaveBeenCalledWith(line);
  });

  it('writes a half portion with the language’s decimal mark, and its portion unit', async () => {
    const line = cartLine({ variant_id: 'variant-1', quantity: 0.5, physical: SPLITTABLE });
    const { fixture } = await open(onSaleVariant({ physical: SPLITTABLE }), [line]);

    expect(normalised((fixture.nativeElement as HTMLElement).textContent)).toContain(
      '0,5 physical.portionsUnit',
    );
  });

  it('writes a whole quantity of a plain variant as before, in pieces', async () => {
    const line = cartLine({ variant_id: 'variant-1', quantity: 3 });
    const { fixture } = await open(onSaleVariant(), [line]);

    expect(normalised((fixture.nativeElement as HTMLElement).textContent)).toContain(
      '3 common.itemsUnit',
    );
  });

  it('prices a line through the cart, and marks a weighed one as an estimate', async () => {
    const line = cartLine({ variant_id: 'variant-1', price: 15_000, quantity: 2, physical: CAKE });
    const { fixture } = await open(
      onSaleVariant({ price: 15_000, physical: CAKE }),
      [line],
      (cart) => cart.lineAmount.mockReturnValue(360_000),
    );

    expect(normalised((fixture.nativeElement as HTMLElement).textContent)).toContain('≈ 360 000');
  });
});

describe('ProductComponent: a portion’s own groups and second-level choices (ADR 0136)', () => {
  const option = (id: string, label: string, extra: Record<string, unknown> = {}) => ({
    id,
    label,
    amountMinor: null,
    maximumQuantity: 1,
    ...extra,
  });
  const heat = {
    id: 'heat',
    name: 'Heat',
    required: true,
    minimumSelections: 1,
    maximumSelections: 1,
    allowSameOptionMultipleTimes: false,
    options: [option('hot', 'Hot'), option('mild', 'Mild')],
  };
  const sauces = {
    id: 'sauces',
    name: 'Sauces',
    required: false,
    minimumSelections: 0,
    maximumSelections: 2,
    allowSameOptionMultipleTimes: false,
    options: [option('chili', 'Chili', { nestedGroups: [heat] }), option('garlic', 'Garlic')],
  };
  const dips = {
    id: 'dips',
    name: 'Dips',
    required: true,
    minimumSelections: 1,
    maximumSelections: 1,
    allowSameOptionMultipleTimes: false,
    options: [option('ketchup', 'Ketchup'), option('mayo', 'Mayo')],
  };

  async function renderItem(item: MenuItem, lines: CartResponseItem[] = []) {
    const menuService = new FakeMenuService();
    menuService.item.mockResolvedValue(item);
    const cartService = new FakeUiCartService();
    cartService.cartData.mockReturnValue({});
    cartService.items.mockReturnValue(lines);
    return { ...(await render({ menuService, cartService })), cartService };
  }

  const saucy = () =>
    menuItem({
      variants: [onSaleVariant()],
      modifierGroups: [sauces] as MenuItem['modifierGroups'],
    });

  it('asks nothing under an option nobody chose, and draws its required group once it is chosen', async () => {
    const { fixture, comp, cartService } = await renderItem(saucy());
    const host: HTMLElement = fixture.nativeElement;

    expect(comp.modifiersValid()).toBe(true);
    expect(host.querySelector('[data-testid="nested-group"]')).toBeNull();

    comp.toggleOption(sauces as MenuItem['modifierGroups'][number], 'chili');
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="nested-group"]')).not.toBeNull();
    expect(comp.modifiersValid()).toBe(false);
    comp.add();
    expect(cartService.add).not.toHaveBeenCalled();
  });

  it('adds with the answers under the first-level option that asked for them', async () => {
    const { fixture, comp, cartService } = await renderItem(saucy());

    comp.toggleOption(sauces as MenuItem['modifierGroups'][number], 'chili');
    comp.toggleNested('chili', heat as MenuItem['modifierGroups'][number], 'hot');
    fixture.detectChanges();
    expect(comp.modifiersValid()).toBe(true);
    comp.add();

    expect(cartService.add).toHaveBeenCalledWith(
      'variant-1',
      1,
      undefined,
      ['chili'],
      [],
      undefined,
      [{ parentOptionId: 'chili', optionId: 'hot' }],
    );
  });

  it('forgets the answers when the option that asked for them is unchecked', async () => {
    const { comp, cartService } = await renderItem(saucy());
    const group = sauces as MenuItem['modifierGroups'][number];

    comp.toggleOption(group, 'chili');
    comp.toggleNested('chili', heat as MenuItem['modifierGroups'][number], 'hot');
    comp.toggleOption(group, 'chili');
    comp.add();

    expect(cartService.add).toHaveBeenCalledWith('variant-1', 1, undefined, [], []);
  });

  it('finds the line by its answers: the same choice is bumped, another is a line of its own', async () => {
    const line = cartLine({
      variant_id: 'variant-1',
      modifierOptionIds: ['chili'],
      nestedModifiers: [{ parentOptionId: 'chili', optionId: 'hot' }],
    });
    const { comp, cartService } = await renderItem(saucy(), [line]);
    const group = sauces as MenuItem['modifierGroups'][number];
    const heatGroup = heat as MenuItem['modifierGroups'][number];

    comp.toggleOption(group, 'chili');
    comp.toggleNested('chili', heatGroup, 'hot');
    comp.add();
    expect(cartService.increaseQuantity).toHaveBeenCalledWith(line);
    expect(cartService.add).not.toHaveBeenCalled();

    comp.toggleNested('chili', heatGroup, 'mild');
    comp.add();
    expect(cartService.add).toHaveBeenCalledWith(
      'variant-1',
      1,
      undefined,
      ['chili'],
      [],
      undefined,
      [{ parentOptionId: 'chili', optionId: 'mild' }],
    );
  });

  describe('a portion that carries groups of its own', () => {
    const fries = () =>
      menuItem({
        variants: [
          onSaleVariant({ id: 'small', name: 'Small' }),
          { ...onSaleVariant({ id: 'large', name: 'Large' }), modifierGroups: [dips] },
        ] as MenuItem['variants'],
        modifierGroups: [],
      });

    it('draws them in that portion’s own row, and holds only that portion to them', async () => {
      const { fixture, comp } = await renderItem(fries());
      const host: HTMLElement = fixture.nativeElement;

      expect(host.querySelectorAll('[data-testid="variant-groups"]')).toHaveLength(1);
      expect(host.querySelector('[data-testid="variant-groups"]')?.textContent).toContain('Dips');
      expect(comp.modifiersValidFor('small')).toBe(true);
      expect(comp.modifiersValidFor('large')).toBe(false);
    });

    it('sends the choice with that portion and never with another', async () => {
      const { comp, cartService } = await renderItem(fries());

      comp.toggleOption(dips as MenuItem['modifierGroups'][number], 'ketchup');
      comp.increaseVariant('large');
      comp.increaseVariant('small');

      expect(cartService.add).toHaveBeenNthCalledWith(1, 'large', 1, undefined, ['ketchup'], []);
      expect(cartService.add).toHaveBeenNthCalledWith(2, 'small', 1, undefined, [], []);
    });

    it('refuses to add that portion until its group is answered', async () => {
      const { comp, cartService } = await renderItem(fries());

      comp.increaseVariant('large');

      expect(cartService.add).not.toHaveBeenCalled();
    });
  });

  it('never asks a combo for modifiers or second-level choices: its choices are its components', async () => {
    const { fixture, comp } = await renderItem(
      menuItem({
        variants: [onSaleVariant({ id: 'v-lunch' })],
        modifierGroups: [sauces] as MenuItem['modifierGroups'],
        comboGroups: [
          {
            id: 'g-main',
            name: 'Main',
            minimumSelections: 1,
            maximumSelections: 1,
            allowSameComponentMultipleTimes: false,
            components: [
              {
                id: 'c-burger',
                name: 'Burger',
                variantName: null,
                defaultQuantity: 1,
                active: true,
                amountMinor: 25_000,
              },
            ],
          },
        ],
      }),
    );
    const host: HTMLElement = fixture.nativeElement;

    expect(comp.modifierGroups()).toEqual([]);
    expect(host.querySelector('app-nested-choices')).toBeNull();
    comp.setComboPicks({ 'c-burger': 1 });
    expect(comp.modifiersValid()).toBe(true);
  });
});
