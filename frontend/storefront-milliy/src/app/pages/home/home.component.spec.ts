import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';

import { APP_CONFIG, type AppConfig } from '../../core/config/app-config';
import { HomeComponent } from './home.component';
import { LangService } from '../../services/lang.service';
import { MenuService } from '../../services/menu.service';
import { TranslateService } from '../../services/translate.service';
import type { CustomerUiResponse, MenuItem, MenuItemVariant } from '../../types/home.types';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

function variant(overrides: Partial<MenuItemVariant> = {}): MenuItemVariant {
  return {
    id: 'v1',
    name: '',
    active: true,
    onSaleNow: true,
    preparation_time: 0,
    price: 48_000,
    price_without_discount: 48_000,
    remainingQuantity: null,
    ...overrides,
  };
}

function item(id: string, name: string, variants: MenuItemVariant[] = [variant()]): MenuItem {
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
    modifierGroups: [],
  };
}

function response(
  categories: { id: string; name: string; items: MenuItem[] }[],
): CustomerUiResponse {
  return {
    category: null,
    offer: null,
    populars: [],
    populars_count: 0,
    menu: {
      categories: categories.map((category) => ({ id: category.id, name: category.name })),
      category_items: categories.map((category) => ({
        ...category,
        items_count: category.items.length,
      })),
      category_items_count: categories.length,
    },
  };
}

class FakeMenuService {
  readonly currency = signal<string | null>('UZS');
  home = vi.fn();
}
class FakeLangService {
  langId = () => 'uz';
}
class FakeTranslateService {
  get = (key: string) => key;
  getWithParams = (key: string, params?: Record<string, string | number>) =>
    params ? `${key}(${JSON.stringify(params)})` : key;
  current = () => ({});
}

