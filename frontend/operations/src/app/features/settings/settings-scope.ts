import { Injectable, Signal, computed, effect, inject, signal, untracked } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { settingsPaths } from '../../core/api/settings-paths';
import { BrandChoice } from '../../core/auth/brand-choice';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { BrandView } from './brand-profile/brand-profile-api';
import { LocationView } from './locations/locations-api';
import { SavedTarget } from './settings-saved';

/** The level a settings screen is currently writing to (settings.md §1.1's "Уровень редактирования"). */
export type SettingsEditingLevel = 'TENANT' | 'BRAND' | 'LOCATION';

/**
 * settings.md §1.1's scope bar, as shared state: which brand and which
 * location (or "Все филиалы", i.e. brand-level) every settings screen reads
 * and writes against, synced to `?brand=&location=` so a link pasted into a
 * chat opens the same thing (wave P31, gap map row `10/X.1`).
 *
 * `settings-shell.ts` used to read only `CurrentLocation` — the operator's
 * own branch, with no way to change it and no brand-level option at all,
 * which is exactly the gap this class closes. `q-scope-bar` is the
 * presentational half; this is the state and the API calls behind it.
 *
 * **Row 10.3b — TENANT level.** `?level=tenant` in the query string is a
 * third state orthogonal to `brand`/`location`: an operator authoring the
 * tenant-wide default for a `q-inherited-field` consumer (order policy cards
 * 2-5 first; the same key ladder applies to any other screen that declares
 * `TENANT` a settable scope). `OperationsConfigurationController.scopeOf`
 * already resolved `TENANT` to `ResourceScope.tenant(tenantId)` regardless of
 * `brandId`/`locationId` before this existed — the console simply had no way
 * to ask for it. Picking a brand or a location leaves TENANT level the same
 * way picking a location already leaves BRAND level: a positive choice of a
 * narrower scope, not a toggle a screen has to remember to clear.
 *
 * **Resolution**, deliberately simple rather than mirroring `CurrentLocation`'s
 * fallback ladder: this screen is for people who administer settings, who by
 * construction hold at least a `BRAND`-scoped grant (`TENANT_CONFIGURATION_*`
 * is never granted narrower than `TENANT`), so the brand list always comes
 * from `OperationsBrandController.list`, never inferred from a single
 * `LOCATION` grant. A brand or location named in the query string that this
 * tenant does not have falls back to the first option — the same defensive
 * stance {@link locationId} already needs for a location that belonged to a
 * brand the query string no longer names.
 */
@Injectable({ providedIn: 'root' })
export class SettingsScope {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly tenant = inject(CurrentTenant);
  private readonly api = inject(ApiClient);
  private readonly shellBrand = inject(BrandChoice);

  private readonly queryBrandId = signal<string | null>(null);
  private readonly queryLocationId = signal<string | null>(null);
  private readonly queryTenantWide = signal(false);
  /**
   * The header's pick count ({@link BrandChoice.picks}) when the query was last read. The query
   * describes the brand the operator was on when it was written; a pick made in the header since
   * then replaces that brand, so until the URL is re-pointed (or the operator moves it) the query's
   * brand, branch and level are not in force.
   */
  private readonly queryReadAtPick = signal(this.shellBrand.picks());
  private readonly queryOutranked = computed(
    () => this.shellBrand.picks() > this.queryReadAtPick(),
  );
  private readonly brandsSig = signal<readonly BrandView[]>([]);
  private readonly locationsSig = signal<readonly LocationView[]>([]);
  private readonly loadingBrands = signal(false);
  private readonly deniedSig = signal(false);
  private brandsLoadedForTenant: string | null = null;
  private locationsLoadedForBrand: string | null = null;

  readonly brands: Signal<readonly BrandView[]> = this.brandsSig.asReadonly();
  readonly locations: Signal<readonly LocationView[]> = this.locationsSig.asReadonly();

