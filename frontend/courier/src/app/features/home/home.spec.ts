import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';

import { TEST_CONFIG, courierProfile } from '../../../testing/fixtures';
import { ApiError } from '../../core/api/problem-details';
import { AuthService } from '../../core/auth/auth.service';
import { APP_CONFIG } from '../../core/config/app-config';
import { CourierApi } from '../../core/courier/courier-api';
import type { CourierProfile } from '../../core/courier/courier-profile';
import { I18n } from '../../core/i18n/i18n';
import { HomePage } from './home';

describe('HomePage', () => {
  let me: ReturnType<typeof vi.fn>;

  async function render(
    outcome: CourierProfile | ApiError,
    name: string | null = 'Alisher Karimov',
  ) {
    me = vi.fn();
    if (outcome instanceof ApiError) {
      me.mockRejectedValue(outcome);
    } else {
      me.mockResolvedValue(outcome);
    }
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: TEST_CONFIG },
        { provide: CourierApi, useValue: { me } },
        {
          provide: AuthService,
          useValue: {
            claims: signal(name === null ? null : { subject: 's', name, username: 'a' }),
          },
        },
      ],
    });
    const i18n = TestBed.inject(I18n);
    await i18n.require(['home']);
    await i18n.use('en');
    const fixture = TestBed.createComponent(HomePage);
    fixture.detectChanges();
    await vi.waitFor(() => expect(me).toHaveBeenCalled());
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const text = (root: HTMLElement) => root.textContent!.replace(/\s+/g, ' ').trim();

  it("greets the courier by name and shows the open shift in the branch's time zone", async () => {
    const page = await render(courierProfile());

    expect(text(page)).toContain('Hello, Alisher Karimov');
    expect(text(page)).toContain('Available');
    // 04:30 UTC is 09:30 in Tashkent, whatever zone the phone is set to.
    expect(text(page)).toContain('Open since 09:30');
    expect(text(page)).toContain('Branch: Chilonzor');
  });

  it('lists the branches the courier is bound to and marks the main one', async () => {
    const page = await render(courierProfile());

    const rows = [...page.querySelectorAll('.branches li')].map((row) => ({
      name: row.querySelector('.q-body')?.textContent?.trim(),
      main: row.querySelector('.badge') !== null,
    }));
    expect(rows).toEqual([
      { name: 'Chilonzor', main: true },
      { name: 'Yunusobod', main: false },
    ]);
  });

  it('says plainly that no shift is open', async () => {
    const page = await render(courierProfile({ openShift: null }));

    expect(text(page)).toContain('No shift is open.');
  });

  it('shows how the courier stands with the registration, and warns before it lapses', async () => {
    const page = await render(
      courierProfile({
        engagement: {
          status: 'ACTIVE',
          warningState: 'EXPIRING',
          registrationValidUntil: '2026-11-02',
          dispatchable: true,
        },
      }),
    );

    expect(text(page)).toContain('Cleared to work');
    expect(text(page)).toContain('Registration valid until');
    expect(text(page)).toContain('November');
    expect(page.querySelector('.notice.warning')?.textContent).toContain('expires soon');
  });

  it('tells a courier whose registration lapsed that they cannot take new work, as an alert', async () => {
    const page = await render(
      courierProfile({
        engagement: {
          status: 'SUSPENDED_COMPLIANCE',
          warningState: 'LAPSED',
          registrationValidUntil: '2026-09-01',
          dispatchable: false,
        },
      }),
    );

    expect(text(page)).toContain('Paused: registration needs attention');
    const alert = page.querySelector('.notice.error[role="alert"]');
    expect(alert?.textContent).toContain('cannot take new work');
  });

  it('says so when no branch is assigned', async () => {
    const page = await render(courierProfile({ branches: [] }));

    expect(text(page)).toContain('No branch is assigned to you yet');
  });

  it('explains, rather than errors, when the sign-in is fine but the account is no courier here', async () => {
    const page = await render(
      new ApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'not a courier' }),
    );

    expect(text(page)).toContain('This account is not a courier here');
    expect(page.querySelector('[role="alert"]')).toBeNull();
    expect(page.querySelector('button')).toBeNull();
  });

  it('offers a retry on a failure, with a sentence about the connection when it is one', async () => {
    const page = await render(new ApiError({ status: 0, code: 'NETWORK', detail: 'offline' }));

    expect(page.querySelector('.notice.error')?.textContent).toContain('Cannot reach the server');
    page.querySelector('button')!.click();
    await vi.waitFor(() => expect(me).toHaveBeenCalledTimes(2));
  });

  it('greets without a name when the token carried none', async () => {
    const page = await render(courierProfile(), null);

    expect(page.querySelector('h1')?.textContent?.trim()).toBe('Hello');
  });

  it("speaks the courier's language", async () => {
    const page = await render(courierProfile());
    await TestBed.inject(I18n).use('ru');
    TestBed.tick();

    expect(text(page)).toContain('Смена');
    expect(text(page)).toContain('На линии');
  });
});
