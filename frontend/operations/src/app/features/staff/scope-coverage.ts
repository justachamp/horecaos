import { ScopeGrant } from '../../core/auth/session-context';

/** Mirrors `uz.horecaos.platform.iam.api.ResourceScope`'s shape, wire-side. */
export interface Scope {
  readonly type: 'PLATFORM' | 'TENANT' | 'BRAND' | 'LOCATION';
  readonly tenantId: string | null;
  readonly brandId: string | null;
  readonly locationId: string | null;
}

/**
 * A client-side port of `ResourceScope.covers`/`chain` and
 * `JdbcAuthorizationService.hasGrant`, for exactly one purpose:
 * staff-and-access.md §0's corollary that a manager offered a job she cannot
 * confer "learns only that the software is taunting her" — so the job picker
 * on the Invite form and the Add-job dialog must *not list* a job the
 * signed-in operator cannot grant, rather than list it disabled.
 *
 * **This is a usability affordance, never an authorization decision** — the
 * same line `session-context.ts` and this application's README already draw
 * for `CurrentLocation`/`CurrentBrand`. `GrantManagementService.requireGrantable`
 * re-checks the identical rule server-side on every `POST .../grants`, and
 * that check is the one that matters; a client that computed this wrong would
 * only ever offer a job the server then refuses, never grant one it should
 * not have.
 */
export function scopeChain(scope: Scope): readonly Scope[] {
  const chain: Scope[] = [scope];
  switch (scope.type) {
    case 'LOCATION':
      chain.push(
        { type: 'BRAND', tenantId: scope.tenantId, brandId: scope.brandId, locationId: null },
        { type: 'TENANT', tenantId: scope.tenantId, brandId: null, locationId: null },
        PLATFORM_SCOPE,
      );
      break;
    case 'BRAND':
      chain.push(
        { type: 'TENANT', tenantId: scope.tenantId, brandId: null, locationId: null },
        PLATFORM_SCOPE,
      );
      break;
    case 'TENANT':
      chain.push(PLATFORM_SCOPE);
      break;
    case 'PLATFORM':
      break;
  }
  return chain;
}

export const PLATFORM_SCOPE: Scope = {
  type: 'PLATFORM',
  tenantId: null,
  brandId: null,
  locationId: null,
};

export function tenantScope(tenantId: string): Scope {
  return { type: 'TENANT', tenantId, brandId: null, locationId: null };
}

export function brandScope(tenantId: string, brandId: string): Scope {
  return { type: 'BRAND', tenantId, brandId, locationId: null };
}

export function locationScope(tenantId: string, brandId: string, locationId: string): Scope {
  return { type: 'LOCATION', tenantId, brandId, locationId };
}

function scopeEquals(a: Scope, b: Scope): boolean {
  return (
    a.type === b.type &&
    a.tenantId === b.tenantId &&
    a.brandId === b.brandId &&
    a.locationId === b.locationId
  );
}

/** Whether `broader` covers `narrower` — a broader scope covers everything beneath it, never sideways or up. */
export function covers(broader: Scope, narrower: Scope): boolean {
  return scopeChain(narrower).some((candidate) => scopeEquals(candidate, broader));
}

/**
 * The union of capability codes the signed-in operator holds at `target`,
 * from every one of their own scopes that covers it — mirroring
 * `JdbcAuthorizationService.hasGrant`'s `grant.scope().covers(scope)`.
 */
export function effectiveCapabilitiesAt(
  scopes: readonly ScopeGrant[],
  target: Scope,
): ReadonlySet<string> {
  const held = new Set<string>();
  for (const grant of scopes) {
    if (covers(grant.scope, target)) {
      for (const capability of grant.capabilities ?? []) {
        held.add(capability);
      }
    }
  }
  return held;
}

/**
 * Whether the operator could confer every one of `requiredCapabilities` at
 * `target` — the job-picker filter itself. `requiredCapabilities` is a job's
 * full capability set (`TenantRoleCatalog.RoleDescriptor.capabilities`);
 * every one of them must be held, matching
 * `GrantManagementService.requireGrantable`'s "subset of the granter's own".
 */
export function canGrantAt(
  scopes: readonly ScopeGrant[],
  target: Scope,
  requiredCapabilities: Iterable<string>,
): boolean {
  const held = effectiveCapabilitiesAt(scopes, target);
  for (const capability of requiredCapabilities) {
    if (!held.has(capability)) {
      return false;
    }
  }
  return true;
}
