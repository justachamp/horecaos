import { TestBed } from '@angular/core/testing';

import {
  callbackFor,
  configureAuth,
  lastAuthorization,
  ORIGIN,
  type AuthHarness,
} from '../../../testing/auth-harness';
import { I18n } from '../i18n/i18n';
import { AuthService, REFRESH_SKEW_MS } from './auth.service';
import { OidcError } from './oidc-client';

describe('AuthService', () => {
  let harness: AuthHarness;
  let auth: AuthService;

  beforeEach(() => {
    sessionStorage.clear();
    harness = configureAuth();
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => sessionStorage.clear());

  async function signedIn(returnTo = '/'): Promise<string> {
    await auth.beginSignIn(returnTo);
    return auth.completeSignIn(callbackFor(harness));
  }

  describe('signing in', () => {
    it("leaves for the identity provider in the courier's language", async () => {
      await TestBed.inject(I18n).use('uz-Latn');

      await auth.beginSignIn('/');

      expect(lastAuthorization(harness).searchParams.get('ui_locales')).toBe('uz-Latn');
    });

    it('comes back signed in, with the screen it was heading for', async () => {
      const destination = await signedIn('/profile');

      expect(destination).toBe('/profile');
      expect(auth.signedIn()).toBe(true);
      expect(auth.claims()?.name).toBe('Alisher Karimov');
      await expect(auth.accessToken()).resolves.toBe('access-1');
    });

    it('redeems with the verifier it generated, not any other', async () => {
      await signedIn();

      // The fake provider hashes the verifier it was sent and compares it to the challenge in the
      // authorization URL; a wrong verifier would have refused the exchange above.
      expect(harness.provider.tokenRequests()).toHaveLength(1);
    });

    it('does not follow a hostile return path', async () => {
      const destination = await signedIn('//evil.example/steal');

      expect(destination).toBe('/');
    });

    it('is single-use: reloading the callback does not replay the verifier', async () => {
      await auth.beginSignIn('/');
      const callback = callbackFor(harness);
      await auth.completeSignIn(callback);

      await expect(auth.completeSignIn(callback)).rejects.toMatchObject({
        code: 'no_pending_login',
      });
    });

    it('fails visibly, and does not leave, when the browser will not keep the login in flight', async () => {
      const original = Storage.prototype.setItem;
      Storage.prototype.setItem = () => {
        throw new DOMException('blocked', 'SecurityError');
      };
      try {
        await expect(auth.beginSignIn('/')).rejects.toMatchObject({ code: 'storage_unavailable' });
      } finally {
        Storage.prototype.setItem = original;
      }

      expect(harness.navigations).toHaveLength(0);
    });

    it('keeps no token in any browser storage', async () => {
      await signedIn();

      for (const store of [localStorage, sessionStorage]) {
        const everything = JSON.stringify({ ...store });
        expect(everything).not.toContain('access-1');
        expect(everything).not.toContain('refresh-1');
        expect(everything).not.toContain('id_token');
      }
    });
  });

  describe('holding the session', () => {
    it('answers null when nobody is signed in', async () => {
      await expect(auth.accessToken()).resolves.toBeNull();
      expect(auth.signedIn()).toBe(false);
    });

    it('renews a token that is about to lapse before handing it out', async () => {
      await signedIn();
      harness.advance(300_000 - REFRESH_SKEW_MS + 1);

      await expect(auth.accessToken()).resolves.toBe('access-2');
    });

    it('does not renew a token that still has its margin', async () => {
      await signedIn();
      harness.advance(60_000);

      await expect(auth.accessToken()).resolves.toBe('access-1');
      expect(harness.provider.tokenRequests()).toHaveLength(1);
    });

    it('renews once for three screens asking at the same moment -- a rotated refresh token is spent by its second use', async () => {
      await signedIn();
      harness.advance(300_000);

      const tokens = await Promise.all([
        auth.accessToken(),
        auth.accessToken(),
        auth.accessToken(),
      ]);

      expect(tokens).toEqual(['access-2', 'access-2', 'access-2']);
      expect(harness.provider.tokenRequests()).toHaveLength(2);
    });

    it('ends the session when the renewal is refused, and says so by answering null', async () => {
      await signedIn();
      harness.advance(300_000);
      harness.provider.refuseWith = 'invalid_grant';

      await expect(auth.accessToken()).resolves.toBeNull();
      expect(auth.signedIn()).toBe(false);
    });

    describe('when the identity provider cannot be reached for a renewal', () => {
      // A courier in a lift or a basement: the access token is inside its skew, the refresh token is
      // still good, and the fetch fails. That is not the end of the session.
      async function lapsedAndOffline() {
        await signedIn();
        harness.advance(300_000 - REFRESH_SKEW_MS + 1);
        harness.provider.tokenEndpointUnreachable = true;
      }

      it('keeps the session, and hands out the token it holds rather than none', async () => {
        await lapsedAndOffline();

        await expect(auth.accessToken()).resolves.toBe('access-1');
        expect(auth.signedIn()).toBe(true);
        expect(auth.claims()?.name).toBe('Alisher Karimov');
      });

      it('renews with the same refresh token once the connection is back', async () => {
        await lapsedAndOffline();
        await auth.accessToken();

        harness.provider.tokenEndpointUnreachable = false;

        await expect(auth.accessToken()).resolves.toBe('access-2');
        expect(auth.signedIn()).toBe(true);
      });

      it('keeps the session through a gateway error or a rate limit as well', async () => {
        await signedIn();
        harness.advance(300_000);
        harness.provider.failNextTokenRequestWith = 503;

        await expect(auth.accessToken()).resolves.toBe('access-1');
        expect(auth.signedIn()).toBe(true);
        await expect(auth.accessToken()).resolves.toBe('access-2');
      });

      it('says the tokens were kept when a renewal is asked for on demand, and not that the session ended', async () => {
        await lapsedAndOffline();

        await expect(auth.forceRefresh()).resolves.toBe('kept');
        expect(auth.signedIn()).toBe(true);
      });

      it('still ends the session once the refresh token itself has run out while offline', async () => {
        await lapsedAndOffline();
        harness.advance(31 * 60 * 1000);

        await expect(auth.accessToken()).resolves.toBeNull();
        expect(auth.signedIn()).toBe(false);
      });
    });

    it('does not even try to renew a refresh token that has itself expired', async () => {
      await signedIn();
      harness.advance(31 * 60 * 1000);

      await expect(auth.accessToken()).resolves.toBeNull();
      expect(harness.provider.tokenRequests()).toHaveLength(1);
    });

    it('can be made to renew on demand, as the API does after a 401', async () => {
      await signedIn();

      await expect(auth.forceRefresh()).resolves.toBe('renewed');
      await expect(auth.accessToken()).resolves.toBe('access-2');
    });

    it('forgets everything when told the session is over', async () => {
      await signedIn();

      auth.expire();

      await expect(auth.accessToken()).resolves.toBeNull();
      expect(auth.claims()).toBeNull();
    });
  });

  describe('signing out', () => {
    it('clears the session and ends it at the identity provider, with the identity token as the hint', async () => {
      await signedIn();

      await auth.signOut();

      const destination = new URL(harness.navigations.at(-1)!);
      expect(destination.pathname).toBe('/realms/horecaos/protocol/openid-connect/logout');
      expect(destination.searchParams.get('id_token_hint')).toMatch(/^.+\..+\..+$/);
      expect(destination.searchParams.get('post_logout_redirect_uri')).toBe(
        `${ORIGIN}/auth/signed-out`,
      );
      await expect(auth.accessToken()).resolves.toBeNull();
    });

    it('still lands on the signed-out screen when the identity provider cannot be asked', async () => {
      harness.provider.discoveryIssuer = 'https://auth.evil.test/realms/horecaos';

      await auth.signOut();

      expect(harness.navigations.at(-1)).toBe(`${ORIGIN}/auth/signed-out`);
    });
  });

  it('exposes the failure of a sign-in as an OidcError the screens can branch on', async () => {
    await auth.beginSignIn('/');

    await expect(
      auth.completeSignIn(new URLSearchParams({ code: 'x', state: 'forged' })),
    ).rejects.toBeInstanceOf(OidcError);
  });
});
