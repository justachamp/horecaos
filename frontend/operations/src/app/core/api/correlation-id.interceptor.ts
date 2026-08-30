import { HttpInterceptorFn } from '@angular/common/http';

/**
 * Stamps every outbound request with `X-Correlation-Id` (ADR 0031).
 *
 * The server generates one when absent, so this is not strictly required. It is
 * here because a correlation id minted in the browser covers the hop the server
 * cannot see: when a request never arrives, this is the only identifier that
 * exists on both sides of the failure.
 *
 * The value is a fresh UUID per request and carries nothing about the operator.
 * A correlation id that encodes a user, a session or a device is personal data
 * being written into every log line the request touches, which ADR 0029 forbids.
 */
export const correlationIdInterceptor: HttpInterceptorFn = (request, next) => {
  if (request.headers.has(CORRELATION_ID_HEADER)) {
    return next(request);
  }
  return next(request.clone({ setHeaders: { [CORRELATION_ID_HEADER]: crypto.randomUUID() } }));
};

export const CORRELATION_ID_HEADER = 'X-Correlation-Id';
