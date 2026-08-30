import { Location } from '@angular/common';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { OrderDetailPane } from './order-detail-pane';
import { OrdersPage } from './orders-page';

/**
 * The one thing about the order board that cannot be added later.
 *
 * Opening an order must dock a column beside the queue rather than covering it —
 * an operator reading one order's address must still see another's approval
 * deadline run out. These tests exist to hold that shape regardless of what the
 * queue inside it does, so `OrderQueue`'s own dependencies (`ApiClient`,
 * `CurrentLocation`) are stubbed here rather than wired to real HTTP — this
 * file is about the dock, not about fetching orders (see `order-queue.spec.ts`
 * for that).
 */
describe('OrdersPage', () => {
  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [
            {
              path: 'orders',
              component: OrdersPage,
              children: [{ path: ':orderId', component: OrderDetailPane }],
            },
          ],
          // The application enables this; without it here the detail's required
          // route input is never populated and the test fails for the wrong reason.
          withComponentInputBinding(),
        ),
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal(null),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: ApiClient, useValue: { get: () => of({ value: [], version: null }) } },
      ],
    });
    TestBed.inject(I18n).setLocale('en');
  });

  it('shows the queue and no dock when no order is selected', async () => {
    const harness = await RouterTestingHarness.create('/orders');
    const host: HTMLElement = harness.routeNativeElement!;

    expect(host.querySelector('[data-testid="queue-body"]')).not.toBeNull();
    expect(host.querySelector('.orders--docked')).toBeNull();
    expect(host.querySelector('[data-testid="order-dock"]')?.getAttribute('aria-hidden')).toBe(
      'true',
    );
  });

  it('docks the detail beside the queue rather than replacing it', async () => {
    const harness = await RouterTestingHarness.create('/orders/018f-abc');
    const host: HTMLElement = harness.routeNativeElement!;

    // Both present at once. This is the assertion that fails the moment somebody
    // turns the detail into a modal or a full-page route.
    expect(host.querySelector('[data-testid="queue-body"]')).not.toBeNull();
    expect(host.querySelector('q-order-detail-pane')).not.toBeNull();
    expect(host.querySelector('.orders--docked')).not.toBeNull();
  });

  it('binds the order id from the URL, so a detail link is shareable', async () => {
    const harness = await RouterTestingHarness.create('/orders/018f-abc');
    expect(harness.routeNativeElement!.textContent).toContain('018f-abc');
  });

  it('keeps the outlet mounted so the browser can navigate back to the queue', async () => {
    const harness = await RouterTestingHarness.create('/orders/018f-abc');
    await harness.navigateByUrl('/orders');

    const host: HTMLElement = harness.routeNativeElement!;
    expect(host.querySelector('q-order-detail-pane')).toBeNull();
    expect(host.querySelector('[data-testid="queue-body"]')).not.toBeNull();
    expect(TestBed.inject(Location).path()).toBe('/orders');
  });
});
