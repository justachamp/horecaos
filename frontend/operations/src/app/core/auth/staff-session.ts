/**
 * The wire shape of `POST/DELETE /api/v1/operations/auth/sessions*` (ADR
 * 0062). Mirrors `uz.horecaos.platform.iam.web.StaffSessionController`'s
 * `StaffSessionResponse`.
 *
 * `refreshTokenExpiresAt` is absent, not a past instant, when the refresh
 * token has no fixed expiry to report — verified live against the dev realm,
 * that is what Keycloak answers for the offline-scoped refresh token this
 * endpoint requests.
 */
export interface StaffSessionResponse {
  readonly accessToken: string;
  readonly refreshToken: string;
  readonly accessTokenExpiresAt: string;
  readonly refreshTokenExpiresAt?: string;
  readonly tokenType: string;
}

export interface StaffSignInRequest {
  readonly username: string;
  readonly password: string;
}

export interface StaffRefreshRequest {
  readonly refreshToken: string;
}

export interface StaffLogoutRequest {
  readonly refreshToken: string;
}
