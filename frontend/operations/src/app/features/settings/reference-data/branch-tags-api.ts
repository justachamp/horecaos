import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { settingsPaths } from '../../../core/api/settings-paths';

/** Mirrors uz.horecaos.platform.tenancy.web.BranchTagController.TagResponse. */
export interface BranchTag {
  readonly tagId: string;
  readonly code: string;
  readonly displayName: string;
  readonly status: 'ACTIVE' | 'ARCHIVED';
}

/** Mirrors BranchTagController.AssignmentResponse — one (branch, tag) pair. */
export interface BranchTagAssignment {
  readonly locationId: string;
  readonly tagId: string;
  readonly assignedAt: string;
}

/**
 * 10.10d — `BranchTagController`: a chain's own tag registry, and which
 * branch carries which tag. No table, no endpoint, no screen before this
 * wave; Delever's own page ships empty too, per settings.md.
 */
@Injectable({ providedIn: 'root' })
export class BranchTagsApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope, activeOnly = true): Promise<readonly BranchTag[]> {
    const result = await firstValueFrom(
      this.api.get<readonly BranchTag[]>(settingsPaths.branchTags(scope), {
        params: { activeOnly },
      }),
    );
    return result.value ?? [];
  }

  /** Every branch's tags, tenant-wide — the filter-and-group read. */
  async assignments(scope: LocationScope): Promise<readonly BranchTagAssignment[]> {
    const result = await firstValueFrom(
      this.api.get<readonly BranchTagAssignment[]>(settingsPaths.branchTagAssignments(scope)),
    );
    return result.value ?? [];
  }

  async create(
    scope: LocationScope,
    code: string,
    displayName: string,
    reason: string,
  ): Promise<string> {
    const response = await firstValueFrom(
      this.api.post<{ code: string; displayName: string; reason: string }, { tagId: string }>(
        settingsPaths.branchTags(scope),
        command({ code, displayName, reason }),
      ),
    );
    return response.tagId;
  }

  async archive(scope: LocationScope, tagId: string, reason: string): Promise<void> {
    await firstValueFrom(
      this.api.post<{ reason: string }, void>(
        settingsPaths.branchTagArchive(scope, tagId),
        command({ reason }),
      ),
    );
  }

  /** One branch's own tags. */
  async tagsOfLocation(scope: LocationScope): Promise<readonly string[]> {
    const result = await firstValueFrom(
      this.api.get<readonly string[]>(settingsPaths.locationBranchTags(scope)),
    );
    return result.value ?? [];
  }

  /** Replaces the whole tag set for one branch. */
  async setTagsOfLocation(
    scope: LocationScope,
    tagIds: readonly string[],
    reason: string,
  ): Promise<void> {
    await firstValueFrom(
      this.api.put<{ tagIds: readonly string[]; reason: string }, void>(
        settingsPaths.locationBranchTags(scope),
        command({ tagIds, reason }),
      ),
    );
  }
}
