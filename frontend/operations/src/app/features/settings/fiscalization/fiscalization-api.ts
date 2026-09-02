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

/**
 * 10.7 Fiscalization, Tab 1 (`LegalEntityController`, ADR 0038). Cross-surface
 * — see `settings-paths.ts`'s own doc comment.
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
}
