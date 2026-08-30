/**
 * The wire shape of `GET /api/v1/session/context`, ADR 0025.
 *
 * Mirrors `uz.horecaos.platform.iam.api.CapabilityView` — see
 * `frontend/control-plane/src/app/core/auth/capability.ts` for the sibling
 * console's copy of the same contract, which is the prior art this one
 * follows.
 *
 * **This console reads `scopes` only, never `capabilities`.** Deciding what an
 * operator may *do* from a capability list is exactly the client-side
 * capability logic this application must never perform: the server is the
 * enforcement point, and the order board renders no per-row action yet for a
 * capability to gate (that lands once the server adds `actions[]` to the order
 * response — see `docs/operations-spec/orders.md` §4.2). What this reads
 * `scopes` for is narrower and not an authorization decision at all: finding
 * *which location* to ask the orders endpoint about, because nothing else in
 * this application resolves that yet.
 */
export interface ScopeGrant {
  readonly scope: {
    readonly type: 'PLATFORM' | 'TENANT' | 'BRAND' | 'LOCATION';
    readonly tenantId: string | null;
    readonly brandId: string | null;
    readonly locationId: string | null;
  };
  readonly roleCode: string;
}

/** `GET /api/v1/session/context`. Mirrors `CapabilityView`, `scopes` field only. */
export interface SessionContext {
  readonly subject: string;
  readonly activeTenantId: string | null;
  readonly scopes: readonly ScopeGrant[];
}
