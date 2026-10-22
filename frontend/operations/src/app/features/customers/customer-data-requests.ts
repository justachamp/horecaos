import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { formatDate, formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CustomerAddressDraft, CustomerAddressEditor } from './customer-address-editor';
import {
  ContactSummary,
  CorrectionResult,
  CorrectionSubject,
  CustomerCorrection,
  CustomersApi,
  EVIDENCE_REFERENCE_PATTERN,
  ExportBundleLink,
  ExportRequest,
  RevealedCustomerAddress,
} from './customers-api';

const PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent';

/** What the operator states when she opens the addresses to pick the one to correct. */
const ADDRESS_PURPOSE = 'Operations console: correct a customer address against evidence';

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * A customer's data-subject requests beyond erasure (ADR 0029): a copy of her data, and corrections against evidence.
 *
 * **A copy of her data.** Asking is `customer.manage`; gathering it is `customer.pii.export`, because the producing person
 * is shown, and answers for, everything the platform holds about her: she states a purpose, the platform records it, and
 * when the tenant has published an approval policy for the export a second person decides first (the same two-person
 * sentence the legal hold uses). The bundle is one file kept for a few days; the link to it works for minutes and each
 * link asked for is recorded. What the file contains is shown here as counts per module and table, never values, with what
 * was left out on purpose.
 *
 * **Corrections.** The same edits the profile and address tabs make, made against a **reference** to the evidence (a
 * ticket, a document number: letters, digits and `. _ : / # -`, no spaces) and keeping the value they replaced. A
 * correction to what is already there records nothing and says so. The previous value is not shown here; it is part of the
 * export, where it belongs.
 */
