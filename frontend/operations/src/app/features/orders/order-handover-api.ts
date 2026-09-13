import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, throwError } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
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
    return this.api.post<{ code: string }, HandoverVerification>(
      marketplacePaths.handoverVerifications(scope, orderId),
      command({ code }),
    );
  }

  /** The audited supervisor override — requires `MARKETPLACE_HANDOVER_BYPASS`, never `ORDER_ADVANCE`. */
  bypass(
    scope: LocationScope,
    orderId: string,
    reasonCode: string,
    supervisorName: string,
  ): Observable<void> {
    return this.api.post<{ reasonCode: string; supervisorName: string }, void>(
      marketplacePaths.handoverBypasses(scope, orderId),
      command({ reasonCode, supervisorName }),
    );
  }
}
