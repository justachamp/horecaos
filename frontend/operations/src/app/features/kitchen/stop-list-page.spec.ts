import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { StopListPage } from './stop-list-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const DEFAULT_COUNTS = { total: 0, available: 0, onStop: 0 };

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('StopListPage', () => {
  let fixture: ComponentFixture<StopListPage>;

  // `q-data-table`'s saved views and persisted filters live in real
  // `localStorage`, keyed by the static `viewId` — clear it so one test's
  // saved view or persisted tab never leaks into the next.
  beforeEach(() => {
    window.localStorage.clear();
  });

  async function render(
    page: ReturnType<typeof vi.fn>,
    options: {
      put?: ReturnType<typeof vi.fn>;
      get?: ReturnType<typeof vi.fn>;
      post?: ReturnType<typeof vi.fn>;
      scope?: LocationScope;
    } = {},
  ): Promise<{
    put: ReturnType<typeof vi.fn>;
    get: ReturnType<typeof vi.fn>;
    post: ReturnType<typeof vi.fn>;
  }> {
    const put = options.put ?? vi.fn().mockReturnValue(of(undefined));
    const get = options.get ?? vi.fn().mockReturnValue(of({ value: DEFAULT_COUNTS }));
    const post =
      options.post ??
      vi
        .fn()
        .mockReturnValue(of({ requestedCount: 0, appliedCount: 0, failedCount: 0, items: [] }));
    await TestBed.configureTestingModule({
      imports: [StopListPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(options.scope ?? SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: ApiClient, useValue: { page, put, get, post } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(StopListPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return { put, get, post };
  }

  it('lists a location’s variants, split into available and on-stop', async () => {
    await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            {
              variantId: 'v1',
              productName: 'Lagman',
              category: 'Soups',
              available: true,
              stopSource: 'UNKNOWN',
            },
            {
              variantId: 'v2',
              productName: 'Somsa',
              category: 'Bakery',
              available: false,
              stopSource: 'MANUAL',
            },
          ],
          nextCursor: null,
        }),
      ),
    );
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="dt-row"]')).toHaveLength(2);
    expect(host.textContent).toContain('Lagman');
    expect(host.textContent).toContain('Somsa');
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [StopListPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: ApiClient,
          useValue: { page: vi.fn(), put: () => of(undefined), get: vi.fn(), post: vi.fn() },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(StopListPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="stop-list-denied"]'),
    ).not.toBeNull();
  });

  // --------------------------------------------------- q-data-table migration: selection + bulk bar

  it('selects rows through q-data-table’s own model and shows the bulk bar with a reason field', async () => {
    await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            {
              variantId: 'v1',
              productName: 'Lagman',
              category: 'Soups',
              available: true,
              stopSource: 'UNKNOWN',
            },
            {
              variantId: 'v2',
              productName: 'Somsa',
              category: 'Bakery',
              available: true,
              stopSource: 'UNKNOWN',
            },
          ],
          nextCursor: null,
        }),
      ),
    );
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelectorAll('[data-testid="dt-row-select"]')[0] as HTMLInputElement).click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="dt-bulk-bar"]')).toBeTruthy();
    expect(host.querySelector('[data-testid="stop-list-bulk-reason"]')).toBeTruthy();
  });

  // --------------------------------------------------- P16: batch endpoint

  it('applies a bulk stop in one round trip to the batch endpoint, not one PUT per row', async () => {
    const post = vi.fn().mockReturnValue(
      of({
        requestedCount: 2,
        appliedCount: 2,
        failedCount: 0,
        items: [
          { variantId: 'v1', status: 'APPLIED', changed: true },
          { variantId: 'v2', status: 'APPLIED', changed: true },
        ],
      }),
    );
    const { put } = await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            {
              variantId: 'v1',
              productName: 'Lagman',
              category: 'Soups',
              available: true,
              stopSource: 'UNKNOWN',
            },
            {
              variantId: 'v2',
              productName: 'Somsa',
              category: 'Bakery',
              available: true,
              stopSource: 'UNKNOWN',
            },
          ],
          nextCursor: null,
        }),
      ),
      { post },
    );
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelectorAll('[data-testid="dt-row-select"]')[0] as HTMLInputElement).click();
    (host.querySelectorAll('[data-testid="dt-row-select"]')[1] as HTMLInputElement).click();
    fixture.detectChanges();

    const stopButton = host.querySelector(
      '[data-testid="dt-bulk-action-stop"]',
    ) as HTMLButtonElement;
    expect(stopButton.disabled).toBe(true);

    const reasonSelect = host.querySelector(
      '[data-testid="stop-list-bulk-reason"]',
    ) as HTMLSelectElement;
    reasonSelect.value = 'OUT_OF_STOCK';
    reasonSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(stopButton.disabled).toBe(false);

    stopButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    // One call, not one per row — the whole point of the batch endpoint.
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0][0]).toBe(operationsPaths.inventoryBulkAvailability(SCOPE));
    expect(post.mock.calls[0][1].body).toEqual({
      variantIds: ['v1', 'v2'],
      available: false,
      reasonCode: 'OUT_OF_STOCK',
    });
    expect(put).not.toHaveBeenCalled();

    // The selection clears back through the two-way `[(selectedIds)]` binding.
    expect(host.querySelector('[data-testid="dt-bulk-bar"]')).toBeFalsy();
  });

  it('reports a partial failure by its own per-item outcome, without failing the whole batch', async () => {
    const post = vi.fn().mockReturnValue(
      of({
        requestedCount: 2,
        appliedCount: 1,
        failedCount: 1,
        items: [
          { variantId: 'v1', status: 'APPLIED', changed: true },
          { variantId: 'v2', status: 'FAILED', changed: false, problemCode: 'VARIANT_NOT_STOCKED' },
        ],
      }),
    );
    await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            {
              variantId: 'v1',
              productName: 'Lagman',
              category: 'Soups',
              available: true,
              stopSource: 'UNKNOWN',
            },
            {
              variantId: 'v2',
              productName: 'Somsa',
              category: 'Bakery',
              available: true,
              stopSource: 'UNKNOWN',
            },
          ],
          nextCursor: null,
        }),
      ),
      { post },
    );
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelectorAll('[data-testid="dt-row-select"]')[0] as HTMLInputElement).click();
    (host.querySelectorAll('[data-testid="dt-row-select"]')[1] as HTMLInputElement).click();
    fixture.detectChanges();
    const reasonSelect = host.querySelector(
      '[data-testid="stop-list-bulk-reason"]',
    ) as HTMLSelectElement;
    reasonSelect.value = 'OUT_OF_STOCK';
    reasonSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (host.querySelector('[data-testid="dt-bulk-action-stop"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.textContent).toContain('1 items failed to update');
  });

  it('still supports the single-row toggle independently of the selection model', async () => {
    const { put } = await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            {
              variantId: 'v1',
              productName: 'Lagman',
              category: 'Soups',
              available: true,
              stopSource: 'UNKNOWN',
            },
          ],
          nextCursor: null,
        }),
      ),
    );
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="stop-list-toggle"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(put).toHaveBeenCalledTimes(1);
    expect(put.mock.calls[0][0]).toBe(operationsPaths.inventoryVariantAvailability(SCOPE, 'v1'));
    expect(put.mock.calls[0][1].body).toEqual({
      available: false,
      reasonCode: 'OPERATIONS_STOP_LIST_TOGGLE',
    });
    expect(host.textContent).toContain('Restore');
  });

  // --------------------------------------------------- P16: server-side counts

  it('renders the ALL badge from the server-side counts endpoint even with only one page loaded', async () => {
    const get = vi.fn().mockReturnValue(of({ value: { total: 250, available: 240, onStop: 10 } }));
    await render(
      vi.fn().mockReturnValue(
        // Deliberately just one loaded row — asserts the ALL badge reflects
        // the counts endpoint, not `items().length`.
        of({
          items: [
            {
              variantId: 'v1',
              productName: 'Lagman',
              category: 'Soups',
              available: true,
              stopSource: 'UNKNOWN',
            },
          ],
          nextCursor: 'v2',
        }),
      ),
      { get },
    );
    const host = fixture.nativeElement as HTMLElement;

    expect(get).toHaveBeenCalledTimes(1);
    const tabs = [...host.querySelectorAll('.tab')] as HTMLButtonElement[];
    expect(tabs[0].querySelector('.tab__count')?.textContent?.trim()).toBe('250');
  });

  it('never shows an AVAILABLE/ON_STOP badge the visible list cannot back up while more of the catalog is still loading', async () => {
    // gap map row 2.5's own trap, caught this time: a real total next to a
    // list that has not loaded that many rows yet. counts() resolves
    // instantly, but only one row (available) has been paged in — the
    // ON_STOP tab's own visible list would show zero rows against a badge
    // that claims 10, so both non-ALL badges must render "…" until the whole
    // catalog is loaded, not just until the counts call resolves.
    const get = vi.fn().mockReturnValue(of({ value: { total: 250, available: 240, onStop: 10 } }));
    await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            {
              variantId: 'v1',
              productName: 'Lagman',
              category: 'Soups',
              available: true,
              stopSource: 'UNKNOWN',
            },
          ],
          nextCursor: 'v2', // hasMore() stays true
        }),
      ),
      { get },
    );
    const host = fixture.nativeElement as HTMLElement;

    const tabs = [...host.querySelectorAll('.tab')] as HTMLButtonElement[];
    expect(tabs[0].querySelector('.tab__count')?.textContent?.trim()).toBe('250');
    expect(tabs[1].querySelector('.tab__count')?.textContent?.trim()).toBe('…');
    expect(tabs[2].querySelector('.tab__count')?.textContent?.trim()).toBe('…');
  });

  it('shows the real AVAILABLE/ON_STOP badges once the whole catalog is loaded, matching the visible list under each tab', async () => {
    const get = vi.fn().mockReturnValue(of({ value: { total: 2, available: 1, onStop: 1 } }));
    await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            {
              variantId: 'v1',
              productName: 'Lagman',
              category: 'Soups',
              available: true,
              stopSource: 'UNKNOWN',
            },
            {
              variantId: 'v2',
              productName: 'Somsa',
              category: 'Bakery',
              available: false,
              stopSource: 'MANUAL',
            },
          ],
          nextCursor: null, // hasMore() is false: the whole catalog is loaded
        }),
      ),
      { get },
    );
    const host = fixture.nativeElement as HTMLElement;

    const tabs = [...host.querySelectorAll('.tab')] as HTMLButtonElement[];
    expect(tabs[1].querySelector('.tab__count')?.textContent?.trim()).toBe('1');
    expect(tabs[2].querySelector('.tab__count')?.textContent?.trim()).toBe('1');

    tabs[2].click(); // ON_STOP
    fixture.detectChanges();
    const rows = host.querySelectorAll('[data-testid="dt-row"]');
    expect(rows.length).toBe(1);
    expect(host.textContent).toContain('Somsa');
  });

  // --------------------------------------------------- P16: search box

  it('a debounced keystroke in the search box becomes a server query for both the row list and the counts', async () => {
    const page = vi.fn().mockReturnValue(
      of({
        items: [
          {
            variantId: 'v1',
            productName: 'Lagman',
            category: 'Soups',
            available: true,
            stopSource: 'UNKNOWN',
          },
        ],
        nextCursor: null,
      }),
    );
    const get = vi.fn().mockReturnValue(of({ value: DEFAULT_COUNTS }));
    // Rendered under real timers — this page's own render() flushes with a
    // real setTimeout(0), which a fake clock would never advance on its own.
    await render(page, { get });
    const host = fixture.nativeElement as HTMLElement;
    const search = host.querySelector('[data-testid="stop-list-search"]') as HTMLInputElement;

    vi.useFakeTimers();
    try {
      search.value = 'лаг';
      search.dispatchEvent(new Event('input'));
      // The box itself updates immediately, before the debounce lands.
      fixture.detectChanges();
      expect(search.value).toBe('лаг');

      await vi.advanceTimersByTimeAsync(400);
      fixture.detectChanges();
    } finally {
      vi.useRealTimers();
    }

    const lastPageCall = page.mock.calls.at(-1)!;
    expect(lastPageCall[2]).toMatchObject({ search: 'лаг' });
    const lastGetCall = get.mock.calls.at(-1)!;
    expect(lastGetCall[1]).toMatchObject({ params: { search: 'лаг' } });
  });

  // --------------------------------------------------- P16: stop source explainer

  it('surfaces the structured stop source, distinguishing a kitchen stop from a POS push', async () => {
    await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            {
              variantId: 'v1',
              productName: 'Lagman',
              category: 'Soups',
              available: false,
              stopSource: 'MANUAL',
              stopReasonCode: 'OPERATIONS_STOP_LIST_TOGGLE',
              stopChangedAt: '2026-09-14T09:00:00Z',
            },
            {
              variantId: 'v2',
              productName: 'Somsa',
              category: 'Bakery',
              available: false,
              stopSource: 'POS',
              stopReasonCode: 'POS_STOP_LIST',
              stopChangedAt: '2026-09-14T09:00:00Z',
            },
            {
              variantId: 'v3',
              productName: 'Osh',
              category: 'Rice',
              available: true,
              stopSource: 'UNKNOWN',
            },
          ],
          nextCursor: null,
        }),
      ),
    );
    const host = fixture.nativeElement as HTMLElement;
    const sources = [...host.querySelectorAll('[data-testid="stop-list-source"]')].map(
      (el) => el.textContent,
    );

    expect(sources).toEqual(['Kitchen', 'POS']);
    // The available row shows no source at all — "why can't I sell this"
    // does not apply to a dish that can be sold.
    expect(host.querySelectorAll('[data-testid="stop-list-source"]')).toHaveLength(2);
  });

  // --------------------------------------------------- q-data-table migration: saved views (X.18)

  it('binds the tab to q-data-table’s filters, so saving and re-applying a view actually changes the visible rows', async () => {
    await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            {
              variantId: 'v1',
              productName: 'Lagman',
              category: 'Soups',
              available: true,
              stopSource: 'UNKNOWN',
            },
            {
              variantId: 'v2',
              productName: 'Somsa',
              category: 'Bakery',
              available: false,
              stopSource: 'MANUAL',
            },
          ],
          nextCursor: null,
        }),
      ),
    );
    const host = fixture.nativeElement as HTMLElement;
    const tabs = () => [...host.querySelectorAll('.tab')] as HTMLButtonElement[];

    // Switch to "On stop" and save that as a view.
    tabs()[2].click();
    fixture.detectChanges();
    expect(host.textContent).toContain('Somsa');
    expect(host.textContent).not.toContain('Lagman');

    (host.querySelector('[data-testid="dt-views-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const nameInput = host.querySelector('[data-testid="dt-view-name-input"]') as HTMLInputElement;
    nameInput.value = 'On stop';
    nameInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (host.querySelector('[data-testid="dt-view-save"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    // Close the views menu, the way an operator would before moving on —
    // it was toggled open above and Save does not close it itself.
    (host.querySelector('[data-testid="dt-views-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    // Back to "All" — both rows are visible again.
    tabs()[0].click();
    fixture.detectChanges();
    expect(host.textContent).toContain('Lagman');
    expect(host.textContent).toContain('Somsa');

    // Re-open the views menu and apply the saved view: this must change the
    // actual rendered rows, not just flip a signal nothing reads.
    (host.querySelector('[data-testid="dt-views-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    (host.querySelector('.q-data-table__view-apply') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(host.textContent).toContain('Somsa');
    expect(host.textContent).not.toContain('Lagman');
  });

  it('restores the last-used tab from a previous mount, the same way the legacy dashboard did', async () => {
    await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            {
              variantId: 'v1',
              productName: 'Lagman',
              category: 'Soups',
              available: true,
              stopSource: 'UNKNOWN',
            },
            {
              variantId: 'v2',
              productName: 'Somsa',
              category: 'Bakery',
              available: false,
              stopSource: 'MANUAL',
            },
          ],
          nextCursor: null,
        }),
      ),
    );
    const host = fixture.nativeElement as HTMLElement;
    ([...host.querySelectorAll('.tab')] as HTMLButtonElement[])[2].click();
    fixture.detectChanges();
    expect(host.textContent).toContain('Somsa');

    // A fresh mount — e.g. a reload — should come back on "On stop", not "All".
    TestBed.resetTestingModule();
    await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            {
              variantId: 'v1',
              productName: 'Lagman',
              category: 'Soups',
              available: true,
              stopSource: 'UNKNOWN',
            },
            {
              variantId: 'v2',
              productName: 'Somsa',
              category: 'Bakery',
              available: false,
              stopSource: 'MANUAL',
            },
          ],
          nextCursor: null,
        }),
      ),
    );
    const reloaded = fixture.nativeElement as HTMLElement;
    expect(reloaded.textContent).toContain('Somsa');
    expect(reloaded.textContent).not.toContain('Lagman');
  });

  it('never shows another location’s persisted tab on a shared terminal', async () => {
    const items = [
      {
        variantId: 'v1',
        productName: 'Lagman',
        category: 'Soups',
        available: true,
        stopSource: 'UNKNOWN',
      },
      {
        variantId: 'v2',
        productName: 'Somsa',
        category: 'Bakery',
        available: false,
        stopSource: 'MANUAL',
      },
    ];
    await render(vi.fn().mockReturnValue(of({ items, nextCursor: null })));
    const host = fixture.nativeElement as HTMLElement;
    ([...host.querySelectorAll('.tab')] as HTMLButtonElement[])[2].click();
    fixture.detectChanges();
    expect(host.textContent).toContain('Somsa');
    expect(host.textContent).not.toContain('Lagman');

    // A different location on the same shared browser session — the point
    // of `scopeKey`: this must land on "All", not location l1's "On stop".
    TestBed.resetTestingModule();
    const otherLocation: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l2' };
    await render(vi.fn().mockReturnValue(of({ items, nextCursor: null })), {
      scope: otherLocation,
    });
    const otherHost = fixture.nativeElement as HTMLElement;
    expect(otherHost.textContent).toContain('Lagman');
    expect(otherHost.textContent).toContain('Somsa');
  });
});
