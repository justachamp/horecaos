import type { ConnectionState, RealtimeFrame } from './realtime-client';

const RECONNECT_BASE_MS = 1_000;
const RECONNECT_MAX_MS = 30_000;

/** Past this many consecutive failures the connection state reads `unavailable` rather than `reconnecting` — a UI distinction only; retrying never stops. */
const UNAVAILABLE_AFTER_FAILURES = 3;

export interface SseConnectionOptions {
  /** Where to connect, asked again on every attempt: the capabilities a URL is built from can change between a drop and its reconnect. */
  readonly url: () => string;
  /** The bearer token, or null while there is none (the attempt is then retried like any failed one). */
  readonly token: () => string | null;
  /** Every frame the stream delivers. */
  readonly onFrame: (frame: RealtimeFrame) => void;
  /** Every change of the connection's own state. */
  readonly onState: (state: ConnectionState) => void;
}

/**
 * One fetch-based `text/event-stream` connection with the reconnect discipline ADR 0045 asks
 * of a client: `Last-Event-Id` resync, a jittered exponential backoff, and the server's own
 * `closing` request for a delay respected.
 *
 * **This is a second copy of the transport `RealtimeClient` carries, and that is deliberate for
 * now.** It was written for the brand-wide board stream (gap map row `1.1`, `BrandOrderStream`), a
 * screen-scoped, lazy-loaded consumer. `RealtimeClient` is in the initial bundle, whose budget
 * (`angular.json`, 832 kB) had 0.6 kB of headroom when this was written; folding the client onto
 * this class cost 0.4 kB of it. When the budget has room, `RealtimeClient` should be reduced to
 * this class plus its channel list, and the duplication goes. Until then a change to one's
 * reconnect rules is a change to both.
 *
 * Nothing here decides *which* stream is wanted; the owner calls {@link open} to (re)start it
 * and {@link close} to end it. A superseded attempt is recognised by its generation, so a slow
 * response from the previous scope can never deliver a frame into the next one.
 */
export class SseConnection {
  private generation = 0;
  private lastEventId: string | null = null;
  private consecutiveFailures = 0;
  private reconnectHandle: ReturnType<typeof setTimeout> | null = null;
  private abortController: AbortController | null = null;
  /** Set by a `closing` frame just before the socket actually ends, so the reconnect that follows honours the server's own jittered-delay request. */
  private pendingClosingDelay: { readonly min: number; readonly max: number } | null = null;

  constructor(private readonly options: SseConnectionOptions) {}

  /** (Re)starts the connection from nothing: no remembered event id, no failures. */
  open(): void {
    this.stop();
    this.options.onState('connecting');
    void this.connect(this.generation);
  }

  /** Ends the connection and forgets everything about it. The owner reports whatever state it wants to show. */
  close(): void {
    this.stop();
  }

  private stop(): void {
    this.generation += 1;
    this.abortController?.abort();
    this.abortController = null;
    if (this.reconnectHandle !== null) {
      clearTimeout(this.reconnectHandle);
      this.reconnectHandle = null;
    }
    this.lastEventId = null;
    this.consecutiveFailures = 0;
    this.pendingClosingDelay = null;
  }

