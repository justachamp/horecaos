import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';

/** The closed list of reasons for hours a manager authors, corrects, approves or voids (`ReasonCode`). */
export const SHIFT_REASONS = [
  'FORGOT_CLOCK_IN',
  'FORGOT_CLOCK_OUT',
  'SYSTEM_PROBLEM',
  'PLAN_CHANGED',
  'SHIFT_SWAP',
  'COVERED_SHORTAGE',
  'ENTERED_IN_ERROR',
  'DUPLICATE',
  'DID_NOT_WORK',
  'WORKED_AS_RECORDED',
  'PERSON_UNAVAILABLE',
  'SCHEDULE_CHANGED',
] as const;
export type ShiftReason = (typeof SHIFT_REASONS)[number];

/** The subset a manager's own entry or correction can give (not an approval's or a cancellation's). */
export const ENTRY_REASONS: readonly ShiftReason[] = [
  'FORGOT_CLOCK_IN',
  'FORGOT_CLOCK_OUT',
  'SYSTEM_PROBLEM',
  'PLAN_CHANGED',
  'SHIFT_SWAP',
  'COVERED_SHORTAGE',
];
export const VOID_REASONS: readonly ShiftReason[] = [
  'ENTERED_IN_ERROR',
  'DUPLICATE',
  'DID_NOT_WORK',
];
export const APPROVAL_REASONS: readonly ShiftReason[] = [
  'WORKED_AS_RECORDED',
  'PLAN_CHANGED',
  'COVERED_SHORTAGE',
  'SHIFT_SWAP',
];
export const CANCEL_REASONS: readonly ShiftReason[] = [
  'PERSON_UNAVAILABLE',
  'SCHEDULE_CHANGED',
  'ENTERED_IN_ERROR',
];

export type RosterStatus = 'DRAFT' | 'PUBLISHED' | 'CONSUMED' | 'MISSED' | 'CANCELLED';
export type ShiftStatus = 'OPEN' | 'AWAITING_APPROVAL' | 'CLOSED' | 'VOIDED';
export type TodayStatus =
  | 'LATE'
  | 'MISSED_TODAY'
  | 'WORKING_WITHOUT_SHIFT'
  | 'OPEN_TOO_LONG'
  | 'WORKING'
  | 'CLOSED'
  | 'SCHEDULED';

/** A person as a rota shows them: the non-personal reference, and a name only for a caller who may see names. */
export interface ShiftPerson {
  readonly memberId: string;
  readonly displayReference: string;
  readonly name: string | null;
}

export interface RosterEntry {
  readonly id: string;
  readonly locationId: string;
  readonly person: ShiftPerson | null;
  readonly status: RosterStatus;
  readonly plannedStart: string;
  readonly plannedEnd: string;
  readonly plannedBreakMinutes: number;
  readonly publishedAt: string | null;
  readonly version: number;
}

export interface RosterWeek {
  readonly timezone: string;
  readonly from: string;
  readonly to: string;
  readonly entries: readonly RosterEntry[];
}

export interface StaffShift {
  readonly id: string;
  readonly locationId: string;
  readonly person: ShiftPerson | null;
  readonly status: ShiftStatus;
  readonly startedAt: string;
  readonly endedAt: string | null;
  readonly openSource: string;
  readonly closeSource: string | null;
  readonly breakSeconds: number;
  readonly workedSeconds: number | null;
  readonly paidSeconds: number | null;
  readonly plannedSeconds: number | null;
  readonly varianceSeconds: number | null;
  readonly varianceThresholdMinutes: number | null;
  readonly businessDate: string;
  readonly hadRosterEntry: boolean;
  readonly editReasonCode: string | null;
  readonly approvedAt: string | null;
  readonly voidReasonCode: string | null;
  readonly version: number;
}

/** What a change to a shift needs of it: which one, and the version it was read at (If-Match). */
export type ShiftRef = Pick<StaffShift, 'id' | 'version'>;

export interface ShiftPage {
  readonly items: readonly StaffShift[];
  readonly nextCursor: string | null;
}

export interface TodayRow {
  readonly person: ShiftPerson | null;
  readonly status: TodayStatus;
  readonly plannedStart: string | null;
  readonly plannedEnd: string | null;
  readonly shiftId: string | null;
  readonly startedAt: string | null;
  readonly endedAt: string | null;
  readonly shiftStatus: ShiftStatus | null;
  readonly shiftVersion: number | null;
}

