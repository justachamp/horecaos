import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MigrationApi, ReconciliationResultView, RunView, ScopeView } from './migration-api';
import { runStatusKey } from './migration-labels';
import { ScopePicker } from './scope-picker';

/**
 * IA 9.3 Dual-run comparison -- what each reconciliation rule expected from
 * the legacy system against what it found in HorecaOS, by dimension, with a
 * severity and whether the difference is settled. Pick a scope, then one of
 * its reconciliation runs.
 */
@Component({
  selector: 'app-dual-run-comparison',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ScopePicker],
  templateUrl: './dual-run-comparison.html',
  styleUrl: './dual-run-comparison.css',
})
export class DualRunComparison {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(MigrationApi);

  protected readonly asDate = asDate;
  protected readonly runStatusKey = runStatusKey;
  protected readonly scope = signal<ScopeView | null>(null);
  protected readonly runs = signal<readonly RunView[]>([]);
  protected readonly runId = signal('');

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly searched = signal(false);
  protected readonly results = signal<readonly ReconciliationResultView[]>([]);

  protected canSearch(): boolean {
    return !this.loading() && this.scope() !== null && this.runId().length > 0;
  }

  /** A scope chosen: its reconciliation runs, newest first, the latest one preselected. */
  protected async chooseScope(scope: ScopeView | null): Promise<void> {
    this.scope.set(scope);
    this.runs.set([]);
    this.runId.set('');
    this.searched.set(false);
    this.results.set([]);
    if (scope === null) {
      return;
    }
    try {
      const page = await this.api.listRuns(scope);
      const reconciliations = page.items.filter((run) => run.runType === 'RECONCILIATION');
      this.runs.set(reconciliations);
      this.runId.set(reconciliations[0]?.id ?? '');
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected async search(event: Event): Promise<void> {
    event.preventDefault();
    const scope = this.scope();
    if (!this.canSearch() || scope === null) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.searched.set(true);
    try {
      const page = await this.api.listReconciliationResults(this.runId(), scope.tenantId);
      this.results.set(page.items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
