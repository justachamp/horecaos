import { DOCUMENT } from '@angular/common';
import { Injectable, computed, inject, signal } from '@angular/core';

import { IntentKeys } from '../api/intent-keys';
import { ApiError, toApiError } from '../api/problem-details';
import { CourierApi } from '../courier/courier-api';
import type { BranchRef } from '../courier/courier-shift';
import type { CourierDelivery, CourierOffer, CustomerLocation } from '../courier/courier-work';
import { DeliveriesStore } from '../deliveries/deliveries.store';
import { classifyStepFailure, type GateRefusalReason } from '../deliveries/step-outcome';
import { LocationService } from '../device/location.service';

/**
 * How often offers are asked for.
 *
 * Push to the courier app is ADR 0020's channel and is not built (ADR 0179 D7.7), so the app polls.
 * An offer lives 60 seconds by default; a poll every fifteen sees every one with most of its life
 * left, and four polls fit inside it. The number is here, once, because the screen's promise to the
 * courier -- "new offers appear by themselves" -- is only as good as it.
 */
export const OFFER_POLL_SECONDS = 15;

/** What became of the courier's decision about an offer. */
export type OfferOutcome =
  | { readonly kind: 'accepted'; readonly delivery: CourierDelivery }
  | { readonly kind: 'declined' }
  | { readonly kind: 'revealed' }
  /** The offer lapsed, or somebody else took it: an answer the platform gives with a 200, not an error. */
  | { readonly kind: 'gone' }
  | { readonly kind: 'refused'; readonly reason: GateRefusalReason | null }
  | { readonly kind: 'stale' }
  | { readonly kind: 'failed'; readonly error: ApiError };

/**
 * The offers a courier can still take, polled, and the decisions about them.
 *
 * Polling pauses while the page is hidden (a phone in a pocket should not spend data on a list
 * nobody is looking at) and catches up the moment it is shown. A failed poll keeps the list it
 * has and says so; the next one recovers by itself.
 *
 * **The customer's address, once revealed, lives here only while its offer does** -- it is dropped
 * the moment the offer is taken, declined or gone, and when polling stops (sign-out, shift end).
 */
@Injectable({ providedIn: 'root' })
export class OffersStore {
  private readonly api = inject(CourierApi);
  private readonly location = inject(LocationService);
  private readonly deliveries = inject(DeliveriesStore);
  private readonly keys = inject(IntentKeys);
  private readonly document = inject(DOCUMENT);

  private where: BranchRef | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private polling: Promise<void> | null = null;
  /** Bumped on start and stop so a late answer to an old poll cannot touch the new run. */
  private run = 0;

  private readonly held = signal<readonly CourierOffer[]>([]);
  private readonly failure = signal<ApiError | null>(null);
  private readonly readAt = signal<number | null>(null);
  private readonly working = signal<string | null>(null);
  private readonly outcomes = signal<Readonly<Record<string, OfferOutcome>>>({});
  private readonly doors = signal<Readonly<Record<string, CustomerLocation>>>({});

  /** Soonest to lapse first. */
  readonly offers = computed(() =>
    [...this.held()].sort((a, b) => a.expiresAt.localeCompare(b.expiresAt)),
  );
  readonly count = computed(() => this.held().length);
  readonly error = this.failure.asReadonly();
  /** When the list was last read (epoch ms). */
  readonly updatedAt = this.readAt.asReadonly();
  /** The offer a decision is in the air for, or null. */
  readonly busy = this.working.asReadonly();

  outcomeOf(offerId: string): OfferOutcome | null {
    return this.outcomes()[offerId] ?? null;
  }

  revealedFor(offerId: string): CustomerLocation | null {
    return this.doors()[offerId] ?? null;
  }

  /** Begins polling at a branch. Starting again at the same branch changes nothing. */
  start(where: BranchRef): void {
    if (this.where?.locationId === where.locationId) {
      return;
    }
    this.stop();
    this.run += 1;
    this.where = where;
    this.document.addEventListener('visibilitychange', this.onVisibility);
    void this.poll();
  }

  /** Stops polling and forgets everything held, the revealed addresses included. */
  stop(): void {
    this.run += 1;
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.document.removeEventListener('visibilitychange', this.onVisibility);
    this.where = null;
    this.polling = null;
    this.held.set([]);
    this.failure.set(null);
    this.readAt.set(null);
    this.working.set(null);
    this.outcomes.set({});
    this.doors.set({});
  }

