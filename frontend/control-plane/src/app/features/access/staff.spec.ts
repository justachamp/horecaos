import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { BrandView, LocationView, TenantSummaryView, TenantsApi } from '../tenants/tenants-api';
import {
  AccessApi,
  PendingApprovalResponse,
  PlatformGrantResponse,
  PlatformGrantView,
  TenantGrantView,
  TenantRoleDescriptor,
} from './access-api';
import { Staff } from './staff';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

const GRANT: PlatformGrantView = {
  id: 'grant-1',
  principalSubject: 'staff-a',
  roleCode: 'platform-support',
  status: 'ACTIVE',
  grantedBy: 'owner',
};

const PENDING: PendingApprovalResponse = {
  id: 'req-1',
  actionCode: 'tenant.activate',
  parametersHash: 'hash',
  scopeType: 'TENANT',
  scopeId: 'tenant-1',
  thresholdDescription: 'Any tenant activation needs a second signature.',
  policyVersion: 1,
  requiredApproverCapability: 'TENANT_WRITE',
  requestedBy: 'staff-a',
  requestedAt: '2026-09-01T00:00:00Z',
  expiresAt: '2026-09-02T00:00:00Z',
  mayDecide: true,
};

const TENANT: TenantSummaryView = {
  id: 'tenant-1',
  slug: 'oshxona',
  legalName: 'Oshxona LLC',
  displayName: 'Oshxona',
  defaultCurrency: 'UZS',
  defaultTimezone: 'Asia/Tashkent',
  status: 'ACTIVE',
  createdAt: '2026-09-01T00:00:00Z',
};

const BRAND: BrandView = {
  id: 'brand-1',
  tenantId: 'tenant-1',
  code: 'OSHXONA',
  slug: 'oshxona',
  displayName: 'Oshxona Brand',
  status: 'ACTIVE',
  version: 0,
};

const LOCATION = {
  id: 'location-1',
  brandId: 'brand-1',
  displayName: 'Chilonzor',
} as LocationView;

const TENANT_GRANT: TenantGrantView = {
  id: 'tg-1',
  principalSubject: 'manager-subject',
  roleCode: 'location-manager',
  scopeType: 'LOCATION',
  scopeId: 'location-1',
  status: 'ACTIVE',
  grantedBy: 'owner',
};

const ROLES: TenantRoleDescriptor[] = [
  { code: 'tenant-admin', scopeType: 'TENANT', capabilities: [] },
  { code: 'location-manager', scopeType: 'LOCATION', capabilities: [] },
];

class FakeAccessApi {
  readonly listPlatformGrants = vi.fn<() => Promise<PlatformGrantView[]>>();
  readonly grantPlatform = vi.fn<() => Promise<PlatformGrantResponse>>();
  readonly revokePlatformGrant = vi.fn<() => Promise<PlatformGrantResponse>>();
  readonly listTenantGrants = vi.fn<() => Promise<TenantGrantView[]>>();
  readonly listTenantRoles = vi.fn<() => Promise<TenantRoleDescriptor[]>>();
  readonly revokeTenantGrant = vi.fn<(...args: string[]) => Promise<void>>();
  readonly grantTenant = vi.fn<(...args: unknown[]) => Promise<{ grantId: string }>>();
  readonly pendingApprovals = vi.fn<() => Promise<{ items: PendingApprovalResponse[]; nextCursor: string | null }>>();
  readonly decide = vi.fn();
}

class FakeTenantsApi {
  readonly listTenants = vi.fn().mockResolvedValue({ items: [TENANT], nextCursor: null });
  readonly getBrands = vi.fn().mockResolvedValue([BRAND]);
  readonly getLocations = vi.fn().mockResolvedValue([LOCATION]);
}

