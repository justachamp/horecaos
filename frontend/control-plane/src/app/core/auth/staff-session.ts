/**
 * The wire shape of `POST/DELETE /api/v1/control-plane/auth/sessions*` (ADR
 * 0062). Mirrors `uz.horecaos.platform.iam.web.StaffSessionController`'s
 * `StaffSessionResponse`, the way `capability.ts` mirrors `CapabilityView`.
 *
 * `refreshTokenExpiresAt` is absent, not a past instant, when the refresh
 * token has no fixed expiry to report — verified live against the dev realm,
 * that is what Keycloak answers for the offline-scoped refresh token this
 * endpoint requests, and it is what keeps this console's session alive across
 * more than an access-token lifetime without asking anyone to type a password
 * again every five minutes.
 */
export interface StaffSessionResponse {
  readonly accessToken: string;
  readonly refreshToken: string;
  readonly accessTokenExpiresAt: string;
  readonly refreshTokenExpiresAt?: string;
  readonly tokenType: string;
  /**
   * ADR 0148: the account holds no second factor and the platform rule is in its `PROMPT` phase.
   * Absent from an older server, which means "not offered".
   */
  readonly mfaEnrolmentOffered?: boolean;
}

export interface StaffSignInRequest {
  readonly username: string;
  readonly password: string;
  /** ADR 0148: the six-digit code from an authenticator app; left out of a first attempt. */
  readonly otp?: string;
}

export interface StaffRefreshRequest {
  readonly refreshToken: string;
}

export interface StaffLogoutRequest {
  readonly refreshToken: string;
}