  /** Hidden entirely when the tenant has exactly one brand — a picker with one option is noise. */
  readonly showBrandPicker: Signal<boolean> = computed(() => this.brandsSig().length > 1);

  /**
   * The brand in effect: the query param when it names a brand this tenant has; else the one picked
   * in the shell's header (`BrandChoice`, row `X.1`), so opening Settings does not silently switch
   * an operator to another brand than the one they have been working in; else the first one.
   *
   * A header pick made while Settings is open outranks the query -- the screen the shell rebuilds
   * for the pick reads this at once, before the URL has been re-pointed, and must not read the brand
   * the header no longer names (a query the operator or a pasted link set *after* the pick wins
   * again).
   */
  readonly brandId: Signal<string | null> = computed(() => {
    const brands = this.brandsSig();
    if (brands.length === 0) {
      return null;
    }
    const requested = this.queryOutranked() ? null : this.queryBrandId();
    if (requested && brands.some((brand) => brand.id === requested)) {
      return requested;
    }
    const shellPick = this.shellBrand.brandId();
    return shellPick && brands.some((brand) => brand.id === shellPick) ? shellPick : brands[0].id;
  });

  /** `null` means "Все филиалы" — editing at BRAND level. A location outside the current brand is dropped. */
  readonly locationId: Signal<string | null> = computed(() => {
    const requested = this.queryOutranked() ? null : this.queryLocationId();
    if (!requested) {
      return null;
    }
    return this.locationsSig().some((location) => location.id === requested) ? requested : null;
  });

  /** Whether the bar is currently set to the tenant-wide (row 10.3b) level. */
  readonly tenantWide: Signal<boolean> = computed(
    () => this.queryTenantWide() && !this.queryOutranked(),
  );

  readonly level: Signal<SettingsEditingLevel> = computed(() => {
    if (this.tenantWide()) {
      return 'TENANT';
    }
    return this.locationId() ? 'LOCATION' : 'BRAND';
  });

  /** What the bar is set to write to, named, for the «задано для филиала …» confirmation (settings.md §1.3). */
  readonly target: Signal<SavedTarget> = computed(() =>
    this.targetFor(this.level(), this.brandId(), this.locationId()),
  );

  readonly loading: Signal<boolean> = this.loadingBrands.asReadonly();
  readonly denied: Signal<boolean> = this.deniedSig.asReadonly();

  constructor() {
    this.route.queryParamMap.subscribe((params) => {
      this.queryBrandId.set(params.get('brand'));
      this.queryLocationId.set(params.get('location'));
      this.queryTenantWide.set(params.get('level') === 'tenant');
      this.queryReadAtPick.set(untracked(() => this.shellBrand.picks()));
    });

    // Puts a header pick into the URL, so a link copied from here opens the brand the screen shows
    // and the scope bar's own pickers follow. Only when the URL pins a brand: with none, the pick is
    // already what {@link brandId} falls back to. It does what choosing the brand in the scope bar
    // does -- the branch and the company-wide level go.
    effect(() => {
      const outranked = this.queryOutranked();
      const brands = this.brandsSig();
      if (!outranked || brands.length === 0) {
        return;
      }
      untracked(() => {
        const brandId = this.brandId();
        if (brandId && this.queryBrandId() !== null) {
          this.setBrand(brandId);
        }
      });
    });

    void this.loadBrands();

    // Re-fetches locations whenever the resolved brand changes, including the
    // first time it resolves from "no query param yet" to a real id — the
    // same "re-read inside an effect keyed on the input" idiom
    // `location-detail-pane.ts` uses, because a plain constructor-only load
    // only ever sees the value at construction time.
    effect(() => {
      const brandId = this.brandId();
      const tenantId = this.tenant.tenantId();
      if (brandId && tenantId) {
        void this.loadLocations(tenantId, brandId);
      }
    });
  }

