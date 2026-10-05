import { Injectable, Signal, effect, inject, signal } from '@angular/core';

import { environment } from '../../../environments/environment';
import { operationsPaths } from '../api/operations-paths';
import { CurrentLocation } from '../auth/current-location';
import { SessionCapabilities } from '../auth/session-capabilities';
import { StaffTokenStore } from '../auth/staff-token-store';
import type { ConnectionState, RealtimeFrame } from './realtime-client';
import { SseConnection } from './sse-connection';

/**
 * The order board's brand-wide stream (gap map row `1.1`, ADR 0045): the second connection a
 * board that reads every branch of the brand listens on, because the first -- `RealtimeClient`'s,
 * at the operator's branch -- hears only that branch.
 *
 * **Why a connection of its own.** A stream's subscription set is fixed for its life, and
 * `order_queue` on the branch stream is keyed by the branch: the brand's copy of a change is a
 * different scope key (`BRAND:<id>`, which `OrderRealtimeSignalTrigger` publishes beside the
 * branch's), so hearing it needs a stream opened at the brand. Only `order_queue` is asked for --
 * the one channel the server carries at the brand; it resolves any other channel to a branch key,
 * which would double the branch stream.
 *
 * **Why a service of its own.** Only the order board wants it, and it is open only while that
 * board is in «Все филиалы» mode. Kept out of `RealtimeClient` so the client every screen loads
 * carries none of it; the transport ({@link SseConnection}) is shared.
 *
 * **Authorization is the server's.** The stream is authorized at the brand (`order.read` granted
 * there, not inferred from a branch grant). A role without it would be refused at connect and
 * retried for as long as it was wanted, so this reports `unavailable` without asking and the
 * board's poll does the work, as it did before this stream existed.
 */
@Injectable({ providedIn: 'root' })
export class BrandOrderStream {
  private readonly location = inject(CurrentLocation);
  private readonly tokens = inject(StaffTokenStore);
  private readonly capabilities = inject(SessionCapabilities);

  /** `null` while no screen wants the stream; otherwise the connection's own state. */
  private readonly stateSignal = signal<ConnectionState | null>(null);
  readonly state: Signal<ConnectionState | null> = this.stateSignal.asReadonly();

  private readonly listeners = new Set<(frame: RealtimeFrame) => void>();
  private watchers = 0;
  /** The location the open connection was made at, so a branch switch is told from the effect's first run. */
  private openedAt: string | null = null;

  private readonly connection = new SseConnection({
    url: () => this.streamUrl(),
    token: () => this.tokens.accessToken(),
    onFrame: (frame) => this.listeners.forEach((listener) => listener(frame)),
    onState: (state) => this.stateSignal.set(state),
  });

  constructor() {
    // The stream is opened *at* a branch (the endpoint's path names one, where `location.read` is
    // checked), so while it is wanted it follows the operator to the next one. Comparing with where
    // it was opened, rather than with the last value the effect saw, keeps the effect's first run
    // -- which can land after a `watch()` -- from opening a second connection beside the first.
    effect(() => {
      const scope = this.location.scope();
      const key = scope ? `${scope.tenantId}:${scope.brandId}:${scope.locationId}` : null;
      if (this.watchers > 0 && key !== this.openedAt) {
        this.open();
      }
    });
  }

  /** Registers a listener for the brand stream's frames; their `scope` reads `BRAND:<id>`. Returns an unsubscribe function. */
  onFrame(listener: (frame: RealtimeFrame) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /**
   * Asks for the stream. It stays open while any caller holds a release function and closes with
   * the last; releasing twice releases once.
   */
  watch(): () => void {
    this.watchers += 1;
    if (this.watchers === 1) {
      this.open();
    }
    let released = false;
    return () => {
      if (released) {
        return;
      }
      released = true;
      this.watchers -= 1;
      if (this.watchers === 0) {
        this.connection.close();
        this.openedAt = null;
        this.stateSignal.set(null);
      }
    };
  }

  private open(): void {
    const scope = this.location.scope();
    this.openedAt = scope ? `${scope.tenantId}:${scope.brandId}:${scope.locationId}` : null;
    if (!this.capabilities.has('ORDER_READ')) {
      this.connection.close();
      this.stateSignal.set('unavailable');
      return;
    }
    if (!scope) {
      this.connection.close();
      this.stateSignal.set('connecting');
      return;
    }
    this.connection.open();
  }

  private streamUrl(): string {
    const scope = this.location.scope();
    if (!scope) {
      throw new Error('a realtime stream is only opened at a resolved location');
    }
    const params = new URLSearchParams();
    params.set('scope', `BRAND:${scope.brandId}`);
    params.append('channels', 'order_queue');
    return `${environment.apiBaseUrl}${operationsPaths.streams(scope)}?${params.toString()}`;
  }
}
