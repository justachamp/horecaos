import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import { BlockedDocumentResponse, FiscalApi } from './fiscal-api';

/** A blocked receipt and, on the cross-tenant board, whose it is. */
interface Row {
  readonly tenantId: string;
  readonly tenantName: string | null;
  readonly document: BlockedDocumentResponse;
}

/**
 * IA 6.1 Fiscalization operations -- fiscal receipts waiting on a person,
 * across every tenant or for one, with a retry.
 *
 * With no tenant chosen the board shows every tenant's blocked receipts,
 * longest-waiting first, each naming its tenant; choosing one narrows it and
 * shows that tenant's warning, if any. Retrying several at once calls the
 * same per-receipt retry for each, in its own tenant.
 */
@Component({
  selector: 'app-fiscalization',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TenantPicker],
  templateUrl: './fiscalization.html',
  styleUrl: './fiscalization.css',
})
export class Fiscalization {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly api = inject(FiscalApi);
  private readonly route = inject(ActivatedRoute);

  private readonly directory = inject(TenantDirectory);
  /** From a `?tenantId=` link first, else the tenant chosen last on any screen. */
  protected readonly tenantId = signal(
    this.route.snapshot.queryParamMap.get('tenantId') ?? this.directory.selected(),
  );
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly worklist = signal<readonly Row[]>([]);
  protected readonly warning = signal<string | null>(null);
  protected readonly selected = signal<ReadonlySet<string>>(new Set());
  protected readonly retrying = signal(false);
  protected readonly actionMessage = signal<string | null>(null);

  constructor() {
    void this.load();
  }

  /** A tenant chosen in the picker, or none for every tenant: shown at once, nothing to press. */
  protected chooseTenant(tenantId: string): void {
    this.tenantId.set(tenantId);
    void this.load();
  }

  protected allTenants(): boolean {
    return this.tenantId().trim().length === 0;
  }

  protected async load(): Promise<void> {
    const tenantId = this.tenantId().trim();
    this.loading.set(true);
    this.loadError.set(null);
    this.selected.set(new Set());
    try {
      if (tenantId.length === 0) {
        const rows = await this.api.blockedAcrossTenants();
        this.worklist.set(rows.map((row) => ({ tenantId: row.tenantId, tenantName: row.tenantName, document: row.document })));
        this.warning.set(null);
      } else {
        const result = await this.api.blocked(tenantId);
        this.worklist.set(result.documents.map((document) => ({ tenantId, tenantName: null, document })));
        this.warning.set(result.warning);
      }
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected toggle(documentId: string): void {
    this.selected.update((current) => {
      const next = new Set(current);
      if (next.has(documentId)) {
        next.delete(documentId);
      } else {
        next.add(documentId);
      }
      return next;
    });
  }

  protected async retrySelected(): Promise<void> {
    const targets = this.worklist().filter((row) => this.selected().has(row.document.documentId));
    if (targets.length === 0) {
      return;
    }
    this.retrying.set(true);
    this.actionMessage.set(null);
    let succeeded = 0;
    let failed = 0;
    for (const { tenantId, document } of targets) {
      try {
        await this.api.retry(
          tenantId,
          document.documentId,
          document.version,
          this.i18n.t('fiscalization.retry.reason'),
        );
        succeeded += 1;
      } catch {
        failed += 1;
      }
    }
    this.actionMessage.set(
      this.i18n.t('fiscalization.retry.result', { succeeded, failed }),
    );
    this.retrying.set(false);
    await this.load();
  }
}
