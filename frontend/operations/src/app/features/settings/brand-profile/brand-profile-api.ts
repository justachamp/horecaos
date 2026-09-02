import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';

/** Mirrors uz.horecaos.platform.tenancy.application.TenantControlPlaneService.BrandView. */
export interface BrandView {
  readonly id: string;
  readonly tenantId: string;
  readonly code: string;
  readonly slug: string;
  readonly displayName: string;
  readonly status: 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';
}

/**
 * 10.1 Brand profile's read (`OperationsBrandController`, added in wave 26 —
 * see this app's `docs/adr/partial/0065-*.md`). No write method exists here
 * on purpose: renaming a brand, replacing its logo, and every other field
 * `docs/operations-spec/settings.md` §10.1 lists has no backend yet (ADR
 * 0002/0010 own the gap), so this screen reads only, and the page itself
 * says so rather than rendering fields it cannot save.
 */
@Injectable({ providedIn: 'root' })
export class BrandProfileApi {
  private readonly api = inject(ApiClient);

  async getBrand(scope: LocationScope): Promise<BrandView> {
    const result = await firstValueFrom(this.api.get<BrandView>(settingsPaths.brand(scope)));
    return result.value;
  }
}
