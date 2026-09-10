import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { SupportSessionView, SupportSessionsApi } from './support-sessions-api';
import { TenantImpersonation } from './tenant-impersonation';
import { TenantsApi } from './tenants-api';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
  operationsAppUrl: 'https://operations.test.horecaos.uz',
};

const session = (overrides: Partial<SupportSessionView>): SupportSessionView => ({
  id: 's-1',
  tenantId: 'tenant-1',
  principalSubject: 'me',
  access: 'VIEW',
  reason: 'Owner reports missing orders',
  ticketReference: 'T-4812',
  startedAt: '2026-09-11T02:00:00Z',
  expiresAt: '2026-09-11T03:00:00Z',
  endedAt: null,
  endedBy: null,
  endReason: null,
  open: true,
  ...overrides,
});

describe('TenantImpersonation', () => {
  let fixture: ComponentFixture<TenantImpersonation>;
  let api: { open: ReturnType<typeof vi.fn>; list: ReturnType<typeof vi.fn>; end: ReturnType<typeof vi.fn> };

  async function create(existing: SupportSessionView[]): Promise<void> {
    api = {
      open: vi.fn().mockResolvedValue(session({})),
      list: vi.fn().mockResolvedValue({ items: existing, nextCursor: null }),
      end: vi.fn().mockResolvedValue(session({ open: false })),
    };
    await TestBed.configureTestingModule({
      imports: [TenantImpersonation],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: SupportSessionsApi, useValue: api },
        { provide: TenantsApi, useValue: { listTenants: vi.fn().mockResolvedValue({ items: [], nextCursor: null }) } },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ tenantId: 'tenant-1' }) } } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(TenantImpersonation);
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

  it('opens a session only with a reason, at the chosen access and length', async () => {
    await create([]);

    const submit = el<HTMLButtonElement>('.openForm button[type="submit"]');
    expect(submit.disabled).toBe(true);
    (el<HTMLInputElement>('input[name="access"][value="ASSIST"]')).click();
    const minutes = el<HTMLSelectElement>('select[name="minutes"]');
    minutes.value = '30';
    minutes.dispatchEvent(new Event('change'));
    const reason = el<HTMLInputElement>('input[name="reason"]');
    reason.value = 'Stuck order at Chilonzor';
    reason.dispatchEvent(new Event('input'));
    await settle();
    submit.click();
    await settle();

    expect(api.open).toHaveBeenCalledWith('tenant-1', {
      access: 'ASSIST',
      reason: 'Stuck order at Chilonzor',
      ticketReference: undefined,
      minutes: 30,
    });
  });

  it('shows the open session with a link into operations for this tenant, and ends it with a reason', async () => {
    await create([session({})]);

    expect(el('.openForm')).toBeNull();
    expect(el<HTMLAnchorElement>('.openOps').href).toBe(
      'https://operations.test.horecaos.uz/?supportTenant=tenant-1',
    );
    expect(el<HTMLButtonElement>('.endMine').disabled).toBe(true);
    const reason = el<HTMLInputElement>('input[name="endReason"]');
    reason.value = 'Fixed';
    reason.dispatchEvent(new Event('input'));
    await settle();
    el<HTMLButtonElement>('.endMine').click();
    await settle();

    expect(api.end).toHaveBeenCalledWith('tenant-1', 's-1', 'Fixed');
  });

  it('keeps the tenant’s record of every visit, saying how each one ended', async () => {
    await create([
      session({ id: 's-2', principalSubject: 'colleague', open: false, endedAt: '2026-09-10T10:30:00Z', endedBy: 'owner-1', endReason: 'We fixed it ourselves' }),
      session({ id: 's-3', principalSubject: 'colleague', open: false }),
    ]);

    const rows = Array.from(fixture.nativeElement.querySelectorAll('.history tbody tr')) as HTMLElement[];
    expect(rows[0].textContent).toContain('owner-1');
    expect(rows[0].textContent).toContain('We fixed it ourselves');
    expect(rows[1].textContent).toContain(ru['support.state.lapsed'].split(' ')[0]);
  });
});
