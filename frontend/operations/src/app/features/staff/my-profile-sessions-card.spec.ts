import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { I18n } from '../../core/i18n/i18n';
import { MyProfileSessionsCard } from './my-profile-sessions-card';
import { OwnSessions, StaffSessionsApi } from './staff-sessions-api';

const TENANT = '018fb100-6000-7000-8000-0000000000a1';

const laptop = {
  id: 'kc-laptop',
  deviceLabel: { browser: 'CHROME', os: 'WINDOWS', form: 'DESKTOP' },
  signedInAt: '2026-10-01T08:00:00Z',
  lastSeenAt: '2026-10-08T09:30:00Z',
  current: true,
  state: 'ACTIVE',
  endReason: null,
} as const;
const phone = {
  id: 'kc-phone',
  deviceLabel: { browser: 'SAFARI', os: 'IOS', form: 'PHONE' },
  signedInAt: '2026-10-02T08:00:00Z',
  lastSeenAt: '2026-10-07T18:00:00Z',
  current: false,
  state: 'ACTIVE',
  endReason: null,
} as const;
const unknown = {
  id: 'kc-old',
  deviceLabel: null,
  signedInAt: '2026-09-01T08:00:00Z',
  lastSeenAt: '2026-09-02T08:00:00Z',
  current: false,
  state: 'ACTIVE',
  endReason: null,
} as const;
const gone = {
  id: 'kc-gone',
  deviceLabel: { browser: 'FIREFOX', os: 'LINUX', form: 'DESKTOP' },
  signedInAt: '2026-09-20T08:00:00Z',
  lastSeenAt: '2026-09-20T09:00:00Z',
  current: false,
  state: 'ENDED',
  endReason: 'SIGNED_OUT_EVERYWHERE',
} as const;

const two: OwnSessions = { keycloak: 'AVAILABLE', items: [laptop, phone, gone] };

