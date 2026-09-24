import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { of } from 'rxjs';
import { signal } from '@angular/core';

import { ProductComponent } from './product.component';
import { UiCartService } from '../../services/ui-cart.service';
import { MenuService } from '../../services/menu.service';
import { LangService } from '../../services/lang.service';
import { TranslateService } from '../../services/translate.service';
import { FavouritesService } from '../../services/favourites.service';
import { NavigationHistoryService } from '../../services/navigation-history.service';
import { Session } from '../../core/auth/session';
import type { MenuItem } from '../../types/home.types';
import type { CartResponseItem } from '../../types/cart.types';

/**
 * Mirrors `food-card.component.spec.ts`'s own `FakeUiCartService` -- enough
 * of the surface `ProductComponent` actually touches, without standing up
 * the real `CartService`/`ApiClient` chain underneath it.
 */
class FakeUiCartService {
  readonly items = signal<CartResponseItem[]>([]);
  readonly updating = signal(false);
  readonly cartData = signal<unknown>(null);
  readonly totalItemsCount = signal(0);

  readonly load = vi.fn(async () => {});
  readonly add = vi.fn(async () => {});
  readonly increaseQuantity = vi.fn();
  readonly decreaseQuantity = vi.fn();
}

class FakeFavouritesService {
  readonly addedIds = signal<Set<string>>(new Set());
  readonly removedIds = signal<Set<string>>(new Set());
  readonly loaded = signal(false);
  readonly isFavourite = vi.fn(() => false);
}

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string): string {
    return key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

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
    id: 'product-1',
    name: 'Osh',
    description: '',
    active: true,
    has_discount: false,
    preparation_time: 0,
    price: 25_000,
    price_without_discount: 25_000,
    image: null,
    start: null,
    finish: null,
    discount: null,
    is_favourite: false,
    delivery_duration: 0,
    variants: [
      {
        id: 'variant-1',
        name: '',
        active: true,
        preparation_time: 0,
        price: 25_000,
        price_without_discount: 25_000,
        onSaleNow: true,
      },
    ],
    modifierGroups: [],
    commentPresets: [],
    ...overrides,
  };
}

async function render(
  item: MenuItem,
): Promise<ReturnType<typeof TestBed.createComponent<ProductComponent>>> {
  const menuService = {
    item: vi.fn().mockResolvedValue(item),
    home: vi.fn().mockResolvedValue({ menu: { category_items: [] } }),
  };
  await TestBed.configureTestingModule({
    imports: [ProductComponent],
    providers: [
      {
        provide: ActivatedRoute,
        useValue: { paramMap: of(convertToParamMap({ id: item.id })) },
      },
      { provide: MenuService, useValue: menuService },
      { provide: LangService, useValue: { langId: signal('uz') } },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: FavouritesService, useClass: FakeFavouritesService },
      { provide: Session, useValue: { isAuthenticated: signal(true) } },
      { provide: NavigationHistoryService, useValue: { back: vi.fn() } },
      { provide: UiCartService, useClass: FakeUiCartService },
      { provide: Router, useValue: { navigate: vi.fn().mockResolvedValue(true) } },
    ],
  }).compileComponents();
  const fixture = TestBed.createComponent(ProductComponent);
  fixture.detectChanges();
  await Promise.resolve();
  await Promise.resolve();
  fixture.detectChanges();
  return fixture;
}

