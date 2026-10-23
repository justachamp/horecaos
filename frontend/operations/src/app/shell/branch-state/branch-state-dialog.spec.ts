import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import {
  LocationsApi,
  ModeBindingView,
  ServiceSummaryResponse,
} from '../../features/settings/locations/locations-api';
import { ShortcutRegistry } from '../../shared/keyboard/shortcut-registry';
import { BranchStateDialog } from './branch-state-dialog';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };
// A Monday, 17:00 in Tashkent.
const NOW = new Date('2026-10-12T12:00:00Z');

const MONDAY: ModeBindingView = {
  fulfillmentMode: 'DELIVERY',
  scheduleId: 's1',
  scheduleName: 'Main',
  acceptsScheduledOrders: false,
  sharedWithLocationCount: 1,
  rules: [{ dayOfWeek: 1, opensAt: '10:00:00', closesAt: '23:00:00' }],
  exceptions: [],
  scheduleVersion: 1,
};

function summary(overrides: Partial<ServiceSummaryResponse> = {}): ServiceSummaryResponse {
  return {
    mode: 'FOLLOW_SCHEDULE',
    effectiveMode: 'FOLLOW_SCHEDULE',
    reasonCode: null,
    effectiveUntil: null,
    maxConcurrentOrders: null,
    openOrderCount: 0,
    bindings: [MONDAY],
    preparationBands: [],
    ...overrides,
  };
}

