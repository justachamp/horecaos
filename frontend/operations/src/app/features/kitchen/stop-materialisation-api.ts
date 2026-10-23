import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';
import { LocationScope, operationsPaths } from '../../core/api/operations-paths';
import { Page } from '../../core/api/page';

/**
 * One materialisation run (ADR 0141, rollback switch three). Mirrors
 * `StopMaterialisationController.RunResponse`. `status` is `RUNNING`, `COMPLETED` or `FAILED`; only a
 * completed, acknowledged run lets the platform administrator switch stops off.
 */
export interface MaterialisationRun {
  readonly id: string;
  readonly status: string;
  readonly startedAt: string;
  readonly completedAt?: string | null;
  /** Stops in force the run looked at. */
  readonly stopsSeen: number;
  /** Stops it wrote onto a position (a dish set unavailable there). */
  readonly positionsWritten: number;
  readonly positionsAlreadyUnavailable: number;
  /** Report lines: stops it could not carry onto a position, failed ones included. */
  readonly notCarried: number;
  /** Stops whose write failed; each keeps the switch blocked until a later run carries it. */
  readonly failedStops: number;
  readonly acknowledged: boolean;
  readonly acknowledgedAt?: string | null;
  readonly version: number;
}

/**
 * One line of a run's report: a stop at a branch that did not land on a position, and why. Mirrors
 * `StopMaterialisationController.ReportLineResponse`; the names are resolved when the report is read
 * and are absent when the dish or the branch no longer resolves.
 */
export interface MaterialisationLine {
  readonly id: string;
  readonly stopId: string;
  readonly variantId: string;
  readonly variantName?: string | null;
  readonly scopeType: string;
  readonly source: string;
  readonly locationId?: string | null;
  readonly locationName?: string | null;
  readonly channelId?: string | null;
  readonly menuId?: string | null;
  readonly reasonCode: string;
}

/**
 * The stop-materialisation run's client: start a run, read the latest and its paged report, and
 * acknowledge it under the version the screen read. The routes are brand-scoped and need
 * `inventory.stop.manage` to write and `inventory.read` to read.
 */
@Injectable({ providedIn: 'root' })
export class StopMaterialisationApi {
  private readonly api = inject(ApiClient);

  /** The brand's newest run, or null when none was ever started. */
  async latest(scope: LocationScope): Promise<MaterialisationRun | null> {
    const result = await firstValueFrom(
      this.api.get<Page<MaterialisationRun>>(
        operationsPaths.inventoryStopMaterialisationRuns(scope),
        {
          params: { limit: '1' },
        },
      ),
    );
    return result.value?.items?.[0] ?? null;
  }

  /** Starts a run. It runs to completion inside the request, so the answer is the finished run. */
  start(scope: LocationScope): Promise<MaterialisationRun> {
    return firstValueFrom(
      this.api.post<null, MaterialisationRun>(
        operationsPaths.inventoryStopMaterialisationRuns(scope),
        command(null),
      ),
    );
  }

  async report(
    scope: LocationScope,
    runId: string,
    cursor: string | null,
  ): Promise<Page<MaterialisationLine>> {
    const params: Record<string, string> = { limit: '50' };
    if (cursor !== null) {
      params['cursor'] = cursor;
    }
    const result = await firstValueFrom(
      this.api.get<Page<MaterialisationLine>>(
        operationsPaths.inventoryStopMaterialisationReport(scope, runId),
        { params },
      ),
    );
    return result.value ?? { items: [], nextCursor: null };
  }

  /** Says the report was read and that those dishes will be on sale again once stops are switched off. */
  acknowledge(scope: LocationScope, run: MaterialisationRun): Promise<MaterialisationRun> {
    return firstValueFrom(
      this.api.post<null, MaterialisationRun>(
        operationsPaths.inventoryStopMaterialisationAcknowledgement(scope, run.id),
        command(null),
        { expectedVersion: run.version },
      ),
    );
  }
}
