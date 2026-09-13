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
    get: ReturnType<typeof vi.fn>,
    put = vi.fn().mockReturnValue(of(undefined)),
    scope: LocationScope = SCOPE,
  ): Promise<{
    put: ReturnType<typeof vi.fn>;
  }> {
    await TestBed.configureTestingModule({
      imports: [StopListPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(scope),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: ApiClient, useValue: { page: get, put } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(StopListPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return { put };
  }

  it('lists a location’s variants, split into available and on-stop', async () => {
    await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            { variantId: 'v1', productName: 'Lagman', category: 'Soups', available: true },
            { variantId: 'v2', productName: 'Somsa', category: 'Bakery', available: false },
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
        { provide: ApiClient, useValue: { page: vi.fn(), put: () => of(undefined) } },
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
            { variantId: 'v1', productName: 'Lagman', category: 'Soups', available: true },
            { variantId: 'v2', productName: 'Somsa', category: 'Bakery', available: true },
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

  it('disables the bulk actions until a reason is entered, then applies them to every selected row', async () => {
    const { put } = await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            { variantId: 'v1', productName: 'Lagman', category: 'Soups', available: true },
            { variantId: 'v2', productName: 'Somsa', category: 'Bakery', available: true },
          ],
          nextCursor: null,
        }),
      ),
    );
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelectorAll('[data-testid="dt-row-select"]')[0] as HTMLInputElement).click();
    (host.querySelectorAll('[data-testid="dt-row-select"]')[1] as HTMLInputElement).click();
    fixture.detectChanges();

    const stopButton = host.querySelector(
      '[data-testid="dt-bulk-action-stop"]',
    ) as HTMLButtonElement;
    expect(stopButton.disabled).toBe(true);

    const reasonInput = host.querySelector(
      '[data-testid="stop-list-bulk-reason"]',
    ) as HTMLInputElement;
    reasonInput.value = 'Списание партии';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(stopButton.disabled).toBe(false);

    stopButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(put).toHaveBeenCalledTimes(2);
    expect(put.mock.calls[0][0]).toBe(operationsPaths.inventoryVariantAvailability(SCOPE, 'v1'));
    expect(put.mock.calls[0][1].body).toEqual({ available: false, reasonCode: 'Списание партии' });
    expect(put.mock.calls[1][0]).toBe(operationsPaths.inventoryVariantAvailability(SCOPE, 'v2'));

    // The selection clears back through the two-way `[(selectedIds)]` binding.
    expect(host.querySelector('[data-testid="dt-bulk-bar"]')).toBeFalsy();
  });

  it('still supports the single-row toggle independently of the selection model', async () => {
    const { put } = await render(
      vi.fn().mockReturnValue(
        of({
          items: [{ variantId: 'v1', productName: 'Lagman', category: 'Soups', available: true }],
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

  // --------------------------------------------------- q-data-table migration: saved views (X.18)

  it('binds the tab to q-data-table’s filters, so saving and re-applying a view actually changes the visible rows', async () => {
    await render(
      vi.fn().mockReturnValue(
        of({
          items: [
            { variantId: 'v1', productName: 'Lagman', category: 'Soups', available: true },
            { variantId: 'v2', productName: 'Somsa', category: 'Bakery', available: false },
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
            { variantId: 'v1', productName: 'Lagman', category: 'Soups', available: true },
            { variantId: 'v2', productName: 'Somsa', category: 'Bakery', available: false },
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
            { variantId: 'v1', productName: 'Lagman', category: 'Soups', available: true },
            { variantId: 'v2', productName: 'Somsa', category: 'Bakery', available: false },
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
      { variantId: 'v1', productName: 'Lagman', category: 'Soups', available: true },
      { variantId: 'v2', productName: 'Somsa', category: 'Bakery', available: false },
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
    await render(
      vi.fn().mockReturnValue(of({ items, nextCursor: null })),
      undefined,
      otherLocation,
    );
    const otherHost = fixture.nativeElement as HTMLElement;
    expect(otherHost.textContent).toContain('Lagman');
    expect(otherHost.textContent).toContain('Somsa');
  });
});
