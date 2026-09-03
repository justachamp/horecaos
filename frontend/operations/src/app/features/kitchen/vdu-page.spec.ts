import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { BoardResponse, KitchenApi, TicketResponse } from './kitchen-api';
import { VduPage } from './vdu-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function ticket(overrides: Partial<TicketResponse>): TicketResponse {
  return {
    ticketId: 'ticket-1',
    orderId: 'order-1',
    sequenceLabel: 'A-014',
    fulfilmentMode: 'DELIVERY',
    channelCode: null,
    status: 'FIRED',
    releaseMode: 'AUTO_ON_CONFIRM',
    releaseAt: null,
    targetReadyAt: null,
    version: 1,
    createdAt: new Date().toISOString(),
    items: [],
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('VduPage', () => {
  let fixture: ComponentFixture<VduPage>;

  async function render(board: BoardResponse): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [VduPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: KitchenApi, useValue: { board: () => Promise.resolve(board) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(VduPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('renders every live ticket’s sequence label, ready ones first', async () => {
    await render({
      tickets: [
        ticket({ ticketId: 'a', sequenceLabel: 'A-002', status: 'FIRED' }),
        ticket({ ticketId: 'b', sequenceLabel: 'A-001', status: 'READY' }),
      ],
      warnings: [],
    });

    const cards = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[data-testid="vdu-card"]',
    );
    expect(cards).toHaveLength(2);
    expect(cards[0].textContent?.trim()).toBe('A-001');
    expect(cards[1].textContent?.trim()).toBe('A-002');
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [VduPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: KitchenApi, useValue: { board: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(VduPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="vdu-denied"]'),
    ).not.toBeNull();
  });
});
