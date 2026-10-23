import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { Auth } from '../../core/auth/auth';
import { I18n } from '../../core/i18n/i18n';
import {
  CourierAccessAcceptance,
  CourierAccessApi,
  CourierAccessInspection,
} from './courier-access-api';
import { CourierAccessPage } from './courier-access-page';

const TOKEN = 'k8Qm2v1Xo9-pL3sW7yZ0aB4cD6eF8gH1iJ2kL3mN4oP';
const GOOD_PASSWORD = 'a-long-enough-password';

const INVITE: CourierAccessInspection = {
  kind: 'INVITE',
  locale: 'en',
  expiresAt: '2026-10-26T10:00:00Z',
};

class FakeLinks {
  readonly inspect = vi.fn<(token: string) => Promise<CourierAccessInspection>>();
  readonly accept = vi.fn<(token: string, password: string) => Promise<CourierAccessAcceptance>>();
}

/** Only `forgetLocalSession`: `logout()` would revoke whoever else's refresh token this handset holds. */
class FakeAuth {
  readonly forgetLocalSession = vi.fn();
}

function notFound(reason: string): ApiError {
  return new ApiError(
    'RESOURCE_NOT_FOUND',
    404,
    { type: 't', title: 'x', status: 404, reason },
    null,
  );
}

describe('CourierAccessPage (ADR 0178)', () => {
  let fixture: ComponentFixture<CourierAccessPage>;
  let links: FakeLinks;
  let auth: FakeAuth;

  beforeEach(() => {
    links = new FakeLinks();
    auth = new FakeAuth();
  });

  async function open(fragment: string | null): Promise<HTMLElement> {
    await TestBed.configureTestingModule({
      imports: [CourierAccessPage],
      providers: [
        { provide: CourierAccessApi, useValue: links },
        { provide: Auth, useValue: auth },
        { provide: ActivatedRoute, useValue: { snapshot: { fragment } } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CourierAccessPage);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.textContent).not.toContain('Checking your link');
    });
    return host;
  }

  function type(host: HTMLElement, testId: string, value: string): void {
    const input = host.querySelector<HTMLInputElement>(`[data-testid="${testId}"]`)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  it('reads the token from the fragment, shows the form in the link’s language, and sets the password', async () => {
    links.inspect.mockResolvedValue({ ...INVITE, locale: 'ru' });
    links.accept.mockResolvedValue({ sessionsEnded: true });
    const host = await open(`token=${TOKEN}`);

    expect(links.inspect).toHaveBeenCalledWith(TOKEN);
    expect(TestBed.inject(I18n).locale()).toBe('ru');

    type(host, 'courier-access-password', GOOD_PASSWORD);
    type(host, 'courier-access-confirm', GOOD_PASSWORD);
    host.querySelector<HTMLButtonElement>('[data-testid="courier-access-submit"]')!.click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="courier-access-done"]')).not.toBeNull();
    });

    expect(links.accept).toHaveBeenCalledWith(TOKEN, GOOD_PASSWORD);
    expect(auth.forgetLocalSession).toHaveBeenCalledTimes(1);
  });

  it('will not submit a short password or two that differ', async () => {
    links.inspect.mockResolvedValue(INVITE);
    const host = await open(`token=${TOKEN}`);

    type(host, 'courier-access-password', 'short');
    type(host, 'courier-access-confirm', 'short');
    expect(
      host.querySelector<HTMLButtonElement>('[data-testid="courier-access-submit"]')!.disabled,
    ).toBe(true);

    type(host, 'courier-access-password', GOOD_PASSWORD);
    type(host, 'courier-access-confirm', GOOD_PASSWORD + 'x');
    expect(
      host.querySelector<HTMLButtonElement>('[data-testid="courier-access-submit"]')!.disabled,
    ).toBe(true);
    expect(links.accept).not.toHaveBeenCalled();
  });

  it('says a link that is gone, or has expired, is not usable and asks for a new one from the manager', async () => {
    links.inspect.mockRejectedValue(notFound('EXPIRED'));
    const expired = await open(`token=${TOKEN}`);
    expect(expired.textContent).toContain('This link has expired');

    TestBed.resetTestingModule();
    links = new FakeLinks();
    links.inspect.mockRejectedValue(notFound('INVALID'));
    const invalid = await open(`token=${TOKEN}`);
    expect(invalid.textContent).toContain('This link cannot be used');
  });

  it('says so when there is no token at all, and asks the platform nothing', async () => {
    const host = await open(null);

    expect(host.textContent).toContain('This link cannot be used');
    expect(links.inspect).not.toHaveBeenCalled();
  });

  it('keeps the link usable when the platform refuses the password, and names the rule', async () => {
    links.inspect.mockResolvedValue(INVITE);
    links.accept.mockRejectedValue(
      new ApiError(
        'VALIDATION_FAILED',
        400,
        { type: 't', title: 'x', status: 400, field: 'password', policy: 'length(12)' },
        null,
      ),
    );
    const host = await open(`token=${TOKEN}`);

    type(host, 'courier-access-password', GOOD_PASSWORD);
    type(host, 'courier-access-confirm', GOOD_PASSWORD);
    host.querySelector<HTMLButtonElement>('[data-testid="courier-access-submit"]')!.click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="courier-access-error"]')).not.toBeNull();
    });

    expect(host.querySelector('[data-testid="courier-access-error"]')?.textContent).toContain(
      'at least 12',
    );
    expect(host.querySelector('[data-testid="courier-access-password"]')).not.toBeNull();
    expect(auth.forgetLocalSession).not.toHaveBeenCalled();
  });

  it('does not claim the other sessions ended when the platform says they did not', async () => {
    links.inspect.mockResolvedValue({ ...INVITE, kind: 'RESET' });
    links.accept.mockResolvedValue({ sessionsEnded: false });
    const host = await open(`token=${TOKEN}`);

    type(host, 'courier-access-password', GOOD_PASSWORD);
    type(host, 'courier-access-confirm', GOOD_PASSWORD);
    host.querySelector<HTMLButtonElement>('[data-testid="courier-access-submit"]')!.click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="courier-access-done"]')).not.toBeNull();
    });

    expect(host.textContent).toContain('other sessions could not be ended');
  });
});
