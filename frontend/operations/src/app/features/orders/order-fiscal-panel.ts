import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { FISCAL_DOCUMENT_STATUS_KEYS, isFiscalDocumentStatus } from '../finance/finance-labels';
import { FiscalApi, FiscalDocumentView, FiscalResolutionView } from '../finance/fiscal/fiscal-api';
import { describeApiError } from './order-errors';

/**
 * `FiscalDocumentService.retry`'s own refusal rule, mirrored: it throws
 * `NotRetryableException` for exactly one condition,
 * `document.state().resolved()` -- `ISSUED` or `NOT_APPLICABLE`, the two
 * states {@code FiscalDocumentState#resolved()} names. Every other state
 * (`PENDING`, `SUBMITTED`, `FAILED`, `BLOCKED`) still owes a receipt and may
 * be asked again, `BLOCKED` included -- retry and the worklist's own unblock
 * are two different doors onto the same obligation.
 */
const RETRYABLE_STATUSES: ReadonlySet<string> = new Set([
  'PENDING',
  'SUBMITTED',
  'FAILED',
  'BLOCKED',
]);

/** `PartnerFiscalizationPort.Outcome` (Java) -- what one retry produced. */
const OUTCOME_KEYS: Readonly<Record<string, MessageKey>> = {
  ISSUED: 'orders.detail.fiscal.outcome.ISSUED',
  ALREADY_ISSUED: 'orders.detail.fiscal.outcome.ALREADY_ISSUED',
  REJECTED: 'orders.detail.fiscal.outcome.REJECTED',
  UNCERTAIN: 'orders.detail.fiscal.outcome.UNCERTAIN',
  NO_PROVIDER_PATH: 'orders.detail.fiscal.outcome.NO_PROVIDER_PATH',
  NOT_WIRED: 'orders.detail.fiscal.outcome.NOT_WIRED',
};

/**
 * Фискализация — the order detail's own fiscal panel (row `1.2l`, wave P12).
 *
 * **The gap this closes.** `FiscalApi.forOrder` (`GET
 * .../fiscal/orders/{orderId}/documents`, `FiscalDocumentController.forOrder`)
 * already existed; nothing before this wave rendered it with a way to act on
 * what it shows. The Finance section's own order-lookup table
 * (`fiscal-page.html`) renders the same read-only, with no retry action at
 * all -- retry and unblock live only on that page's *blocked worklist*, which
 * a `FAILED` document that never reached `BLOCKED` does not appear on. This
 * panel is the one place a FAILED document outside that worklist can be
 * inspected and retried, from the order it actually belongs to.
 *
 * **Only retry, never unblock.** `unblocks` returns a `BLOCKED` document to
 * `PENDING` because whatever was blocking it (a missing classification, an
 * offline terminal) was fixed elsewhere; that fix does not happen on this
 * screen, so offering the button here would let an operator clear a block
 * without having actually resolved it. Retry is the one action row `1.2l`
 * names: "manual re-fiscalize".
 */
@Component({
  selector: 'q-order-fiscal-panel',
  imports: [TPipe],
  templateUrl: './order-fiscal-panel.html',
  styleUrl: './order-fiscal-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderFiscalPanel {
  private readonly api = inject(FiscalApi);
  protected readonly i18n = inject(I18n);

  readonly tenantId = input.required<string>();
  readonly orderId = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  protected readonly denied = signal(false);
  protected readonly documents = signal<readonly FiscalDocumentView[]>([]);

  protected readonly retryingDocumentId = signal<string | null>(null);
  protected readonly retryReason = signal('');
  protected readonly retrySubmitting = signal(false);
  protected readonly retryError = signal<string | null>(null);
  protected readonly retryResult = signal<FiscalResolutionView | null>(null);

  constructor() {
    effect(() => {
      const tenantId = this.tenantId();
      const orderId = this.orderId();
      void this.load(tenantId, orderId);
    });
  }

  private async load(tenantId: string, orderId: string): Promise<void> {
    this.loading.set(true);
    this.loadError.set(false);
    this.denied.set(false);
    try {
      this.documents.set(await this.api.forOrder(tenantId, orderId));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(true);
      }
    } finally {
      this.loading.set(false);
    }
  }

  protected statusLabel(status: string): string {
    return isFiscalDocumentStatus(status)
      ? this.i18n.t(FISCAL_DOCUMENT_STATUS_KEYS[status])
      : status;
  }

  protected canRetry(document: FiscalDocumentView): boolean {
    return RETRYABLE_STATUSES.has(document.status);
  }

  protected startRetry(document: FiscalDocumentView): void {
    this.retryingDocumentId.set(document.documentId);
    this.retryReason.set('');
    this.retryError.set(null);
    this.retryResult.set(null);
  }

  protected cancelRetry(): void {
    this.retryingDocumentId.set(null);
  }

  protected setRetryReason(value: string): void {
    this.retryReason.set(value);
  }

  protected canSubmitRetry(): boolean {
    return !this.retrySubmitting() && this.retryReason().trim().length > 0;
  }

  protected async submitRetry(document: FiscalDocumentView): Promise<void> {
    if (!this.canSubmitRetry()) {
      return;
    }
    this.retrySubmitting.set(true);
    this.retryError.set(null);
    this.retryResult.set(null);
    try {
      const result = await this.api.retry(
        this.tenantId(),
        document.documentId,
        document.version,
        this.retryReason().trim(),
      );
      this.retryResult.set(result);
      this.retryingDocumentId.set(null);
      await this.load(this.tenantId(), this.orderId());
    } catch (error) {
      if (error instanceof ApiError) {
        this.retryError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        this.retryError.set(this.i18n.t('error.unknown.noReference'));
      }
    } finally {
      this.retrySubmitting.set(false);
    }
  }

  protected outcomeLabel(outcome: string): string {
    const key = OUTCOME_KEYS[outcome];
    return key ? this.i18n.t(key) : outcome;
  }
}
