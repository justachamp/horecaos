import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { SessionCapabilities } from '../../../core/auth/session-capabilities';
import { Money, formatMoney } from '../../../core/format/money';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { FISCAL_DOCUMENT_STATUS_KEYS, isFiscalDocumentStatus } from '../finance-labels';
import {
  FISCAL_CORRECTION_KINDS,
  FISCAL_CORRECTION_REASONS,
  FISCAL_EVIDENCE_PURPOSES,
  FiscalCorrectionKind,
  FiscalCorrectionReason,
  FiscalEvidenceApi,
  FiscalEvidenceDocumentView,
  FiscalEvidenceLineView,
  FiscalEvidencePurpose,
  FiscalEvidenceView,
} from './fiscal-evidence-api';

/**
 * How long identifiers stay open on screen after a reveal.
 *
 * An identifier opened in full for a stated purpose is not meant to sit on a screen somebody else may
 * walk past; the server never caches the read (`Cache-Control: no-store`), and this is the same idea
 * on the client. Hiding is one click, and the next reveal is a new look with a new audit fact.
 */
export const REVEAL_WINDOW_MS = 120_000;

const PURPOSE_KEYS: Readonly<Record<FiscalEvidencePurpose, MessageKey>> = {
  TAX_INQUIRY: 'finance.fiscalEvidence.reveal.purpose.TAX_INQUIRY',
  CUSTOMER_COMPLAINT: 'finance.fiscalEvidence.reveal.purpose.CUSTOMER_COMPLAINT',
  AUDIT: 'finance.fiscalEvidence.reveal.purpose.AUDIT',
  CORRECTION: 'finance.fiscalEvidence.reveal.purpose.CORRECTION',
};

const KIND_KEYS: Readonly<Record<FiscalCorrectionKind, MessageKey>> = {
  CORRECTION: 'finance.fiscalEvidence.correction.kind.CORRECTION',
  VOID: 'finance.fiscalEvidence.correction.kind.VOID',
};

const REASON_KEYS: Readonly<Record<FiscalCorrectionReason, MessageKey>> = {
  WRONG_AMOUNT: 'finance.fiscalEvidence.correction.reason.WRONG_AMOUNT',
  WRONG_LINES: 'finance.fiscalEvidence.correction.reason.WRONG_LINES',
  WRONG_CLASSIFICATION: 'finance.fiscalEvidence.correction.reason.WRONG_CLASSIFICATION',
  WRONG_SELLER: 'finance.fiscalEvidence.correction.reason.WRONG_SELLER',
  DUPLICATE_RECEIPT: 'finance.fiscalEvidence.correction.reason.DUPLICATE_RECEIPT',
  CUSTOMER_REFUND: 'finance.fiscalEvidence.correction.reason.CUSTOMER_REFUND',
  OTHER: 'finance.fiscalEvidence.correction.reason.OTHER',
};

const DOCUMENT_TYPE_KEYS: Readonly<Record<string, MessageKey>> = {
  SALE: 'finance.fiscalEvidence.document.type.SALE',
  REFUND: 'finance.fiscalEvidence.document.type.REFUND',
  CORRECTION: 'finance.fiscalEvidence.document.type.CORRECTION',
};

const RESPONSIBILITY_KEYS: Readonly<Record<string, MessageKey>> = {
  PARTNER: 'finance.fiscalEvidence.responsibility.PARTNER',
  TERMINAL: 'finance.fiscalEvidence.responsibility.TERMINAL',
  MARKETPLACE: 'finance.fiscalEvidence.responsibility.MARKETPLACE',
  OPERATOR: 'finance.fiscalEvidence.responsibility.OPERATOR',
};

const SELLER_SOURCE_KEYS: Readonly<Record<string, MessageKey>> = {
  OPENING: 'finance.fiscalEvidence.seller.source.OPENING',
  BACKFILL: 'finance.fiscalEvidence.seller.source.BACKFILL',
  UNRESOLVED: 'finance.fiscalEvidence.seller.source.UNRESOLVED',
};

const LINE_KIND_KEYS: Readonly<Record<string, MessageKey>> = {
  DELIVERY_FEE: 'finance.fiscalEvidence.lines.kind.DELIVERY_FEE',
  SYNTHETIC: 'finance.fiscalEvidence.lines.kind.SYNTHETIC',
};

/** The words for a correction kind, for a screen that lists requests without drawing the evidence card. */
export function correctionKindKey(kind: string): MessageKey | null {
  return (KIND_KEYS as Readonly<Record<string, MessageKey>>)[kind] ?? null;
}

