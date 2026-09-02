import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { Page } from '../../core/api/page';

/** Mirrors uz.horecaos.platform.tenancy.domain.TenantStatus. */
export type TenantStatus = 'PROVISIONING' | 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';

/** Mirrors uz.horecaos.platform.tenancy.domain.OperatingUnitStatus. */
export type OperatingUnitStatus = 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';

/** Mirrors uz.horecaos.platform.tenancy.domain.CustomerIdentityMode. */
export type CustomerIdentityMode = 'TENANT_SHARED' | 'BRAND_ISOLATED';

/** GET /control-plane/tenants -- TenantControlPlaneService.TenantSummaryView. */
export interface TenantSummaryView {
  readonly id: string;
  readonly slug: string;
  readonly legalName: string;
  readonly displayName: string;
  readonly defaultCurrency: string;
  readonly defaultTimezone: string;
  readonly status: TenantStatus;
  readonly createdAt: string;
}

/** TenantControlPlaneService.TenantView. */
export interface TenantView {
  readonly id: string;
  readonly slug: string;
  readonly legalName: string;
  readonly displayName: string;
  readonly defaultCurrency: string;
  readonly defaultTimezone: string;
  readonly keycloakOrganizationId: string | null;
  readonly status: TenantStatus;
  readonly customerIdentityMode: CustomerIdentityMode;
}

export interface CreateTenantRequest {
  readonly slug: string;
  readonly legalName: string;
  readonly displayName: string;
  readonly defaultCurrency: string;
  readonly defaultTimezone: string;
  readonly customerIdentityMode: CustomerIdentityMode;
}

export interface BrandView {
  readonly id: string;
  readonly tenantId: string;
  readonly code: string;
  readonly slug: string;
  readonly displayName: string;
  readonly status: OperatingUnitStatus;
}

export interface CreateOperatingUnitRequest {
  readonly code: string;
  readonly slug: string;
  readonly displayName: string;
}

export interface LocationView {
  readonly id: string;
  readonly tenantId: string;
  readonly brandId: string;
  readonly code: string;
  readonly slug: string;
  readonly displayName: string;
  readonly timezone: string;
  readonly status: OperatingUnitStatus;
  readonly addressLine: string | null;
  readonly district: string | null;
  readonly city: string | null;
  readonly landmark: string | null;
  readonly contactPhone: string | null;
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly coordinateSource: string;
}

export interface CreateLocationRequest {
  readonly code: string;
  readonly slug: string;
  readonly displayName: string;
  readonly timezone: string;
}

/** LegalEntityController.LegalEntityView. */
export interface LegalEntityView {
  readonly id: string;
  readonly code: string;
  readonly legalName: string;
  readonly shortName: string | null;
  readonly tin: string;
  readonly vatRegistered: boolean;
  readonly vatCertificateReference: string | null;
  readonly taxProfileId: string | null;
  readonly registeredAddress: string | null;
  readonly contactPhone: string | null;
  readonly status: OperatingUnitStatus;
  readonly version: number;
}

export interface RegisterLegalEntityRequest {
  readonly code: string;
  readonly legalName: string;
  readonly shortName?: string;
  readonly tin: string;
  readonly vatRegistered: boolean;
  readonly vatCertificateReference?: string;
  readonly taxProfileId?: string;
  readonly registeredAddress?: string;
  readonly contactPhone?: string;
}

export interface LocationFiscalAssignmentView {
  readonly id: string;
  readonly brandId: string;
  readonly locationId: string;
  readonly legalEntityId: string;
  readonly effectiveFrom: string;
  readonly effectiveUntil: string | null;
  readonly approvedBy: string;
  readonly approvalReference: string | null;
  readonly version: number;
}

export interface AssignLocationRequest {
  readonly brandId: string;
  readonly locationId: string;
  readonly effectiveFrom: string;
  readonly approvalReference?: string;
}

/** OnboardingController.RunView / RunSummary / StepView. */
export interface OnboardingRunSummary {
  readonly id: string;
  readonly status: string;
  readonly currentPhase: string;
  readonly startedBy: string;
  readonly lastError: string | null;
}

export interface OnboardingStepView {
  readonly stepKey: string;
  readonly phase: string;
  readonly status: string;
  readonly required: boolean;
  readonly attemptCount: number;
  readonly errorCode: string | null;
  readonly detail: string | null;
  readonly externalReference: string | null;
}

export interface OnboardingRunView {
  readonly run: OnboardingRunSummary;
  readonly steps: readonly OnboardingStepView[];
  readonly outstandingRequired: readonly string[];
}

/** OnboardingService.ActivationOutcome. */
export interface ActivationOutcome {
  readonly activated: boolean;
  readonly outcome: 'ACTIVATED' | 'AWAITING_APPROVAL' | 'NOT_READY' | 'READINESS_INCOMPLETE' | (string & {});
  readonly outstandingRequired: readonly string[];
  readonly approvalRequestId: string | null;
}

/**
 * Every call the tenant screens (IA 2.1-2.5, 2.8) make, composed in one place
 * the way `IntegrationsApi` composed the wave-25 integrations screens.
 *
 * Unlike that service, none of these calls read `activeTenantId` from the
 * session: a platform-admin browsing the tenant directory is not signed in
 * *as* a tenant, and every method here takes the tenant id it acts on
 * explicitly, from the route.
 */
