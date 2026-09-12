import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { AccessCheckApi, AccessCheckResponse } from './access-check-api';
import { AccessCheckPage } from './access-check-page';
import { GrantView, RoleDescriptor, ScopeDirectory, StaffApi } from './staff-api';

function grant(overrides: Partial<GrantView> = {}): GrantView {
  return {
    id: 'grant-1',
    principalSubject: 'aziza',
    roleCode: 'location-staff',
    scopeType: 'LOCATION',
    scopeId: 'loc-1',
    status: 'ACTIVE',
    grantedBy: 'owner-1',
    reason: 'New hire',
    validFrom: '2026-03-12T00:00:00Z',
    validUntil: null,
    revokedAt: null,
    revokedBy: null,
    revokedReason: null,
    ...overrides,
  };
}

function role(overrides: Partial<RoleDescriptor> = {}): RoleDescriptor {
  return {
    code: 'location-staff',
    scopeType: 'LOCATION',
    capabilities: ['order.approve', 'order.cancel'],
    ...overrides,
  };
}

const DIRECTORY: ScopeDirectory = {
  brands: [{ id: 'brand-1', displayName: 'Main brand' }],
  locations: [{ id: 'loc-1', brandId: 'brand-1', displayName: 'Chilonzor' }],
};

function answer(overrides: Partial<AccessCheckResponse> = {}): AccessCheckResponse {
  return {
    verdict: 'ALLOWED',
    capability: 'order.approve',
    scopeType: 'LOCATION',
    scopeId: 'loc-1',
    heldElsewhere: [grant()],
    entitlement: null,
    ...overrides,
  };
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('t1');
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

async function setUp(checkResult?: () => Promise<AccessCheckResponse>) {
  const staffApi = {
    listGrants: vi.fn(async () => [grant()]),
    roles: vi.fn(async () => [role()]),
    scopeDirectory: vi.fn(async () => DIRECTORY),
  };
  const accessCheckApi = {
    check: vi.fn(checkResult ?? (async () => answer())),
  };
  await TestBed.configureTestingModule({
    imports: [AccessCheckPage],
    providers: [
      provideRouter([]),
      { provide: StaffApi, useValue: staffApi },
      { provide: AccessCheckApi, useValue: accessCheckApi },
      { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture: ComponentFixture<AccessCheckPage> = TestBed.createComponent(AccessCheckPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return { fixture, staffApi, accessCheckApi };
}

function select(fixture: ComponentFixture<AccessCheckPage>, testId: string): HTMLSelectElement {
  return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`) as HTMLSelectElement;
}

function fireChange(select: HTMLSelectElement, value: string): void {
  select.value = value;
  select.dispatchEvent(new Event('change'));
}

describe('AccessCheckPage', () => {
  it('loads the subject and capability pickers from what the tenant already has', async () => {
    const { fixture } = await setUp();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('aziza');
    expect(text).toContain('order.approve');
  });

  it('loads the brand directory once a scope narrower than the whole company is chosen', async () => {
    const { fixture } = await setUp();
    fireChange(select(fixture, 'access-check-scope-type'), 'BRAND');
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Main brand');
  });

  it('cannot submit until who, what and where are all chosen', async () => {
    const { fixture } = await setUp();
    const submit = fixture.nativeElement.querySelector(
      '[data-testid="access-check-submit"]',
    ) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
  });

  it('asks GrantController.accessCheck with the chosen subject, capability and scope', async () => {
    const { fixture, accessCheckApi } = await setUp();

    fireChange(select(fixture, 'access-check-subject'), 'aziza');
    fireChange(select(fixture, 'access-check-capability'), 'order.approve');
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector('[data-testid="access-check-submit"]') as HTMLElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(accessCheckApi.check).toHaveBeenCalledWith('t1', {
      subject: 'aziza',
      capability: 'order.approve',
      scopeType: 'TENANT',
      brandId: undefined,
      locationId: undefined,
      entitlementKey: undefined,
    });
  });

  it('shows an ALLOWED answer', async () => {
    const { fixture } = await setUp(async () => answer({ verdict: 'ALLOWED' }));
    fireChange(select(fixture, 'access-check-subject'), 'aziza');
    fireChange(select(fixture, 'access-check-capability'), 'order.approve');
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector('[data-testid="access-check-submit"]') as HTMLElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Yes, she can.');
  });

  it('shows an INSUFFICIENT_CAPABILITY answer with the grant held elsewhere', async () => {
    const { fixture } = await setUp(async () =>
      answer({ verdict: 'INSUFFICIENT_CAPABILITY', heldElsewhere: [grant()] }),
    );
    fireChange(select(fixture, 'access-check-subject'), 'aziza');
    fireChange(select(fixture, 'access-check-capability'), 'order.approve');
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector('[data-testid="access-check-submit"]') as HTMLElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('No, she can’t.');
    expect(text).toContain('location-staff');
    expect(text).toContain('Chilonzor');
  });

  it('shows an ENTITLEMENT_REQUIRED answer distinctly from a denial', async () => {
    const { fixture } = await setUp(async () =>
      answer({
        verdict: 'ENTITLEMENT_REQUIRED',
        heldElsewhere: [],
        entitlement: { description: 'Courier dispatch module', upgradePath: '/plans' },
      }),
    );
    fireChange(select(fixture, 'access-check-subject'), 'aziza');
    fireChange(select(fixture, 'access-check-capability'), 'order.approve');
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector('[data-testid="access-check-submit"]') as HTMLElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Her rights are enough.');
    expect(text).toContain('Courier dispatch module');
  });

  it('shows the denied state on a 403 rather than an empty form', async () => {
    const staffApi = {
      listGrants: vi.fn(async () => {
        throw new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null);
      }),
      roles: vi.fn(async () => []),
      scopeDirectory: vi.fn(async () => DIRECTORY),
    };
    await TestBed.configureTestingModule({
      imports: [AccessCheckPage],
      providers: [
        provideRouter([]),
        { provide: StaffApi, useValue: staffApi },
        { provide: AccessCheckApi, useValue: { check: vi.fn() } },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(AccessCheckPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No location in scope');
  });
});