  private async connect(generation: number): Promise<void> {
    if (generation !== this.generation) {
      return;
    }
    const token = this.options.token();
    if (token === null) {
      this.scheduleReconnect(generation);
      return;
    }

    const controller = new AbortController();
    this.abortController = controller;
    const headers: Record<string, string> = {
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`,
    };
    if (this.lastEventId !== null) {
      headers['Last-Event-Id'] = this.lastEventId;
    }

    try {
      const response = await fetch(this.options.url(), { headers, signal: controller.signal });
      if (!response.ok || response.body === null) {
        throw new Error(`realtime stream connect failed: ${response.status}`);
      }
      this.options.onState('open');
      this.consecutiveFailures = 0;
      await this.pump(response.body, generation);
      // A clean end of stream (the async timeout, or the server closing the
      // socket) is a disconnect like any other — reconnect rather than
      // treating "the response finished" as "nothing more will ever change".
      this.scheduleReconnect(generation);
    } catch {
      if (controller.signal.aborted) {
        // This generation was superseded by a branch switch — the newer
        // attempt owns reconnecting, not this one.
        return;
      }
      this.scheduleReconnect(generation);
    }
  }

  private async pump(body: ReadableStream<Uint8Array>, generation: number): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (generation !== this.generation) {
        await reader.cancel().catch(() => undefined);
        return;
      }
      if (done) {
        return;
      }
      buffer += decoder.decode(value, { stream: true });
      const blocks = buffer.split('\n\n');
      buffer = blocks.pop() ?? '';
      for (const block of blocks) {
        this.handleBlock(block);
      }
    }
  }

  private handleBlock(block: string): void {
    let eventName = 'message';
    let id: string | null = null;
    const dataLines: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) {
        continue; // a heartbeat comment — the open connection is proof enough of itself
      }
      if (line.startsWith('event:')) {
        eventName = line.slice('event:'.length).trim();
      } else if (line.startsWith('id:')) {
        id = line.slice('id:'.length).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice('data:'.length).trim());
      }
    }
    if (id !== null) {
      this.lastEventId = id;
    }
    if (dataLines.length === 0 && eventName !== 'resync') {
      return;
    }
    const data = dataLines.length > 0 ? parseJson(dataLines.join('\n')) : null;
    this.dispatch(eventName, data);
  }

  private dispatch(eventName: string, data: Record<string, unknown> | null): void {
    if (eventName === 'resync') {
      this.options.onFrame({ kind: 'resync' });
      return;
    }
    if (eventName === 'closing') {
      // The next `pump()` read resolves with `done: true` or throws right
      // after this arrives; `scheduleReconnect` reads these two fields off
      // the frame the *next* time it is called for this generation, via the
      // closing-frame delay captured here.
      const min =
        typeof data?.['reconnectAfterSecondsMin'] === 'number'
          ? data['reconnectAfterSecondsMin']
          : null;
      const max =
        typeof data?.['reconnectAfterSecondsMax'] === 'number'
          ? data['reconnectAfterSecondsMax']
          : null;
      this.pendingClosingDelay = min !== null && max !== null ? { min, max } : null;
      return;
    }
    if (eventName === 'signal' && data !== null) {
      this.options.onFrame({
        kind: 'signal',
        channel: String(data['channel'] ?? ''),
        scope: String(data['scope'] ?? ''),
        resourceType: String(data['resourceType'] ?? ''),
        resourceId:
          data['resourceId'] === null || data['resourceId'] === undefined
            ? null
            : String(data['resourceId']),
        version: typeof data['version'] === 'number' ? data['version'] : null,
        occurredAt: String(data['occurredAt'] ?? ''),
      });
      return;
    }
    if (eventName === 'snapshot' && data !== null) {
      this.options.onFrame({
        kind: 'snapshot',
        channel: String(data['channel'] ?? ''),
        scope: String(data['scope'] ?? ''),
        occurredAt: String(data['occurredAt'] ?? ''),
        snapshot: data['snapshot'],
      });
    }
  }

  private scheduleReconnect(generation: number): void {
    if (generation !== this.generation) {
      return;
    }
    this.consecutiveFailures += 1;
    this.options.onState(
      this.consecutiveFailures >= UNAVAILABLE_AFTER_FAILURES ? 'unavailable' : 'reconnecting',
    );

    const closing = this.pendingClosingDelay;
    this.pendingClosingDelay = null;
    const delayMs = closing
      ? randomBetween(closing.min * 1000, closing.max * 1000)
      : jitteredBackoff(this.consecutiveFailures);

    this.reconnectHandle = setTimeout(() => {
      this.reconnectHandle = null;
      void this.connect(generation);
    }, delayMs);
  }
}

/** Full-jitter exponential backoff: a random delay between 0 and the doubling ceiling, so a herd of clients does not retry in lockstep. */
function jitteredBackoff(failureCount: number): number {
  const ceiling = Math.min(RECONNECT_BASE_MS * 2 ** (failureCount - 1), RECONNECT_MAX_MS);
  return Math.random() * ceiling;
}

function randomBetween(minMs: number, maxMs: number): number {
  if (maxMs <= minMs) {
    return minMs;
  }
  return minMs + Math.random() * (maxMs - minMs);
}

function parseJson(text: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(text);
    return typeof parsed === 'object' && parsed !== null
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}
