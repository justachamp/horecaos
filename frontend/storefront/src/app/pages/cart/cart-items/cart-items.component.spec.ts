import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { LangService } from '../../../services/lang.service';
import { TranslateService } from '../../../services/translate.service';
import { UiCartService } from '../../../services/ui-cart.service';
import type { CartResponseItem } from '../../../types/cart.types';
import type { PhysicalFacts } from '../../../utils/physical';
import { CartItemsComponent } from './cart-items.component';

const NBSP = ' ';

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string, params?: Record<string, string | number>): string {
    return params ? `${key}|${Object.values(params).join('|')}` : key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

class FakeLangService {
  langId = () => 'en';
}

class FakeUiCartService {
  readonly items = signal<readonly CartResponseItem[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly updating = signal(false);
  readonly hasProvisionalLines = signal(false);
  readonly totalAmount = signal('0 so’m');
  readonly deliveryFee = signal('0');
  readonly deliveryTime = signal('');
  readonly deliveryUnresolvedMessage = signal<string | null>(null);
  readonly fulfillmentMode = signal('PICKUP');
  orderComment = '';
  load = vi.fn().mockResolvedValue(undefined);
  add = vi.fn().mockResolvedValue(undefined);
  increaseQuantity = vi.fn();
  decreaseQuantity = vi.fn();
  lineAmount = vi.fn((item: CartResponseItem) => item.price * item.quantity);
}

function line(
  overrides: Partial<CartResponseItem> & { physical?: PhysicalFacts | null } = {},
): CartResponseItem {
  return {
    variant_id: 'v1',
    price: 38_000,
    item_id: 'v1',
    name: 'Plov',
    active: true,
    image: null,
    quantity: 1,
    note: null,
    modifierOptionIds: [],
    modifiers: [],
    commentPresetCodes: [],
    commentPresets: [],
    physical: null,
    ...overrides,
  } as CartResponseItem;
}

function open(items: readonly CartResponseItem[], configure?: (cart: FakeUiCartService) => void) {
  const cart = new FakeUiCartService();
  cart.items.set(items);
  configure?.(cart);
  TestBed.configureTestingModule({
    imports: [CartItemsComponent],
    providers: [
      provideRouter([]),
      { provide: UiCartService, useValue: cart },
      { provide: TranslateService, useValue: new FakeTranslateService() },
      { provide: LangService, useValue: new FakeLangService() },
    ],
  });
  const fixture = TestBed.createComponent(CartItemsComponent);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, cart, text: () => host.textContent?.replace(/\s+/g, ' ') ?? '' };
}

describe('CartItemsComponent: portions and weighed items (ADR 0137)', () => {
  it('writes a whole quantity in pieces, as before', () => {
    const { text } = open([line({ quantity: 2 })]);

    expect(text()).toContain('2 common.itemsUnit');
  });

  it('writes a half portion with the language’s decimal mark and its portion unit', () => {
    const { text } = open([
      line({ quantity: 0.5, physical: { catchweight: false, splittable: true, portionSize: 0.5 } }),
    ]);

    expect(text()).toContain('0.5 physical.portionsUnit');
  });

  it('prices a line through the cart, so a portion is priced as the platform will price it', () => {
    const { text, cart } = open([line({ quantity: 0.5 })], (c) =>
      c.lineAmount.mockReturnValue(19_000),
    );

    expect(cart.lineAmount).toHaveBeenCalled();
    expect(text()).toContain('19');
    expect(text()).not.toContain('≈');
  });

  it('marks a weighed line’s amount as an estimate and says what weight it is estimated at', () => {
    const cake = line({
      price: 15_000,
      quantity: 2,
      physical: {
        catchweight: true,
        catchweightQuantumGrams: 100,
        catchweightNominalGrams: 1_200,
        splittable: false,
      },
    });
    const { host, text } = open([cake], (c) => c.lineAmount.mockReturnValue(360_000));

    expect(text()).toContain('≈ 360');
    expect(host.querySelector('[data-testid="cart-line-estimate"]')?.textContent).toContain(
      `2.4${NBSP}kg`,
    );
  });

  it('says the total is an estimate while the basket holds anything sold by weight', () => {
    const { host } = open([line()], (c) => c.hasProvisionalLines.set(true));

    expect(host.querySelector('[data-testid="cart-provisional-notice"]')).not.toBeNull();
  });

  it('says nothing of the kind for a basket of fixed units', () => {
    const { host } = open([line()]);

    expect(host.querySelector('[data-testid="cart-provisional-notice"]')).toBeNull();
  });
});

describe('CartItemsComponent: combos (ADR 0136)', () => {
  it('lists a combo line’s components under its name, with the units a combo puts on the order', () => {
    const { host } = open([
      line({
        variant_id: 'v-lunch',
        item_id: 'v-lunchcabc',
        name: 'Lunch box',
        price: 37_000,
        comboPicks: [{ componentId: 'c-1', quantity: 1 }],
        comboComponents: [
          {
            componentId: 'c-1',
            name: 'Burger',
            variantName: null,
            quantity: 1,
            amountMinor: 25_000,
          },
          {
            componentId: 'c-2',
            name: 'Cola',
            variantName: '0.5 L',
            quantity: 4,
            amountMinor: 3_000,
          },
        ],
      }),
    ]);

    const parts = [...host.querySelectorAll('[data-testid="cart-line-combo"] li')].map((li) =>
      li.textContent?.trim(),
    );
    expect(parts).toEqual(['Burger', 'Cola 0.5 L ×4']);
  });

  it('keeps two lines of one combo with different picks apart, each with its own components', () => {
    const { host } = open([
      line({
        variant_id: 'v-lunch',
        item_id: 'key-a',
        name: 'Lunch box',
        comboComponents: [
          { componentId: 'c-1', name: 'Burger', variantName: null, quantity: 1, amountMinor: 1 },
        ],
      }),
      line({
        variant_id: 'v-lunch',
        item_id: 'key-b',
        name: 'Lunch box',
        comboComponents: [
          { componentId: 'c-2', name: 'Wrap', variantName: null, quantity: 1, amountMinor: 1 },
        ],
      }),
    ]);

    expect(host.querySelectorAll('article')).toHaveLength(2);
    const lists = [...host.querySelectorAll('[data-testid="cart-line-combo"]')].map((ul) =>
      ul.textContent?.trim(),
    );
    expect(lists).toEqual(['Burger', 'Wrap']);
  });

  it('shows no component list on an ordinary line', () => {
    const { host } = open([line()]);

    expect(host.querySelector('[data-testid="cart-line-combo"]')).toBeNull();
  });
});
