import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';

import { ApiClient } from '../../core/api/api-client';
import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { LocationsApi } from '../settings/locations/locations-api';
import { BoardResponse, KitchenApi } from './kitchen-api';
import { KitchenQueuePage } from './kitchen-queue-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const BOARD: BoardResponse = {
  tickets: [
    {
      ticketId: 'ticket-1',
      orderId: 'order-1',
      sequenceLabel: 'A-014',
      fulfilmentMode: 'DELIVERY',
      channelCode: 'telegram-bot',
      status: 'FIRED',
      releaseMode: 'AUTO_ON_CONFIRM',
      targetReadyAt: new Date(Date.now() + 20 * 60 * 1000).toISOString(),
      version: 1,
      createdAt: new Date().toISOString(),
      items: [
        {
          itemId: 'item-1',
          orderLineId: 'line-1',
          stationId: 'station-1',
          quantity: 2,
          routedBy: 'LOCATION_VARIANT',
          status: 'QUEUED',
          version: 1,
        },
      ],
    },
  ],
  warnings: [],
};

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('KitchenQueuePage', () => {
  let fixture: ComponentFixture<KitchenQueuePage>;

  async function render(): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [KitchenQueuePage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: KitchenApi,
          useValue: {
            board: () => Promise.resolve(BOARD),
            stations: () => Promise.resolve([]),
          },
        },
        {
          provide: LocationsApi,
          useValue: { serviceSummary: () => Promise.reject(new Error('no summary in this test')) },
        },
        {
          provide: ApiClient,
          useValue: { get: () => of({ value: { lines: [], kitchenNote: null }, version: null }) },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KitchenQueuePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('renders the live ticket with its channel and fulfilment mode', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="kitchen-ticket"]')).toHaveLength(1);
    expect(host.textContent).toContain('A-014');
    expect(host.textContent).toContain('telegram-bot');
  });

  it('expands to show the item row once the ticket header is clicked', async () => {
    await render();
    const host = fixture.nativeElement as HTMLElement;
    const header = host.querySelector('.ticket__header') as HTMLElement;

    header.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('.ticket__items')).not.toBeNull();
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [KitchenQueuePage],
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
          provide: KitchenApi,
          useValue: { board: () => Promise.resolve(BOARD), stations: () => Promise.resolve([]) },
        },
        {
          provide: LocationsApi,
          useValue: { serviceSummary: () => Promise.reject(new Error('n/a')) },
        },
        { provide: ApiClient, useValue: { get: () => of({ value: {}, version: null }) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(KitchenQueuePage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="kitchen-denied"]'),
    ).not.toBeNull();
  });
});
