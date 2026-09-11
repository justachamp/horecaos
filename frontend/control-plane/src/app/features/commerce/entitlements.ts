import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { Money } from '../../core/api/money';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { Colleagues } from '../../shared/colleagues';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import {
  CommerceApi,
  EntitlementSnapshot,
  PlanDetail,
  ResolvedEntitlement,
  SubscriptionView,
} from './commerce-api';

/** A live plan version a tenant can be put on, named the way the price list names it. */
interface PlanChoice {
  readonly planVersionId: string;
  readonly label: string;
}

/**
 * IA 5.3 Entitlements -- one tenant's subscription and everything it is
 * entitled to, with where each value came from.
 *
 * Staff put a tenant on a plan, move its subscription through the lifecycle
 * (the server says which moves are allowed from where it is now), and grant
 * time-bounded overrides. Every one of those asks for a reason, and an
 * override also names the colleague who approved it.
 */
@Component({
  selector: 'app-entitlements',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TenantPicker, RouterLink],
  templateUrl: './entitlements.html',
  styleUrl: './entitlements.css',
})
export class Entitlements {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly commerceApi = inject(CommerceApi);
  private readonly colleagues = inject(Colleagues);
  private readonly route = inject(ActivatedRoute);
  protected readonly session = inject(SessionContextService);

  private readonly directory = inject(TenantDirectory);
  /** From a `?tenantId=` link first, else the tenant chosen last on any screen. */
  protected readonly tenantId = signal(
    this.route.snapshot.queryParamMap.get('tenantId') ?? this.directory.selected(),
  );

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly snapshot = signal<EntitlementSnapshot | null>(null);
  protected readonly subscription = signal<SubscriptionView | null>(null);
  protected readonly plans = signal<readonly PlanDetail[]>([]);
  protected readonly approvers = signal<readonly string[]>([]);

  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  protected readonly startPlan = signal('');
  protected readonly startTrialDays = signal('');
  protected readonly startTerm = signal('1');
  protected readonly startReason = signal('');

  protected readonly nextStatus = signal('');
  protected readonly suspensionReason = signal('');
  protected readonly cancelAt = signal('');
  protected readonly transitionReason = signal('');

  protected readonly overrideKey = signal('');
  protected readonly overrideLimit = signal('');
  protected readonly overrideEnabled = signal(true);
  protected readonly overrideValidUntil = signal('');
  protected readonly overrideApprovedBy = signal('');
  protected readonly overrideReason = signal('');

  /** Live versions only: a tenant is never put on a draft. */
  protected readonly planChoices = computed<readonly PlanChoice[]>(() =>
    this.plans().flatMap((plan) =>
      plan.versions
        .filter((version) => version.status === 'ACTIVE')
        .map((version) => ({
          planVersionId: version.planVersionId,
          label: `${plan.name} v${version.versionNumber} · ${this.i18n.money(version.price)}`,
        })),
    ),
  );

  /** The terms the chosen version offers, month to month always first. */
  protected readonly termChoices = computed<readonly { months: number; basisPoints: number }[]>(() => {
    const version = this.plans()
      .flatMap((plan) => plan.versions)
      .find((candidate) => candidate.planVersionId === this.startPlan());
    return [
      { months: 1, basisPoints: 0 },
      ...(version?.terms.termDiscounts ?? []).map((term) => ({ months: term.termMonths, basisPoints: term.discountBasisPoints })),
    ];
  });

  /** The chosen version's own trial, shown as what an empty trial field means. */
  protected readonly planTrialDays = computed<number | null>(
    () =>
      this.plans()
        .flatMap((plan) => plan.versions)
        .find((candidate) => candidate.planVersionId === this.startPlan())?.terms.trialDays ?? null,
  );

  /**
   * What the live subscription still owes as its activation deposit (ADR 0093),
   * or null when it owes nothing.
   *
   * The subscription carries the amount in minor units alone, because the
   * deposit is priced by the plan version and not by the subscription; the
   * currency therefore comes from the version's own price. Null as well when
   * the price list did not load, since a bare number is not money and this
   * screen already shows such a reader the plan as an id rather than a name.
   */
  protected readonly activationDepositDue = computed<Money | null>(() => {
    const live = this.subscription();
    if (live === null || live.activationDepositDueMinor === 0) {
      return null;
    }
    const currency = this.plans()
      .flatMap((plan) => plan.versions)
      .find((candidate) => candidate.planVersionId === live.planVersionId)?.price.currency;
    return currency === undefined ? null : { amountMinor: live.activationDepositDueMinor, currency };
  });

  protected readonly overrideTarget = computed<ResolvedEntitlement | null>(
    () => this.snapshot()?.entitlements.find((line) => line.entitlementKey === this.overrideKey()) ?? null,
  );

  constructor() {
    if (this.tenantId().length > 0) {
      void this.load();
    }
  }

  /** A tenant chosen in the picker: shown at once, nothing to press. */
  protected chooseTenant(tenantId: string): void {
    this.tenantId.set(tenantId);
    this.actionMessage.set(null);
    this.actionError.set(null);
    if (tenantId.length > 0) {
      void this.load();
    }
  }

