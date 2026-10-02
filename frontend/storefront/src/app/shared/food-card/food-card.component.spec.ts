import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { signal } from '@angular/core';

import { FoodCardComponent } from './food-card.component';
import { UiCartService } from '../../services/ui-cart.service';
import { FavouritesService } from '../../services/favourites.service';
import { TranslateService } from '../../services/translate.service';
import { Session } from '../../core/auth/session';
import type { MenuItem } from '../../types/home.types';
import type { CartResponseItem } from '../../types/cart.types';

/**
 * Exposes only what `FoodCardComponent` and its template actually touch on
 * the cart -- enough to exercise the gate honestly without standing up the
 * real `CartService`/`ApiClient` chain underneath it (see `ui-cart.service.spec.ts`
 * for the class this stands in for).
 */
class FakeUiCartService {
  readonly items = signal<CartResponseItem[]>([]);
  readonly updating = signal(false);
  readonly cartData = signal<unknown>(null);

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
  readonly add = vi.fn(async () => {});
  readonly remove = vi.fn(async () => {});
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

function menuItem(overrides: Partial<MenuItem> = {}): MenuItem {
  return {
    id: 'product-1',
    name: 'Osh',
    description: '',
    active: true,
    has_discount: false,
    preparation_time: 0,
    price: 25000,
    price_without_discount: 25000,
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
        price: 25000,
        price_without_discount: 25000,
        onSaleNow: true,
        remainingQuantity: null,
      },
    ],
    modifierGroups: [],
    commentPresets: [],
    ...overrides,
  };
}

function setUp() {
  const cart = new FakeUiCartService();
  const favourites = new FakeFavouritesService();

  TestBed.configureTestingModule({
    imports: [FoodCardComponent],
    providers: [
      provideRouter([]),
      { provide: UiCartService, useValue: cart },
      { provide: FavouritesService, useValue: favourites },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });

  const router = TestBed.inject(Router);
  const navigateSpy = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const session = TestBed.inject(Session);
  const fixture = TestBed.createComponent(FoodCardComponent);
  fixture.componentRef.setInput('item', menuItem());

  return { fixture, comp: fixture.componentInstance, cart, favourites, navigateSpy, session };
}

function signIn(session: Session): void {
  session.adopt({ accessToken: 'tok', expiresAt: new Date(Date.now() + 3_600_000).toISOString() });
}

function clickEvent(): Event {
  return new Event('click', { cancelable: true });
}

describe('FoodCardComponent: add-to-cart, anonymous', () => {
  beforeEach(() => localStorage.clear());

  it('sends an anonymous tap on "+" to sign in instead of calling the cart', () => {
    const { comp, cart, navigateSpy } = setUp();

    comp.increase(clickEvent());

    expect(navigateSpy).toHaveBeenCalledWith(['/auth/login']);
    expect(cart.load).not.toHaveBeenCalled();
    expect(cart.add).not.toHaveBeenCalled();
  });
});

describe('FoodCardComponent: add-to-cart, signed in', () => {
  beforeEach(() => localStorage.clear());

  it('adds the first active variant for a signed-in customer, unchanged from before', async () => {
    const { comp, cart, navigateSpy, session } = setUp();
    signIn(session);

    comp.increase(clickEvent());
    await Promise.resolve();
    await Promise.resolve();

    expect(navigateSpy).not.toHaveBeenCalled();
    expect(cart.load).toHaveBeenCalledTimes(1);
    expect(cart.add).toHaveBeenCalledWith('variant-1', 1);
  });
});

describe('FoodCardComponent: favourites, anonymous', () => {
  beforeEach(() => localStorage.clear());

  it('sends an anonymous heart tap to sign in instead of flipping the mark', () => {
    const { comp, favourites, navigateSpy } = setUp();

    comp.toggleFavourite(clickEvent());

    expect(navigateSpy).toHaveBeenCalledWith(['/auth/login']);
    expect(favourites.add).not.toHaveBeenCalled();
    expect(favourites.remove).not.toHaveBeenCalled();
  });
});

describe('FoodCardComponent: favourites, signed in', () => {
  beforeEach(() => localStorage.clear());

  it('marks the product for a signed-in customer, unchanged from before', async () => {
    const { comp, favourites, navigateSpy, session } = setUp();
    signIn(session);

    comp.toggleFavourite(clickEvent());
    await Promise.resolve();

    expect(navigateSpy).not.toHaveBeenCalled();
    expect(favourites.add).toHaveBeenCalledWith('product-1');
  });
});

describe('FoodCardComponent: a combo (ADR 0136)', () => {
  beforeEach(() => localStorage.clear());

  const comboItem = () =>
    menuItem({
      id: 'product-lunch',
      price: 22_000,
      variants: [
        {
          id: 'v-lunch',
          name: '',
          active: true,
          preparation_time: 0,
          price: 22_000,
          price_without_discount: 22_000,
          onSaleNow: true,
          remainingQuantity: null,
        },
      ],
      comboGroups: [
        {
          id: 'g-main',
          name: 'Main',
          minimumSelections: 1,
          maximumSelections: 1,
          allowSameComponentMultipleTimes: false,
          components: [
            {
              id: 'c-1',
              name: 'Burger',
              variantName: null,
              defaultQuantity: 1,
              active: true,
              amountMinor: 22_000,
            },
          ],
        },
      ],
    });

  it('sends a tap on "+" to the product page, where its choices are made, and never adds the bare container', async () => {
    const { fixture, comp, cart, navigateSpy, session } = setUp();
    fixture.componentRef.setInput('item', comboItem());
    signIn(session);

    comp.increase(clickEvent());
    await Promise.resolve();

    expect(navigateSpy).toHaveBeenCalledWith(['/product', 'product-lunch']);
    expect(cart.add).not.toHaveBeenCalled();
  });

  it('reads its price as the least it can cost', () => {
    const { fixture, comp } = setUp();
    fixture.componentRef.setInput('item', comboItem());

    expect(comp.priceLabel()).toContain('product.fromPrice');
  });

  it('bumps a combo already in the basket like any line: the same picks, one more', async () => {
    const { fixture, comp, cart, navigateSpy, session } = setUp();
    fixture.componentRef.setInput('item', comboItem());
    cart.items.set([
      {
        variant_id: 'v-lunch',
        price: 22_000,
        item_id: 'v-lunchcabc',
        name: 'Lunch box',
        active: true,
        image: null,
        quantity: 1,
        note: null,
        modifierOptionIds: [],
        modifiers: [],
        commentPresetCodes: [],
        commentPresets: [],
      },
    ]);
    cart.cartData.set({});
    signIn(session);

    comp.increase(clickEvent());
    await Promise.resolve();

    expect(navigateSpy).not.toHaveBeenCalled();
    expect(cart.increaseQuantity).toHaveBeenCalledTimes(1);
  });
});
