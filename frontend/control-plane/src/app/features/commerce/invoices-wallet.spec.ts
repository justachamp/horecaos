import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { TenantsApi } from '../tenants/tenants-api';
import {
  BonusGrantView,
  CommerceApi,
  StatementPaymentView,
  StatementView,
  WalletEntryView,
  WalletOverviewView,
} from './commerce-api';
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

const WALLET: WalletOverviewView = {
  paidBalance: UZS(300_000), bonusBalance: UZS(200_000), bonusSpendableBalance: UZS(200_000),
  paymentMethod: 'INVOICE', cardTokenReference: null,
};

const LEDGER: readonly WalletEntryView[] = [
  {
    entryId: 'w-1', moneyKind: 'PAID', entryType: 'TOP_UP', amount: UZS(1_000_000), statementId: null,
    grantId: null, expiresAt: null, externalReference: 'MT103-7781', reason: 'August transfer',
    recordedBy: 'finance-1', approvedBy: null, createdAt: '2026-08-03T06:00:00Z',
  },
  {
    entryId: 'w-2', moneyKind: 'BONUS', entryType: 'STATEMENT_PAYMENT', amount: UZS(-200_000),
    statementId: 'st-1', grantId: 'g-1', expiresAt: null, externalReference: null,
    reason: 'statement S-2026-07-000001 paid from a bonus grant', recordedBy: 'system:wallet-settlement',
    approvedBy: null, createdAt: '2026-08-01T05:00:00Z',
  },
];

/** A second page of the ledger, to be appended to the first. */
const MORE_LEDGER: readonly WalletEntryView[] = [
  {
    entryId: 'w-0', moneyKind: 'BONUS', entryType: 'BONUS_GRANT', amount: UZS(400_000), statementId: null,
    grantId: 'g-1', expiresAt: '2026-12-01T00:00:00Z', externalReference: null, reason: 'launch credit',
    recordedBy: 'finance-1', approvedBy: 'finance-2', createdAt: '2026-07-01T05:00:00Z',
  },
];

const GRANTS: readonly BonusGrantView[] = [
  { grantId: 'g-1', granted: UZS(400_000), remaining: UZS(200_000), expiresAt: '2026-12-01T00:00:00Z', reason: 'launch credit' },
];

const PAYMENTS: readonly StatementPaymentView[] = [
  { statementId: 'st-1', number: 'S-2026-07-000001', periodKey: '2026-07', total: UZS(1_500_000), paid: UZS(1_200_000), due: UZS(300_000) },
];

class FakeCommerceApi {
  readonly listStatements = vi.fn().mockResolvedValue([ISSUED]);
  readonly draftStatement = vi.fn().mockResolvedValue(DRAFT);
  readonly statement = vi.fn().mockResolvedValue(ISSUED);
  readonly exportStatement = vi.fn().mockResolvedValue('number\r\n');
  readonly issueStatement = vi.fn().mockResolvedValue({ statementId: 'st-2', number: 'S-2026-08-000002' });
  readonly voidStatement = vi.fn().mockResolvedValue(undefined);
  wallet = vi.fn().mockResolvedValue(WALLET);
  readonly walletLedger = vi.fn().mockResolvedValue({ items: LEDGER, nextCursor: null });
  readonly bonusGrants = vi.fn().mockResolvedValue(GRANTS);
  readonly statementPayments = vi.fn().mockResolvedValue(PAYMENTS);
  readonly recordTransfer = vi.fn().mockResolvedValue({ entryId: 'w-3' });
  readonly recordDeposit = vi.fn().mockResolvedValue({ entryId: 'w-4' });
  readonly proposeWalletAdjustment = vi.fn().mockResolvedValue({ status: 'AWAITING_APPROVAL', approvalRequestId: 'ap-1' });
  readonly proposeBonusGrant = vi.fn().mockResolvedValue({ status: 'AWAITING_APPROVAL', approvalRequestId: 'ap-2' });
  readonly proposeRefund = vi.fn().mockResolvedValue({ status: 'AWAITING_APPROVAL', approvalRequestId: 'ap-3' });
  readonly setPaymentMethod = vi.fn().mockResolvedValue(undefined);
}

