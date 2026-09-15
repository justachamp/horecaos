import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { TimeZone, formatDateTime } from '../../core/format/datetime';
import { formatMoney } from '../../core/format/money';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { QQrCode } from '../../shared/ui/qr-code';
import { describeReissueRefusal } from '../finance/finance-errors';
import {
  PAYMENT_ATTEMPT_STATUS_KEYS,
  PAYMENT_INTENT_STATUS_KEYS,
  TENDER_STATUS_KEYS,
} from '../finance/finance-labels';
import {
  OrderPaymentView,
  PaymentAttemptStatus,
  PaymentIntentStatus,
  PaymentSessionView,
  PaymentsApi,
  TenderStatus,
} from '../finance/payments/payments-api';

/** See `order-queue.ts`'s identical constant for why this is a fixed zone, not the browser's. */
const PLACEHOLDER_TIME_ZONE: TimeZone = 'Asia/Tashkent';

type ReissueKind = 'PAYMENT_LINK' | 'INVOICE_PUSH';

/**
 * Оплата — the order detail's own payment panel (row `1.2l`, wave P12).
 *
 * **Reuses `PaymentsApi` and `financeLabels` wholesale.** Everything this
 * panel reads and does already exists for the Finance section's own
 * `payments-page.ts` (`OperationsPaymentController`, ADR 0013/0046) — the
 * gap this wave closes is that an operator looking at *this* order, on the
 * order detail pane, previously had to leave for Finance and find the order
 * again by id to see its tender, its attempt history, or to re-issue its
 * checkout. Every status here is the same localized label Finance already
 * shows (`intentStatusLabel`/`attemptStatusLabel`/`tenderStatusLabel`) —
 * never the raw enum token.
 *
 * `payment` is the IA's own `payment[]` array (W05): the settlement's
 * tenders, in sequence, each with its own status and how much has been
 * refunded so far.
 */
@Component({
  selector: 'q-order-payment-panel',
  imports: [TPipe, QQrCode],
  templateUrl: './order-payment-panel.html',
  styleUrl: './order-payment-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderPaymentPanel {
  private readonly api = inject(PaymentsApi);
  protected readonly i18n = inject(I18n);

  readonly tenantId = input.required<string>();
  readonly orderId = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  protected readonly denied = signal(false);
  protected readonly payment = signal<OrderPaymentView | null>(null);

  protected readonly showReissueForm = signal(false);
  protected readonly reissueKind = signal<ReissueKind>('PAYMENT_LINK');
  protected readonly reissuePhone = signal('');
  protected readonly reissueSubmitting = signal(false);
  protected readonly reissueError = signal<string | null>(null);
  protected readonly reissueResult = signal<PaymentSessionView | null>(null);

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
      this.payment.set(await this.api.orderPayment(tenantId, orderId));
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

  protected toggleReissueForm(): void {
    this.showReissueForm.update((open) => !open);
    this.reissueError.set(null);
    this.reissueResult.set(null);
  }

  protected canReissue(): boolean {
    if (this.reissueSubmitting()) {
      return false;
    }
    return this.reissueKind() === 'PAYMENT_LINK' || /^998\d{9}$/.test(this.reissuePhone().trim());
  }

  protected async submitReissue(): Promise<void> {
    if (!this.canReissue()) {
      return;
    }
    this.reissueSubmitting.set(true);
    this.reissueError.set(null);
    this.reissueResult.set(null);
    try {
      const result = await this.api.reissuePayment(this.tenantId(), this.orderId(), {
        presentation: this.reissueKind(),
        pushRecipient:
          this.reissueKind() === 'INVOICE_PUSH' ? this.reissuePhone().trim() : undefined,
      });
      this.reissueResult.set(result);
    } catch (error) {
      if (error instanceof ApiError) {
        this.reissueError.set(
          describeReissueRefusal(error, (key, values) => this.i18n.t(key, values)),
        );
      } else {
        this.reissueError.set(this.i18n.t('error.unknown.noReference'));
      }
    } finally {
      this.reissueSubmitting.set(false);
    }
  }

  protected money(value: { amountMinor: number; currency: string } | null): string {
    if (!value) {
      return '—';
    }
    return formatMoney(value, this.i18n.locale(), { withUnit: true });
  }

  protected timestamp(value: string | null): string {
    return value ? formatDateTime(new Date(value), PLACEHOLDER_TIME_ZONE) : '—';
  }

  protected intentStatusLabel(status: PaymentIntentStatus): string {
    return this.i18n.t(PAYMENT_INTENT_STATUS_KEYS[status]);
  }

  protected attemptStatusLabel(status: PaymentAttemptStatus): string {
    return this.i18n.t(PAYMENT_ATTEMPT_STATUS_KEYS[status]);
  }

  protected tenderStatusLabel(status: TenderStatus): string {
    return this.i18n.t(TENDER_STATUS_KEYS[status]);
  }
}
