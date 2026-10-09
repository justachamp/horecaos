import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';

/** ONLINE | PAUSED | WRAP_UP | OFFLINE (ADR 0064). */
export type PresenceState = 'ONLINE' | 'PAUSED' | 'WRAP_UP' | 'OFFLINE';

/** Mirrors `OperatorPresenceController.PresenceResponse`. */
export interface PresenceView {
  readonly operatorPrincipalId: string;
  readonly state: PresenceState;
  readonly reason: string | null;
  readonly changedAt: string;
  readonly version: number;
}

/** Mirrors `OrderDirectory.RecentOrder`, as `ScreenPopController` renders it. */
export interface ScreenPopRecentOrder {
  readonly orderId: string;
  readonly publicOrderNumber: string;
  readonly locationId: string;
  readonly status: string;
  readonly currency: string;
  readonly totalMinor: number;
  readonly placedAt: string;
}

/** Mirrors `ScreenPopController.CardResponse`. */
export interface ScreenPopCard {
  readonly ringing: boolean;
  readonly callEventId: string | null;
  readonly lineDid: string | null;
  readonly maskedCallerNumber: string | null;
  readonly occurredAt: string | null;
  readonly unknownCaller: boolean;
  readonly customerAccountId: string | null;
  readonly customerDisplayName: string | null;
  readonly recentOrders: readonly ScreenPopRecentOrder[];
  readonly acknowledgedBy: string | null;
}

/** Mirrors `CallLogController.CallLogEntryResponse`. */
export interface CallLogEntry {
  readonly callEventId: string;
  readonly providerCallId: string;
  /** OFFERED | ANSWERED | ENDED | MISSED | TRANSFERRED. */
  readonly eventType: string;
  readonly direction: string;
  readonly lineDid: string | null;
  readonly operatorPrincipalId: string | null;
  readonly durationSeconds: number | null;
  readonly occurredAt: string;
}

/**
 * T12 (7.5b): one hour's telephony totals for one operator, written by the
 * same day-close pipeline as every other ADR 0043 fact. Mirrors
 * `CallStatsController.CallHourResponse`.
 *
 * ADR 0175: the last five fields are counts and sums, never ratios, and each is
 * `null` for a row closed before the measure existed ("not measured"), which a
 * screen must show as «—» and never as zero. `talkDurationSeconds` is the call's
 * length (first event to end, ring and queue time included); the name is
 * historic and the label says "Call length".
 */
export interface CallHourStat {
  readonly hourOfDay: number;
  readonly operatorPrincipalId: string;
  readonly offeredCount: number;
  readonly answeredCount: number;
  readonly missedCount: number;
  readonly transferredCount: number;
  readonly talkDurationSeconds: number;
  readonly answeredCallCount?: number | null;
  readonly ringSecondsTotal?: number | null;
  readonly ringSampleCount?: number | null;
  readonly ringSecondsMax?: number | null;
  readonly convertedCount?: number | null;
}

/**
 * ADR 0175 (7.5b): the Telephony tab's three states. Mirrors
 * `CallStatsController.CallCoverageResponse`. No caller number, customer or
 * order identifier is ever in it.
 */
export interface CallCoverage {
  /** `CONNECTED` when an active voice installation is bound to the branch. */
  readonly connection: 'CONNECTED' | 'NOT_CONNECTED';
  readonly activeInstallationCount: number;
  readonly firstCallEventAt: string | null;
  readonly lastCallEventAt: string | null;
  /** Closed business days in the requested range that hold a call fact. */
  readonly closedDaysWithCalls: number;
  /** When the business day open now closes and its calls first appear. */
  readonly nextCloseAt: string;
}

/**
 * IA 1.6, Call centre (ADR 0064) — operator presence, the screen-pop poll,
 * and the branch's call log. Not a softphone and not click-to-call: ADR 0064
 * deliberately keeps audio off this platform, so this API only ever carries
 * what the platform itself knows about a call, never the call itself.
 */
@Injectable({ providedIn: 'root' })
export class CallCentreApi {
  private readonly api = inject(ApiClient);

  async myPresence(scope: LocationScope): Promise<PresenceView> {
    const result = await firstValueFrom(
      this.api.get<PresenceView>(operationsPaths.voicePresenceMine(scope)),
    );
    return result.value;
  }

  async roster(scope: LocationScope): Promise<readonly PresenceView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly PresenceView[]>(operationsPaths.voicePresence(scope)),
    );
    return result.value ?? [];
  }

  async setPresence(
    scope: LocationScope,
    state: PresenceState,
    reason: string | null,
  ): Promise<PresenceView> {
    return firstValueFrom(
      this.api.put<{ state: PresenceState; reason: string | null }, PresenceView>(
        operationsPaths.voicePresence(scope),
        command({ state, reason }),
      ),
    );
  }

  async currentCall(scope: LocationScope): Promise<ScreenPopCard> {
    const result = await firstValueFrom(
      this.api.get<ScreenPopCard>(operationsPaths.voiceScreenPopCurrent(scope)),
    );
    return result.value;
  }

  async acknowledge(scope: LocationScope, callEventId: string): Promise<void> {
    await firstValueFrom(
      this.api.post<Record<string, never>, void>(
        operationsPaths.voiceScreenPopAcknowledgement(scope, callEventId),
        command({}),
      ),
    );
  }

  /**
   * The one deliberate reveal this screen performs — an unknown caller's own
   * number, for the create-customer prefill. Refused (and never called) for
   * a resolved caller; see `ScreenPopController.callerNumber`'s own doc.
   */
  async revealCallerNumber(scope: LocationScope, callEventId: string): Promise<string> {
    const result = await firstValueFrom(
      this.api.get<{ number: string }>(
        operationsPaths.voiceScreenPopCallerNumber(scope, callEventId),
      ),
    );
    return result.value.number;
  }

  async callLog(scope: LocationScope): Promise<readonly CallLogEntry[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CallLogEntry[]>(operationsPaths.voiceCallLog(scope)),
    );
    return result.value ?? [];
  }

  /**
   * T12 (7.5b): this branch's call activity for one business date, by hour
   * and operator (`CallStatsController`). Empty until the day closes — a
   * live shift's calls appear the next time the close runs, not in real time.
   */
  async callStats(scope: LocationScope, businessDate: string): Promise<readonly CallHourStat[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CallHourStat[]>(operationsPaths.voiceCallStats(scope), {
        params: { businessDate },
      }),
    );
    return result.value ?? [];
  }

  /**
   * ADR 0175: whether this branch has telephony, over the date range the page is
   * showing. Decides whether the Telephony tab is offered at all.
   */
  async callCoverage(scope: LocationScope, from: string, to: string): Promise<CallCoverage> {
    const result = await firstValueFrom(
      this.api.get<CallCoverage>(operationsPaths.voiceCallStatsCoverage(scope), {
        params: { from, to },
      }),
    );
    return result.value;
  }
}