export interface TodayList {
  readonly businessDate: string;
  readonly from: string;
  readonly to: string;
  readonly asOf: string;
  /** Always true: «Работает без смены» sees only people who take orders, and the screen says so. */
  readonly ordersOnly: boolean;
  readonly rows: readonly TodayRow[];
}

export interface MyAttendance {
  readonly open: StaffShift | null;
  readonly upcoming: readonly {
    readonly id: string;
    readonly locationId: string;
    readonly plannedStart: string;
    readonly plannedEnd: string;
    readonly plannedBreakMinutes: number;
  }[];
  readonly recent: readonly StaffShift[];
}

export interface EntryDraft {
  readonly memberId: string;
  readonly plannedStart: string;
  readonly plannedEnd: string;
  readonly plannedBreakMinutes: number;
}

/**
 * The wire calls of the rota and the timesheet (ADR 0156). Every write carries an `Idempotency-Key`,
 * every change of an existing row the version it was read at.
 */
@Injectable({ providedIn: 'root' })
export class StaffShiftsApi {
  private readonly api = inject(ApiClient);

  private branch(scope: LocationScope): string {
    return `/api/v1/operations/tenants/${enc(scope.tenantId)}/brands/${enc(scope.brandId)}/locations/${enc(scope.locationId)}/staff`;
  }

  private tenant(tenantId: string): string {
    return `/api/v1/operations/tenants/${enc(tenantId)}/staff`;
  }

  // ----------------------------------------------------------------------- whether it is on

  /** Whether the tenant records staff hours at all; the shift screens exist only when it does. */
  async enabled(tenantId: string): Promise<boolean> {
    const read = await firstValueFrom(
      this.api.get<{ enabled: boolean }>(`${this.tenant(tenantId)}/attendance`),
    );
    return read.value.enabled === true;
  }

  // -------------------------------------------------------------------------------- the rota

  async week(scope: LocationScope, from: string, to: string): Promise<RosterWeek> {
    const read = await firstValueFrom(
      this.api.get<RosterWeek>(`${this.branch(scope)}/roster`, { params: { from, to } }),
    );
    return read.value;
  }

  async rosterable(scope: LocationScope): Promise<readonly ShiftPerson[]> {
    const read = await firstValueFrom(
      this.api.get<ShiftPerson[]>(`${this.branch(scope)}/roster/people`),
    );
    return read.value;
  }

  async createEntry(scope: LocationScope, draft: EntryDraft): Promise<RosterEntry> {
    return firstValueFrom(
      this.api.post<EntryDraft, RosterEntry>(
        `${this.branch(scope)}/roster/entries`,
        command(draft),
      ),
    );
  }

  async updateEntry(
    scope: LocationScope,
    entry: RosterEntry,
    draft: EntryDraft,
  ): Promise<RosterEntry> {
    return firstValueFrom(
      this.api.put<EntryDraft, RosterEntry>(
        `${this.branch(scope)}/roster/entries/${enc(entry.id)}`,
        command(draft),
        { expectedVersion: entry.version },
      ),
    );
  }

  async cancelEntry(
    scope: LocationScope,
    entry: RosterEntry,
    reasonCode: ShiftReason,
  ): Promise<void> {
    const body = { reasonCode };
    await firstValueFrom(
      this.api.send<typeof body, void>(
        'POST',
        `${this.branch(scope)}/roster/entries/${enc(entry.id)}/cancellations`,
        command(body),
        { expectedVersion: entry.version },
      ),
    );
  }

  async publish(
    scope: LocationScope,
    from: string,
    to: string,
  ): Promise<{ published: number; people: readonly string[] }> {
    const body = { from, to };
    return firstValueFrom(
      this.api.post<typeof body, { published: number; people: string[] }>(
        `${this.branch(scope)}/roster/publications`,
        command(body),
      ),
    );
  }

  async copyWeek(
    scope: LocationScope,
    fromWeek: string,
    toWeek: string,
  ): Promise<{ created: number; skipped: number }> {
    const body = { fromWeek, toWeek };
    return firstValueFrom(
      this.api.post<typeof body, { created: number; skipped: number }>(
        `${this.branch(scope)}/roster/copies`,
        command(body),
      ),
    );
  }

