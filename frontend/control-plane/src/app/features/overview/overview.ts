import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { IntegrationOpsApi } from '../integration-ops/integration-ops-api';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { TenantsApi } from '../tenants/tenants-api';

/**
 * IA 1.1 Platform health -- single board: tenants live, order throughput,
 * integration failure rate, fiscalization failure rate, queue lag, SLO burn.
 *
 * Honestly partial. Real tiles: tenants by status (from the new tenant
 * directory list, IA 2.1's own backend addition) and integration failure
 * counts (`FailureOperationsController`, already platform-scoped). Named
 * gaps, not silently absent: order throughput has no platform-wide read
 * anywhere (`ReportingController` is tenant-scoped); fiscalization failure
 * rate has no cross-tenant aggregate (`FiscalDocumentController` is
 * tenant-scoped, the same gap IA 6.1's own screen names); queue lag and SLO
 * burn live in Micrometer/Prometheus, not this HTTP API. `Page` carries no
 * total count, so "tenants live" and the integration counts below are a
 * single page's worth (up to 200), named as such rather than presented as
 * an exact total.
 */
@Component({
  selector: 'app-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview.html',
  styleUrl: './overview.css',
})
export class Overview {
  protected readonly i18n = inject(I18nService);
  private readonly tenantsApi = inject(TenantsApi);
  private readonly integrationOpsApi = inject(IntegrationOpsApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly tenantsActive = signal(0);
  protected readonly tenantsProvisioning = signal(0);
  protected readonly tenantsOther = signal(0);
  protected readonly tenantsSampleTruncated = signal(false);

  protected readonly integrationPending = signal(0);
  protected readonly integrationDeadLettered = signal(0);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const [tenants, pending, deadLettered] = await Promise.all([
        this.tenantsApi.listTenants(null, 200),
        this.integrationOpsApi.outboxFailures('PENDING', 200),
        this.integrationOpsApi.outboxFailures('DEAD_LETTER', 200),
      ]);

      let active = 0;
      let provisioning = 0;
      let other = 0;
      for (const tenant of tenants.items) {
        if (tenant.status === 'ACTIVE') {
          active += 1;
        } else if (tenant.status === 'PROVISIONING') {
          provisioning += 1;
        } else {
          other += 1;
        }
      }
      this.tenantsActive.set(active);
      this.tenantsProvisioning.set(provisioning);
      this.tenantsOther.set(other);
      this.tenantsSampleTruncated.set(tenants.nextCursor !== null);

      this.integrationPending.set(pending.items.length);
      this.integrationDeadLettered.set(deadLettered.items.length);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
