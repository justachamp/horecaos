import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';

/** Mirrors uz.horecaos.platform.tenancy.web.LegalEntityController.LegalEntityView. */
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
  readonly status: 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';
  readonly version: number;
}

/** Mirrors uz.horecaos.platform.tenancy.web.LegalEntityController.LocationFiscalAssignmentView. */
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

/** Everything `OperationsLegalEntityController.update` may correct. No `tin` — see its own doc. */
export interface UpdateLegalEntityRequest {
  readonly legalName: string;
  readonly shortName?: string;
  readonly vatRegistered: boolean;
  readonly vatCertificateReference?: string;
  readonly taxProfileId?: string;
  readonly registeredAddress?: string;
  readonly contactPhone?: string;
}

// ------------------------------------------------- 10.7 Fiscal terminals (Tab 2)

export type FiscalTerminalKind = 'POS' | 'COURIER_TERMINAL' | 'KIOSK' | 'VIRTUAL';
export type FiscalTerminalStatus = 'ACTIVE' | 'SUSPENDED' | 'RETIRED';
export type FiscalTerminalHealth = 'HEALTHY' | 'UNHEALTHY';

/** Mirrors `OperationsFiscalTerminalController.FiscalTerminalView` (ADR 0038 lines 503-513). */
export interface FiscalTerminalView {
  readonly id: string;
  readonly brandId: string;
  readonly locationId: string;
  readonly legalEntityId: string;
  readonly kind: FiscalTerminalKind;
  readonly providerBindingId: string | null;
  readonly terminalReference: string;
  readonly capabilitySnapshot: Readonly<Record<string, boolean>>;
  readonly capable: boolean;
  readonly status: FiscalTerminalStatus;
  readonly lastHealthCheckAt: string | null;
  readonly lastHealthStatus: FiscalTerminalHealth | null;
  readonly version: number;
}

export interface RegisterFiscalTerminalRequest {
  readonly locationId: string;
  readonly legalEntityId: string;
  readonly kind: FiscalTerminalKind;
  readonly providerBindingId?: string;
  readonly terminalReference: string;
  readonly capabilitySnapshot?: Readonly<Record<string, boolean>>;
}

/** The one capability code `responsibilityOf` and this screen both read. */
export const ISSUE_FISCAL_RECEIPT = 'IssueFiscalReceipt';

// ------------------------------------------------- 10.7 Fiscal coverage (Tab 3)

/** Mirrors `CatalogQueryController.FiscalCoverageNodeResponse`. */
export interface FiscalCoverageNode {
  readonly nodeType: 'VARIANT' | 'MODIFIER_OPTION' | 'FEE';
  readonly nodeId: string;
  readonly name: string | null;
  readonly categoryName: string | null;
  readonly locationCount: number;
}

/** Mirrors `CatalogQueryController.FiscalCoverageResponse`. */
export interface FiscalCoverageSummary {
  readonly totalNodes: number;
  readonly unclassifiedCount: number;
  readonly nodes: readonly FiscalCoverageNode[];
}

/**
 * Mirrors `CatalogAuthoringController.FiscalClassificationRequest` — trimmed
 * to the two fields Tab 3 asks the delivery fee for by name (§10.7: "the
 * delivery-fee node's own ИКПУ and the marking control"). Every other field
 * on that record stays whatever it already was: this is a `PUT`-replace on
 * the platform, but the product editor (Catalog 4.2) owns the rest of a fee's
 * classification and this screen is deliberately not a second place that
 * edits it.
 */
export interface ClassifyDeliveryFeeRequest {
  readonly mxikCode?: string;
  readonly packageCode?: string;
  readonly fiscalUnitCode?: number;
  readonly fiscalName?: string;
  readonly markingRequired: boolean;
}

/** `catalog.fees.code` for the tenant's one delivery charge (V0028). */
const DELIVERY_FEE_CODE = 'DELIVERY';

/**
 * 10.7 Fiscalization, all three tabs. Cross-surface for Tab 1 and Tab 3 — see
 * `settings-paths.ts`'s own doc comment; Tab 2 (`OperationsFiscalTerminalController`)
 * is operations-native, built this wave alongside the table it reads.
 */
@Injectable({ providedIn: 'root' })
export class FiscalizationApi {
  private readonly api = inject(ApiClient);

