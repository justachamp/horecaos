import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { DomSanitizer } from '@angular/platform-browser';

import { APP_CONFIG } from '../../core/config/app-config';
import type { CourierOffer } from '../../core/courier/courier-work';
import { CourierProfileStore } from '../../core/courier/courier-profile.store';
import { stepMessage, type StepMessage } from '../../core/deliveries/step-message';
import { formatDistance, formatMoney, formatTime } from '../../core/format';
import { errorMessage } from '../../core/i18n/error-message';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { OFFER_POLL_SECONDS, OffersStore, type OfferOutcome } from '../../core/offers/offers.store';
import { WorkSession } from '../../core/work/work-session';
import { CustomerLocationCard } from '../work/customer-location';
import { geoUri } from '../work/geo-link';
import { StepNotice } from '../work/step-notice';

/**
 * The offers a courier can take (couriers.md section 16).
 *
 * The list is polled, not pushed -- push to the courier app is ADR 0020's channel and is not built --
 * and the page says so in a line the courier can read: how often it looks and when it last did.
 * The countdown is the phone's clock against the offer's `expiresAt` and is only a guide: the
 * platform decides whether an offer is still there, and answers `NO_LONGER_AVAILABLE` (a sentence,
 * not an error) when it is not.
 */
@Component({
  selector: 'app-offers',
  imports: [TPipe, RouterLink, CustomerLocationCard, StepNotice],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './offers.html',
  styleUrl: './offers.css',
})
export class OffersPage implements OnInit, OnDestroy {
  protected readonly store = inject(OffersStore);
  protected readonly profile = inject(CourierProfileStore);
  protected readonly i18n = inject(I18n);
  private readonly work = inject(WorkSession);
  private readonly router = inject(Router);
  private readonly config = inject(APP_CONFIG);
  private readonly sanitizer = inject(DomSanitizer);

  protected readonly pollSeconds = OFFER_POLL_SECONDS;
  private readonly now = signal(Date.now());
  private ticker: ReturnType<typeof setInterval> | null = null;
  /** The last thing that happened to an offer that is no longer on the list ("gone", "declined"). */
  protected readonly lastOutcome = signal<OfferOutcome | null>(null);

  protected readonly updated = computed(() => {
    const at = this.store.updatedAt();
    return at === null
      ? ''
      : formatTime(new Date(at).toISOString(), this.i18n.locale(), this.config.displayTimeZone);
  });

  protected readonly pageMessage = computed<StepMessage | null>(() => {
    const outcome = this.lastOutcome();
    const failure = this.store.error();
    if (outcome !== null && (outcome.kind === 'gone' || outcome.kind === 'declined')) {
      return stepMessage(this.i18n, outcome);
    }
    return failure === null
      ? null
      : { tone: 'warning', text: errorMessage(this.i18n, failure, 'work.error') ?? '' };
  });

  ngOnInit(): void {
    void this.work.begin();
    this.ticker = setInterval(() => this.now.set(Date.now()), 1_000);
  }

  ngOnDestroy(): void {
    if (this.ticker !== null) {
      clearInterval(this.ticker);
    }
  }

  protected secondsLeft(offer: CourierOffer): number {
    return Math.max(0, Math.ceil((Date.parse(offer.expiresAt) - this.now()) / 1_000));
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

  protected pickupLink(offer: CourierOffer) {
    return geoUri(this.sanitizer, offer.pickup.latitude, offer.pickup.longitude);
  }

  protected messageFor(offer: CourierOffer): StepMessage | null {
    return stepMessage(this.i18n, this.store.outcomeOf(offer.offerId));
  }

  protected async accept(offer: CourierOffer): Promise<void> {
    this.lastOutcome.set(null);
    const outcome = await this.store.accept(offer);
    if (outcome.kind === 'accepted') {
      await this.router.navigate(['/deliveries', outcome.delivery.shipmentId]);
    } else {
      this.lastOutcome.set(outcome);
    }
  }

  protected async decline(offer: CourierOffer): Promise<void> {
    this.lastOutcome.set(null);
    this.lastOutcome.set(await this.store.decline(offer));
  }

  protected reveal(offer: CourierOffer): void {
    void this.store.reveal(offer);
  }

  protected hide(offer: CourierOffer): void {
    this.store.hideRevealed(offer.offerId);
  }
}
