import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope } from '../../core/api/catalog-paths';
import { catalogPaths } from '../../core/api/catalog-paths';
import { command } from '../../core/api/idempotency';

/**
 * Row 4.4a — the named {@code Menu} entity (`MenuController`): a copyable,
 * bindable assortment, distinct from {@code CatalogApi}'s own `menus-page.ts`
 * per-location offering matrix (row 4.4).
 */

/** Mirrors `MenuController.MenuResponse`. */
export interface MenuSetSummary {
  readonly menuId: string;
  readonly name: string;
  /** `DRAFT`, `ACTIVE` or `ARCHIVED`. */
  readonly status: string;
  readonly version: number;
}

/** Mirrors `MenuController.MenuItemResponse`. */
export interface MenuSetItem {
  readonly variantId: string;
  readonly productId: string;
  readonly productName: string;
  readonly sku: string | null;
  readonly sortOrder: number;
  /** `AVAILABLE`, `UNAVAILABLE` or `HIDDEN`. */
  readonly availabilityDefault: string;
  readonly version: number;
}

/** Mirrors `MenuController.BranchMenuBindingResponse`. */
export interface MenuSetBinding {
  readonly locationId: string;
  readonly locationName: string;
  /** Null means this row is the branch's default binding, across every channel. */
  readonly channelId: string | null;
  readonly channelCode: string | null;
  readonly menuId: string;
  readonly menuName: string;
  readonly version: number;
}

export interface UpdateMenuSetRequest {
  readonly name: string;
  readonly status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED';
  readonly expectedVersion: number;
}

export interface BulkAddByFilterRequest {
  readonly categoryId?: string | null;
  readonly search?: string | null;
  readonly availabilityDefault?: 'AVAILABLE' | 'UNAVAILABLE' | 'HIDDEN' | null;
  readonly locale?: string | null;
}

/** The operations console's own client for `/catalog/menu-sets`. */
@Injectable({ providedIn: 'root' })
export class MenuSetsApi {
  private readonly api = inject(ApiClient);

  async list(scope: BrandScope): Promise<readonly MenuSetSummary[]> {
    const result = await firstValueFrom(
      this.api.get<readonly MenuSetSummary[]>(catalogPaths.menuSets(scope)),
    );
    return result.value ?? [];
  }

  /** Refused (409) when a menu of this name already exists for the brand. */
  create(scope: BrandScope, name: string): Promise<MenuSetSummary> {
    return firstValueFrom(
      this.api.post<{ name: string }, MenuSetSummary>(
        catalogPaths.menuSets(scope),
        command({ name }),
      ),
    );
  }

  update(scope: BrandScope, menuId: string, body: UpdateMenuSetRequest): Promise<MenuSetSummary> {
    return firstValueFrom(
      this.api.put<UpdateMenuSetRequest, MenuSetSummary>(
        catalogPaths.menuSet(scope, menuId),
        command(body),
      ),
    );
  }

  /** A new menu, in DRAFT, carrying the source's own membership. The name must differ from every other menu on the brand. */
  copy(scope: BrandScope, sourceMenuId: string, name: string): Promise<MenuSetSummary> {
    return firstValueFrom(
      this.api.post<{ name: string }, MenuSetSummary>(
        catalogPaths.copyMenuSet(scope, sourceMenuId),
        command({ name }),
      ),
    );
  }

  async items(scope: BrandScope, menuId: string): Promise<readonly MenuSetItem[]> {
    const result = await firstValueFrom(
      this.api.get<readonly MenuSetItem[]>(catalogPaths.menuSetItems(scope, menuId)),
    );
    return result.value ?? [];
  }

  /** Adds a variant, or re-sorts/re-defaults it if already on the menu — never a second row. */
  async addItem(
    scope: BrandScope,
    menuId: string,
    variantId: string,
    sortOrder: number,
    availabilityDefault: 'AVAILABLE' | 'UNAVAILABLE' | 'HIDDEN',
  ): Promise<void> {
    await firstValueFrom(
      this.api.send<{ sortOrder: number; availabilityDefault: string }, void>(
        'PUT',
        catalogPaths.menuSetItem(scope, menuId, variantId),
        command({ sortOrder, availabilityDefault }),
      ),
    );
  }

  /** Idempotent — removing a variant already off the menu still resolves. */
  async removeItem(scope: BrandScope, menuId: string, variantId: string): Promise<void> {
    await firstValueFrom(
      this.api.send<null, void>(
        'DELETE',
        catalogPaths.menuSetItem(scope, menuId, variantId),
        command(null),
      ),
    );
  }

  /** The filtered select-all gesture. Returns how many variants were added or re-defaulted. */
  addByFilter(
    scope: BrandScope,
    menuId: string,
    request: BulkAddByFilterRequest,
  ): Promise<{ added: number }> {
    return firstValueFrom(
      this.api.post<BulkAddByFilterRequest, { added: number }>(
        catalogPaths.menuSetBulkAddByFilter(scope, menuId),
        command(request),
      ),
    );
  }

  async bindings(scope: BrandScope): Promise<readonly MenuSetBinding[]> {
    const result = await firstValueFrom(
      this.api.get<readonly MenuSetBinding[]>(catalogPaths.menuSetBindings(scope)),
    );
    return result.value ?? [];
  }

  /** `channelId` null binds the branch's default, across every channel; naming one overrides that channel alone. Refused for an archived menu. */
  async bind(
    scope: BrandScope,
    locationId: string,
    menuId: string,
    channelId: string | null,
  ): Promise<void> {
    await firstValueFrom(
      this.api.send<{ menuId: string; channelId: string | null }, void>(
        'PUT',
        catalogPaths.menuSetBinding(scope, locationId),
        command({ menuId, channelId }),
      ),
    );
  }

  /** Idempotent. `channelId` omitted clears the branch's default binding. */
  async unbind(scope: BrandScope, locationId: string, channelId: string | null): Promise<void> {
    await firstValueFrom(
      this.api.send<null, void>(
        'DELETE',
        catalogPaths.menuSetBinding(scope, locationId),
        command(null),
        {
          params: channelId ? { channelId } : {},
        },
      ),
    );
  }
}
