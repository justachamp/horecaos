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
  /**
   * ADR 0148: the account holds no second factor and the platform rule is in its `PROMPT`
   * phase — the session is real, and the console offers enrolment without requiring it.
   * Absent from an older server, which means "not offered".
   */
  readonly mfaEnrolmentOffered?: boolean;
}

export interface StaffSignInRequest {
  readonly username: string;
  readonly password: string;
  /**
   * ADR 0148: the six-digit code from an authenticator app, for an account that holds one. Left
   * out of the first attempt: the platform answers `MFA_REQUIRED` and the page asks for it.
   */
  readonly otp?: string;
}

export interface StaffRefreshRequest {
  readonly refreshToken: string;
}

export interface StaffLogoutRequest {
  readonly refreshToken: string;
}
