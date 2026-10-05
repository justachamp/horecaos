import { Injectable, Signal, effect, inject, signal } from '@angular/core';

import { environment } from '../../../environments/environment';
import { LocationScope, operationsPaths } from '../api/operations-paths';
import { CurrentLocation } from '../auth/current-location';
import { Capability, SessionCapabilities } from '../auth/session-capabilities';
import { StaffTokenStore } from '../auth/staff-token-store';
import { ConnectionState, RealtimeFrame } from './realtime-frames';
import { SseConnection } from './sse-connection';

export type {
  ConnectionState,
  RealtimeFrame,
  RealtimeResyncFrame,
  RealtimeSignalFrame,
  RealtimeSnapshotFrame,
} from './realtime-frames';

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
 * **A second stream, only while a screen asks for it (gap map row `1.1`).** The board's «Все
 * филиалы» mode reads every branch of the brand in one statement, and a branch stream hears
 * only its own branch. {@link watchBrand} opens a separate connection at `BRAND:<id>` for
 * `order_queue` alone -- the one channel the server carries at the brand -- and closes it with
 * its last watcher. The two streams share the machinery ({@link SseConnection}) and the
 * listeners; they differ in which frames arrive, told apart by `scope`.
 *
 * **`Last-Event-Id` resync.** The server never replays — a reconnect with any
 * `Last-Event-Id` value at all answers with a `resync` frame meaning "your
 * whole scope may have changed while you were gone, re-read it", never with
 * the frames that were missed. This class tracks the last event id it saw and
 * sends it as the `Last-Event-Id` header on every reconnect, and turns that
 * `resync` event into a {@link RealtimeResyncFrame} every listener receives.
 *
 * **Jittered reconnect, and the server's own request for one respected.** A
 * deploy drops every stream on the box at once (ADR 0034 has no rolling
 * deploy), so reconnecting at a fixed delay would produce a herd against the
 * API the moment it comes back. A `closing` frame carries
 * `reconnectAfterSecondsMin`/`reconnectAfterSecondsMax` for exactly this, and
 * this class waits a random delay in that range when one arrives. Any other
 * disconnect (a network drop, a proxy timeout, the initial connect failing
 * outright) backs off exponentially from {@link RECONNECT_BASE_MS}, capped at
 * {@link RECONNECT_MAX_MS}, with its own jitter.
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

  /**
   * The brand-wide stream's own state (gap map row `1.1`): `null` while no screen wants it,
   * otherwise what {@link state} says for the branch stream. A board reading every branch of the
   * brand keeps its poll as the fallback exactly when this is anything but `'open'`.
   */
  private readonly brandStateSignal = signal<ConnectionState | null>(null);
  readonly brandState: Signal<ConnectionState | null> = this.brandStateSignal.asReadonly();

  private readonly listeners = new Set<(frame: RealtimeFrame) => void>();

  private currentScopeKey: string | null = null;

  /** The connection at the operator's branch: every channel the operator may have, subscribed once for the session. */
  private readonly branchStream = new SseConnection({
    url: () => this.branchStreamUrl(),
    token: () => this.tokens.accessToken(),
    onFrame: (frame) => this.emit(frame),
    onState: (state) => this.stateSignal.set(state),
  });

  /**
   * The connection at the operator's brand, opened only while a board that reads every branch
   * is on screen. A second connection rather than a second subscription on the first: a stream's
   * subscription set is fixed for its life (ADR 0045), and `order_queue` on the branch stream is
   * keyed by the branch -- the brand's copy of a change is a different scope key, so it needs a
   * stream opened at the brand.
   */
  private readonly brandStream = new SseConnection({
    url: () => this.brandStreamUrl(),
    token: () => this.tokens.accessToken(),
    onFrame: (frame) => this.emit(frame),
    onState: (state) => this.brandStateSignal.set(state),
  });

  /** How many screens want the brand stream right now. Opened on the first, closed on the last. */
  private brandWatchers = 0;

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
      this.restartBranch(scope);
      if (this.brandWatchers > 0) {
        // The brand stream is opened *at* a branch (the endpoint's path names one), so it
        // follows the operator to the new one rather than staying on a location they left.
        this.restartBrand(scope);
      }
    });
  }

  /** Registers a listener for every frame on every subscribed channel; filter by `channel` yourself. Returns an unsubscribe function. */
  onFrame(listener: (frame: RealtimeFrame) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /**
   * Asks for the brand-wide `order_queue` stream (gap map row `1.1`), for the board's «Все
   * филиалы» mode. Returns the release function; the stream stays open while any caller holds
   * one and closes with the last. Its frames arrive through {@link onFrame} like the branch
   * stream's, with `scope` `BRAND:<id>` -- a listener that wants one stream's frames and not the
   * other's filters on it.
   *
   * Server-side this is authorized by the caller's grant at the brand (`order.read`), so a role
   * without one is refused at connect and simply stays `unavailable`: the poll keeps the board
   * current, as it did before this stream existed.
   */
  watchBrand(): () => void {
    this.brandWatchers += 1;
    if (this.brandWatchers === 1) {
      this.restartBrand(this.location.scope());
    }
    let released = false;
    return () => {
      if (released) {
        return;
      }
      released = true;
      this.brandWatchers -= 1;
      if (this.brandWatchers === 0) {
        this.brandStream.close();
        this.brandStateSignal.set(null);
      }
    };
  }

  private emit(frame: RealtimeFrame): void {
    for (const listener of this.listeners) {
      listener(frame);
    }
  }

  private restartBranch(scope: LocationScope | null): void {
    if (!scope) {
      this.branchStream.close();
      this.stateSignal.set('connecting');
      return;
    }
    this.branchStream.open();
  }

  private restartBrand(scope: LocationScope | null): void {
    if (!scope) {
      this.brandStream.close();
      this.brandStateSignal.set('connecting');
      return;
    }
    this.brandStream.open();
  }

  private branchStreamUrl(): string {
    const scope = this.location.scope();
    const params = new URLSearchParams();
    for (const channel of DEFAULT_CHANNELS) {
      if (this.capabilities.has(CHANNEL_CAPABILITY[channel])) {
        params.append('channels', channel);
      }
    }
    return `${environment.apiBaseUrl}${operationsPaths.streams(requireScope(scope))}?${params.toString()}`;
  }

  /**
   * `scope=BRAND:<brandId>` names the brand's copy of the queue; the path still names the
   * operator's branch, because that is where the endpoint's own `location.read` is checked.
   * Only `order_queue`: it is the one channel carried at the brand, and the server resolves any
   * other to a branch key, which would double the branch stream.
   */
  private brandStreamUrl(): string {
    const scope = requireScope(this.location.scope());
    const params = new URLSearchParams();
    params.set('scope', `BRAND:${scope.brandId}`);
    params.append('channels', 'order_queue');
    return `${environment.apiBaseUrl}${operationsPaths.streams(scope)}?${params.toString()}`;
  }
}

function requireScope(scope: LocationScope | null): LocationScope {
  if (!scope) {
    throw new Error('a realtime stream is only opened at a resolved location');
  }
  return scope;
}

function scopeKey(scope: LocationScope): string {
  return `${scope.tenantId}:${scope.brandId}:${scope.locationId}`;
}
