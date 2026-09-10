import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { TenantBrands } from './tenant-brands';
import { BrandView, LocationView, TenantsApi } from './tenants-api';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

function brand(overrides: Partial<BrandView> = {}): BrandView {
  return {
    id: 'brand-1',
    tenantId: 'tenant-1',
    code: 'OSHXONA',
    slug: 'oshxona',
    displayName: 'Oshxona',
    status: 'DRAFT',
    version: 3,
    ...overrides,
  };
}

function location(overrides: Partial<LocationView> = {}): LocationView {
  return {
    id: 'location-1',
    tenantId: 'tenant-1',
    brandId: 'brand-1',
    code: 'CHILONZOR',
    slug: 'chilonzor',
    displayName: 'Chilonzor',
    timezone: 'Asia/Tashkent',
    status: 'DRAFT',
    addressLine: null,
    district: null,
    city: null,
    landmark: null,
    contactPhone: null,
    latitude: null,
    longitude: null,
    coordinateSource: 'NOT_GEOCODED',
    version: 5,
    ...overrides,
  };
}

class FakeTenantsApi {
  readonly getBrands = vi.fn<() => Promise<BrandView[]>>();
  readonly getLocations = vi.fn<() => Promise<LocationView[]>>();
  readonly reviseBrand = vi.fn<(...args: unknown[]) => Promise<BrandView>>();
  readonly reviseLocation = vi.fn<(...args: unknown[]) => Promise<LocationView>>();
  readonly deleteBrand = vi.fn<(...args: unknown[]) => Promise<void>>();
  readonly deleteLocation = vi.fn<(...args: unknown[]) => Promise<void>>();
  readonly describeLocation = vi.fn<(...args: unknown[]) => Promise<LocationView>>();
  readonly activateBrand = vi.fn();
  readonly activateLocation = vi.fn();
  readonly createBrand = vi.fn();
  readonly createLocation = vi.fn();
}