@Component({
  selector: 'q-customer-data-requests',
  imports: [TPipe, CustomerAddressEditor],
  templateUrl: './customer-data-requests.html',
  styleUrl: './customer-data-requests.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerDataRequests {
  private readonly api = inject(CustomersApi);
  protected readonly capabilities = inject(SessionCapabilities);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<LocationScope>();
  readonly accountId = input.required<string>();
  /** The pane's contact points (kind and state, never a value): what a contact correction can name. */
  readonly contacts = input<readonly ContactSummary[]>([]);
  /** Emitted after a correction was applied, so the pane re-reads the profile it shows. */
  readonly corrected = output<void>();

  protected readonly notice = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.scope();
      this.accountId();
      untracked(() => {
        void this.loadExports();
        void this.loadCorrections();
      });
    });
  }

  private noticeFrom(error: unknown): void {
    if (error instanceof ApiError) {
      this.notice.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
    } else {
      throw error;
    }
  }

  protected formatAt(instant: string): string {
    return formatDateTime(new Date(instant), PLACEHOLDER_TIME_ZONE);
  }

  protected formatDay(instant: string): string {
    return formatDate(new Date(instant), PLACEHOLDER_TIME_ZONE);
  }

  // ------------------------------------------------------------------------ a copy of her data

  protected readonly exportRequests = signal<readonly ExportRequest[] | null>(null);
  protected readonly loadingExports = signal(false);
  protected readonly raising = signal(false);
  protected readonly actionPendingId = signal<string | null>(null);
  /** The request whose purpose form is open, and for which action ('produce' or 'link'). */
  protected readonly purposeFor = signal<{
    readonly id: string;
    readonly action: 'produce' | 'link';
  } | null>(null);
  protected readonly purpose = signal('');
  protected readonly waiting = signal<string | null>(null);
  protected readonly link = signal<{
    readonly requestId: string;
    readonly value: ExportBundleLink;
  } | null>(null);
  protected readonly manifestOpenId = signal<string | null>(null);

  private async loadExports(): Promise<void> {
    if (this.loadingExports()) {
      return;
    }
    this.loadingExports.set(true);
    try {
      this.exportRequests.set(await this.api.exportRequests(this.scope(), this.accountId()));
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.loadingExports.set(false);
    }
  }

  protected canAsk(): boolean {
    return this.capabilities.has('CUSTOMER_MANAGE');
  }

  protected canProduce(): boolean {
    return this.capabilities.has('CUSTOMER_PII_EXPORT');
  }

  protected hasPending(): boolean {
    return (this.exportRequests() ?? []).some((request) => request.status === 'PENDING');
  }

  protected statusLabel(status: string): string {
    switch (status) {
      case 'PENDING':
        return this.i18n.t('customers.data.export.status.PENDING');
      case 'COMPLETED':
        return this.i18n.t('customers.data.export.status.COMPLETED');
      case 'CANCELLED':
        return this.i18n.t('customers.data.export.status.CANCELLED');
      default:
        return status;
    }
  }

  protected viaLabel(via: string): string {
    switch (via) {
      case 'STOREFRONT':
        return this.i18n.t('customers.data.export.via.STOREFRONT');
      case 'OPERATIONS':
        return this.i18n.t('customers.data.export.via.OPERATIONS');
      default:
        return via;
    }
  }

  protected async raise(): Promise<void> {
    if (this.raising()) {
      return;
    }
    this.raising.set(true);
    this.notice.set(null);
    try {
      await this.api.requestExport(this.scope(), this.accountId());
      await this.loadExports();
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.raising.set(false);
    }
  }

  protected async withdraw(requestId: string): Promise<void> {
    if (this.actionPendingId() !== null) {
      return;
    }
    this.actionPendingId.set(requestId);
    this.notice.set(null);
    try {
      await this.api.cancelExport(this.scope(), this.accountId(), requestId);
      await this.loadExports();
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.actionPendingId.set(null);
    }
  }

  protected openPurpose(requestId: string, action: 'produce' | 'link'): void {
    this.purposeFor.set({ id: requestId, action });
    this.purpose.set('');
    this.waiting.set(null);
    this.link.set(null);
  }

  protected closePurpose(): void {
    this.purposeFor.set(null);
    this.waiting.set(null);
  }

  protected purposeValid(): boolean {
    return this.purpose().trim().length >= 3;
  }

  /** Whether the answer was "a second person has to decide", with the sentence to show; `null` for any other error. */
  private approvalSentence(error: unknown): string | null {
    if (!(error instanceof ApiError) || error.code !== 'SECOND_APPROVER_REQUIRED') {
      return null;
    }
    const id = error.problem?.['approvalRequestId'];
    const declined = error.problem?.['approvalStatus'] === 'DECLINED';
    const values = { id: typeof id === 'string' ? id : '' };
    return declined
      ? this.i18n.t('customers.data.export.produce.declined', values)
      : this.i18n.t('customers.data.export.produce.pending', values);
  }

  protected async submitPurpose(): Promise<void> {
    const target = this.purposeFor();
    const purpose = this.purpose().trim();
    if (!target || !this.purposeValid() || this.actionPendingId() !== null) {
      return;
    }
    this.actionPendingId.set(target.id);
    this.waiting.set(null);
    this.notice.set(null);
    try {
      if (target.action === 'produce') {
        await this.api.produceExport(this.scope(), this.accountId(), target.id, purpose);
        this.closePurpose();
        await this.loadExports();
      } else {
        const value = await this.api.exportBundleLink(
          this.scope(),
          this.accountId(),
          target.id,
          purpose,
        );
        this.link.set({ requestId: target.id, value });
        this.closePurpose();
      }
    } catch (error) {
      const sentence = this.approvalSentence(error);
      if (sentence) {
        this.waiting.set(sentence);
      } else {
        this.noticeFrom(error);
      }
    } finally {
      this.actionPendingId.set(null);
    }
  }

  protected toggleManifest(requestId: string): void {
    this.manifestOpenId.set(this.manifestOpenId() === requestId ? null : requestId);
  }

  // ------------------------------------------------------------------------ corrections

  protected readonly corrections = signal<readonly CustomerCorrection[] | null>(null);
  protected readonly subjects: readonly CorrectionSubject[] = [
    'DISPLAY_NAME',
    'DATE_OF_BIRTH',
    'CONTACT_POINT',
    'ADDRESS',
  ];
  protected readonly subject = signal<CorrectionSubject>('DISPLAY_NAME');
  protected readonly evidence = signal('');
  protected readonly value = signal('');
  protected readonly contactId = signal('');
  protected readonly addresses = signal<readonly RevealedCustomerAddress[] | null>(null);
  protected readonly addressId = signal('');
  protected readonly addressDraft = signal<CustomerAddressDraft | null>(null);
  protected readonly addressInstructions = signal('');
  protected readonly correcting = signal(false);
  protected readonly outcome = signal<'applied' | 'unchanged' | null>(null);

  private async loadCorrections(): Promise<void> {
    try {
      this.corrections.set(await this.api.corrections(this.scope(), this.accountId()));
    } catch (error) {
      this.noticeFrom(error);
    }
  }

  protected subjectLabel(subject: string): string {
    switch (subject) {
      case 'DISPLAY_NAME':
        return this.i18n.t('customers.data.correct.subject.DISPLAY_NAME');
      case 'DATE_OF_BIRTH':
        return this.i18n.t('customers.data.correct.subject.DATE_OF_BIRTH');
      case 'CONTACT_POINT':
        return this.i18n.t('customers.data.correct.subject.CONTACT_POINT');
      case 'ADDRESS':
        return this.i18n.t('customers.data.correct.subject.ADDRESS');
      default:
        return subject;
    }
  }

  protected contactLabel(contact: ContactSummary): string {
    return `${contact.type}${contact.isPrimary ? ' ★' : ''} (${contact.verificationStatus})`;
  }

  protected selectSubject(subject: CorrectionSubject): void {
    this.subject.set(subject);
    this.value.set('');
    this.outcome.set(null);
    this.addressId.set('');
    this.addressDraft.set(null);
    if (subject === 'ADDRESS' && this.addresses() === null) {
      void this.loadAddresses();
    }
  }

  private async loadAddresses(): Promise<void> {
    try {
      this.addresses.set(
        await this.api.revealAddresses(this.scope(), this.accountId(), ADDRESS_PURPOSE),
      );
    } catch (error) {
      this.noticeFrom(error);
    }
  }

  protected selectedAddress(): RevealedCustomerAddress | null {
    return (this.addresses() ?? []).find((address) => address.id === this.addressId()) ?? null;
  }

  protected selectAddress(id: string): void {
    this.addressId.set(id);
    this.addressDraft.set(null);
    this.addressInstructions.set(this.selectedAddress()?.deliveryInstructions ?? '');
  }

  protected onAddressDraft(draft: CustomerAddressDraft): void {
    this.addressDraft.set(draft);
  }

  protected evidenceValid(): boolean {
    return EVIDENCE_REFERENCE_PATTERN.test(this.evidence().trim());
  }

  protected evidenceInvalidShown(): boolean {
    return this.evidence().trim().length > 0 && !this.evidenceValid();
  }

  protected canSubmitCorrection(): boolean {
    if (!this.canAsk() || this.correcting() || !this.evidenceValid()) {
      return false;
    }
    switch (this.subject()) {
      case 'DISPLAY_NAME':
        return true;
      case 'DATE_OF_BIRTH':
        return ISO_DATE.test(this.value().trim());
      case 'CONTACT_POINT':
        return this.contactId() !== '' && this.value().trim().length > 0;
      case 'ADDRESS':
        return this.selectedAddress() !== null && (this.addressDraft()?.valid ?? false);
    }
  }

  protected async submitCorrection(): Promise<void> {
    if (!this.canSubmitCorrection()) {
      return;
    }
    const scope = this.scope();
    const accountId = this.accountId();
    const evidenceReference = this.evidence().trim();
    this.correcting.set(true);
    this.notice.set(null);
    this.outcome.set(null);
    try {
      let result: CorrectionResult;
      switch (this.subject()) {
        case 'DISPLAY_NAME': {
          const current = await this.api.profile(scope, accountId);
          result = await this.api.correctDisplayName(
            scope,
            accountId,
            { displayName: this.value().trim() || null, evidenceReference },
            current.value.version,
          );
          break;
        }
        case 'DATE_OF_BIRTH': {
          const current = await this.api.profile(scope, accountId);
          result = await this.api.correctDateOfBirth(
            scope,
            accountId,
            { dateOfBirth: this.value().trim(), evidenceReference },
            current.value.version,
          );
          break;
        }
        case 'CONTACT_POINT':
          result = await this.api.correctContactPoint(scope, accountId, this.contactId(), {
            value: this.value().trim(),
            evidenceReference,
          });
          break;
        case 'ADDRESS': {
          const original = this.selectedAddress();
          const draft = this.addressDraft();
          if (!original || !draft) {
            return;
          }
          result = await this.api.correctAddress(
            scope,
            accountId,
            original.id,
            {
              label: draft.label,
              fields: draft.fields,
              deliveryInstructions: this.addressInstructions().trim() || null,
              latitude: draft.latitude,
              longitude: draft.longitude,
              coordinateSource: draft.coordinateSource,
              evidenceReference,
            },
            original.version,
          );
          break;
        }
      }
      this.outcome.set(result.applied ? 'applied' : 'unchanged');
      if (result.applied) {
        this.value.set('');
        this.evidence.set('');
        this.addresses.set(null);
        this.addressId.set('');
        await this.loadCorrections();
        this.corrected.emit();
        if (this.subject() === 'ADDRESS') {
          await this.loadAddresses();
        }
      }
    } catch (error) {
      this.noticeFrom(error);
    } finally {
      this.correcting.set(false);
    }
  }
}
