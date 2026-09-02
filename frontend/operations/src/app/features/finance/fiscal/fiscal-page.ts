import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ApiError } from '../../../core/api/problem-details';
import { describeApiError } from '../../orders/order-errors';
import { FiscalApi, FiscalCoverageView, FiscalDocumentView } from './fiscal-api';

/** `FiscalReasonCode.BLOCKING` (Java), mirrored so the filter offers exactly the same set. */
export const BLOCKING_REASON_CODES = [
  'PROVIDER_REPORT_OVERDUE',
  'CLASSIFICATION_MISSING',
  'MARKS_INCOMPLETE',
  'NO_FISCAL_PATH',
  'TERMINAL_OFFLINE',
] as const;

type ResolveAction = 'retry' | 'unblock';

/**
 * 8.2 Fiscal receipts — `operations-spec/finance.md` §8.2.
 *
 * The whole backend this screen reads — the blocked worklist, the coverage
 * report, retry and unblock — already existed before this wave
 * (`FiscalDocumentController`, ADR 0038). Nothing here is a new endpoint; it
 * is the console operations never had for a queue that has been computing
 * itself the whole time.
 *
 * **What is deliberately absent.** No fiscal sign, receipt URL or marking
 * code appears on this screen. The controller's own doc says why: that
 * evidence is ADR 0029-protected and reached through "the payments module's
 * authorized order-payment view, with a recorded purpose" — a view that does
 * not exist yet. A worklist needs to know a document *has* evidence
 * (`hasEvidence`), not what the evidence says, and that is all this renders.
 */
@Component({
  selector: 'q-fiscal-page',
  imports: [TPipe],
  templateUrl: './fiscal-page.html',
  styleUrl: './fiscal-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FiscalPage {
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(FiscalApi);
  protected readonly i18n = inject(I18n);

  protected readonly reasonCodes = BLOCKING_REASON_CODES;

  // -------------------------------------------------------------- blocked worklist
  protected readonly blockedLoading = signal(true);
  protected readonly blockedDenied = signal(false);
  protected readonly blockedError = signal<string | null>(null);
  protected readonly blockedWarning = signal<string | null>(null);
  protected readonly blocked = signal<readonly FiscalDocumentView[]>([]);
  protected readonly reasonFilter = signal<string>('');

  protected readonly resolvingDocumentId = signal<string | null>(null);
  protected readonly resolveAction = signal<ResolveAction>('retry');
  protected readonly resolveReason = signal('');
  protected readonly resolveSubmitting = signal(false);
  protected readonly resolveError = signal<string | null>(null);

  // -------------------------------------------------------------- coverage
  protected readonly coverageLoading = signal(false);
  protected readonly coverageError = signal<string | null>(null);
  protected readonly coverage = signal<FiscalCoverageView | null>(null);
  protected readonly coverageFrom = signal(isoDaysAgo(30));
  protected readonly coverageTo = signal(isoNow());

  constructor() {
    void this.init();
  }

  private async init(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.blockedLoading.set(false);
      this.blockedDenied.set(this.tenant.denied());
      return;
    }
    await Promise.all([this.loadBlocked(tenantId), this.loadCoverage(tenantId)]);
  }

  // -------------------------------------------------------------- blocked worklist

  protected async setReasonFilter(reasonCode: string): Promise<void> {
    this.reasonFilter.set(reasonCode);
    const tenantId = this.tenant.tenantId();
    if (tenantId) {
      await this.loadBlocked(tenantId);
    }
  }

  private async loadBlocked(tenantId: string): Promise<void> {
    this.blockedLoading.set(true);
    this.blockedError.set(null);
    try {
      const result = await this.api.blocked(tenantId, this.reasonFilter() || undefined);
      this.blocked.set(result.documents);
      this.blockedWarning.set(result.warning);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.blockedDenied.set(true);
      } else {
        this.blockedError.set(this.describe(error));
      }
    } finally {
      this.blockedLoading.set(false);
    }
  }

  protected startResolve(document: FiscalDocumentView, action: ResolveAction): void {
    this.resolvingDocumentId.set(document.documentId);
    this.resolveAction.set(action);
    this.resolveReason.set('');
    this.resolveError.set(null);
  }

  protected cancelResolve(): void {
    this.resolvingDocumentId.set(null);
  }

  protected canSubmitResolve(): boolean {
    return !this.resolveSubmitting() && this.resolveReason().trim().length > 0;
  }

  protected async submitResolve(document: FiscalDocumentView): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId || !this.canSubmitResolve()) {
      return;
    }
    this.resolveSubmitting.set(true);
    this.resolveError.set(null);
    try {
      const reason = this.resolveReason().trim();
      if (this.resolveAction() === 'retry') {
        await this.api.retry(tenantId, document.documentId, document.version, reason);
      } else {
        await this.api.unblock(tenantId, document.documentId, document.version, reason);
      }
      this.resolvingDocumentId.set(null);
      await this.loadBlocked(tenantId);
    } catch (error) {
      this.resolveError.set(this.describe(error));
    } finally {
      this.resolveSubmitting.set(false);
    }
  }

  // -------------------------------------------------------------- coverage

  protected async reloadCoverage(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (tenantId) {
      await this.loadCoverage(tenantId);
    }
  }

  private async loadCoverage(tenantId: string): Promise<void> {
    this.coverageLoading.set(true);
    this.coverageError.set(null);
    try {
      const coverage = await this.api.coverage(
        tenantId,
        new Date(this.coverageFrom()).toISOString(),
        new Date(this.coverageTo()).toISOString(),
      );
      this.coverage.set(coverage);
    } catch (error) {
      this.coverageError.set(this.describe(error));
    } finally {
      this.coverageLoading.set(false);
    }
  }

  protected sharePercent(basisPoints: number): string {
    return (basisPoints / 100).toFixed(1);
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}

function isoNow(): string {
  return new Date().toISOString().slice(0, 10);
}

function isoDaysAgo(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() - days);
  return date.toISOString().slice(0, 10);
}
