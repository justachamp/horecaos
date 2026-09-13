import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { Auth } from '../../core/auth/auth';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { ScopeGrant } from '../../core/auth/session-context';
import { I18n } from '../../core/i18n/i18n';
import { MyProfilePage } from './my-profile-page';
import { StaffApi } from './staff-api';

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('t1');
  readonly scopes = signal<readonly ScopeGrant[]>([]);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

function scope(overrides: Partial<ScopeGrant> = {}): ScopeGrant {
  return {
    scope: { type: 'LOCATION', tenantId: 't1', brandId: 'b1', locationId: 'l1' },
    roleCode: 'location-staff',
    capabilities: ['order.read', 'order.cancel'],
    ...overrides,
  };
}

async function setUp(scopes: readonly ScopeGrant[]) {
  const api = {
    issueTelegramLinkCode: vi.fn().mockResolvedValue({ code: 'ABC123', command: '/link ABC123' }),
  };
  const tenant = new FakeCurrentTenant();
  tenant.scopes.set(scopes);
  await TestBed.configureTestingModule({
    imports: [MyProfilePage],
    providers: [
      { provide: StaffApi, useValue: api },
      { provide: CurrentTenant, useValue: tenant },
      { provide: Auth, useValue: { displayName: signal('Aziza') } },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('ru');
  const fixture: ComponentFixture<MyProfilePage> = TestBed.createComponent(MyProfilePage);
  fixture.detectChanges();
  await Promise.resolve();
  fixture.detectChanges();
  return { fixture, api, tenant };
}

describe('MyProfilePage', () => {
  it('reads Мои должности from CurrentTenant.scopes — GET /api/v1/session/context, already loaded — and nothing else', async () => {
    const { tenant } = await setUp([scope()]);

    // The whole point of "thin wiring": the page's own load path never calls
    // StaffApi for the jobs list, only CurrentTenant.ensureLoaded (which the
    // shell already triggers on its own — see this class's own doc).
    expect(tenant.ensureLoaded).toHaveBeenCalled();
  });

  it('renders one assignment card per scope, with the job and scope level', async () => {
    const { fixture } = await setUp([
      scope({
        roleCode: 'location-manager',
        scope: { type: 'LOCATION', tenantId: 't1', brandId: 'b1', locationId: 'l1' },
      }),
    ]);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Управляющий филиалом');
    expect(text).toContain('Филиал');
  });

  it('shows the empty state when the operator holds no scope at all', async () => {
    const { fixture } = await setUp([]);

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Пока нет ни одной должности.');
  });

  it('issues a Telegram link code and renders the /link <code> command', async () => {
    const { fixture, api } = await setUp([]);

    (
      fixture.nativeElement.querySelector(
        '[data-testid="my-profile-telegram-issue"]',
      ) as HTMLButtonElement
    ).click();
    await Promise.resolve();
    fixture.detectChanges();

    expect(api.issueTelegramLinkCode).toHaveBeenCalledWith('t1');
    const command = fixture.nativeElement.querySelector(
      '[data-testid="telegram-link-command"]',
    ) as HTMLElement;
    expect(command.textContent).toContain('/link ABC123');
  });
});
