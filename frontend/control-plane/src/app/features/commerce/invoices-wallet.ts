import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import { CommerceApi, StatementView } from './commerce-api';

/** `yyyy-MM` of the month before the one `now` falls in, which is the month usually closed. */
export function previousMonth(now: Date): string {
  const year = now.getUTCMonth() === 0 ? now.getUTCFullYear() - 1 : now.getUTCFullYear();
  const month = now.getUTCMonth() === 0 ? 12 : now.getUTCMonth();
  return `${year}-${String(month).padStart(2, '0')}`;
}

/**
 * IA 5.5 Invoices & wallet -- one tenant's monthly statements.
 *
 * A statement is what the tenant owes for one month under its plan and
 * modules, before tax: the plan's price, each module on its own unit, and
 * usage beyond what the plan includes. Any month can be previewed; a month
 * that has ended is issued once, frozen with a number, and exported for the
 * accounting system. A wrong one is voided and issued again.
 *
 * The prepaid wallet is not built: how tenants pay and how money held in
 * advance is taxed are not decided, and the screen says so.
 */
@Component({
  selector: 'app-invoices-wallet',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TenantPicker],
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
