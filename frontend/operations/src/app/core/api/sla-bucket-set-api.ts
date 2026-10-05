import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from './api-client';
import { reportsPaths } from './reports-paths';

/** Mirrors ReportingController.SlaBucketController.Bucket -- one bucket of the platform-fixed set. */
export interface SlaBucketDefinition {
  readonly code: string;
  readonly fromMinutes: number;
  readonly toMinutesExclusive: number | null;
}

/** Mirrors ReportingController.slaBucketSet's SlaBucketController.SlaBuckets. */
export interface SlaBucketSetView {
  readonly version: number;
  readonly buckets: readonly SlaBucketDefinition[];
}

/**
 * The SLA bucket set the reports are computed under (row `10.10c`, ADR 0043 and 0107):
 * platform-fixed and versioned, readable by a tenant, never editable by one.
 *
 * One reader for every screen that prints the version. The Settings card
 * (`reference-data-page`) and the branch and courier reports used to disagree about
 * where it came from: the card read this endpoint, the two reports printed a constant
 * (`slaBucketSetVersion = 1`) and would have gone on saying v1 the day the platform
 * published v2. Both now read the same URL, through this class, so they cannot
 * disagree.
 */
@Injectable({ providedIn: 'root' })
export class SlaBucketSetApi {
  private readonly api = inject(ApiClient);

  async get(tenantId: string): Promise<SlaBucketSetView> {
    const result = await firstValueFrom(
      this.api.get<SlaBucketSetView>(reportsPaths.slaBucketSet(tenantId)),
    );
    if (!result.value) {
      throw new Error('The SLA bucket set answered with no body');
    }
    return result.value;
  }
}
