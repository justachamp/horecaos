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
import { CourierProfileStore } from '../../core/courier/courier-profile.store';
import { NOTICE_VERSION } from '../../core/notice/notice.store';
import { NoticePage } from './notice';

describe('NoticePage', () => {
  let work: WorkHarness;
  const PROFILE = courierProfile();

  async function render(locale: 'en' | 'ru' | 'uz-Latn' = 'en', { read = false } = {}) {
    work = configureWork(PROFILE);
    if (read) {
      noticeAlreadyRead(PROFILE.courierId);
    }
    await TestBed.inject(CourierProfileStore).load();
    return renderPage(NoticePage, ['notice'], locale);
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('says what is collected, when, who sees it and how long it is kept', async () => {
    const page = await render();

    for (const heading of ['What is collected', 'When', 'Who can see it', 'How long it is kept']) {
      expect(textOf(page)).toContain(heading);
    }
    expect(textOf(page)).toContain('Route history is kept for 30 days');
    expect(textOf(page)).toContain('Your pay is not calculated from this track');
    expect(textOf(page)).toContain('only read your position while it is open and the screen is on');
  });

  it('is marked as a draft, with its version, until the tenant’s wording replaces it', async () => {
    const page = await render();

    expect(page.nativeElement.querySelector('.notice.warning')?.textContent).toContain('DRAFT');
    expect(textOf(page)).toContain(`Version ${NOTICE_VERSION}`);
  });

  it('records the acknowledgement on the platform and then says the courier can go on duty', async () => {
    const page = await render();

    buttonLabelled(page.nativeElement, 'I have read this')!.click();

    await vi.waitFor(() => {
      page.detectChanges();
      expect(textOf(page)).toContain('Recorded. You can go on duty.');
    });
    expect(work.api.acknowledgeNotice).toHaveBeenCalledWith(NOTICE_VERSION, expect.any(String));
    expect(page.nativeElement.querySelector('a[href="/shift"]')?.textContent).toContain(
      'Back to the shift',
    );
    expect(buttonLabelled(page.nativeElement, 'I have read this')).toBeNull();
  });

  it('does not say it was recorded when the platform could not be reached', async () => {
    const page = await render();
    work.api.acknowledgeNotice.mockRejectedValue(
      new ApiError({ status: 0, code: 'NETWORK', detail: 'offline' }),
    );

    buttonLabelled(page.nativeElement, 'I have read this')!.click();

    await vi.waitFor(() => {
      page.detectChanges();
      expect(page.nativeElement.querySelector('[role="alert"]')?.textContent).toContain(
        'Cannot reach the server',
      );
    });
    expect(textOf(page)).not.toContain('Recorded.');
    expect(buttonLabelled(page.nativeElement, 'I have read this')).not.toBeNull();
  });

  it('shows a notice already read as read, without asking again', async () => {
    const page = await render('en', { read: true });

    expect(textOf(page)).toContain('Recorded. You can go on duty.');
    expect(buttonLabelled(page.nativeElement, 'I have read this')).toBeNull();
    expect(work.api.acknowledgeNotice).not.toHaveBeenCalled();
  });

  it('is written in all three of the courier’s languages', async () => {
    expect(textOf(await render('ru'))).toContain('Уведомление о геолокации');
    TestBed.resetTestingModule();
    expect(textOf(await render('uz-Latn'))).toContain('Joylashuv xabarnomasi');
  });
});
