import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { CurrentTenant } from '../../../core/auth/current-tenant';
import { OrderLookupApi, OrderNumberMatchView } from '../../../core/api/order-lookup-api';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ApiError } from '../../../core/api/problem-details';
import { describeApiError } from '../../orders/order-errors';
import { orderStatusLabel } from '../../orders/order-status';
import { FiscalApi, FiscalCoverageView, FiscalDocumentView } from './fiscal-api';
import { FiscalEvidence, correctionKindKey, correctionReasonKey } from './fiscal-evidence';
import { FiscalCorrectionRequestView, FiscalEvidenceApi } from './fiscal-evidence-api';

/** `FiscalReasonCode.BLOCKING` (Java), mirrored so the filter offers exactly the same set. */
export const BLOCKING_REASON_CODES = [
  'PROVIDER_REPORT_OVERDUE',
  'CLASSIFICATION_MISSING',
  'MARKS_INCOMPLETE',
  'NO_FISCAL_PATH',
  'TERMINAL_OFFLINE',
  'LINES_NOT_BUILT',
] as const;

type ResolveAction = 'retry' | 'unblock';

/** What the order lookup needs to know of an order: which one, and the number it is known by. */
type OrderIdentity = Pick<OrderNumberMatchView, 'orderId' | 'publicOrderNumber'>;

const MS_PER_DAY = 86_400_000;

/**
 * 8.2 Fiscal receipts — `operations-spec/finance.md` §8.2.
 *
 * The whole backend this screen reads — the blocked worklist, the coverage
 * report, retry and unblock — already existed before this wave
 * (`FiscalDocumentController`, ADR 0038). Nothing here is a new endpoint; it
 * is the console operations never had for a queue that has been computing
 * itself the whole time.
 *
 * **What the worklists leave out.** No fiscal sign, receipt URL or marking
 * code appears in the blocked worklist or the per-order table. That evidence
 * is ADR 0029-protected and read with a recorded purpose; a worklist needs to
 * know a document *has* evidence (`hasEvidence`), not what it says. Once an
 * order is looked up, the evidence card beneath its table (`q-fiscal-evidence`,
 * ADR 0166) shows it, masked until a purpose is given, with the INN each
 * document was filed under and the lines as filed.
 */
