import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ApiError } from '../../../core/api/problem-details';
import { ConfirmDialog } from '../../../shared/ui/confirm-dialog';
import { describeApiError } from '../../orders/order-errors';
import {
  CommercialApi,
  EntitlementSnapshotView,
  SellableModuleView,
  StatementView,
  SubscriptionView,
  TenantArrearsView,
  TenantModuleView,
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

/** `BillingUnit.java`'s five constants. */
const BILLING_UNIT_KEYS: Readonly<Record<string, MessageKey>> = {
  PER_TENANT: 'finance.subscription.modules.billingUnit.PER_TENANT',
  PER_BRAND: 'finance.subscription.modules.billingUnit.PER_BRAND',
  PER_LOCATION: 'finance.subscription.modules.billingUnit.PER_LOCATION',
  PER_UNIT: 'finance.subscription.modules.billingUnit.PER_UNIT',
  ONE_OFF: 'finance.subscription.modules.billingUnit.ONE_OFF',
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
 * **The purchasable-module catalogue and the arrears banner, as of ADR
 * 0127.** `modulesOnSale`/`modulesHeld`/`purchaseModule` render what HorecaOS
 * sells and let `TENANT_OWNER`/`TENANT_FINANCE` add one inline — the same
 * pair that already holds `refund.execute`, under the new
 * `COMMERCIAL_MODULE_READ`/`COMMERCIAL_SUBSCRIPTION_MANAGE` (tenant scope)
 * declarations. `arrears` reads this tenant's own place in the lifecycle
 * under the new `COMMERCIAL_ARREARS_READ`; the restricted-feature banner
 * renders only when something is actually restricted (`additionsBlocked` or
 * plan entitlements not applying) — a healthy tenant sees nothing extra,
 * because a banner that always shows stops meaning anything.
 *
 * **Gap map row 8.6: a confirm step before the purchase call.** Clicking
 * "Add" no longer calls `purchaseModule` directly — it opens {@link
 * ConfirmDialog} naming the module, its total price (quantity folded in for
 * a `PER_UNIT` module), its billing unit (the closest thing to a "period"
 * this catalogue has — `ONE_OFF` bills once, everything else recurs on the
 * next statement, ADR 0088), and what it switches on (`description` when
 * the module has one, else its raw `featureKeys`). Only {@link
 * confirmPurchase} calls the API; {@link cancelPurchase}/Escape/backdrop
 * close the dialog with nothing sent.
 *
 * **Ending one's own purchase, as of ADR 0127's status note of
 * 2026-09-30.** The catalogue's "Added" rows are joined by a "Your modules"
 * table of every live module the tenant holds, with an "End" button only where
 * the server says `endableByTenant` — a module the tenant bought itself, never
 * one HorecaOS assigned (`acquiredVia`). "End" opens the same {@link
 * ConfirmDialog} the purchase has, and says what ADR 0087/0088 already decided
 * rather than promising a refund: its features switch off now, nothing is
 * prorated, so the month it ends in is still billed in full and no later month
 * is. Only {@link confirmEnd} calls the API; {@link cancelEnd}/Escape/backdrop
 * send nothing. On success the server's `lastBilledPeriod` is shown back, so
 * the last month the module bills is read from the statement rule, not
 * guessed by this screen.
 *
 * **What is honestly not.** Period close is HorecaOS-staff work: ADR 0088
 * decided a month is closed by issuing its statement, deliberately manual
 * until tax and invoicing are approved, so this screen has nothing left to
 * add for it. The prepaid wallet stays blocked on ADR 0095. Nor is there a
 * grace window that voids a purchase undone at once: no proration means even
 * an immediate undo bills the month, which the End dialog says.
 */
@Component({
  selector: 'q-subscription-page',
  imports: [TPipe, ConfirmDialog],
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

  protected readonly modulesOnSale = signal<readonly SellableModuleView[]>([]);
  protected readonly modulesHeld = signal<readonly TenantModuleView[]>([]);
  protected readonly modulesDenied = signal(false);
  protected readonly moduleActionError = signal<string | null>(null);
  /** Which module a purchase is in flight for, so a slow write disables only its own button. */
  protected readonly purchasingModuleId = signal<string | null>(null);
  /** `PER_UNIT` modules ask how many; keyed by moduleId, defaulting to 1. */
  protected readonly moduleQuantities = signal<Readonly<Record<string, number>>>({});
  /**
   * Gap map row 8.6: the module "Add" opens a confirm step naming price,
   * billing unit and what activates, rather than calling {@link
   * confirmPurchase} directly — set by {@link requestPurchase}, cleared by
   * {@link cancelPurchase} or once {@link confirmPurchase} settles.
   */
  protected readonly purchaseTarget = signal<SellableModuleView | null>(null);

  /** Every module the tenant holds right now; the ended ones are history, not a row to act on. */
  protected readonly liveModules = computed(() =>
    this.modulesHeld().filter((held) => held.endedAt === null),
  );
  /**
   * ADR 0127 status note: the held module "End" opens a confirm step for,
   * set by {@link requestEnd}, cleared by {@link cancelEnd} or once {@link
   * confirmEnd} settles.
   */
  protected readonly endTarget = signal<TenantModuleView | null>(null);
  /** Which held module an end is in flight for, so a slow write disables only its own button. */
  protected readonly endingModuleId = signal<string | null>(null);
  /** What the last successful end says back: the module's name and the last month that still bills it. */
  protected readonly endNotice = signal<{ readonly name: string; readonly period: string } | null>(
    null,
  );

  protected readonly arrears = signal<TenantArrearsView | null>(null);
  protected readonly arrearsDenied = signal(false);

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
      const [subscription, entitlements, usage, statements, modulesOnSale, modulesHeld, arrears] =
        await Promise.all([
          this.api.subscription(tenantId).catch((error) => {
            if (error instanceof ApiError && error.status === 403) {
              this.subscriptionDenied.set(true);
            }
            return null;
          }),
          this.api.entitlements(tenantId),
          this.api.usage(tenantId),
          this.api.statements(tenantId),
          this.api.modulesOnSale(tenantId).catch((error) => {
            if (error instanceof ApiError && error.status === 403) {
              this.modulesDenied.set(true);
            }
            return [];
          }),
          this.api.modulesHeld(tenantId).catch(() => []),
          this.api.arrears(tenantId).catch((error) => {
            if (error instanceof ApiError && error.status === 403) {
              this.arrearsDenied.set(true);
            }
            return null;
          }),
        ]);
      this.subscription.set(subscription);
      this.entitlements.set(entitlements);
      this.usage.set(usage);
      this.statements.set(statements);
      this.modulesOnSale.set(modulesOnSale);
      this.modulesHeld.set(modulesHeld);
      this.arrears.set(arrears);
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

  protected billingUnitLabel(billingUnit: string): string {
    const key = BILLING_UNIT_KEYS[billingUnit];
    return key ? this.i18n.t(key) : billingUnit;
  }

  /** The banner's own status label — same set as {@link statusLabel}, read off a plain string. */
  protected arrearsStatusLabel(status: string): string {
    const key = STATUS_KEYS[status as SubscriptionView['status']] as MessageKey | undefined;
    return key ? this.i18n.t(key) : status;
  }

  /** True once this tenant has a live (not-ended) instance of the module. */
  protected alreadyHasModule(moduleId: string): boolean {
    return this.modulesHeld().some((held) => held.moduleId === moduleId && held.endedAt === null);
  }

  protected quantityFor(moduleId: string): number {
    return this.moduleQuantities()[moduleId] ?? 1;
  }

  protected setQuantity(moduleId: string, value: string): void {
    const parsed = Number.parseInt(value, 10);
    this.moduleQuantities.update((current) => ({
      ...current,
      [moduleId]: Number.isFinite(parsed) && parsed > 0 ? parsed : 1,
    }));
  }

  /** Gap map row 8.6: "Add" opens the confirm step instead of purchasing at once. */
  protected requestPurchase(module: SellableModuleView): void {
    if (this.purchasingModuleId() !== null || this.endTarget() !== null) {
      return;
    }
    this.moduleActionError.set(null);
    this.endNotice.set(null);
    this.purchaseTarget.set(module);
  }

  protected cancelPurchase(): void {
    if (this.purchasingModuleId() !== null) {
      // A confirm already in flight must not be abandoned mid-request --
      // ConfirmDialog itself disables Cancel/Escape/backdrop while busy, so
      // this is a defensive no-op, not a path a real click can reach.
      return;
    }
    this.purchaseTarget.set(null);
  }

  /**
   * The confirm dialog's own price/billing-unit/activates text, gap map row
   * 8.6's own ask. Quantity is folded into the price for a `PER_UNIT`
   * module rather than shown as a separate line — the operator already set
   * it on the catalogue row before clicking Add.
   */
  protected purchaseConfirmBody(module: SellableModuleView): string {
    const quantity = module.billingUnit === 'PER_UNIT' ? this.quantityFor(module.moduleId) : null;
    const unitPriceText = this.money(module.unitPrice);
    const price =
      quantity !== null && quantity > 1
        ? this.i18n.t('finance.subscription.modules.confirm.priceWithQuantity', {
            price: unitPriceText,
            quantity,
          })
        : unitPriceText;
    const activates =
      module.description?.trim() ||
      (module.featureKeys.length > 0
        ? module.featureKeys.join(', ')
        : this.i18n.t('finance.subscription.modules.confirm.activatesFallback'));
    return this.i18n.t('finance.subscription.modules.confirm.body', {
      name: module.name,
      price,
      billingUnit: this.billingUnitLabel(module.billingUnit),
      activates,
    });
  }

  protected async confirmPurchase(): Promise<void> {
    const module = this.purchaseTarget();
    if (!module || this.purchasingModuleId() !== null) {
      return;
    }
    this.moduleActionError.set(null);
    this.purchasingModuleId.set(module.moduleId);
    try {
      const quantity = module.billingUnit === 'PER_UNIT' ? this.quantityFor(module.moduleId) : null;
      await this.api.purchaseModule(this.requireTenantId(), module.moduleId, quantity);
      const [modulesHeld, entitlements] = await Promise.all([
        this.api.modulesHeld(this.requireTenantId()),
        this.api.entitlements(this.requireTenantId()),
      ]);
      this.modulesHeld.set(modulesHeld);
      this.entitlements.set(entitlements);
      this.purchaseTarget.set(null);
    } catch (error) {
      this.moduleActionError.set(this.describe(error));
      this.purchaseTarget.set(null);
    } finally {
      this.purchasingModuleId.set(null);
    }
  }

  /** ADR 0127 status note: "End" opens the confirm step instead of ending at once. */
  protected requestEnd(held: TenantModuleView): void {
    if (
      !held.endableByTenant ||
      this.endingModuleId() !== null ||
      this.purchasingModuleId() !== null ||
      this.purchaseTarget() !== null
    ) {
      return;
    }
    this.moduleActionError.set(null);
    this.endNotice.set(null);
    this.endTarget.set(held);
  }

  protected cancelEnd(): void {
    if (this.endingModuleId() !== null) {
      // ConfirmDialog disables Cancel/Escape/backdrop while busy; a defensive
      // no-op, not a path a real click can reach.
      return;
    }
    this.endTarget.set(null);
  }

  /**
   * The end dialog's consequence text. It states the rule ADR 0087/0088 set,
   * because "End" reads as "stop paying now" and nothing is prorated: a
   * recurring module is still billed for the whole month it ends in and not
   * after; a one-off was billed once when it was added and ending it refunds
   * nothing.
   */
  protected endConfirmBody(held: TenantModuleView): string {
    const key =
      held.billingUnit === 'ONE_OFF'
        ? 'finance.subscription.modules.end.confirm.bodyOneOff'
        : 'finance.subscription.modules.end.confirm.body';
    return this.i18n.t(key, { name: held.moduleName });
  }

  protected async confirmEnd(): Promise<void> {
    const held = this.endTarget();
    if (!held || this.endingModuleId() !== null) {
      return;
    }
    this.moduleActionError.set(null);
    this.endNotice.set(null);
    this.endingModuleId.set(held.tenantModuleId);
    try {
      const ended = await this.api.endModule(this.requireTenantId(), held.tenantModuleId);
      this.endNotice.set({ name: held.moduleName, period: ended.lastBilledPeriod });
    } catch (error) {
      this.moduleActionError.set(this.describe(error));
    }
    // Read what the tenant holds again either way: after an end the row is gone
    // and the entitlements changed; after a refusal (already ended, no longer
    // the tenant's to end) the list was stale and must stop offering the button.
    // A failed re-read must not turn a successful end into an error.
    await this.refreshModules().catch(() => undefined);
    this.endTarget.set(null);
    this.endingModuleId.set(null);
  }

  private async refreshModules(): Promise<void> {
    const tenantId = this.requireTenantId();
    const [modulesHeld, entitlements] = await Promise.all([
      this.api.modulesHeld(tenantId),
      this.api.entitlements(tenantId),
    ]);
    this.modulesHeld.set(modulesHeld);
    this.entitlements.set(entitlements);
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
