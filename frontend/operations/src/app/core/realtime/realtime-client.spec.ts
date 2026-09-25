import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../api/operations-paths';
import { CurrentLocation } from '../auth/current-location';
import { Capability, SessionCapabilities } from '../auth/session-capabilities';
import { StaffTokenStore } from '../auth/staff-token-store';
import { RealtimeClient } from './realtime-client';

/** Every capability a `DEFAULT_CHANNELS` entry can require — the default fixture, so existing tests connect to every channel exactly as before this class started filtering by capability. */
const ALL_STREAM_CAPABILITIES: readonly Capability[] = [
  'ORDER_READ',
  'DELIVERY_PLAN_READ',
  'KITCHEN_TICKET_READ',
];

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/** A `ReadableStream`-shaped fake whose `read()` answers a fixed script, without needing a real stream. */
function fakeBody(chunks: readonly string[]): {
  getReader(): { read(): Promise<{ value?: Uint8Array; done: boolean }>; cancel(): Promise<void> };
} {
  const encoder = new TextEncoder();
  const queue = [
    ...chunks.map((chunk) => ({ value: encoder.encode(chunk), done: false })),
    { done: true },
  ];
  let index = 0;
  return {
    getReader: () => ({
      read: () => Promise.resolve(index < queue.length ? queue[index++] : { done: true }),
      cancel: () => Promise.resolve(),
    }),
  };
}

function okResponse(chunks: readonly string[]): Response {
  return { ok: true, status: 200, body: fakeBody(chunks) } as unknown as Response;
}

/**
 * A connection that delivers `chunks` and then stays open rather than
 * ending — a real stream never resolves `read()` again until either another
 * frame arrives or the socket actually closes. Used for "and nothing further
 * happens in this test" assertions, so a fixture's own stream ending does
 * not schedule a reconnect the test never mocked a response for.
 */
function openResponse(chunks: readonly string[] = []): Response {
  const encoder = new TextEncoder();
  const queue = chunks.map((chunk) => ({ value: encoder.encode(chunk), done: false }));
  let index = 0;
  return {
    ok: true,
    status: 200,
    body: {
      getReader: () => ({
        read: () =>
          index < queue.length ? Promise.resolve(queue[index++]) : new Promise(() => undefined),
        cancel: () => Promise.resolve(),
      }),
    },
  } as unknown as Response;
}

function failedResponse(status = 500): Response {
  return { ok: false, status, body: null } as unknown as Response;
}

