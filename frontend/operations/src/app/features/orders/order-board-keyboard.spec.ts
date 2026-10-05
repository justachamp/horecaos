import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Location } from '@angular/common';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { PLATFORM_DEFAULT_LATENESS_POLICY } from '../../core/lateness-policy';
import { LatenessPolicyApi } from '../../core/lateness-policy-api';
import { ShortcutRegistry } from '../../shared/keyboard/shortcut-registry';
import { ReferenceDataApi } from '../settings/reference-data/reference-data-api';
import { OrderActionsApi } from './order-actions-api';
import { OrderBulkActionsApi } from './order-bulk-actions-api';
import { OrderCounts, zeroTabCounts } from './order-counts';
import { OrderQueue } from './order-queue';
import { OrderSummaryResponse } from './order-summary';
import { RejectReasonsApi } from './order-reject-reasons-api';

/**
 * orders.md §2.12: the board is keyboard-first. These drive the registry the way the shell's one
 * `keydown` listener does -- `dispatch(event)` with focus on a real row -- so what is proved is the
 * board's keys and what they reach, not the DOM plumbing of the listener (`shell.spec.ts`).
 */

const SCOPE = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function order(overrides: Partial<OrderSummaryResponse>): OrderSummaryResponse {
  return {
    orderId: 'order-1',
    publicOrderNumber: '0001',
    status: 'RECEIVED',
    createdAt: new Date().toISOString(),
    totalMinor: 100_000,
    currency: 'UZS',
    feeMinor: 0,
    discountMinor: 0,
    ...overrides,
  };
}

/**
 * Awaiting approval, a confirmed order with a courier to assign, and a finished one with nothing to do.
 *
 * The «Все» tab sorts newest first, so each order gets its own `createdAt`, one second apart in the
 * order the rows must appear. Three `new Date()` calls usually land in the same millisecond and
 * tie — but not always: on a loaded CI runner they straddled a millisecond boundary, the rows came
 * out in a different order, and `j` seemed to move two rows.
 */
const FIXTURE_NOW = Date.now();
const ORDERS: readonly OrderSummaryResponse[] = [
  order({
    orderId: 'order-a',
    publicOrderNumber: '0001',
    status: 'AWAITING_APPROVAL',
    createdAt: new Date(FIXTURE_NOW).toISOString(),
    actions: [{ action: 'APPROVE' }, { action: 'REJECT' }],
  }),
  order({
    orderId: 'order-b',
    publicOrderNumber: '0002',
    status: 'RECEIVED',
    createdAt: new Date(FIXTURE_NOW - 1_000).toISOString(),
    actions: [{ action: 'CANCEL' }, { action: 'ASSIGN_COURIER' }],
  }),
  order({
    orderId: 'order-c',
    publicOrderNumber: '0003',
    status: 'COMPLETED',
    createdAt: new Date(FIXTURE_NOW - 2_000).toISOString(),
    actions: [],
  }),
];

