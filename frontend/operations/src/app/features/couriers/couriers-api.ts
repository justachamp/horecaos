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

/**
 * The nine protected fields of a courier's compliance file (IA 3.3), by the
 * name the backend's `ComplianceField` gives each.
 *
 * `NOTES` is the enum name; the request field carrying it is `remarks`, which
 * is not an oversight — see `CourierComplianceFileRequest`'s own doc on the
 * controller for why an ADR 0029 response must not have a component whose name
 * contains "note".
 */
export type ComplianceFieldName =
  | 'PASSPORT'
  | 'PINFL'
  | 'DRIVING_LICENCE'
  | 'VEHICLE_REGISTRATION'
  | 'VEHICLE_PLATE'
  | 'ADDRESS'
  | 'EMERGENCY_CONTACT'
  | 'REFERRAL'
  | 'NOTES';

/** `PETROL | DIESEL | GAS | ELECTRIC | HYBRID | NONE`, or absent. */
export type VehicleFuelType = 'PETROL' | 'DIESEL' | 'GAS' | 'ELECTRIC' | 'HYBRID' | 'NONE';

/**
 * What a compliance-file write carries.
 *
 * Three-valued, and it must be: a field sent is written, a field named in
 * `clear` is removed, a field in neither is untouched. The console never holds
 * these plaintexts outside a reveal, so it cannot re-send the eight fields it
 * did not change, and a whole-file replace would erase them.
 */
export interface CourierComplianceFileRequest {
  readonly passport?: string | null;
  readonly pinfl?: string | null;
  readonly drivingLicence?: string | null;
  readonly vehicleRegistration?: string | null;
  readonly vehiclePlate?: string | null;
  readonly vehicleFuelType?: VehicleFuelType | null;
  readonly photoMediaId?: string | null;
  readonly homeAddress?: string | null;
  readonly emergencyContact?: string | null;
  readonly referral?: string | null;
  readonly remarks?: string | null;
  readonly clear?: readonly ComplianceFieldName[];
  readonly reason: string;
}

export interface RegisterCourierRequest extends CourierComplianceFileRequest {
  readonly courierTypeId: string;
  /** The Keycloak courier-client subject this person signs in as — provisioned outside this console. */
  readonly principalSubject: string;
  readonly displayReference: string;
  readonly fullName: string;
  /** ISO date. */
  readonly engagedFrom: string;
  readonly reason: string;
}

/** Mirrors `OperationsCourierController.CourierGroupResponse`. */
export interface CourierGroupResponse {
  readonly groupId: string;
  readonly code: string;
  readonly displayName: string;
  readonly status: 'ACTIVE' | 'ARCHIVED';
  readonly memberCount: number;
}

/** Mirrors `OperationsCourierController.CourierBranchBindingResponse`. */
export interface CourierBranchBindingResponse {
  readonly locationId: string;
  readonly brandId: string;
  readonly locationName: string;
  readonly primary: boolean;
}

/**
 * Mirrors `OperationsCourierController.CourierDetailResponse`.
 *
 * `complianceFieldsOnFile` is presence, never content: it says a passport
 * exists so a manager can chase a missing licence without anybody exercising
 * `courier.pii.reveal`.
 */
export interface CourierDetailResponse extends RosterEntryResponse {
  readonly complianceFieldsOnFile: readonly ComplianceFieldName[];
  readonly vehicleFuelType?: VehicleFuelType | null;
  readonly photoMediaId?: string | null;
  readonly complianceUpdatedAt?: string | null;
  readonly groups: readonly CourierGroupResponse[];
  readonly branches: readonly CourierBranchBindingResponse[];
}

