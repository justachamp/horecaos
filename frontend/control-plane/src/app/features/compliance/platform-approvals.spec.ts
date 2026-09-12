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
  subject: Readonly<Record<string, string>> = {},
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
        ...subject,
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
    decidePlatform = vi.fn().mockResolvedValue({
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
            listTenants: vi.fn().mockResolvedValue({
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
    const subject = line.querySelector('.subject') as HTMLElement;

    // Two refunds raised a minute apart used to read identically here, and the
    // approver signed whichever one they meant to decline. Asserted exactly,
    // and on the subject cell rather than the row: `toContain('50 000 000')`
    // matched the sign as readily as it matched the digits, so an `abs()` on
    // the amount would have rendered money leaving the wallet and money
    // entering it identically with the spec still green.
    expect(subject.textContent?.trim()).toBe(`Non uyi · −50 000 000 ${ru['money.uzsSuffix']}`);
    expect(line.querySelector('a')?.getAttribute('href')).toContain('tenant-1');
  });

  it('shows money entering the wallet without the sign that means money leaving', async () => {
    await create([platformRow('req-7', 'commercial.wallet.bonus-grant', '50000000')]);
    const subject = fixture.nativeElement.querySelector(
      '[data-request="req-7"] .subject',
    ) as HTMLElement;

    expect(subject.textContent?.trim()).toBe(`Non uyi · 50 000 000 ${ru['money.uzsSuffix']}`);
    expect(subject.textContent).not.toContain('−');
  });

  it('tells paid money apart from bonus money at the same amount under the same label', async () => {
    await create([
      platformRow('req-8', 'commercial.wallet.adjustment', '50000000', {
        entryType: 'ADJUSTMENT',
        moneyKind: 'PAID',
      }),
      platformRow('req-9', 'commercial.wallet.adjustment', '50000000', {
        entryType: 'ADJUSTMENT',
        moneyKind: 'BONUS',
        grantId: '018f6f4e-2100-7000-8000-0000000000a1',
      }),
    ]);
    const paid = fixture.nativeElement.querySelector('[data-request="req-8"]') as HTMLElement;
    const bonus = fixture.nativeElement.querySelector('[data-request="req-9"]') as HTMLElement;

    // Same action code, same label, same amount, same threshold caption: the
    // money kind and the grant are the only things that say whether this is
    // real, refundable money or promotional credit that lapses with a grant,
    // and the signature covers both.
    expect(paid.textContent).not.toBe(bonus.textContent);
    expect(
      (paid.querySelector('[data-subject-field="moneyKind"]') as HTMLElement).textContent,
    ).toContain(ru['wallet.kind.PAID']);
    expect(
      (bonus.querySelector('[data-subject-field="moneyKind"]') as HTMLElement).textContent,
    ).toContain(ru['wallet.kind.BONUS']);
    expect(
      (bonus.querySelector('[data-subject-field="grantId"]') as HTMLElement).textContent,
    ).toContain('018f6f4e-2100-7000-8000-0000000000a1');
    expect(
      (paid.querySelector('[data-subject-field="entryType"]') as HTMLElement).textContent,
    ).toContain(ru['wallet.entry.ADJUSTMENT']);
    expect(paid.querySelector('[data-subject-field="grantId"]')).toBeNull();
  });

  it('shows a bonus grant’s own expiry, which is half of what is being given away', async () => {
    await create([
      platformRow('req-10', 'commercial.wallet.bonus-grant', '50000000', {
        entryType: 'BONUS_GRANT',
        moneyKind: 'BONUS',
        expiresAt: '2027-03-01T00:00:00Z',
      }),
    ]);
    const line = fixture.nativeElement.querySelector('[data-request="req-10"]') as HTMLElement;
    const expiry = line.querySelector('[data-subject-field="expiresAt"]') as HTMLElement;

    // Two grants of identical size, one living a week and one three years, are
    // one row to a checker who cannot see this.
    expect(expiry.textContent).toContain(ru['platformApprovals.subject.field.expiresAt']);
    expect(expiry.textContent).toContain('01.03.2027');
    // And not the same thing as the request's own lapse column.
    expect(expiry.textContent).not.toContain('12.09');
  });

  it('shows a subject key nobody labelled rather than dropping what the signature covers', async () => {
    await create([
      platformRow('req-11', 'commercial.wallet.refund', '-500000', { somethingNew: 'X' }),
    ]);
    const field = fixture.nativeElement.querySelector(
      '[data-request="req-11"] [data-subject-field="somethingNew"]',
    ) as HTMLElement;

    expect(field.textContent?.trim()).toBe('somethingNew: X');
  });

  it('draws a row in a currency it has no scale for, and everything after it', async () => {
    await create([
      platformRow('req-12', 'commercial.wallet.refund', '-50000000', { currency: 'KZT' }),
      platformRow('req-13'),
    ]);
    const unscaled = fixture.nativeElement.querySelector('[data-request="req-12"] .subject');
    const after = fixture.nativeElement.querySelector('[data-request="req-13"] .subject');

    // Tenant currency is any three letters the platform accepts, and the
    // market list already declares KZT and GEL. Throwing on one truncated the
    // queue at that row: served oldest first, one such tenant hid every newer
    // platform decision and took the four-eyes control down with it.
    expect(unscaled.textContent).toContain(
      ru['money.unscaled'].replace('{amount}', '−50 000 000').replace('{currency}', 'KZT'),
    );
    expect(after).not.toBeNull();
    expect(after.textContent?.trim()).toBe(`Non uyi · −50 000 000 ${ru['money.uzsSuffix']}`);
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
