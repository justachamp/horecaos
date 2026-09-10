import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { Colleagues } from '../../shared/colleagues';
import { TenantsApi } from '../tenants/tenants-api';
import { CommerceApi, UsageDivergence, UsagePeriodView } from './commerce-api';
import { UsageMetering } from './usage-metering';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

const PERIOD: UsagePeriodView = {
  entitlementKey: 'orders.monthly.included',
  periodKey: '2026-09',
  periodStart: '2026-09-01T00:00:00Z',
  periodEnd: '2026-10-01T00:00:00Z',
  measuredQuantity: 120,
  adjustedQuantity: -3,
  consumedQuantity: 117,
  movementCount: 121,
  lastEventAt: null,
};

class FakeCommerceApi {
  readonly listUsage = vi.fn().mockResolvedValue([PERIOD]);
  readonly rebuildUsage = vi.fn<() => Promise<UsageDivergence[]>>();
  readonly adjustUsage = vi.fn().mockResolvedValue({ adjustmentId: 'adj-1' });
}

describe('UsageMetering', () => {
  let fixture: ComponentFixture<UsageMetering>;
  let api: FakeCommerceApi;

  async function create(): Promise<void> {
    api = new FakeCommerceApi();
    await TestBed.configureTestingModule({
      imports: [UsageMetering],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: CommerceApi, useValue: api },
        { provide: Colleagues, useValue: { others: vi.fn().mockResolvedValue(['approver-b']) } },
        { provide: TenantsApi, useValue: { listTenants: vi.fn().mockResolvedValue({ items: [], nextCursor: null }) } },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({ tenantId: 'tenant-1' }) } },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(UsageMetering);
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

  async function set(selector: string, value: string, event = 'input'): Promise<void> {
    const input = el<HTMLInputElement>(selector);
    input.value = value;
    input.dispatchEvent(new Event(event));
    await settle();
  }

  it('says so when every recomputed total matched the ledger', async () => {
    await create();
    api.rebuildUsage.mockResolvedValue([]);

    el<HTMLButtonElement>('.rebuild').click();
    await settle();

    expect(api.rebuildUsage).toHaveBeenCalledWith('tenant-1');
    expect(el('.rebuildResult').textContent).toContain(ru['usageMetering.rebuild.clean']);
  });

  it('names each period whose total disagreed, with what it was and what it is now', async () => {
    await create();
    api.rebuildUsage.mockResolvedValue([
      { entitlementKey: 'orders.monthly.included', periodKey: '2026-09', stored: 119, recomputed: 117 },
    ]);

    el<HTMLButtonElement>('.rebuild').click();
    await settle();

    const result = el('.rebuildResult').textContent ?? '';
    expect(result).toContain('orders.monthly.included · 2026-09: 119 → 117');
    expect(result).not.toContain(ru['usageMetering.rebuild.clean']);
  });

  it('records a signed correction with a second name, and refuses one that changes nothing', async () => {
    await create();

    el<HTMLButtonElement>('.table button.secondary').click();
    await settle();
    await set('select[name="approvedBy"]', 'approver-b', 'change');
    await set('input[name="adjustReason"]', 'duplicate orders from the 12 September outage');
    const submit = el<HTMLButtonElement>('.adjustForm button[type="submit"]');
    await set('input[name="delta"]', '0');
    expect(submit.disabled).toBe(true);
    await set('input[name="delta"]', '-2');
    submit.click();
    await settle();

    expect(api.adjustUsage).toHaveBeenCalledWith('tenant-1', {
      entitlementKey: 'orders.monthly.included',
      periodKey: '2026-09',
      quantityDelta: -2,
      sourceReference: undefined,
      approvedBy: 'approver-b',
      reason: 'duplicate orders from the 12 September outage',
    });
    expect(fixture.nativeElement.textContent).toContain(ru['usageMetering.adjust.done']);
  });
});
