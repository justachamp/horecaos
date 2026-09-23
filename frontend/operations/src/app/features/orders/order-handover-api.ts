import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, tap, throwError } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { IntentCommandRegistry } from '../../core/api/idempotency';
import { LocationScope, marketplacePaths } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';

/**
 * `ChallengeStateResponse` — `GET .../marketplace/orders/{orderId}/handover-challenge`
 * (`MarketplaceOperationsController`, wave P09/gap map `1.2m`, the one
 * backend addition this wave makes). `type`/`status` mirror
 * `HandoverChallengeType`/`HandoverChallengeStatus` — never the expected
 * value, which this response and every other handover response withhold on
 * purpose (ADR 0040).
 */
export interface ChallengeState {
  readonly id: string;
  readonly type: 'CODE' | 'QR' | 'SIGNATURE' | 'NONE';
  readonly status: 'PENDING' | 'VERIFIED' | 'BYPASSED' | 'FAILED' | 'EXPIRED';
  readonly attempts: number;
  readonly maxAttempts: number;
  readonly attemptsRemaining: number;
}

/** `VerificationResponse` — `POST .../handover-verifications`. */
export interface HandoverVerification {
  readonly verified: boolean;
  readonly status: ChallengeState['status'];
  readonly attemptsRemaining: number;
}

/**
 * Handover verification and its override (ADR 0040, orders.md §3.8; wave
 * P09/gap map `1.2m`) — `MarketplaceOperationsController`, tenant-scoped
 * (the challenge table has no location column of its own).
 */
@Injectable({ providedIn: 'root' })
export class OrderHandoverApi {
  private readonly api = inject(ApiClient);

  /**
   * Neither {@link verify} nor {@link bypass} carries `expectedVersion` — the
   * challenge settles by its own compare-and-set, not the order's aggregate
   * version — so nothing else guarded a double submission before this
   * (2026-09-21 audit follow-up (a)). {@link verify} matters most: it
   * "consumes one attempt whether or not the code matches" per its own doc,
   * so a lost-response retry that minted a fresh key used to burn a second
   * attempt off a customer's limited budget for a code that may already have
   * verified. Held per order, forgotten on a settled response, so a
   * genuinely new entry (a different code, or the same code retyped after
   * seeing the first one rejected) still consumes its own attempt.
   */
  private readonly verifyIntents = new IntentCommandRegistry<{ code: string }>();
  private readonly bypassIntents = new IntentCommandRegistry<{
    reasonCode: string;
    supervisorName: string;
  }>();

  /**
   * The challenge's current state, or `null` when this order was never
   * issued one (a fulfilment path with no handover proof configured, the
   * server's `404`) — the panel renders nothing in that case rather than an
   * error; any other failure still propagates.
   */
  challenge(scope: LocationScope, orderId: string): Observable<ChallengeState | null> {
    return this.api.get<ChallengeState>(marketplacePaths.handoverChallenge(scope, orderId)).pipe(
      map((response) => response.value),
      catchError((error: unknown) =>
        error instanceof ApiError && error.code === ApiErrorCode.RESOURCE_NOT_FOUND
          ? of(null)
          : throwError(() => error),
      ),
    );
  }

  /**
   * Consumes one attempt whether or not the code matches (the server's own
   * doc). No `expectedVersion`: the challenge settles by its own compare-and-
   * set, not by the order's aggregate version.
   */
  verify(scope: LocationScope, orderId: string, code: string): Observable<HandoverVerification> {
    const intent = this.verifyIntents.next(orderId, { code });
    return this.api
      .post<{ code: string }, HandoverVerification>(marketplacePaths.handoverVerifications(scope, orderId), intent)
      .pipe(tap(() => this.verifyIntents.forget(orderId)));
  }

  /** The audited supervisor override — requires `MARKETPLACE_HANDOVER_BYPASS`, never `ORDER_ADVANCE`. */
  bypass(
    scope: LocationScope,
    orderId: string,
    reasonCode: string,
    supervisorName: string,
  ): Observable<void> {
    const intent = this.bypassIntents.next(orderId, { reasonCode, supervisorName });
    return this.api
      .post<{ reasonCode: string; supervisorName: string }, void>(
        marketplacePaths.handoverBypasses(scope, orderId),
        intent,
      )
      .pipe(tap(() => this.bypassIntents.forget(orderId)));
  }
}
