import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { I18n } from '../../core/i18n/i18n';
import { GrantView, RoleDescriptor, StaffApi } from './staff-api';
import { StaffRolesPage } from './staff-roles-page';

const ROLES: readonly RoleDescriptor[] = [
  { code: 'tenant-owner', scopeType: 'TENANT', capabilities: ['tenant.write', 'iam.grant.manage'] },
  { code: 'location-staff', scopeType: 'LOCATION', capabilities: ['order.read', 'order.approve'] },
];

function grant(overrides: Partial<GrantView>): GrantView {
  return {
    id: 'g1',
    principalSubject: 'staff-1',
    roleCode: 'location-staff',
    scopeType: 'LOCATION',
    scopeId: 'l1',
    status: 'ACTIVE',
    grantedBy: 'owner-1',
    reason: 'Onboarded',
    validFrom: '2026-08-01T00:00:00Z',
    validUntil: null,
    revokedAt: null,
    revokedBy: null,
    revokedReason: null,
    ...overrides,
  };
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('t1');
  readonly scopes = signal<readonly unknown[]>([]);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

async function setUp(grants: readonly GrantView[]) {
  const api = {
    roles: vi.fn().mockResolvedValue(ROLES),
    listGrants: vi.fn().mockResolvedValue(grants),
    scopeDirectory: vi.fn().mockResolvedValue({
      brands: [{ id: 'b1', displayName: 'Milliy' }],
      locations: [{ id: 'l1', brandId: 'b1', displayName: 'Chilonzor' }],
    }),
  };
  await TestBed.configureTestingModule({
    imports: [StaffRolesPage],
    providers: [
      provideRouter([]),
      { provide: StaffApi, useValue: api },
      { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('ru');
  const fixture: ComponentFixture<StaffRolesPage> = TestBed.createComponent(StaffRolesPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

describe('StaffRolesPage', () => {
  it('lists both tenant-visible jobs from the fixture, sorted company-level first', async () => {
    const fixture = await setUp([]);
    const rows = [...fixture.nativeElement.querySelectorAll('.row .q-emphasis')].map(
      (e: Element) => e.textContent,
    );
    expect(rows).toEqual(['Владелец', 'Сотрудник филиала']);
  });

  it('counts active holders per job', async () => {
    const fixture = await setUp([
      grant({ id: 'g1' }),
      grant({ id: 'g2', status: 'REVOKED', revokedAt: 'x', revokedBy: 'y', revokedReason: 'z' }),
    ]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('1');
  });

  it('expands to show "Что можно делать" and "Чего нельзя" as plain sentences, never a dotted code', async () => {
    const fixture = await setUp([]);
    (fixture.nativeElement.querySelectorAll('.row')[1] as HTMLElement).click(); // location-staff
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('order.read');
    expect(text).not.toContain('tenant.write');
    expect(text).toContain('Видеть заказы');
    expect(text).toContain('Изменять данные компании');
  });

  it('lists a holder resolved to their scope, and navigating to them goes through /staff/:subject', async () => {
    const fixture = await setUp([grant({ principalSubject: 'staff-1' })]);
    (fixture.nativeElement.querySelectorAll('.row')[1] as HTMLElement).click();
    fixture.detectChanges();

    const holderRow = fixture.nativeElement.querySelector('.holders__row');
    expect(holderRow.textContent).toContain('staff-1');
    expect(holderRow.textContent).toContain('Chilonzor');
  });
});
