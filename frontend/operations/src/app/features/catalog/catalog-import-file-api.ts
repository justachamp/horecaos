import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope, catalogPaths } from '../../core/api/catalog-paths';
import { command } from '../../core/api/idempotency';

/** `CatalogImportController.CatalogImportSubmitRequest`. */
export interface CatalogImportSubmitRequest {
  readonly catalogId: string;
  readonly fileName: string;
  readonly content: string;
}

/** `CatalogImportController.CatalogImportStatusResponse`. */
export interface CatalogImportStatus {
  readonly runId: string;
  readonly status: 'QUEUED' | 'RUNNING' | 'DRY_RUN_COMPLETE' | 'COMPLETE' | 'FAILED';
  readonly dryRun: boolean;
  readonly catalogId: string;
  readonly sourceFileName: string;
  readonly rowsTotal: number;
  readonly rowsProcessed: number;
  readonly rowsCreated: number;
  readonly rowsUpdated: number;
  readonly rowsSkipped: number;
  readonly rowsError: number;
  readonly failureReason: string | null;
}

/** `CatalogImportController.CatalogImportRowResponse`. */
export interface CatalogImportRowView {
  readonly rowNumber: number;
  readonly outcome: 'CREATED' | 'UPDATED' | 'SKIPPED' | 'ERROR';
  readonly productId: string | null;
  readonly variantId: string | null;
  readonly errorReason: string | null;
}

/**
 * Row 4.5b: a brand's catalog CSV/Excel import — template download, submit
 * (dry run and apply), status, per-row report, run history, and the filled
 * export — `CatalogImportController`.
 */
@Injectable({ providedIn: 'root' })
export class CatalogImportFileApi {
  private readonly api = inject(ApiClient);

  /** The empty template's own text -- the caller triggers the browser download. */
  template(scope: BrandScope): Promise<string> {
    return firstValueFrom(this.api.text(catalogPaths.importTemplate(scope)));
  }

  /** The same template as a real `.xlsx` workbook -- the caller triggers the browser download. */
  templateWorkbook(scope: BrandScope): Promise<Blob> {
    return firstValueFrom(this.api.blob(catalogPaths.importTemplateWorkbook(scope)));
  }

  /** The brand's catalog filled into the template's own text. */
  export(scope: BrandScope, catalogId: string): Promise<string> {
    return firstValueFrom(
      this.api.text(catalogPaths.importExport(scope), { params: { catalogId } }),
    );
  }

  async submit(
    scope: BrandScope,
    request: CatalogImportSubmitRequest,
    dryRun: boolean,
  ): Promise<string> {
    const response = await firstValueFrom(
      this.api.post<CatalogImportSubmitRequest, { runId: string }>(
        catalogPaths.imports(scope),
        command(request),
        { params: { dryRun } },
      ),
    );
    return response.runId;
  }

  async status(scope: BrandScope, runId: string): Promise<CatalogImportStatus> {
    return (
      await firstValueFrom(this.api.get<CatalogImportStatus>(catalogPaths.importRun(scope, runId)))
    ).value;
  }

  async rows(scope: BrandScope, runId: string): Promise<readonly CatalogImportRowView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CatalogImportRowView[]>(catalogPaths.importRunRows(scope, runId)),
    );
    return result.value ?? [];
  }

  async history(scope: BrandScope, limit = 50): Promise<readonly CatalogImportStatus[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CatalogImportStatus[]>(catalogPaths.imports(scope), {
        params: { limit },
      }),
    );
    return result.value ?? [];
  }
}

/** Triggers a browser download of CSV text already in hand -- no second round trip. */
export function downloadCsvText(content: string, filename: string): void {
  downloadBlob(new Blob([content], { type: 'text/csv;charset=utf-8' }), filename);
}

/** Triggers a browser download of a binary document already in hand -- `template.xlsx`'s own path, alongside {@link downloadCsvText}'s CSV one. */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}
