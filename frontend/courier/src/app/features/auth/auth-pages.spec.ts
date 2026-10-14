import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { AuthService } from '../../core/auth/auth.service';
import { OidcError } from '../../core/auth/oidc-client';
import { I18n } from '../../core/i18n/i18n';
import { CallbackPage } from './callback';
import { SignedOutPage } from './signed-out';
import { SignInErrorPage } from './sign-in-error';

describe('the sign-in pages', () => {
  const completeSignIn = vi.fn();
  const beginSignIn = vi.fn();

  async function open(url: string, redeem?: () => Promise<string>) {
    completeSignIn.mockReset().mockImplementation(redeem ?? (async () => '/'));
    beginSignIn.mockReset().mockResolvedValue(undefined);
    TestBed.configureTestingModule({
      providers: [
        // The error screen reads its code through component input binding, as the app's does.
        provideRouter(
          [
            { path: 'auth/callback', component: CallbackPage },
            { path: 'auth/error', component: SignInErrorPage },
            { path: 'auth/signed-out', component: SignedOutPage },
            { path: 'profile', component: SignedOutPage },
          ],
          withComponentInputBinding(),
        ),
        { provide: AuthService, useValue: { completeSignIn, beginSignIn } },
      ],
    });
    await TestBed.inject(I18n).use('en');
    return RouterTestingHarness.create(url);
  }

  describe('the callback', () => {
    it('redeems the query, then replaces the address with the screen the courier was heading for', async () => {
      const navigate = vi.spyOn(Router.prototype, 'navigateByUrl');

      await open('/auth/callback?code=abc&state=xyz', async () => '/profile');

      await vi.waitFor(() =>
        expect(navigate).toHaveBeenCalledWith('/profile', { replaceUrl: true }),
      );
      const query = completeSignIn.mock.calls[0][0] as URLSearchParams;
      expect(query.get('code')).toBe('abc');
      expect(query.get('state')).toBe('xyz');
      navigate.mockRestore();
    });

    it("goes to the error screen with the failure's code, and does not start another sign-in by itself", async () => {
      const navigate = vi.spyOn(Router.prototype, 'navigate');

      await open('/auth/callback?code=abc&state=forged', async () => {
        throw new OidcError('state_mismatch', 'forged');
      });

      await vi.waitFor(() =>
        expect(navigate).toHaveBeenCalledWith(['/auth/error'], {
          queryParams: { code: 'state_mismatch' },
          replaceUrl: true,
        }),
      );
      expect(beginSignIn).not.toHaveBeenCalled();
      navigate.mockRestore();
    });
  });

  describe('the error screen', () => {
    it('gives each failure its own sentence', async () => {
      const harness = await open('/auth/error?code=access_denied');

      expect(harness.routeNativeElement!.textContent).toContain('Sign-in was cancelled.');
    });

    it('falls back to a general sentence for a code it does not know -- including one from the address bar', async () => {
      const harness = await open('/auth/error?code=constructor');

      expect(harness.routeNativeElement!.textContent).toContain('Sign-in did not complete.');
    });

    it('announces the failure to assistive technology', async () => {
      const harness = await open('/auth/error?code=discovery_failed');

      expect(harness.routeNativeElement!.querySelector('[role="alert"]')).not.toBeNull();
    });

    it('lets the courier try again, from the start screen', async () => {
      const harness = await open('/auth/error?code=state_mismatch');

      harness.routeNativeElement!.querySelector('button')!.click();

      expect(beginSignIn).toHaveBeenCalledWith('/');
    });
  });

  describe('the signed-out screen', () => {
    it('offers to sign in again', async () => {
      const harness = await open('/auth/signed-out');

      harness.routeNativeElement!.querySelector('button')!.click();

      expect(harness.routeNativeElement!.textContent).toContain('You are signed out');
      expect(beginSignIn).toHaveBeenCalledWith('/');
    });
  });
});