function configure(
  options: {
    readonly actions?: Partial<OrderActionsApi>;
    readonly bulk?: boolean;
    readonly getOrders?: ReturnType<typeof vi.fn>;
    readonly rejectReasons?: ReturnType<typeof vi.fn>;
  } = {},
): ReturnType<typeof vi.fn> {
  const getOrders =
    options.getOrders ??
    vi.fn().mockReturnValue(of({ value: { items: ORDERS, nextCursor: null }, version: null }));
  TestBed.configureTestingModule({
    providers: [
      provideRouter([
        { path: 'orders', component: OrderQueue },
        { path: 'orders/new', component: OrderQueue },
        { path: 'orders/:id', component: OrderQueue },
      ]),
      {
        provide: CurrentLocation,
        useValue: {
          scope: signal(SCOPE),
          denied: signal(false),
          ensureLoaded: () => Promise.resolve(),
          options: signal([]),
          selectLocation: () => undefined,
        },
      },
      { provide: ApiClient, useValue: { get: getOrders } },
      { provide: OrderCounts, useValue: { forOrders: () => Promise.resolve(zeroTabCounts()) } },
      { provide: OrderActionsApi, useValue: options.actions ?? {} },
      {
        provide: RejectReasonsApi,
        useValue: { list: options.rejectReasons ?? vi.fn().mockResolvedValue([]) },
      },
      { provide: ReferenceDataApi, useValue: { list: () => Promise.resolve([]) } },
      { provide: OrderBulkActionsApi, useValue: { submit: vi.fn() } },
      {
        provide: SessionCapabilities,
        useValue: {
          has: (capability: string) => options.bulk === true && capability === 'ORDER_BULK_ACTION',
        },
      },
      {
        provide: LatenessPolicyApi,
        useValue: {
          resolve: () => Promise.resolve(PLATFORM_DEFAULT_LATENESS_POLICY),
          read: () => Promise.resolve(PLATFORM_DEFAULT_LATENESS_POLICY),
        },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  return getOrders;
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

async function open(url = '/orders?tab=all'): Promise<HTMLElement> {
  const harness = await RouterTestingHarness.create(url);
  await flush();
  return harness.routeNativeElement!;
}

function rows(host: HTMLElement): HTMLElement[] {
  return [...host.querySelectorAll<HTMLElement>('[data-testid="order-row"]')];
}

function focusRow(host: HTMLElement, index: number): HTMLElement {
  const row = rows(host)[index];
  row.focus();
  return row;
}

/** What the shell's listener does: hands the event, aimed at whatever has focus, to the registry. */
function press(key: string, target?: HTMLElement): boolean {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
  Object.defineProperty(event, 'target', {
    value: target ?? document.activeElement ?? document.body,
  });
  return TestBed.inject(ShortcutRegistry).dispatch(event);
}

const focusedOrderId = (): string | undefined =>
  (document.activeElement as HTMLElement | null)?.dataset['orderId'];

afterEach(() => {
  localStorage.clear();
  document.body.querySelectorAll('input.stray').forEach((node) => node.remove());
});

describe('OrderQueue: the board’s keys (orders.md §2.12)', () => {
  describe('moving between rows', () => {
    it('j and the down arrow move focus to the next row, and k and the up arrow to the previous', async () => {
      configure();
      const host = await open();
      focusRow(host, 0);

      press('j');
      expect(focusedOrderId()).toBe('order-b');
      press('ArrowDown');
      expect(focusedOrderId()).toBe('order-c');
      press('k');
      expect(focusedOrderId()).toBe('order-b');
      press('ArrowUp');
      expect(focusedOrderId()).toBe('order-a');
    });

    it('stops at the first and the last row instead of wrapping', async () => {
      configure();
      const host = await open();
      focusRow(host, 0);

      press('k');
      expect(focusedOrderId()).toBe('order-a');

      focusRow(host, 2);
      press('j');
      expect(focusedOrderId()).toBe('order-c');
    });

    it('starts from the top on j, and from the bottom on k, when no row has focus yet', async () => {
      configure();
      await open();
      (document.activeElement as HTMLElement | null)?.blur();

      press('j', document.body);
      expect(focusedOrderId()).toBe('order-a');

      (document.activeElement as HTMLElement | null)?.blur();
      press('k', document.body);
      expect(focusedOrderId()).toBe('order-c');
    });

    it('does nothing on an empty board, and leaves the key to the browser', async () => {
      configure({
        getOrders: vi
          .fn()
          .mockReturnValue(of({ value: { items: [], nextCursor: null }, version: null })),
      });
      await open();

      expect(press('j', document.body)).toBe(false);
    });
  });

  describe('acting on the focused order', () => {
    it('a approves the focused order when it awaits approval', async () => {
      const approve = vi.fn().mockReturnValue(
        of({
          orderId: 'order-a',
          status: 'CONFIRMED',
          version: 2,
          applied: true,
          effectiveDecisionId: null,
          effectiveAction: null,
        }),
      );
      configure({ actions: { approve } });
      const host = await open();
      focusRow(host, 0);

      expect(press('a')).toBe(true);
      await flush();

      expect(approve).toHaveBeenCalledOnce();
      expect(approve).toHaveBeenCalledWith(SCOPE, 'order-a', expect.any(String));
    });

    it('a does nothing on an order that does not offer approval, and says nothing to the server', async () => {
      const approve = vi.fn();
      configure({ actions: { approve } });
      const host = await open();
      focusRow(host, 1);

      expect(press('a')).toBe(false);
      await flush();

      expect(approve).not.toHaveBeenCalled();
    });

    it('a does nothing with no row focused: there is no "the order" to approve', async () => {
      const approve = vi.fn();
      configure({ actions: { approve } });
      await open();
      (document.activeElement as HTMLElement | null)?.blur();

      expect(press('a', document.body)).toBe(false);
      expect(approve).not.toHaveBeenCalled();
    });

    it('x opens the cancel dialog and never cancels by itself', async () => {
      const cancel = vi.fn();
      configure({ actions: { cancel } });
      const host = await open();
      focusRow(host, 1);

      expect(press('x')).toBe(true);
      await flush();

      expect(host.querySelector('[data-testid="order-reason-dialog"]')).not.toBeNull();
      expect(cancel).not.toHaveBeenCalled();
    });

    it('x on an order still awaiting approval opens the refusal dialog it offers in place of a cancellation', async () => {
      const reject = vi.fn();
      const rejectReasons = vi.fn().mockResolvedValue([
        {
          code: 'ITEM_UNAVAILABLE',
          displayOrder: 1,
          requiresNote: false,
          labels: { ru: 'Нет', 'uz-Latn': 'Yoʻq', en: 'Item unavailable' },
        },
      ]);
      configure({ actions: { reject }, rejectReasons });
      const host = await open();
      focusRow(host, 0);

      expect(press('x')).toBe(true);
      await flush();

      expect(host.querySelector('[data-testid="order-reject-reason-dialog"]')).not.toBeNull();
      expect(reject).not.toHaveBeenCalled();
    });

    it('x does nothing on an order with nothing to cancel', async () => {
      configure();
      const host = await open();
      focusRow(host, 2);

      expect(press('x')).toBe(false);
    });

    it('c takes the operator to the order, where the courier is assigned', async () => {
      configure();
      const host = await open();
      focusRow(host, 1);

      expect(press('c')).toBe(true);
      await flush();

      expect(TestBed.inject(Location).path()).toContain('/orders/order-b');
    });

    it('does not act on the board behind a hand-written reject dialog: r, n and the tab digits are not "refresh", "new order" and "switch tab" while a reason is being chosen', async () => {
      const rejectReasons = vi.fn().mockResolvedValue([
        {
          code: 'ITEM_UNAVAILABLE',
          displayOrder: 1,
          requiresNote: false,
          labels: { ru: 'Нет', 'uz-Latn': 'Yoʻq', en: 'Item unavailable' },
        },
      ]);
      const getOrders = configure({ rejectReasons });
      const host = await open('/orders?tab=attention');
      focusRow(host, 0);
      press('x');
      await flush();
      const option = host.querySelector<HTMLInputElement>(
        '[data-testid="order-reject-reason-option-ITEM_UNAVAILABLE"]',
      )!;
      option.focus();
      const readsBefore = getOrders.mock.calls.length;

      // The dialog is not built on q-modal, so it is the aria-modal marker that tells the keyboard.
      expect(press('r', option)).toBe(false);
      expect(press('n', option)).toBe(false);
      expect(press('3', option)).toBe(false);
      await flush();

      expect(getOrders.mock.calls.length).toBe(readsBefore);
      expect(TestBed.inject(Location).path()).toContain('tab=attention');
    });

    it('stands down for a key pressed while a dialog is open, so x cannot stack a second dialog', async () => {
      configure();
      const host = await open();
      focusRow(host, 1);
      press('x');
      await flush();
      expect(host.querySelector('[data-testid="order-reason-dialog"]')).not.toBeNull();

      expect(press('x')).toBe(false);
    });
  });

  describe('the board itself', () => {
    it('1 to 7 switch tab, in the tab strip’s order', async () => {
      configure();
      await open('/orders?tab=attention');

      press('3', document.body);
      await flush();
      expect(TestBed.inject(Location).path()).toContain('tab=preparing');

      press('7', document.body);
      await flush();
      expect(TestBed.inject(Location).path()).toContain('tab=all');

      press('1', document.body);
      await flush();
      expect(TestBed.inject(Location).path()).toContain('tab=attention');
    });

    it('r refreshes the board', async () => {
      const getOrders = configure();
      await open();
      const before = getOrders.mock.calls.length;

      expect(press('r', document.body)).toBe(true);
      await flush();

      expect(getOrders.mock.calls.length).toBeGreaterThan(before);
    });

    it('n starts a new order', async () => {
      configure();
      await open();

      press('n', document.body);
      await flush();

      expect(TestBed.inject(Location).path()).toBe('/orders/new');
    });

    it('/ focuses the search box, and Esc in it clears it and lets go', async () => {
      configure();
      const host = await open();
      const search = host.querySelector<HTMLInputElement>(
        '[data-testid="order-queue-filter-search"]',
      )!;

      press('/', document.body);
      expect(document.activeElement).toBe(search);

      search.value = 'abc';
      search.dispatchEvent(new Event('input'));
      press('Escape', search);

      expect(search.value).toBe('');
      expect(document.activeElement).not.toBe(search);
    });

    it('leaves letters alone while the operator types in the search box', async () => {
      const approve = vi.fn();
      configure({ actions: { approve } });
      const host = await open();
      const search = host.querySelector<HTMLInputElement>(
        '[data-testid="order-queue-filter-search"]',
      )!;
      search.focus();

      expect(press('a', search)).toBe(false);
      expect(press('j', search)).toBe(false);
      expect(approve).not.toHaveBeenCalled();
    });
  });

  describe('selection', () => {
    it('Space selects the focused row for an operator who may bulk-act, and Esc leaves selection mode', async () => {
      configure({ bulk: true });
      const host = await open();
      focusRow(host, 1);

      expect(press(' ')).toBe(true);
      await flush();

      expect(host.querySelector('[data-testid="order-queue-bulk-bar"]')).not.toBeNull();
      expect(
        host.querySelectorAll<HTMLInputElement>('[data-testid="order-row-select"]')[1].checked,
      ).toBe(true);

      press('Escape', document.body);
      await flush();
      expect(host.querySelector('[data-testid="order-queue-bulk-bar"]')).toBeNull();
    });

    it('Space does nothing for an operator without the bulk capability: there is no selection to make', async () => {
      configure({ bulk: false });
      const host = await open();
      focusRow(host, 1);

      expect(press(' ')).toBe(false);
    });

    it('leaves Space on a row’s own checkbox to the checkbox', async () => {
      configure({ bulk: true });
      const host = await open();
      const checkbox = host.querySelectorAll<HTMLInputElement>(
        '[data-testid="order-row-select"]',
      )[0];
      checkbox.focus();

      expect(press(' ', checkbox)).toBe(false);
    });
  });

  describe('the cheat-sheet entry', () => {
    it('is registered while the board is on screen, lists the documented keys, and not a key nothing handles', async () => {
      configure();
      await open();

      const scope = TestBed.inject(ShortcutRegistry)
        .scopes()
        .find((candidate) => candidate.id === 'orders-board')!;
      const caps = scope.shortcuts.flatMap((shortcut) => shortcut.caps);

      expect(caps).toEqual(
        expect.arrayContaining([
          '/',
          'j',
          'k',
          '↓',
          '↑',
          'Enter',
          'Space',
          '1–7',
          'a',
          'x',
          'c',
          'n',
          'r',
          'Esc',
        ]),
      );
      // «print to POS» has no row action to bind to; listing it would promise a key that does nothing.
      expect(caps).not.toContain('p');
      expect(scope.title()).toBe('Order board');
    });

    it('is gone when the board is', async () => {
      configure();
      const harness = await RouterTestingHarness.create('/orders?tab=all');
      await flush();
      const registry = TestBed.inject(ShortcutRegistry);
      expect(registry.scopes().some((candidate) => candidate.id === 'orders-board')).toBe(true);

      harness.fixture.destroy();

      expect(registry.scopes().some((candidate) => candidate.id === 'orders-board')).toBe(false);
    });
  });
});
