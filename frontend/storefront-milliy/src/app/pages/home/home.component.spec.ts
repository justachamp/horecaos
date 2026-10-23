import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { signal } from '@angular/core';

import { ApiClient } from '../../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../../core/config/app-config';
import { HomeComponent } from './home.component';
import { ContentService } from '../../services/content.service';
import { EditorialService } from '../../services/editorial.service';
import { LangService } from '../../services/lang.service';
import { MenuService } from '../../services/menu.service';
import { ReviewsService } from '../../services/reviews.service';
import { TranslateService } from '../../services/translate.service';
import { NO_CONTENT, type ContentHome } from '../../types/content.types';
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
/** The public rating block is not under test here: the platform reports nothing to show. */
class FakeReviewsService {
  publicSummary = vi.fn(async () => ({
    visibility: 'NONE' as const,
    ready: false,
    count: null,
    average: null,
    distribution: null,
  }));
  publicComments = vi.fn();
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

/** A content service that has nothing to show, so the menu specs below see only the menu. */
class FakeContentService {
  readonly carousel = signal<ContentHome['carousel']>([]);
  readonly groups = signal<ContentHome['groups']>([]);
  readonly stories = signal<ContentHome['stories']>([]);
  readonly promoGrid = signal<ContentHome['promoGrid']>([]);
  readonly popup = signal<ContentHome['popup']>(null);
  readonly brandSwitcher = signal<ContentHome['brandSwitcher']>([]);
  readonly layout = signal<ContentHome['layout']>('STANDARD');
  load = vi.fn().mockResolvedValue(undefined);
}

/** An editorial service that runs no kind, so the menu specs below see only the menu. */
class FakeEditorialService {
  readonly features = signal({ news: false, gallery: false, recipes: false, vacancies: false });
  loadFeatures = vi.fn().mockResolvedValue(undefined);
}

async function setUp(menu: CustomerUiResponse) {
  const service = new FakeMenuService();
  service.home.mockResolvedValue(menu);
  TestBed.configureTestingModule({
    imports: [HomeComponent],
    providers: [
      provideRouter([]),
      { provide: ContentService, useClass: FakeContentService },
      { provide: EditorialService, useClass: FakeEditorialService },
      { provide: MenuService, useValue: service },
      { provide: ReviewsService, useClass: FakeReviewsService },
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

// ---------------------------------------------------------------------------------------------
// ADR 0157: the content the brand published, read beside the menu and never in its way.
// ---------------------------------------------------------------------------------------------

const UUID = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;

const IMAGE = {
  kind: 'IMAGE',
  alt: 'A plate of plov',
  widthPx: 1600,
  heightPx: 533,
  renditions: [
    { widthPx: 400, url: '/api/v1/storefront/tenants/t/media/a?variant=w400' },
    { widthPx: 800, url: '/api/v1/storefront/tenants/t/media/a?variant=w800' },
  ],
};

function contentBody(overrides: Record<string, unknown> = {}) {
  return {
    locale: 'uz-Latn',
    carousel: [
      {
        id: UUID(1),
        kind: 'BANNER',
        revisionNo: 1,
        title: 'Plov week',
        textLocale: 'uz-Latn',
        media: IMAGE,
        link: { type: 'PRODUCT', targetId: UUID(11) },
      },
    ],
    groups: [
      {
        id: UUID(2),
        revisionNo: 1,
        title: 'Popular',
        textLocale: 'uz-Latn',
        productIds: [UUID(11), UUID(404)],
      },
    ],
    promoGrid: [
      {
        id: UUID(3),
        kind: 'PROMO_CARD',
        revisionNo: 1,
        title: 'Second helping',
        textLocale: 'uz-Latn',
        media: IMAGE,
        link: { type: 'NONE' },
      },
    ],
    stories: [
      {
        id: UUID(4),
        revisionNo: 1,
        title: 'Today',
        textLocale: 'uz-Latn',
        slides: [{ media: IMAGE, durationSeconds: 5, link: { type: 'NONE' } }],
      },
    ],
    ...overrides,
  };
}

const MENU_WITH_PLOV = () =>
  response([{ id: UUID(100), name: 'Osh', items: [item(UUID(11), 'Toshkent oshi')] }]);

type ContentReply = 'pending' | 'fail' | Record<string, unknown>;

const NO_FEATURES = { news: false, gallery: false, recipes: false, vacancies: false };

async function setUpWithContent(
  menu: CustomerUiResponse,
  content: ContentReply,
  features: ContentReply = NO_FEATURES,
) {
  const menuService = new FakeMenuService();
  menuService.home.mockResolvedValue(menu);
  const answer = (reply: ContentReply) => {
    if (reply === 'pending') {
      return new Promise(() => undefined);
    }
    return reply === 'fail'
      ? Promise.reject(new TypeError('Failed to fetch'))
      : Promise.resolve(reply);
  };
  const api = {
    get: vi.fn((path: string) => answer(path.endsWith('/content/features') ? features : content)),
  };
  TestBed.configureTestingModule({
    imports: [HomeComponent],
    providers: [
      provideRouter([{ path: '**', children: [] }]),
      { provide: MenuService, useValue: menuService },
      { provide: ApiClient, useValue: api },
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
  const homeReads = () =>
    api.get.mock.calls.filter(([path]) => String(path).endsWith('/content/home'));
  return { fixture, host, api, menuService, homeReads };
}

describe('HomeComponent -- the content a brand published', () => {
  it('asks for the content of this brand and channel in the visitor’s language, beside the menu', async () => {
    const { api, menuService, homeReads } = await setUpWithContent(MENU_WITH_PLOV(), contentBody());

    expect(menuService.home).toHaveBeenCalledTimes(1);
    expect(homeReads()).toHaveLength(1);
    expect(api.get).toHaveBeenCalledWith(
      `/storefront/tenants/${CONFIG.tenantId}/brands/${CONFIG.brandId}/content/home`,
      { query: { channel: 'STOREFRONT', locale: 'uz' }, anonymous: true, silent: true },
    );
  });

  it('draws the carousel, the promo grid, the stories and the groups above the menu', async () => {
    const { host } = await setUpWithContent(MENU_WITH_PLOV(), contentBody());

    expect(host.querySelector('[data-testid="content-carousel"]')?.textContent).toContain(
      'Plov week',
    );
    expect(host.querySelector('[data-testid="content-promo-grid"]')?.textContent).toContain(
      'Second helping',
    );
    expect(host.querySelector('[data-testid="content-stories"]')?.textContent).toContain('Today');
    expect(host.querySelector('[data-testid="content-group"]')?.textContent).toContain('Popular');
    // The group joined the menu the page holds, skipped the id it lacks, and the menu is still there.
    expect(
      host.querySelectorAll('[data-testid="content-group"] [data-testid="dish-card"]'),
    ).toHaveLength(1);
    expect(host.querySelectorAll('app-menu-grid [data-testid="dish-card"]')).toHaveLength(1);
  });

  it('draws the content above the menu, in the order of the page', async () => {
    const { host } = await setUpWithContent(MENU_WITH_PLOV(), contentBody());
    const position = (selector: string) => {
      const el = host.querySelector(selector);
      return el ? [...host.querySelectorAll('*')].indexOf(el) : -1;
    };

    const order = [
      '.search',
      '[data-testid="content-stories"]',
      '[data-testid="content-carousel"]',
      '[data-testid="content-promo-grid"]',
      '[data-testid="content-group"]',
      'app-menu-grid',
    ].map(position);

    expect(order.every((index) => index >= 0)).toBe(true);
    expect(order).toEqual([...order].sort((a, b) => a - b));
  });

  it('still shows the whole menu when the content cannot be read, and says nothing about it', async () => {
    const { host } = await setUpWithContent(MENU_WITH_PLOV(), 'fail');

    expect(host.querySelectorAll('[data-testid="dish-card"]')).toHaveLength(1);
    expect(host.textContent).toContain('Toshkent oshi');
    expect(host.textContent).not.toContain('home.loadError');
    expect(host.querySelector('[data-testid="content-carousel"]')).toBeNull();
    expect(host.querySelector('[data-testid="content-promo-grid"]')).toBeNull();
    expect(host.querySelector('[data-testid="content-stories"]')).toBeNull();
    expect(host.querySelector('[data-testid="content-group"]')).toBeNull();
    expect(host.querySelector('[data-testid="content-popup"]')).toBeNull();
  });

  it('does not wait for the content to draw the menu', async () => {
    const { host } = await setUpWithContent(MENU_WITH_PLOV(), 'pending');

    expect(host.querySelectorAll('[data-testid="dish-card"]')).toHaveLength(1);
    expect(host.querySelector('[data-testid="content-carousel"]')).toBeNull();
  });

  it('looks as it did before content existed when the brand has published nothing', async () => {
    const { host } = await setUpWithContent(MENU_WITH_PLOV(), {
      locale: 'uz-Latn',
      carousel: [],
      groups: [],
    });

    expect(host.querySelector('[data-testid="content-carousel"]')).toBeNull();
    expect(host.querySelector('[data-testid="content-group"]')).toBeNull();
    expect(host.querySelectorAll('[data-testid="dish-card"]')).toHaveLength(1);
  });

  it('keeps showing content when the menu cannot be read, but draws no group over a menu it lacks', async () => {
    const menuService = new FakeMenuService();
    menuService.home.mockRejectedValue(new Error('menu down'));
    TestBed.configureTestingModule({
      imports: [HomeComponent],
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        { provide: MenuService, useValue: menuService },
        { provide: ApiClient, useValue: { get: vi.fn().mockResolvedValue(contentBody()) } },
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

    expect(host.textContent).toContain('home.loadError');
    expect(host.querySelector('[data-testid="content-carousel"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="content-group"]')).toBeNull();
  });

  it('has no hard-coded promotion picture in the template', async () => {
    const { host } = await setUpWithContent(MENU_WITH_PLOV(), 'fail');

    expect(host.innerHTML).not.toContain('home-promo');
  });

  it('shows the entry pop-up after its delay', async () => {
    vi.useFakeTimers();
    try {
      localStorage.clear();
      const body = contentBody({
        popup: {
          id: UUID(5),
          revisionNo: 1,
          title: 'Welcome',
          textLocale: 'uz-Latn',
          link: { type: 'NONE' },
          showAfterSeconds: 5,
          cap: { mode: 'ONCE_EVER', n: 1 },
          dismiss: 'DISMISSIBLE',
        },
      });
      const menuService = new FakeMenuService();
      menuService.home.mockResolvedValue(MENU_WITH_PLOV());
      TestBed.configureTestingModule({
        imports: [HomeComponent],
        providers: [
          provideRouter([{ path: '**', children: [] }]),
          { provide: MenuService, useValue: menuService },
          { provide: ApiClient, useValue: { get: vi.fn().mockResolvedValue(body) } },
          { provide: LangService, useClass: FakeLangService },
          { provide: TranslateService, useClass: FakeTranslateService },
          { provide: APP_CONFIG, useValue: CONFIG },
        ],
      });
      const fixture = TestBed.createComponent(HomeComponent);
      fixture.detectChanges();
      await vi.advanceTimersByTimeAsync(0);
      fixture.detectChanges();
      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="content-popup"]')).toBeNull();

      await vi.advanceTimersByTimeAsync(5_000);
      fixture.detectChanges();

      expect(host.querySelector('[data-testid="content-popup"]')?.textContent).toContain('Welcome');
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('HomeComponent -- the editorial pages a brand runs', () => {
  const links = (host: HTMLElement) =>
    [...host.querySelectorAll('[data-testid="editorial-links"] a')].map((a) =>
      a.getAttribute('href'),
    );

  it('links to the kinds the brand runs, below the content and above the menu', async () => {
    const { host, api } = await setUpWithContent(MENU_WITH_PLOV(), contentBody(), {
      news: true,
      gallery: false,
      recipes: true,
      vacancies: true,
    });

    expect(links(host)).toEqual(['/news', '/recipes', '/careers']);
    expect(api.get).toHaveBeenCalledWith(
      `/storefront/tenants/${CONFIG.tenantId}/brands/${CONFIG.brandId}/content/features`,
      { anonymous: true, silent: true },
    );
    const position = (selector: string) =>
      [...host.querySelectorAll('*')].indexOf(host.querySelector(selector) as Element);
    expect(position('[data-testid="content-promo-grid"]')).toBeLessThan(
      position('[data-testid="editorial-links"]'),
    );
    expect(position('[data-testid="editorial-links"]')).toBeLessThan(position('app-menu-grid'));
  });

  it('shows no link when no kind is on', async () => {
    const { host } = await setUpWithContent(MENU_WITH_PLOV(), contentBody());

    expect(host.querySelector('[data-testid="editorial-links"]')).toBeNull();
  });

  it('shows no link, and nothing else changes, when the features cannot be read', async () => {
    const { host } = await setUpWithContent(MENU_WITH_PLOV(), contentBody(), 'fail');

    expect(host.querySelector('[data-testid="editorial-links"]')).toBeNull();
    expect(host.querySelectorAll('[data-testid="dish-card"]').length).toBeGreaterThan(0);
    expect(host.querySelector('[data-testid="content-carousel"]')).not.toBeNull();
  });

  it('does not wait for the features to draw the menu', async () => {
    const { host } = await setUpWithContent(MENU_WITH_PLOV(), 'fail', 'pending');

    expect(host.querySelectorAll('[data-testid="dish-card"]')).toHaveLength(1);
  });
});

describe('HomeComponent -- a content link to a category', () => {
  it('chooses that category in the menu', async () => {
    const { fixture, host } = await setUp(
      response([
        { id: UUID(100), name: 'Osh', items: [item('p1', 'Toshkent oshi')] },
        { id: UUID(101), name: 'Salat', items: [item('p2', 'Achichuk')] },
      ]),
    );

    fixture.componentRef.setInput('category', UUID(101));
    fixture.detectChanges();

    expect(host.querySelectorAll('[data-testid="dish-card"]').length).toBe(1);
    expect(host.textContent).toContain('Achichuk');
    expect(host.textContent).not.toContain('Toshkent oshi');
    expect(host.querySelectorAll('.chip.is-active')[0].textContent).toContain('Salat');
  });

  it('ignores a category the menu does not have, and shows the whole menu', async () => {
    const { fixture, host } = await setUp(
      response([
        { id: UUID(100), name: 'Osh', items: [item('p1', 'Toshkent oshi')] },
        { id: UUID(101), name: 'Salat', items: [item('p2', 'Achichuk')] },
      ]),
    );

    fixture.componentRef.setInput('category', UUID(999));
    fixture.detectChanges();

    expect(host.querySelectorAll('[data-testid="dish-card"]').length).toBe(2);
  });

  it('still lets the customer choose another category afterwards', async () => {
    const { fixture, host } = await setUp(
      response([
        { id: UUID(100), name: 'Osh', items: [item('p1', 'Toshkent oshi')] },
        { id: UUID(101), name: 'Salat', items: [item('p2', 'Achichuk')] },
      ]),
    );
    fixture.componentRef.setInput('category', UUID(101));
    fixture.detectChanges();

    host.querySelectorAll<HTMLButtonElement>('.chip')[1].click(); // "Osh"
    fixture.detectChanges();

    expect(host.textContent).toContain('Toshkent oshi');
    expect(host.textContent).not.toContain('Achichuk');
  });
});

describe('HomeComponent -- the brand switcher and the marketplace layout (ADR 0183 D17)', () => {
  const eight = () =>
    response(
      Array.from({ length: 8 }, (_, i) => ({
        id: `c${i + 1}`,
        name: `Category ${i + 1}`,
        items: [item(`p${i + 1}`, `Dish ${i + 1}`)],
      })),
    );

  const railNames = (host: HTMLElement) =>
    Array.from(host.querySelectorAll('nav[aria-label="home.categories"] .chip'))
      .map((chip) => (chip.textContent ?? '').trim())
      .filter((name) => name.startsWith('Category '));

  it('draws no switcher and the whole category rail when the brand uses neither', async () => {
    const { host } = await setUp(eight());

    expect(host.querySelector('[data-testid="brand-switcher"]')).toBeNull();
    expect(host.querySelector('[data-testid="all-categories-open"]')).toBeNull();
    expect(railNames(host)).toHaveLength(8);
  });

  it('draws the other brands as links to their own websites, above the stories', async () => {
    const { fixture, host } = await setUp(eight());
    const content = TestBed.inject(ContentService) as unknown as FakeContentService;

    content.brandSwitcher.set([
      { targetBrandId: 'b-2', name: 'Pizza House', url: 'https://pizza.example.uz/' },
      { targetBrandId: 'b-3', name: 'Sushi Bar', url: 'https://sushi.example.uz/' },
    ]);
    fixture.detectChanges();

    const links = Array.from(host.querySelectorAll('[data-testid="brand-switcher"] a'));
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      'https://pizza.example.uz/',
      'https://sushi.example.uz/',
    ]);
  });

  it('compacts the rail and offers «all categories» when the brand chose the marketplace layout', async () => {
    const { fixture, host } = await setUp(eight());
    const content = TestBed.inject(ContentService) as unknown as FakeContentService;

    content.layout.set('MARKETPLACE');
    fixture.detectChanges();

    expect(railNames(host)).toHaveLength(5);
    expect(host.querySelector('[data-testid="all-categories-open"]')).not.toBeNull();
  });
});

describe('the no-content value', () => {
  it('is the same empty document a failed read leaves', () => {
    expect(NO_CONTENT.carousel).toEqual([]);
    expect(NO_CONTENT.popup).toBeNull();
  });
});
