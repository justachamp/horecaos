import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { courierProfile } from '../../../testing/fixtures';
import { AuthService } from '../../core/auth/auth.service';
import { CourierProfileStore } from '../../core/courier/courier-profile.store';
import { I18n } from '../../core/i18n/i18n';
import { APP_VERSION } from '../../core/version';
import { ProfilePage } from './profile';

describe('ProfilePage', () => {
  const signOut = vi.fn().mockResolvedValue(undefined);

  async function render() {
    signOut.mockClear();
    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuthService,
          useValue: {
            claims: signal({ subject: 's', name: 'Alisher Karimov', username: 'alisher' }),
            signOut,
          },
        },
        { provide: CourierProfileStore, useValue: { profile: signal(courierProfile()) } },
      ],
    });
    const i18n = TestBed.inject(I18n);
    await i18n.require(['profile']);
    await i18n.use('en');
    const fixture = TestBed.createComponent(ProfilePage);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it("shows who is signed in and the tenant's own label for the courier", async () => {
    const page = await render();

    expect(page.textContent).toContain('Alisher Karimov');
    expect(page.textContent).toContain('alisher');
    expect(page.textContent).toContain('REF-00E1');
  });

  it('names the exact version of the app', async () => {
    const page = await render();

    expect(page.textContent).toContain(`Version ${APP_VERSION}`);
  });

  it('switches language from the profile and marks the chosen one for assistive technology', async () => {
    const page = await render();
    const radios = [...page.querySelectorAll<HTMLButtonElement>('[role="radio"]')];
    expect(radios.map((radio) => radio.getAttribute('aria-checked'))).toEqual([
      'false',
      'false',
      'true',
    ]);

    radios.find((radio) => radio.textContent!.includes('Русский'))!.click();

    await vi.waitFor(() => expect(TestBed.inject(I18n).locale()).toBe('ru'));
  });

  it('signs out', async () => {
    const page = await render();

    [...page.querySelectorAll('button')].find((b) => b.textContent!.includes('Sign out'))!.click();

    expect(signOut).toHaveBeenCalledOnce();
  });
});
