import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { DraftCartResponse, DraftsApi } from './drafts-api';
import { DraftsPage } from './drafts-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function draft(overrides: Partial<DraftCartResponse>): DraftCartResponse {
  return {
    cartId: '11111111-1111-1111-1111-111111111111',
    createdAt: new Date().toISOString(),
    channelId: 'chan-1',
    locationId: 'l1',
    customerAccountId: null,
    guestReferenceHash: 'hash',
    expiresAt: new Date().toISOString(),
    status: 'ACTIVE',
    lineCount: 2,
    ...overrides,
  };
}

function channel(overrides: Partial<ChannelView>): ChannelView {
  return {
    id: 'chan-1',
    code: 'TELEGRAM',
    systemType: 'TELEGRAM',
    displayName: 'Telegram bot',
    status: 'ACTIVE',
    pricePlaneChannelId: null,
    externallyPriced: false,
    guestOrdersAllowed: true,
    providerInstallationId: null,
    version: 1,
    locationCount: 0,
    enabledPaymentMethodCount: 0,
    enabledFulfillmentModes: [],
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DraftsPage', () => {
  let fixture: ComponentFixture<DraftsPage>;

  async function render(
    draftsApi: Partial<DraftsApi>,
    channelsApi: Partial<SalesChannelsApi> = { list: () => Promise.resolve([]) },
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [DraftsPage],
      providers: [
        provideRouter([]),
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            options: signal([]),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: DraftsApi, useValue: draftsApi },
        { provide: SalesChannelsApi, useValue: channelsApi },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DraftsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists drafts and breaks abandonment down by channel', async () => {
    await render(
      {
        list: () =>
          Promise.resolve([
            draft({ cartId: 'a', channelId: 'chan-1', status: 'ACTIVE' }),
            draft({ cartId: 'b', channelId: 'chan-1', status: 'ABANDONED' }),
            draft({ cartId: 'c', channelId: 'chan-2', status: 'EXPIRED' }),
          ]),
      },
      { list: () => Promise.resolve([channel({})]) },
    );

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('[data-testid="draft-row"]')).toHaveLength(3);
    const breakdown = host.querySelector('[data-testid="drafts-breakdown"]');
    expect(breakdown?.textContent).toContain('Telegram bot');
  });

  // ----------------------------------------------------------- abandonment classification

  it('excludes an ACTIVE cart from the "Отказы по каналам" abandonment breakdown', async () => {
    await render({
      list: () =>
        Promise.resolve([
          draft({ cartId: 'a', channelId: 'chan-1', status: 'ACTIVE' }),
          draft({ cartId: 'b', channelId: 'chan-1', status: 'ABANDONED' }),
        ]),
    });

    const host = fixture.nativeElement as HTMLElement;
    const rows = host.querySelectorAll('[data-testid="drafts-breakdown-row"]');
    expect(rows).toHaveLength(1);
    // One ABANDONED cart out of two ACTIVE+ABANDONED — the live ACTIVE cart
    // must not itself count as an abandonment, only as part of the denominator.
    expect(rows[0].querySelector('[data-testid="drafts-breakdown-rate"]')?.textContent).toContain(
      '(1 of 2)',
    );
  });

  it('shows the abandonment rate with its denominator, not a bare count', async () => {
    await render({
      list: () =>
        Promise.resolve([
          draft({ cartId: 'a', channelId: 'chan-1', status: 'ACTIVE' }),
          draft({ cartId: 'b', channelId: 'chan-1', status: 'ACTIVE' }),
          draft({ cartId: 'c', channelId: 'chan-1', status: 'ACTIVE' }),
          draft({ cartId: 'd', channelId: 'chan-1', status: 'ABANDONED' }),
          draft({ cartId: 'e', channelId: 'chan-1', status: 'ABANDONED' }),
        ]),
    });

    const host = fixture.nativeElement as HTMLElement;
    const rate = host.querySelector('[data-testid="drafts-breakdown-rate"]')?.textContent ?? '';
    // 2 of 5 abandoned — a rate (40%), not the raw abandoned count (2) alone.
    expect(rate).toContain('40%');
    expect(rate).toContain('(2 of 5)');
  });

  // ------------------------------------------------------------------------- filters

  it('sends the period and channel filters to the drafts read', async () => {
    const list = vi.fn().mockResolvedValue([]);
    await render({ list }, { list: () => Promise.resolve([channel({})]) });
    await flushMicrotasks();
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    const from = host.querySelector('[data-testid="drafts-from"]') as HTMLInputElement;
    const to = host.querySelector('[data-testid="drafts-to"]') as HTMLInputElement;
    from.value = '2026-09-01';
    from.dispatchEvent(new Event('change'));
    to.value = '2026-09-10';
    to.dispatchEvent(new Event('change'));
    (host.querySelector('[data-testid="drafts-apply-period"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    const channelSelect = host.querySelector(
      '[data-testid="drafts-channel-filter"]',
    ) as HTMLSelectElement;
    channelSelect.value = 'chan-1';
    channelSelect.dispatchEvent(new Event('change'));
    await flushMicrotasks();
    fixture.detectChanges();

    const lastCall = list.mock.calls.at(-1);
    expect(lastCall?.[1]?.from).toContain('2026-09-01');
    expect(lastCall?.[1]?.to).toContain('2026-09-10');
    expect(lastCall?.[1]?.channelId).toBe('chan-1');
  });

  it('narrows by owner type client-side without a request the endpoint cannot serve', async () => {
    const list = vi
      .fn()
      .mockResolvedValue([
        draft({ cartId: 'a', customerAccountId: 'acct-1', guestReferenceHash: null }),
        draft({ cartId: 'b', customerAccountId: null, guestReferenceHash: 'hash' }),
      ]);
    await render({ list });

    const host = fixture.nativeElement as HTMLElement;
    const ownerSelect = host.querySelector(
      '[data-testid="drafts-owner-filter"]',
    ) as HTMLSelectElement;
    ownerSelect.value = 'GUEST';
    ownerSelect.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(host.querySelectorAll('[data-testid="draft-row"]')).toHaveLength(1);
    // Owner type never reaches the server — `OperationsOrderController.drafts`
    // has no such parameter — so only the one request from initial load happened.
    expect(list).toHaveBeenCalledTimes(1);
  });

  // ------------------------------------------------------------------------- ordering

  it('sorts drafts newest first explicitly, not by trusting the response order', async () => {
    const older = draft({
      cartId: 'older',
      createdAt: new Date('2026-09-01T00:00:00Z').toISOString(),
    });
    const newer = draft({
      cartId: 'newer',
      createdAt: new Date('2026-09-10T00:00:00Z').toISOString(),
    });
    // Deliberately out of order — the page must not assume the backend's
    // `ORDER BY created_at DESC` already put them right.
    await render({ list: () => Promise.resolve([older, newer]) });

    const host = fixture.nativeElement as HTMLElement;
    const firstColumnCells = [...host.querySelectorAll('[data-testid="draft-row"] td')].filter(
      (_, index) => index % 9 === 0,
    );
    expect(firstColumnCells[0]?.textContent).toBe('newer'.slice(0, 8));
    expect(firstColumnCells[1]?.textContent).toBe('older'.slice(0, 8));
  });

  // ------------------------------------------------------------------------- rows

  it('renders the location and expiry columns already on the response', async () => {
    await render({
      list: () =>
        Promise.resolve([
          draft({ cartId: 'a', locationId: 'l1', expiresAt: '2026-09-20T10:00:00Z' }),
        ]),
    });

    const host = fixture.nativeElement as HTMLElement;
    const row = host.querySelector('[data-testid="draft-row"]');
    // No `CurrentLocation.options` entry for `l1` in this fixture — falls
    // back to the truncated id, the same treatment the cart id already gets.
    expect(row?.textContent).toContain('l1');
    // `formatDateTime` renders `DD.MM HH:mm` (`datetime.ts`'s own doc) — no
    // year, so this asserts the expiry actually reached the row, not a blank.
    expect(row?.textContent).toContain('20.09');
  });

  it('offers "open customer" only for an account cart, never a guest one', async () => {
    await render({
      list: () =>
        Promise.resolve([
          draft({ cartId: 'a', customerAccountId: 'acct-1', guestReferenceHash: null }),
          draft({ cartId: 'b', customerAccountId: null, guestReferenceHash: 'hash' }),
        ]),
    });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('[data-testid="draft-open-customer"]')).toHaveLength(1);
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [DraftsPage],
      providers: [
        provideRouter([]),
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(null),
            denied: signal(true),
            options: signal([]),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: DraftsApi, useValue: { list: vi.fn() } },
        { provide: SalesChannelsApi, useValue: { list: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DraftsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="drafts-denied"]'),
    ).not.toBeNull();
  });
});
