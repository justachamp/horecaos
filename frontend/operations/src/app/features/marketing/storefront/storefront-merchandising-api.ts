import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { contentPaths } from '../../../core/api/content-paths';
import { parseETag } from '../../../core/api/aggregate-version';
import { command } from '../../../core/api/idempotency';

/** Mirrors `BrandSwitcherService.SwitcherEntryView`. `version` is the ETag every change presents in If-Match. */
export interface SwitcherEntry {
  readonly id: string;
  readonly targetBrandId: string;
  readonly targetBrandName: string;
  readonly channelId: string;
  readonly channelCode: string | null;
  /** The target website's verified hostname, read on every request; null while it has none. */
  readonly hostname: string | null;
  readonly sortKey: number;
  readonly state: 'ACTIVE' | 'HIDDEN';
  /** Whether a visitor would see this entry right now. */
  readonly shown: boolean;
  readonly version: number;
}

/** Mirrors `BrandSwitcherService.SwitcherCandidateView`: a sibling brand and one of its website channels. */
export interface SwitcherCandidate {
  readonly brandId: string;
  readonly brandName: string;
  readonly channelId: string;
  readonly channelCode: string;
  readonly hostname: string | null;
}

/** Mirrors `StorefrontBranchOrderController.StorefrontBranchView`. */
export interface OrderedBranch {
  readonly locationId: string;
  readonly displayName: string;
  readonly status: string;
  /** Null for a branch nobody has placed: the storefront lists those last, by name. */
  readonly storefrontSortOrder: number | null;
}

/** The brand's branches in storefront order, with the version of that order the next save must present. */
export interface BranchOrder {
  readonly branches: readonly OrderedBranch[];
  /** Null only when the platform sent no validator; a save then has nothing to present and is refused. */
  readonly version: number | null;
}

/**
 * Storefront merchandising beyond the home screen (gap-map row `6.8`; ADR 0157 Release 4, built by
 * ADR 0183 D17): the brand switcher, and the order the storefront lists the brand's branches in.
 * The category layout is a content policy and lives in `ContentApi`.
 */
@Injectable({ providedIn: 'root' })
export class StorefrontMerchandisingApi {
  private readonly api = inject(ApiClient);

  async switcherEntries(scope: BrandScope): Promise<readonly SwitcherEntry[]> {
    return (
      await firstValueFrom(
        this.api.get<readonly SwitcherEntry[]>(contentPaths.switcherEntries(scope)),
      )
    ).value;
  }

  async switcherCandidates(scope: BrandScope): Promise<readonly SwitcherCandidate[]> {
    return (
      await firstValueFrom(
        this.api.get<readonly SwitcherCandidate[]>(contentPaths.switcherCandidates(scope)),
      )
    ).value;
  }

  addSwitcherEntry(
    scope: BrandScope,
    targetBrandId: string,
    channelId: string,
  ): Promise<SwitcherEntry> {
    return firstValueFrom(
      this.api.post<{ targetBrandId: string; channelId: string }, SwitcherEntry>(
        contentPaths.switcherEntries(scope),
        command({ targetBrandId, channelId }),
      ),
    );
  }

  setSwitcherEntryState(
    scope: BrandScope,
    entry: SwitcherEntry,
    state: SwitcherEntry['state'],
  ): Promise<SwitcherEntry> {
    return firstValueFrom(
      this.api.put<{ state: string }, SwitcherEntry>(
        contentPaths.switcherEntry(scope, entry.id),
        command({ state }),
        { expectedVersion: entry.version },
      ),
    );
  }

  async removeSwitcherEntry(scope: BrandScope, entryId: string): Promise<void> {
    await firstValueFrom(
      this.api.send<null, void>(
        'DELETE',
        contentPaths.switcherEntry(scope, entryId),
        command(null),
      ),
    );
  }

  reorderSwitcher(scope: BrandScope, ids: readonly string[]): Promise<readonly SwitcherEntry[]> {
    return firstValueFrom(
      this.api.put<{ ids: readonly string[] }, readonly SwitcherEntry[]>(
        contentPaths.switcherOrder(scope),
        command({ ids }),
      ),
    );
  }

  async branches(scope: BrandScope): Promise<BranchOrder> {
    const read = await firstValueFrom(
      this.api.get<readonly OrderedBranch[]>(contentPaths.branchOrder(scope)),
    );
    return { branches: read.value, version: read.version };
  }

  /**
   * Replaces the order with the branches named, first to last; any branch left out becomes unplaced.
   *
   * `expectedVersion` is the version the order was read at. The write replaces the whole order, so a console that
   * saved without it would silently unplace a branch a colleague placed meanwhile; the platform refuses with
   * `STALE_VERSION` instead.
   */
  async setBranchOrder(
    scope: BrandScope,
    locationIds: readonly string[],
    expectedVersion: number,
  ): Promise<BranchOrder> {
    const response = await firstValueFrom(
      this.api.send<{ locationIds: readonly string[] }, readonly OrderedBranch[]>(
        'PUT',
        contentPaths.branchOrder(scope),
        command({ locationIds }),
        { expectedVersion },
      ),
    );
    return {
      branches: response.body ?? [],
      version: parseETag(response.headers.get('ETag')),
    };
  }
}
