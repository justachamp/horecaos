import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Auth } from '../../core/auth/auth';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { I18n } from '../../core/i18n/i18n';
import { MyProfilePasswordCard } from './my-profile-password-card';
import { StaffApi } from './staff-api';

describe('MyProfilePasswordCard (ADR 0098, row X.5)', () => {
  let fixture: ComponentFixture<MyProfilePasswordCard>;
  let api: { passwordHandoff: ReturnType<typeof vi.fn> };
  let navigate: ReturnType<typeof vi.fn>;

  async function setUp(
    handoff: { emailConfigured: boolean } | Error,
    signInName: string | null = '+998901234567',
  ): Promise<void> {
    api = { passwordHandoff: vi.fn() };
    if (handoff instanceof Error) {
      api.passwordHandoff.mockRejectedValue(handoff);
    } else {
      api.passwordHandoff.mockResolvedValue(handoff);
    }
    navigate = vi.fn().mockResolvedValue(true);
    await TestBed.configureTestingModule({
      imports: [MyProfilePasswordCard],
      providers: [
        { provide: StaffApi, useValue: api },
        {
          provide: CurrentTenant,
          useValue: { ensureLoaded: () => Promise.resolve(), tenantId: () => 't1' },
        },
        { provide: Auth, useValue: { signInName: signal(signInName) } },
        { provide: Router, useValue: { navigate } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(MyProfilePasswordCard);
    fixture.detectChanges();
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
  }

  function text(): string {
    return fixture.nativeElement.textContent as string;
  }

  function button(): HTMLButtonElement {
    return fixture.nativeElement.querySelector('[data-testid="password-change"]');
  }

  beforeEach(() => {
    localStorage.clear();
  });

  it('says before the button that a reset ends every sign-in, this console included', async () => {
    await setUp({ emailConfigured: true });

    expect(
      fixture.nativeElement.querySelector('[data-testid="password-ends-all"]').textContent,
    ).toContain('signed out everywhere, this console included');
    expect(text()).not.toContain('not built');
  });

  it('hands the person to the reset page with their own sign-in name, in the router state and not the address', async () => {
    await setUp({ emailConfigured: true });

    button().click();

    expect(navigate).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledWith(['/forgot-password'], {
      state: { login: '+998901234567' },
    });
    const [commands] = navigate.mock.calls[0];
    expect(JSON.stringify(commands)).not.toContain('998901234567');
  });

  it('hands over nothing when the console does not know the sign-in name, and the page asks for it', async () => {
    await setUp({ emailConfigured: true }, null);

    button().click();

    expect(navigate).toHaveBeenCalledWith(['/forgot-password'], { state: {} });
  });

  it('says when the platform cannot send email, and offers no button that would promise a link', async () => {
    await setUp({ emailConfigured: false });

    expect(fixture.nativeElement.querySelector('[data-testid="password-no-mail"]')).not.toBeNull();
    expect(button().disabled).toBe(true);
    button().click();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('keeps the button when the platform could not be asked: the reset is still the way out', async () => {
    await setUp(new Error('offline'));

    expect(fixture.nativeElement.querySelector('[data-testid="password-no-mail"]')).toBeNull();
    expect(button().disabled).toBe(false);
  });
});
