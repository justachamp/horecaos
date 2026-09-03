import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { BoardResponse, KitchenApi, TicketResponse } from './kitchen-api';
import { BufferPage } from './buffer-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function held(overrides: Partial<TicketResponse>): TicketResponse {
  return {
    ticketId: 'ticket-1',
    orderId: 'order-1',
    sequenceLabel: 'A-014',
    fulfilmentMode: 'DELIVERY',
    channelCode: 'telegram-bot',
    status: 'HELD',
    releaseMode: 'MANUAL_HOLD',
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

describe('BufferPage', () => {
  let fixture: ComponentFixture<BufferPage>;

  async function render(kitchenApi: Partial<KitchenApi>): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [BufferPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: KitchenApi, useValue: kitchenApi },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(BufferPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists held tickets, soonest fire time first', async () => {
    const board: BoardResponse = {
      tickets: [
        held({
          ticketId: 't-late',
          sequenceLabel: 'A-020',
          releaseAt: new Date(Date.now() + 30 * 60_000).toISOString(),
        }),
        held({
          ticketId: 't-soon',
          sequenceLabel: 'A-021',
          releaseAt: new Date(Date.now() + 5 * 60_000).toISOString(),
        }),
      ],
      warnings: [],
    };
    await render({ board: () => Promise.resolve(board) });

    const rows = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[data-testid="buffer-row"]',
    );
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('A-021');
    expect(rows[1].textContent).toContain('A-020');
  });

  it('releases a ticket and removes it from the buffer', async () => {
    const board: BoardResponse = { tickets: [held({})], warnings: [] };
    const release = vi.fn().mockReturnValue(of({ ...held({}), status: 'FIRED' }));
    await render({ board: () => Promise.resolve(board), release });

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('[data-testid="buffer-row"]')).toHaveLength(1);

    (host.querySelector('[data-testid="buffer-release"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(release).toHaveBeenCalledWith(SCOPE, 'ticket-1', 1, 'OPERATIONS_BUFFER_RELEASE');
    expect(host.querySelector('[data-testid="buffer-empty"]')).not.toBeNull();
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [BufferPage],
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
    fixture = TestBed.createComponent(BufferPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="buffer-denied"]'),
    ).not.toBeNull();
  });
});
