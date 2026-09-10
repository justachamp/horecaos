import { HttpInterceptorFn } from '@angular/common/http';

/**
 * The tenant a HorecaOS support person is working in (ADR 0081).
 *
 * Staff sign in to operations with the same account they use for the control
 * plane, and belong to no tenant's organization, so the session context has no
 * tenant to resolve for them. The control plane opens this app with
 * `?supportTenant=<id>`; it is kept for the browser tab, and every session
 * context read names that tenant explicitly. What the person may then do is
 * whatever their support session's grant says — this only decides which
 * tenant to ask about, never what is allowed.
 */
const KEY = 'horecaos.support.tenant';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Remembers `?supportTenant=` from the address the control plane opened, if there is one. */
export function captureSupportTenant(search: string = globalThis.location?.search ?? ''): void {
  const tenant = new URLSearchParams(search).get('supportTenant');
  if (tenant !== null && UUID.test(tenant)) {
    try {
      sessionStorage.setItem(KEY, tenant);
    } catch {
      // A blocked store means the person reopens the link; nothing is granted either way.
    }
  }
}

export function supportTenant(): string | null {
  try {
    return sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function leaveSupportTenant(): void {
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // Nothing stored, nothing to leave.
  }
}

/** Names the support tenant on every session-context read that does not already name one. */
export const supportTenantInterceptor: HttpInterceptorFn = (request, next) => {
  const tenant = supportTenant();
  if (tenant === null || !request.url.endsWith('/api/v1/session/context') || request.params.has('tenantId')) {
    return next(request);
  }
  return next(request.clone({ params: request.params.set('tenantId', tenant) }));
};