  async listLegalEntities(scope: LocationScope): Promise<readonly LegalEntityView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly LegalEntityView[]>(settingsPaths.legalEntities(scope)),
    );
    return result.value ?? [];
  }

  async registerLegalEntity(
    scope: LocationScope,
    request: RegisterLegalEntityRequest,
  ): Promise<LegalEntityView> {
    return firstValueFrom(
      this.api.post<RegisterLegalEntityRequest, LegalEntityView>(
        settingsPaths.legalEntities(scope),
        command(request),
      ),
    );
  }

  async activateLegalEntity(
    scope: LocationScope,
    entityId: string,
    expectedVersion: number,
  ): Promise<LegalEntityView> {
    return firstValueFrom(
      this.api.post<null, LegalEntityView>(
        settingsPaths.legalEntityActivate(scope, entityId),
        command(null),
        {
          params: { expectedVersion },
        },
      ),
    );
  }

  /**
   * Corrects a registered entity's own fields. There was no way to fix any of
   * these before wave P34 — a registered entity could never be corrected.
   */
  async updateLegalEntity(
    scope: LocationScope,
    entityId: string,
    request: UpdateLegalEntityRequest,
    expectedVersion: number,
  ): Promise<LegalEntityView> {
    return firstValueFrom(
      this.api.put<UpdateLegalEntityRequest, LegalEntityView>(
        settingsPaths.legalEntity(scope, entityId),
        command(request),
        { params: { expectedVersion } },
      ),
    );
  }

  /** Wave P34: there was no HTTP surface for this on either controller before. */
  async suspendLegalEntity(
    scope: LocationScope,
    entityId: string,
    expectedVersion: number,
  ): Promise<LegalEntityView> {
    return firstValueFrom(
      this.api.post<null, LegalEntityView>(
        settingsPaths.legalEntitySuspend(scope, entityId),
        command(null),
        { params: { expectedVersion } },
      ),
    );
  }

  /** Wave P34: there was no HTTP surface for this on either controller before. */
  async archiveLegalEntity(
    scope: LocationScope,
    entityId: string,
    expectedVersion: number,
  ): Promise<LegalEntityView> {
    return firstValueFrom(
      this.api.post<null, LegalEntityView>(
        settingsPaths.legalEntityArchive(scope, entityId),
        command(null),
        { params: { expectedVersion } },
      ),
    );
  }

  /** The current location's own assignment history, most recent first. */
  async assignmentHistory(scope: LocationScope): Promise<readonly LocationFiscalAssignmentView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly LocationFiscalAssignmentView[]>(
        settingsPaths.legalEntityAssignmentHistory(scope),
      ),
    );
    return result.value ?? [];
  }

  async assign(
    scope: LocationScope,
    entityId: string,
    effectiveFrom: string,
    approvalReference?: string,
  ): Promise<LocationFiscalAssignmentView> {
    return firstValueFrom(
      this.api.post<
        { brandId: string; locationId: string; effectiveFrom: string; approvalReference?: string },
        LocationFiscalAssignmentView
      >(
        settingsPaths.legalEntityAssign(scope, entityId),
        command({
          brandId: scope.brandId,
          locationId: scope.locationId,
          effectiveFrom,
          approvalReference,
        }),
      ),
    );
  }

  // ------------------------------------------------- 10.7 Fiscal terminals (Tab 2)

  async listFiscalTerminals(scope: LocationScope): Promise<readonly FiscalTerminalView[]> {
    const result = await firstValueFrom(
      this.api.get<readonly FiscalTerminalView[]>(settingsPaths.fiscalTerminals(scope)),
    );
    return result.value ?? [];
  }

  async registerFiscalTerminal(
    scope: LocationScope,
    request: RegisterFiscalTerminalRequest,
  ): Promise<FiscalTerminalView> {
    return firstValueFrom(
      this.api.post<RegisterFiscalTerminalRequest, FiscalTerminalView>(
        settingsPaths.fiscalTerminals(scope),
        command(request),
      ),
    );
  }

  /** Settings 10.7 Tab 2's «Проверить связь». */
  async checkFiscalTerminalHealth(
    scope: LocationScope,
    terminalId: string,
    outcome: FiscalTerminalHealth,
    expectedVersion: number,
  ): Promise<FiscalTerminalView> {
    return firstValueFrom(
      this.api.post<{ outcome: FiscalTerminalHealth }, FiscalTerminalView>(
        settingsPaths.fiscalTerminalHealthCheck(scope, terminalId),
        command({ outcome }),
        { params: { expectedVersion } },
      ),
    );
  }

  /** Settings 10.7 Tab 2's «Отключить». */
  async suspendFiscalTerminal(
    scope: LocationScope,
    terminalId: string,
    expectedVersion: number,
  ): Promise<FiscalTerminalView> {
    return firstValueFrom(
      this.api.post<null, FiscalTerminalView>(
        settingsPaths.fiscalTerminalSuspend(scope, terminalId),
        command(null),
        { params: { expectedVersion } },
      ),
    );
  }

  async reactivateFiscalTerminal(
    scope: LocationScope,
    terminalId: string,
    expectedVersion: number,
  ): Promise<FiscalTerminalView> {
    return firstValueFrom(
      this.api.post<null, FiscalTerminalView>(
        settingsPaths.fiscalTerminalReactivate(scope, terminalId),
        command(null),
        { params: { expectedVersion } },
      ),
    );
  }

  /** Permanent. Never deleted: a document already issued through it must still resolve who issued it. */
  async retireFiscalTerminal(
    scope: LocationScope,
    terminalId: string,
    expectedVersion: number,
  ): Promise<FiscalTerminalView> {
    return firstValueFrom(
      this.api.post<null, FiscalTerminalView>(
        settingsPaths.fiscalTerminalRetire(scope, terminalId),
        command(null),
        { params: { expectedVersion } },
      ),
    );
  }

  // ------------------------------------------------- 10.7 Fiscal coverage (Tab 3)

  /**
   * `CatalogQueryController.fiscalCoverage` — the minimum this wave builds
   * locally in place of P21's not-yet-merged fiscal workbench: a per-brand
   * unclassified count and node list, delivery fee first.
   */
  async fiscalCoverage(scope: LocationScope): Promise<FiscalCoverageSummary> {
    const result = await firstValueFrom(
      this.api.get<FiscalCoverageSummary>(settingsPaths.catalogFiscalCoverage(scope)),
    );
    return (
      result.value ?? {
        totalNodes: 0,
        unclassifiedCount: 0,
        nodes: [],
      }
    );
  }

  /**
   * Classifies the brand's one delivery-fee node — §10.7's own words, "the one
   * people forget". Reuses `CatalogAuthoringController.classifyFee`, built and
   * reachable from control-plane before this wave; nothing about the endpoint
   * itself is new.
   */
  async classifyDeliveryFee(
    scope: LocationScope,
    request: ClassifyDeliveryFeeRequest,
  ): Promise<void> {
    await firstValueFrom(
      this.api.put<ClassifyDeliveryFeeRequest, void>(
        settingsPaths.catalogFeeFiscalClassification(scope, DELIVERY_FEE_CODE),
        command(request),
      ),
    );
  }
}