  // ----------------------------------------------------------------------------- the timesheet

  async today(scope: LocationScope): Promise<TodayList> {
    const read = await firstValueFrom(
      this.api.get<TodayList>(`${this.branch(scope)}/shifts/today`),
    );
    return read.value;
  }

  async shifts(
    scope: LocationScope,
    filter: { from?: string; to?: string; memberId?: string; cursor?: string | null },
  ): Promise<ShiftPage> {
    const read = await firstValueFrom(
      this.api.get<ShiftPage>(`${this.branch(scope)}/shifts`, {
        params: {
          from: filter.from,
          to: filter.to,
          memberId: filter.memberId,
          cursor: filter.cursor ?? undefined,
        },
      }),
    );
    return read.value;
  }

  /** A person's own recent shifts at this branch, for the person card's «Смены» tab. */
  async shiftsOf(scope: LocationScope, memberId: string): Promise<ShiftPage> {
    return this.shifts(scope, { memberId });
  }

  async openFor(
    scope: LocationScope,
    memberId: string,
    startedAt: string,
    reasonCode: ShiftReason | null,
  ): Promise<StaffShift> {
    const body = { memberId, startedAt, ...(reasonCode === null ? {} : { reasonCode }) };
    return firstValueFrom(
      this.api.post<typeof body, StaffShift>(`${this.branch(scope)}/shifts`, command(body)),
    );
  }

  async closeFor(
    scope: LocationScope,
    shift: ShiftRef,
    endedAt: string,
    breakSeconds: number | null,
    reasonCode: ShiftReason | null,
  ): Promise<StaffShift> {
    const body = {
      endedAt,
      ...(breakSeconds === null ? {} : { breakSeconds }),
      ...(reasonCode === null ? {} : { reasonCode }),
    };
    return firstValueFrom(
      this.api.post<typeof body, StaffShift>(
        `${this.branch(scope)}/shifts/${enc(shift.id)}/closures`,
        command(body),
        {
          expectedVersion: shift.version,
        },
      ),
    );
  }

  async correct(
    scope: LocationScope,
    shift: ShiftRef,
    change: { startedAt?: string; endedAt?: string; breakSeconds?: number },
    reasonCode: ShiftReason,
  ): Promise<StaffShift> {
    const body = { ...change, reasonCode };
    return firstValueFrom(
      this.api.put<typeof body, StaffShift>(
        `${this.branch(scope)}/shifts/${enc(shift.id)}`,
        command(body),
        {
          expectedVersion: shift.version,
        },
      ),
    );
  }

  async voidShift(
    scope: LocationScope,
    shift: ShiftRef,
    reasonCode: ShiftReason,
  ): Promise<StaffShift> {
    const body = { reasonCode };
    return firstValueFrom(
      this.api.post<typeof body, StaffShift>(
        `${this.branch(scope)}/shifts/${enc(shift.id)}/voidings`,
        command(body),
        {
          expectedVersion: shift.version,
        },
      ),
    );
  }

  async approve(
    scope: LocationScope,
    shift: ShiftRef,
    reasonCode: ShiftReason,
  ): Promise<StaffShift> {
    const body = { reasonCode };
    return firstValueFrom(
      this.api.post<typeof body, StaffShift>(
        `${this.branch(scope)}/shifts/${enc(shift.id)}/approvals`,
        command(body),
        {
          expectedVersion: shift.version,
        },
      ),
    );
  }

  // ------------------------------------------------------------------------- a person's own

  async mine(tenantId: string): Promise<MyAttendance> {
    const read = await firstValueFrom(
      this.api.get<MyAttendance>(`${this.tenant(tenantId)}/me/attendance`),
    );
    return read.value;
  }

  async clockIn(tenantId: string, locationId: string): Promise<StaffShift> {
    const body = { locationId };
    return firstValueFrom(
      this.api.post<typeof body, StaffShift>(
        `${this.tenant(tenantId)}/me/attendance/clock-ins`,
        command(body),
      ),
    );
  }

  async clockOut(tenantId: string): Promise<StaffShift> {
    const body = {};
    return firstValueFrom(
      this.api.post<typeof body, StaffShift>(
        `${this.tenant(tenantId)}/me/attendance/clock-outs`,
        command(body),
      ),
    );
  }
}

function enc(value: string): string {
  return encodeURIComponent(value);
}
