import { Injectable, InjectionToken, inject } from '@angular/core';

import { APP_CONFIG } from '../config/app-config';
import { codeChallengeS256, decodeJwtPayload, newCodeVerifier, randomToken } from './pkce';

/**
 * The `fetch` the OIDC calls use.
 *
 * Plain `fetch` rather than `HttpClient`: nothing here may pick up the platform's interceptors
 * (a bearer token must never be sent to Keycloak, and a custom header turns the discovery request
 * into a preflighted one the identity provider does not answer), and a token endpoint that does
 * not go through them cannot be given one by accident.
 */
export const OIDC_FETCH = new InjectionToken<typeof fetch>('OIDC_FETCH', {
  providedIn: 'root',
  factory: () => globalThis.fetch.bind(globalThis),
});

/** Where this page lives and how to leave it, so a spec can observe a redirect instead of performing one. */
export interface BrowserNavigation {
  readonly origin: string;
  assign(url: string): void;
}

export const BROWSER_NAVIGATION = new InjectionToken<BrowserNavigation>('BROWSER_NAVIGATION', {
  providedIn: 'root',
  factory: () => ({
    origin: globalThis.location.origin,
    assign: (url: string) => globalThis.location.assign(url),
  }),
});

/** Milliseconds since the epoch. Injected so an expiry is something a spec can advance. */
export const AUTH_CLOCK = new InjectionToken<() => number>('AUTH_CLOCK', {
  providedIn: 'root',
  factory: () => () => Date.now(),
});

export const CALLBACK_PATH = '/auth/callback';
export const SIGNED_OUT_PATH = '/auth/signed-out';
const SCOPE = 'openid profile';

/** A login the browser left to go and complete at the identity provider. */
export interface PendingLogin {
  readonly state: string;
  readonly nonce: string;
  readonly verifier: string;
  readonly returnTo: string;
  readonly createdAt: number;
}

/** What the token endpoint gave, with the two lifetimes turned into instants. */
export interface Tokens {
  readonly accessToken: string;
  readonly refreshToken: string | null;
  readonly idToken: string | null;
  readonly accessExpiresAt: number;
  /** Null when the provider did not say; the refresh is then simply tried. */
  readonly refreshExpiresAt: number | null;
}

/** The claims the app reads. Nothing else of the identity token is kept. */
export interface IdentityClaims {
  readonly subject: string;
  readonly name: string | null;
  readonly username: string | null;
}

export type OidcErrorCode =
  | 'discovery_failed'
  | 'issuer_mismatch'
  | 'access_denied'
  | 'provider_error'
  | 'no_pending_login'
  | 'login_expired'
  | 'state_mismatch'
  | 'token_exchange_failed'
  | 'token_invalid'
  | 'refresh_failed'
  | 'storage_unavailable';

/**
 * A sign-in that did not complete. The `code` is the vocabulary the screen branches on; the
 * detail is for a developer and is never shown, and never contains a token or a code.
 */
export class OidcError extends Error {
  constructor(
    readonly code: OidcErrorCode,
    detail: string,
  ) {
    super(`${code}: ${detail}`);
    this.name = 'OidcError';
  }
}

interface ProviderMetadata {
  readonly issuer: string;
  readonly authorizationEndpoint: string;
  readonly tokenEndpoint: string;
  readonly endSessionEndpoint: string | null;
}

/** How long a login may take at the identity provider before its stored verifier is refused. */
export const LOGIN_TTL_MS = 10 * 60 * 1000;

/**
 * Authorization Code + PKCE against one Keycloak realm, as a public client.
 *
 * Standards-based on purpose (AGENTS.md): the endpoints come from the OIDC discovery document and
 * its `issuer` must equal the configured one, `state` binds the callback to the request that
 * started it, `nonce` binds the identity token to it, PKCE binds the code to this browser, and an
 * `iss` parameter on the callback (RFC 9207) must match when present. There is no client secret
 * and nowhere to put one.
 *
 * The identity token's signature is not verified here. OIDC Core §3.1.3.7 permits that when the
 * token came directly from the token endpoint over TLS, which is the only way one reaches this
 * class; the claims are still checked (issuer, audience, nonce, expiry) so a wrong-realm or
 * replayed token is refused. The access token is opaque to the app and is verified by the
 * platform on every call.
 */
@Injectable({ providedIn: 'root' })
export class OidcClient {
  private readonly config = inject(APP_CONFIG);
  private readonly doFetch = inject(OIDC_FETCH);
  private readonly navigation = inject(BROWSER_NAVIGATION);
  private readonly now = inject(AUTH_CLOCK);

  private metadataPromise: Promise<ProviderMetadata> | null = null;

  get redirectUri(): string {
    return this.navigation.origin + CALLBACK_PATH;
  }

  get postLogoutRedirectUri(): string {
    return this.navigation.origin + SIGNED_OUT_PATH;
  }

