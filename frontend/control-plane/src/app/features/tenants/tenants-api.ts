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
  readonly countryCode: string;
  readonly businessType: string;
}

/** GET /control-plane/tenant-plans -- a tenant's live subscription, by plan. */
export interface TenantPlanView {
  readonly tenantId: string;
  readonly planCode: string;
  readonly planVersionNumber: number;
  readonly status: string;
}

/** GET /control-plane/tenant-health -- a tenant's open problems, by kind. */
export interface TenantHealthView {
  readonly tenantId: string;
  readonly deadLetters: number;
  readonly blockedReceipts: number;
  readonly posOrdersAwaiting: number;
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
  /** Sent back as `If-Match` when correcting or deleting (ADR 0031). */
  readonly version: number;
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
  /** Sent back as `If-Match` when correcting or deleting (ADR 0031). */
  readonly version: number;
}

/**
 * Where a location is and how to reach it, sent whole: moving a pin while
 * leaving a contradicting address behind it is the mistake a partial update
 * would allow. Omit `coordinateSource` to let the platform infer it.
 */
export interface DescribeLocationRequest {
  readonly addressLine: string | null;
  readonly district: string | null;
  readonly city: string | null;
  readonly landmark: string | null;
  readonly contactPhone: string | null;
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly coordinateSource?: 'OPERATOR_PIN';
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

/** OnboardingService.ValidationOutcome: every read-only check, run now. Nothing is written. */
export interface ValidationOutcome {
  readonly allPassed: boolean;
  readonly checks: readonly {
    readonly stepKey: string;
    readonly passed: boolean;
    readonly errorCode: string | null;
    readonly detail: string | null;
  }[];
}

/** OnboardingTemplateService.TemplateView. */
export interface OnboardingTemplateView {
  readonly id: string;
  readonly code: string;
  readonly version: number;
  readonly status: string;
  readonly description: string;
  readonly requiredSteps: readonly string[];
  /** The business types this version suits (ADR 0090); empty for the default. */
  readonly businessTypes: readonly string[];
}

/** OnboardingTemplateService.Suggestion: the template a new run is pre-set to, and why. */
export interface OnboardingTemplateSuggestion {
  readonly template: OnboardingTemplateView;
  readonly businessType: string | null;
  /** True when a template names the tenant's business type; false when the default was taken. */
  readonly matched: boolean;
}

/** The language an owner's invitation is written in. */
export type InvitationLocale = 'uz' | 'ru' | 'en';

/** OwnerInvitationService.OwnerInvitationView (ADR 0097). The address is masked by the server. */
export interface OwnerInvitationView {
  readonly state:
    'QUEUED' | 'SENT' | 'ACCEPTED' | 'NOT_NEEDED' | 'FAILED' | 'EXPIRED' | (string & {});
  readonly emailMasked: string | null;
  readonly locale: InvitationLocale | (string & {});
  readonly attempts: number;
  readonly lastErrorCode: string | null;
  readonly queuedAt: string;
  readonly sentAt: string | null;
  readonly openedAt: string | null;
  readonly acceptedAt: string | null;
  readonly expiresAt: string | null;
}

/** OnboardingService.ActivationOutcome. */
export interface ActivationOutcome {
  readonly activated: boolean;
  readonly outcome:
    'ACTIVATED' | 'AWAITING_APPROVAL' | 'NOT_READY' | 'READINESS_INCOMPLETE' | (string & {});
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

  /** Every tenant's live subscription; a tenant without one is absent. */
  async tenantPlans(): Promise<TenantPlanView[]> {
    return firstValueFrom(this.api.get<TenantPlanView[]>('/api/v1/control-plane/tenant-plans'));
  }

  /** Open problems per tenant; a tenant with none is absent. */
  async tenantHealth(): Promise<TenantHealthView[]> {
    return firstValueFrom(this.api.get<TenantHealthView[]>('/api/v1/control-plane/tenant-health'));
  }

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

  /** Platform admins only. A tenant's link is permanent: a different organization is refused. */
  async linkKeycloakOrganization(tenantId: string, organizationId: string): Promise<TenantView> {
    return firstValueFrom(
      this.api.put<TenantView>(
        `/api/v1/control-plane/tenants/${tenantId}/identity/keycloak-organization`,
        {
          organizationId,
        },
      ),
    );
  }

  /**
   * Platform admins only. Narrows everyone at the tenant to read-only access
   * and disables its sign-in organization; the reason goes to the audit log.
   */
  async suspendTenant(tenantId: string, reason: string): Promise<TenantView> {
    return firstValueFrom(
      this.api.post<TenantView>(`/api/v1/control-plane/tenants/${tenantId}/suspend`, { reason }),
    );
  }

