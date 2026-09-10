import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { EntityMappingView, MigrationApi, ScopeView } from './migration-api';
import { ScopePicker } from './scope-picker';

/**
 * IA 9.2 ID mapping explorer -- which HorecaOS record each legacy id became,
 * for one scope and entity type. Rows that could not be mapped are listed
 * too: a failed mapping is still evidence the legacy row was seen.
 */
@Component({
  selector: 'app-id-mapping-explorer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ScopePicker],
  templateUrl: './id-mapping-explorer.html',
  styleUrl: './id-mapping-explorer.css',
})
export class IdMappingExplorer {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly api = inject(MigrationApi);

  protected readonly scope = signal<ScopeView | null>(null);
  protected readonly entityType = signal('');
  protected readonly entityTypes = ['ORDER', 'CUSTOMER', 'PRODUCT', 'CATEGORY', 'BRAND', 'LOCATION'];

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly searched = signal(false);
  protected readonly mappings = signal<readonly EntityMappingView[]>([]);

  protected canSearch(): boolean {
    return !this.loading() && this.scope() !== null && this.entityType().trim().length > 0;
  }

  protected chooseScope(scope: ScopeView | null): void {
    this.scope.set(scope);
    this.searched.set(false);
    this.mappings.set([]);
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
      const page = await this.api.listEntityMappings(scope.id, scope.tenantId, this.entityType().trim().toUpperCase());
      this.mappings.set(page.items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
