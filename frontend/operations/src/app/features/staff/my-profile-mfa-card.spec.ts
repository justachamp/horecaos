import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { MfaApi, OwnMfa } from '../../core/auth/mfa-api';
import { I18n } from '../../core/i18n/i18n';
import { MyProfileMfaCard } from './my-profile-mfa-card';

const off: OwnMfa = {
  enrolled: false,
  authenticators: [],
  requirement: 'NOT_REQUIRED',
  maximum: 2,
};
const one: OwnMfa = {
  enrolled: true,
  authenticators: [{ id: 'c1', label: 'phone', createdAt: '2026-10-01T10:00:00Z' }],
  requirement: 'REQUIRED',
  maximum: 2,
};
const two: OwnMfa = {
  enrolled: true,
  authenticators: [
    { id: 'c1', label: 'phone', createdAt: '2026-10-01T10:00:00Z' },
    { id: 'c2', label: null, createdAt: null },
  ],
  requirement: 'NOT_REQUIRED',
  maximum: 2,
};

describe('MyProfileMfaCard (ADR 0148)', () => {
  let fixture: ComponentFixture<MyProfileMfaCard>;
  let api: {
    own: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
    begin: ReturnType<typeof vi.fn>;
  };

  async function setUp(initial: OwnMfa | Error): Promise<void> {
    api = { own: vi.fn(), remove: vi.fn(), begin: vi.fn() };
    if (initial instanceof Error) {
      api.own.mockRejectedValue(initial);
    } else {
      api.own.mockResolvedValue(initial);
    }
    await TestBed.configureTestingModule({
      imports: [MyProfileMfaCard],
      providers: [{ provide: MfaApi, useValue: api }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(MyProfileMfaCard);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const root = (): HTMLElement => fixture.nativeElement;
  const text = (): string => root().textContent ?? '';

  beforeEach(() => TestBed.resetTestingModule());

  it('says it is off, and offers to turn it on, for a person with no authenticator', async () => {
    await setUp(off);

    expect(root().querySelector('[data-testid="mfa-status"]')?.textContent).toContain('Off');
    expect(root().querySelector('[data-testid="mfa-devices"]')).toBeNull();
    expect(root().querySelector('[data-testid="mfa-enrol"]')?.textContent).toContain('Turn on');
  });

  it('lists each authenticator with its name and date, says the account is required to use it, and offers a second device', async () => {
    await setUp(one);

    expect(text()).toContain('On:');
    expect(text()).toContain('required to use it');
    expect(root().querySelectorAll('[data-testid="mfa-devices"] li')).toHaveLength(1);
    expect(text()).toContain('phone');
    expect(text()).toContain('Added');
    expect(root().querySelector('[data-testid="mfa-enrol"]')?.textContent).toContain(
      'another device',
    );
  });

  it('refuses to offer removing the only authenticator, and says what to do instead', async () => {
    await setUp(one);

    expect(root().querySelector('[data-testid="mfa-remove"]')).toBeNull();
    expect(text()).toContain('only authenticator');
    expect(text()).toContain('administrator can reset');
  });

  it('offers no further device at the limit, names an unnamed authenticator, and lets either be removed', async () => {
    await setUp(two);

    expect(root().querySelector('[data-testid="mfa-enrol"]')).toBeNull();
    expect(text()).toContain('Authenticator');
    expect(root().querySelectorAll('[data-testid="mfa-remove"]')).toHaveLength(2);
  });

  it('removes one with the password and a code, then reads the list again', async () => {
    await setUp(two);
    api.remove.mockResolvedValue(undefined);
    api.own.mockResolvedValue(one);
    (root().querySelectorAll('[data-testid="mfa-remove"]')[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    const password = root().querySelector('#mfa-remove-password') as HTMLInputElement;
    password.value = 'correct horse';
    password.dispatchEvent(new Event('input'));
    const cells = Array.from(
      root().querySelectorAll<HTMLInputElement>('[data-testid="q-otp-input-cell"]'),
    );
    [...'482913'].forEach((digit, index) => {
      cells[index].value = digit;
      cells[index].dispatchEvent(new Event('input'));
    });
    fixture.detectChanges();

    (root().querySelector('[data-testid="mfa-remove-confirm"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.remove).toHaveBeenCalledWith('c2', 'correct horse', '482913');
    expect(root().querySelector('[data-testid="mfa-notice"]')?.textContent).toContain(
      'was removed',
    );
    expect(root().querySelectorAll('[data-testid="mfa-devices"] li')).toHaveLength(1);
  });

  it('keeps the form open, clears the code and says why when the removal is refused', async () => {
    await setUp(two);
    api.remove.mockRejectedValue(
      new ApiError(
        'MFA_CONFIRMATION_CODE_INVALID',
        422,
        { status: 422, code: 'MFA_CONFIRMATION_CODE_INVALID' },
        null,
      ),
    );
    (root().querySelectorAll('[data-testid="mfa-remove"]')[0] as HTMLButtonElement).click();
    fixture.detectChanges();
    const password = root().querySelector('#mfa-remove-password') as HTMLInputElement;
    password.value = 'correct horse';
    password.dispatchEvent(new Event('input'));
    const cells = Array.from(
      root().querySelectorAll<HTMLInputElement>('[data-testid="q-otp-input-cell"]'),
    );
    [...'000000'].forEach((digit, index) => {
      cells[index].value = digit;
      cells[index].dispatchEvent(new Event('input'));
    });
    fixture.detectChanges();

    (root().querySelector('[data-testid="mfa-remove-confirm"]') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(
      root().querySelector('[data-testid="mfa-remove-form"] [role="alert"]')?.textContent,
    ).toContain('nothing was removed');
    expect(root().querySelectorAll('[data-testid="mfa-devices"] li')).toHaveLength(2);
  });

  it('says it could not read the list instead of showing an empty one that would read as "off"', async () => {
    await setUp(new Error('boom'));

    expect(root().querySelector('[role="alert"]')?.textContent).toContain('Could not read');
    expect(root().querySelector('[data-testid="mfa-enrol"]')).toBeNull();
  });
});
