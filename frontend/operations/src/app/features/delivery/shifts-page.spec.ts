import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { CurrentLocation } from '../../core/auth/current-location';
import {
  CouriersApi,
  PlannedShiftView,
  RosterEntryResponse,
  ShiftView,
} from '../couriers/couriers-api';
import { I18n } from '../../core/i18n/i18n';
import { ShiftsPage } from './shifts-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function shift(overrides: Partial<ShiftView>): ShiftView {
  return {
    shiftId: 'shift-1',
    courierId: 'courier-1',
    courierDisplayReference: 'K-014',
    status: 'OPEN',
    dutyState: 'AVAILABLE',
    openedAt: new Date().toISOString(),
    closedAt: null,
    paidSeconds: null,
    breakSeconds: 0,
    approvalRequestId: null,
    ...overrides,
  };
}

function courier(overrides: Partial<RosterEntryResponse> = {}): RosterEntryResponse {
  return {
    courierId: 'courier-1',
    displayReference: 'K-014',
    status: 'ACTIVE',
    courierTypeId: 'type-1',
    courierTypeName: 'Scooter',
    vehicleClass: 'SCOOTER',
    activeAssignments: 0,
    concurrencyCeiling: 2,
    engagementId: 'engagement-1',
    engagementStatus: 'ACTIVE',
    warningState: 'VALID',
    reverificationDueOn: null,
    ...overrides,
  };
}

