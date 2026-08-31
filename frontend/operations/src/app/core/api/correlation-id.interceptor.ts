import { HttpInterceptorFn } from '@angular/common/http';

/**
 * Stamps platform API requests with `X-Correlation-Id` (ADR 0031).
 *
 * The server generates one when absent, so this is not strictly required. It is
 * here because a correlation id minted in the browser covers the hop the server
 * cannot see: when a request never arrives, this is the only identifier that
 * exists on both sides of the failure.
 *
 * Only same-origin requests are stamped. The OIDC library fetches Keycloak's
 * discovery document and token endpoint through this same HttpClient, and
 * Keycloak's CORS preflight rejects the unexpected header outright — which
 * blank-screened the whole app before sign-in could even start. A correlation
 * id on a request to an identity provider we do not operate buys nothing and
 * breaks everything; the platform API is same-origin (dev proxy and production
 * alike), so the origin check is the exact boundary ADR 0031 cares about.
 *
 * The value is a fresh UUID per request and carries nothing about the operator.
 * A correlation id that encodes a user, a session or a device is personal data
 * being written into every log line the request touches, which ADR 0029 forbids.
 */
export const correlationIdInterceptor: HttpInterceptorFn = (request, next) => {
  const sameOrigin = request.url.startsWith('/') || request.url.startsWith(window.location.origin);
  if (!sameOrigin || request.headers.has(CORRELATION_ID_HEADER)) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { [CORRELATION_ID_HEADER]: crypto.randomUUID() } }));
};

export const CORRELATION_ID_HEADER = 'X-Correlation-Id';
