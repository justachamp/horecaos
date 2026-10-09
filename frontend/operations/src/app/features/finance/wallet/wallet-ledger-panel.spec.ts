import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { WalletApi } from './wallet-api';
import { WalletLedgerPanel } from './wallet-ledger-panel';
import { STATEMENT_PAYMENTS, TENANT_ID, ledgerEntry } from './wallet-fixtures.testing';

async function settle(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

@Component({
  selector: 'q-host',
  imports: [WalletLedgerPanel],
  template: `<q-wallet-ledger-panel [tenantId]="tenantId" [revision]="revision()" />`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class Host {
  tenantId = TENANT_ID;
  revision = signal(0);
}

describe('WalletLedgerPanel', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let api: {
    statementPayments: ReturnType<typeof vi.fn>;
    ledger: ReturnType<typeof vi.fn>;
  };

  const root = (): HTMLElement => fixture.nativeElement;
  const q = (testid: string): HTMLElement | null =>
    root().querySelector(`[data-testid="${testid}"]`);
  const flush = async (): Promise<void> => {
    await settle();
    fixture.detectChanges();
  };
  const plain = (text: string | null | undefined): string => (text ?? '').replace(/[ ]/g, ' ');

  beforeEach(async () => {
    api = {
      statementPayments: vi.fn().mockResolvedValue(STATEMENT_PAYMENTS),
      ledger: vi.fn().mockResolvedValue({
        items: [
          ledgerEntry(2, {
            entryType: 'STATEMENT_PAYMENT',
            amount: { amountMinor: -1_000_000, currency: 'UZS' },
          }),
          ledgerEntry(1, {
            moneyKind: 'BONUS',
            entryType: 'BONUS_GRANT',
            amount: { amountMinor: 300_000, currency: 'UZS' },
          }),
        ],
        nextCursor: null,
      }),
    };
    await TestBed.configureTestingModule({
      imports: [Host],
      providers: [{ provide: WalletApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    fixture.detectChanges();
    await flush();
  });

  it('shows what each statement has been paid and what is still due, and marks a settled one paid', () => {
    const unsettled = root().querySelector('tr[data-statement="S-2026-09-000002"]')?.textContent;
    expect(plain(unsettled)).toContain('1 000 000');
    expect(plain(unsettled)).toContain('500 000');
    const settled = root().querySelector('tr[data-statement="S-2026-08-000001"]')?.textContent;
    expect(plain(settled)).toContain('Paid');
  });

  it('labels each ledger entry in words and keeps bonus money visibly apart from paid money', () => {
    const entries = [...root().querySelectorAll('[data-testid="wallet-ledger-table"] tbody tr')];
    expect(entries).toHaveLength(2);
    expect(entries[0].textContent).toContain('Paid a statement');
    expect(entries[0].textContent).toContain('Paid money');
    expect(entries[1].textContent).toContain('Bonus credit granted');
    expect(entries[1].textContent).toContain('Bonus');
    // A debit keeps its sign, as a true minus.
    expect(plain(entries[0].textContent)).toContain('−1 000 000');
  });

  it('shows an entry type it has no word for as itself, never a blank', async () => {
    api.ledger.mockResolvedValue({
      items: [ledgerEntry(1, { entryType: 'SOMETHING_NEW' })],
      nextCursor: null,
    });
    host.revision.set(1);
    fixture.detectChanges();
    await flush();
    expect(root().textContent).toContain('SOMETHING_NEW');
  });

  it('claims the balances are the sum of the rows only while the whole ledger is on screen', async () => {
    expect(q('wallet-ledger-note')?.textContent).toContain(
      'a balance above is the sum of these entries',
    );
    expect(q('wallet-ledger-more')).toBeNull();

    api.ledger.mockResolvedValueOnce({ items: [ledgerEntry(1)], nextCursor: 'c1' });
    host.revision.set(1);
    fixture.detectChanges();
    await flush();
    expect(q('wallet-ledger-more')).not.toBeNull();
    expect(q('wallet-ledger-note')?.textContent).toContain('newest 1 entries');
    expect(q('wallet-ledger-note')?.textContent).not.toContain(
      'a balance above is the sum of these entries',
    );
  });

  it('follows the cursor to the next page and appends it', async () => {
    api.ledger.mockResolvedValueOnce({ items: [ledgerEntry(1)], nextCursor: 'c1' });
    host.revision.set(1);
    fixture.detectChanges();
    await flush();

    api.ledger.mockResolvedValueOnce({ items: [ledgerEntry(2), ledgerEntry(3)], nextCursor: null });
    (q('wallet-ledger-more') as HTMLElement).click();
    await flush();

    expect(api.ledger).toHaveBeenLastCalledWith(TENANT_ID, 'c1');
    expect(root().querySelectorAll('[data-testid="wallet-ledger-table"] tbody tr')).toHaveLength(3);
    expect(q('wallet-ledger-more')).toBeNull();
  });

  it('keeps the rows already read when a later page fails, and says so', async () => {
    api.ledger.mockResolvedValueOnce({ items: [ledgerEntry(1)], nextCursor: 'c1' });
    host.revision.set(1);
    fixture.detectChanges();
    await flush();

    api.ledger.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, 'corr-3'),
    );
    (q('wallet-ledger-more') as HTMLElement).click();
    await flush();

    expect(q('wallet-ledger-error')).not.toBeNull();
    expect(root().querySelectorAll('[data-testid="wallet-ledger-table"] tbody tr')).toHaveLength(1);
  });

  it('says a ledger with nothing in it is empty, and a tenant with no statement has none', async () => {
    api.ledger.mockResolvedValue({ items: [], nextCursor: null });
    api.statementPayments.mockResolvedValue([]);
    host.revision.set(1);
    fixture.detectChanges();
    await flush();
    expect(root().textContent).toContain('Nothing has moved in this wallet yet.');
    expect(root().textContent).toContain('No statement has been issued yet.');
  });

  it('shows what did load when one of the two reads fails', async () => {
    api.statementPayments.mockRejectedValueOnce(
      new ApiError(ApiErrorCode.INTERNAL_ERROR, 500, null, 'corr-4'),
    );
    host.revision.set(1);
    fixture.detectChanges();
    await flush();
    expect(q('wallet-ledger-error')).not.toBeNull();
    expect(root().querySelectorAll('[data-testid="wallet-ledger-table"] tbody tr')).toHaveLength(2);
  });
});
