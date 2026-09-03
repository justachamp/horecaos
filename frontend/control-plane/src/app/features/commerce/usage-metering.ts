import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { CommerceApi, UsagePeriodView } from './commerce-api';

/**
 * IA 5.4 Metering & usage -- counted units per tenant per period, reconciled
 * to invoices (ADR 0021).
 *
 * Tenant-picker, the same pattern 5.3 Entitlements and 6.1 Fiscalization
 * already use: there is no cross-tenant usage list, only a per-tenant read.
 * "Reconciled to invoices" is this row's own framing, not a claim this
 * screen makes -- IA 5.5 Invoices & wallet is unbuilt, so there is nothing
 * on the other side of that reconciliation yet. What is real is the
 * measured/adjusted/consumed breakdown per entitlement key and period,
 * which is the evidence an invoice would be defended with once one exists.
 */
@Component({
  selector: 'app-usage-metering',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './usage-metering.html',
  styleUrl: './usage-metering.css',
})
export class UsageMetering {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(CommerceApi);
  private readonly route = inject(ActivatedRoute);

  protected readonly tenantId = signal(this.route.snapshot.queryParamMap.get('tenantId') ?? '');

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly searched = signal(false);
  protected readonly periods = signal<readonly UsagePeriodView[]>([]);

  constructor() {
    if (this.tenantId().length > 0) {
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
}
