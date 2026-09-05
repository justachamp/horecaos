/**
 * What the login screen hands the code screen.
 *
 * The legacy `LoginRequest`/`LoginResponse`/`VerifyOtpRequest`/`VerifyOtpResponse`
 * shapes are gone with the endpoints that served them. The platform's identity
 * flow is three calls, and their types live with the client that makes them
 * (`core/session/customer-otp.ts`); what remains here is only the state that
 * travels between two screens.
 */
export interface AuthCodeState {
  /** Canonical E.164. The code screen re-sends it on a resend. */
  phone: string;
  /** The challenge to answer. Replaced when the customer asks for a new code. */
  challengeId: string;
  /** How many digits the platform generated. The UI renders six today. */
  codeLength?: number;
  attemptsAllowed?: number;
  /** ISO-8601. The window is minutes, not hours. */
  expiresAt?: string;
}