  /** Platform admins only; only a SUSPENDED tenant can be reactivated. */
  async reactivateTenant(tenantId: string, reason: string): Promise<TenantView> {
    return firstValueFrom(
      this.api.post<TenantView>(`/api/v1/control-plane/tenants/${tenantId}/reactivate`, { reason }),
    );
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

  /**
   * The whole editable identity. The server refuses a changed code or slug
   * once the brand has left DRAFT, and refuses a stale `version`.
   */
  async reviseBrand(
    tenantId: string,
    brand: Pick<BrandView, 'id' | 'version'>,
    request: CreateOperatingUnitRequest,
  ): Promise<BrandView> {
    return firstValueFrom(
      this.api.put<BrandView>(
        `/api/v1/control-plane/tenants/${tenantId}/brands/${brand.id}`,
        request,
        {
          expectedVersion: brand.version,
        },
      ),
    );
  }

  /** Only a DRAFT with no locations, no scoped staff access, and nothing else referring to it. */
  async deleteBrand(tenantId: string, brand: Pick<BrandView, 'id' | 'version'>): Promise<void> {
    await firstValueFrom(
      this.api.delete<void>(`/api/v1/control-plane/tenants/${tenantId}/brands/${brand.id}`, null, {
        expectedVersion: brand.version,
      }),
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

  /** As {@link reviseBrand}; the timezone joins the code and slug in being fixed after DRAFT. */
  async reviseLocation(
    tenantId: string,
    location: Pick<LocationView, 'id' | 'brandId' | 'version'>,
    request: CreateLocationRequest,
  ): Promise<LocationView> {
    return firstValueFrom(
      this.api.put<LocationView>(
        `/api/v1/control-plane/tenants/${tenantId}/brands/${location.brandId}/locations/${location.id}`,
        request,
        { expectedVersion: location.version },
      ),
    );
  }

  async describeLocation(
    tenantId: string,
    location: Pick<LocationView, 'id' | 'brandId'>,
    request: DescribeLocationRequest,
  ): Promise<LocationView> {
    return firstValueFrom(
      this.api.put<LocationView>(
        `/api/v1/control-plane/tenants/${tenantId}/brands/${location.brandId}/locations/${location.id}/place`,
        request,
      ),
    );
  }

  async deleteLocation(
    tenantId: string,
    location: Pick<LocationView, 'id' | 'brandId' | 'version'>,
  ): Promise<void> {
    await firstValueFrom(
      this.api.delete<void>(
        `/api/v1/control-plane/tenants/${tenantId}/brands/${location.brandId}/locations/${location.id}`,
        null,
        { expectedVersion: location.version },
      ),
    );
  }

  async activateLocation(
    tenantId: string,
    brandId: string,
    locationId: string,
  ): Promise<LocationView> {
    return firstValueFrom(
      this.api.post<LocationView>(
        `/api/v1/control-plane/tenants/${tenantId}/brands/${brandId}/locations/${locationId}/activate`,
        {},
      ),
    );
  }

  /** Most recent first; an open-ended one (no `effectiveUntil`) is the current seller. */
  async getLocationAssignments(
    tenantId: string,
    brandId: string,
    locationId: string,
  ): Promise<LocationFiscalAssignmentView[]> {
    return firstValueFrom(
      this.api.get<LocationFiscalAssignmentView[]>(
        `/api/v1/control-plane/tenants/${tenantId}/legal-entities/brands/${brandId}/locations/${locationId}/assignments`,
      ),
    );
  }

  async getLegalEntities(tenantId: string): Promise<LegalEntityView[]> {
    return firstValueFrom(
      this.api.get<LegalEntityView[]>(`/api/v1/control-plane/tenants/${tenantId}/legal-entities`),
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
    templateId?: string,
    ownerLocale?: InvitationLocale,
  ): Promise<{ runId: string }> {
    return firstValueFrom(
      this.api.post<{ runId: string }>(
        `/api/v1/control-plane/tenants/${tenantId}/onboarding-runs`,
        { ownerEmail, ownerSubjectId, templateId, ownerLocale },
      ),
    );
  }

  /** The owner's invitation (ADR 0097), or null when onboarding has not queued one. */
  async ownerInvitation(tenantId: string): Promise<OwnerInvitationView | null> {
    try {
      return await firstValueFrom(
        this.api.get<OwnerInvitationView>(
          `/api/v1/control-plane/tenants/${tenantId}/owner-invitation`,
        ),
      );
    } catch (error) {
      if ((error as { code?: string }).code === 'RESOURCE_NOT_FOUND') {
        return null;
      }
      throw error;
    }
  }

  /** Sends it again with a new link; the one already sent stops working. */
  async resendOwnerInvitation(
    tenantId: string,
    reason: string,
    locale?: InvitationLocale,
  ): Promise<void> {
    await firstValueFrom(
      this.api.post<unknown>(`/api/v1/control-plane/tenants/${tenantId}/owner-invitation/resend`, {
        reason,
        locale,
      }),
    );
  }

  /** The template a new run for this tenant would start under (ADR 0090). */
  async suggestedOnboardingTemplate(tenantId: string): Promise<OnboardingTemplateSuggestion> {
    return firstValueFrom(
      this.api.get<OnboardingTemplateSuggestion>(
        `/api/v1/control-plane/tenants/${tenantId}/onboarding-runs/suggested-template`,
      ),
    );
  }

  /** Every template version; needs platform scope. */
  async onboardingTemplates(): Promise<OnboardingTemplateView[]> {
    return firstValueFrom(
      this.api.get<OnboardingTemplateView[]>('/api/v1/control-plane/onboarding-templates'),
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

  /** A fresh idempotency key per call, so every press is a fresh answer rather than a replay. */
  async validateOnboarding(tenantId: string, runId: string): Promise<ValidationOutcome> {
    return firstValueFrom(
      this.api.post<ValidationOutcome>(
        `/api/v1/control-plane/tenants/${tenantId}/onboarding-runs/${runId}/validate`,
        {},
      ),
    );
  }

  /** Refused once the run is ACTIVE or FAILED: a failed run is resumed, never abandoned. */
  async cancelOnboarding(tenantId: string, runId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<unknown>(
        `/api/v1/control-plane/tenants/${tenantId}/onboarding-runs/${runId}/cancel`,
        {
          reason,
        },
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
