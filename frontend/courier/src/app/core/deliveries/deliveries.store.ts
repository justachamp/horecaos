import { Injectable, computed, inject, signal } from '@angular/core';

import { IntentKeys } from '../api/intent-keys';
import { ApiError, toApiError } from '../api/problem-details';
import { CourierApi } from '../courier/courier-api';
import { CourierProfileStore } from '../courier/courier-profile.store';
import type { BranchRef } from '../courier/courier-shift';
import type { CourierDelivery, CustomerLocation, PositionBody } from '../courier/courier-work';
import { LocationService } from '../device/location.service';
import { DutyStore } from '../telemetry/duty.store';
import { classifyStepFailure, describeSuccess, type StepOutcome } from './step-outcome';

/** A delivery still being carried: assigned, waiting at the pickup, or on the road. */
const CARRIED: ReadonlySet<CourierDelivery['status']> = new Set([
  'ASSIGNED',
  'PICKUP_PENDING',
  'PICKED_UP',
]);

export type RevealAnswer =
  | { readonly ok: true; readonly location: CustomerLocation }
  | { readonly ok: false; readonly outcome: Exclude<StepOutcome, { kind: 'done' }> };

/**
 * The courier's deliveries and the steps that move them (couriers.md section 16).
 *
 * Every step goes through one routine: the courier's position (when the phone has one), one
 * idempotency key for the intent -- the same one if the connection dropped and the courier taps
 * again -- the delivery's own version as `If-Match`, and an answer sorted into the branches the
 * screen has sentences for (`StepOutcome`). A step is one at a time per delivery.
 *
 * **The customer's address is not kept here.** The reveal is audited on the platform and returned
 * to the screen that asked, which holds it in its own state and drops it when the screen goes.
 */
@Injectable({ providedIn: 'root' })
export class DeliveriesStore {
  private readonly api = inject(CourierApi);
  private readonly profile = inject(CourierProfileStore);
  private readonly location = inject(LocationService);
  private readonly duty = inject(DutyStore);
  private readonly keys = inject(IntentKeys);

  private readonly held = signal<readonly CourierDelivery[]>([]);
  private readonly working = signal<string | null>(null);
  private readonly failure = signal<ApiError | null>(null);

  /** Oldest assignment first: the order the courier took them in, which is the order they are usually served. */
  readonly deliveries = computed(() =>
    [...this.held()].sort((a, b) => a.assignedAt.localeCompare(b.assignedAt)),
  );
  readonly active = computed(() => this.deliveries().filter((d) => CARRIED.has(d.status)));
  /** Delivered or cancelled: kept in view so a finished delivery can still be looked at. */
  readonly finished = computed(() => this.deliveries().filter((d) => !CARRIED.has(d.status)));
  /** The shipment a step is in the air for, or null. */
  readonly busy = this.working.asReadonly();
  /** Why the last list read failed; the list itself is left as it was. */
  readonly error = this.failure.asReadonly();

  byId(shipmentId: string): CourierDelivery | null {
    return this.held().find((d) => d.shipmentId === shipmentId) ?? null;
  }

  async refresh(): Promise<void> {
    const where = this.profile.workBranch();
    if (where === null) {
      this.replace([]);
      return;
    }
    try {
      this.replace(await this.api.deliveries(where));
      this.failure.set(null);
    } catch (cause) {
      this.failure.set(toApiError(cause));
    }
  }

  /** Sign-out: nothing of a courier's work stays in memory for whoever uses the phone next. */
  clear(): void {
    this.replace([]);
    this.failure.set(null);
    this.working.set(null);
  }

  /** Takes up a delivery that arrived with an answer (an accepted offer) without another read. */
  adopt(delivery: CourierDelivery): void {
    this.upsert(delivery);
  }

