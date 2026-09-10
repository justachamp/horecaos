import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { AccessApi } from '../access/access-api';
import { TenantsApi } from '../tenants/tenants-api';
import { PlatformApprovals } from './platform-approvals';
import { PlatformPendingApproval, ResidencyApi } from './residency-api';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };

function row(id: string, mayDecide: boolean): PlatformPendingApproval {
  return {
    tenantId: 'tenant-1',
    request: {
      id, actionCode: 'tenant.country.change', parametersHash: 'h', scopeType: 'TENANT', scopeId: 'tenant-1',
      thresholdDescription: 'Every change of the country a tenant trades in', policyVersion: 1,
      requiredApproverCapability: 'tenant.write', requestedBy: 'support-1', requestedAt: '2026-09-11T08:00:00Z',
      expiresAt: '2026-09-12T08:00:00Z', mayDecide,
    },
  };
}

describe('PlatformApprovals', () => {
  let fixture: ComponentFixture<PlatformApprovals>;
  let decide: ReturnType<typeof vi.fn>;

  async function create(rows: PlatformPendingApproval[]): Promise<void> {
    decide = vi.fn().mockResolvedValue({ id: 'req-1', actionCode: 'tenant.country.change', status: 'APPROVED' });
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [PlatformApprovals],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: ResidencyApi, useValue: { platformApprovals: vi.fn().mockResolvedValue(rows) } },
        { provide: AccessApi, useValue: { decide } },
        {
          provide: TenantsApi,
          useValue: { listTenants: vi.fn().mockResolvedValue({ items: [{ id: 'tenant-1', displayName: 'Non uyi', slug: 'non' }], nextCursor: null }) },
        },
        { provide: SessionContextService, useValue: { has: () => true, current: () => ({ subject: 'me' }) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PlatformApprovals);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  it('names the decision and the tenant, and approves with a reason', async () => {
    await create([row('req-1', true)]);
    const line = fixture.nativeElement.querySelector('[data-request="req-1"]') as HTMLElement;
    expect(line.textContent).toContain(ru['platformApprovals.action.tenant_country_change']);
    expect(line.textContent).toContain('Non uyi');

    (line.querySelector('.approveToggle') as HTMLButtonElement).click();
    await settle();
    const reason = fixture.nativeElement.querySelector('[name="decisionReason"]') as HTMLInputElement;
    reason.value = 'contract checked';
    reason.dispatchEvent(new Event('input'));
    await settle();
    (fixture.nativeElement.querySelector('.confirmDecision') as HTMLButtonElement).click();
    await settle();

    expect(decide).toHaveBeenCalledWith('tenant-1', 'req-1', 'APPROVE', 'contract checked');
  });

  it('offers no decision on a request the reader raised', async () => {
    await create([row('req-2', false)]);

    expect(fixture.nativeElement.querySelector('.approveToggle')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain(ru['platformApprovals.notYours']);
  });

  it('says so when nothing waits', async () => {
    await create([]);

    expect(fixture.nativeElement.textContent).toContain(ru['platformApprovals.empty']);
  });
});
