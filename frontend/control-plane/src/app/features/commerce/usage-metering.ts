import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { Colleagues } from '../../shared/colleagues';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import { CommerceApi, UsageDivergence, UsagePeriodView } from './commerce-api';

/**
 * IA 5.4 Metering & usage -- what one tenant used, per entitlement and
 * period, with measured and adjusted quantities kept apart.
 *
 * A figure is corrected by adding a signed adjustment with a reason and a
 * second name, never by editing what was measured. Recomputing rebuilds every
 * cached total from the ledger and names any period that disagreed -- which
 * should be none.
 */
@Component({
  selector: 'app-usage-metering',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TenantPicker],
  templateUrl: './usage-metering.html',
  styleUrl: './usage-metering.css',
})
export class UsageMetering {
  protected readonly i18n = inject(I18nService);
  protected readonly session = inject(SessionContextService);
  private readonly api = inject(CommerceApi);
  private readonly colleagues = inject(Colleagues);
  private readonly route = inject(ActivatedRoute);

  private readonly directory = inject(TenantDirectory);
  /** From a `?tenantId=` link first, else the tenant chosen last on any screen. */
  protected readonly tenantId = signal(
    this.route.snapshot.queryParamMap.get('tenantId') ?? this.directory.selected(),
  );

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly searched = signal(false);
  protected readonly periods = signal<readonly UsagePeriodView[]>([]);

  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);
  protected readonly divergences = signal<readonly UsageDivergence[] | null>(null);

  protected readonly adjusting = signal<string | null>(null);
  protected readonly delta = signal('');
  protected readonly approvedBy = signal('');
  protected readonly sourceReference = signal('');
  protected readonly reason = signal('');
  protected readonly approvers = signal<readonly string[]>([]);

  constructor() {
    if (this.tenantId().length > 0) {
      void this.load();
    }
  }

  /** A tenant chosen in the picker: shown at once, nothing to press. */
  protected chooseTenant(tenantId: string): void {
    this.tenantId.set(tenantId);
    this.divergences.set(null);
    this.actionMessage.set(null);
    this.actionError.set(null);
    this.adjusting.set(null);
    if (tenantId.length > 0) {
      void this.load();
    }
  }

  protected async load(event?: Event): Promise<void> {
    event?.preventDefault();
    const tenantId = this.tenantId().trim();
    if (tenantId.length === 0) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.searched.set(true);
    try {
      this.periods.set(await this.api.listUsage(tenantId));
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected key(period: UsagePeriodView): string {
    return `${period.entitlementKey}|${period.periodKey}`;
  }

  // ------------------------------------------------------------ recompute

  protected async rebuild(): Promise<void> {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    this.divergences.set(null);
    try {
      this.divergences.set(await this.api.rebuildUsage(this.tenantId()));
      await this.load();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }

  // ------------------------------------------------------------ adjust

  protected async openAdjust(period: UsagePeriodView): Promise<void> {
    const key = this.key(period);
    if (this.adjusting() === key) {
      this.adjusting.set(null);
      return;
    }
    this.adjusting.set(key);
    this.delta.set('');
    this.approvedBy.set('');
    this.sourceReference.set('');
    this.reason.set('');
    this.actionError.set(null);
    if (this.approvers().length === 0) {
      this.approvers.set(await this.colleagues.others());
    }
  }

  /** A signed whole number, never zero: an adjustment that changes nothing is not a correction. */
  protected deltaValue(): number | null {
    const text = this.delta().trim().replace('−', '-');
    if (!/^[+-]?\d+$/.test(text)) {
      return null;
    }
    const value = Number(text);
    return value === 0 || !Number.isSafeInteger(value) ? null : value;
  }

  protected canAdjust(): boolean {
    return (
      !this.busy() &&
      this.deltaValue() !== null &&
      this.approvedBy().trim().length > 0 &&
      this.reason().trim().length > 0
    );
  }

  protected async adjust(period: UsagePeriodView, event: Event): Promise<void> {
    event.preventDefault();
    const quantityDelta = this.deltaValue();
    if (!this.canAdjust() || quantityDelta === null) {
      return;
    }
    const source = this.sourceReference().trim();
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      await this.api.adjustUsage(this.tenantId(), {
        entitlementKey: period.entitlementKey,
        periodKey: period.periodKey,
        quantityDelta,
        sourceReference: source.length > 0 ? source : undefined,
        approvedBy: this.approvedBy().trim(),
        reason: this.reason().trim(),
      });
      this.adjusting.set(null);
      this.actionMessage.set(this.i18n.t('usageMetering.adjust.done'));
      await this.load();
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.busy.set(false);
    }
  }
}