function plannedEntry(overrides: Partial<PlannedShiftView> = {}): PlannedShiftView {
  return {
    entryId: 'entry-1',
    courierId: 'courier-1',
    courierDisplayReference: 'K-014',
    status: 'PUBLISHED',
    plannedStart: new Date().toISOString(),
    plannedEnd: new Date(Date.now() + 8 * 3_600_000).toISOString(),
    publishedAt: new Date().toISOString(),
    respondedAt: null,
    comparison: { coverage: 'PENDING', matchedShiftId: null, matchedDutyState: null },
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

/** Every ShiftsPage render now loads the roster and the courier list beside the shifts. */
function apiDefaults(overrides: Partial<CouriersApi> = {}): Partial<CouriersApi> {
  return {
    shifts: () => Promise.resolve([]),
    roster: () => Promise.resolve([]),
    rosterComparison: () => Promise.resolve([]),
    ...overrides,
  };
}

describe('ShiftsPage', () => {
  let fixture: ComponentFixture<ShiftsPage>;

  async function render(api: Partial<CouriersApi>): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [ShiftsPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CouriersApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ShiftsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists the branch’s shifts with a close action on an open one', async () => {
    await render(apiDefaults({ shifts: () => Promise.resolve([shift({})]) }));

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('[data-testid="shift-row"]')).toHaveLength(1);
    expect(host.querySelector('[data-testid="shift-close"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="shift-approve"]')).toBeNull();
  });

  it('offers approve on a shift awaiting approval, and calls the API on click', async () => {
    const approveShift = vi.fn().mockResolvedValue(undefined);
    await render(
      apiDefaults({
        shifts: () => Promise.resolve([shift({ status: 'AWAITING_APPROVAL' })]),
        approveShift,
      }),
    );

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="shift-approve"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(approveShift).toHaveBeenCalledWith('t1', 'shift-1', expect.any(String));
  });

  it('shows the denied state when the location grant is missing', async () => {
    await TestBed.configureTestingModule({
      imports: [ShiftsPage],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(null),
            denied: signal(true),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: CouriersApi, useValue: { shifts: vi.fn() } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(ShiftsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="shifts-denied"]'),
    ).not.toBeNull();
  });

  // ---------------------------------------------------------- what the template used to drop

  it('renders a courier’s display reference in place of the raw courier id', async () => {
    await render(apiDefaults({ shifts: () => Promise.resolve([shift({})]) }));

    const cell = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="shift-courier"]',
    );
    expect(cell?.textContent).toContain('K-014');
    expect(cell?.textContent).not.toContain('courier-1');
  });

  it('falls back to the raw courier id only when no display reference is on the wire', async () => {
    await render(
      apiDefaults({
        shifts: () => Promise.resolve([shift({ courierDisplayReference: null })]),
      }),
    );

    const cell = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="shift-courier"]',
    );
    expect(cell?.textContent).toContain('courier-1');
  });

  it('renders dutyState — the template used to carry it on the wire and never show it', async () => {
    await render(
      apiDefaults({ shifts: () => Promise.resolve([shift({ dutyState: 'ON_BREAK' })]) }),
    );

    const cell = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="shift-duty-state"]',
    );
    expect(cell?.textContent).toContain('On break');
  });

  it('renders breakSeconds as hours:minutes — time on break is the central attendance figure', async () => {
    await render(apiDefaults({ shifts: () => Promise.resolve([shift({ breakSeconds: 5_400 })]) }));

    const cell = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="shift-break"]',
    );
    expect(cell?.textContent?.trim()).toBe('1:30');
  });

  // --------------------------------------------------------------------- period filter

  it('sends the chosen from/to window to the shifts read', async () => {
    const shiftsFn = vi.fn().mockResolvedValue([]);
    await render(apiDefaults({ shifts: shiftsFn }));

    const host = fixture.nativeElement as HTMLElement;
    const from = host.querySelector('[data-testid="shifts-from"]') as HTMLInputElement;
    const to = host.querySelector('[data-testid="shifts-to"]') as HTMLInputElement;
    from.value = '2026-01-01';
    from.dispatchEvent(new Event('change'));
    to.value = '2026-01-07';
    to.dispatchEvent(new Event('change'));
    (host.querySelector('[data-testid="shifts-apply-period"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    const lastCall = shiftsFn.mock.calls.at(-1);
    expect(lastCall?.[4]).toContain('2026-01-01');
    expect(lastCall?.[5]).toContain('2026-01-07');
  });

  // ------------------------------------------------------------------------- roster

  it('renders the roster grid with each entry’s coverage against what a courier actually opened', async () => {
    await render(
      apiDefaults({
        rosterComparison: () =>
          Promise.resolve([
            plannedEntry({
              comparison: { coverage: 'UNCOVERED', matchedShiftId: null, matchedDutyState: null },
            }),
          ]),
      }),
    );

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelectorAll('[data-testid="roster-row"]')).toHaveLength(1);
    expect(host.querySelector('[data-testid="roster-coverage"]')?.textContent).toContain(
      'Uncovered',
    );
    expect(host.querySelector('[data-testid="roster-courier"]')?.textContent).toContain('K-014');
  });

  it('offers publish on a DRAFT entry and cancel on a DRAFT or PUBLISHED one, and calls the API', async () => {
    const publishRosterEntry = vi.fn().mockResolvedValue(undefined);
    await render(
      apiDefaults({
        rosterComparison: () => Promise.resolve([plannedEntry({ status: 'DRAFT' })]),
        publishRosterEntry,
      }),
    );

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="roster-publish"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="roster-cancel"]')).not.toBeNull();

    (host.querySelector('[data-testid="roster-publish"]') as HTMLButtonElement).click();
    await flushMicrotasks();

    expect(publishRosterEntry).toHaveBeenCalledWith('t1', 'b1', 'l1', 'entry-1', expect.any(String));
  });

  it('offers no publish action on an already-published entry', async () => {
    await render(
      apiDefaults({
        rosterComparison: () => Promise.resolve([plannedEntry({ status: 'PUBLISHED' })]),
      }),
    );

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="roster-publish"]'),
    ).toBeNull();
  });

  it('opens the plan-shift dialog and drafts a roster entry with the chosen courier', async () => {
    const draftRosterEntry = vi.fn().mockResolvedValue(plannedEntry());
    await render(
      apiDefaults({
        roster: () => Promise.resolve([courier()]),
        draftRosterEntry,
      }),
    );

    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="roster-plan"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const dialog = host.querySelector('[data-testid="plan-shift-dialog"]');
    expect(dialog).not.toBeNull();

    const courierSelect = dialog!.querySelector(
      '[data-testid="plan-shift-dialog-courier"]',
    ) as HTMLSelectElement;
    courierSelect.value = 'courier-1';
    courierSelect.dispatchEvent(new Event('change'));
    const start = dialog!.querySelector(
      '[data-testid="plan-shift-dialog-start"]',
    ) as HTMLInputElement;
    start.value = '2026-01-01T09:00';
    start.dispatchEvent(new Event('input'));
    const end = dialog!.querySelector('[data-testid="plan-shift-dialog-end"]') as HTMLInputElement;
    end.value = '2026-01-01T17:00';
    end.dispatchEvent(new Event('input'));
    const reason = dialog!.querySelector(
      '[data-testid="plan-shift-dialog-reason"]',
    ) as HTMLInputElement;
    reason.value = 'Covering the lunch rush';
    reason.dispatchEvent(new Event('input'));

    (
      dialog!.querySelector('[data-testid="plan-shift-dialog-confirm"]') as HTMLButtonElement
    ).click();
    await flushMicrotasks();

    expect(draftRosterEntry).toHaveBeenCalledWith(
      't1',
      'b1',
      'l1',
      expect.objectContaining({ courierId: 'courier-1' }),
    );
  });
});