  protected async load(): Promise<void> {
    const tenantId = this.tenantId().trim();
    if (tenantId.length === 0) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const [snapshot, subscription] = await Promise.all([
        this.commerceApi.getEntitlements(tenantId),
        this.commerceApi.getSubscription(tenantId),
      ]);
      this.snapshot.set(snapshot);
      this.subscription.set(subscription);
      this.nextStatus.set('');
      if (this.plans().length === 0) {
        // Names the plan a subscription is on; the screen still works without it.
        this.plans.set(await this.commerceApi.listPlansWithDrafts().catch(() => []));
      }
      if (this.approvers().length === 0 && this.session.has('COMMERCIAL_OVERRIDE_APPROVE')) {
        this.approvers.set(await this.colleagues.others());
      }
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected planName(planVersionId: string): string {
    for (const plan of this.plans()) {
      const version = plan.versions.find((candidate) => candidate.planVersionId === planVersionId);
      if (version) {
        return `${plan.name} v${version.versionNumber}`;
      }
    }
    return planVersionId;
  }

  protected statusKey(status: string): MessageKey {
    return `commerce.subscription.${status}` as MessageKey;
  }

  protected modeKey(mode: string): MessageKey {
    return `commerce.mode.${mode}` as MessageKey;
  }

  protected sourceKey(source: string): MessageKey {
    return `commerce.source.${source}` as MessageKey;
  }

  protected value(line: ResolvedEntitlement): string {
    if (line.enabled !== null) {
      return this.i18n.t(line.enabled ? 'commerce.on' : 'commerce.off');
    }
    return line.limit === null ? this.i18n.t('commerce.unlimited') : String(line.limit);
  }

  // ------------------------------------------------------------ subscribe

  protected canStart(): boolean {
    const trial = this.startTrialDays().trim();
    return (
      !this.busy() &&
      this.startPlan().length > 0 &&
      (trial.length === 0 || /^\d{1,3}$/.test(trial)) &&
      this.startReason().trim().length > 0
    );
  }

  protected async start(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canStart()) {
      return;
    }
    const trial = this.startTrialDays().trim();
    await this.run(async () => {
      await this.commerceApi.startSubscription(
        this.tenantId(),
        this.startPlan(),
        this.startReason().trim(),
        trial.length > 0 ? Number(trial) : undefined,
        Number(this.startTerm()),
      );
      this.startPlan.set('');
      this.startTrialDays.set('');
      this.startTerm.set('1');
      this.startReason.set('');
      return this.i18n.t('entitlements.start.done');
    });
  }

  /** Basis points as a percentage without trailing zeros: 1000 is "10", 250 is "2.5". */
  protected percent(basisPoints: number): string {
    return String(basisPoints / 100);
  }

  // ------------------------------------------------------------ transition

  /** Expiring and terminating end the subscription; only a new one restarts it. */
  protected isTerminal(status: string): boolean {
    return status === 'EXPIRED' || status === 'TERMINATED';
  }

  protected canTransition(): boolean {
    const next = this.nextStatus();
    return (
      !this.busy() &&
      next.length > 0 &&
      this.transitionReason().trim().length > 0 &&
      (next !== 'SUSPENDED' || this.suspensionReason().trim().length > 0) &&
      (next !== 'CANCELLATION_SCHEDULED' || this.cancelAt().length > 0)
    );
  }

  protected async transition(event: Event): Promise<void> {
    event.preventDefault();
    const live = this.subscription();
    if (!this.canTransition() || live === null) {
      return;
    }
    const next = this.nextStatus();
    await this.run(async () => {
      await this.commerceApi.transitionSubscription(this.tenantId(), {
        status: next,
        // The version read with the subscription: a colleague's move in between is refused, not overwritten.
        expectedVersion: live.version,
        suspensionReason: next === 'SUSPENDED' ? this.suspensionReason().trim() : undefined,
        cancelAt: next === 'CANCELLATION_SCHEDULED' ? new Date(this.cancelAt()).toISOString() : undefined,
        reason: this.transitionReason().trim(),
      });
      this.suspensionReason.set('');
      this.cancelAt.set('');
      this.transitionReason.set('');
      return this.i18n.t('entitlements.transition.done', { status: this.i18n.t(this.statusKey(next)) });
    });
  }

  // ------------------------------------------------------------ override

  protected canOverride(): boolean {
    const target = this.overrideTarget();
    if (target === null || this.busy()) {
      return false;
    }
    const counted = target.enabled === null;
    return (
      (!counted || /^\d+$/.test(this.overrideLimit().trim())) &&
      this.overrideValidUntil().length > 0 &&
      this.overrideApprovedBy().trim().length > 0 &&
      this.overrideReason().trim().length > 0
    );
  }

  protected async submitOverride(event: Event): Promise<void> {
    event.preventDefault();
    const target = this.overrideTarget();
    if (!this.canOverride() || target === null) {
      return;
    }
    const counted = target.enabled === null;
    await this.run(async () => {
      await this.commerceApi.grantOverride(this.tenantId(), {
        entitlementKey: target.entitlementKey,
        limit: counted ? Number(this.overrideLimit().trim()) : undefined,
        enabled: counted ? undefined : this.overrideEnabled(),
        validUntil: new Date(this.overrideValidUntil()).toISOString(),
        approvedBy: this.overrideApprovedBy().trim(),
        reason: this.overrideReason().trim(),
      });
      this.overrideKey.set('');
      this.overrideLimit.set('');
      this.overrideValidUntil.set('');
      this.overrideReason.set('');
      return this.i18n.t('entitlements.override.success');
    });
  }

  /** Runs one write, then re-reads the tenant so the screen shows what the server now holds. */
  private async run(write: () => Promise<string>): Promise<void> {
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      const message = await write();
      await this.load();
      this.actionMessage.set(message);
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
      if ((error as ApiError).code === 'STALE_VERSION') {
        await this.load();
      }
    } finally {
      this.busy.set(false);
    }
  }
}
