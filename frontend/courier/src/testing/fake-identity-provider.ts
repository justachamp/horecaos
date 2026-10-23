import { base64Url, codeChallengeS256 } from '../app/core/auth/pkce';
import { TEST_CONFIG } from './fixtures';

const encode = (value: unknown) => base64Url(new TextEncoder().encode(JSON.stringify(value)));

export interface RecordedRequest {
  readonly url: string;
  readonly method: string;
  readonly form: URLSearchParams | null;
}

/**
 * Keycloak, as far as the courier app can tell: the discovery document and a token endpoint that
 * does what a real one does with PKCE -- it remembers the challenge from the authorization URL and
 * refuses a code redemption whose verifier does not hash to it.
 *
 * A fake, not a mock: it enforces the protocol, so a client that sent the wrong verifier, the
 * wrong redirect URI or a replayed code fails here the way it would against the realm.
 */
export class FakeIdentityProvider {
  readonly issuer = TEST_CONFIG.issuerUrl;
  readonly requests: RecordedRequest[] = [];

  /** The access token lifetime the next token response reports. */
  accessTokenSeconds = 300;
  /** When set, the next token request is refused with this OAuth error. */
  refuseWith: string | null = null;
  /** Overrides applied to the identity token's claims. */
  idTokenClaims: Record<string, unknown> = {};
  /** The `iss` the discovery document claims. */
  discoveryIssuer = this.issuer;
  /** Where the discovery document says the endpoints are; a hostile document names another origin. */
  endpointBase = this.issuer;

  private challenge: string | null = null;
  private nonce: string | null = null;
  private issued = 0;
  private spentCodes = new Set<string>();
  private liveRefreshTokens = new Set<string>();

  /** Read the challenge and nonce off the authorization URL the app navigated to. */
  rememberAuthorization(authorizationUrl: string): { state: string } {
    const url = new URL(authorizationUrl);
    this.challenge = url.searchParams.get('code_challenge');
    this.nonce = url.searchParams.get('nonce');
    return { state: url.searchParams.get('state') ?? '' };
  }

  readonly fetch: typeof fetch = async (input, init) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const method = init?.method ?? 'GET';
    const body = typeof init?.body === 'string' ? new URLSearchParams(init.body) : null;
    this.requests.push({ url, method, form: body });

    if (url.endsWith('/.well-known/openid-configuration')) {
      return json({
        issuer: this.discoveryIssuer,
        authorization_endpoint: `${this.endpointBase}/protocol/openid-connect/auth`,
        token_endpoint: `${this.endpointBase}/protocol/openid-connect/token`,
        end_session_endpoint: `${this.endpointBase}/protocol/openid-connect/logout`,
      });
    }
    if (url.endsWith('/protocol/openid-connect/token') && body !== null) {
      return this.token(body);
    }
    return new Response('not found', { status: 404 });
  };

  tokenRequests(): RecordedRequest[] {
    return this.requests.filter((request) => request.url.endsWith('/token'));
  }

  private async token(form: URLSearchParams): Promise<Response> {
    if (this.refuseWith !== null) {
      const error = this.refuseWith;
      this.refuseWith = null;
      return json({ error }, 400);
    }
    if (form.get('client_id') !== TEST_CONFIG.clientId) {
      return json({ error: 'invalid_client' }, 401);
    }

    if (form.get('grant_type') === 'authorization_code') {
      const code = form.get('code') ?? '';
      const verifier = form.get('code_verifier') ?? '';
      if (this.spentCodes.has(code)) {
        return json({ error: 'invalid_grant' }, 400);
      }
      this.spentCodes.add(code);
      if (this.challenge === null || (await codeChallengeS256(verifier)) !== this.challenge) {
        return json({ error: 'invalid_grant' }, 400);
      }
      return json(this.tokenResponse());
    }

    if (form.get('grant_type') === 'refresh_token') {
      const token = form.get('refresh_token') ?? '';
      if (!this.liveRefreshTokens.delete(token)) {
        // A refresh token is single-use: the second redemption of a spent one ends the session.
        return json({ error: 'invalid_grant' }, 400);
      }
      return json(this.tokenResponse());
    }
    return json({ error: 'unsupported_grant_type' }, 400);
  }

  private tokenResponse() {
    this.issued += 1;
    const refreshToken = `refresh-${this.issued}`;
    this.liveRefreshTokens.add(refreshToken);
    const now = Math.floor(Date.now() / 1000);
    const claims = {
      iss: this.issuer,
      aud: TEST_CONFIG.clientId,
      sub: 'keycloak-alisher',
      name: 'Alisher Karimov',
      preferred_username: 'alisher',
      nonce: this.nonce,
      exp: now + 300,
      ...this.idTokenClaims,
    };
    return {
      access_token: `access-${this.issued}`,
      token_type: 'Bearer',
      expires_in: this.accessTokenSeconds,
      refresh_token: refreshToken,
      refresh_expires_in: 1800,
      id_token: `${encode({ alg: 'RS256' })}.${encode(claims)}.signature`,
    };
  }
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
