import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { CommerceApi } from '../commerce/commerce-api';
import { TenantDetail } from './tenant-detail';
import { TenantView, TenantsApi } from './tenants-api';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

function tenant(status: TenantView['status']): TenantView {
  return {
    id: 'tenant-1',
    slug: 'oshxona',
    legalName: 'Oshxona LLC',
    displayName: 'Oshxona',
    defaultCurrency: 'UZS',
    defaultTimezone: 'Asia/Tashkent',
    keycloakOrganizationId: 'org-1',
    status,
    customerIdentityMode: 'TENANT_SHARED',
  };
}

class FakeTenantsApi {
  readonly getTenant = vi.fn<() => Promise<TenantView>>();
  readonly getBrands = vi.fn().mockResolvedValue([]);
  readonly suspendTenant = vi.fn<(...args: unknown[]) => Promise<TenantView>>();
  readonly reactivateTenant = vi.fn<(...args: unknown[]) => Promise<TenantView>>();
}

describe('TenantDetail', () => {
  let fixture: ComponentFixture<TenantDetail>;
  let api: FakeTenantsApi;

  async function createWith(status: TenantView['status'], platformAdmin = true): Promise<void> {
    api = new FakeTenantsApi();
    api.getTenant.mockResolvedValue(tenant(status));
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [TenantDetail],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: TenantsApi, useValue: api },
        { provide: CommerceApi, useValue: { getEntitlements: vi.fn().mockRejectedValue(new Error('none')) } },
        { provide: SessionContextService, useValue: { has: (c: string) => platformAdmin && c === 'PLATFORM_ADMIN' } },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ tenantId: 'tenant-1' }) } } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(TenantDetail);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function button(label: string): HTMLButtonElement | undefined {
    return (Array.from(fixture.nativeElement.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (b) => b.textContent?.trim() === label,
    );
  }

  it('suspends a live tenant only with a reason, and then offers to reactivate it', async () => {
    await createWith('ACTIVE');
    api.suspendTenant.mockResolvedValue(tenant('SUSPENDED'));

    button(ru['tenantDetail.status.suspend'])!.click();
    await settle();
    const submit = button(ru['tenantDetail.status.confirmSuspend'])!;
    expect(submit.disabled).toBe(true);

    const reason = fixture.nativeElement.querySelector('input[name="reason"]') as HTMLInputElement;
    reason.value = 'unpaid invoice';
    reason.dispatchEvent(new Event('input'));
    await settle();
    button(ru['tenantDetail.status.confirmSuspend'])!.click();
    await settle();

    expect(api.suspendTenant).toHaveBeenCalledWith('tenant-1', 'unpaid invoice');
    expect(fixture.nativeElement.textContent).toContain(ru['tenantDetail.status.suspended']);
    expect(button(ru['tenantDetail.status.reactivate'])).toBeDefined();
    expect(button(ru['tenantDetail.status.suspend'])).toBeUndefined();
  });

  it('keeps the tenant as it was when the server refuses, and says why', async () => {
    await createWith('SUSPENDED');
    api.reactivateTenant.mockRejectedValue(new ApiError({ status: 409, code: 'RESOURCE_CONFLICT' }));

    button(ru['tenantDetail.status.reactivate'])!.click();
    await settle();
    const reason = fixture.nativeElement.querySelector('input[name="reason"]') as HTMLInputElement;
    reason.value = 'paid';
    reason.dispatchEvent(new Event('input'));
    await settle();
    button(ru['tenantDetail.status.confirmReactivate'])!.click();
    await settle();

    expect(fixture.nativeElement.textContent).toContain(ru['error.RESOURCE_CONFLICT']);
    expect(button(ru['tenantDetail.status.confirmReactivate'])).toBeDefined();
  });

  it('offers nothing for a tenant still being set up, and nothing at all to someone who is not a platform admin', async () => {
    await createWith('PROVISIONING');
    expect(fixture.nativeElement.textContent).toContain(ru['tenantDetail.status.notYetLive']);
    expect(button(ru['tenantDetail.status.suspend'])).toBeUndefined();

    TestBed.resetTestingModule();
    await createWith('ACTIVE', false);
    expect(fixture.nativeElement.textContent).not.toContain(ru['tenantDetail.status.title']);
  });
});