@Injectable({ providedIn: 'root' })
export class TenantsApi {
  private readonly api = inject(ApiClient);

  async listTenants(cursor: string | null = null, limit = 50): Promise<Page<TenantSummaryView>> {
    return firstValueFrom(
      this.api.getPage<TenantSummaryView>('/api/v1/control-plane/tenants', { cursor, limit }),
    );
  }

  async createTenant(request: CreateTenantRequest): Promise<TenantView> {
    return firstValueFrom(this.api.post<TenantView>('/api/v1/control-plane/tenants', request));
  }

  async getTenant(tenantId: string): Promise<TenantView> {
    return firstValueFrom(this.api.get<TenantView>(`/api/v1/control-plane/tenants/${tenantId}`));
  }

  async getBrands(tenantId: string): Promise<BrandView[]> {
    return firstValueFrom(
      this.api.get<BrandView[]>(`/api/v1/control-plane/tenants/${tenantId}/brands`),
    );
  }

  async createBrand(tenantId: string, request: CreateOperatingUnitRequest): Promise<BrandView> {
    return firstValueFrom(
      this.api.post<BrandView>(`/api/v1/control-plane/tenants/${tenantId}/brands`, request),
    );
  }

  async activateBrand(tenantId: string, brandId: string): Promise<BrandView> {
    return firstValueFrom(
      this.api.post<BrandView>(
        `/api/v1/control-plane/tenants/${tenantId}/brands/${brandId}/activate`,
        {},
      ),
    );
  }

  async getLocations(tenantId: string, brandId: string): Promise<LocationView[]> {
    return firstValueFrom(
      this.api.get<LocationView[]>(
        `/api/v1/control-plane/tenants/${tenantId}/brands/${brandId}/locations`,
      ),
    );
  }

  async createLocation(
    tenantId: string,
    brandId: string,
    request: CreateLocationRequest,
  ): Promise<LocationView> {
    return firstValueFrom(
      this.api.post<LocationView>(
        `/api/v1/control-plane/tenants/${tenantId}/brands/${brandId}/locations`,
        request,
      ),
    );
  }

  async activateLocation(tenantId: string, brandId: string, locationId: string): Promise<LocationView> {
    return firstValueFrom(
      this.api.post<LocationView>(
        `/api/v1/control-plane/tenants/${tenantId}/brands/${brandId}/locations/${locationId}/activate`,
        {},
      ),
    );
  }

  async getLegalEntities(tenantId: string): Promise<LegalEntityView[]> {
    return firstValueFrom(
      this.api.get<LegalEntityView[]>(
        `/api/v1/control-plane/tenants/${tenantId}/legal-entities`,
      ),
    );
  }

  async registerLegalEntity(
    tenantId: string,
    request: RegisterLegalEntityRequest,
  ): Promise<LegalEntityView> {
    return firstValueFrom(
      this.api.post<LegalEntityView>(
        `/api/v1/control-plane/tenants/${tenantId}/legal-entities`,
        request,
      ),
    );
  }

  async activateLegalEntity(
    tenantId: string,
    entityId: string,
    expectedVersion: number,
  ): Promise<LegalEntityView> {
    return firstValueFrom(
      this.api.post<LegalEntityView>(
        `/api/v1/control-plane/tenants/${tenantId}/legal-entities/${entityId}/activate`,
        {},
        { query: { expectedVersion } },
      ),
    );
  }

  async assignLegalEntity(
    tenantId: string,
    entityId: string,
    request: AssignLocationRequest,
  ): Promise<LocationFiscalAssignmentView> {
    return firstValueFrom(
      this.api.post<LocationFiscalAssignmentView>(
        `/api/v1/control-plane/tenants/${tenantId}/legal-entities/${entityId}/assignments`,
        request,
      ),
    );
  }

  async startOnboarding(
    tenantId: string,
    ownerEmail?: string,
    ownerSubjectId?: string,
  ): Promise<{ runId: string }> {
    return firstValueFrom(
      this.api.post<{ runId: string }>(
        `/api/v1/control-plane/tenants/${tenantId}/onboarding-runs`,
        { ownerEmail, ownerSubjectId },
      ),
    );
  }

  async currentOnboardingRun(tenantId: string): Promise<OnboardingRunView | null> {
    try {
      return await firstValueFrom(
        this.api.get<OnboardingRunView>(
          `/api/v1/control-plane/tenants/${tenantId}/onboarding-runs/current`,
        ),
      );
    } catch (error) {
      if ((error as { code?: string }).code === 'RESOURCE_NOT_FOUND') {
        return null;
      }
      throw error;
    }
  }

  async resumeOnboarding(
    tenantId: string,
    runId: string,
    reason: string,
  ): Promise<{ reopenedSteps: number }> {
    return firstValueFrom(
      this.api.post<{ reopenedSteps: number }>(
        `/api/v1/control-plane/tenants/${tenantId}/onboarding-runs/${runId}/resume`,
        { reason },
      ),
    );
  }

  async activateOnboarding(
    tenantId: string,
    runId: string,
    reason: string,
  ): Promise<ActivationOutcome> {
    return firstValueFrom(
      this.api.post<ActivationOutcome>(
        `/api/v1/control-plane/tenants/${tenantId}/onboarding-runs/${runId}/activate`,
        { reason },
      ),
    );
  }
}