  async accept(offer: CourierOffer): Promise<OfferOutcome> {
    return this.decide(offer, async (where) => {
      // The position is part of the body the platform hashes under the key: read once for this
      // intent, and sent again as it was if the connection dropped and the courier taps again.
      const result = await this.keys.runWith(
        `accept:${offer.offerId}:${offer.version}`,
        () => this.location.position(),
        (key, position) => this.api.acceptOffer(where, offer, position, key),
      );
      if (result.outcome === 'ACCEPTED' && result.delivery !== null) {
        this.deliveries.adopt(result.delivery);
        this.drop(offer.offerId);
        return { kind: 'accepted', delivery: result.delivery };
      }
      this.drop(offer.offerId);
      return { kind: 'gone' };
    });
  }

  async decline(offer: CourierOffer): Promise<OfferOutcome> {
    return this.decide(offer, async (where) => {
      const result = await this.keys.run(`decline:${offer.offerId}:${offer.version}`, (key) =>
        this.api.declineOffer(where, offer, key),
      );
      this.drop(offer.offerId);
      return result.outcome === 'DECLINED' ? { kind: 'declined' } : { kind: 'gone' };
    });
  }

  /** The audited look at the customer's address before acceptance, where the tenant allows it. */
  async reveal(offer: CourierOffer): Promise<OfferOutcome> {
    return this.decide(offer, async (where) => {
      const location = await this.keys.run(`reveal-offer:${offer.offerId}`, (key) =>
        this.api.revealOfferLocation(where, offer.offerId, key),
      );
      this.doors.update((all) => ({ ...all, [offer.offerId]: location }));
      return { kind: 'revealed' };
    });
  }

  /** The courier hid an address they had opened; nothing of it stays in memory. */
  hideRevealed(offerId: string): void {
    this.doors.update(({ [offerId]: _door, ...rest }) => rest);
  }

  private async decide(
    offer: CourierOffer,
    action: (where: BranchRef) => Promise<OfferOutcome>,
  ): Promise<OfferOutcome> {
    const where = this.where;
    if (where === null || this.working() !== null) {
      return {
        kind: 'failed',
        error: new ApiError({
          status: 0,
          code: 'BUSY',
          detail: 'a decision is already in the air',
        }),
      };
    }
    this.working.set(offer.offerId);
    this.outcomes.update(({ [offer.offerId]: _previous, ...rest }) => rest);
    try {
      const outcome = await action(where);
      this.remember(offer.offerId, outcome);
      return outcome;
    } catch (cause) {
      const classified = classifyStepFailure(cause);
      const outcome: OfferOutcome =
        classified.kind === 'refused' || classified.kind === 'stale' || classified.kind === 'failed'
          ? classified
          : { kind: 'failed', error: toApiError(cause) };
      if (outcome.kind === 'stale') {
        void this.poll();
      }
      this.remember(offer.offerId, outcome);
      return outcome;
    } finally {
      this.working.set(null);
    }
  }

  /** An outcome worth showing under an offer that is still on the list; a finished offer needs none. */
  private remember(offerId: string, outcome: OfferOutcome): void {
    if (outcome.kind === 'refused' || outcome.kind === 'stale' || outcome.kind === 'failed') {
      this.outcomes.update((all) => ({ ...all, [offerId]: outcome }));
    }
  }

  private drop(offerId: string): void {
    this.held.update((all) => all.filter((o) => o.offerId !== offerId));
    this.doors.update(({ [offerId]: _door, ...rest }) => rest);
    this.outcomes.update(({ [offerId]: _outcome, ...rest }) => rest);
  }

  private poll(): Promise<void> {
    this.polling ??= this.readOnce().finally(() => {
      this.polling = null;
    });
    return this.polling;
  }

  private async readOnce(): Promise<void> {
    const where = this.where;
    const run = this.run;
    if (where === null) {
      return;
    }
    try {
      const fresh = await this.api.offers(where);
      if (run !== this.run) {
        return;
      }
      const alive = new Set(fresh.map((offer) => offer.offerId));
      this.held.set(fresh);
      this.doors.update((all) =>
        Object.fromEntries(Object.entries(all).filter(([id]) => alive.has(id))),
      );
      this.failure.set(null);
      this.readAt.set(Date.now());
    } catch (cause) {
      if (run === this.run) {
        this.failure.set(toApiError(cause));
      }
    } finally {
      if (run === this.run) {
        this.schedule();
      }
    }
  }

  private schedule(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
    }
    this.timer = setTimeout(() => {
      this.timer = null;
      if (this.document.visibilityState === 'hidden') {
        this.schedule();
        return;
      }
      void this.poll();
    }, OFFER_POLL_SECONDS * 1_000);
  }

  private readonly onVisibility = (): void => {
    if (this.where !== null && this.document.visibilityState !== 'hidden') {
      void this.poll();
    }
  };
}
