import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { DineInTableComponent } from './dine-in-table.component';
import { DineInService, type DineInAdmission } from '../../../services/dine-in.service';
import { LangService } from '../../../services/lang.service';
import { MenuService } from '../../../services/menu.service';
import { TranslateService } from '../../../services/translate.service';
import type { CustomerUiResponse, MenuItem, MenuItemVariant } from '../../../types/home.types';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}(${JSON.stringify(params)})` : key;
  current = (): Record<string, unknown> => ({});
}

class FakeDineInService {
  private readonly admissionSig = signal<DineInAdmission | null>(null);
  admission = () => this.admissionSig();
  seed(value: DineInAdmission | null): void {
    this.admissionSig.set(value);
  }
}

class FakeMenuService {
  readonly currency = signal<string | null>('UZS');
  home = vi.fn<(...args: unknown[]) => Promise<CustomerUiResponse>>();
}

function variant(overrides: Partial<MenuItemVariant> = {}): MenuItemVariant {
  return {
    id: 'v1',
    name: '',
    active: true,
    onSaleNow: true,
    preparation_time: 0,
    price: 45_000,
    price_without_discount: 45_000,
    remainingQuantity: null,
    ...overrides,
  };
}

function dish(id: string, name: string, variants: MenuItemVariant[] = [variant()]): MenuItem {
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

function menu(items: MenuItem[] = [dish('p1', 'Osh')]): CustomerUiResponse {
  return {
    category: null,
    offer: null,
    populars: [],
    populars_count: 0,
    menu: {
      categories: [{ id: 'c1', name: 'Main' }],
      category_items: [{ id: 'c1', name: 'Main', items, items_count: items.length }],
      category_items_count: 1,
    },
  };
}

function admission(overrides: Partial<DineInAdmission> = {}): DineInAdmission {
  return {
    expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    mode: 'VIEW_ONLY',
    tenantId: 'tenant-1',
    brandId: 'brand-1',
    locationId: 'location-1',
    tableCode: 'T7',
    openSessionId: null,
    channelCode: 'QRTABLE',
    ...overrides,
  };
}

function setUp() {
  const dineIn = new FakeDineInService();
  const menuService = new FakeMenuService();
  TestBed.configureTestingModule({
    imports: [DineInTableComponent],
    providers: [
      provideRouter([]),
      { provide: DineInService, useValue: dineIn },
      { provide: MenuService, useValue: menuService },
      { provide: LangService, useValue: { langId: () => 'uz' } },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(DineInTableComponent);
  return { fixture, dineIn, menuService, host: fixture.nativeElement as HTMLElement };
}

async function render(fixture: ReturnType<typeof setUp>['fixture']): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

describe('DineInTableComponent', () => {
  it('asks the visitor to scan a table when nothing has been scanned this visit', async () => {
    const { fixture, host, menuService } = setUp();

    await render(fixture);

    expect(host.querySelector('[data-testid="dine-in-no-admission"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="dish-card"]')).toBeNull();
    expect(menuService.home).not.toHaveBeenCalled();
  });

  describe('VIEW_ONLY -- the menu-only mode', () => {
    it('shows the table and its menu, read on the table\'s own location and QR channel', async () => {
      const { fixture, host, dineIn, menuService } = setUp();
      dineIn.seed(admission());
      menuService.home.mockResolvedValue(menu([dish('p1', 'Osh'), dish('p2', 'Norin')]));

      await render(fixture);

      // The branch and channel come from the admission, never from this
      // build's own config: a table's QR_TABLE channel is resolved per scan.
      expect(menuService.home).toHaveBeenCalledWith('uz', 'location-1', 'QRTABLE');
      expect(host.querySelector('[data-testid="dine-in-table-code"]')?.textContent).toContain('"code":"T7"');
      expect(host.querySelectorAll('[data-testid="dish-card"]').length).toBe(2);
      expect(host.textContent).toContain('Norin');
    });

    it('offers no way to order: no link into the delivery product page, no add control, no ordering notice', async () => {
      const { fixture, host, dineIn, menuService } = setUp();
      dineIn.seed(admission());
      menuService.home.mockResolvedValue(menu());

      await render(fixture);

      const card = host.querySelector('[data-testid="dish-card"]') as HTMLElement;
      expect(card.tagName).not.toBe('A');
      expect(card.getAttribute('href')).toBeNull();
      expect(host.querySelector('a[href^="/product"]')).toBeNull();
      expect(host.querySelector('[data-testid="dine-in-ordering-unavailable"]')).toBeNull();
    });

    it('marks a sold-out dish and a dish outside its sale window, so a guest is not shown a dish they cannot have as if they could', async () => {
      const { fixture, host, dineIn, menuService } = setUp();
      dineIn.seed(admission());
      menuService.home.mockResolvedValue(
        menu([
          dish('p1', 'Osh', [variant({ active: false })]),
          dish('p2', 'Nonushta', [variant({ onSaleNow: false })]),
          dish('p3', 'Norin'),
        ]),
      );

      await render(fixture);

      expect(host.querySelectorAll('[data-testid="dish-sold-out"]').length).toBe(1);
      expect(host.querySelectorAll('[data-testid="dish-out-of-window"]').length).toBe(1);
    });

    it('omits the channel when the tenant registered none, so the build\'s own channel applies', async () => {
      const { fixture, dineIn, menuService } = setUp();
      dineIn.seed(admission({ channelCode: null }));
      menuService.home.mockResolvedValue(menu());

      await render(fixture);

      expect(menuService.home).toHaveBeenCalledWith('uz', 'location-1', undefined);
    });

    it('says the menu is unavailable when it cannot be read, instead of an empty page', async () => {
      const { fixture, host, dineIn, menuService } = setUp();
      dineIn.seed(admission());
      menuService.home.mockRejectedValue(new Error('offline'));

      await render(fixture);

      expect(host.querySelector('[data-testid="dine-in-menu-unavailable"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="dish-card"]')).toBeNull();
    });

    it('says the menu is unavailable when the branch has no dishes', async () => {
      const { fixture, host, dineIn, menuService } = setUp();
      dineIn.seed(admission());
      menuService.home.mockResolvedValue(menu([]));

      await render(fixture);

      expect(host.querySelector('[data-testid="dine-in-menu-unavailable"]')).not.toBeNull();
    });
  });

  describe('ORDER_AND_PAY -- ordering at the table is not built in this app', () => {
    it('still shows the menu, and says plainly that ordering from the table is not available here', async () => {
      const { fixture, host, dineIn, menuService } = setUp();
      dineIn.seed(admission({ mode: 'ORDER_AND_PAY', openSessionId: 'session-1' }));
      menuService.home.mockResolvedValue(menu());

      await render(fixture);

      expect(host.querySelector('[data-testid="dine-in-ordering-unavailable"]')?.textContent).toContain(
        'dineIn.orderingUnavailable',
      );
      expect(host.querySelectorAll('[data-testid="dish-card"]').length).toBe(1);
      // No cart is opened on the guest's behalf, and nothing links into the
      // delivery flow.
      expect(host.querySelector('a[href^="/product"]')).toBeNull();
    });
  });
});
