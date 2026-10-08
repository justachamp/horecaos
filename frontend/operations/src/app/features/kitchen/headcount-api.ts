import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/** `HeadcountPlanner.Status`: a row, or a whole weekday, that can or cannot be planned. */
export type HeadcountStatus = 'OK' | 'NO_POLICY' | 'TOO_FEW_DATES';

export type HeadcountHolidayMode = 'INCLUDE' | 'EXCLUDE' | 'WEIGHT';

/** The cooking station roles (ADR 0041) a figure may name. The pass, `EXPO`, is never planned. */
export const COOKING_ROLES = ['HOT', 'COLD', 'GRILL', 'BAR', 'BAKERY', 'PACKING'] as const;
export type CookingRole = (typeof COOKING_ROLES)[number];

/** Mirrors `KitchenHeadcountController.RowView`: one station in one operating hour. */
export interface HeadcountRow {
  readonly stationId: string;
  readonly stationCode: string;
  readonly stationRole: string;
  /** Operating-day-relative: 0 is the business day's own start, not the wall clock. */
  readonly hour: number;
  readonly status: HeadcountStatus;
  readonly portionsPerCookHour: number | null;
  readonly expectedPortions: number | null;
  readonly lowPortions: number | null;
  readonly highPortions: number | null;
  readonly cooksExpected: number | null;
  readonly cooksAtPeakOfBand: number | null;
  /** The station's own lowest ceiling for the hour; null when no window covers it. */
  readonly stationCeiling: number | null;
  /** The top of the band exceeds the ceiling: more cooks do not help. */
  readonly plantLimited: boolean;
  readonly sampleSize: number;
}

export interface HeadcountPeak {
  readonly hour: number;
  readonly cooksExpected: number;
  readonly cooksAtPeakOfBand: number;
}

export interface HeadcountRawCell {
  readonly date: string;
  readonly stationCode: string;
  readonly hour: number;
  readonly portions: number;
}

/** Mirrors `KitchenHeadcountController.PlanResponse`. */
export interface HeadcountPlan {
  readonly targetDate: string;
  readonly weekday: number;
  readonly status: HeadcountStatus;
  readonly sampleDates: readonly string[];
  readonly generatedFrom: { readonly metricId: string; readonly calculationVersion: number };
  readonly rows: readonly HeadcountRow[];
  readonly peak: HeadcountPeak | null;
  readonly stationsWithoutFigure: readonly string[];
  /** The raw per-date portions, only when the sample was too thin to plan from. */
  readonly history: readonly HeadcountRawCell[];
}

export type PolicyScope = 'TENANT' | 'BRAND' | 'LOCATION';

/** Mirrors `PolicyEditorView.Level`: one rung of the resolution ladder. */
export interface ProductivityLevel {
  readonly scopeType: PolicyScope | 'PLATFORM';
  readonly authored: boolean;
}

/** Mirrors `KitchenProductivityController.ProductivityResponse`. */
export interface ProductivityDocument {
  readonly schema: number;
  readonly roles: Readonly<Record<string, number>>;
  readonly stations: Readonly<Record<string, number>>;
  /** Nothing was published anywhere in the chain: no station has a figure. */
  readonly isDefault: boolean;
  readonly winningScope: PolicyScope | null;
  readonly policyId: string | null;
  readonly policyVersion: number;
  /** The version authored at exactly the asked scope (0 when it only inherits): the `If-Match`. */
  readonly versionAtScope: number;
  readonly levels: readonly ProductivityLevel[];
}

export interface ProductivityInput {
  readonly roles: Readonly<Record<string, number>>;
  readonly stations: Readonly<Record<string, number>>;
  readonly reason: string;
}

/**
 * The cook headcount plan and the productivity figures it divides by (ADR 0163,
 * `KitchenHeadcountController` and `KitchenProductivityController`).
 *
 * The plan is a read of a station-grain fact the day close writes; the figures are a tenant's own
 * statement of how many portions one cook produces in an hour, which the platform never supplies.
 */
@Injectable({ providedIn: 'root' })
export class HeadcountApi {
  private readonly api = inject(ApiClient);

  async plan(
    scope: LocationScope,
    weekday: number,
    holidayMode: HeadcountHolidayMode = 'INCLUDE',
  ): Promise<HeadcountPlan> {
    const params: Record<string, string | number> = { weekday };
    if (holidayMode !== 'INCLUDE') {
      params['holidayMode'] = holidayMode;
    }
    const result = await firstValueFrom(
      this.api.get<HeadcountPlan>(operationsPaths.kitchenHeadcountPlan(scope), { params }),
    );
    return result.value;
  }

  /** The figures in force at the asked scope, and the version a save is checked against. */
  async productivity(scope: LocationScope, level: PolicyScope): Promise<ProductivityDocument> {
    const result = await firstValueFrom(
      this.api.get<ProductivityDocument>(operationsPaths.kitchenProductivity(scope.tenantId), {
        params: scopeParams(scope, level),
      }),
    );
    return result.value;
  }

  /** Whole-document replace at exactly one scope; a stale `expectedVersion` is refused with `STALE_VERSION`. */
  async publishProductivity(
    scope: LocationScope,
    level: PolicyScope,
    input: ProductivityInput,
    expectedVersion: number,
  ): Promise<ProductivityDocument> {
    return firstValueFrom(
      this.api.put<ProductivityInput, ProductivityDocument>(
        operationsPaths.kitchenProductivity(scope.tenantId),
        command(input),
        { expectedVersion, params: scopeParams(scope, level) },
      ),
    );
  }
}

function scopeParams(scope: LocationScope, level: PolicyScope): Record<string, string> {
  switch (level) {
    case 'TENANT':
      return {};
    case 'BRAND':
      return { brandId: scope.brandId };
    case 'LOCATION':
      return { brandId: scope.brandId, locationId: scope.locationId };
  }
}
