import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { TenantsApi } from '../tenants/tenants-api';
import { CommerceApi, StatementView } from './commerce-api';
import { InvoicesWallet, previousMonth } from './invoices-wallet';

const CONFIG: AppConfig = { apiBaseUrl: 'https://api.test.horecaos.uz', displayTimeZone: 'Asia/Tashkent' };
const UZS = (amountMinor: number) => ({ amountMinor, currency: 'UZS' });

const DRAFT: StatementView = {
  statementId: null, number: null, periodKey: '2026-08', periodStart: '2026-07-31T19:00:00Z',
  periodEnd: '2026-08-31T19:00:00Z', status: 'DRAFT', total: UZS(1_500_000), issuedBy: null, issuedAt: null,
  issueReason: null, voidedBy: null, voidedAt: null, voidReason: null,
  lines: [
    { lineNumber: 1, kind: 'PLAN', referenceCode: 'BASIC@v1', description: 'BASIC v1, MONTHLY', quantity: 1, unitPrice: UZS(1_200_000), amount: UZS(1_200_000) },
    { lineNumber: 2, kind: 'MODULE', referenceCode: 'kds', description: 'KDS, PER_LOCATION', quantity: 3, unitPrice: UZS(100_000), amount: UZS(300_000) },
  ],
};

const ISSUED: StatementView = {
  ...DRAFT, statementId: 'st-1', number: 'S-2026-07-000001', periodKey: '2026-07', status: 'ISSUED',
  issuedBy: 'finance', issuedAt: '2026-08-01T05:00:00Z', issueReason: 'July close',
};

class FakeCommerceApi {
  readonly listStatements = vi.fn().mockResolvedValue([ISSUED]);
  readonly draftStatement = vi.fn().mockResolvedValue(DRAFT);
  readonly statement = vi.fn().mockResolvedValue(ISSUED);
  readonly exportStatement = vi.fn().mockResolvedValue('number\r\n');
  readonly issueStatement = vi.fn().mockResolvedValue({ statementId: 'st-2', number: 'S-2026-08-000002' });
  readonly voidStatement = vi.fn().mockResolvedValue(undefined);
}

describe('InvoicesWallet', () => {
  let fixture: ComponentFixture<InvoicesWallet>;
  let api: FakeCommerceApi;

  async function create(canIssue = true): Promise<void> {
    api = new FakeCommerceApi();
    localStorage.clear();
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [InvoicesWallet],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: CommerceApi, useValue: api },
        { provide: TenantsApi, useValue: { listTenants: vi.fn().mockResolvedValue({ items: [], nextCursor: null }) } },
        {
          provide: SessionContextService,
          useValue: { has: (c: string) => canIssue || c !== 'COMMERCIAL_STATEMENT_ISSUE', current: () => ({ subject: 'me' }) },
        },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ tenantId: 'tenant-1' }) } } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(InvoicesWallet);
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

  it('defaults to closing last month, across a new year too', () => {
    expect(previousMonth(new Date('2026-09-11T09:00:00Z'))).toBe('2026-08');
    expect(previousMonth(new Date('2027-01-05T09:00:00Z'))).toBe('2026-12');
  });

  it('previews the month line by line with its total, before tax', async () => {
    await create();

    const text = el('.preview').textContent as string;
    expect(el('[data-kind="PLAN"]').textContent).toContain('BASIC v1');
    expect(el('[data-kind="MODULE"]').textContent).toContain(ru['statements.kind.MODULE']);
    expect(text).toContain(ru['statements.beforeTax']);
    expect(el('.preview .total').textContent).toContain('1');
  });

  it('issues an ended month with a reason', async () => {
    await create();
    const reason = el<HTMLInputElement>('[name="issueReason"]');
    reason.value = 'August close';
    reason.dispatchEvent(new Event('input'));
    await settle();
    el<HTMLButtonElement>('.issueForm button[type="submit"]').click();
    await settle();

    expect(api.issueStatement).toHaveBeenCalledWith('tenant-1', DRAFT.periodKey, 'August close');
    expect(fixture.nativeElement.textContent).toContain('S-2026-08-000002');
  });

  it('voids an issued statement with a reason, and offers neither to someone who may only read', async () => {
    await create();
    el<HTMLButtonElement>('[data-number="S-2026-07-000001"] .voidToggle').click();
    await settle();
    const reason = el<HTMLInputElement>('[name="voidReason"]');
    reason.value = 'wrong plan';
    reason.dispatchEvent(new Event('input'));
    await settle();
    el<HTMLButtonElement>('.confirmVoid').click();
    await settle();
    expect(api.voidStatement).toHaveBeenCalledWith('tenant-1', 'st-1', 'wrong plan');

    TestBed.resetTestingModule();
    await create(false);
    expect(el('.issueForm')).toBeNull();
    expect(el('.voidToggle')).toBeNull();
    expect(el('.exportCsv')).not.toBeNull();
  });

  it('says plainly that the prepaid wallet is not built', async () => {
    await create();

    expect(el('.wallet').textContent).toContain(ru['statements.wallet.body']);
  });
});
