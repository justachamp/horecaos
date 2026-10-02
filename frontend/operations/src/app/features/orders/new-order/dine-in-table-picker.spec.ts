import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { ReservationsApi, TableAvailability } from '../reservations-api';
import { SessionView, TableSessionsApi } from '../table-sessions-api';
import { DineInTablePicker, TablePick } from './dine-in-table-picker';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function party(overrides: Partial<SessionView> = {}): SessionView {
  return {
    sessionId: 'ses-1',
    reservationId: null,
    partySize: 3,
    businessDate: '2026-09-29',
    openedAt: '2026-09-29T14:00:00Z',
    status: 'OPEN',
    serviceChargeRateBp: null,
    currency: 'UZS',
    settledTotalMinor: null,
    closedAt: null,
    closeReasonCode: null,
    version: 1,
    tables: [{ tableId: 'tb-7', code: 'T7', displayName: 'Table 7' }],
    origin: 'STAFF',
    claimExpiresAt: null,
    confirmedAt: null,
    ...overrides,
  };
}

function table(overrides: Partial<TableAvailability> = {}): TableAvailability {
  return {
    tableId: 'tb-1',
    code: 'T1',
    seats: 4,
    sectionId: 's1',
    booked: false,
    occupied: false,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('DineInTablePicker', () => {
  let fixture: ComponentFixture<DineInTablePicker>;
  let sessionsApi: { live: ReturnType<typeof vi.fn>; open: ReturnType<typeof vi.fn> };
  let reservationsApi: { availability: ReturnType<typeof vi.fn> };
  let picks: (TablePick | null)[];

  async function render(
    live: readonly SessionView[] = [party()],
    availability: readonly TableAvailability[] = [table()],
  ): Promise<HTMLElement> {
    sessionsApi = { live: vi.fn().mockReturnValue(of(live)), open: vi.fn() };
    reservationsApi = { availability: vi.fn().mockResolvedValue(availability) };
    await TestBed.configureTestingModule({
      imports: [DineInTablePicker],
      providers: [
        { provide: TableSessionsApi, useValue: sessionsApi },
        { provide: ReservationsApi, useValue: reservationsApi },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DineInTablePicker);
    fixture.componentRef.setInput('scope', SCOPE);
    picks = [];
    fixture.componentInstance.picked.subscribe((pick) => picks.push(pick));
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function rows(host: HTMLElement): string[] {
    return Array.from(host.querySelectorAll('[data-testid="new-order-table-session"]')).map((row) =>
      (row.textContent ?? '').trim(),
    );
  }

  it('lists each seated party with its tables and headcount, in the order the room reads them', async () => {
    const host = await render([
      party(),
      party({
        sessionId: 'ses-2',
        partySize: 5,
        tables: [
          { tableId: 'tb-2', code: 'T2', displayName: 'Table 2' },
          { tableId: 'tb-10', code: 'T10', displayName: 'Table 10' },
        ],
      }),
      party({
        sessionId: 'ses-3',
        partySize: null,
        tables: [{ tableId: 'tb-9', code: 'T9', displayName: 'Table 9' }],
      }),
    ]);

    expect(rows(host)).toEqual(['T7 · 3 guests', 'T2 + T10 · 5 guests', 'T9']);
    expect(sessionsApi.live).toHaveBeenCalledWith(SCOPE);
  });

  it('choosing a party reports its session and how staff say its tables', async () => {
    const host = await render([
      party({
        sessionId: 'ses-2',
        tables: [
          { tableId: 'tb-2', code: 'T2', displayName: 'Table 2' },
          { tableId: 'tb-10', code: 'T10', displayName: 'Table 10' },
        ],
      }),
    ]);

    host.querySelector<HTMLButtonElement>('[data-testid="new-order-table-session"]')!.click();
    fixture.detectChanges();

    expect(picks).toEqual([{ sessionId: 'ses-2', tables: 'T2 + T10' }]);
    expect(
      host
        .querySelector('[data-testid="new-order-table-session"]')!
        .classList.contains('picker__option--selected'),
    ).toBe(true);
  });

  it('says so, plainly, when nobody is seated yet', async () => {
    const host = await render([]);

    expect(host.querySelector('[data-testid="new-order-table-no-sessions"]')).not.toBeNull();
    expect(picks).toEqual([]);
  });

  it('offers only free tables for seating, and marks one a booking holds soon', async () => {
    const host = await render(
      [],
      [
        table({ tableId: 'tb-1', code: 'T1' }),
        table({ tableId: 'tb-2', code: 'T2', occupied: true }),
        table({ tableId: 'tb-3', code: 'T3', booked: true, seats: 2 }),
      ],
    );

    const options = Array.from(
      host.querySelectorAll<HTMLOptionElement>(
        '[data-testid="new-order-table-free-select"] option',
      ),
    )
      .map((option) => (option.textContent ?? '').trim())
      .filter((text) => text !== '—');

    expect(options).toEqual(['T1 · seats 4', 'T3 · seats 2 · booking soon']);
    const [from, to] = reservationsApi.availability.mock.calls[0].slice(1);
    expect(new Date(to).getTime() - new Date(from).getTime()).toBe(90 * 60_000);
  });

  it('seats a party from here with no booking, then selects it', async () => {
    const opened = party({
      sessionId: 'ses-new',
      partySize: 4,
      tables: [{ tableId: 'tb-1', code: 'T1', displayName: 'Table 1' }],
    });
    const host = await render([], [table({ tableId: 'tb-1', code: 'T1' })]);
    sessionsApi.open.mockReturnValue(of(opened));

    const select = host.querySelector<HTMLSelectElement>(
      '[data-testid="new-order-table-free-select"]',
    )!;
    select.value = 'tb-1';
    select.dispatchEvent(new Event('change'));
    const size = host.querySelector<HTMLInputElement>(
      '[data-testid="new-order-table-party-size"]',
    )!;
    size.value = '4';
    size.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    host.querySelector<HTMLButtonElement>('[data-testid="new-order-table-seat"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(sessionsApi.open).toHaveBeenCalledTimes(1);
    const [scope, body] = sessionsApi.open.mock.calls[0];
    expect(scope).toEqual(SCOPE);
    expect(body).toEqual({
      tableIds: ['tb-1'],
      partySize: 4,
      currency: 'UZS',
      reason: 'Walk-in seated from the New order screen',
    });
    expect(body).not.toHaveProperty('reservationId');
    expect(picks).toEqual([{ sessionId: 'ses-new', tables: 'T1' }]);
    expect(rows(host)).toEqual(['T1 · 4 guests']);
    expect(host.querySelector('[data-testid="new-order-table-no-free"]')).not.toBeNull();
  });

  it('will not seat until a table is chosen', async () => {
    const host = await render([], [table()]);

    expect(
      host.querySelector<HTMLButtonElement>('[data-testid="new-order-table-seat"]')!.disabled,
    ).toBe(true);
    expect(sessionsApi.open).not.toHaveBeenCalled();
  });

  it('a table taken a moment ago is a plain sentence, and the room is read again', async () => {
    const host = await render([], [table({ tableId: 'tb-1', code: 'T1' })]);
    sessionsApi.open.mockReturnValue(
      throwError(
        () =>
          new ApiError(
            ApiErrorCode.RESOURCE_CONFLICT,
            409,
            { status: 409, conflict: 'TABLE_OCCUPIED' },
            null,
          ),
      ),
    );
    sessionsApi.live.mockReturnValue(
      of([party({ tables: [{ tableId: 'tb-1', code: 'T1', displayName: 'Table 1' }] })]),
    );
    reservationsApi.availability.mockResolvedValue([
      table({ tableId: 'tb-1', code: 'T1', occupied: true }),
    ]);

    const select = host.querySelector<HTMLSelectElement>(
      '[data-testid="new-order-table-free-select"]',
    )!;
    select.value = 'tb-1';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    host.querySelector<HTMLButtonElement>('[data-testid="new-order-table-seat"]')!.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="new-order-table-seat-error"]')?.textContent).toContain(
      'a moment ago',
    );
    expect(sessionsApi.live).toHaveBeenCalledTimes(2);
    expect(rows(host)).toEqual(['T1 · 3 guests']);
  });

  it('a room the operator has no access to is a wall, not an empty room', async () => {
    sessionsApi = {
      live: vi
        .fn()
        .mockReturnValue(
          throwError(() => new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
        ),
      open: vi.fn(),
    };
    reservationsApi = { availability: vi.fn().mockResolvedValue([]) };
    await TestBed.configureTestingModule({
      imports: [DineInTablePicker],
      providers: [
        { provide: TableSessionsApi, useValue: sessionsApi },
        { provide: ReservationsApi, useValue: reservationsApi },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(DineInTablePicker);
    fixture.componentRef.setInput('scope', SCOPE);
    picks = [];
    fixture.componentInstance.picked.subscribe((pick) => picks.push(pick));
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="new-order-table-denied"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="new-order-table-no-sessions"]')).toBeNull();
    expect(host.querySelector('[data-testid="new-order-table-seat"]')).toBeNull();
    expect(picks).toEqual([null]);
  });

  it('drops a chosen party that has closed since the room was last read', async () => {
    const host = await render([party()]);
    host.querySelector<HTMLButtonElement>('[data-testid="new-order-table-session"]')!.click();
    fixture.detectChanges();
    expect(picks).toEqual([{ sessionId: 'ses-1', tables: 'T7' }]);

    sessionsApi.live.mockReturnValue(of([]));
    fixture.componentRef.setInput('scope', { ...SCOPE });
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(picks[picks.length - 1]).toBeNull();
    expect(rows(host)).toEqual([]);
  });
});