describe('MyProfileSessionsCard (ADR 0176)', () => {
  let fixture: ComponentFixture<MyProfileSessionsCard>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    terminateAll: ReturnType<typeof vi.fn>;
    terminateOne: ReturnType<typeof vi.fn>;
  };
  let auth: { forgetLocalSession: ReturnType<typeof vi.fn> };
  let router: { navigateByUrl: ReturnType<typeof vi.fn> };

  async function setUp(initial: OwnSessions | Error): Promise<void> {
    api = { list: vi.fn(), terminateAll: vi.fn(), terminateOne: vi.fn() };
    if (initial instanceof Error) {
      api.list.mockRejectedValue(initial);
    } else {
      api.list.mockResolvedValue(initial);
    }
    auth = { forgetLocalSession: vi.fn() };
    router = { navigateByUrl: vi.fn().mockResolvedValue(true) };
    await TestBed.configureTestingModule({
      imports: [MyProfileSessionsCard],
      providers: [
        { provide: StaffSessionsApi, useValue: api },
        { provide: Auth, useValue: auth },
        { provide: Router, useValue: router },
        {
          provide: CurrentTenant,
          useValue: { ensureLoaded: () => Promise.resolve(), tenantId: () => TENANT },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(MyProfileSessionsCard);
    fixture.detectChanges();
    await settle();
  }

  const root = (): HTMLElement => fixture.nativeElement;
  const text = (): string => root().textContent ?? '';
  const q = <T extends HTMLElement>(testId: string): T | null =>
    root().querySelector<T>(`[data-testid="${testId}"]`);

  /** The card awaits the tenant and then the list, so a few microtask hops pass before it renders. */
  async function settle(): Promise<void> {
    for (let hop = 0; hop < 8; hop++) {
      await Promise.resolve();
    }
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function chooseReason(index = 0): Promise<void> {
    const radios = root().querySelectorAll<HTMLInputElement>('[data-testid="sessions-reason"]');
    radios[index].checked = true;
    radios[index].dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  beforeEach(() => TestBed.resetTestingModule());

  it('lists each device with its browser, system and form, marks this one, and says whose sessions these are', async () => {
    await setUp(two);

    const devices = Array.from(root().querySelectorAll('[data-testid="session-device"]')).map(
      (node) => node.textContent?.trim(),
    );
    expect(devices).toEqual(['Chrome on Windows (computer)', 'Safari on iOS (phone)']);
    expect(root().querySelectorAll('[data-testid="session-current"]')).toHaveLength(1);
    expect(
      root()
        .querySelector('[data-testid="session-current"]')
        ?.closest('li')
        ?.querySelector('[data-testid="session-device"]')?.textContent,
    ).toContain('Chrome');
    expect(q('sessions-scope-note')?.textContent).toContain('every company where you work');
  });

  it('names a session with no record «Unknown device», and shows an ended one with why', async () => {
    await setUp({ keycloak: 'AVAILABLE', items: [laptop, unknown, gone] });

    expect(text()).toContain('Unknown device');
    expect(q('sessions-ended')?.textContent).toContain('signed out everywhere');
    expect(q('sessions-ended')?.textContent).toContain('Firefox on Linux');
  });

  it('says the list is only what this system recorded when Keycloak could not be asked, and still offers the act', async () => {
    await setUp({ keycloak: 'UNAVAILABLE', items: [laptop] });

    expect(q('sessions-unavailable')?.textContent).toContain('only what this system recorded');
    expect(q('sessions-end-all')).not.toBeNull();
  });

  it('says it could not read the sessions instead of showing an empty list that would read as "none"', async () => {
    await setUp(new Error('boom'));

    expect(q('sessions-error')?.textContent).toContain('Could not read');
    expect(q('sessions-end-all')).toBeNull();
  });

  it('asks before signing out everywhere: the confirmation names how many end, that this device goes too and that every company is affected, and needs a reason', async () => {
    await setUp(two);

    q<HTMLButtonElement>('sessions-end-all')?.click();
    fixture.detectChanges();

    const body = q('sessions-confirm')?.textContent ?? '';
    expect(q('sessions-confirm-body')?.textContent).toContain('Sessions that will end: 2');
    expect(q('sessions-confirm-body')?.textContent).toContain('This device signs out too');
    expect(body).toContain('every company where you work');
    expect(body).toContain('may still succeed for a few minutes');
    expect(q<HTMLButtonElement>('sessions-confirm-go')?.disabled).toBe(true);
    expect(api.terminateAll).not.toHaveBeenCalled();
  });

  it('ends everything with the chosen reason, then forgets this device and goes to the sign-in page', async () => {
    await setUp(two);
    api.terminateAll.mockResolvedValue(undefined);
    q<HTMLButtonElement>('sessions-end-all')?.click();
    fixture.detectChanges();
    await chooseReason(2);

    q<HTMLButtonElement>('sessions-confirm-go')?.click();
    await settle();

    expect(api.terminateAll).toHaveBeenCalledWith(TENANT, 'SUSPECTED_COMPROMISE');
    expect(auth.forgetLocalSession).toHaveBeenCalledTimes(1);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('does not clear this device or leave when the platform could not reach the sign-in service, and says nothing was ended', async () => {
    await setUp(two);
    api.terminateAll.mockRejectedValue(
      new ApiError(
        'IDENTITY_PROVIDER_UNAVAILABLE',
        503,
        { status: 503, code: 'IDENTITY_PROVIDER_UNAVAILABLE' },
        null,
      ),
    );
    q<HTMLButtonElement>('sessions-end-all')?.click();
    fixture.detectChanges();
    await chooseReason(0);

    q<HTMLButtonElement>('sessions-confirm-go')?.click();
    await settle();

    expect(q('sessions-failure')?.textContent).toContain('Nothing was ended');
    expect(auth.forgetLocalSession).not.toHaveBeenCalled();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    expect(q('sessions-confirm')).not.toBeNull();
  });

  it('ends one other session and reads the list again, staying signed in', async () => {
    await setUp(two);
    api.terminateOne.mockResolvedValue(undefined);
    api.list.mockResolvedValue({ keycloak: 'AVAILABLE', items: [laptop] });
    const buttons = root().querySelectorAll<HTMLButtonElement>('[data-testid="session-end"]');
    buttons[1].click();
    fixture.detectChanges();
    expect(q('sessions-confirm-body')?.textContent).toContain('Sessions that will end: 1');
    await chooseReason(1);

    q<HTMLButtonElement>('sessions-confirm-go')?.click();
    await settle();

    expect(api.terminateOne).toHaveBeenCalledWith(TENANT, 'kc-phone', 'SHARED_DEVICE');
    expect(auth.forgetLocalSession).not.toHaveBeenCalled();
    expect(q('sessions-notice')?.textContent).toContain('was ended');
    expect(root().querySelectorAll('[data-testid="session-device"]')).toHaveLength(1);
  });

  it('leaves too when the session ended is the one the page runs in', async () => {
    await setUp(two);
    api.terminateOne.mockResolvedValue(undefined);
    root().querySelectorAll<HTMLButtonElement>('[data-testid="session-end"]')[0].click();
    fixture.detectChanges();
    await chooseReason(3);

    q<HTMLButtonElement>('sessions-confirm-go')?.click();
    await settle();

    expect(api.terminateOne).toHaveBeenCalledWith(TENANT, 'kc-laptop', 'JUST_IN_CASE');
    expect(auth.forgetLocalSession).toHaveBeenCalledTimes(1);
    expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
  });

  it('closes the confirmation on cancel without calling anything', async () => {
    await setUp(two);
    q<HTMLButtonElement>('sessions-end-all')?.click();
    fixture.detectChanges();

    q<HTMLButtonElement>('sessions-confirm-cancel')?.click();
    fixture.detectChanges();

    expect(q('sessions-confirm')).toBeNull();
    expect(api.terminateAll).not.toHaveBeenCalled();
  });
});
