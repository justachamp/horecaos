import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { EntityMappingView, MigrationApi } from './migration-api';

/**
 * IA 9.2 ID mapping explorer -- legacy <-> HorecaOS identity resolution
 * across entities (ADR 0024).
 *
 * `migration.entity_mappings` is the crosswalk `ImportService` has written
 * since the module shipped; nothing served the read until now. Scope-picker,
 * the same shape 9.1's own program lookup already uses: there is no
 * "browse every scope" index, so a scope id (from 9.1 or 9.4) plus its
 * tenant and the entity type being traced are what this screen asks for.
 */
@Component({
  selector: 'app-id-mapping-explorer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './id-mapping-explorer.html',
  styleUrl: './id-mapping-explorer.css',
})
export class IdMappingExplorer {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly api = inject(MigrationApi);

  protected readonly scopeId = signal('');
  protected readonly tenantId = signal('');
  protected readonly entityType = signal('');

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly searched = signal(false);
  protected readonly mappings = signal<readonly EntityMappingView[]>([]);

  protected canSearch(): boolean {
    return (
      !this.loading() &&
      this.scopeId().trim().length > 0 &&
      this.tenantId().trim().length > 0 &&
      this.entityType().trim().length > 0
    );
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
      const page = await this.api.listEntityMappings(
        this.scopeId().trim(),
        this.tenantId().trim(),
        this.entityType().trim(),
      );
      this.mappings.set(page.items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