describe('TenantBrands', () => {
  let fixture: ComponentFixture<TenantBrands>;
  let api: FakeTenantsApi;

  async function createWith(brands: BrandView[], locations: LocationView[]): Promise<void> {
    api = new FakeTenantsApi();
    api.getBrands.mockResolvedValue(brands);
    api.getLocations.mockResolvedValue(locations);
    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [TenantBrands],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: TenantsApi, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ tenantId: 'tenant-1' }) } },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TenantBrands);
    await settle();
  }

  /**
   * The page loads in two awaited steps -- brands, then every brand's
   * locations -- and whenStable does not wait for plain promises, so a
   * macrotask is yielded as well: by then every promise chain started by the
   * last action has run.
   */
  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function buttons(label: string, within: ParentNode = fixture.nativeElement): HTMLButtonElement[] {
    return Array.from(within.querySelectorAll('button')).filter(
      (button) => button.textContent?.trim() === label,
    ) as HTMLButtonElement[];
  }

  function input(name: string): HTMLInputElement {
    return fixture.nativeElement.querySelector(`.drawer input[name="${name}"]`) as HTMLInputElement;
  }

  function type(name: string, value: string): void {
    const field = input(name);
    field.value = value;
    field.dispatchEvent(new Event('input'));
  }

  it('corrects a brand, sending back the version it was read at', async () => {
    await createWith([brand()], []);
    api.reviseBrand.mockResolvedValue(brand({ displayName: 'Oshxona No.1', version: 4 }));

    buttons(ru['tenantBrands.edit.action'])[0].click();
    await settle();
    expect(input('code').value).toBe('OSHXONA');
    type('displayName', 'Oshxona No.1');
    await settle();
    (fixture.nativeElement.querySelector('.drawer button[type="submit"]') as HTMLButtonElement).click();
    await settle();

    expect(api.reviseBrand).toHaveBeenCalledWith(
      'tenant-1',
      expect.objectContaining({ id: 'brand-1', version: 3 }),
      { code: 'OSHXONA', slug: 'oshxona', displayName: 'Oshxona No.1' },
    );
    expect(fixture.nativeElement.querySelector('.drawer')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Oshxona No.1');
  });

  it('locks the code and slug of a brand that has been active, but not its name', async () => {
    await createWith([brand({ status: 'ACTIVE' })], []);

    buttons(ru['tenantBrands.edit.action'])[0].click();
    await settle();

    expect(input('code').disabled).toBe(true);
    expect(input('slug').disabled).toBe(true);
    expect(input('displayName').disabled).toBe(false);
    expect(fixture.nativeElement.textContent).toContain(ru['tenantBrands.edit.brandLocked']);
  });

  it('locks a live location’s timezone along with its code and slug', async () => {
    await createWith([brand({ status: 'ACTIVE' })], [location({ status: 'ACTIVE' })]);

    buttons(ru['tenantBrands.edit.action'])[1].click();
    await settle();

    expect(input('timezone').disabled).toBe(true);
    expect(input('code').disabled).toBe(true);
    expect(input('displayName').disabled).toBe(false);
  });

  it('deletes a draft location only after a second, explicit press', async () => {
    await createWith([brand()], [location()]);
    api.deleteLocation.mockResolvedValue();

    const table = fixture.nativeElement.querySelector('.table') as HTMLElement;
    buttons(ru['tenantBrands.delete.action'], table)[0].click();
    await settle();
    expect(api.deleteLocation).not.toHaveBeenCalled();

    buttons(ru['tenantBrands.delete.confirm'])[0].click();
    await settle();

    expect(api.deleteLocation).toHaveBeenCalledWith(
      'tenant-1',
      expect.objectContaining({ id: 'location-1', brandId: 'brand-1', version: 5 }),
    );
    expect(fixture.nativeElement.textContent).not.toContain('Chilonzor');
  });

  it('never offers to delete a live brand, and holds a draft one back while it has locations', async () => {
    await createWith([brand({ status: 'ACTIVE' })], []);
    expect(buttons(ru['tenantBrands.delete.action'])).toHaveLength(0);

    TestBed.resetTestingModule();
    await createWith([brand()], [location({ status: 'ACTIVE' })]);
    const head = fixture.nativeElement.querySelector('.brandActions') as HTMLElement;
    const deleteBrand = buttons(ru['tenantBrands.delete.action'], head)[0];
    expect(deleteBrand.disabled).toBe(true);
    expect(deleteBrand.title).toBe(ru['tenantBrands.delete.hasLocationsHint']);
  });

  it('explains a refused delete from the platform’s reason, naming what still refers to it', async () => {
    await createWith([brand()], [location()]);
    api.deleteLocation.mockRejectedValue(
      new ApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        reason: 'STILL_REFERENCED',
        referencedBy: 'location_fiscal_assignments',
      }),
    );

    buttons(ru['tenantBrands.delete.action'], fixture.nativeElement.querySelector('.table'))[0].click();
    await settle();
    buttons(ru['tenantBrands.delete.confirm'])[0].click();
    await settle();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain(ru['tenantBrands.delete.reason.STILL_REFERENCED']);
    expect(text).toContain('location_fiscal_assignments');
    expect(text).toContain('Chilonzor');
  });

  it('records a location’s place, sending a point as an operator’s pin', async () => {
    await createWith([brand()], [location()]);
    api.describeLocation.mockResolvedValue(location({ addressLine: 'Bunyodkor 1', city: 'Tashkent', latitude: 41.28, longitude: 69.2 }));

    buttons(ru['tenantBrands.place.action'])[0].click();
    await settle();
    type('addressLine', 'Bunyodkor 1');
    type('city', 'Tashkent');
    type('latitude', '41.28');
    type('longitude', '69.2');
    await settle();
    (fixture.nativeElement.querySelector('.drawer button[type="submit"]') as HTMLButtonElement).click();
    await settle();

    expect(api.describeLocation).toHaveBeenCalledWith(
      'tenant-1',
      expect.objectContaining({ id: 'location-1' }),
      expect.objectContaining({ addressLine: 'Bunyodkor 1', city: 'Tashkent', latitude: 41.28, longitude: 69.2, coordinateSource: 'OPERATOR_PIN' }),
    );
    expect(fixture.nativeElement.textContent).toContain('Bunyodkor 1, Tashkent');
  });

  it('refuses half a point, and a phone number that is not one, before asking the server', async () => {
    await createWith([brand()], [location()]);

    buttons(ru['tenantBrands.place.action'])[0].click();
    await settle();
    type('latitude', '41.28');
    await settle();
    (fixture.nativeElement.querySelector('.drawer button[type="submit"]') as HTMLButtonElement).click();
    await settle();
    expect(fixture.nativeElement.textContent).toContain(ru['tenantBrands.place.pairError']);

    type('latitude', '');
    type('contactPhone', '998712000000');
    await settle();
    (fixture.nativeElement.querySelector('.drawer button[type="submit"]') as HTMLButtonElement).click();
    await settle();
    expect(fixture.nativeElement.textContent).toContain(ru['tenantBrands.place.phoneError']);
    expect(api.describeLocation).not.toHaveBeenCalled();
  });

  it('falls back to the ordinary sentence for a refusal that carries no reason', async () => {
    await createWith([brand()], []);
    api.deleteBrand.mockRejectedValue(new ApiError({ status: 409, code: 'STALE_VERSION' }));

    buttons(ru['tenantBrands.delete.action'])[0].click();
    await settle();
    buttons(ru['tenantBrands.delete.confirm'])[0].click();
    await settle();

    expect(fixture.nativeElement.textContent).toContain(ru['error.STALE_VERSION']);
  });
});
