import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';

import { APP_CONFIG } from '../../core/config/app-config';
import type { CustomerLocation } from '../../core/courier/courier-work';
import { CourierProfileStore } from '../../core/courier/courier-profile.store';
import { DeliveriesStore } from '../../core/deliveries/deliveries.store';
import { stepMessage, type StepMessage } from '../../core/deliveries/step-message';
import type { StepOutcome } from '../../core/deliveries/step-outcome';
import { formatDistance, formatMoney, formatTime, parseMinorUnits } from '../../core/format';
import { I18n } from '../../core/i18n/i18n';
import type { MessageKey } from '../../core/i18n/message-keys';
import { TPipe } from '../../core/i18n/t.pipe';
import { WorkSession } from '../../core/work/work-session';
import { CustomerLocationCard } from '../work/customer-location';
import { geoUri } from '../work/geo-link';
import { StepNotice } from '../work/step-notice';
import { STATUS_KEYS } from './deliveries';

type Step = 'arrive' | 'pickup' | 'handover';

const DONE_KEYS: Readonly<Record<Step, MessageKey>> = {
  arrive: 'deliveries.done.arrive',
  pickup: 'deliveries.done.pickup',
  handover: 'deliveries.done.handover',
};

/**
 * One delivery, and the steps that move it: arrive, pick up, confirm the cash, hand over.
 *
 * Every step sorts the platform's answer into the same branches (`StepOutcome`): worked (with the
 * soft gate's checks, told as a warning, not a failure), refused by a hard gate (`409 GATE_FAILED`,
 * the failed checks named), refused by a rule (`422`, a sentence for its reason), changed under the
 * courier (reload), or not delivered at all. The customer's address is a deliberate, audited tap,
 * held in this screen's state only and gone when it is hidden or the screen is.
 */
@Component({
  selector: 'app-delivery',
  imports: [TPipe, RouterLink, CustomerLocationCard, StepNotice],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './delivery.html',
  styleUrl: './delivery.css',
})
export class DeliveryPage implements OnInit {
  /** The shipment id, bound from the route. */
  readonly id = input.required<string>();

  protected readonly store = inject(DeliveriesStore);
  protected readonly profile = inject(CourierProfileStore);
  protected readonly i18n = inject(I18n);
  private readonly work = inject(WorkSession);
  private readonly config = inject(APP_CONFIG);
  private readonly sanitizer = inject(DomSanitizer);

  protected readonly statusKeys = STATUS_KEYS;
  protected readonly currency = this.config.currency;

  protected readonly missing = signal(false);
  protected readonly door = signal<CustomerLocation | null>(null);
  protected readonly cashText = signal('');
  protected readonly cashInvalid = signal(false);
  private readonly lastStep = signal<{ step: Step; outcome: StepOutcome } | null>(null);
  private readonly doorRefusal = signal<StepOutcome | null>(null);

  protected readonly delivery = computed(() => this.store.byId(this.id()));
  protected readonly busy = computed(() => this.store.busy() !== null);

  /** The cash must be confirmed before the handover when the tenant requires it and cash is due. */
  protected readonly awaitingCash = computed(() => {
    const d = this.delivery();
    return (
      d !== null &&
      d.paymentConfirmationRequired &&
      d.cashDueMinor > 0 &&
      d.paymentConfirmedAt === null
    );
  });

  protected readonly carrying = computed(() => {
    const status = this.delivery()?.status;
    return status === 'ASSIGNED' || status === 'PICKUP_PENDING' || status === 'PICKED_UP';
  });

  protected readonly message = computed<StepMessage | null>(() => {
    const last = this.lastStep();
    if (last === null) {
      return this.doorRefusal() === null ? null : stepMessage(this.i18n, this.doorRefusal());
    }
    return stepMessage(this.i18n, last.outcome);
  });

  /** The plain sentence a worked step earns ("Picked up. Take it to the customer."), apart from any checks. */
  protected readonly doneKey = computed<MessageKey | null>(() => {
    const last = this.lastStep();
    return last !== null && last.outcome.kind === 'done' ? DONE_KEYS[last.step] : null;
  });

  async ngOnInit(): Promise<void> {
    await this.work.begin();
    await this.profile.ensureLoaded();
    const found = await this.store.load(this.id());
    this.missing.set(found === null && this.store.byId(this.id()) === null);
  }

  protected money(minor: number, currency: string): string {
    return formatMoney(minor, currency, this.i18n.locale());
  }

  protected distance(meters: number): string {
    return formatDistance(meters, this.i18n.locale());
  }

  protected time(iso: string): string {
    return formatTime(iso, this.i18n.locale(), this.config.displayTimeZone);
  }

  protected pickupLink() {
    const pickup = this.delivery()?.pickup;
    return pickup ? geoUri(this.sanitizer, pickup.latitude, pickup.longitude) : null;
  }

  protected async arrive(): Promise<void> {
    await this.run('arrive', (d) => this.store.arrive(d));
  }

  protected async pickUp(): Promise<void> {
    await this.run('pickup', (d) => this.store.pickUp(d));
  }

  protected async handOver(): Promise<void> {
    await this.run('handover', (d) => this.store.handOver(d));
  }

  protected typeCash(event: Event): void {
    this.cashText.set((event.target as HTMLInputElement).value);
    this.cashInvalid.set(false);
  }

  protected async confirmCash(): Promise<void> {
    const d = this.delivery();
    if (d === null) {
      return;
    }
    const minor = parseMinorUnits(this.cashText(), d.currency);
    if (minor === null) {
      this.cashInvalid.set(true);
      return;
    }
    this.lastStep.set(null);
    const outcome = await this.store.confirmPayment(d, minor);
    this.lastStep.set(outcome.kind === 'done' ? null : { step: 'handover', outcome });
  }

  protected async reveal(): Promise<void> {
    const d = this.delivery();
    if (d === null) {
      return;
    }
    this.doorRefusal.set(null);
    const answer = await this.store.reveal(d);
    if (answer.ok) {
      this.door.set(answer.location);
    } else {
      this.doorRefusal.set(answer.outcome);
      this.lastStep.set(null);
    }
  }

  protected hide(): void {
    this.door.set(null);
  }

  private async run(
    step: Step,
    action: (delivery: NonNullable<ReturnType<typeof this.delivery>>) => Promise<StepOutcome>,
  ): Promise<void> {
    const d = this.delivery();
    if (d === null) {
      return;
    }
    this.doorRefusal.set(null);
    this.lastStep.set(null);
    const outcome = await action(d);
    this.lastStep.set({ step, outcome });
    if (step === 'handover' && outcome.kind === 'done') {
      this.door.set(null);
    }
  }
}
