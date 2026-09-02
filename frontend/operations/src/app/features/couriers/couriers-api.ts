import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { courierPaths } from '../../core/api/operations-paths';
import { command } from '../../core/api/idempotency';

/**
 * Mirrors `OperationsCourierController.RosterEntryResponse`. No name field —
 * not even a masked one — because there is nothing decrypted to mask.
 * `displayReference` ("K-014") is the whole of what this response names a
 * courier by; see the wave's final report for why a legal-name reveal is not
 * built this wave.
 */
export interface RosterEntryResponse {
  readonly courierId: string;
  readonly displayReference: string;
  readonly status: string;
  readonly courierTypeId: string;
  readonly courierTypeName: string;
  readonly vehicleClass: string;
  readonly activeAssignments: number;
  readonly concurrencyCeiling: number;
  readonly engagementId?: string | null;
  /** `PENDING_VERIFICATION` | `ACTIVE` | `SUSPENDED_COMPLIANCE` | `SUSPENDED_OPERATIONAL` | `ENDED`. */
  readonly engagementStatus?: string | null;
  /** `VALID` | `EXPIRING` | `LAPSED`. */
  readonly warningState?: string | null;
  readonly reverificationDueOn?: string | null;
}

/** Mirrors `OperationsCourierController.CourierTypeResponse`. */
export interface CourierTypeResponse {
  readonly courierTypeId: string;
  readonly code: string;
  readonly displayName: string;
  readonly vehicleClass: string;
  readonly minDistanceMeters: number;
  readonly maxDistanceMeters?: number | null;
  readonly maxConcurrentAssignments: number;
  readonly offerTtlSeconds: number;
}

export interface RegisterCourierRequest {
  readonly courierTypeId: string;
  /** The Keycloak courier-client subject this person signs in as — provisioned outside this console. */
  readonly principalSubject: string;
  readonly displayReference: string;
  readonly fullName: string;
  /** ISO date. */
  readonly engagedFrom: string;
  readonly reason: string;
}

export interface CourierResponse {
  readonly courierId: string;
  readonly engagementId: string;
  readonly status: string;
}

export interface VerifyEngagementRequest {
  readonly registrationIdentifier: string;
  /** ISO date. */
  readonly validUntil: string;
  readonly method: 'MANUAL_ATTESTATION' | 'REGISTRY_LOOKUP';
  readonly evidenceMediaId?: string | null;
  readonly reason: string;
}

export interface EngagementResponse {
  readonly engagementId: string;
  readonly status: string;
  readonly warningState: string;
  readonly registrationValidUntil?: string | null;
  readonly reverificationDueOn?: string | null;
}

export interface SuspendEngagementRequest {
  readonly reasonCode: string;
  readonly reason: string;
}

/**
 * The in-house roster (operations §3.3 Couriers) — `OperationsCourierController`
 * (ADR 0042), tenant-scoped.
 */
@Injectable({ providedIn: 'root' })
export class CouriersApi {
  private readonly api = inject(ApiClient);

  async roster(tenantId: string): Promise<readonly RosterEntryResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RosterEntryResponse[]>(courierPaths.couriers(tenantId)),
    );
    return result.value ?? [];
  }

  async types(tenantId: string): Promise<readonly CourierTypeResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CourierTypeResponse[]>(courierPaths.courierTypes(tenantId)),
    );
    return result.value ?? [];
  }

  async register(tenantId: string, request: RegisterCourierRequest): Promise<CourierResponse> {
    return firstValueFrom(
      this.api.post<RegisterCourierRequest, CourierResponse>(
        courierPaths.courierRegistrations(tenantId),
        command(request),
      ),
    );
  }

  async verify(
    tenantId: string,
    engagementId: string,
    request: VerifyEngagementRequest,
  ): Promise<EngagementResponse> {
    return firstValueFrom(
      this.api.post<VerifyEngagementRequest, EngagementResponse>(
        courierPaths.courierEngagementVerify(tenantId, engagementId),
        command(request),
      ),
    );
  }

  async suspend(
    tenantId: string,
    engagementId: string,
    request: SuspendEngagementRequest,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<SuspendEngagementRequest, void>(
        courierPaths.courierEngagementSuspend(tenantId, engagementId),
        command(request),
      ),
    );
  }
}
