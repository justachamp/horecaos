/**
 * The courier's own duty session and the observation batch (ADR 0045, ADR 0179).
 *
 * The cadence is the platform's lever and not the device's: it arrives with the session and on
 * every batch answer, and the sender obeys whatever it last heard.
 */
export type DutySessionStatus = 'OPEN' | 'SUSPENDED' | 'CLOSED';

export interface DutySession {
  readonly sessionId: string;
  readonly shiftId: string;
  readonly locationId: string;
  readonly status: DutySessionStatus;
  readonly collectionGate: 'ON_DUTY' | 'ON_ASSIGNMENT';
  /** An ISO instant. */
  readonly startedAt: string;
  /** An ISO date: the self-employment registration the session was opened under. */
  readonly registrationValidUntil: string;
  readonly batchCadenceSeconds: number;
  readonly maximumBatchSize: number;
}

/** `GET .../duty-session`: a null session is "not on duty here", not an error. */
export interface CurrentDuty {
  readonly session: DutySession | null;
}

/**
 * One reading as the platform's batch endpoint takes it. A position, so it is held in memory only,
 * is never logged, and never put in a URL (ADR 0029).
 */
export interface Observation {
  /** An ISO instant. */
  readonly capturedAt: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly accuracyMeters: number;
  readonly headingDegrees: number | null;
  readonly speedMps: number | null;
  readonly batteryPercent: number | null;
  readonly deviceCharging: boolean | null;
}

export interface ObservationBatchRequest {
  readonly activeAssignmentCount: number;
  readonly observations: readonly Observation[];
}

export interface ObservationBatchResult {
  readonly observationsAccepted: number;
  readonly windowsWritten: number;
  readonly livePositionMoved: boolean;
  readonly collectionGate: string;
  /** True while a break runs: the platform kept nothing, and the indicator must say so. */
  readonly suspended: boolean;
  readonly nextBatchAfterSeconds: number;
}

/** What `POST .../telemetry-notice-acknowledgements` answers: the version the platform recorded and when. */
export interface NoticeAcknowledgement {
  readonly version: string;
  /** An ISO instant, the platform's clock. */
  readonly acknowledgedAt: string;
}