describe('BranchStateDialog', () => {
  let api: {
    serviceSummary: ReturnType<typeof vi.fn>;
    profile: ReturnType<typeof vi.fn>;
    serviceStates: ReturnType<typeof vi.fn>;
    changeServiceState: ReturnType<typeof vi.fn>;
  };
  let fixture: ComponentFixture<BranchStateDialog>;
  let closedEvents: number;

  async function render(
    state: ServiceSummaryResponse = summary(),
    locationId = 'l1',
    pickerOptions: readonly { id: string; displayName: string; status: string }[] = [
      { id: 'l1', displayName: 'Chilonzor', status: 'ACTIVE' },
    ],
  ): Promise<void> {
    api = {
      serviceSummary: vi.fn().mockResolvedValue(state),
      profile: vi
        .fn()
        .mockResolvedValue({ id: 'l1', displayName: 'Chilonzor', timezone: 'Asia/Tashkent' }),
      serviceStates: vi.fn().mockResolvedValue([]),
      changeServiceState: vi.fn().mockResolvedValue(undefined),
    };
    await TestBed.configureTestingModule({
      imports: [BranchStateDialog],
      providers: [
        { provide: LocationsApi, useValue: api },
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            options: signal(pickerOptions),
            ensureLoaded: () => Promise.resolve(),
          },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(BranchStateDialog);
    fixture.componentRef.setInput('locationId', locationId);
    closedEvents = 0;
    fixture.componentInstance.closed.subscribe(() => (closedEvents += 1));
    fixture.detectChanges();
    await settle();
  }

  async function settle(): Promise<void> {
    for (let turn = 0; turn < 4; turn += 1) {
      await Promise.resolve();
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    }
    fixture.detectChanges();
  }

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const byId = <T extends HTMLElement>(id: string): T =>
    host().querySelector(`[data-testid="${id}"]`) as T;
  const submit = (): HTMLButtonElement => byId<HTMLButtonElement>('branch-state-submit');

  async function pick(mode: string, reason?: string): Promise<void> {
    byId<HTMLInputElement>(`branch-state-mode-${mode}`).click();
    fixture.detectChanges();
    if (reason !== undefined) {
      const select = byId<HTMLSelectElement>('branch-state-reason');
      select.value = reason;
      select.dispatchEvent(new Event('change'));
      fixture.detectChanges();
    }
  }

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(NOW);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('names the branch in its title', async () => {
    await render();

    expect(host().textContent).toContain('Branch status — Chilonzor');
  });

  it('names the branch from its own profile when the picker has none: a branch manager’s grant lists no branches', async () => {
    await render(summary(), 'l1', []);

    expect(host().textContent).toContain('Branch status — Chilonzor');
    expect(host().textContent).not.toContain('Branch status — l1');
  });

  it('asks for the branch’s own profile and never for the brand’s list, which a branch manager may not read', async () => {
    await render();

    expect(api.profile).toHaveBeenCalledWith({ ...SCOPE, locationId: 'l1' });
    expect(byId('branch-state-mode-FORCE_CLOSED')).not.toBeNull();
  });

  it('starts on «Close» for a branch that is open, and the button says nothing until a reason is chosen', async () => {
    await render();

    expect(byId<HTMLInputElement>('branch-state-mode-FORCE_CLOSED').checked).toBe(true);
    expect(submit().disabled).toBe(true);
  });

  it('starts on «Follow the schedule» for a branch that is closed already, and says it is closed', async () => {
    await render(
      summary({ mode: 'FORCE_CLOSED', effectiveMode: 'FORCE_CLOSED', reasonCode: 'NO_STAFF' }),
    );

    expect(byId<HTMLInputElement>('branch-state-mode-FOLLOW_SCHEDULE').checked).toBe(true);
    expect(byId('branch-state-closed-now').textContent).toContain('No staff');
    expect(submit().textContent?.trim()).toBe('Open');
    expect(submit().disabled).toBe(false);
  });

  it('starts on «Open anyway» and says so when the schedule has the branch shut right now', async () => {
    vi.setSystemTime(new Date('2026-10-12T02:00:00Z')); // 07:00 in Tashkent, before it opens
    await render();

    expect(byId<HTMLInputElement>('branch-state-mode-FORCE_OPEN').checked).toBe(true);
    expect(byId('branch-state-outside-hours')).not.toBeNull();
  });

  it('offers the closing reasons when closing and the opening ones when opening anyway, and none when following the schedule', async () => {
    await render();
    const options = (): string[] =>
      [...host().querySelectorAll('[data-testid="branch-state-reason"] option')]
        .map((option) => (option as HTMLOptionElement).value)
        .filter((value) => value !== '');

    expect(options()).toEqual([
      'EQUIPMENT_FAILURE',
      'NO_STAFF',
      'POWER_OUTAGE',
      'OVERLOADED',
      'OUT_OF_STOCK',
      'WEATHER',
      'RENOVATION',
      'PRIVATE_EVENT',
      'OTHER',
    ]);

    await pick('FORCE_OPEN');
    expect(options()).toEqual(['EXTENDED_HOURS', 'SPECIAL_EVENT', 'CATCH_UP', 'OTHER']);

    await pick('FOLLOW_SCHEDULE');
    expect(byId('branch-state-reason')).toBeNull();
    expect(byId('branch-state-note')).toBeNull();
  });

  it('closing for an hour sends the reason and the instant an hour from now, and the button says the time', async () => {
    await render();
    await pick('FORCE_CLOSED', 'EQUIPMENT_FAILURE');

    expect(submit().disabled).toBe(false);
    expect(submit().textContent?.trim()).toBe('Close until 18:00'); // 17:00 + 1 h on the branch's clock

    submit().click();
    await settle();

    expect(api.changeServiceState).toHaveBeenCalledWith(SCOPE, {
      mode: 'FORCE_CLOSED',
      reasonCode: 'EQUIPMENT_FAILURE',
      effectiveUntil: '2026-10-12T13:00:00.000Z',
    });
    expect(closedEvents).toBe(1);
  });

  it('the presets are 30 minutes, an hour, two hours, the end of today’s service and until cancelled, last', async () => {
    await render();

    const chips = [...host().querySelectorAll('[data-testid^="branch-state-until-"]')];
    expect(chips.map((chip) => chip.textContent?.trim())).toEqual([
      '30 min',
      '1 hour',
      '2 hours',
      'End of day (23:00)',
      'Until cancelled',
    ]);
    expect(chips[4].classList.contains('branch-state__chip--danger')).toBe(true);
  });

  it('«End of day» sends the instant today’s last window ends, resolved on the branch’s clock', async () => {
    await render();
    await pick('FORCE_CLOSED', 'WEATHER');
    byId('branch-state-until-endOfDay').click();
    fixture.detectChanges();

    expect(submit().textContent?.trim()).toBe('Close until 23:00');
    submit().click();
    await settle();

    expect(api.changeServiceState).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ effectiveUntil: '2026-10-12T18:00:00.000Z' }),
    );
  });

  it('does not offer «End of day» once today’s service is over', async () => {
    vi.setSystemTime(new Date('2026-10-12T18:30:00Z')); // 23:30 in Tashkent
    await render();

    expect(byId('branch-state-until-endOfDay')).toBeNull();
  });

  it('«Until cancelled» sends no expiry, and the button says nobody will reopen it', async () => {
    await render();
    await pick('FORCE_CLOSED', 'NO_STAFF');
    byId('branch-state-until-cancelled').click();
    fixture.detectChanges();

    expect(submit().textContent?.trim()).toBe('Close until cancelled');
    submit().click();
    await settle();

    const request = api.changeServiceState.mock.calls[0][1];
    expect(request).toEqual({ mode: 'FORCE_CLOSED', reasonCode: 'NO_STAFF' });
    expect('effectiveUntil' in request).toBe(false);
  });

  it('«Other» needs a comment before it can be sent, and the comment travels with it', async () => {
    await render();
    await pick('FORCE_CLOSED', 'OTHER');

    expect(submit().disabled).toBe(true);
    expect(byId('branch-state-note-required')).not.toBeNull();

    const note = byId<HTMLTextAreaElement>('branch-state-note');
    note.value = '  Fryer is down  ';
    note.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(submit().disabled).toBe(false);

    submit().click();
    await settle();

    expect(api.changeServiceState).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ reasonCode: 'OTHER', note: 'Fryer is down' }),
    );
  });

  it('going back to the schedule sends the mode alone: no reason and no expiry, which the database would refuse', async () => {
    await render();
    await pick('FORCE_CLOSED', 'EQUIPMENT_FAILURE');
    await pick('FOLLOW_SCHEDULE');

    expect(submit().textContent?.trim()).toBe('Open');
    submit().click();
    await settle();

    expect(api.changeServiceState).toHaveBeenCalledWith(SCOPE, { mode: 'FOLLOW_SCHEDULE' });
  });

  it('opening anyway restates the effect on the button and sends a force-open with its reason', async () => {
    await render();
    await pick('FORCE_OPEN', 'EXTENDED_HOURS');
    byId('branch-state-until-120').click();
    fixture.detectChanges();

    expect(submit().textContent?.trim()).toBe('Open anyway until 19:00');
    submit().click();
    await settle();

    expect(api.changeServiceState).toHaveBeenCalledWith(SCOPE, {
      mode: 'FORCE_OPEN',
      reasonCode: 'EXTENDED_HOURS',
      effectiveUntil: '2026-10-12T14:00:00.000Z',
    });
  });

  it('says how many orders are in progress and that closing does not cancel them', async () => {
    await render(summary({ openOrderCount: 7 }));

    expect(byId('branch-state-in-flight').textContent).toContain('7 in progress');
    expect(byId('branch-state-in-flight').textContent).toContain('does not cancel');
  });

  it('1, 2 and 3 pick one of the top three reasons from anywhere in the dialog', async () => {
    await render();
    const registry = TestBed.inject(ShortcutRegistry);
    const press = (key: string): boolean =>
      registry.dispatch(
        Object.defineProperty(
          new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }),
          'target',
          { value: host() },
        ),
      );

    expect(press('2')).toBe(true);
    fixture.detectChanges();
    expect(byId<HTMLSelectElement>('branch-state-reason').value).toBe('NO_STAFF');
    expect(press('3')).toBe(true);
    fixture.detectChanges();
    expect(byId<HTMLSelectElement>('branch-state-reason').value).toBe('POWER_OUTAGE');
    expect(press('1')).toBe(true);
    fixture.detectChanges();
    expect(byId<HTMLSelectElement>('branch-state-reason').value).toBe('EQUIPMENT_FAILURE');
    expect(press('4'), 'only the top three').toBe(false);
  });

  it('Enter submits from a field that is not a text area, and not before the form is valid', async () => {
    await render();
    const reason = byId<HTMLSelectElement>('branch-state-reason');
    const enter = (target: HTMLElement): void => {
      target.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
      );
    };

    enter(reason);
    await settle();
    expect(api.changeServiceState).not.toHaveBeenCalled();

    await pick('FORCE_CLOSED', 'WEATHER');
    enter(byId('branch-state-note'));
    await settle();
    expect(api.changeServiceState).not.toHaveBeenCalled();

    enter(byId('branch-state-reason'));
    await settle();
    expect(api.changeServiceState).toHaveBeenCalledOnce();
  });

  it('stays open and says why when the server refuses', async () => {
    await render();
    await pick('FORCE_CLOSED', 'WEATHER');
    api.changeServiceState.mockRejectedValue(
      new ApiError('INSUFFICIENT_CAPABILITY', 403, null, 'c-1'),
    );

    submit().click();
    await settle();

    expect(byId('branch-state-error').textContent?.trim()).not.toBe('');
    expect(closedEvents).toBe(0);
    expect(submit().disabled).toBe(false);
  });

  it('cancel closes without writing', async () => {
    await render();

    byId('branch-state-cancel').click();

    expect(closedEvents).toBe(1);
    expect(api.changeServiceState).not.toHaveBeenCalled();
  });

  it('writes to the branch it was opened for, not the one the operator is looking at', async () => {
    await render(summary(), 'l2');
    await pick('FORCE_CLOSED', 'WEATHER');

    submit().click();
    await settle();

    expect(api.changeServiceState).toHaveBeenCalledWith(
      { ...SCOPE, locationId: 'l2' },
      expect.objectContaining({ mode: 'FORCE_CLOSED' }),
    );
  });
});
