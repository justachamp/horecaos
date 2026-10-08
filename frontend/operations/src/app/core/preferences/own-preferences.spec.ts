import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { environment } from '../../../environments/environment';
import { CurrentTenant } from '../auth/current-tenant';
import { Auth } from '../auth/auth';
import { ACCOUNT_STORAGE_KEY } from '../i18n/i18n';
import { OwnPreferences, PREFERENCE_WRITE_DEBOUNCE_MS, mirrorKeyFor } from './own-preferences';
import { OrderBoardDocument } from './preference-documents';
import { withColumnShown, withSavedViews, withoutColumnChoice } from './preference-edits';

const BASE = '/api/v1/operations/tenants/t1/staff/me/preferences';
const VIEW_ID = '0198a3c0-0000-7000-8000-000000000001';

function url(path: string): string {
  return `${environment.apiBaseUrl}${path}`;
}

/** Lets the continuation that issues the next request run: promise hops, then one macrotask. */
async function settle(): Promise<void> {
  await vi.advanceTimersByTimeAsync(0);
}

describe('OwnPreferences (ADR 0160)', () => {
  const subject = signal<string | null>('subject-1');
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    localStorage.clear();
    subject.set('subject-1');
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Auth, useValue: { subject } },
        {
          provide: CurrentTenant,
          useValue: { tenantId: signal('t1'), ensureLoaded: () => Promise.resolve() },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    localStorage.clear();
  });

  function preferences(): OwnPreferences {
    return TestBed.inject(OwnPreferences);
  }

  /** Loads the person's documents from a platform that holds `surfaces`. */
  async function load(surfaces: Record<string, unknown> = {}): Promise<OwnPreferences> {
    const prefs = preferences();
    const loaded = prefs.ensureLoaded();
    await settle();
    http.expectOne(url(BASE)).flush({ surfaces });
    await loaded;
    return prefs;
  }

  function stored(document: unknown, version = 1) {
    return { document, documentSchema: 1, version };
  }

  // ------------------------------------------------------------------ reading

  it('reads the documents once per signed-in person and holds them in signals', async () => {
    const prefs = await load({
      ORDER_BOARD: stored({ columns: { show: ['createdBy'], hide: ['courier'] } }),
      SHELL: stored({ defaultLocationId: '0198a3c0-0000-7000-8000-0000000000b1' }),
    });
    await prefs.ensureLoaded();

    expect(prefs.orderBoard().columns).toEqual({ show: ['createdBy'], hide: ['courier'] });
    expect(prefs.shell().defaultLocationId).toBe('0198a3c0-0000-7000-8000-0000000000b1');
    expect(prefs.status()).toBe('ready');
    expect(prefs.persistent()).toBe(true);
    // The second ensureLoaded replayed the first: there was exactly one GET (expectOne above).
    http.verify();
  });

  it('knows nothing of a person who has saved nothing: the code’s default applies', async () => {
    const prefs = await load({});

    expect(prefs.orderBoard()).toEqual({});
    expect(prefs.shell()).toEqual({});
  });

  it('draws the first paint from the browser’s mirror, before the platform has answered', () => {
    localStorage.setItem(
      mirrorKeyFor('subject-1'),
      JSON.stringify({ ORDER_BOARD: { columns: { hide: ['customer'] } } }),
    );

    const prefs = preferences();

    expect(prefs.orderBoard().columns?.hide).toEqual(['customer']);
    expect(prefs.status()).toBe('loading');
  });

  it('lets the platform’s document replace the mirror’s, and rewrites the mirror from it', async () => {
    localStorage.setItem(
      mirrorKeyFor('subject-1'),
      JSON.stringify({ ORDER_BOARD: { columns: { hide: ['customer'] } } }),
    );

    const prefs = await load({ ORDER_BOARD: stored({ columns: { show: ['discount'] } }) });

    expect(prefs.orderBoard().columns).toEqual({ show: ['discount'] });
    expect(JSON.parse(localStorage.getItem(mirrorKeyFor('subject-1')) ?? '{}')).toEqual({
      ORDER_BOARD: { columns: { show: ['discount'] } },
    });
  });

  it('drops a column, a tab or a view this release does not know instead of trusting it', async () => {
    const prefs = await load({
      ORDER_BOARD: stored({
        columns: { show: ['courierEta', 'discount'], hide: ['source'] },
        savedViews: [
          {
            id: VIEW_ID,
            label: 'Known',
            tab: 'attention',
            filters: { lateOnly: true, theme: 'x' },
          },
          { id: 'not-a-uuid', label: 'Broken', tab: 'attention', filters: {} },
          {
            id: '0198a3c0-0000-7000-8000-000000000002',
            label: 'Unknown tab',
            tab: 'inbox',
            filters: {},
          },
        ],
      }),
    });

    expect(prefs.orderBoard().columns).toEqual({ show: ['discount'] });
    expect(prefs.orderBoard().savedViews).toEqual([
      { id: VIEW_ID, label: 'Known', tab: 'attention', filters: { lateOnly: true } },
    ]);
  });

  it('keeps the mirror and the code’s default when the read fails, and the board still works', async () => {
    localStorage.setItem(
      mirrorKeyFor('subject-1'),
      JSON.stringify({ ORDER_BOARD: { columns: { show: ['acceptedBy'] } } }),
    );
    const prefs = preferences();
    const loaded = prefs.ensureLoaded();
    await settle();
    http
      .expectOne(url(BASE))
      .flush({ status: 500, code: 'INTERNAL_ERROR' }, { status: 500, statusText: 'x' });
    await loaded;

    expect(prefs.status()).toBe('error');
    expect(prefs.orderBoard().columns?.show).toEqual(['acceptedBy']);
    expect(prefs.persistent()).toBe(true);
  });

  it('is unavailable for an account the tenant keeps no record for: changes live for the session and are never written', async () => {
    const prefs = preferences();
    const loaded = prefs.ensureLoaded();
    await settle();
    http
      .expectOne(url(BASE))
      .flush({ status: 404, code: 'RESOURCE_NOT_FOUND' }, { status: 404, statusText: 'x' });
    await loaded;

    expect(prefs.status()).toBe('unavailable');
    expect(prefs.persistent()).toBe(false);

    prefs.updateOrderBoard((document) => withColumnShown(document, 'discount', true));
    await vi.advanceTimersByTimeAsync(PREFERENCE_WRITE_DEBOUNCE_MS * 3);

    expect(prefs.orderBoard().columns?.show).toEqual(['discount']);
    http.expectNone(url(`${BASE}/ORDER_BOARD`));
  });

  // ------------------------------------------------------------------ writing

  it('applies a change at once and writes it through after a quiet moment, with no If-Match for a first write', async () => {
    const prefs = await load({});

    prefs.updateOrderBoard((document) => withColumnShown(document, 'createdBy', true));

    expect(prefs.orderBoard().columns?.show).toEqual(['createdBy']);
    http.expectNone(url(`${BASE}/ORDER_BOARD`));

    await vi.advanceTimersByTimeAsync(PREFERENCE_WRITE_DEBOUNCE_MS);
    const request = http.expectOne(url(`${BASE}/ORDER_BOARD`));
    expect(request.request.method).toBe('PUT');
    expect(request.request.headers.has('If-Match')).toBe(false);
    expect(request.request.headers.has('Idempotency-Key')).toBe(true);
    expect(request.request.body).toEqual({ document: { columns: { show: ['createdBy'] } } });
    request.flush(stored({ columns: { show: ['createdBy'] } }, 1));
    await settle();

    // The next write carries the version that came back.
    prefs.updateOrderBoard((document) => withColumnShown(document, 'courier', false));
    await vi.advanceTimersByTimeAsync(PREFERENCE_WRITE_DEBOUNCE_MS);
    const second = http.expectOne(url(`${BASE}/ORDER_BOARD`));
    expect(second.request.headers.get('If-Match')).toBe('W/"1"');
    second.flush(stored({ columns: { show: ['createdBy'], hide: ['courier'] } }, 2));
    await settle();
  });

  it('turns several clicks in a row into one write', async () => {
    const prefs = await load({});

    prefs.updateOrderBoard((document) => withColumnShown(document, 'createdBy', true));
    await vi.advanceTimersByTimeAsync(PREFERENCE_WRITE_DEBOUNCE_MS / 2);
    prefs.updateOrderBoard((document) => withColumnShown(document, 'acceptedBy', true));
    await vi.advanceTimersByTimeAsync(PREFERENCE_WRITE_DEBOUNCE_MS / 2);
    http.expectNone(url(`${BASE}/ORDER_BOARD`));
    await vi.advanceTimersByTimeAsync(PREFERENCE_WRITE_DEBOUNCE_MS);

    const request = http.expectOne(url(`${BASE}/ORDER_BOARD`));
    expect(request.request.body).toEqual({
      document: { columns: { show: ['createdBy', 'acceptedBy'] } },
    });
    request.flush(stored({ columns: { show: ['createdBy', 'acceptedBy'] } }));
    await settle();
  });

  it('keeps the document a change from the default: turning a default column back on removes it', async () => {
    const prefs = await load({ ORDER_BOARD: stored({ columns: { hide: ['courier'] } }) });

    prefs.updateOrderBoard((document) => withColumnShown(document, 'courier', true));

    expect(prefs.orderBoard()).toEqual({});
    await vi.advanceTimersByTimeAsync(PREFERENCE_WRITE_DEBOUNCE_MS);
    const request = http.expectOne(url(`${BASE}/ORDER_BOARD`));
    expect(request.request.body).toEqual({ document: {} });
    request.flush(stored({}, 2));
    await settle();
  });

  it('writes the default branch to its own surface', async () => {
    const prefs = await load({});

    prefs.updateShell(() => ({ defaultLocationId: '0198a3c0-0000-7000-8000-0000000000b1' }));
    await vi.advanceTimersByTimeAsync(PREFERENCE_WRITE_DEBOUNCE_MS);

    const request = http.expectOne(url(`${BASE}/SHELL`));
    expect(request.request.body).toEqual({
      document: { defaultLocationId: '0198a3c0-0000-7000-8000-0000000000b1' },
    });
    request.flush(stored({ defaultLocationId: '0198a3c0-0000-7000-8000-0000000000b1' }));
    await settle();
  });

  it('flush() writes what is waiting now, without waiting out the quiet moment', async () => {
    const prefs = await load({});
    prefs.updateOrderBoard((document) => withColumnShown(document, 'discount', true));

    const flushed = prefs.flush();
    await settle();
    const request = http.expectOne(url(`${BASE}/ORDER_BOARD`));
    request.flush(stored({ columns: { show: ['discount'] } }));
    await flushed;

    expect(request.request.method).toBe('PUT');
  });

  // ---------------------------------------------------------------- two devices

  it('on a stale version re-reads, puts its own one change on what the other device left, and tries once more', async () => {
    const prefs = await load({ ORDER_BOARD: stored({ columns: { hide: ['customer'] } }, 1) });

    prefs.updateOrderBoard((document) => withColumnShown(document, 'createdBy', true));
    await vi.advanceTimersByTimeAsync(PREFERENCE_WRITE_DEBOUNCE_MS);
    http
      .expectOne(url(`${BASE}/ORDER_BOARD`))
      .flush(
        { status: 409, code: 'STALE_VERSION', expected: 1, actual: 2 },
        { status: 409, statusText: 'x' },
      );
    await settle();

    // The other device hid «Курьер» meanwhile: that is what the re-read returns.
    http.expectOne(url(BASE)).flush({
      surfaces: { ORDER_BOARD: stored({ columns: { hide: ['customer', 'courier'] } }, 2) },
    });
    await settle();

    const retry = http.expectOne(url(`${BASE}/ORDER_BOARD`));
    expect(retry.request.headers.get('If-Match')).toBe('W/"2"');
    expect(retry.request.body).toEqual({
      document: { columns: { show: ['createdBy'], hide: ['customer', 'courier'] } },
    });
    retry.flush(stored({ columns: { show: ['createdBy'], hide: ['customer', 'courier'] } }, 3));
    await settle();

    expect(prefs.orderBoard().columns).toEqual({
      show: ['createdBy'],
      hide: ['customer', 'courier'],
    });
    expect(prefs.writeFailed()).toBe(false);
  });

  it('drops the write when the second try is stale too, and leaves the screen as it is', async () => {
    const prefs = await load({ ORDER_BOARD: stored({}, 1) });

    prefs.updateOrderBoard((document) => withColumnShown(document, 'discount', true));
    await vi.advanceTimersByTimeAsync(PREFERENCE_WRITE_DEBOUNCE_MS);
    http
      .expectOne(url(`${BASE}/ORDER_BOARD`))
      .flush({ status: 409, code: 'STALE_VERSION' }, { status: 409, statusText: 'x' });
    await settle();
    http.expectOne(url(BASE)).flush({ surfaces: { ORDER_BOARD: stored({}, 2) } });
    await settle();
    http
      .expectOne(url(`${BASE}/ORDER_BOARD`))
      .flush({ status: 409, code: 'STALE_VERSION' }, { status: 409, statusText: 'x' });
    await settle();

    http.expectNone(url(`${BASE}/ORDER_BOARD`));
    expect(prefs.writeFailed()).toBe(true);
    expect(prefs.orderBoard().columns?.show).toEqual(['discount']);
  });

  it('says a write failed when the platform refuses the document, and keeps what is on screen', async () => {
    const prefs = await load({});

    prefs.updateOrderBoard((document) => withColumnShown(document, 'discount', true));
    await vi.advanceTimersByTimeAsync(PREFERENCE_WRITE_DEBOUNCE_MS);
    http
      .expectOne(url(`${BASE}/ORDER_BOARD`))
      .flush({ status: 400, code: 'VALIDATION_FAILED' }, { status: 400, statusText: 'x' });
    await settle();

    expect(prefs.writeFailed()).toBe(true);
    expect(prefs.orderBoard().columns?.show).toEqual(['discount']);
  });

  // -------------------------------------------------------------------- reset

  it('resets a surface: gone from the screen, the mirror and the platform', async () => {
    const prefs = await load({ ORDER_BOARD: stored({ columns: { show: ['discount'] } }, 4) });

    const reset = prefs.reset('ORDER_BOARD');
    await settle();
    const request = http.expectOne(url(`${BASE}/ORDER_BOARD`));
    expect(request.request.method).toBe('DELETE');
    request.flush(null, { status: 204, statusText: 'No Content' });
    await reset;

    expect(prefs.orderBoard()).toEqual({});
    expect(JSON.parse(localStorage.getItem(mirrorKeyFor('subject-1')) ?? '{}')).toEqual({});

    // After a reset the next write is a first write again.
    prefs.updateOrderBoard((document) => withColumnShown(document, 'discount', true));
    await vi.advanceTimersByTimeAsync(PREFERENCE_WRITE_DEBOUNCE_MS);
    const next = http.expectOne(url(`${BASE}/ORDER_BOARD`));
    expect(next.request.headers.has('If-Match')).toBe(false);
    next.flush(stored({ columns: { show: ['discount'] } }, 1));
    await settle();
  });

  // ------------------------------------------------------------ a shared PC

  it('removes every mirror at sign-out, so the next person on this browser inherits nothing', async () => {
    const prefs = await load({
      ORDER_BOARD: stored({
        columns: { show: ['createdBy'] },
        savedViews: [{ id: VIEW_ID, label: 'Mine', tab: 'all', filters: {} }],
      }),
    });
    localStorage.setItem(ACCOUNT_STORAGE_KEY, 'uz-Latn');
    expect(localStorage.getItem(mirrorKeyFor('subject-1'))).not.toBeNull();
    expect(prefs.orderBoard().savedViews).toHaveLength(1);

    subject.set(null);
    TestBed.tick();

    expect(prefs.orderBoard()).toEqual({});
    const keys = Object.keys(localStorage);
    expect(keys.filter((key) => key.startsWith('horecaos.operations.preferences.'))).toEqual([]);
    expect(localStorage.getItem(ACCOUNT_STORAGE_KEY)).toBeNull();

    // And the next person to sign in starts from nothing, not from the last person's document.
    subject.set('subject-2');
    TestBed.tick();
    expect(prefs.orderBoard()).toEqual({});
    expect(prefs.status()).toBe('loading');
  });

  it('removes a mirror a previous person left behind, however they left, when someone else signs in', () => {
    localStorage.setItem(
      mirrorKeyFor('subject-left-without-signing-out'),
      JSON.stringify({ ORDER_BOARD: { columns: { show: ['createdBy'] } } }),
    );

    const prefs = preferences();

    expect(localStorage.getItem(mirrorKeyFor('subject-left-without-signing-out'))).toBeNull();
    expect(prefs.orderBoard()).toEqual({});
  });

  it('leaves the mirror alone on a cold start with nobody signed in yet', () => {
    localStorage.setItem(
      mirrorKeyFor('subject-1'),
      JSON.stringify({ ORDER_BOARD: { columns: { show: ['createdBy'] } } }),
    );
    localStorage.setItem(ACCOUNT_STORAGE_KEY, 'uz-Latn');
    subject.set(null);

    preferences();
    TestBed.tick();

    expect(localStorage.getItem(mirrorKeyFor('subject-1'))).not.toBeNull();
    expect(localStorage.getItem(ACCOUNT_STORAGE_KEY)).toBe('uz-Latn');
  });

  it('leaves nothing on the browser for an account the tenant keeps no record for', async () => {
    const prefs = preferences();
    const loaded = prefs.ensureLoaded();
    await settle();
    http
      .expectOne(url(BASE))
      .flush({ code: 'RESOURCE_NOT_FOUND', status: 404 }, { status: 404, statusText: 'x' });
    await loaded;

    prefs.updateOrderBoard((document) => withColumnShown(document, 'discount', true));

    expect(prefs.orderBoard().columns?.show).toEqual(['discount']);
    expect(localStorage.getItem(mirrorKeyFor('subject-1'))).toBeNull();
  });

  it('never shows one person’s document to another who signs in on the same tab without a sign-out', async () => {
    const prefs = await load({ ORDER_BOARD: stored({ columns: { show: ['createdBy'] } }) });

    subject.set('subject-2');
    TestBed.tick();

    expect(prefs.orderBoard()).toEqual({});
  });

  it('keys the mirror by a hash of the subject, never the subject itself', () => {
    expect(mirrorKeyFor('subject-1')).not.toContain('subject-1');
    expect(mirrorKeyFor('subject-1')).toBe(mirrorKeyFor('subject-1'));
    expect(mirrorKeyFor('subject-1')).not.toBe(mirrorKeyFor('subject-2'));
  });

  it('tolerates a browser whose storage throws, and a mirror that is not JSON', () => {
    localStorage.setItem(mirrorKeyFor('subject-1'), '{not json');
    expect(preferences().orderBoard()).toEqual({});

    const broken = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    try {
      preferences().updateOrderBoard((document) => withColumnShown(document, 'discount', true));
      expect(preferences().orderBoard().columns?.show).toEqual(['discount']);
    } finally {
      broken.mockRestore();
    }
  });
});

describe('the document helpers (ADR 0160)', () => {
  const view = { id: VIEW_ID, label: 'Late', tab: 'attention', filters: {} } as const;

  it('turns an optional column on and off as a change from the default, in the platform’s order', () => {
    let document: OrderBoardDocument = {};
    document = withColumnShown(document, 'discount', true);
    document = withColumnShown(document, 'createdBy', true);
    expect(document.columns?.show).toEqual(['createdBy', 'discount']);

    document = withColumnShown(document, 'createdBy', false);
    expect(document.columns?.show).toEqual(['discount']);
  });

  it('hides and shows a default column through hide only', () => {
    let document: OrderBoardDocument = withColumnShown({}, 'courier', false);
    expect(document.columns).toEqual({ hide: ['courier'] });

    document = withColumnShown(document, 'courier', true);
    expect(document).toEqual({});
  });

  it('clears the column choice and keeps the saved views, and the other way round', () => {
    const both = withSavedViews(withColumnShown({}, 'discount', true), [view]);

    expect(withoutColumnChoice(both)).toEqual({ savedViews: [view] });
    expect(withSavedViews(both, [])).toEqual({ columns: { show: ['discount'] } });
  });
});
