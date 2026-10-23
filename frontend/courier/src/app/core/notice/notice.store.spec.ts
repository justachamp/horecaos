import { TestBed } from '@angular/core/testing';

import { TEST_CONFIG, courierProfile } from '../../../testing/fixtures';
import { ApiError } from '../api/problem-details';
import { APP_CONFIG } from '../config/app-config';
import { CourierApi } from '../courier/courier-api';
import { CourierProfileStore } from '../courier/courier-profile.store';
import { NOTICE_VERSION, NoticeStore } from './notice.store';

describe('NoticeStore', () => {
  let acknowledgeNotice: ReturnType<typeof vi.fn>;
  let me: ReturnType<typeof vi.fn>;

  async function create(profile = courierProfile()) {
    me = vi.fn().mockResolvedValue(profile);
    acknowledgeNotice = vi.fn().mockResolvedValue({
      version: NOTICE_VERSION,
      acknowledgedAt: '2026-10-14T05:00:00Z',
    });
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: CourierApi, useValue: { me, acknowledgeNotice } },
      ],
    });
    await TestBed.inject(CourierProfileStore).load();
    return TestBed.inject(NoticeStore);
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('is not acknowledged until the courier has read it', async () => {
    const notice = await create();

    expect(notice.acknowledged()).toBe(false);
  });

  it('is a draft, and says its version so the platform’s record names exactly what was shown', async () => {
    const notice = await create();

    expect(notice.version).toBe(NOTICE_VERSION);
    expect(notice.isDraft).toBe(true);
  });

  it('records the acknowledgement on the platform first, then remembers it on the handset', async () => {
    const notice = await create();

    const done = await notice.acknowledge();

    expect(done).toBe(true);
    expect(acknowledgeNotice).toHaveBeenCalledWith(NOTICE_VERSION, expect.any(String));
    expect(notice.acknowledged()).toBe(true);
    expect(localStorage.getItem('courier.notice')).toContain(NOTICE_VERSION);
  });

  it('keeps no personal data in what it remembers: a courier id, a version and a time', async () => {
    const notice = await create();

    await notice.acknowledge();

    const kept = JSON.parse(localStorage.getItem('courier.notice')!) as Record<string, unknown>;
    expect(Object.keys(kept).sort()).toEqual(['acknowledgedAt', 'courierId', 'version']);
  });

  it('does not count a failed acknowledgement, and says why', async () => {
    const notice = await create();
    acknowledgeNotice.mockRejectedValue(
      new ApiError({ status: 0, code: 'NETWORK', detail: 'offline' }),
    );

    const done = await notice.acknowledge();

    expect(done).toBe(false);
    expect(notice.acknowledged()).toBe(false);
    expect(notice.error()?.isNetwork).toBe(true);
    expect(localStorage.getItem('courier.notice')).toBeNull();
  });

  it('replays the same key when the courier taps again after a dropped connection', async () => {
    const notice = await create();
    acknowledgeNotice
      .mockRejectedValueOnce(new ApiError({ status: 0, code: 'NETWORK', detail: 'offline' }))
      .mockResolvedValueOnce({ version: NOTICE_VERSION, acknowledgedAt: '2026-10-14T05:00:00Z' });

    await notice.acknowledge();
    await notice.acknowledge();

    expect(acknowledgeNotice.mock.calls[1][1]).toBe(acknowledgeNotice.mock.calls[0][1]);
  });

  it('remembers across a restart for the same courier and the same version', async () => {
    await (await create()).acknowledge();
    TestBed.resetTestingModule();

    const restarted = await create();

    expect(restarted.acknowledged()).toBe(true);
  });

  it('asks again when the version changes, or when another courier uses the phone', async () => {
    localStorage.setItem(
      'courier.notice',
      JSON.stringify({
        courierId: courierProfile().courierId,
        version: 'an-older-version',
        acknowledgedAt: '2026-09-01T00:00:00Z',
      }),
    );
    expect((await create()).acknowledged()).toBe(false);
    TestBed.resetTestingModule();

    localStorage.setItem(
      'courier.notice',
      JSON.stringify({
        courierId: 'someone-else',
        version: NOTICE_VERSION,
        acknowledgedAt: '2026-09-01T00:00:00Z',
      }),
    );
    expect((await create()).acknowledged()).toBe(false);
  });

  it('survives a corrupt record and a browser that keeps no storage', async () => {
    localStorage.setItem('courier.notice', '{not json');
    const notice = await create();
    expect(notice.acknowledged()).toBe(false);

    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage refused');
    });
    await notice.acknowledge();

    // Held for this page's life: the platform has the record, the handset just forgets it.
    expect(notice.acknowledged()).toBe(true);
  });

  it('is never acknowledged for a courier whose record has not loaded', async () => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: CourierApi, useValue: { me: vi.fn(), acknowledgeNotice: vi.fn() } },
      ],
    });
    localStorage.setItem(
      'courier.notice',
      JSON.stringify({
        courierId: courierProfile().courierId,
        version: NOTICE_VERSION,
        acknowledgedAt: '2026-10-01T00:00:00Z',
      }),
    );

    expect(TestBed.inject(NoticeStore).acknowledged()).toBe(false);
  });
});
