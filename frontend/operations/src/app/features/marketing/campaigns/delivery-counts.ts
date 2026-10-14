import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { handedKey } from '../channel-wiring';
import { explainDeliveryReason } from '../delivery-reasons';
import { DeliveryCountsView } from '../marketing-api';

/**
 * What became of a campaign's messages, in words (rows 7.9b and 6.4a, ADR 0182): delivered, read,
 * handed to the operator, failed, rejected, pending and withheld, counted from the messages' own
 * evidence when the page was asked.
 *
 * **Not reported is not zero.** A channel's provider may report nothing after the hand-over: an SMTP
 * relay reports no delivery and no read, a Telegram bot reports neither, an SMS gateway reports a
 * delivery only where its receipt source is enabled. For such a figure the server says so
 * (`reportsDelivery`, `reportsRead`) and this prints «not reported by this channel», because a «0»
 * would read as «nobody got it» and the truth is «we cannot tell». A figure above zero is printed
 * whatever the flag says: evidence outranks a capability. A channel that gains a provider that
 * reports a read changes the flag and nothing here.
 *
 * Telegram's accepted message is «sent», not «handed to the operator»: the Bot API has no operator in
 * between, and the word is the honest one for what it says.
 */
@Component({
  selector: 'q-delivery-counts',
  imports: [TPipe],
  templateUrl: './delivery-counts.html',
  styleUrl: './delivery-counts.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeliveryCounts {
  protected readonly i18n = inject(I18n);

  readonly counts = input.required<DeliveryCountsView>();
  /** The campaign's channel, which decides the word for an accepted message. */
  readonly channel = input<string | null>(null);

  protected readonly deliveredShown = computed(
    () => this.counts().reportsDelivery || this.counts().delivered > 0,
  );
  protected readonly readShown = computed(
    () => this.counts().reportsRead || this.counts().read > 0,
  );
  protected readonly noReceiptShown = computed(
    () => this.counts().reportsDelivery || this.counts().noReceipt > 0,
  );
  protected readonly handedLabel = computed<MessageKey>(() => handedKey(this.channel()));
  protected readonly withheldReasons = computed<readonly (readonly [string, number])[]>(() =>
    Object.entries(this.counts().withheldByReason),
  );

  /** A withheld reason in words; one a newer server added is shown as a reason code. */
  protected withheldText(reason: string): string {
    return explainDeliveryReason(reason, (key, values) => this.i18n.t(key, values));
  }
}
