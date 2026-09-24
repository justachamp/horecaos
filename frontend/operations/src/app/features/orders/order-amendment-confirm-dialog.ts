import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { formatMoney } from '../../core/format/money';
import { Locale } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { Modal } from '../../shared/ui/modal';

/** The channels an operator can attest a customer's agreement through — free text server-side (`ConfirmAmendmentRequest.channel`, `@Size(max = 24)`), a fixed set here so the audit log reads consistently. */
export const AMENDMENT_CONFIRMATION_CHANNELS = ['PHONE', 'IN_PERSON', 'SMS'] as const;
export type AmendmentConfirmationChannel = (typeof AMENDMENT_CONFIRMATION_CHANNELS)[number];

const CHANNEL_LABEL_KEYS: Readonly<Record<AmendmentConfirmationChannel, MessageKey>> = {
  PHONE: 'orders.dialog.amendConfirm.channel.PHONE',
  IN_PERSON: 'orders.dialog.amendConfirm.channel.IN_PERSON',
  SMS: 'orders.dialog.amendConfirm.channel.SMS',
};

/**
 * The priced-delta confirmation step every repricing wave-10 command shares
 * (`ADD_LINES`, `CHANGE_LINE_QUANTITY`, `CHANGE_DELIVERY_ADDRESS`) — and the
 * one dialog `order-detail-pane.ts`'s history table's own RESOLVE action
 * reopens for an amendment still blocked on it.
 *
 * `POST .../amendments` (`OrderAmendmentsApi`'s three repricing methods)
 * always proposes with `applyImmediately: false`, so it answers with a
 * `PRICED` amendment carrying `deltaTotalMinor` and never applies on its own
 * — the only endpoint that can move it past `PRICED` is `POST
 * .../amendments/{id}/confirmation` (`OrderAmendmentsApi.confirm`), which
 * both records the operator's attestation of the customer's agreement *and*
 * applies the amendment in the same call (wave 10; see that endpoint's own
 * doc on the Java side). This dialog is the one place that call is made from.
 *
 * A blocked amendment can also be waiting on an ADR 0027 four-eyes decrease
 * approval rather than (or as well as) a customer's agreement —
 * {@link requiresApproval} renders that as a second, additional notice; the
 * confirm button still calls the identical endpoint, because no other one
 * exists to move a `PRICED` amendment forward (the approval decision itself
 * is out of this screen's scope — see the wave's own report).
 *
 * <p>ADR 0039 §3.11 ties the customer's recorded agreement to an increase
 * only, so the channel picker below only renders for `deltaMinor() > 0` — a
 * decrease-only (or zero-delta) confirm still hits the same endpoint, but
 * never asks the operator how a customer they were never meant to consult
 * "agreed". The server side of this gate is
 * `OrderAmendmentService#attestConfirmation`, which likewise only persists
 * `confirmation_attested_by`/`_at`/`_channel` for a positive delta.
 */
@Component({
  selector: 'q-order-amendment-confirm-dialog',
  imports: [TPipe, Modal],
  templateUrl: './order-amendment-confirm-dialog.html',
  styleUrl: './order-amendment-confirm-dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderAmendmentConfirmDialog {
  /** Signed, exactly as `AmendmentResponse.deltaTotalMinor` carries it — positive raises the total, negative lowers it. */
  readonly deltaMinor = input.required<number>();
  readonly currency = input.required<string>();
  readonly requiresApproval = input(false);
  readonly busy = input(false);
  readonly locale = input.required<Locale>();

  readonly confirm = output<AmendmentConfirmationChannel>();
  readonly dismiss = output<void>();

  protected readonly channels = AMENDMENT_CONFIRMATION_CHANNELS;
  protected readonly channel = signal<AmendmentConfirmationChannel>('PHONE');

  protected readonly formattedDelta = computed(() =>
    formatMoney(
      { amountMinor: Math.abs(this.deltaMinor()), currency: this.currency() },
      this.locale(),
      { withUnit: true },
    ),
  );

  protected channelLabelKey(option: AmendmentConfirmationChannel): MessageKey {
    return CHANNEL_LABEL_KEYS[option];
  }

  protected setChannel(value: string): void {
    if ((AMENDMENT_CONFIRMATION_CHANNELS as readonly string[]).includes(value)) {
      this.channel.set(value as AmendmentConfirmationChannel);
    }
  }

  protected submit(): void {
    this.confirm.emit(this.channel());
  }
}
