import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { TenantsApi } from '../tenants/tenants-api';
import { CommerceApi, ModuleView, TenantModuleView } from './commerce-api';
import { ModuleCatalog } from './module-catalog';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

function module(code: string, status: string, unit: string, createdBy = 'someone'): ModuleView {
  return {
    moduleId: `m-${code}`, code, name: code.toUpperCase(), description: null, billingUnit: unit,
    unitPrice: { amountMinor: 100_000, currency: 'UZS' }, featureKeys: [], status, createdBy,
    approvedBy: status === 'DRAFT' ? null : 'approver', activatedAt: null, retiredAt: null,
  };
}

const HELD: TenantModuleView = {
  tenantModuleId: 'tm-1', moduleId: 'm-kds', moduleCode: 'kds', moduleName: 'KDS', billingUnit: 'PER_LOCATION',
  unitPrice: { amountMinor: 100_000, currency: 'UZS' }, quantity: null, startedAt: '2026-09-01T00:00:00Z',
  startedBy: 'me', startReason: 'sold', endedAt: null, endedBy: null, endReason: null,
};

class FakeCommerceApi {
  readonly listModules = vi.fn().mockResolvedValue([
    module('kds', 'ACTIVE', 'PER_LOCATION'),
    module('kiosk', 'ACTIVE', 'PER_UNIT'),
    module('mine', 'DRAFT', 'PER_TENANT', 'me'),
    module('theirs', 'DRAFT', 'ONE_OFF'),
  ]);
  readonly entitlementKeys = vi.fn().mockResolvedValue([
    { code: 'telegram.digests.enabled', counted: false, unit: 'feature', defaultMode: 'HARD', resetPeriod: 'NONE' },
    { code: 'locations.max_count', counted: true, unit: 'location', defaultMode: 'SOFT', resetPeriod: 'NONE' },
  ]);
  readonly draftModule = vi.fn().mockResolvedValue({ moduleId: 'm-new' });
  readonly activateModule = vi.fn().mockResolvedValue(undefined);
  readonly retireModule = vi.fn().mockResolvedValue(undefined);
  readonly tenantModules = vi.fn().mockResolvedValue([HELD]);
  readonly addTenantModule = vi.fn().mockResolvedValue({ tenantModuleId: 'tm-2' });
  readonly endTenantModule = vi.fn().mockResolvedValue(undefined);
}

describe('ModuleCatalog', () => {
  let fixture: ComponentFixture<ModuleCatalog>;
  let api: FakeCommerceApi;

  async function create(): Promise<void> {
    api = new FakeCommerceApi();
    localStorage.clear();
    sessionStorage.clear();
    sessionStorage.setItem('horecaos.console.tenant', 'tenant-1');
    await TestBed.configureTestingModule({
      imports: [ModuleCatalog],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: CommerceApi, useValue: api },
        { provide: TenantsApi, useValue: { listTenants: vi.fn().mockResolvedValue({ items: [], nextCursor: null }) } },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(ModuleCatalog);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function el<T extends HTMLElement>(selector: string): T {
    return fixture.nativeElement.querySelector(selector) as T;
  }

  async function set(selector: string, value: string, event = 'input'): Promise<void> {
    const input = el<HTMLInputElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event(event));
    await settle();
  }

  it('lists every module with its unit, and lets nobody put their own draft on sale', async () => {
    await create();

    expect(el('[data-module="kds"]').textContent).toContain(ru['moduleCatalog.unit.PER_LOCATION']);
    expect(el('[data-module="mine"] .activateToggle')).toBeNull();
    expect(el('[data-module="mine"]').textContent).toContain(ru['moduleCatalog.activate.notYours']);
    expect(el('[data-module="theirs"] .activateToggle')).not.toBeNull();
  });

  it('offers only features to switch on and sends the draft priced in minor units', async () => {
    await create();
    el<HTMLButtonElement>('.draftToggle').click();
    await settle();

    expect(el('[name="feature-telegram.digests.enabled"]')).not.toBeNull();
    expect(el('[name="feature-locations.max_count"]')).toBeNull();

    await set('[name="moduleCode"]', 'white-label');
    await set('[name="moduleName"]', 'White-label app');
    await set('[name="moduleUnit"]', 'ONE_OFF', 'change');
    await set('[name="modulePrice"]', '5 000 000');
    const feature = el<HTMLInputElement>('[name="feature-telegram.digests.enabled"]');
    feature.checked = true;
    feature.dispatchEvent(new Event('change'));
    await set('[name="moduleReason"]', 'the 2026 price list');
    el<HTMLButtonElement>('.draftForm button[type="submit"]').click();
    await settle();

    expect(api.draftModule).toHaveBeenCalledWith(
      expect.objectContaining({
        code: 'white-label',
        billingUnit: 'ONE_OFF',
        unitPriceMinor: 5_000_000,
        featureKeys: ['telegram.digests.enabled'],
      }),
    );
  });

  it('asks how many only for a per-unit module and adds it to the chosen tenant', async () => {
    await create();
    expect(el('[data-held="kds"]')).not.toBeNull();

    await set('[name="addModule"]', 'm-kiosk', 'change');
    expect(el('[name="addQuantity"]')).not.toBeNull();
    await set('[name="addReason"]', 'two kiosks');
    expect(el<HTMLButtonElement>('.addForm button[type="submit"]').disabled).toBe(true);
    await set('[name="addQuantity"]', '2');
    el<HTMLButtonElement>('.addForm button[type="submit"]').click();
    await settle();

    expect(api.addTenantModule).toHaveBeenCalledWith('tenant-1', 'm-kiosk', 'two kiosks', 2);
  });

  it('ends a tenant’s module with a reason', async () => {
    await create();
    el<HTMLButtonElement>('[data-held="kds"] .endToggle').click();
    await settle();
    await set('[name="endReason"]', 'closed the kitchen');
    el<HTMLButtonElement>('.confirmEnd').click();
    await settle();

    expect(api.endTenantModule).toHaveBeenCalledWith('tenant-1', 'tm-1', 'closed the kitchen');
  });
});