/** The words for a correction reason; null for a reason this client does not know, which the caller shows raw. */
export function correctionReasonKey(reason: string): MessageKey | null {
  return (REASON_KEYS as Readonly<Record<string, MessageKey>>)[reason] ?? null;
}

/** The tender-and-document pair the payments page links with: the document a tender's receipt is. */
export function documentOfTender(
  documents: readonly FiscalEvidenceDocumentView[],
  tenderId: string,
): FiscalEvidenceDocumentView | null {
  return documents.find((document) => document.tenderId === tenderId) ?? null;
}

/** The DOM id a document's evidence card carries, so a tender row can link to it. */
export function evidenceDocumentAnchor(documentId: string): string {
  return `fiscal-doc-${documentId}`;
}

/**
 * Fiscal evidence for one order (ADR 0166, gap-map row `X.2`): what was filed with the tax authority,
 * under which INN, as which lines, with the payment split how, and the number of marking codes sent.
 *
 * **It draws references to a receipt, never a receipt.** The tax authority's page is the receipt; a
 * HorecaOS rendering would be a second document that could disagree with it, and a partial one is worse
 * than none. So this shows identifiers, a link to that page and the lines exactly as they were filed
 * (stored as sent, not re-read from the catalogue), and says «not built» or «not kept» where a filing
 * has no lines rather than reconstructing them.
 *
 * **Identifiers are masked until asked for.** Opening the fiscal sign, receipt number, link and terminal
 * in full needs a separate permission and a purpose from a closed set; the server records each look.
 * The full read is held in this component only, hides itself after {@link REVEAL_WINDOW_MS}, and is
 * dropped when the order changes. A marking code is on no screen: the figure shown is a count, and the
 * server returns no code under any purpose.
 *
 * **It records and asks; the restaurant's fiscal agent issues.** A correction or void is a request
 * under a second person's approval, and HorecaOS never issues one. Once the agent has, the operator
 * attaches the agent's reference here and the original reads «corrected» or «voided» beside a linked
 * document.
 */
