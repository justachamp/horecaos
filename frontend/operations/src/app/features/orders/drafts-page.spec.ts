import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
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

  it('lists drafts and breaks them down by channel', async () => {
    await render(
      {
        list: () =>
          Promise.resolve([
            draft({ cartId: 'a', channelId: 'chan-1' }),
            draft({ cartId: 'b', channelId: 'chan-1' }),
            draft({ cartId: 'c', channelId: 'chan-2' }),
          ]),
      },
      {
        list: () =>
          Promise.resolve([
            {
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
            },
          ]),
      },
    );

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('[data-testid="draft-row"]')).toHaveLength(3);
    const breakdown = host.querySelector('[data-testid="drafts-breakdown"]');
    expect(breakdown?.textContent).toContain('Telegram bot');
    expect(breakdown?.textContent).toContain('2');
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
