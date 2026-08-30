import { Injectable } from '@angular/core';

/**
 * Where the bearer token comes from, as far as the HTTP layer is concerned.
 *
 * A seam, so the interceptor does not depend on `OAuthService`: it lets a test
 * assert what the client sends without standing up an OIDC library, and it
 * keeps the one place tokens are read small enough to audit.
 */
@Injectable()
export abstract class AccessTokenSource {
  /** The current access token, or null when there is none. */
  abstract accessToken(): string | null;
}
