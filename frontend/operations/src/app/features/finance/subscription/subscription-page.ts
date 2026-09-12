import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ApiError } from '../../../core/api/problem-details';
import { describeApiError } from '../../orders/order-errors';
import {
  CommercialApi,
  EntitlementSnapshotView,
  StatementView,
  SubscriptionView,
  UsageView,
} from '../commercial-api';

type LoadState = 'loading' | 'ready' | 'denied' | 'error';

const STATUS_KEYS: Readonly<Record<SubscriptionView['status'], MessageKey>> = {
  DRAFT: 'finance.subscription.status.DRAFT',
  TRIALING: 'finance.subscription.status.TRIALING',
  ACTIVE: 'finance.subscription.status.ACTIVE',
  PAST_DUE: 'finance.subscription.status.PAST_DUE',
  SUSPENDED: 'finance.subscription.status.SUSPENDED',
  CANCELLATION_SCHEDULED: 'finance.subscription.status.CANCELLATION_SCHEDULED',
  EXPIRED: 'finance.subscription.status.EXPIRED',
  TERMINATED: 'finance.subscription.status.TERMINATED',
};

/** `Statement.java`'s three constants. An unrecognised value falls back to itself, never a blank cell. */
const STATEMENT_STATUS_KEYS: Readonly<Record<string, MessageKey>> = {
  DRAFT: 'finance.subscription.statements.status.DRAFT',
  ISSUED: 'finance.subscription.statements.status.ISSUED',
  VOID: 'finance.subscription.statements.status.VOID',
};

/** `StatementLine.java`'s five kind constants. */
const STATEMENT_LINE_KIND_KEYS: Readonly<Record<string, MessageKey>> = {
  PLAN: 'finance.subscription.statements.kind.PLAN',
  MODULE: 'finance.subscription.statements.kind.MODULE',
  OVERAGE: 'finance.subscription.statements.kind.OVERAGE',
  DEPOSIT: 'finance.subscription.statements.kind.DEPOSIT',
  EARLY_EXIT: 'finance.subscription.statements.kind.EARLY_EXIT',
};

/**
 * 8.6 Subscription & billing (`frontend-information-architecture.md` §8.6) —
 * tier 2. "The merchant's own HorecaOS account: current plan and term;
 * purchasable modules …; prepaid wallet …; credit-expiry warning; arrears
 * state …; invoices."
 *
 * **What is real here.** Plan, term and status (`CommercialOperationsController
 * .subscription`), every entitled module with where its value came from
 * (`.entitlements` — the locked-by-plan half of IA 9.1's locked-vs-denied
 * distinction), metered usage, measured and adjusted kept apart (`.usage`) —
 * the same three reads `CommercialControlPlaneController` already serves
 * platform staff, reachable from this console for the first time — and, as of
 * Finance 8/X.4, every statement this tenant has been issued, with its lines
 * and totals, and a CSV download per statement (`.statements`/`.statement`/
 * `.statementExport`). That read was always `ScopeType.TENANT` and held by
 * `TENANT_OWNER`/`TENANT_FINANCE`; it simply had no client until now.
 *
 * **What is honestly not.** ADR 0021's own status line: no period close, no
 * wallet. Purchasable modules with inline purchase needs the platform-wide
 * plan catalogue, a `ScopeType.PLATFORM` read no tenant grant can satisfy —
 * this screen names that rather than shipping a catalogue with nothing to buy
 * from it. Arrears state and the restricted-feature banner need
 * `ArrearsController`'s own tenant-reachable mirror, not built here either.
 */
@Component({
  selector: 'q-subscription-page',
  imports: [TPipe],
  templateUrl: './subscription-page.html',
  styleUrl: './subscription-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SubscriptionPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(CommercialApi);
  protected readonly i18n = inject(I18n);

  protected readonly state = signal<LoadState>('loading');
  protected readonly loadErrorText = signal<string | null>(null);

  protected readonly subscription = signal<SubscriptionView | null>(null);
  protected readonly subscriptionDenied = signal(false);
  protected readonly entitlements = signal<EntitlementSnapshotView | null>(null);
  protected readonly usage = signal<readonly UsageView[]>([]);

  protected readonly statements = signal<readonly StatementView[]>([]);
  /** The statement whose lines are expanded inline, or null when none is. */
  protected readonly openStatement = signal<StatementView | null>(null);
  protected readonly statementActionError = signal<string | null>(null);
  /** Which statement a download is in flight for, so a slow export disables only its own button. */
  protected readonly downloadingStatementId = signal<string | null>(null);

  private tenantId: string | null = null;

  constructor() {
    void this.load();
  }

  protected retry(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    this.state.set('loading');
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.state.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    this.tenantId = tenantId;
    try {
      const [subscription, entitlements, usage, statements] = await Promise.all([
        this.api.subscription(tenantId).catch((error) => {
          if (error instanceof ApiError && error.status === 403) {
            this.subscriptionDenied.set(true);
          }
          return null;
        }),
        this.api.entitlements(tenantId),
        this.api.usage(tenantId),
        this.api.statements(tenantId),
      ]);
      this.subscription.set(subscription);
      this.entitlements.set(entitlements);
      this.usage.set(usage);
      this.statements.set(statements);
      this.state.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.state.set('denied');
      } else {
        this.loadErrorText.set(this.describe(error));
        this.state.set('error');
      }
    }
  }

  protected money(value: { amountMinor: number; currency: string } | null): string {
    if (!value) {
      return '—';
    }
    return formatMoney(value, this.i18n.locale(), { withUnit: true });
  }

  protected statusLabel(status: SubscriptionView['status']): string {
    return this.i18n.t(STATUS_KEYS[status]);
  }

  protected statementStatusLabel(status: string): string {
    const key = STATEMENT_STATUS_KEYS[status];
    return key ? this.i18n.t(key) : status;
  }

  protected lineKindLabel(kind: string): string {
    const key = STATEMENT_LINE_KIND_KEYS[kind];
    return key ? this.i18n.t(key) : kind;
  }

  /** Toggles the inline line detail for one statement, fetching it the first time it opens. */
  protected async toggleStatement(statement: StatementView): Promise<void> {
    if (statement.statementId === null) {
      return;
    }
    if (this.openStatement()?.statementId === statement.statementId) {
      this.openStatement.set(null);
      return;
    }
    this.statementActionError.set(null);
    try {
      const detail = await this.api.statement(this.requireTenantId(), statement.statementId);
      this.openStatement.set(detail);
    } catch (error) {
      this.statementActionError.set(this.describe(error));
    }
  }

  protected async downloadStatement(statement: StatementView): Promise<void> {
    if (statement.statementId === null || this.downloadingStatementId() !== null) {
      return;
    }
    this.statementActionError.set(null);
    this.downloadingStatementId.set(statement.statementId);
    try {
      const csv = await this.api.statementExport(this.requireTenantId(), statement.statementId);
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `statement-${statement.number ?? statement.periodKey}.csv`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      this.statementActionError.set(this.describe(error));
    } finally {
      this.downloadingStatementId.set(null);
    }
  }

  private requireTenantId(): string {
    if (this.tenantId === null) {
      throw new Error('downloadStatement/toggleStatement called before the page finished loading');
    }
    return this.tenantId;
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
