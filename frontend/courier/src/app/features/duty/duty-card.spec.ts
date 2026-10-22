import {
  buttonLabelled,
  configureWork,
  noticeAlreadyRead,
  renderPage,
  textOf,
  type WorkHarness,
} from '../../../testing/work-harness';
import { fix } from '../../../testing/fake-device';
import { courierProfile } from '../../../testing/fixtures';
import { ApiError } from '../../core/api/problem-details';
import type { CourierProfile } from '../../core/courier/courier-profile';
import type { DutySession } from '../../core/courier/courier-duty';
import { CourierProfileStore } from '../../core/courier/courier-profile.store';
import { DutyStore } from '../../core/telemetry/duty.store';
import { TestBed } from '@angular/core/testing';
import { DutyCard } from './duty-card';

const OPEN = courierProfile();

function session(): DutySession {
  return {
    sessionId: 'session-1',
    shiftId: OPEN.openShift!.shiftId,
    locationId: OPEN.openShift!.locationId,
    status: 'OPEN',
    collectionGate: 'ON_DUTY',
    startedAt: '2026-10-14T05:00:00Z',
    registrationValidUntil: '2027-01-31',
    batchCadenceSeconds: 10,
    maximumBatchSize: 60,
  };
}

describe('DutyCard', () => {
  let work: WorkHarness;

  async function render(record: CourierProfile = OPEN, { read = true } = {}) {
    work = configureWork(record);
    work.api.openDuty.mockResolvedValue(session());
    if (read) {
      noticeAlreadyRead(record.courierId);
    }
    await TestBed.inject(CourierProfileStore).load();
    return renderPage(DutyCard, ['shift', 'notice']);
  }

  async function tap(fixture: Awaited<ReturnType<typeof render>>, label: string) {
    const button = buttonLabelled(fixture.nativeElement, label);
    expect(button, `a button labelled "${label}"`).not.toBeNull();
    button!.click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
  afterEach(() => {
    TestBed.inject(DutyStore).shiftEnded();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('explains what is shared and says the screen must stay on, before anything is asked', async () => {
    const card = await render();

    expect(textOf(card)).toContain('your dispatcher sees where you are');
    expect(textOf(card)).toContain('Keep the screen on');
    expect(textOf(card)).toContain('your pin on the dispatcher’s map goes stale');
    expect(textOf(card)).toContain('Your pay does not depend on it');
  });

  it('will not go on duty before the notice is read: it says so and links to it', async () => {
    const card = await render(OPEN, { read: false });

    expect(textOf(card)).toContain('read and accept the location notice');
    expect(buttonLabelled(card.nativeElement, 'Go on duty')).toBeNull();
    const link = card.nativeElement.querySelector('a[href="/notice"]');
    expect(link?.textContent).toContain('Read the location notice');
    expect(work.api.openDuty).not.toHaveBeenCalled();
  });

  it('goes on duty once the notice is read, holds the screen and shows it is sharing', async () => {
    const card = await render();

    await tap(card, 'Go on duty');

    await vi.waitFor(() => {
      card.detectChanges();
      expect(textOf(card)).toContain('You are on duty.');
    });
    expect(work.api.openDuty).toHaveBeenCalledOnce();
    expect(textOf(card)).toContain('The screen is kept awake.');
    expect(buttonLabelled(card.nativeElement, 'Stop sharing location')).not.toBeNull();
    expect(work.geo.watching).toBe(1);
  });

  it('stops sharing when asked, and lets the screen sleep', async () => {
    const card = await render();
    await tap(card, 'Go on duty');
    await vi.waitFor(() => expect(textOf(card)).toContain('You are on duty.'));

    await tap(card, 'Stop sharing location');

    await vi.waitFor(() => {
      card.detectChanges();
      expect(textOf(card)).toContain('You are not on duty.');
    });
    expect(work.api.closeDuty).toHaveBeenCalledOnce();
    expect(work.geo.watching).toBe(0);
    expect(work.lock.held).toBe(0);
  });

  it('says the browser would not keep the screen awake, and that sharing then stops when it locks', async () => {
    const card = await render();
    work.lock.granted = false;

    await tap(card, 'Go on duty');

    await vi.waitFor(() => {
      card.detectChanges();
      expect(textOf(card)).toContain('would not keep the screen awake');
    });
  });

  it('says when the handset blocks location, and where to change it', async () => {
    const card = await render();
    await tap(card, 'Go on duty');
    await vi.waitFor(() => expect(textOf(card)).toContain('You are on duty.'));

    work.geo.fail('denied');
    card.detectChanges();

    expect(textOf(card)).toContain('Location is blocked for this app');
  });

  it('says why the platform would not open a session', async () => {
    const card = await render();
    work.api.openDuty.mockRejectedValue(
      new ApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        reason: 'REGISTRATION_LAPSED',
        detail: 'x',
      }),
    );

    await tap(card, 'Go on duty');

    await vi.waitFor(() => {
      card.detectChanges();
      expect(card.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(
        'registration has lapsed',
      );
    });
  });

  it('asks for the notice on this phone when a session is open from somewhere else', async () => {
    work = configureWork(OPEN);
    work.api.currentDuty.mockResolvedValue({ session: session() });
    await TestBed.inject(CourierProfileStore).load();
    await TestBed.inject(DutyStore).restore();
    const card = await renderPage(DutyCard, ['shift', 'notice']);

    expect(textOf(card)).toContain('Read the notice on this phone to continue');
    expect(buttonLabelled(card.nativeElement, 'Go on duty')).toBeNull();
  });

  it('says so when the platform ended the session under the courier', async () => {
    const card = await render();
    work.api.postObservations.mockRejectedValue(
      new ApiError({
        status: 422,
        code: 'UNPROCESSABLE_STATE',
        reason: 'NO_OPEN_DUTY_SESSION',
        detail: 'x',
      }),
    );
    vi.useFakeTimers();
    try {
      await TestBed.inject(DutyStore).goOnDuty();
      work.geo.emit(fix());
      await vi.advanceTimersByTimeAsync(10_000);
    } finally {
      vi.useRealTimers();
    }

    card.detectChanges();
    expect(textOf(card)).toContain('Your duty session was ended by the platform');
  });
});
