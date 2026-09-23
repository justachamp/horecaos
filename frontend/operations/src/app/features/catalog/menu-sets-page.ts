import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { BrandScope } from '../../core/api/catalog-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { LocationScope } from '../../core/api/operations-paths';
import { LocationView, LocationsApi } from '../settings/locations/locations-api';
import { ChannelView, SalesChannelsApi } from '../settings/sales-channels/sales-channels-api';
import { describeApiError } from '../orders/order-errors';
import { CatalogApi } from './catalog-api';
import { CategorySummary, toCatalogLocale } from './catalog-domain';
import { MenuSetBinding, MenuSetItem, MenuSetSummary, MenuSetsApi } from './menu-sets-api';

/**
 * Row 4.4a — the named {@code Menu} entity: a brand-owned, copyable
 * assortment a chain authors once and binds to any number of branches.
 *
 * Deliberately its own screen at `/catalog/menu-sets`, not `/catalog/menus`
 * — that path already belongs to the per-location offering matrix (row 4.4,
 * `menus-page.ts`), an unrelated, already-built screen this wave must not
 * collide with.
 */
@Component({
  selector: 'q-menu-sets-page',
  imports: [TPipe],
  templateUrl: './menu-sets-page.html',
  styleUrl: './menu-sets-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MenuSetsPage implements OnInit {
  private readonly api = inject(MenuSetsApi);
  private readonly catalogApi = inject(CatalogApi);
  private readonly locationsApi = inject(LocationsApi);
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);

  protected readonly firstLoadComplete = signal(false);
  protected readonly denied = signal(false);
  protected readonly lastError = signal<string | null>(null);

  protected readonly menus = signal<readonly MenuSetSummary[]>([]);
  protected readonly bindings = signal<readonly MenuSetBinding[]>([]);
  protected readonly locations = signal<readonly LocationView[]>([]);
  protected readonly channels = signal<readonly ChannelView[]>([]);
  protected readonly categories = signal<readonly CategorySummary[]>([]);

  protected readonly selectedMenuId = signal<string | null>(null);
  protected readonly items = signal<readonly MenuSetItem[]>([]);
  protected readonly itemsLoading = signal(false);

  protected readonly selectedMenu = computed<MenuSetSummary | null>(
    () => this.menus().find((menu) => menu.menuId === this.selectedMenuId()) ?? null,
  );
  protected readonly selectedBindings = computed<readonly MenuSetBinding[]>(() =>
    this.bindings().filter((binding) => binding.menuId === this.selectedMenuId()),
  );

  // -------------------------------------------------------------- create

  protected readonly createOpen = signal(false);
  protected readonly createName = signal('');
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

  // ---------------------------------------------------------------- copy

  protected readonly copyTarget = signal<MenuSetSummary | null>(null);
  protected readonly copyName = signal('');
  protected readonly copying = signal(false);
  protected readonly copyError = signal<string | null>(null);

  // --------------------------------------------------------- add-by-filter

  protected readonly filterCategoryId = signal('');
  protected readonly filterSearch = signal('');
  protected readonly filterAvailability = signal<'AVAILABLE' | 'UNAVAILABLE' | 'HIDDEN'>(
    'AVAILABLE',
  );
  protected readonly addingByFilter = signal(false);
  protected readonly addByFilterResult = signal<number | null>(null);
  protected readonly addByFilterError = signal<string | null>(null);

  // -------------------------------------------------------------------- bind

  protected readonly bindLocationId = signal('');
  protected readonly bindChannelId = signal('');
  protected readonly binding = signal(false);
  protected readonly bindError = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.brand.ensureLoaded();
    const scope = this.brand.scope();
    if (!scope) {
      this.denied.set(this.brand.denied());
      this.firstLoadComplete.set(true);
      return;
    }
    try {
      const [menus, bindings, locations, channels, catalogs] = await Promise.all([
        this.api.list(scope),
        this.api.bindings(scope),
        this.locationsApi.list(this.brandWideLocationScope(scope)),
        this.channelsApi.list(this.brandWideLocationScope(scope)),
        firstValueFrom(this.catalogApi.listCatalogs(scope)),
      ]);
      this.menus.set(menus);
      this.bindings.set(bindings);
      this.locations.set(locations);
      this.channels.set(channels);
      const firstCatalogId = catalogs[0]?.catalogId ?? null;
      if (firstCatalogId) {
        this.categories.set(
          await firstValueFrom(this.catalogApi.listCategories(scope, firstCatalogId)),
        );
      }
      this.denied.set(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else if (error instanceof ApiError) {
        this.lastError.set(this.describe(error));
      } else {
        throw error;
      }
    } finally {
      this.firstLoadComplete.set(true);
    }
  }

  private async refreshMenus(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.menus.set(await this.api.list(scope));
  }

  private async refreshBindings(): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    this.bindings.set(await this.api.bindings(scope));
  }

  // -------------------------------------------------------------- selection

  protected async select(menuId: string): Promise<void> {
    this.selectedMenuId.set(menuId);
    this.addByFilterResult.set(null);
    this.addByFilterError.set(null);
    await this.loadItems();
  }

  private async loadItems(): Promise<void> {
    const scope = this.brand.scope();
    const menuId = this.selectedMenuId();
    if (!scope || !menuId) {
      return;
    }
    this.itemsLoading.set(true);
    try {
      this.items.set(await this.api.items(scope, menuId));
    } finally {
      this.itemsLoading.set(false);
    }
  }

  // ------------------------------------------------------------------ create

  protected openCreate(): void {
    this.createName.set('');
    this.createError.set(null);
    this.createOpen.set(true);
  }

  protected closeCreate(): void {
    this.createOpen.set(false);
  }

  protected setCreateName(value: string): void {
    this.createName.set(value);
  }

  protected async submitCreate(): Promise<void> {
    const scope = this.brand.scope();
    const name = this.createName().trim();
    if (!scope || !name) {
      return;
    }
    this.creating.set(true);
    this.createError.set(null);
    try {
      await this.api.create(scope, name);
      await this.refreshMenus();
      this.createOpen.set(false);
    } catch (error) {
      this.createError.set(this.describe(error));
    } finally {
      this.creating.set(false);
    }
  }

  // -------------------------------------------------------------------- copy

  protected openCopy(menu: MenuSetSummary): void {
    this.copyTarget.set(menu);
    this.copyName.set(`${menu.name} (copy)`);
    this.copyError.set(null);
  }

  protected closeCopy(): void {
    this.copyTarget.set(null);
  }

  protected setCopyName(value: string): void {
    this.copyName.set(value);
  }

  protected async submitCopy(): Promise<void> {
    const scope = this.brand.scope();
    const source = this.copyTarget();
    const name = this.copyName().trim();
    if (!scope || !source || !name) {
      return;
    }
    this.copying.set(true);
    this.copyError.set(null);
    try {
      await this.api.copy(scope, source.menuId, name);
      await this.refreshMenus();
      this.copyTarget.set(null);
    } catch (error) {
      this.copyError.set(this.describe(error));
    } finally {
      this.copying.set(false);
    }
  }

  // --------------------------------------------------------- add-by-filter

  protected setFilterCategoryId(value: string): void {
    this.filterCategoryId.set(value);
  }

  protected setFilterSearch(value: string): void {
    this.filterSearch.set(value);
  }

  protected setFilterAvailability(value: string): void {
    this.filterAvailability.set(value as 'AVAILABLE' | 'UNAVAILABLE' | 'HIDDEN');
  }

  protected async submitAddByFilter(): Promise<void> {
    const scope = this.brand.scope();
    const menuId = this.selectedMenuId();
    if (!scope || !menuId) {
      return;
    }
    this.addingByFilter.set(true);
    this.addByFilterError.set(null);
    this.addByFilterResult.set(null);
    try {
      const result = await this.api.addByFilter(scope, menuId, {
        categoryId: this.filterCategoryId() || null,
        search: this.filterSearch().trim() || null,
        availabilityDefault: this.filterAvailability(),
        locale: toCatalogLocale(this.i18n.locale()),
      });
      this.addByFilterResult.set(result.added);
      await this.loadItems();
    } catch (error) {
      this.addByFilterError.set(this.describe(error));
    } finally {
      this.addingByFilter.set(false);
    }
  }

  protected async removeItem(variantId: string): Promise<void> {
    const scope = this.brand.scope();
    const menuId = this.selectedMenuId();
    if (!scope || !menuId) {
      return;
    }
    await this.api.removeItem(scope, menuId, variantId);
    await this.loadItems();
  }

  // -------------------------------------------------------------------- bind

  protected setBindLocationId(value: string): void {
    this.bindLocationId.set(value);
  }

  protected setBindChannelId(value: string): void {
    this.bindChannelId.set(value);
  }

  protected async submitBind(): Promise<void> {
    const scope = this.brand.scope();
    const menuId = this.selectedMenuId();
    const locationId = this.bindLocationId();
    if (!scope || !menuId || !locationId) {
      return;
    }
    this.binding.set(true);
    this.bindError.set(null);
    try {
      await this.api.bind(scope, locationId, menuId, this.bindChannelId() || null);
      await this.refreshBindings();
      this.bindLocationId.set('');
      this.bindChannelId.set('');
    } catch (error) {
      this.bindError.set(this.describe(error));
    } finally {
      this.binding.set(false);
    }
  }

  protected async unbind(binding: MenuSetBinding): Promise<void> {
    const scope = this.brand.scope();
    if (!scope) {
      return;
    }
    await this.api.unbind(scope, binding.locationId, binding.channelId);
    await this.refreshBindings();
  }

  protected locationName(locationId: string): string {
    return (
      this.locations().find((location) => location.id === locationId)?.displayName ?? locationId
    );
  }

  protected channelName(channelId: string | null): string {
    if (!channelId) {
      return this.i18n.t('catalog.menuSets.bindings.allChannels');
    }
    return this.channels().find((channel) => channel.id === channelId)?.displayName ?? channelId;
  }

  protected statusLabel(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return this.i18n.t('catalog.status.ACTIVE');
      case 'DRAFT':
        return this.i18n.t('catalog.status.DRAFT');
      case 'ARCHIVED':
        return this.i18n.t('catalog.status.ARCHIVED');
      default:
        return status;
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }

  /**
   * `LocationsApi.list`/`SalesChannelsApi.list` are typed against
   * `LocationScope` because their usual caller already has one (the
   * scope-bar's current branch); the endpoints both call
   * (`OperationsBrandController.locations`, `SalesChannelController.list`)
   * read only `tenantId`/`brandId` from it, confirmed by reading
   * `settings-paths.ts` directly. This screen has no current branch of its
   * own to read every branch and channel for the whole brand, so the extra
   * field is a harmless placeholder rather than a real identifier.
   */
  private brandWideLocationScope(scope: BrandScope): LocationScope {
    return { ...scope, locationId: '' };
  }
}
