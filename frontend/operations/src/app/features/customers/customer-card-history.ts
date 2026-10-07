import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { formatDateTime } from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { CallRecorder } from './call-recorder';
import { LEAD_SOURCE_KEYS, LEAD_STATUS_KEYS } from './lead-labels';
import { CustomerCard, HistoryEntry, HistoryKind, RecordContactAttemptRequest } from './leads-api';

const PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent';

const KIND_KEYS: Readonly<Record<HistoryKind, MessageKey>> = {
  NOTIFICATION: 'customers.card.kind.NOTIFICATION',
  CAMPAIGN_RECEIPT: 'customers.card.kind.CAMPAIGN_RECEIPT',
  PROMO_REDEMPTION: 'customers.card.kind.PROMO_REDEMPTION',
  REVIEW: 'customers.card.kind.REVIEW',
  VOICE_CONTACT: 'customers.card.kind.VOICE_CONTACT',
};

/** The stable status codes the card's sources contribute; anything else is shown as the code it is. */
const STATUS_KEYS: Readonly<Record<string, MessageKey>> = {
  DELIVERED: 'customers.card.status.DELIVERED',
  SUPPRESSED: 'customers.card.status.SUPPRESSED',
  FAILED_TERMINAL: 'customers.card.status.FAILED_TERMINAL',
  EXPIRED: 'customers.card.status.EXPIRED',
  READY: 'customers.card.status.READY',
  CREATED: 'customers.card.status.CREATED',
  SENDING: 'customers.card.status.SENDING',
  RETRY_PENDING: 'customers.card.status.RETRY_PENDING',
  UNCERTAIN: 'customers.card.status.UNCERTAIN',
  RECONCILING: 'customers.card.status.RECONCILING',
  MANUAL_REVIEW: 'customers.card.status.MANUAL_REVIEW',
  PENDING: 'customers.card.status.PENDING',
  QUEUED: 'customers.card.status.QUEUED',
  DEFERRED: 'customers.card.status.DEFERRED',
  REFUSED: 'customers.card.status.REFUSED',
  RESERVED: 'customers.card.status.RESERVED',
  REDEEMED: 'customers.card.status.REDEEMED',
  RELEASED: 'customers.card.status.RELEASED',
  SUBMITTED: 'customers.card.status.SUBMITTED',
  CONNECTED: 'customers.card.status.CONNECTED',
  NO_ANSWER: 'customers.card.status.NO_ANSWER',
  DECLINED: 'customers.card.status.DECLINED',
  VOICEMAIL: 'customers.card.status.VOICEMAIL',
  BLOCKED: 'customers.card.status.BLOCKED',
};

const CHANNEL_KEYS: Readonly<Record<string, MessageKey>> = {
  SMS: 'customers.card.channel.SMS',
  PUSH: 'customers.card.channel.PUSH',
  EMAIL: 'customers.card.channel.EMAIL',
  MESSAGING_APP: 'customers.card.channel.MESSAGING_APP',
  PHONE: 'customers.card.channel.PHONE',
};

const DIRECTION_KEYS: Readonly<Record<string, MessageKey>> = {
  INBOUND: 'customers.card.direction.INBOUND',
  OUTBOUND: 'customers.card.direction.OUTBOUND',
};

/**
 * One guest's contacts (ADR 0111 §2, §8): what the platform has said to her, which campaigns reached her,
 * the promotions she used, the reviews she left and the calls an operator made or took — merged by time,
 * each read from the module that owns it, and her own callbacks and enquiries above them.
 *
 * Presentational: the customer pane opens the card (which is the audited act) and hands the result in,
 * so opening the pane and opening the card are one thing and this component never opens one on its own.
 * It shows codes, never prose and never a guest's own words — a review's rating without its comment, a
 * message's channel and outcome without its text — and offers the call form to an operator who may
 * record one.
 */
@Component({
  selector: 'q-customer-card-history',
  imports: [TPipe, RouterLink, CallRecorder],
  templateUrl: './customer-card-history.html',
  styleUrl: './customer-card-history.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerCardHistory {
  protected readonly i18n = inject(I18n);

  readonly card = input<CustomerCard | null>(null);
  readonly loading = input(false);
  readonly loadingMore = input(false);
  readonly error = input<string | null>(null);
  readonly canRecord = input(false);
  readonly recordBusy = input(false);

  readonly olderRequested = output<void>();
  readonly callRecorded = output<RecordContactAttemptRequest>();

  protected readonly recording = signal(false);
  protected readonly hasMore = computed(() => this.card()?.nextBefore != null);

  protected kindLabel(entry: HistoryEntry): string {
    return this.i18n.t(KIND_KEYS[entry.kind]);
  }

  protected statusLabel(code: string): string {
    const key = STATUS_KEYS[code];
    return key ? this.i18n.t(key) : code;
  }

  protected channelLabel(channel: string | null): string | null {
    if (channel === null) {
      return null;
    }
    const key = CHANNEL_KEYS[channel];
    return key ? this.i18n.t(key) : channel;
  }

  /** A voice entry carries its direction in {@code label}; every other kind's label is a name. */
  protected labelOf(entry: HistoryEntry): string | null {
    if (entry.kind === 'VOICE_CONTACT' && entry.label !== null) {
      const key = DIRECTION_KEYS[entry.label];
      return key ? this.i18n.t(key) : entry.label;
    }
    return entry.label;
  }

  protected leadStatus(status: keyof typeof LEAD_STATUS_KEYS): string {
    return this.i18n.t(LEAD_STATUS_KEYS[status]);
  }

  protected leadSource(source: keyof typeof LEAD_SOURCE_KEYS): string {
    return this.i18n.t(LEAD_SOURCE_KEYS[source]);
  }

  protected formatWhen(instant: string): string {
    return formatDateTime(new Date(instant), PLACEHOLDER_TIME_ZONE);
  }

  protected record(request: RecordContactAttemptRequest): void {
    this.callRecorded.emit(request);
    this.recording.set(false);
  }
}
