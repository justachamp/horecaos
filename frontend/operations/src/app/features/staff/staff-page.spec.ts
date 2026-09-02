import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Auth } from '../../core/auth/auth';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { I18n } from '../../core/i18n/i18n';
import { GrantView } from './staff-api';
import { StaffApi } from './staff-api';
import { StaffPage } from './staff-page';

function grant(overrides: Partial<GrantView>): GrantView {
  return {
    id: 'g1',
    principalSubject: 'subject-1',
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
  /** A tenant-wide grant-manage holder, so every job in the fixture's `roles()` is offerable in the job dialog. */
  readonly scopes = signal<readonly unknown[]>([
    {
      scope: { type: 'TENANT', tenantId: 't1', brandId: null, locationId: null },
      roleCode: 'tenant-owner',
      capabilities: ['order.read', 'iam.grant.manage'],
    },
  ]);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function makeApi(grants: readonly GrantView[]) {
  return {
    listGrants: vi.fn().mockResolvedValue(grants),
    roles: vi
      .fn()
      .mockResolvedValue([
        { code: 'location-staff', scopeType: 'LOCATION', capabilities: ['order.read'] },
      ]),
    scopeDirectory: vi.fn().mockResolvedValue({
      brands: [{ id: 'b1', displayName: 'Milliy' }],
      locations: [{ id: 'l1', brandId: 'b1', displayName: 'Chilonzor' }],
    }),
    telegramLinks: vi.fn().mockResolvedValue([]),
    grant: vi.fn().mockResolvedValue({ grantId: 'new-grant' }),
    revoke: vi.fn().mockResolvedValue({ changed: true, outcome: 'revoked' }),
  };
}

async function setUp(grants: readonly GrantView[], subject = 'the-operator') {
  const api = makeApi(grants);
  await TestBed.configureTestingModule({
    imports: [StaffPage],
    providers: [
      provideRouter([]),
      { provide: StaffApi, useValue: api },
      { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      { provide: Auth, useValue: { subject: signal(subject) } },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('ru');
  const fixture: ComponentFixture<StaffPage> = TestBed.createComponent(StaffPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return { fixture, api };
}

describe('StaffPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('shows the §0 rule sentence as the empty state when nobody has a job yet', async () => {
    const { fixture } = await setUp([]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('одна или несколько должностей');
  });

  it('lists a person derived from their grant, with the role and scope shown', async () => {
    const { fixture } = await setUp([
      grant({ principalSubject: 'staff-1', roleCode: 'location-staff' }),
    ]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('staff-1');
    expect(text).toContain('Chilonzor');
  });

  it('captions a fully-revoked person with the revocation reason, not a bare badge', async () => {
    const { fixture } = await setUp([
      grant({
        principalSubject: 'staff-2',
        status: 'REVOKED',
        revokedAt: '2026-08-30T00:00:00Z',
        revokedBy: 'owner-1',
        revokedReason: 'Left the company',
      }),
    ]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Left the company');
  });

  it('computes status pill counts before filtering, so they do not move when a filter narrows the table', async () => {
    const { fixture } = await setUp([
      grant({ principalSubject: 'staff-1', status: 'ACTIVE' }),
      grant({
        principalSubject: 'staff-2',
        status: 'REVOKED',
        revokedAt: '2026-08-30T00:00:00Z',
        revokedBy: 'owner-1',
        revokedReason: 'Left',
      }),
    ]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('2'); // "Все (2)"
  });

  it('hides destructive actions on the signed-in operator’s own row', async () => {
    const { fixture } = await setUp(
      [grant({ principalSubject: 'the-operator', status: 'ACTIVE' })],
      'the-operator',
    );
    expect(fixture.nativeElement.querySelector('[data-testid="staff-row-suspend"]')).toBeNull();
  });

  it('suspending fans out one revoke call per active grant and reloads', async () => {
    const { fixture, api } = await setUp([
      grant({ id: 'g1', principalSubject: 'staff-1', roleCode: 'location-staff', scopeId: 'l1' }),
      grant({
        id: 'g2',
        principalSubject: 'staff-1',
        roleCode: 'location-staff',
        scopeId: 'l1',
        scopeType: 'BRAND',
      }),
    ]);

    (
      fixture.nativeElement.querySelector('[data-testid="staff-row-suspend"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const reasonInput = fixture.nativeElement.querySelector(
      '[data-testid="staff-access-dialog-reason"]',
    ) as HTMLInputElement;
    reasonInput.value = 'Left the company';
    reasonInput.dispatchEvent(new Event('input'));
    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-access-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.revoke).toHaveBeenCalledTimes(2);
    expect(api.revoke).toHaveBeenCalledWith('t1', 'g1', 'Left the company');
    expect(api.revoke).toHaveBeenCalledWith('t1', 'g2', 'Left the company');
    expect(api.listGrants).toHaveBeenCalledTimes(2); // initial load + reload after suspend
  });

  it('opens the job dialog and grants on submit', async () => {
    const { fixture, api } = await setUp([grant({ principalSubject: 'staff-1' })]);

    (
      fixture.nativeElement.querySelector('[data-testid="staff-row-add-job"]') as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="staff-job-dialog"]')).not.toBeNull();

    const roleSelect = fixture.nativeElement.querySelector(
      '[data-testid="staff-job-dialog-role"]',
    ) as HTMLSelectElement;
    roleSelect.value = 'location-staff';
    roleSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    const reason = fixture.nativeElement.querySelector(
      '[data-testid="staff-job-dialog-reason"]',
    ) as HTMLInputElement;
    reason.value = 'Second branch';
    reason.dispatchEvent(new Event('input'));
    // Otherwise the confirm button's `[disabled]` binding is still the
    // pre-reason render, and a genuinely `disabled` DOM button ignores `.click()`.
    fixture.detectChanges();
    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-job-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.grant).toHaveBeenCalledWith(
      't1',
      expect.objectContaining({
        principalSubject: 'staff-1',
        roleCode: 'location-staff',
        reason: 'Second branch',
      }),
    );
  });
});
