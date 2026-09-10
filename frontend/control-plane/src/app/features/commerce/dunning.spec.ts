import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { ArrearsBoardView, CommerceApi } from './commerce-api';
import { Dunning } from './dunning';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const BOARD: ArrearsBoardView = {
  stages: [
    { status: 'ACTIVE', planEntitlementsApply: true, additionsBlocked: false, allowedNext: [] },
    { status: 'PAST_DUE', planEntitlementsApply: true, additionsBlocked: false, allowedNext: [] },
    { status: 'SUSPENDED', planEntitlementsApply: false, additionsBlocked: true, allowedNext: [] },
    { status: 'TERMINATED', planEntitlementsApply: false, additionsBlocked: false, allowedNext: [] },
  ],
  subscriptions: [
    {
      tenantId: 'tenant-1', tenantName: 'Non uyi', subscriptionId: 'sub-1', status: 'PAST_DUE',
      since: '2026-08-20T09:00:00Z', daysInStatus: 22, planCode: 'BASIC', planVersionNumber: 1, version: 3,
      allowedNext: ['ACTIVE', 'SUSPENDED', 'TERMINATED'], suspensionReason: null,
      latestStatement: { statementId: 'st-1', number: 'S-2026-08-000001', periodKey: '2026-08', total: { amountMinor: 1_200_000, currency: 'UZS' }, issuedAt: '2026-09-01T05:00:00Z' },
    },
  ],
};

describe('Dunning', () => {
  let fixture: ComponentFixture<Dunning>;
  let api: { arrears: ReturnType<typeof vi.fn>; transitionSubscription: ReturnType<typeof vi.fn> };

  async function create(board: ArrearsBoardView = BOARD): Promise<void> {
    api = { arrears: vi.fn().mockResolvedValue(board), transitionSubscription: vi.fn().mockResolvedValue(undefined) };
    localStorage.clear();
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [Dunning],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: CommerceApi, useValue: api },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(Dunning);
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

  async function set(selector: string, value: string): Promise<void> {
    const input = el<HTMLInputElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event('input'));
    await settle();
  }

  it('says what each stage restricts, as the server says it', async () => {
    await create();

    expect(el('[data-stage="SUSPENDED"]').textContent).toContain(ru['dunning.stage.additionsBlocked']);
    expect(el('[data-stage="PAST_DUE"]').textContent).toContain(ru['dunning.stage.planApplies']);
    expect(el('[data-stage="PAST_DUE"]').textContent).not.toContain(ru['dunning.stage.additionsBlocked']);
  });

  it('shows how long a tenant has been late and its last statement', async () => {
    await create();
    const row = el('[data-tenant="tenant-1"]');

    expect(row.textContent).toContain('22');
    expect(row.textContent).toContain('S-2026-08-000001');
  });

  it('suspends only with a reason for the suspension, sending the version it read', async () => {
    await create();
    el<HTMLButtonElement>('[data-to="SUSPENDED"]').click();
    await settle();
    await set('[name="moveReason"]', 'three months late');
    expect(el<HTMLButtonElement>('.confirmMove').disabled).toBe(true);
    await set('[name="suspensionReason"]', 'unpaid since June');
    el<HTMLButtonElement>('.confirmMove').click();
    await settle();

    expect(api.transitionSubscription).toHaveBeenCalledWith('tenant-1', {
      status: 'SUSPENDED',
      expectedVersion: 3,
      suspensionReason: 'unpaid since June',
      reason: 'three months late',
    });
  });

  it('says so when nobody is late', async () => {
    await create({ ...BOARD, subscriptions: [] });

    expect(fixture.nativeElement.textContent).toContain(ru['dunning.empty']);
  });
});
