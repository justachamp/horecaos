import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeWalletError } from './wallet-errors';
import { walletDateTime, walletMoney } from './wallet-format';
import { LedgerEntryView, StatementPaymentView, WalletApi } from './wallet-api';

const ENTRY_KEYS: Readonly<Record<string, MessageKey>> = {
  TOP_UP: 'finance.wallet.entry.TOP_UP',
  DEPOSIT: 'finance.wallet.entry.DEPOSIT',
  BONUS_GRANT: 'finance.wallet.entry.BONUS_GRANT',
  BONUS_EXPIRY: 'finance.wallet.entry.BONUS_EXPIRY',
  STATEMENT_PAYMENT: 'finance.wallet.entry.STATEMENT_PAYMENT',
  STATEMENT_REVERSAL: 'finance.wallet.entry.STATEMENT_REVERSAL',
  ADJUSTMENT: 'finance.wallet.entry.ADJUSTMENT',
  REFUND: 'finance.wallet.entry.REFUND',
  DEPOSIT_REVERSAL: 'finance.wallet.entry.DEPOSIT_REVERSAL',
};

const KIND_KEYS: Readonly<Record<string, MessageKey>> = {
  PAID: 'finance.wallet.kind.PAID',
  BONUS: 'finance.wallet.kind.BONUS',
};

/**
 * What each issued statement has been paid, and the append-only ledger behind it (ADR 0095).
 *
 * Both are the server's reading of the same ledger: an issued statement is frozen and carries no payment
 * column, so "paid" and "due" are sums over the entries that name it, computed there, never here. This
 * panel adds nothing up.
 *
 * **The ledger table is a page, and says so.** The balances above it are sums over every entry; the table
 * holds fifty at a time. A caption that said the balance was the sum of the rows on screen would be false
 * the moment a tenant has more, and a reader who cannot reach the rest cannot check it — so "show more"
 * follows the server's cursor and the caption changes while rows are missing.
 *
 * A ledger line carries no reason: what a HorecaOS staff member typed beside a correction is theirs, not
 * the tenant's to read (ADR 0029), and the entry type already says what the line is.
 */
@Component({
  selector: 'q-wallet-ledger-panel',
  imports: [TPipe],
  templateUrl: './wallet-ledger-panel.html',
  styleUrl: './wallet.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WalletLedgerPanel {
  private readonly api = inject(WalletApi);
  protected readonly i18n = inject(I18n);

  readonly tenantId = input.required<string>();
  /** Bumped by the page whenever anything changed, so both reads start over. */
  readonly revision = input(0);

  protected readonly statements = signal<readonly StatementPaymentView[]>([]);
  protected readonly entries = signal<readonly LedgerEntryView[]>([]);
  protected readonly cursor = signal<string | null>(null);
  protected readonly loaded = signal(false);
  protected readonly loadingMore = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.revision();
      const tenantId = this.tenantId();
      untracked(() => void this.load(tenantId));
    });
  }

  private async load(tenantId: string): Promise<void> {
    const [statements, ledger] = await Promise.allSettled([
      this.api.statementPayments(tenantId),
      this.api.ledger(tenantId),
    ]);
    const failures: unknown[] = [];
    if (statements.status === 'fulfilled') {
      this.statements.set(statements.value);
    } else {
      failures.push(statements.reason);
    }
    if (ledger.status === 'fulfilled') {
      this.entries.set(ledger.value.items);
      this.cursor.set(ledger.value.nextCursor);
    } else {
      failures.push(ledger.reason);
    }
    this.error.set(
      failures.length === 0
        ? null
        : describeWalletError(failures[0], (key, values) => this.i18n.t(key, values)),
    );
    this.loaded.set(true);
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.cursor();
    if (cursor === null || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    try {
      const page = await this.api.ledger(this.tenantId(), cursor);
      this.entries.update((current) => [...current, ...page.items]);
      this.cursor.set(page.nextCursor);
      this.error.set(null);
    } catch (failure) {
      // Reported beside the rows already read, which stay: a second page failing is no reason to lose the first.
      this.error.set(describeWalletError(failure, (key, values) => this.i18n.t(key, values)));
    } finally {
      this.loadingMore.set(false);
    }
  }

  protected money(value: { amountMinor: number; currency: string }): string {
    return walletMoney(value, this.i18n.locale());
  }

  protected time(instant: string): string {
    return walletDateTime(instant);
  }

  protected entryLabel(entryType: string): string {
    const key = ENTRY_KEYS[entryType];
    return key ? this.i18n.t(key) : entryType;
  }

  protected kindLabel(moneyKind: string): string {
    const key = KIND_KEYS[moneyKind];
    return key ? this.i18n.t(key) : moneyKind;
  }

  protected settled(statement: StatementPaymentView): boolean {
    return statement.due.amountMinor === 0;
  }
}
