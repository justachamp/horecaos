import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { asDate } from '../../core/api/dates';
import { ENTRY_CURRENCIES, formatAmount, parseAmount } from '../../core/api/money';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import {
  BILLING_PERIODS,
  CommerceApi,
  ENFORCEMENT_MODES,
  EntitlementKeyView,
  EntitlementLineRequest,
  PlanDetail,
  PlanEntitlementLineView,
  PlanVersionDetail,
} from './commerce-api';

/** One row of the draft form: an entitlement key, whether the version carries it, and its terms. */
interface DraftLine {
  readonly key: EntitlementKeyView;
  readonly included: boolean;
  readonly limit: string;
  readonly enabled: boolean;
  readonly mode: string;
  readonly overage: string;
}

const PLAN_CODE = /^[A-Z0-9][A-Z0-9_]{0,63}$/;

/**
 * IA 5.1 Plan catalog -- every plan and every version of it, drafts included.
 *
 * A version is never edited: a change is a new draft, pre-filled from the
 * newest version so only the difference has to be typed. A draft goes live
 * when somebody other than its author activates it, and after that its price
 * and entitlements are fixed for good -- the terms a tenant signed up under
 * stay readable years later.
 */
@Component({
  selector: 'app-plan-catalog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './plan-catalog.html',
  styleUrl: './plan-catalog.css',
})
export class PlanCatalog {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(CommerceApi);

  protected readonly currencies = ENTRY_CURRENCIES;
  protected readonly billingPeriods = BILLING_PERIODS;
  protected readonly modes = ENFORCEMENT_MODES;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly plans = signal<readonly PlanDetail[]>([]);
  protected readonly expanded = signal<string | null>(null);

  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  protected readonly registering = signal(false);
  protected readonly newCode = signal('');
  protected readonly newName = signal('');
  protected readonly newReason = signal('');

  protected readonly activating = signal<string | null>(null);
  protected readonly activationReason = signal('');

  protected readonly draftFor = signal<string | null>(null);
  protected readonly keysError = signal<string | null>(null);
  protected readonly currency = signal('UZS');
  protected readonly price = signal('');
  protected readonly billingPeriod = signal<string>('MONTHLY');
  protected readonly termsReference = signal('');
  protected readonly draftReason = signal('');
  protected readonly lines = signal<readonly DraftLine[]>([]);

  private keys: readonly EntitlementKeyView[] | null = null;

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.plans.set(await this.api.listPlansWithDrafts());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected toggle(planVersionId: string): void {
    this.expanded.set(this.expanded() === planVersionId ? null : planVersionId);
  }

  protected statusKey(status: string): MessageKey {
    return `planCatalog.status.${status}` as MessageKey;
  }

  protected billingKey(period: string): MessageKey {
    return `commerce.billing.${period}` as MessageKey;
  }

  protected modeKey(mode: string): MessageKey {
    return `commerce.mode.${mode}` as MessageKey;
  }

  protected resetKey(period: string): MessageKey {
    return `commerce.reset.${period}` as MessageKey;
  }

  /** A person as the table names them: "you" for the signed-in operator. */
  protected who(subject: string | null): string {
    if (subject === null || subject.length === 0) {
      return '—';
    }
    return subject === this.session.current()?.subject ? this.i18n.t('planCatalog.you') : subject;
  }

  protected value(line: PlanEntitlementLineView): string {
    if (line.enabled !== null) {
      return this.i18n.t(line.enabled ? 'commerce.on' : 'commerce.off');
    }
    return line.limit === null ? this.i18n.t('commerce.unlimited') : String(line.limit);
  }

  // ------------------------------------------------------------ register

  protected openRegister(): void {
    this.registering.set(!this.registering());
    this.newCode.set('');
    this.newName.set('');
    this.newReason.set('');
    this.actionError.set(null);
  }

  protected codeValid(): boolean {
    return PLAN_CODE.test(this.newCode());
  }

  protected canRegister(): boolean {
    return (
      !this.busy() && this.codeValid() && this.newName().trim().length > 0 && this.newReason().trim().length > 0
    );
  }

