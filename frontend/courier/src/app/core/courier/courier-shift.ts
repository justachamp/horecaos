import type { DutyState } from './courier-profile';

/**
 * A branch a call is made at. The courier surface keeps brand and location in the path (ADR 0031),
 * and the courier's own record (`GET .../me`) is where both come from -- never typed in.
 */
export interface BranchRef {
  readonly brandId: string;
  readonly locationId: string;
}

/** What `POST .../shifts` answers (`CourierShiftController.ShiftResponse`). */
export interface OpenedShift {
  readonly shiftId: string;
  readonly status: string;
  readonly dutyState: DutyState;
  readonly enforcementMode: 'ENFORCED' | 'ADVISORY' | 'OFF';
  readonly version: number;
}

/** What `POST .../shifts/{id}/close` answers: the paid and unpaid seconds, and the cash to hand over. */
export interface ClosedShift {
  readonly status: string;
  readonly paidSeconds: number;
  readonly breakSeconds: number;
  /** Null when the shift took no cash, so no handover opened. */
  readonly cashHandoverId: string | null;
}
