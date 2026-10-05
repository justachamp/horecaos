import { Injectable, Signal, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../api/operations-paths';
import { ApiClient } from '../api/api-client';
import { settingsPaths } from '../api/settings-paths';
import { BrandScope } from '../api/catalog-paths';
import { BrandChoice } from './brand-choice';
import { firstBrandScope, resolveBrandForTenant } from './current-brand';
import { SessionContext } from './session-context';

const STORAGE_KEY = 'horecaos.operations.locationId';

/** A location the operator can switch to — only what the shell's picker needs to render an option. */
export interface LocationOption {
  readonly id: string;
  readonly displayName: string;
  readonly status: 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';
}

/**
 * Which branch the operator is looking at, and — new in wave 50 — which
 * branches they may switch to.
 *
 * **The defect this replaced.** Resolution used to be `firstLocationScope`
 * alone: the operator's first `LOCATION`-scoped grant from `GET
 * /api/v1/session/context` (ADR 0025), and `null` for everyone else. That is
 * right for an operator whose *only* grant is a location, and it is wrong for
 * everyone above one — a tenant-admin or brand-manager (`manager@horecaos.uz`
 * in `dev-personas.md`) holds a `TENANT` or `BRAND` grant and no `LOCATION`
 * grant at all, so this resolved `null` and every location-scoped screen
 * (Orders, Kitchen, Delivery, Reservations, Capacity, and 70-odd more) showed
 * its denied state — even though ADR 0025 scopes cover downwards and the
 * backend happily serves `GET .../locations/{locationId}/orders/counts` to
 * exactly that actor. The console was refusing to show a persona what the
 * platform would serve it.
 *
 * **Resolution order now.**
 *
 * 1. A direct `LOCATION`-scoped grant, exactly as before — {@link
 *    firstLocationScope}, zero extra network calls. An operator whose scope
 *    genuinely is one branch never notices anything changed.
 * 2. Failing that, a brand: a direct `BRAND` grant, or — new — a `TENANT`
 *    grant resolved to a brand via {@link resolveBrandForTenant} (see that
 *    function and `current-brand.ts`'s own doc comment for why the fallback
 *    lives there rather than being duplicated here). That brand's locations
 *    are fetched from `OperationsBrandController.locations`
 *    (`settingsPaths.locations`, the same path builder
 *    `features/settings/locations/locations-api.ts` and half a dozen report
 *    screens already call, reused rather than duplicated) and become
 *    {@link options}: the operator's own choice if it is still among them,
 *    else the first one.
 * 3. Neither of the above answers anything — no `LOCATION` grant, no `BRAND`
 *    grant, no `TENANT` grant that covers a brand, or the brand it resolved
 *    to turned out to have zero locations — and {@link scope} stays `null`.
 *    This is a real "covers nothing" outcome, not a resolution failure: the
 *    board's denied state is still the right answer for it, exactly as
 *    before.
 *
 * **Row `X.1`, batch 18.** On the brand path of §2 a tenant-wide operator with several brands now
 * picks one in the shell's header (`BrandChoice`); the effect in the constructor re-reads the
 * locations of whichever brand is picked, so the location picker and the 76 screens behind it
 * follow the pick. An operator who never picks gets the first brand, as before.
 *
 * **The gap after a pick.** The brand in effect changes the moment the operator picks, but that
 * brand's branches arrive a read later, and the shell rebuilds the open screen at once. In that
 * gap this class answers «not known yet» ({@link scope} is `null`, {@link denied} is false,
 * {@link options} is empty) instead of the previous brand's branch under the new brand's name,
 * and {@link ensureLoaded} does not resolve until the read has settled -- so the rebuilt screen,
 * which awaits it before reading {@link scope}, loads the brand the header names and never the one
 * it just left.
 *
 * **What this does not do.** It does not enforce anything — same
 * non-enforcement stance this class always documented: the server decides
 * what the operator may see at the location it resolves to, this only picks
 * *which* location to ask about. It does not build `settings.md` §1.1's full
 * scope bar (brand picker, level readout, `?brand=&location=` in the URL) —
 * only the location half, in the shell (`shell.ts`), because that is what 76
 * consumer screens already depend on through this one class. And it does not
 * change what any of those 76 screens import or call: they still read
 * {@link scope} and {@link denied} exactly as they always have.
 */
@Injectable({ providedIn: 'root' })
export class CurrentLocation {
  private readonly api = inject(ApiClient);
  private readonly choice = inject(BrandChoice);

  private readonly context = signal<SessionContext | null>(null);
  private readonly resolvedBrand = signal<BrandScope | null>(null);
  /** The tenant of the TENANT grant the brand path resolved through; null on the other paths. */
  private readonly tenantPathTenantId = signal<string | null>(null);
  private readonly resolvedOptions = signal<readonly LocationOption[]>([]);
  private readonly selectedLocationId = signal<string | null>(readStoredLocationId());
  private readonly hasLoaded = signal(false);

  /**
   * True from the pick of a brand whose branches are not the ones loaded until they have been read
   * (or could not be). Derived from the two signals rather than set by the read, so it is right in
   * the same tick as the pick, before the effect below has had a chance to start the read.
   */
  private readonly switching: Signal<boolean> = computed(() => {
    const tenantId = this.tenantPathTenantId();
    const brandId = this.choice.brandId();
    return tenantId !== null && brandId !== null && this.resolvedBrand()?.brandId !== brandId;
  });

  /**
   * The operator's location, or null before load, when they cover none, and while the branches of a
   * brand they have just picked are being read.
   */
  readonly scope: Signal<LocationScope | null> = computed(() => {
    const direct = firstLocationScope(this.context());
    if (direct) {
      return direct;
    }
    if (this.switching()) {
      return null;
    }
    const brand = this.resolvedBrand();
    const options = this.resolvedOptions();
    if (!brand || options.length === 0) {
      return null;
    }
    const selected = this.selectedLocationId();
    const chosen = options.find((option) => option.id === selected) ?? options[0];
    return { tenantId: brand.tenantId, brandId: brand.brandId, locationId: chosen.id };
  });

  /**
   * Every location the operator may switch to, for the shell's picker.
   *
   * Populated only along the brand-resolution path (§2 above) — an operator
   * who resolved through a direct `LOCATION` grant (§1) gets an empty list,
   * not a singleton, because the shell hides the picker below two options
   * either way (see `shell.ts`) and this class has no display name for a
   * grant that never named one, without a round trip this wave deliberately
   * does not spend on the common case. Consumers other than the picker
   * should not read this as "every location the operator can see."
   *
   * Empty while the branches of a freshly picked brand are being read: the previous brand's are
   * not an answer for the new one.
   */
  readonly options: Signal<readonly LocationOption[]> = computed(() =>
    this.switching() ? [] : this.resolvedOptions(),
  );

  /**
   * True once loading has settled (successfully or not) and no location was
   * found. False while still loading, so callers can tell "not yet known" from
   * "known to be denied" — showing the board's denied state before the first
   * response would tell an operator they lack access when the truth is simply
   * that nobody has asked yet.
   */
  readonly denied: Signal<boolean> = computed(
    () => this.hasLoaded() && !this.switching() && this.scope() === null,
  );

  private loadPromise: Promise<void> | null = null;

  constructor() {
    // Follows a brand pick made after the first load. `untracked` for everything but the two
    // signals that matter: re-reading `resolvedBrand` here must not re-run the effect.
    effect(() => {
      const tenantId = this.tenantPathTenantId();
      const brandId = this.choice.brandId();
      if (!tenantId || !brandId) {
        return;
      }
      untracked(() => {
        if (this.resolvedBrand()?.brandId !== brandId) {
          void this.readOptionsFor({ tenantId, brandId });
        }
      });
    });
  }

  /**
   * Fetches the session context once; every later call replays the same promise. After a brand
   * pick it also waits for the picked brand's branches, so what a caller reads from {@link scope}
   * once this resolves is the brand in effect and never the one the operator just left.
   */
  ensureLoaded(): Promise<void> {
    if (this.loadPromise === null) {
      this.loadPromise = this.load();
    }
    return this.loadPromise.then(() => this.settleBrandPick());
  }

  /**
   * Waits out the read of the branches of the brand now picked. Looped, because a second pick can
   * land while the first is being read: that read's answer is dropped, and the one that decides is
   * the one for the brand in effect when this looks again. Each pass ends in a settled read --
   * stored, failed (which stores an empty list) or superseded by a newer one -- so it terminates.
   */
  private async settleBrandPick(): Promise<void> {
    while (this.switching()) {
      const tenantId = this.tenantPathTenantId();
      const brandId = this.choice.brandId();
      if (tenantId === null || brandId === null) {
        return;
      }
      await this.readOptionsFor({ tenantId, brandId });
    }
  }

  /**
   * Switches the operator to a different location from {@link options}.
   *
   * Remembered in `localStorage` under {@link STORAGE_KEY} — the same
   * tolerant, try/catch-guarded persistence `core/i18n/i18n.ts` uses for the
   * console's locale choice, so a kiosk profile with storage disabled loses
   * the preference between sessions rather than throwing. An id outside
   * {@link options} is not rejected here; {@link scope}'s own lookup already
   * falls back to the first option for exactly that case (this operator no
   * longer covers the remembered location, most commonly), so there is
   * nothing more for this method to validate.
   */
  selectLocation(locationId: string): void {
    this.selectedLocationId.set(locationId);
    try {
      globalThis.localStorage?.setItem(STORAGE_KEY, locationId);
    } catch {
      // Lost between sessions, not lost now — same stance as I18n.setLocale.
    }
  }

  private async load(): Promise<void> {
    try {
      const result = await firstValueFrom(this.api.get<SessionContext>('/api/v1/session/context'));
      this.context.set(result.value);
      if (!firstLocationScope(result.value)) {
        const direct = firstBrandScope(result.value);
        const brand = direct ?? (await resolveBrandForTenant(this.api, result.value, this.choice));
        if (brand) {
          await this.readOptionsFor(brand);
          // After the first read, never before: the effect above compares this brand with the one
          // already loaded, and arming it earlier would read the same locations twice.
          if (!direct && this.resolvedBrand() !== null) {
            this.tenantPathTenantId.set(brand.tenantId);
          }
        }
      }
    } catch {
      // Left null. The board's denied state is a better outcome here than a
      // console left starting up forever — see `denied` above.
      this.context.set(null);
    } finally {
      this.hasLoaded.set(true);
    }
  }

  /** Bumped per read, so a slow answer for a brand the operator has since left is dropped. */
  private optionsRequest = 0;

  /** The read under way, so the effect and a screen's `ensureLoaded()` share one request per brand. */
  private optionsRead: { readonly brandId: string; readonly promise: Promise<void> } | null = null;

  private readOptionsFor(brand: BrandScope): Promise<void> {
    if (this.optionsRead?.brandId === brand.brandId) {
      return this.optionsRead.promise;
    }
    const read = { brandId: brand.brandId, promise: this.loadOptionsFor(brand) };
    this.optionsRead = read;
    void read.promise.finally(() => {
      if (this.optionsRead === read) {
        this.optionsRead = null;
      }
    });
    return read.promise;
  }

  private async loadOptionsFor(brand: BrandScope): Promise<void> {
    const request = ++this.optionsRequest;
    try {
      const result = await firstValueFrom(
        this.api.get<readonly LocationOption[]>(
          settingsPaths.locations({ ...brand, locationId: '' }),
        ),
      );
      if (request !== this.optionsRequest) {
        return;
      }
      // Set together, even when empty: {@link scope}'s `!brand` guard would
      // otherwise never fire for a brand that resolved but has zero
      // locations, since `resolvedBrand` would stay at its initial `null`.
      this.resolvedBrand.set(brand);
      this.resolvedOptions.set(result.value ?? []);
    } catch {
      // Leave both unset — the brand resolved but its locations could not be
      // read, which is the same "denied" outcome as never resolving a brand
      // at all, not a reason to throw a screen into an error state instead.
      //
      // After a *switch* the previous brand's branches are already loaded; keeping them while the
      // header names another brand would show the operator one brand's branches under the other's
      // name. The honest answer is the same denied outcome.
      if (request === this.optionsRequest && this.resolvedBrand() !== null) {
        this.resolvedBrand.set(brand);
        this.resolvedOptions.set([]);
      }
    }
  }
}

function firstLocationScope(context: SessionContext | null): LocationScope | null {
  const grant = context?.scopes.find(
    (candidate) =>
      candidate.scope.type === 'LOCATION' &&
      candidate.scope.tenantId !== null &&
      candidate.scope.brandId !== null &&
      candidate.scope.locationId !== null,
  );
  if (!grant) {
    return null;
  }
  // The `find` predicate above already proved these are non-null; TypeScript
  // cannot see across the closure, so the assertion states what was checked.
  return {
    tenantId: grant.scope.tenantId as string,
    brandId: grant.scope.brandId as string,
    locationId: grant.scope.locationId as string,
  };
}

function readStoredLocationId(): string | null {
  try {
    return globalThis.localStorage?.getItem(STORAGE_KEY) ?? null;
  } catch {
    return null;
  }
}