  /** The URL to send the browser to, and the verifier it must remember to come back with. */
  async startLogin(
    returnTo: string,
    uiLocale: string,
  ): Promise<{ url: string; pending: PendingLogin }> {
    const metadata = await this.metadata();
    const pending: PendingLogin = {
      state: randomToken(32),
      nonce: randomToken(32),
      verifier: newCodeVerifier(),
      returnTo,
      createdAt: this.now(),
    };

    const url = new URL(metadata.authorizationEndpoint);
    url.searchParams.set('client_id', this.config.clientId);
    url.searchParams.set('redirect_uri', this.redirectUri);
    url.searchParams.set('response_type', 'code');
    url.searchParams.set('scope', SCOPE);
    url.searchParams.set('state', pending.state);
    url.searchParams.set('nonce', pending.nonce);
    url.searchParams.set('code_challenge', await codeChallengeS256(pending.verifier));
    url.searchParams.set('code_challenge_method', 'S256');
    url.searchParams.set('ui_locales', uiLocale);
    return { url: url.toString(), pending };
  }

  /**
   * Redeems the callback for tokens.
   *
   * @param callback the query of `/auth/callback`
   * @param pending what {@link startLogin} stored before leaving
   */
  async completeLogin(
    callback: URLSearchParams,
    pending: PendingLogin | null,
  ): Promise<{ tokens: Tokens; claims: IdentityClaims }> {
    const error = callback.get('error');
    if (error !== null) {
      // The provider's own refusal -- a courier who pressed "cancel" arrives here. No code in it.
      throw new OidcError(
        error === 'access_denied' ? 'access_denied' : 'provider_error',
        `the provider answered ${error}`,
      );
    }
    if (pending === null) {
      throw new OidcError('no_pending_login', 'no login was started from this browser');
    }
    if (this.now() - pending.createdAt > LOGIN_TTL_MS) {
      throw new OidcError('login_expired', 'the login was started too long ago');
    }
    if (callback.get('state') !== pending.state) {
      throw new OidcError(
        'state_mismatch',
        'the callback does not answer the login that was started',
      );
    }
    const issuerParameter = callback.get('iss');
    if (issuerParameter !== null && issuerParameter !== this.config.issuerUrl) {
      throw new OidcError('issuer_mismatch', 'the callback names a different issuer');
    }
    const code = callback.get('code');
    if (code === null || code === '') {
      throw new OidcError('provider_error', 'the callback carries no code');
    }

    const metadata = await this.metadata();
    const received = await this.tokenRequest(
      metadata,
      new URLSearchParams({
        grant_type: 'authorization_code',
        code,
        redirect_uri: this.redirectUri,
        client_id: this.config.clientId,
        code_verifier: pending.verifier,
      }),
      'token_exchange_failed',
    );

    if (received.idToken === null) {
      throw new OidcError('token_invalid', 'the provider returned no identity token');
    }
    const claims = this.readIdentity(received.idToken, pending.nonce);
    return { tokens: received, claims };
  }

