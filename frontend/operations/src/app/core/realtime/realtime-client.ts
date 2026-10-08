import { Injectable, Signal, effect, inject, signal } from '@angular/core';

import { environment } from '../../../environments/environment';
import { LocationScope, operationsPaths } from '../api/operations-paths';
import { CurrentLocation } from '../auth/current-location';
import { Capability, SessionCapabilities } from '../auth/session-capabilities';
import { StaffTokenStore } from '../auth/staff-token-store';
import { SseConnection } from './sse-connection';

/**
 * What every operational screen may show about the accelerator, not about
 * correctness — every surface this feeds also has the 10s poll that must
 * work regardless (ADR 0045; `X.34`).
 */
export type ConnectionState = 'connecting' | 'open' | 'reconnecting' | 'unavailable';

interface FrameBase {
  readonly channel: string;
  readonly scope: string;
  readonly occurredAt: string;
}

/** "Something in your scope changed, re-read it" — never carries the resource itself. */
export interface RealtimeSignalFrame extends FrameBase {
  readonly kind: 'signal';
  readonly resourceType: string;
  readonly resourceId: string | null;
  readonly version: number | null;
}

/** A registered exception (`COUNTERS`, `COURIER_POSITIONS`): a bounded payload inline. */
export interface RealtimeSnapshotFrame extends FrameBase {
  readonly kind: 'snapshot';
  readonly snapshot: unknown;
}

/**
 * "Reconnect and re-read your whole scope" — no replay buffer exists, so this
 * is what a `Last-Event-Id` resync answers with instead of stale frames.
 */
export interface RealtimeResyncFrame {
  readonly kind: 'resync';
}

export type RealtimeFrame = RealtimeSignalFrame | RealtimeSnapshotFrame | RealtimeResyncFrame;

type FrameListener = (frame: RealtimeFrame) => void;

/** The channel set every screen that has a producer today may want a frame from. */
const DEFAULT_CHANNELS = [
  'order_queue',
  'order_detail',
  'counters',
  'dispatch_board',
  'kitchen_board',
] as const;

/**
 * The capability each of {@link DEFAULT_CHANNELS} requires, mirroring
 * `StreamChannel.capability()` (platform `telemetry/api/StreamChannel.java`)
 * for every channel this build ever requests. `streamUrl()` filters
 * against this so a role missing one channel's capability — `DELIVERY_PLAN_READ`
 * for `dispatch_board`, held by dispatchers but not, say, `BRAND_MANAGER` or
 * `TENANT_FINANCE`, or `KITCHEN_TICKET_READ` for `kitchen_board`, held by
 * kitchen and expo staff but not every desk role — never asks for it at all:
 * `OperationsStreamController.authorize()`
 * refuses the *entire* connection on the first channel it has no capability
 * for (`capabilityIsCheckedPerChannelNotOnceForTheWholeSubscription`), so a
 * client that requested a channel it holds no capability for would lose the
 * accelerator on every other channel too, not just that one.
 */
const CHANNEL_CAPABILITY: Record<(typeof DEFAULT_CHANNELS)[number], Capability> = {
  order_queue: 'ORDER_READ',
  order_detail: 'ORDER_READ',
  counters: 'ORDER_READ',
  dispatch_board: 'DELIVERY_PLAN_READ',
  kitchen_board: 'KITCHEN_TICKET_READ',
};

