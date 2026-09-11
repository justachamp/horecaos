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

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

function row(id: string, mayDecide: boolean): PlatformPendingApproval {
  return {
    tenantId: 'tenant-1',
    request: {
      id,
      actionCode: 'tenant.country.change',
      parametersHash: 'h',
      scopeType: 'TENANT',
      scopeId: 'tenant-1',
      thresholdDescription: 'Every change of the country a tenant trades in',
      policyVersion: 1,
      requiredApproverCapability: 'tenant.write',
      requestedBy: 'support-1',
      requestedAt: '2026-09-11T08:00:00Z',
      expiresAt: '2026-09-12T08:00:00Z',
      mayDecide,
      subjectTenantId: null,
      subjectTenantName: null,
      subject: {},
    },
  };
}

/**
 * A wallet decision: HorecaOS's own, raised above every tenant's queue and
 * carrying no tenant of its own, so the subject is what says whose money moves.
 */
function platformRow(
  id: string,
  actionCode = 'commercial.wallet.refund',
  amountMinor = '-50000000',
): PlatformPendingApproval {
  return {
    tenantId: null,
    request: {
      id,
      actionCode,
      parametersHash: 'h',
      scopeType: 'PLATFORM',
      scopeId: null,
      thresholdDescription: "Every refund of a tenant's paid money",
      policyVersion: 1,
      requiredApproverCapability: 'commercial.wallet.manage',
      requestedBy: 'finance-1',
      requestedAt: '2026-09-11T08:00:00Z',
      expiresAt: '2026-09-12T08:00:00Z',
      mayDecide: true,
      subjectTenantId: 'tenant-1',
      subjectTenantName: 'Non uyi',
      subject: {
        tenantId: 'tenant-1',
        amountMinor,
        currency: 'UZS',
        entryType: 'REFUND',
        moneyKind: 'PAID',
      },
    },
  };
}

describe('PlatformApprovals', () => {
  let fixture: ComponentFixture<PlatformApprovals>;
  let decide: ReturnType<typeof vi.fn>;
  let decidePlatform: ReturnType<typeof vi.fn>;

  async function create(rows: PlatformPendingApproval[]): Promise<void> {
    decide = vi
      .fn()
      .mockResolvedValue({ id: 'req-1', actionCode: 'tenant.country.change', status: 'APPROVED' });
    decidePlatform = vi
      .fn()
      .mockResolvedValue({
        id: 'req-3',
        actionCode: 'commercial.wallet.refund',
        status: 'APPROVED',
      });
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [PlatformApprovals],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        {
          provide: ResidencyApi,
          useValue: { platformApprovals: vi.fn().mockResolvedValue(rows), decidePlatform },
        },
        { provide: AccessApi, useValue: { decide } },
        {
          provide: TenantsApi,
          useValue: {
            listTenants: vi
              .fn()
              .mockResolvedValue({
                items: [{ id: 'tenant-1', displayName: 'Non uyi', slug: 'non' }],
                nextCursor: null,
              }),
          },
        },
        {
          provide: SessionContextService,
          useValue: { has: () => true, current: () => ({ subject: 'me' }) },
        },
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
    const reason = fixture.nativeElement.querySelector(
      '[name="decisionReason"]',
    ) as HTMLInputElement;
    reason.value = 'contract checked';
    reason.dispatchEvent(new Event('input'));
    await settle();
    (fixture.nativeElement.querySelector('.confirmDecision') as HTMLButtonElement).click();
    await settle();

    expect(decide).toHaveBeenCalledWith('tenant-1', 'req-1', 'APPROVE', 'contract checked');
  });

  it('decides a request that belongs to no tenant through the platform route', async () => {
    await create([platformRow('req-3')]);
    const line = fixture.nativeElement.querySelector('[data-request="req-3"]') as HTMLElement;
    expect(line.textContent).toContain(ru['platformApprovals.platformScoped']);

    (line.querySelector('.approveToggle') as HTMLButtonElement).click();
    await settle();
    const reason = fixture.nativeElement.querySelector(
      '[name="decisionReason"]',
    ) as HTMLInputElement;
    reason.value = 'the bank confirmed the payout';
    reason.dispatchEvent(new Event('input'));
    await settle();
    (fixture.nativeElement.querySelector('.confirmDecision') as HTMLButtonElement).click();
    await settle();

    expect(decidePlatform).toHaveBeenCalledWith(
      'req-3',
      'APPROVE',
      'the bank confirmed the payout',
    );
    expect(decide).not.toHaveBeenCalled();
  });

  it('names the tenant and the amount on a row that carries no tenant of its own', async () => {
    await create([platformRow('req-4')]);
    const line = fixture.nativeElement.querySelector('[data-request="req-4"]') as HTMLElement;

    // Two refunds raised a minute apart used to read identically here, and the
    // approver signed whichever one they meant to decline.
    expect(line.textContent).toContain('Non uyi');
    expect(line.textContent).toContain('50 000 000');
    expect(line.querySelector('a')?.getAttribute('href')).toContain('tenant-1');
  });

  it('labels every wallet action, including the one no catalogue had', async () => {
    await create([platformRow('req-5', 'commercial.wallet.deposit-reversal', '-500000')]);
    const line = fixture.nativeElement.querySelector('[data-request="req-5"]') as HTMLElement;
    const label = line.querySelector('.actionLabel') as HTMLElement;

    // The key is built from the code and cast, so a missing label is invisible
    // to the compiler and renders the empty string.
    expect(label.textContent?.trim()).toBe(
      ru['platformApprovals.action.commercial_wallet_deposit-reversal'],
    );
    expect(label.textContent?.trim().length).toBeGreaterThan(0);
  });

  it('shows the raw code rather than an empty cell for an action nobody labelled', async () => {
    await create([platformRow('req-6', 'commercial.wallet.something-new')]);
    const label = fixture.nativeElement.querySelector(
      '[data-request="req-6"] .actionLabel',
    ) as HTMLElement;

    expect(label.textContent?.trim()).toBe('commercial.wallet.something-new');
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
