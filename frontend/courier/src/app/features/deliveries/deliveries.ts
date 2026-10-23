import { ChangeDetectionStrategy, Component, OnInit, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { APP_CONFIG } from '../../core/config/app-config';
import type { CourierDelivery, DeliveryStatus } from '../../core/courier/courier-work';
import { DeliveriesStore } from '../../core/deliveries/deliveries.store';
import { formatMoney, formatTime } from '../../core/format';
import { errorMessage } from '../../core/i18n/error-message';
import { I18n } from '../../core/i18n/i18n';
import type { MessageKey } from '../../core/i18n/message-keys';
import { TPipe } from '../../core/i18n/t.pipe';
import { WorkSession } from '../../core/work/work-session';

/** A typed map, not a built key: a status the platform adds is a compile error here, not raw text on a phone. */
export const STATUS_KEYS: Readonly<Record<DeliveryStatus, MessageKey>> = {
  ASSIGNED: 'deliveries.status.ASSIGNED',
  PICKUP_PENDING: 'deliveries.status.PICKUP_PENDING',
  PICKED_UP: 'deliveries.status.PICKED_UP',
  DELIVERED: 'deliveries.status.DELIVERED',
  CANCELLED: 'deliveries.status.CANCELLED',
};

/** What the courier carries, and what they have finished, each a way into its own screen. */
@Component({
  selector: 'app-deliveries',
  imports: [TPipe, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './deliveries.html',
  styleUrl: './deliveries.css',
})
export class DeliveriesPage implements OnInit {
  protected readonly store = inject(DeliveriesStore);
  protected readonly i18n = inject(I18n);
  private readonly work = inject(WorkSession);
  private readonly config = inject(APP_CONFIG);

  protected readonly statusKeys = STATUS_KEYS;

  protected readonly errorText = computed(() =>
    errorMessage(this.i18n, this.store.error(), 'work.error'),
  );

  ngOnInit(): void {
    void this.work.begin().then(() => this.store.refresh());
  }

  protected refresh(): void {
    void this.store.refresh();
  }

  protected money(minor: number, currency: string): string {
    return formatMoney(minor, currency, this.i18n.locale());
  }

  protected time(iso: string): string {
    return formatTime(iso, this.i18n.locale(), this.config.displayTimeZone);
  }

  protected cashDue(delivery: CourierDelivery): string | null {
    return delivery.prepaid || delivery.cashDueMinor <= 0
      ? null
      : this.money(delivery.cashDueMinor, delivery.currency);
  }
}
