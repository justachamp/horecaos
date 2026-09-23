import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../core/api/catalog-paths';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { LocationsApi } from '../settings/locations/locations-api';
import { SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { CatalogApi } from './catalog-api';
import { MenuSetSummary, MenuSetsApi } from './menu-sets-api';
import { MenuSetsPage } from './menu-sets-page';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

const MAIN_MENU: MenuSetSummary = {
  menuId: 'menu-1',
  name: 'Main menu',
  status: 'DRAFT',
  version: 1,
};

function stubApis(overrides: {
  menus?: Partial<MenuSetsApi>;
  catalog?: Partial<CatalogApi>;
  locations?: Partial<LocationsApi>;
  channels?: Partial<SalesChannelsApi>;
}) {
  return {
    menus: {
      list: vi.fn().mockResolvedValue([MAIN_MENU]),
      bindings: vi.fn().mockResolvedValue([]),
      items: vi.fn().mockResolvedValue([]),
      create: vi.fn().mockResolvedValue(MAIN_MENU),
      copy: vi.fn().mockResolvedValue({ ...MAIN_MENU, menuId: 'menu-2', name: 'Copy' }),
      addByFilter: vi.fn().mockResolvedValue({ added: 0 }),
      bind: vi.fn().mockResolvedValue(undefined),
      unbind: vi.fn().mockResolvedValue(undefined),
      removeItem: vi.fn().mockResolvedValue(undefined),
      ...overrides.menus,
    } as unknown as MenuSetsApi,
    catalog: {
      listCatalogs: vi
        .fn()
        .mockReturnValue(
          of([{ catalogId: 'cat-1', code: 'MAIN', name: 'Main', status: 'ACTIVE' }]),
        ),
      listCategories: vi.fn().mockReturnValue(of([])),
      ...overrides.catalog,
    } as unknown as CatalogApi,
    locations: {
      list: vi.fn().mockResolvedValue([]),
      ...overrides.locations,
    } as unknown as LocationsApi,
    channels: {
      list: vi.fn().mockResolvedValue([]),
      ...overrides.channels,
    } as unknown as SalesChannelsApi,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('MenuSetsPage', () => {
  let fixture: ComponentFixture<MenuSetsPage>;

  async function render(
    scope: BrandScope | null,
    apis: ReturnType<typeof stubApis>,
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [MenuSetsPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(scope),
            denied: signal(scope === null),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: MenuSetsApi, useValue: apis.menus },
        { provide: CatalogApi, useValue: apis.catalog },
        { provide: LocationsApi, useValue: apis.locations },
        { provide: SalesChannelsApi, useValue: apis.channels },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(MenuSetsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it("lists the brand's menus after load", async () => {
    const apis = stubApis({});
    await render(SCOPE, apis);

    const host = fixture.nativeElement as HTMLElement;
    expect(apis.menus.list).toHaveBeenCalledWith(SCOPE);
    expect(host.querySelector('[data-testid="menu-sets-table"]')?.textContent).toContain(
      'Main menu',
    );
  });

  it('shows the denied state with no brand scope', async () => {
    const apis = stubApis({});
    await render(null, apis);

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="menu-sets-denied"]')).not.toBeNull();
    expect(apis.menus.list).not.toHaveBeenCalled();
  });

  it('creates a menu and refreshes the list', async () => {
    const apis = stubApis({});
    await render(SCOPE, apis);
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="menu-sets-create-button"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const nameInput = host.querySelector(
      '[data-testid="menu-sets-create-name"]',
    ) as HTMLInputElement;
    nameInput.value = 'Delivery menu';
    nameInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (host.querySelector('[data-testid="menu-sets-create-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(apis.menus.create).toHaveBeenCalledWith(SCOPE, 'Delivery menu');
    expect(apis.menus.list).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[data-testid="menu-sets-create-dialog"]')).toBeNull();
  });

  it('copies a menu with a caller-supplied name', async () => {
    const apis = stubApis({});
    await render(SCOPE, apis);
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="menu-sets-copy-menu-1"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const nameInput = host.querySelector('[data-testid="menu-sets-copy-name"]') as HTMLInputElement;
    nameInput.value = 'Second branch menu';
    nameInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (host.querySelector('[data-testid="menu-sets-copy-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(apis.menus.copy).toHaveBeenCalledWith(SCOPE, 'menu-1', 'Second branch menu');
  });

  it('selecting a menu loads its items, and add-by-filter reports how many were added', async () => {
    const apis = stubApis({
      menus: { addByFilter: vi.fn().mockResolvedValue({ added: 3 }) },
    });
    await render(SCOPE, apis);
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="menu-sets-select-menu-1"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(apis.menus.items).toHaveBeenCalledWith(SCOPE, 'menu-1');

    (host.querySelector('[data-testid="menu-sets-filter-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(apis.menus.addByFilter).toHaveBeenCalledWith(
      SCOPE,
      'menu-1',
      expect.objectContaining({ availabilityDefault: 'AVAILABLE' }),
    );
    expect(host.querySelector('[data-testid="menu-sets-filter-result"]')?.textContent).toContain(
      '3',
    );
  });

  it('binds the selected menu to a chosen branch', async () => {
    const apis = stubApis({
      // bindToBranch refuses anything but ACTIVE (a fresh menu starts DRAFT).
      menus: {
        list: vi.fn().mockResolvedValue([{ ...MAIN_MENU, status: 'ACTIVE' }]),
      },
      locations: {
        list: vi
          .fn()
          .mockResolvedValue([
            {
              id: 'loc-1',
              tenantId: 't1',
              brandId: 'b1',
              code: 'MAIN',
              slug: 'main',
              displayName: 'Main branch',
              timezone: 'Asia/Tashkent',
              status: 'ACTIVE',
              addressLine: null,
              district: null,
              city: null,
              landmark: null,
              contactPhone: null,
              latitude: null,
              longitude: null,
              coordinateSource: 'NOT_GEOCODED',
            },
          ]),
      },
    });
    await render(SCOPE, apis);
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="menu-sets-select-menu-1"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const branchSelect = host.querySelector(
      '[data-testid="menu-sets-bind-location"]',
    ) as HTMLSelectElement;
    branchSelect.value = 'loc-1';
    branchSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (host.querySelector('[data-testid="menu-sets-bind-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(apis.menus.bind).toHaveBeenCalledWith(SCOPE, 'loc-1', 'menu-1', null);
  });

  it('disables binding a still-DRAFT menu and shows a warning instead of letting it bind silently', async () => {
    // MAIN_MENU's default status is DRAFT -- the state every menu starts in.
    const apis = stubApis({});
    await render(SCOPE, apis);
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="menu-sets-select-menu-1"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const submit = host.querySelector(
      '[data-testid="menu-sets-bind-submit"]',
    ) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    expect(host.querySelector('[data-testid="menu-sets-bind-not-active"]')).toBeTruthy();

    submit.click();
    await flushMicrotasks();
    expect(apis.menus.bind).not.toHaveBeenCalled();
  });
});
