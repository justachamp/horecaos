import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { CurrentTenant } from '../../core/auth/current-tenant';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
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

interface ApiOverrides {
  readonly roles?: () => Promise<readonly RoleDescriptor[]>;
  /** The capabilities the reader holds; none unless a spec says. */
  readonly held?: readonly string[];
}

async function setUp(grants: readonly GrantView[], overrides: ApiOverrides = {}) {
  const held = new Set(overrides.held ?? []);
  const api = {
    roles: vi.fn(overrides.roles ?? (() => Promise.resolve(ROLES))),
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
      {
        provide: SessionCapabilities,
        useValue: { has: (capability: string) => held.has(capability) },
      },
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

  it('opens the holder detail when the holder count itself is clicked, instead of only stopping the click', async () => {
    // The dead control the gap map flagged (staff-roles-page.html:35-37):
    // this used to swallow the click and leave the row collapsed.
    const fixture = await setUp([]);
    const holderCount = fixture.nativeElement.querySelectorAll('.text-button')[1] as HTMLElement;

    holderCount.click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.detail-row')).not.toBeNull();

    // And it closes the same detail on a second click, exactly like the row.
    holderCount.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.detail-row')).toBeNull();
  });

  it('narrows the list to jobs matching a capability search, over the plain sentence', async () => {
    const fixture = await setUp([]);
    const input = fixture.nativeElement.querySelector(
      '[data-testid="staff-roles-search"]',
    ) as HTMLInputElement;

    // "Заказы" (RU for "Orders") matches location-staff's `order.read`/`order.approve`
    // sentences and not tenant-owner's `tenant.write`.
    input.value = 'заказ';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const names = [...fixture.nativeElement.querySelectorAll('.row .q-emphasis')].map(
      (e: Element) => e.textContent,
    );
    expect(names).toEqual(['Сотрудник филиала']);
  });

  it('also matches a pasted-in raw capability code, not only its sentence', async () => {
    const fixture = await setUp([]);
    const input = fixture.nativeElement.querySelector(
      '[data-testid="staff-roles-search"]',
    ) as HTMLInputElement;

    input.value = 'iam.grant.manage';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const names = [...fixture.nativeElement.querySelectorAll('.row .q-emphasis')].map(
      (e: Element) => e.textContent,
    );
    expect(names).toEqual(['Владелец']);
  });

  it('shows an honest empty state, naming the query, when nothing matches', async () => {
    const fixture = await setUp([]);
    const input = fixture.nativeElement.querySelector(
      '[data-testid="staff-roles-search"]',
    ) as HTMLInputElement;

    input.value = 'nonexistent-capability';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.table')).toBeNull();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('nonexistent-capability');
  });

  it('renders q-denied-state, naming the capability, on a live INSUFFICIENT_CAPABILITY refusal', async () => {
    const fixture = await setUp([], {
      roles: () =>
        Promise.reject(
          new ApiError(
            ApiErrorCode.INSUFFICIENT_CAPABILITY,
            403,
            { status: 403, requiredCapability: 'IAM_GRANT_MANAGE', requiredScope: 'TENANT' },
            'corr-1',
          ),
        ),
    });

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="q-denied-state"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="q-denied-state-capability"]')?.textContent).toContain(
      'IAM_GRANT_MANAGE',
    );
    expect(el.querySelector('.table')).toBeNull();
  });

  it('renders q-locked-state, naming the module, on ENTITLEMENT_REQUIRED', async () => {
    // Nothing in `iam`/Staff is plan-gated today, so this screen never hits
    // this branch in production — the assertion is that the template's own
    // `refusal().kind === 'locked'` wiring is correct wherever `accessRefusal`
    // classifies a refusal that way, not that Staff itself is entitlement-gated.
    const fixture = await setUp([], {
      roles: () =>
        Promise.reject(
          new ApiError(
            ApiErrorCode.ENTITLEMENT_REQUIRED,
            403,
            { status: 403, entitlementKey: 'staff.directory.enabled' },
            'corr-2',
          ),
        ),
    });

    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="q-locked-state"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="q-locked-state-module"]')?.textContent).toContain(
      'staff.directory.enabled',
    );
    expect(el.querySelector('[data-testid="q-denied-state"]')).toBeNull();
  });
});

describe('StaffRolesPage: the company’s own jobs (ADR 0186, row 9.1b)', () => {
  const OWN: RoleDescriptor = {
    code: 'job-3f9a1c0b2d',
    scopeType: 'LOCATION',
    capabilities: ['order.read', 'order.cancel'],
    custom: true,
    name: 'Старший кассир',
    version: 3,
    status: 'ACTIVE',
  };

  const withOwn = () => Promise.resolve([...ROLES, OWN]);

  function text(fixture: ComponentFixture<StaffRolesPage>): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('names the job by what the company called it, marks it «Своя», and never shows its code', async () => {
    const fixture = await setUp([], { roles: withOwn });

    const names = [...fixture.nativeElement.querySelectorAll('.row .q-emphasis')].map(
      (e: Element) => e.textContent,
    );
    expect(names).toContain('Старший кассир');
    expect(fixture.nativeElement.querySelectorAll('[data-testid="role-own"]')).toHaveLength(1);
    expect(text(fixture)).not.toContain('job-3f9a1c0b2d');
    expect(fixture.nativeElement.querySelector('[data-testid="roles-own-note"]')).not.toBeNull();
  });

  it('says nothing about own jobs while the company has none', async () => {
    const fixture = await setUp([], { held: ['IAM_ROLE_MANAGE'] });

    expect(fixture.nativeElement.querySelector('[data-testid="role-own"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="roles-own-note"]')).toBeNull();
  });

  it('offers define, edit and copy to a person who holds iam.role.manage, edit only on the company’s own', async () => {
    const fixture = await setUp([], { roles: withOwn, held: ['IAM_ROLE_MANAGE'] });
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);

    expect(fixture.nativeElement.querySelector('[data-testid="roles-create"]')).not.toBeNull();
    (fixture.nativeElement.querySelector('[data-testid="roles-create"]') as HTMLElement).click();
    expect(navigate).toHaveBeenLastCalledWith(['/staff/roles/grid'], { queryParams: { new: 1 } });

    const rows = fixture.nativeElement.querySelectorAll('.row') as NodeListOf<HTMLElement>;
    rows[0].click(); // tenant-owner: code-owned
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="role-edit"]')).toBeNull();
    (fixture.nativeElement.querySelector('[data-testid="role-copy"]') as HTMLElement).click();
    expect(navigate).toHaveBeenLastCalledWith(['/staff/roles/grid'], {
      queryParams: { copy: 'tenant-owner' },
    });

    const own = [...rows].find((row) => row.textContent?.includes('Старший кассир'))!;
    own.click();
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('[data-testid="role-edit"]') as HTMLElement).click();
    expect(navigate).toHaveBeenLastCalledWith(['/staff/roles/grid'], {
      queryParams: { edit: 'job-3f9a1c0b2d' },
    });
  });

  it('offers a reader without iam.role.manage the table and no way to define, edit or copy', async () => {
    const fixture = await setUp([], { roles: withOwn });

    expect(fixture.nativeElement.querySelector('[data-testid="roles-grid-link"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="roles-create"]')).toBeNull();
    (fixture.nativeElement.querySelectorAll('.row')[0] as HTMLElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="role-edit"]')).toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="role-copy"]')).toBeNull();
  });
});