describe('Staff', () => {
  let fixture: ComponentFixture<Staff>;
  let api: FakeAccessApi;

  beforeEach(async () => {
    api = new FakeAccessApi();
    api.listPlatformGrants.mockResolvedValue([GRANT]);
    api.listTenantGrants.mockResolvedValue([TENANT_GRANT]);
    api.listTenantRoles.mockResolvedValue(ROLES);
    api.pendingApprovals.mockResolvedValue({ items: [], nextCursor: null });
    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [Staff],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: AccessApi, useValue: api },
        { provide: TenantsApi, useValue: new FakeTenantsApi() },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(Staff);
    await settle();
  });

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  async function chooseTenant(): Promise<void> {
    const select = fixture.nativeElement.querySelector('select[name="tenant"]') as HTMLSelectElement;
    select.value = 'tenant-1';
    select.dispatchEvent(new Event('change'));
    await settle();
  }

  function button(label: string, within: ParentNode = fixture.nativeElement): HTMLButtonElement | undefined {
    return (Array.from(within.querySelectorAll('button')) as HTMLButtonElement[]).find(
      (b) => b.textContent?.trim() === label,
    );
  }

  it('lists platform-scope grants', () => {
    expect(fixture.nativeElement.textContent).toContain('staff-a');
    expect(fixture.nativeElement.textContent).toContain('platform-support');
  });

  it('shows the awaiting-approval outcome distinctly when granting is policy-gated', async () => {
    api.grantPlatform.mockResolvedValue({ outcome: 'AWAITING_APPROVAL', grantId: null, approvalRequestId: 'req-9' });
    api.listPlatformGrants.mockResolvedValue([GRANT]);

    const form = fixture.nativeElement.querySelectorAll('.panel')[0].querySelector('form');
    const [principal, role, reason] = form.querySelectorAll('input');
    principal.value = 'new-admin';
    principal.dispatchEvent(new Event('input'));
    role.value = 'platform-support';
    role.dispatchEvent(new Event('input'));
    reason.value = 'onboarding a new support engineer';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    form.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();

    expect(api.grantPlatform).toHaveBeenCalledWith('new-admin', 'platform-support', 'onboarding a new support engineer');
    expect(fixture.nativeElement.textContent).toContain('Ожидает вторую подпись');
  });

  it('shows a chosen tenant’s grants by place name, and revokes one only with a reason', async () => {
    await chooseTenant();
    api.revokeTenantGrant.mockResolvedValue();

    const table = fixture.nativeElement.querySelector('.tenantGrants') as HTMLElement;
    expect(table.textContent).toContain('manager-subject');
    expect(table.textContent).toContain('Chilonzor');

    // Scoped to the tenant table: the platform grants above carry a revoke button with the same label.
    const inTable = (label: string) => button(label, fixture.nativeElement.querySelector('.tenantGrants'));
    inTable(ru['staff.revoke.action'])!.click();
    await settle();
    expect(inTable(ru['staff.revokeTenant.confirm'])!.disabled).toBe(true);
    const reason = fixture.nativeElement.querySelector('input[name="revokeReason"]') as HTMLInputElement;
    reason.value = 'left the company';
    reason.dispatchEvent(new Event('input'));
    await settle();
    inTable(ru['staff.revokeTenant.confirm'])!.click();
    await settle();

    expect(api.revokeTenantGrant).toHaveBeenCalledWith('tenant-1', 'tg-1', 'left the company');
    expect(fixture.nativeElement.querySelector('.tenantGrants')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain(ru['staff.revokeTenant.done']);
  });

  it('grants a location-level job at a location picked from the tenant, never a typed id', async () => {
    await chooseTenant();
    api.grantTenant.mockResolvedValue({ grantId: 'tg-2' });

    const set = async (name: string, value: string, event = 'change') => {
      const el = fixture.nativeElement.querySelector(`.grantForm [name="${name}"]`) as HTMLInputElement;
      el.value = value;
      el.dispatchEvent(new Event(event));
      await settle();
    };
    await set('principal', 'new-manager', 'input');
    await set('role', 'location-manager');
    expect(fixture.nativeElement.querySelector('.grantForm button[type="submit"]').disabled).toBe(true);
    await set('brand', 'brand-1');
    await set('location', 'location-1');
    await set('reason', 'runs the Chilonzor branch', 'input');
    (fixture.nativeElement.querySelector('.grantForm button[type="submit"]') as HTMLButtonElement).click();
    await settle();

    expect(api.grantTenant).toHaveBeenCalledWith(
      'tenant-1',
      'new-manager',
      'location-manager',
      'runs the Chilonzor branch',
      'brand-1',
      'location-1',
    );
  });

  it('decides a pending approval in the chosen tenant, removing it once decided', async () => {
    api.pendingApprovals.mockResolvedValue({ items: [PENDING], nextCursor: null });
    api.decide.mockResolvedValue({ id: 'req-1', actionCode: 'tenant.activate', status: 'APPROVED' });
    await chooseTenant();

    expect(fixture.nativeElement.textContent).toContain('tenant.activate');
    const reasonInput = fixture.nativeElement.querySelector('.decideReason') as HTMLInputElement;
    reasonInput.value = 'reviewed, looks correct';
    reasonInput.dispatchEvent(new Event('input'));
    await settle();
    button('Одобрить')!.click();
    await settle();

    expect(api.decide).toHaveBeenCalledWith('tenant-1', 'req-1', 'APPROVE', 'reviewed, looks correct');
    expect(fixture.nativeElement.textContent).not.toContain('tenant.activate');
  });

  it('refuses to let the requester decide their own request', async () => {
    api.pendingApprovals.mockResolvedValue({ items: [{ ...PENDING, mayDecide: false }], nextCursor: null });
    await chooseTenant();

    expect(fixture.nativeElement.textContent).toContain('Вы запросили это');
  });
});
