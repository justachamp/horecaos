import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { TenantLegalEntities } from './tenant-legal-entities';
import { BrandView, LegalEntityView, LocationFiscalAssignmentView, LocationView, TenantsApi } from './tenants-api';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const BRAND: BrandView = {
  id: 'brand-1', tenantId: 'tenant-1', code: 'OSHXONA', slug: 'oshxona', displayName: 'Oshxona', status: 'DRAFT', version: 0,
};

function location(id: string, name: string): LocationView {
  return {
    id, tenantId: 'tenant-1', brandId: 'brand-1', code: name.toUpperCase(), slug: name.toLowerCase(), displayName: name,
    timezone: 'Asia/Tashkent', status: 'DRAFT', addressLine: null, district: null, city: null, landmark: null,
    contactPhone: null, latitude: null, longitude: null, coordinateSource: 'NOT_GEOCODED', version: 0,
  };
}

function entity(id: string, name: string, status: LegalEntityView['status']): LegalEntityView {
  return {
    id, code: name.toUpperCase(), legalName: name, shortName: null, tin: '123456789', vatRegistered: false,
    vatCertificateReference: null, taxProfileId: null, registeredAddress: null, contactPhone: null, status, version: 0,
  };
}

function assignment(locationId: string, entityId: string, from: string): LocationFiscalAssignmentView {
  return {
    id: `a-${locationId}`, brandId: 'brand-1', locationId, legalEntityId: entityId, effectiveFrom: from,
    effectiveUntil: null, approvedBy: 'platform-admin', approvalReference: null, version: 0,
  };
}

class FakeTenantsApi {
  readonly getLegalEntities = vi.fn<() => Promise<LegalEntityView[]>>();
  readonly getBrands = vi.fn<() => Promise<BrandView[]>>();
  readonly getLocations = vi.fn<() => Promise<LocationView[]>>();
  readonly getLocationAssignments = vi.fn<(...args: string[]) => Promise<LocationFiscalAssignmentView[]>>();
  readonly assignLegalEntity = vi.fn<(...args: unknown[]) => Promise<LocationFiscalAssignmentView>>();
  readonly activateLegalEntity = vi.fn();
  readonly registerLegalEntity = vi.fn();
}

describe('TenantLegalEntities', () => {
  let fixture: ComponentFixture<TenantLegalEntities>;
  let api: FakeTenantsApi;

  async function createWith(
    entities: LegalEntityView[],
    locations: LocationView[],
    assignments: Record<string, LocationFiscalAssignmentView[]>,
  ): Promise<void> {
    api = new FakeTenantsApi();
    api.getLegalEntities.mockResolvedValue(entities);
    api.getBrands.mockResolvedValue(locations.length ? [BRAND] : []);
    api.getLocations.mockResolvedValue(locations);
    api.getLocationAssignments.mockImplementation(async (_t, _b, locationId) => assignments[locationId] ?? []);
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [TenantLegalEntities],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: TenantsApi, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ tenantId: 'tenant-1' }) } } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(TenantLegalEntities);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function button(label: string): HTMLButtonElement {
    return (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (b) => b.textContent?.trim() === label,
    )!;
  }

  it('shows which entity each location sells as, and flags the ones that would fail onboarding', async () => {
    await createWith(
      [entity('e-1', 'Oshxona Savdo', 'DRAFT')],
      [location('l-1', 'Chilonzor'), location('l-2', 'Yunusobod')],
      { 'l-1': [assignment('l-1', 'e-1', '2026-01-01')] },
    );

    const rows = Array.from(fixture.nativeElement.querySelectorAll('.locations tbody tr')) as HTMLElement[];
    expect(rows[0].textContent).toContain('Oshxona › Chilonzor');
    expect(rows[0].textContent).toContain('Oshxona Savdo');
    expect(rows[0].textContent).toContain(ru['legalEntities.locations.inactive']);
    expect(rows[1].textContent).toContain(ru['legalEntities.locations.unassigned']);
  });

  it('assigns an entity to a location picked from the list, never a typed id', async () => {
    await createWith([entity('e-1', 'Oshxona Savdo', 'ACTIVE')], [location('l-1', 'Chilonzor')], {});
    api.assignLegalEntity.mockResolvedValue(assignment('l-1', 'e-1', '2026-09-10'));
    api.getLocationAssignments.mockResolvedValue([assignment('l-1', 'e-1', '2026-09-10')]);

    button(ru['legalEntities.assign.action']).click();
    await settle();
    expect(fixture.nativeElement.querySelector('.drawer input[type="text"]')).toBeNull();
    const date = fixture.nativeElement.querySelector('.drawer input[type="date"]') as HTMLInputElement;
    expect(date.value).toMatch(/^\d{4}-\d{2}-\d{2}$/);

    const select = fixture.nativeElement.querySelector('.drawer select') as HTMLSelectElement;
    select.value = 'brand-1/l-1';
    select.dispatchEvent(new Event('change'));
    await settle();
    (fixture.nativeElement.querySelector('.drawer button[type="submit"]') as HTMLButtonElement).click();
    await settle();

    expect(api.assignLegalEntity).toHaveBeenCalledWith('tenant-1', 'e-1', {
      brandId: 'brand-1',
      locationId: 'l-1',
      effectiveFrom: date.value,
    });
    expect(fixture.nativeElement.textContent).toContain(ru['legalEntities.assign.done']);
  });

  it('says there is nothing to assign to when the tenant has no locations yet', async () => {
    await createWith([entity('e-1', 'Oshxona Savdo', 'ACTIVE')], [], {});

    button(ru['legalEntities.assign.action']).click();
    await settle();

    expect(fixture.nativeElement.querySelector('.drawer').textContent).toContain(ru['legalEntities.assign.noLocations']);
  });
});
