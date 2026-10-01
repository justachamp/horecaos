import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { MenuProduct, MenuVariant, StorefrontMenu } from './new-order-api';
import { NewOrderMenuGrid } from './new-order-menu-grid';

const variant = (orderable: boolean): MenuVariant => ({
  variantId: 'v',
  sku: null,
  unitCode: null,
  isDefault: true,
  orderable,
  onSaleNow: true,
  amountMinor: 10_000,
});

const product = (productId: string, name: string, orderable = true): MenuProduct => ({
  productId,
  code: productId,
  name,
  description: null,
  imageUrls: [],
  variants: [variant(orderable)],
  modifierGroupIds: [],
  commentPresets: [],
});

const MENU: StorefrontMenu = {
  publicationId: 'p',
  locale: 'en',
  currency: 'UZS',
  modifierGroups: [],
  products: [product('a', 'Plov'), product('b', 'Samsa', false), product('c', 'Tea')],
  categories: [
    {
      categoryId: 'c1',
      code: 'MAINS',
      name: 'Mains',
      parentCategoryId: null,
      sortOrder: 0,
      productIds: ['a', 'b', 'gone'],
    },
    {
      categoryId: 'c2',
      code: 'DRINKS',
      name: 'Drinks',
      parentCategoryId: null,
      sortOrder: 1,
      productIds: ['c'],
    },
  ],
};

function render(menu: StorefrontMenu = MENU) {
  TestBed.resetTestingModule();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(NewOrderMenuGrid);
  fixture.componentRef.setInput('menu', menu);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const products = () => [
    ...host.querySelectorAll<HTMLButtonElement>('[data-testid="new-order-product"]'),
  ];
  return { fixture, host, products };
}

describe('NewOrderMenuGrid', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('draws each category with its products in the category’s own order', () => {
    const { host, products } = render();

    expect(
      [...host.querySelectorAll('.new-order__category-title')].map((t) => t.textContent?.trim()),
    ).toEqual(['Mains', 'Drinks']);
    expect(products().map((button) => button.querySelector('span')?.textContent?.trim())).toEqual([
      'Plov',
      'Samsa',
      'Tea',
    ]);
  });

  it('skips a product id the menu does not carry rather than drawing a hole', () => {
    const { host } = render();

    expect(
      host.querySelectorAll('.new-order__category')[0].querySelectorAll('button'),
    ).toHaveLength(2);
  });

  it('marks a product with nothing orderable as stopped, and says so, but still draws it', () => {
    const { products } = render();

    expect(products()[0].classList.contains('new-order__product--stopped')).toBe(false);
    expect(products()[0].querySelector('.new-order__stop-chip')).toBeNull();
    expect(products()[1].classList.contains('new-order__product--stopped')).toBe(true);
    expect(products()[1].querySelector('.new-order__stop-chip')?.textContent?.trim()).toBe('stop');
  });

  it('reports the product a click chose, stopped or not', () => {
    const { fixture, products } = render();
    const chosen: string[] = [];
    fixture.componentInstance.productSelected.subscribe((p) => chosen.push(p.productId));

    products()[0].click();
    products()[1].click();

    expect(chosen).toEqual(['a', 'b']);
  });

  it('draws nothing for a menu with no categories', () => {
    const { host } = render({ ...MENU, categories: [] });

    expect(host.querySelector('.new-order__category')).toBeNull();
  });
});
