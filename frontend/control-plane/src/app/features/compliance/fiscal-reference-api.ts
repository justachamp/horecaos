import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Page } from '../../core/api/page';

const BASE = '/api/v1/control-plane/fiscal-reference/mxik';

/** MxikReferenceRow, with the two fields a dated search adds (ADR 0173). */
export interface MxikReferenceRow {
  readonly code: string;
  readonly parentCode: string | null;
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn: string | null;
  readonly defaultPackageCodes: readonly string[];
  readonly validFrom: string;
  readonly validUntil: string | null;
  readonly withdrawn: boolean | null;
  readonly replacedByCode: string | null;
}

/** FiscalReferenceController.ReferenceStatus. */
export interface ReferenceStatus {
  readonly loaded: boolean;
  readonly datasetVersion: number | null;
  readonly importedAt: string | null;
  readonly sourcePublishedOn: string | null;
  readonly validRows: number;
  readonly withdrawnRows: number;
  readonly ageDays: number | null;
  readonly maxAgeDays: number | null;
  readonly stale: boolean;
}

export type ImportMode = 'FULL_SNAPSHOT' | 'PARTIAL';

export type RunStatus =
  'VALIDATED' | 'HELD_FOR_REVIEW' | 'APPLYING' | 'APPLIED' | 'FAILED' | 'REJECTED';

/** MxikImportController.RunResponse. */
export interface ImportRun {
  readonly runId: string;
  readonly status: RunStatus;
  readonly mode: ImportMode;
  readonly mappingProfile: string;
  readonly sourceFileName: string;
  readonly sourceSha256: string;
  readonly sourcePublishedOn: string | null;
  readonly rowsTotal: number;
  readonly rowsNew: number;
  readonly rowsChanged: number;
  readonly rowsUnchanged: number;
  readonly rowsWithdrawn: number;
  readonly rowsReinstated: number;
  readonly rowsError: number;
  readonly labelsBorrowed: number;
  readonly codeLengthHistogram: Readonly<Record<string, number>>;
  readonly withdrawalThresholdPercent: number;
  readonly acknowledgedWithdrawals: number | null;
  readonly createdBy: string;
  readonly createdAt: string;
  readonly appliedBy: string | null;
  readonly appliedAt: string | null;
  readonly datasetVersion: number | null;
  readonly failureReason: string | null;
  readonly version: number;
  readonly sampleErrors: readonly {
    readonly row: number;
    readonly code: string | null;
    readonly detailCode: string;
  }[];
}

export interface ImportFinding {
  readonly kind: 'ROW_ERROR' | 'WITHDRAWN' | 'REINSTATED' | 'PACKAGE_DEFAULTS_CHANGED';
  readonly code: string | null;
  readonly detailCode: string;
}

interface UploadTicket {
  readonly objectKey: string;
  readonly url: string;
  readonly requiredHeaders: Readonly<Record<string, string>>;
  readonly expiresAt: string;
  readonly maxBytes: number;
}

/**
 * What the request body can carry decoded. The API refuses a body over 1 MiB, and Base64 costs a third on top,
 * so anything larger goes to the object store through a signed upload and is named by its key (ADR 0173).
 */
export const INLINE_LIMIT_BYTES = 700_000;

/** ADR 0173's import endpoints, platform-scoped, behind `platform.admin`. */
@Injectable({ providedIn: 'root' })
export class FiscalReferenceApi {
  private readonly api = inject(ApiClient);

  status(): Promise<ReferenceStatus> {
    return firstValueFrom(this.api.get<ReferenceStatus>(`${BASE}/status`));
  }

  async search(query: string, includeWithdrawn: boolean): Promise<readonly MxikReferenceRow[]> {
    const asOf = includeWithdrawn ? new Date().toISOString().slice(0, 10) : undefined;
    const page = await firstValueFrom(
      this.api.getPage<MxikReferenceRow>(BASE, {}, { query: { query, asOf } }),
    );
    return page.items;
  }

  recentRuns(): Promise<readonly ImportRun[]> {
    return firstValueFrom(this.api.get<readonly ImportRun[]>(`${BASE}/imports`));
  }

  /** A dry run: the list does not change. */
  async dryRun(file: File, mode: ImportMode, sourcePublishedOn: string | null): Promise<ImportRun> {
    const common = { fileName: file.name, mode, sourcePublishedOn };
    if (file.size <= INLINE_LIMIT_BYTES) {
      const contentBase64 = toBase64(new Uint8Array(await file.arrayBuffer()));
      return firstValueFrom(
        this.api.post<ImportRun>(`${BASE}/imports`, { ...common, contentBase64 }),
      );
    }
    const ticket = await firstValueFrom(this.api.post<UploadTicket>(`${BASE}/imports/uploads`, {}));
    await this.putToSignedUrl(ticket, file);
    return firstValueFrom(
      this.api.post<ImportRun>(`${BASE}/imports`, { ...common, objectKey: ticket.objectKey }),
    );
  }

  run(runId: string): Promise<ImportRun> {
    return firstValueFrom(this.api.get<ImportRun>(`${BASE}/imports/${runId}`));
  }

  findings(runId: string, after: string | null): Promise<Page<ImportFinding>> {
    return firstValueFrom(
      this.api.get<Page<ImportFinding>>(`${BASE}/imports/${runId}/findings`, {
        query: { after, limit: 100 },
      }),
    );
  }

  apply(run: ImportRun, acknowledgedWithdrawals: number | null): Promise<ImportRun> {
    return firstValueFrom(
      this.api.post<ImportRun>(
        `${BASE}/imports/${run.runId}/apply`,
        { acknowledgedWithdrawals },
        { expectedVersion: run.version },
      ),
    );
  }

  reject(run: ImportRun): Promise<ImportRun> {
    return firstValueFrom(
      this.api.post<ImportRun>(
        `${BASE}/imports/${run.runId}/reject`,
        {},
        { expectedVersion: run.version },
      ),
    );
  }

  /** PUT straight to the object store. Not the platform API: no bearer token, no idempotency key. */
  protected async putToSignedUrl(ticket: UploadTicket, file: File): Promise<void> {
    const response = await fetch(ticket.url, {
      method: 'PUT',
      headers: { ...ticket.requiredHeaders },
      body: file,
    });
    if (!response.ok) {
      throw new Error(`The object store refused the upload (${response.status})`);
    }
  }
}

/** Base64 of bytes, in chunks: String.fromCharCode(...bytes) overflows the stack on a large file. */
export function toBase64(bytes: Uint8Array): string {
  let binary = '';
  const chunk = 0x8000;
  for (let offset = 0; offset < bytes.length; offset += chunk) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + chunk));
  }
  return btoa(binary);
}