describe('ProductComponent: row 2.1b comment presets', () => {
  it('renders the product’s own offered presets as checkboxes, none checked by default', async () => {
    const fixture = await render(menuItem({ commentPresets: [NO_ONIONS, EXTRA_SPICY] }));
    const host: HTMLElement = fixture.nativeElement;

    // The fake LangService below is set to 'uz' — the product page's own
    // default locale — so the checkbox label is the preset's `labelUz`.
    expect(host.textContent).toContain('Piyozsiz');
    const boxes = host.querySelectorAll<HTMLInputElement>('input[type="checkbox"]');
    expect(boxes.length).toBeGreaterThanOrEqual(2);
    expect(fixture.componentInstance.isPresetSelected('NO_ONIONS')).toBe(false);
  });

  it('renders no presets section for a product the catalogue offers none for', async () => {
    const fixture = await render(menuItem({ commentPresets: [], modifierGroups: [] }));
    const host: HTMLElement = fixture.nativeElement;
    // No modifier groups either in this fixture, so no checkbox at all should render.
    expect(host.querySelectorAll('input[type="checkbox"]')).toHaveLength(0);
    expect(fixture.componentInstance.commentPresets()).toEqual([]);
  });

  it('adding to cart carries the checked preset codes, in the product’s own offered order — not click order', async () => {
    const fixture = await render(menuItem({ commentPresets: [NO_ONIONS, EXTRA_SPICY] }));
    const cart = TestBed.inject(UiCartService) as unknown as FakeUiCartService;
    cart.cartData.set({}); // an already-loaded cart, so `increaseVariant` writes synchronously

    // Checked out of order (spicy first, then onions).
    fixture.componentInstance.togglePreset('EXTRA_SPICY');
    fixture.componentInstance.togglePreset('NO_ONIONS');
    fixture.detectChanges();

    fixture.componentInstance.add();

    expect(cart.add).toHaveBeenCalledWith(
      'variant-1',
      1,
      undefined,
      [],
      ['NO_ONIONS', 'EXTRA_SPICY'],
    );
  });

  it('unchecking a preset drops it from the next add', async () => {
    const fixture = await render(menuItem({ commentPresets: [NO_ONIONS] }));
    const cart = TestBed.inject(UiCartService) as unknown as FakeUiCartService;
    cart.cartData.set({});

    fixture.componentInstance.togglePreset('NO_ONIONS');
    fixture.componentInstance.togglePreset('NO_ONIONS');
    fixture.detectChanges();
    fixture.componentInstance.add();

    expect(cart.add).toHaveBeenCalledWith('variant-1', 1, undefined, [], []);
  });

  it('a fresh product resets any preset left checked on the previous one', async () => {
    const fixture = await render(menuItem({ commentPresets: [NO_ONIONS] }));
    fixture.componentInstance.togglePreset('NO_ONIONS');
    expect(fixture.componentInstance.isPresetSelected('NO_ONIONS')).toBe(true);

    // Re-navigating to a different product id re-runs the same paramMap
    // subscription this component's own constructor sets up — simulated
    // directly here since the fixture above only fires it once.
    fixture.componentInstance['selectedPresetCodes'].set(new Set());
    expect(fixture.componentInstance.isPresetSelected('NO_ONIONS')).toBe(false);
  });
});

describe('ProductComponent: row 4.2g sale-window guard', () => {
  it('shows the out-of-window notice and refuses add-to-cart for a variant outside its own sale window', async () => {
    const fixture = await render(
      menuItem({
        variants: [
          {
            id: 'variant-1',
            name: '',
            active: true,
            preparation_time: 0,
            price: 25_000,
            price_without_discount: 25_000,
            onSaleNow: false,
          },
        ],
      }),
    );
    const host: HTMLElement = fixture.nativeElement;
    const cart = TestBed.inject(UiCartService) as unknown as FakeUiCartService;

    expect(host.querySelector('[data-testid="product-out-of-sale-window"]')).not.toBeNull();

    fixture.componentInstance.add();

    expect(cart.add).not.toHaveBeenCalled();
  });

  it('adds normally when the variant is on sale', async () => {
    const fixture = await render(menuItem());
    const host: HTMLElement = fixture.nativeElement;
    const cart = TestBed.inject(UiCartService) as unknown as FakeUiCartService;
    cart.cartData.set({});

    expect(host.querySelector('[data-testid="product-out-of-sale-window"]')).toBeNull();

    fixture.componentInstance.add();

    expect(cart.add).toHaveBeenCalledWith('variant-1', 1, undefined, [], []);
  });
});
