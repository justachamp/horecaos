import { Injectable, Signal, computed, inject, signal } from '@angular/core';

import { CurrentLocation } from '../core/auth/current-location';
import { ApiError } from '../core/api/problem-details';
import {
  CallCentreApi,
  PresenceState,
  PresenceView,
  ScreenPopCard,
} from '../features/orders/call-centre-api';

/** Same cadence every other live screen in this app polls at (`order-queue.ts`, `call-centre-page.ts`), until ADR 0045 exists. */
const POLL_INTERVAL_MS = 10_000;

/**
 * The operator's own presence and the branch's ringing call, lifted out of
 * `call-centre-page.ts` so a screen-pop card can follow an operator to any
 * screen (IA X.37, CallBar) — the entire reason X.37 exists is that the pop
 * previously could not: an operator answering the phone had to already be on
 * `/orders/call-centre` to see it.
 *
 * `call-centre-page.ts` still owns the call log, presence-change form and
 * the create-customer flow; this owns only the one poll and the one
 * presence read/write both that page and the shell's call bar need in
 * common, so there is exactly one interval running, not two racing each
 * other.
 *
 * {@link start} is idempotent, the same shape `CurrentLocation.ensureLoaded`
 * already is — call it from every consumer that depends on the poll running
 * (the shell's constructor, and `call-centre-page.ts`'s own `ngOnInit`, for
 * the case where this page is opened directly rather than through the
 * shell's already-mounted bar).
 */
@Injectable({ providedIn: 'root' })
export class VoicePresence {
  private readonly location = inject(CurrentLocation);
  private readonly api = inject(CallCentreApi);

  private readonly presenceState = signal<PresenceView | null>(null);
  private readonly cardState = signal<ScreenPopCard | null>(null);
  private readonly pollErrorState = signal<string | null>(null);

  readonly presence: Signal<PresenceView | null> = this.presenceState.asReadonly();

  /** The ringing call, or null when nothing is ringing — never the non-ringing card `currentCall` itself returns. */
  readonly currentCall: Signal<ScreenPopCard | null> = computed(() => {
    const card = this.cardState();
    return card?.ringing ? card : null;
  });

  readonly pollError: Signal<string | null> = this.pollErrorState.asReadonly();

  private started = false;
  private pollHandle: ReturnType<typeof setInterval> | null = null;
  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') {
      void this.poll();
    }
  };

  /** Safe to call from every consumer; only the first call does anything. */
  start(): void {
    if (this.started) {
      return;
    }
    this.started = true;
    void this.loadPresence();
    document.addEventListener('visibilitychange', this.onVisibilityChange);
    this.pollHandle = setInterval(() => {
      if (document.visibilityState === 'visible') {
        void this.poll();
      }
    }, POLL_INTERVAL_MS);
    void this.poll();
  }

  /**
   * Fetches this operator's own presence and throws on failure, unlike
   * {@link start}'s own silent first load — `call-centre-page.ts` calls this
   * directly so it can tell a genuine 403 apart from "not loaded yet" for
   * its own denied state, the same distinction it always drew before this
   * read moved here.
   */
  async refreshPresence(): Promise<PresenceView> {
    const scope = this.location.scope();
    if (!scope) {
      throw new ApiError('LOCATION_NOT_RESOLVED', 403, null, null);
    }
    const presence = await this.api.myPresence(scope);
    this.presenceState.set(presence);
    return presence;
  }

  async setPresence(state: PresenceState, reason: string | null): Promise<PresenceView> {
    const scope = this.location.scope();
    if (!scope) {
      throw new ApiError('LOCATION_NOT_RESOLVED', 403, null, null);
    }
    const updated = await this.api.setPresence(scope, state, reason);
    this.presenceState.set(updated);
    return updated;
  }

  /** Claims the ringing call's card, then re-polls so every consumer sees `acknowledgedBy` fill in together. */
  async claim(callEventId: string): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    await this.api.acknowledge(scope, callEventId);
    await this.poll();
  }

  /**
   * Re-polls on demand — `call-centre-page.ts`'s own create-customer flow
   * calls this once a customer exists, so the next read re-resolves the
   * same caller against the account this page just created rather than
   * waiting a whole interval to stop showing "unknown caller".
   */
  async refreshCurrentCall(): Promise<void> {
    await this.poll();
  }

  private async loadPresence(): Promise<void> {
    try {
      await this.refreshPresence();
    } catch {
      // The bar has no room for an error state, and `call-centre-page.ts`'s
      // own `refreshPresence()` call already surfaces a genuine denial.
    }
  }

  private async poll(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      const card = await this.api.currentCall(scope);
      this.cardState.set(card);
      this.pollErrorState.set(null);
    } catch (error) {
      // A poll failing once is not worth interrupting an operator over; the
      // next tick tries again. A persistent 403 already surfaced on first
      // load through `refreshPresence`, so it stays silent here too.
      if (error instanceof ApiError && error.status !== 403) {
        this.pollErrorState.set('poll-failed');
      }
    }
  }
}
