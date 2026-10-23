/**
 * What `GET /api/v1/courier/tenants/{tenantId}/me` returns: the courier's own record, found from
 * their token subject within the tenant (`courier` OpenAPI group, `CourierSelfController`).
 *
 * Hand-written like every app's DTOs here (the generated clients are not consumed yet); the
 * platform's `CourierSelfEndpointTests` reads the same field names off the real HTTP answer, so a
 * rename on either side fails a build. The telemetry cadence is not here: the platform returns it
 * with the duty session and on every batch, where the sender needs it.
 */
export type EngagementStatus =
  'PENDING_VERIFICATION' | 'ACTIVE' | 'SUSPENDED_COMPLIANCE' | 'SUSPENDED_OPERATIONAL' | 'ENDED';

export type RegistrationWarning = 'VALID' | 'EXPIRING' | 'LAPSED';

export type DutyState = 'AVAILABLE' | 'ON_BREAK' | 'AT_CAPACITY' | 'UNREACHABLE';

export interface CourierEngagement {
  readonly status: EngagementStatus;
  readonly warningState: RegistrationWarning;
  /** An ISO date (`2027-01-31`), or null before the registration has been checked. */
  readonly registrationValidUntil: string | null;
  /** Whether dispatch may offer this courier work at all. */
  readonly dispatchable: boolean;
}

export interface CourierBranch {
  readonly brandId: string;
  readonly locationId: string;
  readonly displayName: string;
  readonly primary: boolean;
}

export interface CourierOpenShift {
  readonly shiftId: string;
  readonly brandId: string;
  readonly locationId: string;
  /** Null when the branch is no longer one of the courier's bindings. */
  readonly locationName: string | null;
  readonly status: string;
  readonly dutyState: DutyState;
  readonly enforcementMode: 'ENFORCED' | 'ADVISORY' | 'OFF';
  /** An ISO instant. */
  readonly openedAt: string;
  readonly version: number;
}

export interface CourierProfile {
  readonly courierId: string;
  /** The tenant's own label for this courier. Not a name: the legal name stays protected on the platform. */
  readonly displayReference: string;
  readonly status: string;
  readonly engagement: CourierEngagement | null;
  readonly branches: readonly CourierBranch[];
  readonly openShift: CourierOpenShift | null;
}