/** Mirrors `OperationsCourierController.CourierComplianceRevealResponse`. */
export interface CourierComplianceRevealResponse {
  readonly fields: readonly { readonly field: ComplianceFieldName; readonly value: string }[];
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

/** Mirrors `OperationsCourierController.CreateCourierTypeRequest` (IA 3.4). */
export interface CreateCourierTypeRequest {
  readonly code: string;
  readonly displayName: string;
  readonly vehicleClass: string;
  readonly minDistanceMeters: number;
  readonly maxDistanceMeters?: number | null;
  readonly maxConcurrentAssignments: number;
  readonly offerTtlSeconds: number;
}

/** Mirrors `OperationsCourierController.RateCardSummaryResponse`. */
export interface RateCardSummaryResponse {
  readonly cardId: string;
  readonly brandId: string;
  readonly locationId?: string | null;
  readonly courierTypeId?: string | null;
  readonly code: string;
  readonly cardVersion: number;
  readonly status: 'DRAFT' | 'ACTIVE' | 'SUPERSEDED';
  readonly currency: string;
  readonly effectiveFrom?: string | null;
  readonly effectiveTo?: string | null;
}

/** Mirrors `OperationsCourierController.RateComponentResponse`. */
export interface RateComponentView {
  readonly componentId: string;
  /** `PER_SHIFT_FIXED` | `PER_ORDER` | `PER_KM_BAND` | `PER_ORDER_MINIMUM`. */
  readonly componentType: string;
  readonly priority: number;
  readonly amountMinor: number;
  readonly bandFromMeters?: number | null;
  readonly bandToMeters?: number | null;
  readonly minimumPaidSeconds?: number | null;
}

/** Mirrors `OperationsCourierController.RateCardDetailResponse`. */
export interface RateCardDetailResponse {
  readonly cardId: string;
  readonly cardVersion: number;
  readonly currency: string;
  readonly components: readonly RateComponentView[];
}

/** Mirrors `OperationsCourierController.RateComponentRequest`. */
export interface RateComponentRequest {
  readonly componentType: string;
  readonly priority: number;
  readonly amountMinor: number;
  readonly bandFromMeters?: number | null;
  readonly bandToMeters?: number | null;
  readonly minimumPaidSeconds?: number | null;
}

/** Mirrors `OperationsCourierController.NewRateCardRequest`. */
export interface NewRateCardRequest {
  readonly brandId: string;
  readonly locationId?: string | null;
  readonly courierTypeId?: string | null;
  readonly code: string;
  readonly cardVersion: number;
  readonly currency: string;
  readonly components: readonly RateComponentRequest[];
}

/** Mirrors `OperationsCourierController.ShiftResponse` (IA 3.5). */
export interface ShiftView {
  readonly shiftId: string;
  readonly courierId: string;
  /** `OPEN` | `CLOSE_REQUESTED` | `RECONCILING` | `AWAITING_APPROVAL` | `CLOSED` | `AUTO_CLOSED` | `SETTLED`. */
  readonly status: string;
  readonly dutyState: string;
  readonly openedAt: string;
  readonly closedAt?: string | null;
  readonly paidSeconds?: number | null;
  readonly breakSeconds: number;
  readonly approvalRequestId?: string | null;
}

/** Mirrors `OperationsCourierController.CourierPolicyResponse` (IA 3.9). */
export interface CourierPolicyView {
  readonly reverificationDays: number;
  readonly warningDays: number;
  readonly settlementPeriodDays: number;
  readonly cashCeilingMinor: number;
  readonly penaltyApprovalThresholdMinor: number;
  readonly shiftEnforcement: 'ENFORCED' | 'ADVISORY' | 'OFF';
  readonly graceSeconds: number;
  readonly confirmationPointRetentionDays: number;
  readonly winningScope: string;
  readonly policyId: string;
  readonly policyVersion: number;
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

  // ---------------------------------------------- IA 3.3, the compliance file

  async courier(tenantId: string, courierId: string): Promise<CourierDetailResponse> {
    const result = await firstValueFrom(
      this.api.get<CourierDetailResponse>(courierPaths.courier(tenantId, courierId)),
    );
    return result.value;
  }

  async recordComplianceFile(
    tenantId: string,
    courierId: string,
    request: CourierComplianceFileRequest,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<CourierComplianceFileRequest, void>(
        courierPaths.courierComplianceFile(tenantId, courierId),
        command(request),
      ),
    );
  }

  /**
   * The ADR 0029 reveal. Every call is audited against the purpose the operator
   * typed, so the purpose is a required argument here rather than a defaulted
   * one — a default would be the same sentence on every reveal the platform
   * ever records.
   */
  async revealComplianceFile(
    tenantId: string,
    courierId: string,
    purpose: string,
  ): Promise<CourierComplianceRevealResponse> {
    const result = await firstValueFrom(
      this.api.get<CourierComplianceRevealResponse>(
        courierPaths.courierComplianceFile(tenantId, courierId),
        { params: { purpose } },
      ),
    );
    return result.value;
  }