async function setUp(menu: CustomerUiResponse) {
  const service = new FakeMenuService();
  service.home.mockResolvedValue(menu);
  TestBed.configureTestingModule({
    imports: [HomeComponent],
    providers: [
      provideRouter([]),
      { provide: MenuService, useValue: service },
      { provide: LangService, useClass: FakeLangService },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  const fixture = TestBed.createComponent(HomeComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, service };
}

function cardFor(host: HTMLElement, name: string): HTMLElement {
  const card = Array.from(host.querySelectorAll<HTMLElement>('[data-testid="dish-card"]')).find(
    (el) => el.textContent?.includes(name),
  );
  if (!card) {
    throw new Error(`no card for ${name}`);
  }
  return card;
}

describe('HomeComponent -- the menu grid', () => {
  it('renders every dish under its category with its name and a link to its page', async () => {
    const { host } = await setUp(
      response([
        { id: 'c1', name: 'Osh', items: [item('p1', 'Toshkent oshi')] },
        { id: 'c2', name: 'Salat', items: [item('p2', 'Achichuk')] },
      ]),
    );

    expect(host.querySelectorAll('[data-testid="dish-card"]').length).toBe(2);
    const osh = cardFor(host, 'Toshkent oshi');
    expect(osh.getAttribute('href')).toBe('/product/p1');
    expect(host.textContent).toContain('Achichuk');
  });

  it('shows a normal dish with no badge at all', async () => {
    const { host } = await setUp(
      response([{ id: 'c1', name: 'Osh', items: [item('p1', 'Toshkent oshi')] }]),
    );
    const card = cardFor(host, 'Toshkent oshi');

    expect(card.querySelector('[data-testid="dish-sold-out"]')).toBeNull();
    expect(card.querySelector('[data-testid="dish-out-of-window"]')).toBeNull();
    expect(card.classList).not.toContain('is-unavailable');
  });

  it('marks a sold-out dish on the grid itself, not only on its details page (rows 4.4c/4.4d)', async () => {
    const { host } = await setUp(
      response([
        {
          id: 'c1',
          name: 'Osh',
          items: [item('p1', 'Toshkent oshi', [variant({ active: false })])],
        },
      ]),
    );
    const card = cardFor(host, 'Toshkent oshi');

    expect(card.querySelector('[data-testid="dish-sold-out"]')?.textContent).toContain(
      'dish.soldOut',
    );
    expect(card.querySelector('[data-testid="dish-out-of-window"]')).toBeNull();
    expect(card.classList).toContain('is-unavailable');
    // Still a link: the customer may read the dish, the page refuses the add.
    expect(card.getAttribute('href')).toBe('/product/p1');
  });

  it('marks a dish outside its sale window as not orderable, with the window text -- and not as sold out (row 4.2g)', async () => {
    const { host } = await setUp(
      response([
        {
          id: 'c1',
          name: 'Nonushta',
          items: [item('p1', "Sut bilan bo'tqa", [variant({ onSaleNow: false })])],
        },
      ]),
    );
    const card = cardFor(host, "bo'tqa");

    expect(card.querySelector('[data-testid="dish-out-of-window"]')?.textContent).toContain(
      'dish.outOfSaleWindow',
    );
    expect(card.querySelector('[data-testid="dish-sold-out"]')).toBeNull();
    expect(card.classList).toContain('is-unavailable');
  });

  it('treats a dish as available when any one portion can be bought right now', async () => {
    const { host } = await setUp(
      response([
        {
          id: 'c1',
          name: 'Osh',
          items: [
            item('p1', 'Toshkent oshi', [
              variant({ id: 'v1', active: false }),
              variant({ id: 'v2', onSaleNow: false }),
              variant({ id: 'v3' }),
            ]),
          ],
        },
      ]),
    );
    const card = cardFor(host, 'Toshkent oshi');

    expect(card.querySelector('[data-testid="dish-sold-out"]')).toBeNull();
    expect(card.querySelector('[data-testid="dish-out-of-window"]')).toBeNull();
  });

  it('shows the low-stock count on a dish that can still be bought', async () => {
    const { host } = await setUp(
      response([
        {
          id: 'c1',
          name: 'Osh',
          items: [item('p1', 'Toshkent oshi', [variant({ remainingQuantity: 3 })])],
        },
      ]),
    );
    const card = cardFor(host, 'Toshkent oshi');

    expect(card.querySelector('[data-testid="dish-low-stock"]')?.textContent).toContain(
      '"count":3',
    );
  });

  it('shows no low-stock count on a dish that has already sold out', async () => {
    const { host } = await setUp(
      response([
        {
          id: 'c1',
          name: 'Osh',
          items: [item('p1', 'Toshkent oshi', [variant({ active: false, remainingQuantity: 0 })])],
        },
      ]),
    );

    expect(
      cardFor(host, 'Toshkent oshi').querySelector('[data-testid="dish-low-stock"]'),
    ).toBeNull();
  });

  it('filters the grid to the chosen category, and back to all of them', async () => {
    const { fixture, host } = await setUp(
      response([
        { id: 'c1', name: 'Osh', items: [item('p1', 'Toshkent oshi')] },
        { id: 'c2', name: 'Salat', items: [item('p2', 'Achichuk')] },
      ]),
    );
    const chips = host.querySelectorAll<HTMLButtonElement>('.chip');

    chips[2].click(); // "Salat" (chip 0 is "all")
    fixture.detectChanges();
    expect(host.querySelectorAll('[data-testid="dish-card"]').length).toBe(1);
    expect(host.textContent).toContain('Achichuk');
    expect(host.textContent).not.toContain('Toshkent oshi');

    chips[0].click();
    fixture.detectChanges();
    expect(host.querySelectorAll('[data-testid="dish-card"]').length).toBe(2);
  });

  it('still says the menu is empty when there is nothing to show, and shows no grid', async () => {
    const { host } = await setUp(response([{ id: 'c1', name: 'Osh', items: [] }]));

    expect(host.textContent).toContain('home.emptyMenu');
    expect(host.querySelectorAll('[data-testid="dish-card"]').length).toBe(0);
  });
});
