/**
 * The wire shape of `GET /api/v1/session/context`, ADR 0025.
 *
 * Mirrors `uz.horecaos.platform.iam.api.CapabilityView` — see
 * `frontend/control-plane/src/app/core/auth/capability.ts` for the sibling
 * console's copy of the same contract, which is the prior art this one
 * follows.
 *
 * **This console reads `scopes` only, never the top-level `capabilities`
 * union.** Deciding what an operator may *do* from a flat capability list is
 * exactly the client-side capability logic this application must never
 * perform as *enforcement* — the server is the enforcement point. `scopes`
 * (each carrying its own `capabilities`) is read for two narrower, USABILITY,
 * never-authorization purposes: finding *which location* to ask the orders
 * endpoint about (`current-location.ts`, `current-brand.ts`), and — new in
 * the Staff section (staff-and-access.md §0's "granter can only give away
 * what they hold") — deciding which jobs to *offer* in a picker so the
 * refusal `GrantManagementService.requireGrantable` already enforces
 * server-side never has to be shown as an error. See
 * `features/staff/scope-coverage.ts`.
 */
export interface ScopeGrant {
  readonly scope: {
    readonly type: 'PLATFORM' | 'TENANT' | 'BRAND' | 'LOCATION';
    readonly tenantId: string | null;
    readonly brandId: string | null;
    readonly locationId: string | null;
  };
  readonly roleCode: string;
  /**
   * Capability codes this scope grants. Optional so every fixture written
   * before the Staff section still type-checks; a scope-coverage read that
   * finds it absent treats it as empty rather than throwing.
   */
  readonly capabilities?: readonly string[];
}

/** `GET /api/v1/session/context`. Mirrors `CapabilityView`, `scopes` field only. */
export interface SessionContext {
  readonly subject: string;
  readonly activeTenantId: string | null;
  readonly scopes: readonly ScopeGrant[];
}