  protected async register(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canRegister()) {
      return;
    }
    await this.run(async () => {
      await this.api.createPlan(this.newCode(), this.newName().trim(), this.newReason().trim());
      this.registering.set(false);
      return this.i18n.t('planCatalog.register.done', { code: this.newCode() });
    });
  }

  // ------------------------------------------------------------ draft

  /** Opens the draft form for a plan, filled in from its newest version when it has one. */
  protected async openDraft(plan: PlanDetail): Promise<void> {
    if (this.draftFor() === plan.planId) {
      this.draftFor.set(null);
      return;
    }
    this.actionError.set(null);
    this.keysError.set(null);
    if (this.keys === null) {
      try {
        this.keys = await this.api.entitlementKeys();
      } catch (error) {
        this.keysError.set(this.i18n.describe(error as ApiError));
        this.draftFor.set(plan.planId);
        return;
      }
    }
    const newest = plan.versions[0];
    this.currency.set(newest?.price.currency ?? 'UZS');
    this.price.set(newest ? formatAmount(newest.price) : '');
    this.billingPeriod.set(newest?.billingPeriod ?? 'MONTHLY');
    this.termsReference.set(newest?.termsReference ?? '');
    this.draftReason.set('');
    this.lines.set(this.keys.map((key) => this.lineFrom(key, newest)));
    this.draftFor.set(plan.planId);
  }

  private lineFrom(key: EntitlementKeyView, newest: PlanVersionDetail | undefined): DraftLine {
    const existing = newest?.entitlements.find((line) => line.entitlementKey === key.code);
    return {
      key,
      included: existing !== undefined,
      limit: existing?.limit === null || existing?.limit === undefined ? '' : String(existing.limit),
      enabled: existing?.enabled ?? true,
      mode: existing?.enforcementMode ?? key.defaultMode,
      overage:
        existing?.overageUnitPrice === null || existing?.overageUnitPrice === undefined
          ? ''
          : formatAmount(existing.overageUnitPrice),
    };
  }

  protected updateLine(index: number, change: Partial<DraftLine>): void {
    this.lines.update((lines) => lines.map((line, at) => (at === index ? { ...line, ...change } : line)));
  }

  /** The draft as the server takes it, or null while something in it cannot be read exactly. */
  private draftRequest(): EntitlementLineRequest[] | null {
    const out: EntitlementLineRequest[] = [];
    for (const line of this.lines()) {
      if (!line.included) {
        continue;
      }
      if (!line.key.counted) {
        out.push({
          entitlementKey: line.key.code,
          enabled: line.enabled,
          enforcementMode: line.mode,
          resetPeriod: line.key.resetPeriod,
        });
        continue;
      }
      if (!/^\d+$/.test(line.limit.trim())) {
        return null;
      }
      // Priced in the version's own currency, typed the way prices are shown.
      const overage = line.overage.trim();
      const overageMinor = overage.length > 0 ? parseAmount(overage, this.currency()) : undefined;
      if (overageMinor === null) {
        return null;
      }
      out.push({
        entitlementKey: line.key.code,
        limit: Number(line.limit.trim()),
        enforcementMode: line.mode,
        resetPeriod: line.key.resetPeriod,
        overageUnitPriceMinor: overageMinor,
      });
    }
    return out;
  }

  protected priceMinor(): number | null {
    return parseAmount(this.price(), this.currency());
  }

  protected canDraft(): boolean {
    return (
      !this.busy() &&
      this.priceMinor() !== null &&
      this.draftRequest() !== null &&
      this.draftReason().trim().length > 0
    );
  }

  protected async draft(plan: PlanDetail, event: Event): Promise<void> {
    event.preventDefault();
    const entitlements = this.draftRequest();
    const priceMinor = this.priceMinor();
    if (!this.canDraft() || entitlements === null || priceMinor === null) {
      return;
    }
    const terms = this.termsReference().trim();
    await this.run(async () => {
      await this.api.draftVersion(plan.planId, {
        currency: this.currency(),
        priceMinor,
        billingPeriod: this.billingPeriod(),
        termsReference: terms.length > 0 ? terms : undefined,
        entitlements,
        reason: this.draftReason().trim(),
      });
      this.draftFor.set(null);
      return this.i18n.t('planCatalog.draft.done', { code: plan.code });
    });
  }

  // ------------------------------------------------------------ activate

  /** The four-eyes rule, shown before anyone tries: the author may not activate their own draft. */
  protected draftedByMe(version: PlanVersionDetail): boolean {
    return version.createdBy === this.session.current()?.subject;
  }

  protected openActivation(version: PlanVersionDetail): void {
    this.activating.set(this.activating() === version.planVersionId ? null : version.planVersionId);
    this.activationReason.set('');
    this.actionError.set(null);
  }

  protected async activate(plan: PlanDetail, version: PlanVersionDetail): Promise<void> {
    const reason = this.activationReason().trim();
    if (this.busy() || reason.length === 0 || this.draftedByMe(version)) {
      return;
    }
    await this.run(async () => {
      await this.api.activateVersion(version.planVersionId, reason);
      this.activating.set(null);
      return this.i18n.t('planCatalog.activate.done', { code: plan.code, version: version.versionNumber });
    });
  }

  /** Runs one write, then re-reads the catalogue so the table shows what the server now holds. */
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
