import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';

/** FiscalReferenceController's row shape (JdbcCatalogStore.MxikReferenceRow). */
interface MxikReferenceRow {
  readonly code: string;
  readonly parentCode: string | null;
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string | null;
  readonly defaultPackageCodes: readonly string[];
  readonly validFrom: string;
  readonly validUntil: string | null;
}

/**
 * IA 6.2 Fiscal reference -- ИКПУ/MXIK classifier data, browsed rather than
 * assigned (assignment is `CatalogAuthoringController`'s own
 * `fiscal-classification` endpoints, a different screen's job).
 */
@Component({
  selector: 'app-fiscal-reference',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './fiscal-reference.html',
  styleUrl: './fiscal-reference.css',
})
export class FiscalReference {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(ApiClient);

  protected readonly query = signal('');
  protected readonly loading = signal(false);
  protected readonly searched = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly results = signal<readonly MxikReferenceRow[]>([]);
  protected readonly loaded = signal<boolean | null>(null);

  constructor() {
    void this.loadStatus();
  }

  private async loadStatus(): Promise<void> {
    try {
      const status = await firstValueFrom(
        this.api.get<{ loaded: boolean }>('/api/v1/control-plane/fiscal-reference/mxik/status'),
      );
      this.loaded.set(status.loaded);
    } catch {
      this.loaded.set(null);
    }
  }

  protected async search(event: Event): Promise<void> {
    event.preventDefault();
    const query = this.query().trim();
    if (query.length < 2) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.searched.set(true);
    try {
      const page = await firstValueFrom(
        this.api.getPage<MxikReferenceRow>(
          '/api/v1/control-plane/fiscal-reference/mxik',
          {},
          { query: { query } },
        ),
      );
      this.results.set(page.items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }
}
