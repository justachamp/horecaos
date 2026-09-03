import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MigrationApi, ReconciliationResultView } from './migration-api';

/**
 * IA 9.3 Dual-run comparison -- legacy vs. HorecaOS output diffing during
 * cutover (ADR 0024).
 *
 * `migration.reconciliation_results` is what `ReconciliationService`
 * evaluates every rule into: what a rule expected from the legacy source
 * against what it found in HorecaOS, by dimension, with a severity and a
 * settlement state. The cutover gate has only ever asked "is there an open
 * critical difference" (`MigrationReconciliationStore.hasOpenCritical`); this
 * is the first screen that shows the comparison itself. Run-picker, the same
 * shape 9.2 takes: there is no "list every run" index, so a run id (from the
 * run a reconciliation was started under) plus its tenant are what this
 * screen asks for.
 */
@Component({
  selector: 'app-dual-run-comparison',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dual-run-comparison.html',
  styleUrl: './dual-run-comparison.css',
})
export class DualRunComparison {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(MigrationApi);

  protected readonly runId = signal('');
  protected readonly tenantId = signal('');

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly searched = signal(false);
  protected readonly results = signal<readonly ReconciliationResultView[]>([]);

  protected canSearch(): boolean {
    return !this.loading() && this.runId().trim().length > 0 && this.tenantId().trim().length > 0;
  }

  protected async search(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSearch()) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.searched.set(true);
    try {
      const page = await this.api.listReconciliationResults(this.runId().trim(), this.tenantId().trim());
      this.results.set(page.items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
