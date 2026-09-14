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

  it('places a ticket with no promise yet on manual hold, with no reason (gap map row 2.2)', async () => {
    const board: BoardResponse = { tickets: [held({})], warnings: [] };
    const reschedule = vi
      .fn()
      .mockReturnValue(of({ ...held({}), releaseMode: 'MANUAL_HOLD', releaseAt: null }));
    await render({ board: () => Promise.resolve(board), reschedule });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="buffer-hold"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(reschedule).toHaveBeenCalledWith(SCOPE, 'ticket-1', 1, 'MANUAL_HOLD', null, undefined);
  });

  it('edits a held ticket’s fire time through PUT .../release-schedule', async () => {
    const board: BoardResponse = { tickets: [held({})], warnings: [] };
    const rescheduled = { ...held({}), releaseMode: 'SCHEDULED' as const };
    const reschedule = vi.fn().mockReturnValue(of(rescheduled));
    await render({ board: () => Promise.resolve(board), reschedule });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="buffer-edit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const input = host.querySelector('[data-testid="buffer-edit-release-at"]') as HTMLInputElement;
    expect(input).not.toBeNull();
    input.value = '2026-09-14T19:30';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="buffer-edit-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(reschedule).toHaveBeenCalledTimes(1);
    const call = reschedule.mock.calls[0];
    expect(call[0]).toEqual(SCOPE);
    expect(call[1]).toBe('ticket-1');
    expect(call[2]).toBe(1);
    expect(call[3]).toBe('SCHEDULED');
    expect(new Date(call[4] as string).getTime()).toBe(new Date('2026-09-14T19:30').getTime());
    // No promise on this fixture, so no reason is required or sent.
    expect(call[5]).toBeUndefined();

    expect(host.querySelector('[data-testid="buffer-edit-row"]')).toBeNull();
  });

  it('requires a reason to hold or re-time a ticket that already has a promise', async () => {
    const board: BoardResponse = {
      tickets: [
        held({
          targetReadyAt: new Date(Date.now() + 20 * 60_000).toISOString(),
          prepEstimateSeconds: 300,
        }),
      ],
      warnings: [],
    };
    await render({ board: () => Promise.resolve(board), reschedule: vi.fn() });

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="buffer-edit"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    // This ticket has no releaseAt yet, so the editor opens with the fire-time
    // field blank — an explicit hold on a ticket that already has a promise,
    // which the reason field reflects immediately, with nothing typed yet.
    expect(host.querySelector('[data-testid="buffer-edit-reason"]')).not.toBeNull();

    (host.querySelector('[data-testid="buffer-edit-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="buffer-edit-error"]')).not.toBeNull();
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
