import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../api/operations-paths';
import { CurrentLocation } from '../auth/current-location';
import { Capability, SessionCapabilities } from '../auth/session-capabilities';
import { StaffTokenStore } from '../auth/staff-token-store';
import { BrandOrderStream } from './brand-order-stream';
import { RealtimeClient } from './realtime-client';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

/** A connection that delivers `chunks` and then stays open, as a real stream does until the socket closes. */
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

const BRAND_SIGNAL =
  'event: signal\n' +
  'id: evt-b1\n' +
  'data: {"channel":"order_queue","scope":"BRAND:b1","resourceType":"Order","resourceId":"o7","version":5,"occurredAt":"2026-10-05T09:00:00Z"}\n\n';

/**
 * Gap map row `1.1`: the order board's brand-wide stream. The branch stream (`RealtimeClient`) hears
 * only its own branch; a board that reads every branch listens on this second connection, opened
 * only while it is on screen.
 */
describe('BrandOrderStream', () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  const scope = signal<LocationScope | null>(SCOPE);

  function setUp(heldCapabilities: readonly Capability[] = ['ORDER_READ']): BrandOrderStream {
    scope.set(SCOPE);
    TestBed.configureTestingModule({
      providers: [
        { provide: CurrentLocation, useValue: { scope } },
        { provide: StaffTokenStore, useValue: { accessToken: () => 'access-token-1' } },
        {
          provide: SessionCapabilities,
          useValue: { has: (capability: Capability) => heldCapabilities.includes(capability) },
        },
      ],
    });
    return TestBed.inject(BrandOrderStream);
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

  it('opens nothing until a screen asks', async () => {
    fetchMock.mockResolvedValue(openResponse());
    const stream = setUp();
    await vi.advanceTimersByTimeAsync(0);

    expect(fetchMock).not.toHaveBeenCalled();
    expect(stream.state()).toBeNull();
  });

  it('opens a connection at the brand for order_queue alone, from the operator’s own branch', async () => {
    fetchMock.mockResolvedValue(openResponse());
    const stream = setUp();

    stream.watch();
    await vi.advanceTimersByTimeAsync(0);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url, 'the path still names a branch: that is where location.read is checked').toContain(
      '/tenants/t1/brands/b1/locations/l1/operations/streams',
    );
    const query = new URL(url, 'http://console.test').searchParams;
    expect(query.get('scope')).toBe('BRAND:b1');
    expect(query.getAll('channels'), 'the one channel carried at the brand').toEqual([
      'order_queue',
    ]);
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer access-token-1');
    expect(stream.state()).toBe('open');
  });

  it('delivers the brand stream’s frames to its listeners, scope intact', async () => {
    fetchMock.mockResolvedValue(openResponse([BRAND_SIGNAL]));
    const stream = setUp();
    const frames: unknown[] = [];
    stream.onFrame((frame) => frames.push(frame));

    stream.watch();
    await vi.advanceTimersByTimeAsync(0);

    expect(frames).toEqual([
      {
        kind: 'signal',
        channel: 'order_queue',
        scope: 'BRAND:b1',
        resourceType: 'Order',
        resourceId: 'o7',
        version: 5,
        occurredAt: '2026-10-05T09:00:00Z',
      },
    ]);
  });

  it('is one connection however many screens ask, and closes with the last release', async () => {
    fetchMock.mockResolvedValue(openResponse());
    const stream = setUp();

    const releaseFirst = stream.watch();
    const releaseSecond = stream.watch();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const signalOfConnection = (fetchMock.mock.calls[0][1] as RequestInit).signal as AbortSignal;

    releaseFirst();
    expect(stream.state()).toBe('open');
    expect(signalOfConnection.aborted).toBe(false);

    releaseSecond();
    expect(stream.state()).toBeNull();
    expect(signalOfConnection.aborted).toBe(true);
  });

  it('a release that is called twice releases once', async () => {
    fetchMock.mockResolvedValue(openResponse());
    const stream = setUp();

    const release = stream.watch();
    const stillWatching = stream.watch();
    release();
    release();
    await vi.advanceTimersByTimeAsync(0);

    expect(stream.state(), 'the other screen is still watching').toBe('open');
    stillWatching();
    expect(stream.state()).toBeNull();
  });

  it('can be asked for again after it was closed', async () => {
    fetchMock.mockResolvedValue(openResponse());
    const stream = setUp();

    stream.watch()();
    stream.watch();
    await vi.advanceTimersByTimeAsync(0);

    expect(stream.state()).toBe('open');
  });

  it('follows the operator to another branch while watched, and not otherwise', async () => {
    fetchMock.mockResolvedValue(openResponse());
    const stream = setUp();
    await vi.advanceTimersByTimeAsync(0);

    // Not watched: a branch switch opens nothing.
    scope.set({ tenantId: 't1', brandId: 'b1', locationId: 'l2' });
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).not.toHaveBeenCalled();

    const release = stream.watch();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0] as string).toContain('/locations/l2/');

    scope.set({ tenantId: 't1', brandId: 'b1', locationId: 'l3' });
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][0] as string).toContain('/locations/l3/');

    release();
  });

  it('does not ask the server for a stream the operator holds no grant for: unavailable, and the poll does the work', async () => {
    fetchMock.mockResolvedValue(openResponse());
    const stream = setUp(['DELIVERY_PLAN_READ']);

    stream.watch();
    await vi.advanceTimersByTimeAsync(0);

    expect(fetchMock, 'no request that is certain to be refused').not.toHaveBeenCalled();
    expect(stream.state()).toBe('unavailable');
  });

  it('a refused stream degrades to unavailable on its own, and keeps retrying rather than giving up', async () => {
    fetchMock.mockResolvedValue(failedResponse(403));
    vi.spyOn(Math, 'random').mockReturnValue(0.5);
    const stream = setUp();

    stream.watch();
    await vi.advanceTimersByTimeAsync(0);
    expect(stream.state()).toBe('reconnecting');
    await vi.advanceTimersByTimeAsync(500);
    await vi.advanceTimersByTimeAsync(1_000);
    expect(stream.state()).toBe('unavailable');

    await vi.advanceTimersByTimeAsync(2_000);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('is independent of the branch stream: both open, each with its own state and its own frames', async () => {
    fetchMock.mockResolvedValue(openResponse());
    TestBed.configureTestingModule({
      providers: [
        { provide: CurrentLocation, useValue: { scope } },
        { provide: StaffTokenStore, useValue: { accessToken: () => 'access-token-1' } },
        { provide: SessionCapabilities, useValue: { has: () => true } },
      ],
    });
    scope.set(SCOPE);
    const branch = TestBed.inject(RealtimeClient);
    const brand = TestBed.inject(BrandOrderStream);
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock, 'the branch stream, opened for the session').toHaveBeenCalledTimes(1);

    brand.watch();
    await vi.advanceTimersByTimeAsync(0);

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(branch.state()).toBe('open');
    expect(brand.state()).toBe('open');
    const urls = fetchMock.mock.calls.map((call) => call[0] as string);
    expect(urls.filter((url) => url.includes('scope=BRAND'))).toHaveLength(1);
  });
});
