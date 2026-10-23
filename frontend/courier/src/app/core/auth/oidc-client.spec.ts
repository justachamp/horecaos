import { TestBed } from '@angular/core/testing';

import { configureAuth, ORIGIN, type AuthHarness } from '../../../testing/auth-harness';
import { TEST_CONFIG } from '../../../testing/fixtures';
import { OidcClient, OidcError, LOGIN_TTL_MS, type PendingLogin } from './oidc-client';
import { codeChallengeS256 } from './pkce';

describe('OidcClient', () => {
  let harness: AuthHarness;
  let client: OidcClient;

  beforeEach(() => {
    harness = configureAuth();
    client = TestBed.inject(OidcClient);
  });

  async function started(): Promise<{ url: URL; pending: PendingLogin }> {
    const { url, pending } = await client.startLogin('/profile', 'uz-Latn');
    harness.provider.rememberAuthorization(url);
    return { url: new URL(url), pending };
  }

  describe('the authorization request', () => {
    it('is a public-client code request with an S256 challenge and every binding', async () => {
      const { url, pending } = await started();

      expect(url.origin + url.pathname).toBe(
        `${TEST_CONFIG.issuerUrl}/protocol/openid-connect/auth`,
      );
      expect(Object.fromEntries(url.searchParams)).toEqual({
        client_id: 'horecaos-courier',
        redirect_uri: `${ORIGIN}/auth/callback`,
        response_type: 'code',
        scope: 'openid profile',
        state: pending.state,
        nonce: pending.nonce,
        code_challenge: await codeChallengeS256(pending.verifier),
        code_challenge_method: 'S256',
        ui_locales: 'uz-Latn',
      });
    });

    it('never puts the verifier, a secret or a token in the URL', async () => {
      const { url, pending } = await started();

      expect(url.href).not.toContain(pending.verifier);
      expect(url.href).not.toMatch(/client_secret|access_token|refresh_token/);
    });

    it('starts a different login every time', async () => {
      const first = (await client.startLogin('/', 'ru')).pending;
      const second = (await client.startLogin('/', 'ru')).pending;

      expect(first.state).not.toBe(second.state);
      expect(first.nonce).not.toBe(second.nonce);
      expect(first.verifier).not.toBe(second.verifier);
    });

    it('reads the discovery document once, not once per login', async () => {
      await client.startLogin('/', 'ru');
      await client.startLogin('/', 'ru');

      const discoveries = harness.provider.requests.filter((request) =>
        request.url.endsWith('/.well-known/openid-configuration'),
      );
      expect(discoveries).toHaveLength(1);
    });
  });

  describe('discovery', () => {
    it('refuses a document that names another issuer', async () => {
      harness.provider.discoveryIssuer = 'https://auth.evil.test/realms/horecaos';

      await expect(client.startLogin('/', 'ru')).rejects.toMatchObject({ code: 'issuer_mismatch' });
    });

    it('refuses a document whose endpoints are on another origin -- tokens and the browser are about to be sent there', async () => {
      harness.provider.endpointBase = 'https://auth.evil.test/realms/horecaos';

      await expect(client.startLogin('/', 'ru')).rejects.toMatchObject({ code: 'issuer_mismatch' });
    });

    it('does not remember a failed discovery', async () => {
      harness.provider.discoveryIssuer = 'https://auth.evil.test/realms/horecaos';
      await expect(client.startLogin('/', 'ru')).rejects.toBeInstanceOf(OidcError);

      harness.provider.discoveryIssuer = TEST_CONFIG.issuerUrl;

      await expect(client.startLogin('/', 'ru')).resolves.toBeDefined();
    });
  });

  describe('redeeming the callback', () => {
    it('trades the code and the verifier for tokens and an identity', async () => {
      const { pending } = await started();

      const { tokens, claims } = await client.completeLogin(
        new URLSearchParams({ code: 'code-1', state: pending.state }),
        pending,
      );

      expect(tokens.accessToken).toBe('access-1');
      expect(tokens.refreshToken).toBe('refresh-1');
      expect(claims).toEqual({
        subject: 'keycloak-alisher',
        name: 'Alisher Karimov',
        username: 'alisher',
      });
      const exchange = harness.provider.tokenRequests().at(-1)?.form;
      expect(exchange?.get('grant_type')).toBe('authorization_code');
      expect(exchange?.get('code_verifier')).toBe(pending.verifier);
      expect(exchange?.get('redirect_uri')).toBe(`${ORIGIN}/auth/callback`);
      expect(exchange?.has('client_secret')).toBe(false);
    });

    it('turns the lifetimes into instants on the injected clock', async () => {
      const { pending } = await started();
      harness.provider.accessTokenSeconds = 120;

      const before = Date.now();
      const { tokens } = await client.completeLogin(
        new URLSearchParams({ code: 'code-1', state: pending.state }),
        pending,
      );

      expect(tokens.accessExpiresAt - before).toBeGreaterThanOrEqual(120_000);
      expect(tokens.accessExpiresAt - before).toBeLessThan(121_000);
      expect(tokens.refreshExpiresAt).not.toBeNull();
    });

    it('refuses a callback whose state is not the one the login started with', async () => {
      const { pending } = await started();

      await expect(
        client.completeLogin(new URLSearchParams({ code: 'code-1', state: 'forged' }), pending),
      ).rejects.toMatchObject({ code: 'state_mismatch' });
      expect(harness.provider.tokenRequests()).toHaveLength(0);
    });

    it('refuses a callback with no login in flight, without calling the token endpoint', async () => {
      await expect(
        client.completeLogin(new URLSearchParams({ code: 'code-1', state: 'x' }), null),
      ).rejects.toMatchObject({ code: 'no_pending_login' });
      expect(harness.provider.tokenRequests()).toHaveLength(0);
    });

    it('refuses a login that was left waiting past its lifetime', async () => {
      const { pending } = await started();
      harness.advance(LOGIN_TTL_MS + 1);

      await expect(
        client.completeLogin(
          new URLSearchParams({ code: 'code-1', state: pending.state }),
          pending,
        ),
      ).rejects.toMatchObject({ code: 'login_expired' });
    });

    it('says "cancelled" for the courier who pressed cancel, and "provider error" for anything else', async () => {
      const { pending } = await started();

      await expect(
        client.completeLogin(new URLSearchParams({ error: 'access_denied' }), pending),
      ).rejects.toMatchObject({ code: 'access_denied' });
      await expect(
        client.completeLogin(new URLSearchParams({ error: 'server_error' }), pending),
      ).rejects.toMatchObject({ code: 'provider_error' });
    });

    it('refuses a callback from a different issuer (RFC 9207)', async () => {
      const { pending } = await started();

      await expect(
        client.completeLogin(
          new URLSearchParams({
            code: 'code-1',
            state: pending.state,
            iss: 'https://auth.evil.test/realms/horecaos',
          }),
          pending,
        ),
      ).rejects.toMatchObject({ code: 'issuer_mismatch' });
    });

    it('refuses an identity token minted for another login', async () => {
      const { pending } = await started();
      harness.provider.idTokenClaims = { nonce: 'somebody-elses' };

      await expect(
        client.completeLogin(
          new URLSearchParams({ code: 'code-1', state: pending.state }),
          pending,
        ),
      ).rejects.toMatchObject({ code: 'state_mismatch' });
    });

    it('refuses an identity token for another client, another issuer, or already expired', async () => {
      for (const [claims, code] of [
        [{ aud: 'horecaos-operations' }, 'token_invalid'],
        [{ iss: 'https://auth.evil.test/realms/horecaos' }, 'issuer_mismatch'],
        [{ exp: Math.floor(Date.now() / 1000) - 10 }, 'token_invalid'],
        [{ sub: '' }, 'token_invalid'],
      ] as const) {
        const { pending } = await started();
        harness.provider.idTokenClaims = claims;

        await expect(
          client.completeLogin(
            new URLSearchParams({
              code: `code-${code}-${Object.keys(claims)[0]}`,
              state: pending.state,
            }),
            pending,
          ),
        ).rejects.toMatchObject({ code });
      }
    });

    it('does not let a code be redeemed twice', async () => {
      const { pending } = await started();
      const callback = new URLSearchParams({ code: 'code-1', state: pending.state });
      await client.completeLogin(callback, pending);

      await expect(client.completeLogin(callback, pending)).rejects.toMatchObject({
        code: 'token_exchange_failed',
      });
    });

    it('reports a refusing or unreachable token endpoint as a failed exchange without echoing its words', async () => {
      const { pending } = await started();
      harness.provider.refuseWith = 'invalid_grant';

      const failure = await client
        .completeLogin(new URLSearchParams({ code: 'code-1', state: pending.state }), pending)
        .catch((caught: unknown) => caught);

      expect(failure).toMatchObject({ code: 'token_exchange_failed' });
      expect((failure as OidcError).message).not.toContain('code-1');
      expect((failure as OidcError).message).not.toContain(pending.verifier);
    });
  });

  describe('refreshing', () => {
    it('trades a refresh token for a new set, once', async () => {
      const { pending } = await started();
      const { tokens } = await client.completeLogin(
        new URLSearchParams({ code: 'code-1', state: pending.state }),
        pending,
      );

      const renewed = await client.refresh(tokens.refreshToken!);

      expect(renewed.accessToken).toBe('access-2');
      await expect(client.refresh(tokens.refreshToken!)).rejects.toMatchObject({
        code: 'refresh_failed',
      });
    });

    describe('telling a refusal from a token endpoint that could not answer', () => {
      async function signedInTokens() {
        const { pending } = await started();
        const { tokens } = await client.completeLogin(
          new URLSearchParams({ code: 'code-1', state: pending.state }),
          pending,
        );
        return tokens;
      }

      it('calls a refusal of the refresh token a refusal: the session it belonged to is over', async () => {
        const tokens = await signedInTokens();
        harness.provider.refuseWith = 'invalid_grant';

        await expect(client.refresh(tokens.refreshToken!)).rejects.toMatchObject({
          code: 'refresh_failed',
        });
      });

      it('calls a client error without an OAuth body a refusal too', async () => {
        const tokens = await signedInTokens();
        harness.provider.failNextTokenRequestWith = 401;

        await expect(client.refresh(tokens.refreshToken!)).rejects.toMatchObject({
          code: 'refresh_failed',
        });
      });

      it('calls a fetch that never reached the endpoint unavailable, not a refusal', async () => {
        const tokens = await signedInTokens();
        harness.provider.tokenEndpointUnreachable = true;

        await expect(client.refresh(tokens.refreshToken!)).rejects.toMatchObject({
          code: 'refresh_unavailable',
        });
      });

      it.each([500, 502, 503, 504, 408, 429])(
        'calls a %s from the token endpoint unavailable: the refresh token was not refused',
        async (status) => {
          const tokens = await signedInTokens();
          harness.provider.failNextTokenRequestWith = status;

          await expect(client.refresh(tokens.refreshToken!)).rejects.toMatchObject({
            code: 'refresh_unavailable',
          });
        },
      );

      it('still reports the same trouble at the first sign-in as a failed exchange: there is nothing to keep', async () => {
        const { pending } = await started();
        harness.provider.tokenEndpointUnreachable = true;

        await expect(
          client.completeLogin(
            new URLSearchParams({ code: 'code-1', state: pending.state }),
            pending,
          ),
        ).rejects.toMatchObject({ code: 'token_exchange_failed' });
      });
    });
  });

  describe('ending the session', () => {
    it('names the redirect target and hints with the identity token', async () => {
      const url = new URL((await client.endSessionUrl('id.token.value'))!);

      expect(url.origin + url.pathname).toBe(
        `${TEST_CONFIG.issuerUrl}/protocol/openid-connect/logout`,
      );
      expect(url.searchParams.get('post_logout_redirect_uri')).toBe(`${ORIGIN}/auth/signed-out`);
      expect(url.searchParams.get('id_token_hint')).toBe('id.token.value');
      expect(url.searchParams.get('client_id')).toBe('horecaos-courier');
    });
  });
});
