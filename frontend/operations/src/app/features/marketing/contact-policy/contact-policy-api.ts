import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { command } from '../../../core/api/idempotency';
import { marketingPaths } from '../../../core/api/marketing-paths';

/** Mirrors `marketing.domain.ContactPeriod`: the window a cap counts over. */
export type ContactPeriod = 'DAILY' | 'WEEKLY' | 'ROLLING_7D' | 'ROLLING_30D';

/** Mirrors `ContactPolicyController.PlatformBounds`: the numbers an override may only move inwards from. */
export interface PlatformBoundsView {
  /** `HH:mm:ss`: quiet hours may start no later than this. */
  readonly quietHoursStartNoLaterThan: string;
  /** `HH:mm:ss`: quiet hours may end no earlier than this. */
  readonly quietHoursEndNoEarlierThan: string;
  readonly dailyCapCeiling: number;
  readonly weeklyCapCeiling: number;
  readonly rolling7DayCapCeiling: number;
  readonly rolling30DayCapCeiling: number;
}

/** Mirrors `ContactPolicyController.OverrideResponse`. */
export interface ContactPolicyOverrideView {
  readonly channel: string;
  readonly campaignPurpose: string;
  readonly period: ContactPeriod;
  readonly capCount: number | null;
  readonly quietHoursStart: string | null;
  readonly quietHoursEnd: string | null;
  readonly statedReason: string;
  readonly updatedBy: string;
  readonly version: number;
  readonly updatedAt: string;
}

/** Mirrors `ContactPolicyController.ContactPolicyResponse`. */
export interface ContactPolicyView {
  readonly platform: PlatformBoundsView;
  readonly overrides: readonly ContactPolicyOverrideView[];
}

/** Mirrors `ContactPolicyController.OverrideBody`. Absent numbers and times are `null`. */
export interface ContactPolicyOverrideRequest {
  readonly channel: string;
  readonly campaignPurpose: string;
  readonly period: ContactPeriod;
  readonly capCount: number | null;
  readonly quietHoursStart: string | null;
  readonly quietHoursEnd: string | null;
  readonly statedReason: string;
}

/** Mirrors `ContactPolicyController.DefaultsResponse`: the ADR 0030 values scenarios read, read-only here. */
export interface ContactPolicyDefaultsView {
  readonly channelPriorityOrder: readonly string[];
  readonly inAppShowCapPerDay: number;
  readonly controlGroupPercentDefault: number;
}

/**
 * A brand's contact policy (ADR 0112): the platform's bounds and the tighter rules the tenant has
 * added. An override may make a brand quieter and never louder; the server refuses a loosening
 * with the number it exceeded, and the table's own CHECK is the second wall.
 */
@Injectable({ providedIn: 'root' })
export class ContactPolicyApi {
  private readonly api = inject(ApiClient);

  async read(scope: BrandScope): Promise<ContactPolicyView> {
    const result = await firstValueFrom(
      this.api.get<ContactPolicyView>(marketingPaths.contactPolicy(scope)),
    );
    return result.value;
  }

  async defaults(scope: BrandScope): Promise<ContactPolicyDefaultsView> {
    const result = await firstValueFrom(
      this.api.get<ContactPolicyDefaultsView>(marketingPaths.contactPolicyDefaults(scope)),
    );
    return result.value;
  }

  /**
   * Creates the override for the channel, purpose and period named in the body, or, with
   * `expectedVersion`, replaces the one at that version. Without a version an existing override is
   * refused, which is how two people setting the same rule at once are told apart.
   */
  async set(
    scope: BrandScope,
    request: ContactPolicyOverrideRequest,
    expectedVersion?: number,
  ): Promise<ContactPolicyOverrideView> {
    return firstValueFrom(
      this.api.put<ContactPolicyOverrideRequest, ContactPolicyOverrideView>(
        marketingPaths.contactPolicyOverrides(scope),
        command(request),
        expectedVersion === undefined ? {} : { expectedVersion },
      ),
    );
  }

  /** Returns the brand to the platform bound for that channel, purpose and period. A reason is required. */
  async remove(
    scope: BrandScope,
    override: Pick<ContactPolicyOverrideView, 'channel' | 'campaignPurpose' | 'period'>,
    reason: string,
  ): Promise<void> {
    await firstValueFrom(
      this.api.send<null, void>(
        'DELETE',
        marketingPaths.contactPolicyOverride(
          scope,
          override.channel,
          override.campaignPurpose,
          override.period,
        ),
        command(null),
        { params: { reason } },
      ),
    );
  }
}
