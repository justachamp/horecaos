import { Injectable, Signal, computed, inject } from '@angular/core';

import { CurrentTenant } from './current-tenant';

/**
 * The capabilities this console's navigation and route guard ask about
 * (ADR 0025, operations IA §9.1c).
 *
 * The server owns the registry — `uz.horecaos.platform.iam.api.Capability` —
 * and serialises each value as its enum name, so these strings are the wire
 * values. Only the ones `navigation.ts` and `capability.guard.ts` reference
 * are listed here; see `frontend/control-plane/src/app/core/auth/capability.ts`
 * for that console's own copy of the same idea, over its own, larger subset.
 *
 * A capability check here is a usability affordance and nothing more — see
 * {@link SessionCapabilities}'s own doc.
 */
export type Capability =
  | 'ORDER_READ'
  | 'CONVERSATION_INBOX_MANAGE'
  | 'KITCHEN_TICKET_READ'
  | 'DELIVERY_PLAN_READ'
  | 'COURIER_READ'
  | 'CUSTOMER_READ'
  | 'IAM_GRANT_MANAGE'
  | 'REPORTING_READ'
  | 'PAYMENT_READ'
  | 'CATALOG_READ'
  | 'REFERRAL_READ'
  | 'BRAND_READ'
  | 'TENANT_READ';

/**
 * Whether the signed-in operator holds a given capability *anywhere* — the
 * rail filter's and the route guard's question, never the server's.
 *
 * **Why a flat union of every scope, not `scope-coverage.ts`'s
 * `effectiveCapabilitiesAt`.** That helper answers "what may the operator do
 * *at one target scope*" — right for the staff feature's job picker, which
 * always has a concrete target (the scope a grant would be conferred at).
 * A rail item has no such target: `CurrentLocation` may not have resolved yet
 * when the shell first paints, and a `TENANT`-scoped owner and a
 * `LOCATION`-scoped line cook are asking the identical question — "do I have
 * any business in this section" — from scopes that are not comparable by
 * `covers()` at all. The union of capabilities across every one of the
 * operator's own grants answers that question directly, with no target
 * scope required, and — because `effectiveCapabilitiesAt` only ever *adds*
 * capabilities from a grant that already appears in the operator's own
 * `scopes`, never from one that does not — it is provably the same set
 * `effectiveCapabilitiesAt` would produce if it were asked about every one
 * of the operator's own scopes and the answers were combined.
 *
 * **This is a usability affordance, never an authorization decision** — the
 * same line `session-context.ts` and `scope-coverage.ts` already draw.
 * `CapabilityEnforcementInterceptor` re-checks the identical capability
 * server-side on every request; a client that computed this wrong would only
 * ever hide a section the operator could in fact use, or show one the server
 * then refuses — never grant power it should not have.
 */
@Injectable({ providedIn: 'root' })
export class SessionCapabilities {
  private readonly tenant = inject(CurrentTenant);

  /**
   * Every capability the operator holds, at any of their own scopes.
   *
   * `false` for everything before the session context has loaded, on the
   * same reasoning `SessionContextService.has()` gives in `control-plane`:
   * the opposite default would flash a full rail on every start and then
   * remove half of it, which reads as a bug. `ensureLoaded()` settles this
   * before the shell's first paint in practice — see `Shell`'s constructor.
   */
  private readonly held: Signal<ReadonlySet<Capability>> = computed(
    () =>
      new Set(
        this.tenant.scopes().flatMap((grant) => grant.capabilities ?? []),
      ) as ReadonlySet<Capability>,
  );

  has(capability: Capability): boolean {
    return this.held().has(capability);
  }

  /** Delegates to {@link CurrentTenant} — the two never fetch independently. */
  ensureLoaded(): Promise<void> {
    return this.tenant.ensureLoaded();
  }
}
