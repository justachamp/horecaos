import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { businessCalendarPaths } from '../../../core/api/reports-paths';
import { command } from '../../../core/api/idempotency';

/** Mirrors uz.horecaos.platform.reporting.web.BusinessCalendarController.HolidayResponse. */
export interface TenantHoliday {
  readonly holidayId: string;
  readonly name: string;
  readonly month: number | null;
  readonly day: number | null;
  readonly date: string | null;
}

/** Mirrors uz.horecaos.platform.reporting.web.BusinessCalendarController.CalendarResponse. */
export interface TenantCalendar {
  readonly timezone: string;
  readonly businessDayStart: string;
  readonly boundaryVersion: number;
  readonly recutCompletedThrough: string | null;
  readonly weekendDays: readonly number[];
  readonly holidays: readonly TenantHoliday[];
}

/** Mirrors BusinessCalendarController.BoundaryChangeResponse's `status`. */
export type BoundaryChangeStatus = 'CHANGED' | 'AWAITING_APPROVAL' | 'DECLINED' | 'UNCHANGED';

export interface BoundaryChangeResult {
  readonly status: BoundaryChangeStatus;
  readonly approvalRequestId: string | null;
}

/**
 * 10.10b — `BusinessCalendarController`: a tenant's weekend, its own
 * holidays, and the business-day boundary editor
 * `BusinessDayService.setBoundary` never had a caller for before this wave.
 */
@Injectable({ providedIn: 'root' })
export class BusinessCalendarApi {
  private readonly api = inject(ApiClient);

  async get(tenantId: string): Promise<TenantCalendar> {
    const result = await firstValueFrom(
      this.api.get<TenantCalendar>(businessCalendarPaths.root(tenantId)),
    );
    if (!result.value) {
      throw new Error('The tenant business calendar answered with no body');
    }
    return result.value;
  }

  /**
   * Moves the boundary. The caller must show its own "this changes which
   * business date every future fact is filed under" warning before calling
   * this — the same discipline the reason-editor's version warning follows.
   */
  async changeBoundary(
    tenantId: string,
    businessDayStart: string,
    reason: string,
  ): Promise<BoundaryChangeResult> {
    return firstValueFrom(
      this.api.put<{ businessDayStart: string; reason: string }, BoundaryChangeResult>(
        businessCalendarPaths.boundary(tenantId),
        command({ businessDayStart, reason }),
      ),
    );
  }

  async setWeekend(
    tenantId: string,
    weekendDays: readonly number[],
    reason: string,
  ): Promise<void> {
    await firstValueFrom(
      this.api.put<{ weekendDays: readonly number[]; reason: string }, void>(
        businessCalendarPaths.weekend(tenantId),
        command({ weekendDays, reason }),
      ),
    );
  }

  async addHoliday(
    tenantId: string,
    holiday: { name: string; month?: number; day?: number; date?: string },
    reason: string,
  ): Promise<string> {
    const response = await firstValueFrom(
      this.api.post<
        { name: string; month?: number; day?: number; date?: string; reason: string },
        { holidayId: string }
      >(businessCalendarPaths.holidays(tenantId), command({ ...holiday, reason })),
    );
    return response.holidayId;
  }

  async removeHoliday(tenantId: string, holidayId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.send<{ reason: string }, void>(
        'DELETE',
        businessCalendarPaths.holiday(tenantId, holidayId),
        command({ reason }),
      ),
    );
  }
}
