import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { BlockedDocumentResponse, FiscalApi } from './fiscal-api';

/**
 * IA 6.1 Fiscalization operations -- fiscal receipt failures with an
 * operator-visible retry path (ADR 0038; the IA row's own note that "HorecaOS
 * has no ADR for it" is stale -- ADR 0038 covers the whole document
 * lifecycle, built in wave 8/V0039).
 *
 * One tenant at a time: `FiscalDocumentController` has no cross-tenant
 * aggregate (see `fiscal-api.ts`'s own note), so this is the same
 * tenant-picker pattern IA 5.3 uses, not the single always-on board the IA
 * prose imagines. Bulk retry is client-orchestrated -- selecting several rows
 * and calling the same per-document retry endpoint for each -- because the
 * mutation itself already exists and is well-tested; only the picking is new.
 */
@Component({
  selector: 'app-fiscalization',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './fiscalization.html',
  styleUrl: './fiscalization.css',
})
export class Fiscalization {
  protected readonly i18n = inject(I18nService);
  protected readonly asDate = asDate;
  private readonly api = inject(FiscalApi);
  private readonly route = inject(ActivatedRoute);

  protected readonly tenantId = signal(this.route.snapshot.queryParamMap.get('tenantId') ?? '');
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly worklist = signal<readonly BlockedDocumentResponse[]>([]);
  protected readonly warning = signal<string | null>(null);
  protected readonly selected = signal<ReadonlySet<string>>(new Set());
  protected readonly retrying = signal(false);
  protected readonly actionMessage = signal<string | null>(null);

  constructor() {
    if (this.tenantId().length > 0) {
      void this.load();
    }
  }

  protected async load(): Promise<void> {
    const tenantId = this.tenantId().trim();
    if (tenantId.length === 0) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.selected.set(new Set());
    try {
      const result = await this.api.blocked(tenantId);
      this.worklist.set(result.documents);
      this.warning.set(result.warning);
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
    const tenantId = this.tenantId().trim();
    const targets = this.worklist().filter((document) => this.selected().has(document.documentId));
    if (tenantId.length === 0 || targets.length === 0) {
      return;
    }
    this.retrying.set(true);
    this.actionMessage.set(null);
    let succeeded = 0;
    let failed = 0;
    for (const document of targets) {
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
