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
  /** The variant's category (batch 16); absent for a modifier option, the fee, and an older server. */
  readonly categoryId?: string | null;
  /** What the node already holds for its ИКПУ (batch 16); an unclassified node may hold some fields. */
  readonly mxikCode?: string | null;
  /** What the node already holds for its package code (batch 16). */
  readonly packageCode?: string | null;
}

/**
 * Mirrors `CatalogQueryController.CategoryDefaultResponse`: the pair of codes
 * most of a category's classified dishes carry together. Derived, not stored —
 * there is no «category default» setting to edit.
 */
export interface FiscalCategoryDefault {
  readonly categoryId: string;
  readonly categoryName: string | null;
  readonly mxikCode: string;
  readonly packageCode: string;
  /** How many of the category's classified dishes carry exactly this pair. */
  readonly agreeingCount: number;
  /** How many classified dishes the category has. */
  readonly sampleSize: number;
}

/** Mirrors `CatalogQueryController.FiscalCoverageResponse`. */
export interface FiscalCoverageSummary {
  readonly totalNodes: number;
  readonly unclassifiedCount: number;
  readonly nodes: readonly FiscalCoverageNode[];
  /** Absent on an older server, which then offers no «copy category default». */
  readonly categoryDefaults?: readonly FiscalCategoryDefault[];
}

/** One row of a backfill batch: the dish or modifier option, and only the codes to write. */
export interface FiscalBackfillItem {
  /** Which kind of priceable node `nodeId` is: the platform keys a classification by both. */
  readonly nodeType: 'VARIANT' | 'MODIFIER_OPTION';
  readonly nodeId: string;
  readonly mxikCode?: string;
  readonly packageCode?: string;
}

/** Mirrors `CatalogAuthoringService.BulkClassifyStatus`. */
export type FiscalBackfillStatus =
  'CLASSIFIED' | 'UNCHANGED' | 'SKIPPED_EMPTY' | 'NOT_FOUND' | 'CONFLICT';

/** Mirrors `CatalogAuthoringController.BulkClassifyOutcomeResponse`. */
export interface FiscalBackfillOutcome {
  readonly nodeType: 'VARIANT' | 'MODIFIER_OPTION' | 'FEE';
  readonly nodeId: string;
  readonly status: FiscalBackfillStatus;
}

interface BulkClassifyRequest {
  readonly mode: 'MERGE';
  readonly items: readonly {
    readonly nodeType: 'VARIANT' | 'MODIFIER_OPTION';
    readonly nodeId: string;
    readonly fiscal: { readonly mxikCode?: string; readonly packageCode?: string };
  }[];
}

interface BulkClassifyResponse {
  readonly outcomes: readonly FiscalBackfillOutcome[];
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
   * Writes ИКПУ and package codes for a batch of dishes and modifier options through
   * `CatalogAuthoringController.bulkClassify` in `MERGE` mode (gap map row
   * 10.7c): a code a row supplies fills the node's gap and a code it omits keeps
   * the stored one, so completing a half-classified dish cannot blank the unit
   * or fiscal name someone entered earlier. A code that differs from one the
   * dish already holds is never written: that row comes back `CONFLICT` and
   * nothing is written for it. One outcome per row, in the order sent; a bad
   * row does not fail the others. The caller batches —
   * one call is one intent, and one `Idempotency-Key`.
   */
  async backfillCodes(
    scope: LocationScope,
    items: readonly FiscalBackfillItem[],
  ): Promise<readonly FiscalBackfillOutcome[]> {
    const result = await firstValueFrom(
      this.api.put<BulkClassifyRequest, BulkClassifyResponse>(
        settingsPaths.catalogBulkFiscalClassification(scope),
        command({
          mode: 'MERGE',
          items: items.map((item) => ({
            nodeType: item.nodeType,
            nodeId: item.nodeId,
            fiscal: {
              ...(item.mxikCode ? { mxikCode: item.mxikCode } : {}),
              ...(item.packageCode ? { packageCode: item.packageCode } : {}),
            },
          })),
        }),
      ),
    );
    return result.outcomes;
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
