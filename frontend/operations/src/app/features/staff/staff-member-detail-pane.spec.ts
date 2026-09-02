import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Auth } from '../../core/auth/auth';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { I18n } from '../../core/i18n/i18n';
import { GrantView, StaffApi } from './staff-api';
import { StaffMemberDetailPane } from './staff-member-detail-pane';

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

function makeApi(grants: readonly GrantView[]) {
  return {
    listGrants: vi.fn().mockResolvedValue(grants),
    roles: vi.fn().mockResolvedValue([
      {
        code: 'location-staff',
        scopeType: 'LOCATION',
        capabilities: ['order.read', 'order.cancel'],
      },
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

async function setUp(grants: readonly GrantView[], subjectId = 'staff-1') {
  const api = makeApi(grants);
  await TestBed.configureTestingModule({
    imports: [StaffMemberDetailPane],
    providers: [
      provideRouter([]),
      { provide: StaffApi, useValue: api },
      { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
      { provide: Auth, useValue: { subject: signal('someone-else') } },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('ru');
  const fixture: ComponentFixture<StaffMemberDetailPane> =
    TestBed.createComponent(StaffMemberDetailPane);
  fixture.componentRef.setInput('subjectId', subjectId);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return { fixture, api };
}

describe('StaffMemberDetailPane', () => {
  it('shows the honest not-found state for a subject with no grant at all', async () => {
    const { fixture } = await setUp([grant({ principalSubject: 'somebody-else' })], 'staff-1');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Такого сотрудника нет');
  });

  it('renders the assignment card with the role, scope, and reason', async () => {
    const { fixture } = await setUp([grant({})]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Chilonzor');
    expect(text).toContain('Onboarded');
  });

  it('expands "Что можно делать" into plain sentences grouped by area, never a dotted code', async () => {
    const { fixture } = await setUp([grant({})]);
    (fixture.nativeElement.querySelector('.card button.text-button') as HTMLButtonElement).click();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('order.read');
    expect(text).not.toContain('order.cancel');
    expect(text).toContain('Заказы');
  });

  it('offers "Убрать" only when it is not the operator locking themselves out of their own last assignment', async () => {
    const { fixture } = await setUp([grant({})], 'staff-1'); // signed-in subject is 'someone-else'
    expect(
      fixture.nativeElement.querySelector('[data-testid="staff-detail-remove"]'),
    ).not.toBeNull();
  });

  it('removes a grant via the access dialog and reloads', async () => {
    const { fixture, api } = await setUp([grant({ id: 'g1' })]);

    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-detail-remove"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    const reason = fixture.nativeElement.querySelector(
      '[data-testid="staff-access-dialog-reason"]',
    ) as HTMLInputElement;
    reason.value = 'Moved to another branch';
    reason.dispatchEvent(new Event('input'));
    (
      fixture.nativeElement.querySelector(
        '[data-testid="staff-access-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api.revoke).toHaveBeenCalledWith('t1', 'g1', 'Moved to another branch');
    expect(api.listGrants).toHaveBeenCalledTimes(2);
  });

  it('shows the Telegram link state on the Безопасность tab, real data even without a name', async () => {
    const api = makeApi([grant({})]);
    api.telegramLinks.mockResolvedValue([
      { principalSubject: 'staff-1', telegramUserId: 555, linkedAt: '2026-09-01T00:00:00Z' },
    ]);
    await TestBed.configureTestingModule({
      imports: [StaffMemberDetailPane],
      providers: [
        provideRouter([]),
        { provide: StaffApi, useValue: api },
        { provide: CurrentTenant, useValue: new FakeCurrentTenant() },
        { provide: Auth, useValue: { subject: signal('someone-else') } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('ru');
    const fixture = TestBed.createComponent(StaffMemberDetailPane);
    fixture.componentRef.setInput('subjectId', 'staff-1');
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    const tabs = [...fixture.nativeElement.querySelectorAll('.tab')] as HTMLButtonElement[];
    tabs[1].click(); // «Безопасность»
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Telegram привязан');
  });
});