describe('RealtimeClient', () => {
  let fetchMock: ReturnType<typeof vi.fn>;

  function setUp(
    heldCapabilities: readonly Capability[] = ALL_STREAM_CAPABILITIES,
  ): RealtimeClient {
    TestBed.configureTestingModule({
      providers: [
        {
          provide: CurrentLocation,
          useValue: { scope: signal<LocationScope | null>(SCOPE) },
        },
        { provide: StaffTokenStore, useValue: { accessToken: () => 'access-token-1' } },
        {
          provide: SessionCapabilities,
          useValue: { has: (capability: Capability) => heldCapabilities.includes(capability) },
        },
      ],
    });
    return TestBed.inject(RealtimeClient);
  }

  beforeEach(() => {
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('connects to the streams endpoint for the resolved location, subscribed to every declared channel', async () => {
    fetchMock.mockResolvedValueOnce(okResponse([]));
    setUp();
    await vi.advanceTimersByTimeAsync(0);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/tenants/t1/brands/b1/locations/l1/operations/streams');
    expect(url).toContain('channels=order_queue');
    expect(url).toContain('channels=counters');
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer access-token-1');
    expect((init.headers as Record<string, string>)['Last-Event-Id']).toBeUndefined();
    expect(url).toContain('channels=order_detail');
    expect(url).toContain('channels=dispatch_board');
    expect(url).toContain('channels=kitchen_board');
  });

  it('omits dispatch_board — and only dispatch_board — for a role that holds ORDER_READ but not DELIVERY_PLAN_READ, so the server never refuses the whole connection over one channel this operator cannot have', async () => {
    fetchMock.mockResolvedValueOnce(okResponse([]));
    setUp(['ORDER_READ']);
    await vi.advanceTimersByTimeAsync(0);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('channels=order_queue');
    expect(url).toContain('channels=order_detail');
    expect(url).toContain('channels=counters');
    expect(url).not.toContain('dispatch_board');
    expect(url).not.toContain('kitchen_board');
  });

  it('omits kitchen_board for a role that holds ORDER_READ but not KITCHEN_TICKET_READ, the same per-channel refusal dispatch_board already proves', async () => {
    fetchMock.mockResolvedValueOnce(okResponse([]));
    setUp(['ORDER_READ', 'DELIVERY_PLAN_READ']);
    await vi.advanceTimersByTimeAsync(0);

    const [url] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('channels=dispatch_board');
    expect(url).not.toContain('kitchen_board');
  });

  it('requests kitchen_board for a KDS/VDU wallboard session holding KITCHEN_TICKET_READ', async () => {
    fetchMock.mockResolvedValueOnce(okResponse([]));
    setUp(['KITCHEN_TICKET_READ']);
    await vi.advanceTimersByTimeAsync(0);

    const [url] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('channels=kitchen_board');
    expect(url).not.toContain('dispatch_board');
  });

  it('a reconnect after a dropped connection sends Last-Event-Id, from the last frame actually seen', async () => {
    fetchMock.mockResolvedValueOnce(
      okResponse([
        'event: signal\n' +
          'id: evt-1\n' +
          'data: {"channel":"order_queue","scope":"LOCATION:l1","resourceType":"Order","resourceId":"o1","version":2,"occurredAt":"2026-09-14T09:00:00Z"}\n\n',
      ]),
    );
    fetchMock.mockResolvedValueOnce(openResponse());
    setUp();

    // First connect, its one frame, and the stream then ending (a dropped
    // connection) — the reconnect this schedules is what carries the id.
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(30_000);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const secondCallInit = fetchMock.mock.calls[1][1] as RequestInit;
    expect((secondCallInit.headers as Record<string, string>)['Last-Event-Id']).toBe('evt-1');
  });

  it('a resync frame is delivered to every listener', async () => {
    fetchMock.mockResolvedValueOnce(
      okResponse([
        'event: resync\nid: r-1\ndata: {"reason":"NO_REPLAY_BUFFER","channels":["order_queue"]}\n\n',
      ]),
    );
    const client = setUp();
    const received: string[] = [];
    client.onFrame((frame) => received.push(frame.kind));

    await vi.advanceTimersByTimeAsync(0);

    expect(received).toEqual(['resync']);
  });

  it('a signal frame reaches a listener with its fields intact', async () => {
    fetchMock.mockResolvedValueOnce(
      okResponse([
        'event: signal\n' +
          'id: evt-9\n' +
          'data: {"channel":"order_queue","scope":"LOCATION:l1","resourceType":"Order","resourceId":"o1","version":3,"occurredAt":"2026-09-14T09:00:00Z"}\n\n',
      ]),
    );
    const client = setUp();
    const frames: unknown[] = [];
    client.onFrame((frame) => frames.push(frame));

    await vi.advanceTimersByTimeAsync(0);

    expect(frames).toEqual([
      {
        kind: 'signal',
        channel: 'order_queue',
        scope: 'LOCATION:l1',
        resourceType: 'Order',
        resourceId: 'o1',
        version: 3,
        occurredAt: '2026-09-14T09:00:00Z',
      },
    ]);
  });

  it('a heartbeat comment produces no frame', async () => {
    fetchMock.mockResolvedValueOnce(okResponse([': keep-alive\n\n']));
    const client = setUp();
    const frames: unknown[] = [];
    client.onFrame((frame) => frames.push(frame));

    await vi.advanceTimersByTimeAsync(0);

    expect(frames).toEqual([]);
  });

  it('reconnects with a jittered delay after a failed connect, bounded by the exponential ceiling', async () => {
    fetchMock.mockResolvedValueOnce(failedResponse());
    fetchMock.mockResolvedValueOnce(okResponse([]));
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    setUp();

    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // failureCount 1 → ceiling = min(1000 * 2^0, 30000) = 1000; jitter 0.5 → 500ms exactly.
    await vi.advanceTimersByTimeAsync(499);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("a `closing` frame's own reconnectAfterSeconds range is honoured for the next reconnect", async () => {
    fetchMock.mockResolvedValueOnce(
      okResponse([
        'event: closing\nid: c-1\ndata: {"reason":"GRANTS_CHANGED","reconnectAfterSecondsMin":4,"reconnectAfterSecondsMax":4}\n\n',
      ]),
    );
    fetchMock.mockResolvedValueOnce(okResponse([]));
    setUp();

    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // min === max === 4s: the server named an exact delay, not a range.
    await vi.advanceTimersByTimeAsync(3_999);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('degrades to `unavailable` after repeated failures, but keeps retrying rather than giving up', async () => {
    fetchMock.mockResolvedValue(failedResponse());
    // A fixed, non-zero jitter so each backoff step has one exact, known
    // delay — see `jitteredBackoff`: ceiling doubles per failure (1s, 2s,
    // 4s, capped at 30s), and 0.5 always lands on exactly half of it.
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    const client = setUp();

    expect(client.state()).toBe('connecting');

    // Attempt 1 fails immediately: ceiling 1000ms → 500ms scheduled.
    await vi.advanceTimersByTimeAsync(0);
    expect(client.state()).toBe('reconnecting');
    expect(fetchMock).toHaveBeenCalledTimes(1);

    // Attempt 2 fails: ceiling 2000ms → 1000ms scheduled.
    await vi.advanceTimersByTimeAsync(500);
    expect(client.state()).toBe('reconnecting');
    expect(fetchMock).toHaveBeenCalledTimes(2);

    // Attempt 3 fails: the third consecutive failure crosses
    // UNAVAILABLE_AFTER_FAILURES. Ceiling 4000ms → 2000ms scheduled next.
    await vi.advanceTimersByTimeAsync(1_000);
    expect(client.state()).toBe('unavailable');
    expect(fetchMock).toHaveBeenCalledTimes(3);

    // Still retrying, not stopped — every consumer's own 10s poll is what
    // correctness depends on, never this state.
    await vi.advanceTimersByTimeAsync(2_000);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('reconnects when the operator switches branch, to the new location', async () => {
    fetchMock.mockResolvedValue(okResponse([]));
    const scope = signal<LocationScope | null>(SCOPE);
    TestBed.configureTestingModule({
      providers: [
        { provide: CurrentLocation, useValue: { scope } },
        { provide: StaffTokenStore, useValue: { accessToken: () => 'access-token-1' } },
      ],
    });
    TestBed.inject(RealtimeClient);
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);

    scope.set({ tenantId: 't1', brandId: 'b1', locationId: 'l2' });
    await vi.advanceTimersByTimeAsync(0);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    const secondUrl = fetchMock.mock.calls[1][0] as string;
    expect(secondUrl).toContain('/locations/l2/');
  });
});