  /** Trades a refresh token for a new set. A refusal means the session is over, not that it should be retried. */
  async refresh(refreshToken: string): Promise<Tokens> {
    const metadata = await this.metadata();
    return this.tokenRequest(
      metadata,
      new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: refreshToken,
        client_id: this.config.clientId,
      }),
      'refresh_failed',
    );
  }

  /** The URL that ends the session at the identity provider, or null when it publishes none. */
  async endSessionUrl(idToken: string | null): Promise<string | null> {
    const metadata = await this.metadata();
    if (metadata.endSessionEndpoint === null) {
      return null;
    }
    const url = new URL(metadata.endSessionEndpoint);
    url.searchParams.set('client_id', this.config.clientId);
    url.searchParams.set('post_logout_redirect_uri', this.postLogoutRedirectUri);
    if (idToken !== null) {
      url.searchParams.set('id_token_hint', idToken);
    }
    return url.toString();
  }

  private metadata(): Promise<ProviderMetadata> {
    this.metadataPromise ??= this.loadMetadata().catch((failure: unknown) => {
      // A failed discovery must not be remembered: the next attempt is a fresh one.
      this.metadataPromise = null;
      throw failure;
    });
    return this.metadataPromise;
  }

  private async loadMetadata(): Promise<ProviderMetadata> {
    let body: unknown;
    try {
      const response = await this.doFetch(
        `${this.config.issuerUrl}/.well-known/openid-configuration`,
        {
          headers: { Accept: 'application/json' },
        },
      );
      if (!response.ok) {
        throw new Error(`answered ${response.status}`);
      }
      body = await response.json();
    } catch (cause) {
      throw new OidcError('discovery_failed', cause instanceof Error ? cause.message : 'failed');
    }

    const raw = (typeof body === 'object' && body !== null ? body : {}) as Record<string, unknown>;
    if (raw['issuer'] !== this.config.issuerUrl) {
      throw new OidcError('issuer_mismatch', 'the discovery document names a different issuer');
    }
    const authorizationEndpoint = this.sameOriginEndpoint(raw['authorization_endpoint']);
    const tokenEndpoint = this.sameOriginEndpoint(raw['token_endpoint']);
    const endSession = raw['end_session_endpoint'];
    return {
      issuer: this.config.issuerUrl,
      authorizationEndpoint,
      tokenEndpoint,
      endSessionEndpoint: endSession === undefined ? null : this.sameOriginEndpoint(endSession),
    };
  }

  /**
   * An endpoint the discovery document names must be on the issuer's own origin. Tokens and the
   * browser are about to be sent there; a document that points them elsewhere is refused.
   */
  private sameOriginEndpoint(value: unknown): string {
    if (typeof value !== 'string') {
      throw new OidcError('discovery_failed', 'the discovery document lacks an endpoint');
    }
    let endpoint: URL;
    try {
      endpoint = new URL(value);
    } catch {
      throw new OidcError('discovery_failed', 'an endpoint is not a URL');
    }
    if (endpoint.origin !== new URL(this.config.issuerUrl).origin) {
      throw new OidcError('issuer_mismatch', 'an endpoint is not on the issuer origin');
    }
    return value;
  }

  private async tokenRequest(
    metadata: ProviderMetadata,
    form: URLSearchParams,
    failure: 'token_exchange_failed' | 'refresh_failed',
  ): Promise<Tokens> {
    let body: unknown;
    try {
      const response = await this.doFetch(metadata.tokenEndpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          Accept: 'application/json',
        },
        body: form.toString(),
      });
      body = await response.json().catch(() => null);
      if (!response.ok) {
        // `error` is a short code from RFC 6749 §5.2 ("invalid_grant"); the description is not read.
        const code =
          typeof body === 'object' &&
          body !== null &&
          typeof (body as { error?: unknown }).error === 'string'
            ? (body as { error: string }).error
            : String(response.status);
        throw new OidcError(failure, `the token endpoint answered ${code}`);
      }
    } catch (cause) {
      if (cause instanceof OidcError) {
        throw cause;
      }
      throw new OidcError(failure, 'the token endpoint could not be reached');
    }

    const raw = (typeof body === 'object' && body !== null ? body : {}) as Record<string, unknown>;
    const accessToken = raw['access_token'];
    const tokenType = raw['token_type'];
    const expiresIn = raw['expires_in'];
    if (
      typeof accessToken !== 'string' ||
      accessToken === '' ||
      typeof tokenType !== 'string' ||
      tokenType.toLowerCase() !== 'bearer' ||
      typeof expiresIn !== 'number'
    ) {
      throw new OidcError('token_invalid', 'the token response is not a bearer token');
    }

    const now = this.now();
    const refreshExpiresIn = raw['refresh_expires_in'];
    return {
      accessToken,
      refreshToken: typeof raw['refresh_token'] === 'string' ? raw['refresh_token'] : null,
      idToken: typeof raw['id_token'] === 'string' ? raw['id_token'] : null,
      accessExpiresAt: now + expiresIn * 1000,
      // Keycloak reports 0 for a refresh token that never expires on its own.
      refreshExpiresAt:
        typeof refreshExpiresIn === 'number' && refreshExpiresIn > 0
          ? now + refreshExpiresIn * 1000
          : null,
    };
  }

  private readIdentity(idToken: string, expectedNonce: string): IdentityClaims {
    let payload: Record<string, unknown>;
    try {
      payload = decodeJwtPayload(idToken);
    } catch {
      throw new OidcError('token_invalid', 'the identity token cannot be read');
    }

    const audience = payload['aud'];
    const audiences = Array.isArray(audience) ? audience : [audience];
    const exp = payload['exp'];
    if (payload['iss'] !== this.config.issuerUrl) {
      throw new OidcError('issuer_mismatch', 'the identity token was issued by another issuer');
    }
    if (!audiences.includes(this.config.clientId)) {
      throw new OidcError('token_invalid', 'the identity token is for another client');
    }
    if (audiences.length > 1 && payload['azp'] !== this.config.clientId) {
      throw new OidcError('token_invalid', 'the identity token was issued to another party');
    }
    if (payload['nonce'] !== expectedNonce) {
      throw new OidcError('state_mismatch', 'the identity token answers another login');
    }
    if (typeof exp !== 'number' || exp * 1000 <= this.now()) {
      throw new OidcError('token_invalid', 'the identity token has expired');
    }
    const subject = payload['sub'];
    if (typeof subject !== 'string' || subject === '') {
      throw new OidcError('token_invalid', 'the identity token has no subject');
    }
    return {
      subject,
      name: typeof payload['name'] === 'string' ? payload['name'] : null,
      username:
        typeof payload['preferred_username'] === 'string' ? payload['preferred_username'] : null,
    };
  }
}
