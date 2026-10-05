import { Injectable, Signal, computed, signal } from '@angular/core';

const STORAGE_KEY = 'horecaos.operations.brandId';

/** A brand the operator may pick between -- only what the shell's picker needs to draw an option. */
export interface BrandOption {
  readonly id: string;
  readonly displayName: string;
}

/**
 * Which brand a tenant-wide operator is looking at, when the tenant has more than one (row `X.1`,
 * settings.md §1.1: «Brand picker -- hidden entirely when the signed-in principal has exactly one
 * brand in scope; a picker with one option is noise»).
 *
 * **What it is the answer for.** Until now an operator whose grant spans the tenant was pinned to
 * whichever brand the platform listed first (`CurrentBrand`'s own doc comment says so): every
 * catalogue screen, the location picker and the regional formats followed that one brand, and a
 * second brand's menu could not be reached at all. `CurrentBrand` and `CurrentLocation` both read
 * {@link brandId} on that path, so a pick here re-points both at once, and the shell's header
 * draws the picker from {@link options}.
 *
 * **What it is not.** It is not an authorization claim (the server decides what any brand shows),
 * and it does not touch an operator whose scope is a brand or a branch: their own grant names the
 * brand, {@link options} stays empty for them and the picker is not drawn. The Settings scope bar
 * keeps its own brand in the URL (`?brand=`); this seeds it when the URL names none.
 *
 * Remembered in `localStorage` with the same tolerant try/catch every other per-viewer
 * convenience in the console uses (`CurrentLocation`, `I18n`): a kiosk profile with storage off
 * loses the pick between sessions, not the page.
 */
@Injectable({ providedIn: 'root' })
export class BrandChoice {
  private readonly offered = signal<readonly BrandOption[]>([]);
  private readonly picked = signal<string | null>(readStored());

  /** Every brand the tenant-wide operator may pick between; empty until the list has been read. */
  readonly options: Signal<readonly BrandOption[]> = this.offered.asReadonly();

  /** Whether there is anything to choose between. */
  readonly multiBrand: Signal<boolean> = computed(() => this.offered().length > 1);

  /** The brand in effect: the remembered pick while it is still offered, else the first. */
  readonly brandId: Signal<string | null> = computed(() => {
    const options = this.offered();
    if (options.length === 0) {
      return null;
    }
    const wanted = this.picked();
    return options.some((option) => option.id === wanted) ? wanted : options[0].id;
  });

  /** Called by whoever has just read the tenant's brand list. */
  offer(options: readonly BrandOption[]): void {
    this.offered.set(options);
  }

  select(brandId: string): void {
    this.picked.set(brandId);
    try {
      globalThis.localStorage?.setItem(STORAGE_KEY, brandId);
    } catch {
      // Lost between sessions, not lost now.
    }
  }
}

function readStored(): string | null {
  try {
    return globalThis.localStorage?.getItem(STORAGE_KEY) ?? null;
  } catch {
    return null;
  }
}
