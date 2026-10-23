import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';

/**
 * `CourierAccessController` (ADR 0178): a courier setting their own password from the link they were texted.
 *
 * Both calls are unauthenticated -- somebody with no password has no session to present -- and the token
 * travels in a request body rather than a URL, so it reaches no server or proxy access log. What the page
 * learns from the link is its kind, its language and when it stops working; never a number, never an account.
 */
const INSPECT_PATH = '/api/v1/operations/courier-access/inspect';
const ACCEPT_PATH = '/api/v1/operations/courier-access/accept';

/** `CourierAccessService.Inspection`. */
export interface CourierAccessInspection {
  /** `INVITE` (a first sign-in) or `RESET`. */
  readonly kind: 'INVITE' | 'RESET' | (string & {});
  /** The registry's tag the text was written in (ADR 0149). */
  readonly locale: string;
  readonly expiresAt: string;
}

/**
 * `CourierAccessController.CourierAccessAcceptance`: the password is set, which is what a success means.
 * `sessionsEnded` is false when the identity provider took the password and refused to end the account's other
 * sessions, and the page must say so rather than assert the happy sentence.
 */
export interface CourierAccessAcceptance {
  readonly sessionsEnded: boolean;
}

@Injectable({ providedIn: 'root' })
export class CourierAccessApi {
  private readonly api = inject(ApiClient);

  inspect(token: string): Promise<CourierAccessInspection> {
    return firstValueFrom(
      this.api.post<{ token: string }, CourierAccessInspection>(INSPECT_PATH, command({ token })),
    );
  }

  accept(token: string, password: string): Promise<CourierAccessAcceptance> {
    return firstValueFrom(
      this.api.post<{ token: string; password: string }, CourierAccessAcceptance>(
        ACCEPT_PATH,
        command({ token, password }),
      ),
    );
  }
}
