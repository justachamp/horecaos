import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { CommerceApi, PlanVersionView } from './commerce-api';

/**
 * IA 5.1 Plan catalog -- plans, terms, and billing period (ADR 0021).
 *
 * `GET /control-plane/plans` is real and already used by nothing: wave 28
 * wired up 5.3 Entitlements and left the plan catalogue itself unbuilt. Only
 * activated versions are ever returned -- `PlanCatalogService`'s own
 * Javadoc says a draft is an unfinished commercial decision and showing one
 * invites somebody to quote it -- so this table is never a place to see work
 * in progress.
 *
 * Named gap: "6/12-month term discounts, trials, activation deposit" are not
 * modeled as distinct fields anywhere in `PlanVersion`/`PlanEntitlement`;
 * `billingPeriod` is the one term this build tracks.
 */
@Component({
  selector: 'app-plan-catalog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './plan-catalog.html',
  styleUrl: './plan-catalog.css',
})
export class PlanCatalog {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(CommerceApi);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly plans = signal<readonly PlanVersionView[]>([]);
  protected readonly expanded = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.plans.set(await this.api.listPlanCatalogue());
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected toggle(planVersionId: string): void {
    this.expanded.set(this.expanded() === planVersionId ? null : planVersionId);
  }
}