  async groups(tenantId: string): Promise<readonly CourierGroupResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly CourierGroupResponse[]>(courierPaths.courierGroups(tenantId)),
    );
    return result.value ?? [];
  }

  async createGroup(
    tenantId: string,
    code: string,
    displayName: string,
    reason: string,
  ): Promise<{ groupId: string }> {
    return firstValueFrom(
      this.api.post<{ code: string; displayName: string; reason: string }, { groupId: string }>(
        courierPaths.courierGroups(tenantId),
        command({ code, displayName, reason }),
      ),
    );
  }

  async joinGroup(
    tenantId: string,
    courierId: string,
    groupId: string,
    reason: string,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<{ groupId: string; reason: string }, void>(
        courierPaths.courierGroupMemberships(tenantId, courierId),
        command({ groupId, reason }),
      ),
    );
  }

  async leaveGroup(
    tenantId: string,
    courierId: string,
    groupId: string,
    reason: string,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<{ reason: string }, void>(
        courierPaths.courierGroupRemoval(tenantId, courierId, groupId),
        command({ reason }),
      ),
    );
  }

  async bindBranch(
    tenantId: string,
    courierId: string,
    brandId: string,
    locationId: string,
    primary: boolean,
    reason: string,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<
        { brandId: string; locationId: string; primary: boolean; reason: string },
        void
      >(
        courierPaths.courierBranchBindings(tenantId, courierId),
        command({ brandId, locationId, primary, reason }),
      ),
    );
  }

  async unbindBranch(
    tenantId: string,
    courierId: string,
    locationId: string,
    reason: string,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<{ reason: string }, void>(
        courierPaths.courierBranchUnbinding(tenantId, courierId, locationId),
        command({ reason }),
      ),
    );
  }

  // ------------------------------------------------------------- IA 3.4

  async createType(
    tenantId: string,
    request: CreateCourierTypeRequest,
  ): Promise<CourierTypeResponse> {
    return firstValueFrom(
      this.api.post<CreateCourierTypeRequest, CourierTypeResponse>(
        courierPaths.courierTypes(tenantId),
        command(request),
      ),
    );
  }

  async rateCards(tenantId: string, brandId: string): Promise<readonly RateCardSummaryResponse[]> {
    const result = await firstValueFrom(
      this.api.get<readonly RateCardSummaryResponse[]>(courierPaths.rateCards(tenantId), {
        params: { brandId },
      }),
    );
    return result.value ?? [];
  }

  async rateCard(tenantId: string, cardId: string): Promise<RateCardDetailResponse> {
    const result = await firstValueFrom(
      this.api.get<RateCardDetailResponse>(courierPaths.rateCard(tenantId, cardId)),
    );
    return result.value;
  }

  async authorRateCard(tenantId: string, request: NewRateCardRequest): Promise<{ cardId: string }> {
    return firstValueFrom(
      this.api.post<NewRateCardRequest, { cardId: string }>(
        courierPaths.rateCards(tenantId),
        command(request),
      ),
    );
  }

  async activateRateCard(tenantId: string, cardId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<{ reason: string }, void>(
        courierPaths.rateCardActivation(tenantId, cardId),
        command({ reason }),
      ),
    );
  }

  // ------------------------------------------------------------- IA 3.5

  async shifts(
    tenantId: string,
    brandId: string,
    locationId: string,
    limit = 200,
  ): Promise<readonly ShiftView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly ShiftView[]>(courierPaths.courierShifts(tenantId), {
        params: { brandId, locationId, limit },
      }),
    );
    return result.value ?? [];
  }

  async closeShift(
    tenantId: string,
    shiftId: string,
    reasonCode: string,
    reason: string,
    currency: string,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<{ reasonCode: string; reason: string; currency: string }, void>(
        `${courierPaths.courierShifts(tenantId)}/${encodeURIComponent(shiftId)}/close`,
        command({ reasonCode, reason, currency }),
      ),
    );
  }

  async approveShift(tenantId: string, shiftId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<{ reason: string; approvalRequestId?: string | null }, void>(
        `${courierPaths.courierShifts(tenantId)}/${encodeURIComponent(shiftId)}/approve`,
        command({ reason }),
      ),
    );
  }

  // ------------------------------------------------------------- IA 3.9

  async policy(
    tenantId: string,
    brandId?: string,
    locationId?: string,
  ): Promise<CourierPolicyView> {
    const result = await firstValueFrom(
      this.api.get<CourierPolicyView>(courierPaths.courierPolicy(tenantId), {
        params: { ...(brandId ? { brandId } : {}), ...(locationId ? { locationId } : {}) },
      }),
    );
    return result.value;
  }
}
