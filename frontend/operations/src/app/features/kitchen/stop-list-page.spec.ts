import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope } from '../../core/api/operations-paths';
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

  async function render(
    get: ReturnType<typeof vi.fn>,
    put = vi.fn().mockReturnValue(of(undefined)),
  ): Promise<{
    put: ReturnType<typeof vi.fn>;
  }> {
    await TestBed.configureTestingModule({
      imports: [StopListPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
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
    expect(put.mock.calls[0][1].body).toEqual({ available: false, reasonCode: 'Списание партии' });

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
    expect(put.mock.calls[0][1].body).toEqual({
      available: false,
      reasonCode: 'OPERATIONS_STOP_LIST_TOGGLE',
    });
    expect(host.textContent).toContain('Restore');
  });
});