/**
 * The ADR 0045 push client, and the app's one connection to it (wave P08, row
 * `0.1f`).
 *
 * **Fetch-based, not the native `EventSource`, and that is a deliberate
 * substitution rather than a stylistic one.** `EventSource` cannot set a
 * request header, and this console's every other call authenticates with
 * `Authorization: Bearer <token>` attached by `bearerTokenInterceptor` — the
 * platform's stream endpoint is an ordinary Spring Security OAuth2 resource
 * server route with no query-parameter token acceptance, and ADR 0045 itself
 * never asked for one. Putting the access token in a URL a proxy or a browser
 * history entry could retain would be a real regression this class refuses to
 * introduce just to use one specific browser API. `fetch` with a `ReadableStream`
 * reader parses the same `text/event-stream` wire format `EventSource` would
 * have, using the exact header every other request already sends.
 *
 * **One connection for the whole session, not one per screen.** ADR 0045's
 * own controller doc is explicit that "the subscription set is fixed for the
 * connection's life. Changing it means reconnecting" — so rather than each
 * screen opening and tearing down its own stream as it routes in and out,
 * this class connects once its constructor runs, subscribed to every channel
 * in {@link DEFAULT_CHANNELS} the operator holds the capability for (see
 * {@link CHANNEL_CAPABILITY} — the server refuses the whole connection on
 * the first channel it has no capability for, so a channel the operator
 * cannot have must never be requested), and screens that care register a
 * listener with {@link onFrame} and filter by `channel`/`resourceId`
 * themselves. Reconnects when the operator switches branch, because a
 * channel's scope key is the location this connection was opened at.
 *
 * **The transport is {@link SseConnection}'s, shared with `BrandOrderStream`.**
 * This class decides *which* stream is wanted — the channel list, filtered by
 * capability, at the operator's branch — and fans every frame out to its
 * listeners; the connection does the rest, identically for both owners:
 *
 * - **`Last-Event-Id` resync.** The server never replays — a reconnect with any
 *   `Last-Event-Id` value at all answers with a `resync` frame meaning "your
 *   whole scope may have changed while you were gone, re-read it", never with
 *   the frames that were missed. The connection tracks the last event id it
 *   saw, sends it as the `Last-Event-Id` header on every reconnect, and turns
 *   that `resync` event into a {@link RealtimeResyncFrame} every listener
 *   receives.
 * - **Jittered reconnect, and the server's own request for one respected.** A
 *   deploy drops every stream on the box at once (ADR 0034 has no rolling
 *   deploy), so reconnecting at a fixed delay would produce a herd against the
 *   API the moment it comes back. A `closing` frame carries
 *   `reconnectAfterSecondsMin`/`reconnectAfterSecondsMax` for exactly this, and
 *   the connection waits a random delay in that range when one arrives. Any
 *   other disconnect (a network drop, a proxy timeout, the initial connect
 *   failing outright) backs off exponentially, capped, with its own jitter.
 *
 * **Degrade to poll — meaning nothing else changes.** {@link state} exists so
 * `X.34`'s `ConnectionState`/`LiveBadge` can tell an operator whether the
 * accelerator is live, but no consumer's correctness depends on it: every
 * screen this feeds keeps its own unconditional 10s poll running exactly as
 * it always has (`order-queue.ts`'s own `POLL_INTERVAL_MS`), so a session
 * that never manages to connect — a proxy stripping `text/event-stream`, a
 * browser with `fetch` streaming disabled — degrades to precisely the
 * behaviour this console shipped with before this wave, silently.
 */
@Injectable({ providedIn: 'root' })
export class RealtimeClient {
  private readonly location = inject(CurrentLocation);
  private readonly tokens = inject(StaffTokenStore);
  private readonly capabilities = inject(SessionCapabilities);

  private readonly stateSignal = signal<ConnectionState>('connecting');
  readonly state: Signal<ConnectionState> = this.stateSignal.asReadonly();

  private readonly listeners = new Set<FrameListener>();

  private currentScopeKey: string | null = null;
  /** The branch the open connection was made at; every reconnect of that connection asks for the same one. */
  private streamScope: LocationScope | null = null;

  private readonly connection = new SseConnection({
    url: () => this.streamUrl(),
    token: () => this.tokens.accessToken(),
    onFrame: (frame) => this.emit(frame),
    onState: (state) => this.stateSignal.set(state),
  });

  /**
   * Wired up once, here, rather than behind an idempotent `start()` a
   * consumer has to remember to call — `providedIn: 'root'` already
   * guarantees exactly one instance for the session, and the shell's own
   * `private readonly realtimeClient = inject(RealtimeClient)` field
   * (mirroring `voicePresence`/`status`) is what makes sure this class is
   * constructed before any routed screen. `effect()` needs the injection
   * context a constructor provides; a later method call would not
   * reliably have one.
   */
  constructor() {
    effect(() => {
      const scope = this.location.scope();
      const key = scope ? scopeKey(scope) : null;
      if (key === this.currentScopeKey) {
        return;
      }
      this.currentScopeKey = key;
      this.restart(scope);
    });
  }

  /** Registers a listener for every frame on every subscribed channel; filter by `channel` yourself. Returns an unsubscribe function. */
  onFrame(listener: FrameListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private restart(scope: LocationScope | null): void {
    this.streamScope = scope;
    if (!scope) {
      this.connection.close();
      this.stateSignal.set('connecting');
      return;
    }
    this.connection.open();
  }

  private emit(frame: RealtimeFrame): void {
    for (const listener of this.listeners) {
      listener(frame);
    }
  }

  private streamUrl(): string {
    const scope = this.streamScope;
    if (!scope) {
      throw new Error('a realtime stream is only opened at a resolved location');
    }
    const params = new URLSearchParams();
    for (const channel of DEFAULT_CHANNELS) {
      if (this.capabilities.has(CHANNEL_CAPABILITY[channel])) {
        params.append('channels', channel);
      }
    }
    return `${environment.apiBaseUrl}${operationsPaths.streams(scope)}?${params.toString()}`;
  }
}

function scopeKey(scope: LocationScope): string {
  return `${scope.tenantId}:${scope.brandId}:${scope.locationId}`;
}
