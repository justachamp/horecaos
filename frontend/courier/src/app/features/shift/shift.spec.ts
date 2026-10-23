import { TestBed } from '@angular/core/testing';

import { courierProfile } from '../../../testing/fixtures';
import {
  buttonLabelled,
  configureWork,
  noticeAlreadyRead,
  renderPage,
  textOf,
  type WorkHarness,
} from '../../../testing/work-harness';
import { ApiError } from '../../core/api/problem-details';
import type { CourierProfile } from '../../core/courier/courier-profile';
import { ShiftPage } from './shift';

describe('ShiftPage', () => {
  let work: WorkHarness;

  const CLOSED = courierProfile({ openShift: null });
  const OPEN = courierProfile();

  async function render(record: CourierProfile, locale: 'en' | 'ru' = 'en') {
    work = configureWork(record);
    const fixture = await renderPage(ShiftPage, ['shift', 'home'], locale);
    await vi.waitFor(() => expect(work.api.me).toHaveBeenCalled());
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  async function tap(fixture: Awaited<ReturnType<typeof render>>, label: string) {
    const button = buttonLabelled(fixture.nativeElement, label);
    expect(button, `a button labelled "${label}"`).not.toBeNull();
    button!.click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });
  afterEach(() => {
    sessionStorage.clear();
    localStorage.clear();
  });

  describe('with no shift open', () => {
    it('says so and offers to open one at the main branch', async () => {
      const page = await render(CLOSED);

      expect(textOf(page)).toContain('No shift is open');
      const select = page.nativeElement.querySelector('select') as HTMLSelectElement | null;
      expect(select?.value).toBe(CLOSED.branches[0].locationId);
      expect(buttonLabelled(page.nativeElement, 'Open shift')).not.toBeNull();
    });

    it('opens the shift at the chosen branch, then shows it open once the platform says so', async () => {
      const page = await render(CLOSED);
      const select = page.nativeElement.querySelector('select') as HTMLSelectElement;

      select.value = CLOSED.branches[1].locationId;
      select.dispatchEvent(new Event('change'));
      work.api.openShift.mockImplementation(async () => {
        work.record = OPEN;
        return { shiftId: OPEN.openShift!.shiftId, status: 'OPEN' };
      });
      await tap(page, 'Open shift');

      await vi.waitFor(() => expect(work.api.openShift).toHaveBeenCalled());
      expect(work.api.openShift.mock.calls[0][0]).toEqual({
        brandId: CLOSED.branches[1].brandId,
        locationId: CLOSED.branches[1].locationId,
      });
      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('Shift open');
      });
    });

    it('offers no choice of branch to a courier with one, and no shift to one with none', async () => {
      const single = await render(
        courierProfile({ openShift: null, branches: [courierProfile().branches[0]] }),
      );
      expect(single.nativeElement.querySelector('select')).toBeNull();
      expect(buttonLabelled(single.nativeElement, 'Open shift')).not.toBeNull();
      TestBed.resetTestingModule();

      const none = await render(courierProfile({ openShift: null, branches: [] }));
      expect(textOf(none)).toContain('No branch is assigned to you yet');
      expect(buttonLabelled(none.nativeElement, 'Open shift')).toBeNull();
    });

    it('tells the courier why the platform refused, in words, as an alert', async () => {
      const page = await render(CLOSED);
      work.api.openShift.mockRejectedValue(
        new ApiError({ status: 422, code: 'UNPROCESSABLE_STATE', detail: 'developer words' }),
      );

      await tap(page, 'Open shift');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(page.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(
          'did not accept this',
        );
      });
      expect(textOf(page)).not.toContain('developer words');
    });
  });

  describe('with a shift open', () => {
    it('shows the branch and when it opened in the branch’s time zone', async () => {
      const page = await render(OPEN);

      expect(textOf(page)).toContain('Shift open');
      expect(textOf(page)).toContain('Branch: Chilonzor');
      expect(textOf(page)).toContain('Open since 09:30');
    });

    it('starts a break, and offers to end it while one runs', async () => {
      const page = await render(OPEN);
      work.api.startBreak.mockImplementation(async () => {
        work.record = courierProfile({
          openShift: { ...OPEN.openShift!, dutyState: 'ON_BREAK' },
        });
      });

      await tap(page, 'Start a break');

      await vi.waitFor(() => expect(work.api.startBreak).toHaveBeenCalled());
      expect(work.api.startBreak.mock.calls[0][1]).toBe(OPEN.openShift!.shiftId);
      await vi.waitFor(() => {
        page.detectChanges();
        expect(buttonLabelled(page.nativeElement, 'End the break')).not.toBeNull();
      });
      expect(textOf(page)).toContain('On a break you are not offered work');
    });

    it('asks before closing, and closing can be called off', async () => {
      const page = await render(OPEN);

      await tap(page, 'Close shift');
      expect(textOf(page)).toContain('Close this shift?');
      expect(work.api.closeShift).not.toHaveBeenCalled();

      await tap(page, 'Keep working');
      expect(textOf(page)).not.toContain('Close this shift?');
      expect(work.api.closeShift).not.toHaveBeenCalled();
    });

    it('closes after the confirmation and shows what the platform computed', async () => {
      const page = await render(OPEN);
      work.api.closeShift.mockImplementation(async () => {
        work.record = CLOSED;
        return { status: 'CLOSED', paidSeconds: 21_900, breakSeconds: 1_800, cashHandoverId: null };
      });

      await tap(page, 'Close shift');
      await tap(page, 'Yes, close it');

      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('Shift closed');
      });
      expect(textOf(page)).toContain('Paid time (h:min): 6:05');
      expect(textOf(page)).toContain('Break time (h:min): 0:30');
      expect(textOf(page)).not.toContain('Cash to hand over');
    });

    it('asks for the cash declaration when the close opened a handover, and sends minor units', async () => {
      const page = await render(OPEN);
      work.api.closeShift.mockImplementation(async () => {
        work.record = CLOSED;
        return {
          status: 'CLOSED',
          paidSeconds: 100,
          breakSeconds: 0,
          cashHandoverId: 'handover-1',
        };
      });
      await tap(page, 'Close shift');
      await tap(page, 'Yes, close it');
      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('Cash to hand over');
      });

      const input = page.nativeElement.querySelector('input') as HTMLInputElement;
      input.value = 'lots';
      input.dispatchEvent(new Event('input'));
      await tap(page, 'Declare');
      expect(textOf(page)).toContain('Enter an amount');
      expect(work.api.declareCash).not.toHaveBeenCalled();

      input.value = '125 000';
      input.dispatchEvent(new Event('input'));
      await tap(page, 'Declare');

      await vi.waitFor(() => expect(work.api.declareCash).toHaveBeenCalled());
      expect(work.api.declareCash.mock.calls[0].slice(1, 3)).toEqual(['handover-1', 12_500_000]);
      await vi.waitFor(() => {
        page.detectChanges();
        expect(textOf(page)).toContain('Declared. A cashier will confirm');
      });
    });

    it('says in words that the screen has to stay on for the location to be read', async () => {
      noticeAlreadyRead(OPEN.courierId);
      const page = await render(OPEN);

      expect(textOf(page)).toContain('Keep the screen on');
      expect(textOf(page)).toContain(
        'only read your position while it is open and the screen is on',
      );
    });
  });

  it('speaks the courier’s language', async () => {
    const page = await render(CLOSED, 'ru');

    expect(textOf(page)).toContain('Смена не открыта');
    expect(buttonLabelled(page.nativeElement, 'Открыть смену')).not.toBeNull();
  });
});