@Component({
  selector: 'q-fiscal-page',
  imports: [TPipe, FiscalEvidence],
  templateUrl: './fiscal-page.html',
  styleUrl: './fiscal-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FiscalPage {
  private readonly tenant = inject(CurrentTenant);
  protected readonly tenantId = this.tenant.tenantId;
  private readonly api = inject(FiscalApi);
  private readonly evidenceApi = inject(FiscalEvidenceApi);
  private readonly orderLookup = inject(OrderLookupApi);
  protected readonly i18n = inject(I18n);

  protected readonly reasonCodes = BLOCKING_REASON_CODES;

  // -------------------------------------------------------------- order lookup (per-order documents)
  protected readonly orderNumberInput = signal('');
  protected readonly orderLookupLoading = signal(false);
  protected readonly orderLookupError = signal<string | null>(null);
  protected readonly orderCandidates = signal<readonly OrderNumberMatchView[]>([]);
  protected readonly selectedOrder = signal<OrderIdentity | null>(null);
  protected readonly orderDocumentsLoading = signal(false);
  protected readonly orderDocuments = signal<readonly FiscalDocumentView[]>([]);

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

  // -------------------------------------------------------------- correction requests (ADR 0166)
  protected readonly requestsLoading = signal(true);
  protected readonly requestsDenied = signal(false);
  protected readonly requestsError = signal(false);
  protected readonly requests = signal<readonly FiscalCorrectionRequestView[]>([]);

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
    await Promise.all([
      this.loadBlocked(tenantId),
      this.loadCoverage(tenantId),
      this.loadRequests(tenantId),
    ]);
  }

  // -------------------------------------------------------------- order lookup (per-order documents)

  protected canLookupOrder(): boolean {
    return this.orderNumberInput().trim().length > 0 && !this.orderLookupLoading();
  }

  /**
   * `FiscalApi.forOrder` wraps `GET /fiscal/orders/{orderId}/documents` and
   * had no caller before this wave -- a FAILED or unissued document outside
   * the blocked worklist could not be inspected from any screen. This is
   * that caller, reached the same way `payments-page` reaches an order: by
   * the number an operator actually has, not the UUID nobody outside this
   * console has ever seen.
   */
  protected async lookupOrder(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    const number = this.orderNumberInput().trim();
    if (!tenantId || !number) {
      return;
    }
    this.orderLookupLoading.set(true);
    this.orderLookupError.set(null);
    this.orderCandidates.set([]);
    this.selectedOrder.set(null);
    this.orderDocuments.set([]);
    try {
      const matches = await this.orderLookup.byNumber(tenantId, number);
      if (matches.length === 1) {
        await this.selectOrder(matches[0]);
      } else {
        this.orderCandidates.set(matches);
      }
    } catch (error) {
      this.orderLookupError.set(this.describe(error));
    } finally {
      this.orderLookupLoading.set(false);
    }
  }

  protected async selectOrder(order: OrderIdentity): Promise<void> {
    const tenantId = this.tenant.tenantId();
    this.orderCandidates.set([]);
    this.selectedOrder.set(order);
    if (!tenantId) {
      return;
    }
    this.orderDocumentsLoading.set(true);
    try {
      this.orderDocuments.set(await this.api.forOrder(tenantId, order.orderId));
    } catch (error) {
      this.orderLookupError.set(this.describe(error));
    } finally {
      this.orderDocumentsLoading.set(false);
    }
  }

  protected orderStatusLabel(status: string): string {
    return orderStatusLabel(status, (key) => this.i18n.t(key));
  }

  /** `document.orderId` is a UUID nobody outside this console has ever seen. */
  protected documentOrderLabel(document: FiscalDocumentView): string {
    return document.publicOrderNumber ?? document.orderId;
  }

  // -------------------------------------------------------------- correction requests

  /**
   * The requests the tenant's fiscal agent has not answered, longest-waiting first (`GET
   * .../fiscal/correction-requests`). Aged and not alarmed: HorecaOS records a request and the agent
   * issues, and no legal deadline per path is enforced here, so a number of days is all it can honestly say.
   */
  private async loadRequests(tenantId: string): Promise<void> {
    this.requestsLoading.set(true);
    this.requestsDenied.set(false);
    this.requestsError.set(false);
    try {
      this.requests.set(await this.evidenceApi.openCorrections(tenantId));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.requestsDenied.set(true);
      } else {
        this.requestsError.set(true);
      }
    } finally {
      this.requestsLoading.set(false);
    }
  }

  /** Whole days a request has waited; never negative, whatever the clocks say. */
  protected requestAgeDays(request: FiscalCorrectionRequestView): number {
    return Math.max(0, Math.floor((Date.now() - Date.parse(request.requestedAt)) / MS_PER_DAY));
  }

  protected requestKindLabel(request: FiscalCorrectionRequestView): string {
    const key = correctionKindKey(request.kind);
    return key ? this.i18n.t(key) : request.kind;
  }

  protected requestReasonLabel(request: FiscalCorrectionRequestView): string {
    const key = correctionReasonKey(request.reasonCode);
    return key ? this.i18n.t(key) : request.reasonCode;
  }

  /** The number an operator knows the order by; the id only for an order that cannot be resolved. */
  protected requestOrderLabel(request: FiscalCorrectionRequestView): string {
    return request.publicOrderNumber ?? request.orderId;
  }

  /** Opens that order's documents and evidence above, where the agent's reference is attached. */
  protected async openRequestOrder(request: FiscalCorrectionRequestView): Promise<void> {
    this.orderNumberInput.set(request.publicOrderNumber ?? '');
    this.orderLookupError.set(null);
    this.orderCandidates.set([]);
    this.orderDocuments.set([]);
    await this.selectOrder({
      orderId: request.orderId,
      publicOrderNumber: this.requestOrderLabel(request),
    });
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
