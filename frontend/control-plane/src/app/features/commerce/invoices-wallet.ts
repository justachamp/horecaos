import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { parseAmount } from '../../core/api/money';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import {
  BonusGrantView,
  CommerceApi,
  PAYMENT_METHODS,
  StatementPaymentView,
  StatementView,
  WalletChangeResponse,
  WalletEntryView,
  WalletOverviewView,
} from './commerce-api';

/** `yyyy-MM` of the month before the one `now` falls in, which is the month usually closed. */
export function previousMonth(now: Date): string {
  const year = now.getUTCMonth() === 0 ? now.getUTCFullYear() - 1 : now.getUTCFullYear();
  const month = now.getUTCMonth() === 0 ? 12 : now.getUTCMonth();
  return `${year}-${String(month).padStart(2, '0')}`;
}

/** Which of the wallet's forms is open; only one at a time, like the void confirmation. */
export type WalletForm = 'transfer' | 'deposit' | 'adjustment' | 'grant' | 'refund' | 'method';

/**
 * IA 5.5 Invoices & wallet -- one tenant's monthly statements and its wallet.
 *
 * A statement is what the tenant owes for one month under its plan and
 * modules, before tax: the plan's price, each module on its own unit, and
 * usage beyond what the plan includes. Any month can be previewed; a month
 * that has ended is issued once, frozen with a number, and exported for the
 * accounting system. A wrong one is voided and issued again.
 *
 * The wallet (ADR 0095) is the other half: an append-only ledger that keeps
 * money the tenant paid apart from bonus money HorecaOS granted. Issuing a
 * statement pays it from that wallet at once -- bonus first, the grant
 * expiring soonest first -- so each statement here shows what it has been
 * paid and what is still due, both derived from the ledger rather than
 * stored. Recording a bank transfer is one person's audited act; a
 * correction, a bonus grant and a refund are proposed here and wait for a
 * different person to approve them under Approvals.
 */
@Component({
  selector: 'app-invoices-wallet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TenantPicker, RouterLink],
  templateUrl: './invoices-wallet.html',
  styleUrls: ['./plan-catalog.css', './invoices-wallet.css'],
})
export class InvoicesWallet {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(CommerceApi);
  private readonly directory = inject(TenantDirectory);
  private readonly route = inject(ActivatedRoute);