  /** Reads one delivery fresh. Null for one that is not the courier's, which the platform answers as it does a missing one. */
  async load(shipmentId: string): Promise<CourierDelivery | null> {
    const where = this.profile.workBranch();
    if (where === null) {
      return null;
    }
    try {
      const read = await this.api.delivery(where, shipmentId);
      this.upsert(read);
      return read;
    } catch (cause) {
      const error = toApiError(cause);
      if (!error.isNotFound) {
        this.failure.set(error);
      }
      return null;
    }
  }

  /** Arrival at the pickup. */
  arrive(delivery: CourierDelivery): Promise<StepOutcome> {
    return this.step(delivery, 'arrive', true, (where, position, key) =>
      this.api.advance(where, delivery, 'PICKUP_PENDING', position, key),
    );
  }

  /** The pickup gate. HARD mode refuses with the failed checks; SOFT mode succeeds and reports them. */
  pickUp(delivery: CourierDelivery): Promise<StepOutcome> {
    return this.step(delivery, 'pickup', true, (where, position, key) =>
      this.api.pickUp(where, delivery, position, key),
    );
  }

  /** The handover gate, the same contract as pickup. */
  handOver(delivery: CourierDelivery): Promise<StepOutcome> {
    return this.step(delivery, 'handover', true, (where, position, key) =>
      this.api.handOver(where, delivery, position, key),
    );
  }

  /** The cash counted at the door, in minor units. The platform compares it to what is due. */
  confirmPayment(delivery: CourierDelivery, collectedMinor: number): Promise<StepOutcome> {
    return this.step(delivery, `pay-${collectedMinor}`, false, (where, _position, key) =>
      this.api.confirmPayment(where, delivery, collectedMinor, key),
    );
  }

  /** The audited reveal of the customer's door. Every call is a fact on the platform, so a tap is a decision. */
  async reveal(delivery: CourierDelivery): Promise<RevealAnswer> {
    const where = this.profile.workBranch();
    if (where === null) {
      return {
        ok: false,
        outcome: {
          kind: 'failed',
          error: new ApiError({ status: 0, code: 'NO_BRANCH', detail: 'no branch to ask at' }),
        },
      };
    }
    try {
      const location = await this.keys.run(`reveal-delivery:${delivery.shipmentId}`, (key) =>
        this.api.revealDeliveryLocation(where, delivery.shipmentId, key),
      );
      return { ok: true, location };
    } catch (cause) {
      return {
        ok: false,
        outcome: classifyStepFailure(cause) as Exclude<StepOutcome, { kind: 'done' }>,
      };
    }
  }

  private async step(
    delivery: CourierDelivery,
    intent: string,
    wantsPosition: boolean,
    call: (
      where: BranchRef,
      position: PositionBody | null,
      key: string,
    ) => Promise<CourierDelivery>,
  ): Promise<StepOutcome> {
    const where = this.profile.workBranch();
    if (where === null || this.working() !== null) {
      return {
        kind: 'failed',
        error: new ApiError({ status: 0, code: 'BUSY', detail: 'a step is already in the air' }),
      };
    }
    this.working.set(delivery.shipmentId);
    try {
      const position = wantsPosition ? await this.location.position() : null;
      const result = await this.keys.run(
        `${intent}:${delivery.shipmentId}:${delivery.version}`,
        (key) => call(where, position, key),
      );
      this.upsert(result);
      return describeSuccess(result);
    } catch (cause) {
      const outcome = classifyStepFailure(cause);
      if (outcome.kind === 'stale') {
        await this.load(delivery.shipmentId);
      }
      return outcome;
    } finally {
      this.working.set(null);
    }
  }

  private upsert(delivery: CourierDelivery): void {
    // A gate step's answer carries `checks`; they belong to that step's outcome, not to the delivery.
    const plain: CourierDelivery & { checks?: unknown } = { ...delivery };
    delete plain.checks;
    this.replace([...this.held().filter((d) => d.shipmentId !== plain.shipmentId), plain]);
  }

  private replace(next: readonly CourierDelivery[]): void {
    this.held.set(next);
    this.duty.assignments.set(this.active().length);
  }
}
