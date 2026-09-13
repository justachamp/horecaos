import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import {
  BatchImportResponse,
  BatchImportZoneRow,
  DeliveryZonesApi,
  RowOutcomeResponse,
} from './delivery-zones-api';
import { Toasts } from '../../shared/ui/toast';

/**
 * IA 3.6c — Bulk geozone upload.
 *
 * **Built**: a file picker over a JSON export of legacy zone geometry, a
 * row-level preview before anything is sent, a dry run that runs every
 * validity/area/region-bbox check a real import would and reports the
 * outcome without persisting, and the same batch committed for real —
 * landing every accepted row as a new zone's `DRAFT` version, exactly like
 * drawing one by hand.
 *
 * **Not built, honestly, and deliberately**: activation from this screen.
 * ADR 0037 requires imported geometry to be rendered on a map beside its
 * source before it goes live, because a coordinate-order mistake produces a
 * polygon no containment test can tell from a correct one — only looking at
 * it can. That map is `X.4` and is not built; activating an imported zone
 * stays the ordinary per-zone `DELIVERY_ZONE_ACTIVATE` flow on the Zones
 * tab, with no shortcut offered here. A row whose warnings include
 * `OUTSIDE_REGION_BBOX` is exactly the case a coordinate transposition
 * produces, named on the report rather than silently accepted.
 *
 * **This is not `q-import-wizard`.** `P26` owns that shared component
 * (FileDropzone, dry-run diff, JobProgress, ResultSummary) and it is not
 * merged yet, so this page is the minimum local equivalent — a plain file
 * input and two tables — built to be replaced by the shared wizard without
 * changing the API calls underneath it.
 */
@Component({
  selector: 'q-geozone-batch-import-page',
  imports: [TPipe],
  templateUrl: './geozone-batch-import-page.html',
  styleUrl: './geozone-batch-import-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GeozoneBatchImportPage implements OnInit {
  private readonly api = inject(DeliveryZonesApi);
  private readonly brand = inject(CurrentBrand);
  private readonly toasts = inject(Toasts);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);

  protected readonly fileName = signal<string | null>(null);
  protected readonly parseError = signal<string | null>(null);
  protected readonly rows = signal<readonly BatchImportZoneRow[]>([]);

  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly report = signal<BatchImportResponse | null>(null);

  async ngOnInit(): Promise<void> {
    await this.brand.ensureLoaded();
    this.denied.set(this.brand.denied());
    this.loading.set(false);
  }

  protected async onFileSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    this.fileName.set(file.name);
    this.report.set(null);
    this.actionError.set(null);
    try {
      const text = await file.text();
      const parsed = JSON.parse(text) as unknown;
      this.rows.set(parseRows(parsed));
      this.parseError.set(null);
    } catch {
      this.rows.set([]);
      this.parseError.set(this.i18n.t('delivery.zoneImport.parseError'));
    } finally {
      input.value = '';
    }
  }

  protected clear(): void {
    this.fileName.set(null);
    this.rows.set([]);
    this.parseError.set(null);
    this.report.set(null);
    this.actionError.set(null);
  }

  protected async dryRun(): Promise<void> {
    await this.submit(true);
  }

  protected async commit(): Promise<void> {
    await this.submit(false);
  }

  private async submit(dryRun: boolean): Promise<void> {
    const scope = this.brand.scope();
    const rows = this.rows();
    if (!scope || rows.length === 0) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    try {
      const result = await this.api.importBatch(scope, { dryRun, rows });
      this.report.set(result);
      if (!dryRun) {
        this.toasts.show({
          message: this.i18n.t('delivery.zoneImport.committed', { count: result.accepted }),
          tone: result.rejected === 0 ? 'success' : 'info',
        });
      }
    } catch (error) {
      this.actionError.set(this.describe(error));
    } finally {
      this.busy.set(false);
    }
  }

  protected rowOutcomeFor(row: BatchImportZoneRow): RowOutcomeResponse | null {
    const report = this.report();
    if (!report) {
      return null;
    }
    return report.rows.find((outcome) => outcome.externalRef === row.externalRef) ?? null;
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}

/**
 * Validates the uploaded JSON is an array of rows carrying at least the
 * fields the batch endpoint requires, defaulting `role` to `DELIVERY` (the
 * shape almost every legacy export is) when the source file omits it.
 */
function parseRows(parsed: unknown): readonly BatchImportZoneRow[] {
  if (!Array.isArray(parsed)) {
    throw new Error('Expected a JSON array of rows');
  }
  return parsed.map((entry, index) => {
    if (typeof entry !== 'object' || entry === null) {
      throw new Error(`Row ${index} is not an object`);
    }
    const row = entry as Record<string, unknown>;
    const externalRef = requireString(row, 'externalRef', index);
    const code = requireString(row, 'code', index);
    const geoJson = requireString(row, 'geoJson', index);
    const currency = requireString(row, 'currency', index);
    return {
      externalRef,
      role: row['role'] === 'CATCHMENT' ? 'CATCHMENT' : 'DELIVERY',
      code,
      displayNameRu: optionalString(row, 'displayNameRu') ?? code,
      displayNameUz: optionalString(row, 'displayNameUz') ?? code,
      displayNameEn: optionalString(row, 'displayNameEn') ?? code,
      regionId: optionalString(row, 'regionId'),
      priority: typeof row['priority'] === 'number' ? row['priority'] : 0,
      currency,
      deliveryTariffId: optionalString(row, 'deliveryTariffId'),
      freeDeliveryFromMinor: optionalNumber(row, 'freeDeliveryFromMinor'),
      minBasketMinor: optionalNumber(row, 'minBasketMinor'),
      geoJson,
    } satisfies BatchImportZoneRow;
  });
}

function requireString(row: Record<string, unknown>, field: string, index: number): string {
  const value = row[field];
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`Row ${index} is missing "${field}"`);
  }
  return value;
}

function optionalString(row: Record<string, unknown>, field: string): string | undefined {
  const value = row[field];
  return typeof value === 'string' && value.trim() !== '' ? value : undefined;
}

function optionalNumber(row: Record<string, unknown>, field: string): number | undefined {
  const value = row[field];
  return typeof value === 'number' ? value : undefined;
}