  protected readonly tenantId = signal(
    this.route.snapshot.queryParamMap.get('tenantId') ?? this.directory.selected(),
  );
  protected readonly periodKey = signal(previousMonth(new Date()));

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly draft = signal<StatementView | null>(null);
  protected readonly draftError = signal<string | null>(null);
  protected readonly issued = signal<readonly StatementView[]>([]);
  protected readonly open = signal<StatementView | null>(null);

  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);
  protected readonly issueReason = signal('');
  protected readonly voiding = signal<string | null>(null);
  protected readonly voidReason = signal('');

  // ------------------------------------------------------------- wallet

  protected readonly methods = PAYMENT_METHODS;
  protected readonly wallet = signal<WalletOverviewView | null>(null);
  protected readonly ledger = signal<readonly WalletEntryView[]>([]);
  protected readonly grants = signal<readonly BonusGrantView[]>([]);
  protected readonly payments = signal<readonly StatementPaymentView[]>([]);
  protected readonly walletError = signal<string | null>(null);

  protected readonly form = signal<WalletForm | null>(null);
  protected readonly amount = signal('');
  protected readonly reference = signal('');
  protected readonly reason = signal('');
  protected readonly moneyKind = signal<'PAID' | 'BONUS'>('PAID');
  protected readonly grantId = signal('');
  protected readonly expiresOn = signal('');
  protected readonly method = signal<string>('INVOICE');
  protected readonly cardToken = signal('');

  /** The currency every wallet entry of this tenant is written in. */
  protected readonly currency = computed(() => this.wallet()?.paidBalance.currency ?? null);

  /** What the typed amount comes to in stored minor units, or null while it is not an amount. */
  protected readonly typedAmount = computed(() => {
    const currency = this.currency();
    return currency === null ? null : parseAmount(this.amount().trim(), currency);
  });

  constructor() {
    if (this.tenantId().length > 0) {
      void this.load();
    }
  }

  protected chooseTenant(tenantId: string): void {
    this.tenantId.set(tenantId);
    this.open.set(null);
    this.actionMessage.set(null);
    this.actionError.set(null);
    this.closeForm();
    if (tenantId.length > 0) {
      void this.load();
    }
  }

  protected choosePeriod(periodKey: string): void {
    if (!/^\d{4}-\d{2}$/.test(periodKey)) {
      return;
    }
    this.periodKey.set(periodKey);
    void this.loadDraft();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.issued.set(await this.api.listStatements(this.tenantId()));
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
    await this.loadDraft();
    await this.loadWallet();
  }

  /**
   * The wallet, its ledger, its live grants and what each statement has been
   * paid. One failure takes the whole panel rather than leaving a balance
   * standing beside a ledger that did not load: a balance the reader cannot
   * check against its entries is the state ADR 0095 exists to prevent.
   */
  private async loadWallet(): Promise<void> {
    this.walletError.set(null);
    const tenantId = this.tenantId();
    try {
      const [wallet, ledger, grants, payments] = await Promise.all([
        this.api.wallet(tenantId),
        this.api.walletLedger(tenantId),
        this.api.bonusGrants(tenantId),
        this.api.statementPayments(tenantId),
      ]);
      this.wallet.set(wallet);
      this.ledger.set(ledger.items);
      this.grants.set(grants);
      this.payments.set(payments);
      this.method.set(wallet.paymentMethod);
      this.cardToken.set(wallet.cardTokenReference ?? '');
    } catch (error) {
      this.wallet.set(null);
      this.ledger.set([]);
      this.grants.set([]);
      this.payments.set([]);
      this.walletError.set(this.i18n.describe(error as ApiError));
    }
  }

  private async loadDraft(): Promise<void> {
    this.draftError.set(null);
    try {
      this.draft.set(await this.api.draftStatement(this.tenantId(), this.periodKey()));
    } catch (error) {
      this.draft.set(null);
      this.draftError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected kindKey(kind: string): MessageKey {
    return `statements.kind.${kind}` as MessageKey;
  }

  protected statusKey(status: string): MessageKey {
    return `statements.status.${status}` as MessageKey;
  }

  /** Whether the previewed month has ended, which is when it may be issued. */
  protected monthEnded(statement: StatementView): boolean {
    return asDate(statement.periodEnd).getTime() <= Date.now();
  }

  /** Whether the previewed month already has a standing statement. */
  protected alreadyIssued(): boolean {
    return this.issued().some((statement) => statement.status === 'ISSUED' && statement.periodKey === this.periodKey());
  }

  protected canIssue(statement: StatementView): boolean {
    return (
      !this.busy() &&
      this.monthEnded(statement) &&
      !this.alreadyIssued() &&
      statement.lines.length > 0 &&
      this.issueReason().trim().length > 0
    );
  }

  protected async issue(event: Event): Promise<void> {
    event.preventDefault();
    const statement = this.draft();
    if (statement === null || !this.canIssue(statement)) {
      return;
    }
    await this.run(async () => {
      const issued = await this.api.issueStatement(this.tenantId(), this.periodKey(), this.issueReason().trim());
      this.issueReason.set('');
      return this.i18n.t('statements.issue.done', { number: issued.number });
    });
  }

  protected async show(statement: StatementView): Promise<void> {
    if (statement.statementId === null) {
      return;
    }
    if (this.open()?.statementId === statement.statementId) {
      this.open.set(null);
      return;
    }
    try {
      this.open.set(await this.api.statement(this.tenantId(), statement.statementId));
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected async exportCsv(statement: StatementView): Promise<void> {
    if (statement.statementId === null) {
      return;
    }
    try {
      const csv = await this.api.exportStatement(this.tenantId(), statement.statementId);
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `statement-${statement.number ?? statement.periodKey}.csv`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected openVoid(statement: StatementView): void {
    this.voiding.set(this.voiding() === statement.statementId ? null : statement.statementId);
    this.voidReason.set('');
  }

  protected async confirmVoid(statement: StatementView): Promise<void> {
    const reason = this.voidReason().trim();
    if (statement.statementId === null || reason.length === 0 || this.busy()) {
      return;
    }
    const statementId = statement.statementId;
    await this.run(async () => {
      await this.api.voidStatement(this.tenantId(), statementId, reason);
      this.voiding.set(null);
      this.open.set(null);
      return this.i18n.t('statements.void.done', { number: statement.number ?? '' });
    });
  }

  // ------------------------------------------------------ wallet actions

  protected entryKey(entryType: string): MessageKey {
    return `wallet.entry.${entryType}` as MessageKey;
  }

  protected methodKey(method: string): MessageKey {
    return `wallet.method.${method}` as MessageKey;
  }

  /** What this statement has been paid and what is still due, or null while the wallet has not loaded. */
  protected payment(statement: StatementView): StatementPaymentView | null {
    return this.payments().find((payment) => payment.statementId === statement.statementId) ?? null;
  }

  /** An em dash for a statement the wallet says nothing about: a void one, or a wallet that did not load. */
  protected paidOf(statement: StatementView): string {
    const payment = this.payment(statement);
    return payment === null ? '—' : this.i18n.money(payment.paid);
  }

  protected dueOf(statement: StatementView): string {
    const payment = this.payment(statement);
    return payment === null ? '—' : this.i18n.money(payment.due);
  }

  protected settled(statement: StatementView): boolean {
    return this.payment(statement)?.due.amountMinor === 0;
  }

  protected openForm(form: WalletForm): void {
    this.form.set(this.form() === form ? null : form);
    this.amount.set('');
    this.reference.set('');
    this.reason.set('');
    this.grantId.set(this.grants()[0]?.grantId ?? '');
    this.moneyKind.set('PAID');
    this.expiresOn.set('');
    this.actionError.set(null);
    this.actionMessage.set(null);
  }

  private closeForm(): void {
    this.form.set(null);
    this.amount.set('');
    this.reference.set('');
    this.reason.set('');
  }

  protected chooseKind(moneyKind: string): void {
    this.moneyKind.set(moneyKind === 'BONUS' ? 'BONUS' : 'PAID');
    this.grantId.set(moneyKind === 'BONUS' ? (this.grants()[0]?.grantId ?? '') : '');
  }

  /** An adjustment may be negative; everything else in is a positive amount. */
  protected canSubmit(form: WalletForm): boolean {
    if (this.busy() || this.reason().trim().length === 0) {
      return false;
    }
    switch (form) {
      case 'transfer':
        return (this.typedAmount() ?? 0) > 0 && this.reference().trim().length > 0;
      case 'deposit':
        return this.reference().trim().length > 0;
      case 'adjustment':
        return (
          this.typedAmount() !== null &&
          this.typedAmount() !== 0 &&
          (this.moneyKind() === 'PAID' || this.grantId().length > 0)
        );
      case 'grant':
        return (this.typedAmount() ?? 0) > 0 && /^\d{4}-\d{2}-\d{2}$/.test(this.expiresOn());
      case 'refund':
        return (this.typedAmount() ?? 0) > 0 && this.reference().trim().length > 0;
      case 'method':
        return this.method().length > 0;
    }
  }

  protected async recordTransfer(event: Event): Promise<void> {
    event.preventDefault();
    const amountMinor = this.typedAmount();
    if (amountMinor === null || !this.canSubmit('transfer')) {
      return;
    }
    await this.run(async () => {
      await this.api.recordTransfer(this.tenantId(), {
        amountMinor,
        bankReference: this.reference().trim(),
        reason: this.reason().trim(),
      });
      this.closeForm();
      return this.i18n.t('wallet.transfer.done');
    });
  }

  protected async recordDeposit(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmit('deposit')) {
      return;
    }
    await this.run(async () => {
      await this.api.recordDeposit(this.tenantId(), {
        bankReference: this.reference().trim(),
        reason: this.reason().trim(),
      });
      this.closeForm();
      return this.i18n.t('wallet.deposit.done');
    });
  }

  protected async proposeAdjustment(event: Event): Promise<void> {
    event.preventDefault();
    const amountMinor = this.typedAmount();
    if (amountMinor === null || !this.canSubmit('adjustment')) {
      return;
    }
    const bonus = this.moneyKind() === 'BONUS';
    await this.run(async () =>
      this.describeChange(
        await this.api.proposeWalletAdjustment(this.tenantId(), {
          moneyKind: this.moneyKind(),
          grantId: bonus ? this.grantId() : undefined,
          amountMinor,
          reason: this.reason().trim(),
        }),
      ),
    );
  }

  protected async proposeBonusGrant(event: Event): Promise<void> {
    event.preventDefault();
    const amountMinor = this.typedAmount();
    if (amountMinor === null || !this.canSubmit('grant')) {
      return;
    }
    await this.run(async () =>
      this.describeChange(
        await this.api.proposeBonusGrant(this.tenantId(), {
          amountMinor,
          // The grant lapses at the start of the day it is given, UTC, which is
          // the instant the server's sweeper compares against.
          expiresAt: `${this.expiresOn()}T00:00:00Z`,
          reason: this.reason().trim(),
        }),
      ),
    );
  }

  protected async proposeRefund(event: Event): Promise<void> {
    event.preventDefault();
    const amountMinor = this.typedAmount();
    if (amountMinor === null || !this.canSubmit('refund')) {
      return;
    }
    await this.run(async () =>
      this.describeChange(
        await this.api.proposeRefund(this.tenantId(), {
          amountMinor,
          payoutReference: this.reference().trim(),
          reason: this.reason().trim(),
        }),
      ),
    );
  }

  protected async changePaymentMethod(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmit('method')) {
      return;
    }
    const method = this.method();
    const token = this.cardToken().trim();
    await this.run(async () => {
      await this.api.setPaymentMethod(this.tenantId(), {
        paymentMethod: method,
        cardTokenReference: method === 'CARD' && token.length > 0 ? token : undefined,
        reason: this.reason().trim(),
      });
      this.closeForm();
      return this.i18n.t('wallet.method.done', { method: this.i18n.t(this.methodKey(method)) });
    });
  }

  /**
   * Nothing has moved while a change is waiting: the maker submits the
   * identical change again once a different person has approved it.
   */
  private describeChange(outcome: WalletChangeResponse): string {
    if (outcome.status === 'CHANGED') {
      this.closeForm();
      return this.i18n.t('wallet.change.done');
    }
    if (outcome.status === 'DECLINED') {
      return this.i18n.t('wallet.change.declined');
    }
    return this.i18n.t('wallet.change.awaiting');
  }

  private async run(write: () => Promise<string>): Promise<void> {
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      this.actionMessage.set(await write());
      await this.load();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