  /**
   * The level a write went to and the name of the brand or branch it names, for a confirmation that
   * must say where the change landed. A name the lists have not resolved is absent, never invented.
   */
  targetFor(
    level: SettingsEditingLevel,
    brandId: string | null,
    locationId: string | null,
  ): SavedTarget {
    if (level === 'TENANT') {
      return { level, name: null };
    }
    if (level === 'LOCATION') {
      return {
        level,
        name:
          this.locationsSig().find((location) => location.id === locationId)?.displayName ?? null,
      };
    }
    return {
      level: 'BRAND',
      name: this.brandsSig().find((brand) => brand.id === brandId)?.displayName ?? null,
    };
  }

  /**
   * Switches the brand, clearing any location selection — a different
   * brand's branches are a different set — and leaves TENANT level, the
   * same "a narrower scope is a positive choice" rule {@link setLocation}
   * follows.
   */
  setBrand(brandId: string): void {
    void this.router.navigate([], {
      queryParams: { brand: brandId, location: null, level: null },
      queryParamsHandling: 'merge',
    });
  }

  /**
   * Switches the location, or pass `null` for "Все филиалы" (brand-level
   * editing). Also leaves TENANT level: choosing a location is choosing
   * BRAND or LOCATION, never both TENANT and something narrower at once.
   */
  setLocation(locationId: string | null): void {
    void this.router.navigate([], {
      queryParams: { location: locationId, level: null },
      queryParamsHandling: 'merge',
    });
  }

  /**
   * Row 10.3b — switches to the tenant-wide default level. The current
   * brand/location stay in the URL as display context (the scope bar's own
   * pickers still show them), but {@link level} answers `TENANT` and every
   * `q-inherited-field` consumer that reads it writes/resolves at
   * `ResourceScope.tenant(tenantId)` until {@link setBrand} or {@link
   * setLocation} is called.
   */
  setTenantLevel(): void {
    void this.router.navigate([], {
      queryParams: { level: 'tenant' },
      queryParamsHandling: 'merge',
    });
  }

  /** Leaves TENANT level, returning to whatever BRAND/LOCATION the pickers already show. */
  leaveTenantLevel(): void {
    void this.router.navigate([], {
      queryParams: { level: null },
      queryParamsHandling: 'merge',
    });
  }

  private async loadBrands(): Promise<void> {
    this.loadingBrands.set(true);
    try {
      await this.tenant.ensureLoaded();
      const tenantId = this.tenant.tenantId();
      if (!tenantId) {
        this.deniedSig.set(this.tenant.denied());
        return;
      }
      if (this.brandsLoadedForTenant === tenantId) {
        return;
      }
      const result = await firstValueFrom(
        this.api.get<readonly BrandView[]>(
          settingsPaths.brands({ tenantId, brandId: '', locationId: '' }),
        ),
      );
      this.brandsSig.set(result.value ?? []);
      this.brandsLoadedForTenant = tenantId;

      // The URL should reflect the level it will actually edit, per
      // settings.md §1.1 ("a link pasted into a chat opens the same thing") —
      // so a first load with no `?brand=` in the query string writes the
      // resolved default back rather than leaving the URL silently disagree
      // with what the scope bar shows.
      const resolved = this.brandId();
      if (resolved && this.queryBrandId() !== resolved) {
        void this.router.navigate([], {
          queryParams: { brand: resolved },
          queryParamsHandling: 'merge',
        });
      }
    } catch {
      this.deniedSig.set(true);
    } finally {
      this.loadingBrands.set(false);
    }
  }

  private async loadLocations(tenantId: string, brandId: string): Promise<void> {
    if (this.locationsLoadedForBrand === brandId) {
      return;
    }
    try {
      const result = await firstValueFrom(
        this.api.get<readonly LocationView[]>(
          settingsPaths.locations({ tenantId, brandId, locationId: '' }),
        ),
      );
      this.locationsSig.set(result.value ?? []);
      this.locationsLoadedForBrand = brandId;
    } catch {
      this.locationsSig.set([]);
    }
  }
}
