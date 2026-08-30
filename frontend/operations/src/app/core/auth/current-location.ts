import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../api/operations-paths';
import { ApiClient } from '../api/api-client';
import { SessionContext } from './session-context';

/**
 * Which branch the operator is looking at, until a real location switcher
 * exists.
 *
 * **What is missing, and why this exists anyway.** `docs/operations-spec/`
 * says a location picker belongs in `settings.md` §1.1 (`?brand=…&location=…`
 * in the URL, filtered by the operator's ADR 0025 grants) and that the order
 * board itself expects `Филиал` to auto-hide for a single-location tenant
 * (`orders.md` §2.5). Neither is built. But the order board cannot call
 * `GET .../locations/{locationId}/orders` without *some* `LocationScope`, so
 * this resolves the simplest correct thing available today: the operator's
 * first `LOCATION`-scoped grant from `GET /api/v1/session/context` (ADR
 * 0025). An operator scoped to several branches gets the first one — the
 * `Показаны N филиалов из M` partial-scope state (`orders.md` §2.11) and a
 * real switcher are follow-up work, not invented here.
 *
 * This is deliberately not a port of `frontend/control-plane`'s
 * `SessionContextService`: that service also exposes `has(capability)`, and a
 * capability check in this client is exactly what this application must never
 * do (see `session-context.ts`). Only the scope lookup is ported.
 */
@Injectable({ providedIn: 'root' })
export class CurrentLocation {
  private readonly api = inject(ApiClient);

  private readonly context = signal<SessionContext | null>(null);
  private readonly hasLoaded = signal(false);

  /** The operator's location, or null before load and when they hold no `LOCATION` grant. */
  readonly scope: Signal<LocationScope | null> = computed(() => firstLocationScope(this.context()));

  /**
   * True once loading has settled (successfully or not) and no location was
   * found. False while still loading, so callers can tell "not yet known" from
   * "known to be denied" — showing the board's denied state before the first
   * response would tell an operator they lack access when the truth is simply
   * that nobody has asked yet.
   */
  readonly denied: Signal<boolean> = computed(() => this.hasLoaded() && this.scope() === null);

  private loadPromise: Promise<void> | null = null;

  /** Fetches the session context once; every later call replays the same promise. */
  ensureLoaded(): Promise<void> {
    if (this.loadPromise === null) {
      this.loadPromise = this.load();
    }
    return this.loadPromise;
  }

  private async load(): Promise<void> {
    try {
      const result = await firstValueFrom(this.api.get<SessionContext>('/api/v1/session/context'));
      this.context.set(result.value);
    } catch {
      // Left null. The board's denied state is a better outcome here than a
      // console left starting up forever — see `denied` above.
      this.context.set(null);
    } finally {
      this.hasLoaded.set(true);
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