describe('InvoicesWallet', () => {
  let fixture: ComponentFixture<InvoicesWallet>;
  let api: FakeCommerceApi;

  async function create(staff = true): Promise<void> {
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
          useValue: {
            has: (c: string) =>
              staff || (c !== 'COMMERCIAL_STATEMENT_ISSUE' && c !== 'COMMERCIAL_WALLET_MANAGE'),
            current: () => ({ subject: 'me' }),
          },
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

  /** Types into a field the way a person does, then lets the screen catch up. */
  async function type(selector: string, value: string, event = 'input'): Promise<void> {
    const field = el<HTMLInputElement>(selector);
    field.value = value;
    field.dispatchEvent(new Event(event));
    await settle();
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

  it('shows both balances apart, and the ledger a reader can add up for themselves', async () => {
    await create();

    expect(el('.paidBalance').textContent).toContain('300 000');
    expect(el('.bonusBalance').textContent).toContain('200 000');
    expect(el('[data-entry="TOP_UP"]').textContent).toContain('MT103-7781');
    // A spend of bonus money reads negative, with the U+2212 the money
    // formatter uses, and names the entry type rather than a raw code.
    expect(el('[data-entry="STATEMENT_PAYMENT"]').textContent).toContain('−200 000');
    expect(el('[data-entry="STATEMENT_PAYMENT"]').textContent).toContain(ru['wallet.entry.STATEMENT_PAYMENT']);
    expect(el('[data-grant="g-1"]').textContent).toContain('200 000');
    expect(el('.wallet').textContent).toContain(ru['wallet.ledger.appendOnly']);
  });

  it('shows what each issued statement has been paid and what is still due', async () => {
    await create();

    const row = el('[data-number="S-2026-07-000001"]');
    expect(row.querySelector('.paid')?.textContent).toContain('1 200 000');
    expect(row.querySelector('.due')?.textContent).toContain('300 000');
    expect(row.querySelector('.due')?.classList.contains('settled')).toBe(false);
  });

  it('records a bank transfer with the reference that proves it', async () => {
    await create();
    el<HTMLButtonElement>('.openTransfer').click();
    await settle();
    for (const [name, value] of [
      ['amount', '1 000 000'],
      ['bankReference', 'MT103-9001'],
      ['reason', 'August transfer'],
    ]) {
      const field = el<HTMLInputElement>(`.transferForm [name="${name}"]`);
      field.value = value;
      field.dispatchEvent(new Event('input'));
    }
    await settle();
    el<HTMLButtonElement>('.transferForm button[type="submit"]').click();
    await settle();

    expect(api.recordTransfer).toHaveBeenCalledWith('tenant-1', {
      amountMinor: 1_000_000,
      bankReference: 'MT103-9001',
      reason: 'August transfer',
    });
  });

  it('says a proposed correction has moved nothing yet, and points at Approvals', async () => {
    await create();
    el<HTMLButtonElement>('.openAdjustment').click();
    await settle();
    const amount = el<HTMLInputElement>('.adjustmentForm [name="amount"]');
    amount.value = '50 000';
    amount.dispatchEvent(new Event('input'));
    const reason = el<HTMLInputElement>('.adjustmentForm [name="reason"]');
    reason.value = 'a misposted transfer';
    reason.dispatchEvent(new Event('input'));
    await settle();
    el<HTMLButtonElement>('.adjustmentForm button[type="submit"]').click();
    await settle();

    expect(api.proposeWalletAdjustment).toHaveBeenCalledWith('tenant-1', {
      moneyKind: 'PAID',
      grantId: undefined,
      amountMinor: 50_000,
      reason: 'a misposted transfer',
    });
    expect(fixture.nativeElement.textContent).toContain(ru['wallet.change.awaiting']);
    expect(el<HTMLAnchorElement>('.approvalsLink').getAttribute('href')).toBe('/compliance/approvals');
  });

  it('a correction of bonus money names the grant it corrects', async () => {
    await create();
    el<HTMLButtonElement>('.openAdjustment').click();
    await settle();
    await type('.adjustmentForm [name="moneyKind"]', 'BONUS', 'change');
    // Downwards, because that is what "granted too much" means: a positive
    // amount here would grow the grant it says it is cutting back.
    await type('.adjustmentForm [name="amount"]', '-10 000');
    await type('.adjustmentForm [name="reason"]', 'granted too much');
    el<HTMLButtonElement>('.adjustmentForm button[type="submit"]').click();
    await settle();

    expect(api.proposeWalletAdjustment).toHaveBeenCalledWith('tenant-1', {
      moneyKind: 'BONUS',
      grantId: 'g-1',
      amountMinor: -10_000,
      reason: 'granted too much',
    });
  });

  it('takes paid money away when the minus the field asks for is typed', async () => {
    await create();
    el<HTMLButtonElement>('.openAdjustment').click();
    await settle();
    await type('.adjustmentForm [name="amount"]', '-50 000');
    await type('.adjustmentForm [name="reason"]', 'a transfer recorded against the wrong tenant');
    expect(el<HTMLButtonElement>('.adjustmentForm button[type="submit"]').disabled).toBe(false);
    el<HTMLButtonElement>('.adjustmentForm button[type="submit"]').click();
    await settle();

    // Minor units, signed: the amount that undoes a 50 000 over-credit is
    // −50 000, and nothing on the way to the server may drop the sign.
    expect(api.proposeWalletAdjustment).toHaveBeenCalledWith('tenant-1', {
      moneyKind: 'PAID',
      grantId: undefined,
      amountMinor: -50_000,
      reason: 'a transfer recorded against the wrong tenant',
    });
  });

  it('reads the minus sign the ledger itself prints', async () => {
    await create();
    el<HTMLButtonElement>('.openAdjustment').click();
    await settle();
    // U+2212, which is what the reader copies out of the table above.
    await type('.adjustmentForm [name="amount"]', '−50 000');
    await type('.adjustmentForm [name="reason"]', 'reversing the August misposting');
    el<HTMLButtonElement>('.adjustmentForm button[type="submit"]').click();
    await settle();

    expect(api.proposeWalletAdjustment).toHaveBeenCalledWith('tenant-1', {
      moneyKind: 'PAID',
      grantId: undefined,
      amountMinor: -50_000,
      reason: 'reversing the August misposting',
    });
  });

  it('refuses a sign with no amount behind it', async () => {
    await create();
    el<HTMLButtonElement>('.openAdjustment').click();
    await settle();
    await type('.adjustmentForm [name="reason"]', 'nothing to correct');

    for (const typed of ['-0', '-', '−']) {
      await type('.adjustmentForm [name="amount"]', typed);
      expect(el<HTMLButtonElement>('.adjustmentForm button[type="submit"]').disabled, typed).toBe(true);
      el<HTMLButtonElement>('.adjustmentForm button[type="submit"]').click();
      await settle();
    }

    expect(api.proposeWalletAdjustment).not.toHaveBeenCalled();
  });

  it('shows the tenant its own collection method, and sends the one chosen', async () => {
    await create();
    api.wallet.mockResolvedValue({ ...WALLET, paymentMethod: 'CARD', cardTokenReference: 'vault:pilot' });
    await fixture.componentInstance['load']();
    await settle();
    el<HTMLButtonElement>('.openMethod').click();
    await settle();

    // The dropdown must read what the tenant is on, not the first option: an
    // operator moving a CARD tenant to invoicing was shown INVOICE already
    // selected and their untouched submit re-sent CARD.
    const select = el<HTMLSelectElement>('.methodForm [name="paymentMethod"]');
    expect(select.value).toBe('CARD');
    expect(select.selectedIndex).toBe(2);

    await type('.methodForm [name="paymentMethod"]', 'INVOICE', 'change');
    await type('.methodForm [name="reason"]', 'the tenant asked to be invoiced');
    el<HTMLButtonElement>('.methodForm button[type="submit"]').click();
    await settle();

    expect(api.setPaymentMethod).toHaveBeenCalledWith('tenant-1', {
      paymentMethod: 'INVOICE',
      cardTokenReference: undefined,
      reason: 'the tenant asked to be invoiced',
    });
    // Spelled out as well as matched: a stale token travelling with a method
    // that is not CARD is exactly what the old select would have sent.
    expect(api.setPaymentMethod.mock.calls[0][1].cardTokenReference).toBeUndefined();
  });

  it('offers the rest of a ledger longer than one page, and does not claim the balance is what is shown', async () => {
    await create();
    api.walletLedger
      .mockResolvedValueOnce({ items: LEDGER, nextCursor: 'c1' })
      .mockResolvedValueOnce({ items: MORE_LEDGER, nextCursor: null });
    await fixture.componentInstance['load']();
    await settle();

    expect(el('.loadMore')).not.toBeNull();
    expect(el('.ledgerNote').textContent).toContain(
      ru['wallet.ledger.appendOnlyTruncated'].replace('{count}', '2'),
    );
    expect(el('.ledgerNote').textContent).not.toContain(ru['wallet.ledger.appendOnly']);

    el<HTMLButtonElement>('.loadMore').click();
    await settle();

    expect(api.walletLedger).toHaveBeenLastCalledWith('tenant-1', 'c1');
    expect(fixture.nativeElement.querySelectorAll('.ledger tbody tr').length).toBe(3);
    expect(el('[data-entry="BONUS_GRANT"]').textContent).toContain('launch credit');
    expect(el('.loadMore')).toBeNull();
    expect(el('.ledgerNote').textContent).toContain(ru['wallet.ledger.appendOnly']);
  });

  it('leads the bonus tile with what a statement could draw on, and says what the ledger sums', async () => {
    await create();
    // A grant that is past its date but that the hourly sweep has not lapsed
    // yet: the ledger still sums it, and no statement can spend it.
    api.wallet.mockResolvedValue({ ...WALLET, bonusBalance: UZS(200_000), bonusSpendableBalance: UZS(150_000) });
    await fixture.componentInstance['load']();
    await settle();

    const tile = el('.bonusBalance').textContent as string;
    expect(tile).toContain('150 000');
    expect(el('.bonusLedger').textContent).toContain('200 000');
  });

  it('names the entry that takes paid money back, and shows a raw code rather than a blank cell', async () => {
    await create();
    api.walletLedger.mockResolvedValue({
      items: [
        {
          entryId: 'w-9', moneyKind: 'PAID', entryType: 'DEPOSIT_REVERSAL', amount: UZS(-500_000),
          statementId: null, grantId: null, expiresAt: null, externalReference: 'MT103-DEP',
          reason: 'recorded against the wrong tenant', recordedBy: 'finance-1',
          approvedBy: 'finance-2', createdAt: '2026-09-15T09:00:00Z',
        },
        {
          entryId: 'w-10', moneyKind: 'PAID', entryType: 'SOMETHING_NEW', amount: UZS(-1_000),
          statementId: null, grantId: null, expiresAt: null, externalReference: null,
          reason: 'a type the server has and no catalogue names yet', recordedBy: 'system',
          approvedBy: null, createdAt: '2026-09-15T09:00:00Z',
        },
      ],
      nextCursor: null,
    });
    await fixture.componentInstance['load']();
    await settle();

    // DEPOSIT_REVERSAL shipped missing from all three catalogues, and key
    // parity between them cannot see that: the row for the one entry that
    // takes paid money back rendered a blank Type cell.
    const reversal = el('[data-entry="DEPOSIT_REVERSAL"] .entryLabel');
    expect(reversal.textContent?.trim()).toBe(ru['wallet.entry.DEPOSIT_REVERSAL']);
    expect(reversal.textContent?.trim().length).toBeGreaterThan(0);

    // And the next type the server grows shows itself rather than nothing.
    expect(el('[data-entry="SOMETHING_NEW"] .entryLabel').textContent?.trim()).toBe('SOMETHING_NEW');
  });

  it('shows a payment method nobody labelled by its raw code', async () => {
    await create();
    api.wallet.mockResolvedValue({ ...WALLET, paymentMethod: 'DIRECT_DEBIT' });
    await fixture.componentInstance['load']();
    await settle();

    expect(el('.methodName').textContent?.trim()).toBe('DIRECT_DEBIT');
  });

  it('says card charging is not connected, and offers nothing to someone who may only read', async () => {
    await create();
    api.wallet.mockResolvedValue({ ...WALLET, paymentMethod: 'CARD', cardTokenReference: 'vault:pilot' });
    await fixture.componentInstance['load']();
    await settle();

    expect(el('.cardNote').textContent).toContain(ru['wallet.card.notConnected']);
    expect(el('.methodName').textContent).toContain(ru['wallet.method.CARD']);

    TestBed.resetTestingModule();
    await create(false);
    expect(el('.openTransfer')).toBeNull();
    expect(el('.openRefund')).toBeNull();
    expect(el('.openMethod')).toBeNull();
    expect(el('.ledger')).not.toBeNull();
  });
});
