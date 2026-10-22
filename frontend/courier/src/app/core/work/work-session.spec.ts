import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG, courierProfile } from '../../../testing/fixtures';
import { APP_CONFIG } from '../config/app-config';
import { CourierApi } from '../courier/courier-api';
import { DutyStore } from '../telemetry/duty.store';
import { WorkSession } from './work-session';

describe('WorkSession', () => {
  let me: ReturnType<typeof vi.fn>;
  let restore: ReturnType<typeof vi.fn>;
  let shiftEnded: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    me = vi.fn().mockResolvedValue(courierProfile());
    restore = vi.fn().mockResolvedValue(undefined);
    shiftEnded = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: CourierApi, useValue: { me } },
        { provide: DutyStore, useValue: { restore, shiftEnded } },
      ],
    });
  });

  it('reads the record once and picks up a duty session the platform still has open', async () => {
    const work = TestBed.inject(WorkSession);

    await work.begin();
    await work.begin();

    expect(me).toHaveBeenCalledOnce();
    expect(restore).toHaveBeenCalledOnce();
    expect(shiftEnded).not.toHaveBeenCalled();
  });

  it('lets duty go when the record says no shift is open', async () => {
    me.mockResolvedValue(courierProfile({ openShift: null }));

    await TestBed.inject(WorkSession).begin();

    expect(shiftEnded).toHaveBeenCalledOnce();
    expect(restore).not.toHaveBeenCalled();
  });

  it('re-reads the record before it reconciles, so a shift closed elsewhere is noticed', async () => {
    const work = TestBed.inject(WorkSession);
    await work.begin();
    me.mockResolvedValue(courierProfile({ openShift: null }));

    await work.refresh();

    expect(me).toHaveBeenCalledTimes(2);
    expect(shiftEnded).toHaveBeenCalledOnce();
  });

  it('stops everything at sign-out and starts again at the next sign-in', async () => {
    const work = TestBed.inject(WorkSession);
    await work.begin();

    work.end();
    expect(shiftEnded).toHaveBeenCalledOnce();

    await work.begin();
    expect(me).toHaveBeenCalledTimes(2);
  });
});
