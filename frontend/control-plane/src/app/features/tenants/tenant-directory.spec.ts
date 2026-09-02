import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { Page } from '../../core/api/page';
import { TenantDirectory } from './tenant-directory';
import { TenantSummaryView, TenantView, TenantsApi } from './tenants-api';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

const TENANT_A: TenantSummaryView = {
  id: 'tenant-a',
  slug: 'tenant-a',
  legalName: 'Tenant A LLC',
  displayName: 'Tenant A',
  defaultCurrency: 'UZS',
  defaultTimezone: 'Asia/Tashkent',
  status: 'ACTIVE',
  createdAt: '2026-08-01T00:00:00Z',
};

class FakeTenantsApi {
  readonly listTenants = vi.fn<() => Promise<Page<TenantSummaryView>>>();
  readonly createTenant = vi.fn<() => Promise<TenantView>>();
}

describe('TenantDirectory', () => {
  let fixture: ComponentFixture<TenantDirectory>;
  let api: FakeTenantsApi;
  let router: Router;

  beforeEach(async () => {
    api = new FakeTenantsApi();
    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [TenantDirectory],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: TenantsApi, useValue: api },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
  });

  it('loads and renders the tenant list', async () => {
    api.listTenants.mockResolvedValue({ items: [TENANT_A], nextCursor: null });

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('Tenant A');
    expect(rows[0].textContent).toContain('tenant-a');
  });

  it('shows the empty state when there are no tenants', async () => {
    api.listTenants.mockResolvedValue({ items: [], nextCursor: null });

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Клиентов пока нет.');
  });

  it('shows a translated error rather than a raw code on failure', async () => {
    api.listTenants.mockRejectedValue(new ApiError({ status: 403, code: 'INSUFFICIENT_CAPABILITY' }));

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('У вас нет права');
  });

  it('offers a "load more" button only while a cursor remains, and appends the next page', async () => {
    api.listTenants.mockResolvedValueOnce({ items: [TENANT_A], nextCursor: 'tenant-a' });

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const loadMore = fixture.nativeElement.querySelector('.loadMore') as HTMLButtonElement;
    expect(loadMore).not.toBeNull();

    const tenantB: TenantSummaryView = { ...TENANT_A, id: 'tenant-b', slug: 'tenant-b', displayName: 'Tenant B' };
    api.listTenants.mockResolvedValueOnce({ items: [tenantB], nextCursor: null });

    loadMore.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(fixture.nativeElement.querySelector('.loadMore')).toBeNull();
  });

  it('creates a tenant and navigates straight to its detail page', async () => {
    api.listTenants.mockResolvedValue({ items: [], nextCursor: null });
    api.createTenant.mockResolvedValue({
      id: 'new-tenant',
      slug: 'new-tenant',
      legalName: 'New Tenant LLC',
      displayName: 'New Tenant',
      defaultCurrency: 'UZS',
      defaultTimezone: 'Asia/Tashkent',
      keycloakOrganizationId: null,
      status: 'PROVISIONING',
      customerIdentityMode: 'TENANT_SHARED',
    });

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.primary').click();
    fixture.detectChanges();

    const fields = fixture.nativeElement.querySelectorAll('.drawer input[type="text"]');
    const values = ['new-tenant', 'New Tenant LLC', 'New Tenant', 'UZS', 'Asia/Tashkent'];
    fields.forEach((field: HTMLInputElement, index: number) => {
      field.value = values[index];
      field.dispatchEvent(new Event('input'));
    });
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.drawer').dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();

    expect(api.createTenant).toHaveBeenCalledWith(
      expect.objectContaining({ slug: 'new-tenant', legalName: 'New Tenant LLC', displayName: 'New Tenant' }),
    );
    expect(router.navigate).toHaveBeenCalledWith(['/tenants', 'new-tenant']);
  });
});
