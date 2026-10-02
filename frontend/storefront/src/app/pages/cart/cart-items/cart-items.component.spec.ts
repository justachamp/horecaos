import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { LangService } from '../../../services/lang.service';
import { TranslateService } from '../../../services/translate.service';
import { UiCartService } from '../../../services/ui-cart.service';
import type { CartResponseItem } from '../../../types/cart.types';
import { CartItemsComponent } from './cart-items.component';

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

function item(overrides: Partial<CartResponseItem> = {}): CartResponseItem {
  return {
    variant_id: 'v1',
    price: 25_000,
    item_id: 'v1',
    name: 'Osh',
    active: true,
    image: null,
    quantity: 1,
    note: null,
    modifierOptionIds: [],
    modifiers: [],
    commentPresetCodes: [],
    commentPresets: [],
    ...overrides,
  };
}

function render(items: CartResponseItem[]) {
  const cart = {
    loading: signal(false),
    error: signal<string | null>(null),
    items: signal(items),
    updating: signal(false),
    load: vi.fn(),
    fulfillmentMode: signal('PICKUP'),
    totalAmount: signal('0'),
    orderComment: '',
    decreaseQuantity: vi.fn(),
    increaseQuantity: vi.fn(),
  };
  TestBed.configureTestingModule({
    imports: [CartItemsComponent],
    providers: [
      provideRouter([]),
      { provide: UiCartService, useValue: cart },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: LangService, useValue: { langId: () => 'uz' } },
    ],
  });
  const fixture = TestBed.createComponent(CartItemsComponent);
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement };
}

describe('CartItemsComponent: combos (ADR 0136)', () => {
  it('lists a combo line’s components under its name, with the units a combo puts on the order', () => {
    const { host } = render([
      item({
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
    const { host } = render([
      item({
        variant_id: 'v-lunch',
        item_id: 'key-a',
        name: 'Lunch box',
        comboComponents: [
          { componentId: 'c-1', name: 'Burger', variantName: null, quantity: 1, amountMinor: 1 },
        ],
      }),
      item({
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
    const { host } = render([item()]);

    expect(host.querySelector('[data-testid="cart-line-combo"]')).toBeNull();
  });
});