@Component({
  selector: 'q-fiscal-evidence',
  imports: [TPipe],
  templateUrl: './fiscal-evidence.html',
  styleUrl: './fiscal-evidence.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FiscalEvidence {
  private readonly api = inject(FiscalEvidenceApi);
  private readonly capabilities = inject(SessionCapabilities);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly i18n = inject(I18n);

  readonly tenantId = input.required<string>();
  readonly orderId = input.required<string>();

  /**
   * A host that changes a document without this component knowing (a retry on the order's fiscal panel)
   * bumps this to have the evidence read again. A value it has not seen before is the reason to reload;
   * the number itself means nothing. Not left to the host's own re-render: that destroys and rebuilds
   * this component only when a change-detection pass happens to fall between the host's two writes.
   */
  readonly refreshKey = input(0);

  /** Every time the masked read lands, so a screen that lists tenders can link each to its document. */
  readonly loaded = output<readonly FiscalEvidenceDocumentView[]>();

  protected readonly purposes = FISCAL_EVIDENCE_PURPOSES;
  protected readonly kinds = FISCAL_CORRECTION_KINDS;
  protected readonly reasons = FISCAL_CORRECTION_REASONS;

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly failed = signal(false);
  private readonly masked = signal<FiscalEvidenceView | null>(null);
  private readonly opened = signal<FiscalEvidenceView | null>(null);

  /** The read being shown: the full one while a reveal stands, otherwise the masked one. */
  protected readonly view = computed(() => this.opened() ?? this.masked());
  protected readonly revealed = computed(() => this.opened() !== null);

  protected readonly canReveal = computed(() => this.capabilities.has('FISCAL_EVIDENCE_REVEAL'));
  protected readonly canResolve = computed(() => this.capabilities.has('FISCAL_DOCUMENT_RESOLVE'));

  // ------------------------------------------------------------ reveal
  protected readonly purpose = signal<FiscalEvidencePurpose | ''>('');
  protected readonly revealing = signal(false);
  protected readonly revealError = signal<string | null>(null);
  private hideTimer: ReturnType<typeof setTimeout> | null = null;

  /** Which read is the latest asked for, so the answer to an older one is dropped and not drawn. */
  private loadTicket = 0;
  private shown = '';

  // ------------------------------------------------------------ correction request
  protected readonly requestingFor = signal<string | null>(null);
  protected readonly requestKind = signal<FiscalCorrectionKind>('CORRECTION');
  protected readonly requestReason = signal<FiscalCorrectionReason | ''>('');
  protected readonly requestNote = signal('');
  protected readonly requestSubmitting = signal(false);
  protected readonly requestError = signal<string | null>(null);
  protected readonly requestWaiting = signal<string | null>(null);

  // ------------------------------------------------------------ attaching the agent's reference
  protected readonly attachingFor = signal<string | null>(null);
  protected readonly attachFiscalSign = signal('');
  protected readonly attachReceiptNumber = signal('');
  protected readonly attachReceiptUrl = signal('');
  protected readonly attachTerminalId = signal('');
  protected readonly attachSubmitting = signal(false);
  protected readonly attachError = signal<string | null>(null);

  protected readonly notice = signal<string | null>(null);

  constructor() {
    effect(() => {
      const tenantId = this.tenantId();
      const orderId = this.orderId();
      this.refreshKey();
      untracked(() => {
        this.hide();
        this.closeForms();
        this.notice.set(null);
        const order = `${tenantId}/${orderId}`;
        if (order !== this.shown) {
          // Another order, not a re-read of this one: its predecessor's documents must not stay on the
          // screen while it loads.
          this.shown = order;
          this.masked.set(null);
        }
        void this.load(tenantId, orderId);
      });
    });
    this.destroyRef.onDestroy(() => this.clearTimer());
  }

  // ------------------------------------------------------------ loading

  private async load(tenantId: string, orderId: string): Promise<void> {
    const ticket = ++this.loadTicket;
    this.loading.set(true);
    this.denied.set(false);
    this.failed.set(false);
    try {
      const view = await this.api.forOrder(tenantId, orderId);
      if (ticket !== this.loadTicket) {
        return;
      }
      this.masked.set(view);
      this.loaded.emit(view.documents);
    } catch (error) {
      if (ticket !== this.loadTicket) {
        return;
      }
      this.masked.set(null);
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.failed.set(true);
      }
    } finally {
      if (ticket === this.loadTicket) {
        this.loading.set(false);
      }
    }
  }

  // ------------------------------------------------------------ reveal

  protected canSubmitReveal(): boolean {
    return this.purpose() !== '' && !this.revealing();
  }

  protected async reveal(): Promise<void> {
    const purpose = this.purpose();
    if (purpose === '' || this.revealing()) {
      return;
    }
    this.revealing.set(true);
    this.revealError.set(null);
    const order = this.shown;
    try {
      const view = await this.api.forOrder(this.tenantId(), this.orderId(), purpose);
      if (order !== this.shown) {
        // The order changed while this look was in flight. It was asked for about the one before.
        return;
      }
      this.opened.set(view);
      this.armTimer();
    } catch (error) {
      this.revealError.set(this.describe(error));
    } finally {
      this.revealing.set(false);
    }
  }

  protected hide(): void {
    this.clearTimer();
    this.opened.set(null);
    this.purpose.set('');
    this.revealError.set(null);
  }

  private armTimer(): void {
    this.clearTimer();
    this.hideTimer = setTimeout(() => this.hide(), REVEAL_WINDOW_MS);
  }

  private clearTimer(): void {
    if (this.hideTimer !== null) {
      clearTimeout(this.hideTimer);
      this.hideTimer = null;
    }
  }

  protected purposeLabel(purpose: FiscalEvidencePurpose): string {
    return this.i18n.t(PURPOSE_KEYS[purpose]);
  }

  // ------------------------------------------------------------ correction request

  protected canRequestCorrection(document: FiscalEvidenceDocumentView): boolean {
    return (
      this.canResolve() &&
      document.status === 'ISSUED' &&
      document.documentType === 'SALE' &&
      document.correction?.status !== 'REQUESTED'
    );
  }

  protected openRequest(document: FiscalEvidenceDocumentView): void {
    this.closeForms();
    this.requestingFor.set(document.documentId);
  }

  protected canSubmitRequest(): boolean {
    return (
      !this.requestSubmitting() &&
      this.requestReason() !== '' &&
      this.requestNote().trim().length > 0 &&
      this.requestNote().trim().length <= 255
    );
  }

  protected async submitRequest(document: FiscalEvidenceDocumentView): Promise<void> {
    const reason = this.requestReason();
    if (!this.canSubmitRequest() || reason === '') {
      return;
    }
    this.requestSubmitting.set(true);
    this.requestError.set(null);
    this.requestWaiting.set(null);
    try {
      await this.api.requestCorrection(this.tenantId(), document.documentId, document.version, {
        kind: this.requestKind(),
        reasonCode: reason,
        reasonNote: this.requestNote().trim(),
      });
      this.closeForms();
      this.notice.set(this.i18n.t('finance.fiscalEvidence.correction.requested'));
      await this.reloadAfterWrite();
    } catch (error) {
      if (error instanceof ApiError && error.code === 'SECOND_APPROVER_REQUIRED') {
        // Not a failure: the request exists and a second person has to decide it. The maker repeats
        // the same request once they have, so the form stays open with what was typed.
        this.requestWaiting.set(this.approvalSentence(error));
      } else {
        this.requestError.set(this.describe(error));
      }
    } finally {
      this.requestSubmitting.set(false);
    }
  }

  private approvalSentence(error: ApiError): string {
    const id = error.problem?.['approvalRequestId'];
    const declined = error.problem?.['approvalStatus'] === 'DECLINED';
    const values = { id: typeof id === 'string' ? id : '' };
    return declined
      ? this.i18n.t('finance.fiscalEvidence.correction.declined', values)
      : this.i18n.t('finance.fiscalEvidence.correction.pending', values);
  }

  protected kindLabel(kind: FiscalCorrectionKind): string {
    return this.i18n.t(KIND_KEYS[kind]);
  }

  protected reasonLabel(reason: FiscalCorrectionReason): string {
    return this.i18n.t(REASON_KEYS[reason]);
  }

  // ------------------------------------------------------------ attaching

  protected canAttach(document: FiscalEvidenceDocumentView): boolean {
    return this.canResolve() && document.correction?.status === 'REQUESTED';
  }

  protected openAttach(document: FiscalEvidenceDocumentView): void {
    this.closeForms();
    this.attachingFor.set(document.documentId);
  }

  /** The link is rendered as an anchor later, so only an https URL is accepted at the door. */
  protected canSubmitAttach(): boolean {
    return (
      !this.attachSubmitting() &&
      this.attachFiscalSign().trim().length > 0 &&
      this.attachReceiptNumber().trim().length > 0 &&
      /^https:\/\/\S+$/.test(this.attachReceiptUrl().trim())
    );
  }

  protected async submitAttach(document: FiscalEvidenceDocumentView): Promise<void> {
    const requestId = document.correction?.requestId;
    if (!this.canSubmitAttach() || !requestId) {
      return;
    }
    this.attachSubmitting.set(true);
    this.attachError.set(null);
    try {
      await this.api.attach(this.tenantId(), requestId, document.version, {
        fiscalSign: this.attachFiscalSign().trim(),
        receiptNumber: this.attachReceiptNumber().trim(),
        receiptUrl: this.attachReceiptUrl().trim(),
        ...(this.attachTerminalId().trim() ? { terminalId: this.attachTerminalId().trim() } : {}),
      });
      this.closeForms();
      this.notice.set(this.i18n.t('finance.fiscalEvidence.correction.attached'));
      await this.reloadAfterWrite();
    } catch (error) {
      this.attachError.set(this.describe(error));
    } finally {
      this.attachSubmitting.set(false);
    }
  }

  protected closeForms(): void {
    this.requestingFor.set(null);
    this.requestKind.set('CORRECTION');
    this.requestReason.set('');
    this.requestNote.set('');
    this.requestError.set(null);
    this.requestWaiting.set(null);
    this.attachingFor.set(null);
    this.attachFiscalSign.set('');
    this.attachReceiptNumber.set('');
    this.attachReceiptUrl.set('');
    this.attachTerminalId.set('');
    this.attachError.set(null);
  }

  /** A write changes a document's version and status, and an opened read is stale with it: drop it and re-read. */
  private async reloadAfterWrite(): Promise<void> {
    this.hide();
    await this.load(this.tenantId(), this.orderId());
  }

  // ------------------------------------------------------------ formatting

  protected statusLabel(status: string): string {
    return isFiscalDocumentStatus(status)
      ? this.i18n.t(FISCAL_DOCUMENT_STATUS_KEYS[status])
      : status;
  }

  protected typeLabel(type: string): string {
    const key = DOCUMENT_TYPE_KEYS[type];
    return key ? this.i18n.t(key) : type;
  }

  protected responsibilityLabel(responsibility: string): string {
    const key = RESPONSIBILITY_KEYS[responsibility];
    return key ? this.i18n.t(key) : responsibility;
  }

  protected sellerSourceLabel(source: string): string {
    const key = SELLER_SOURCE_KEYS[source];
    return key ? this.i18n.t(key) : source;
  }

  /** A line of kind `ITEM` needs no label; the delivery fee and the placeholder are named. */
  protected lineKindLabel(line: FiscalEvidenceLineView): string | null {
    const key = LINE_KIND_KEYS[line.kind];
    return key ? this.i18n.t(key) : null;
  }

  protected money(value: Money): string {
    return formatMoney(value, this.i18n.locale());
  }

  /** Only an https link becomes an anchor; whatever else a provider column holds stays text. */
  protected openableUrl(url: string | null): string | null {
    return url !== null && /^https:\/\/\S+$/.test(url) ? url : null;
  }

  protected anchor(documentId: string): string {
    return evidenceDocumentAnchor(documentId);
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
