import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';

/**
 * `StaffPasswordResetController` (ADR 0098), on this console's own prefix.
 *
 * All three are unauthenticated — somebody who has forgotten their password
 * has no session to present — and the token travels in a request body rather
 * than a URL, so it reaches no server or proxy access log.
 *
 * The prefix is not incidental. It is what tells the platform which console to
 * point the emailed link at, so an operator asking from here is sent back
 * here rather than to the operations console, which they may have no access to
 * at all.
 */
const REQUEST_PATH = '/api/v1/control-plane/auth/password-resets';
const INSPECT_PATH = '/api/v1/control-plane/auth/password-resets/inspect';
const ACCEPT_PATH = '/api/v1/control-plane/auth/password-resets/accept';

/** `PasswordResetService.ResetInspection`. The login is masked by the platform, never whole. */
export interface ResetInspection {
  readonly console: 'CONTROL_PLANE' | 'OPERATIONS' | (string & {});
  readonly maskedLogin: string | null;
  readonly expiresAt: string;
  readonly locale: 'uz' | 'ru' | 'en' | (string & {});
}

/**
 * `StaffPasswordResetController.PasswordResetAcceptance`.
 *
 * The password is set — that is what a success means here. What it does not
 * mean is that the account's other sessions are gone: Keycloak can refuse the
 * revocation while accepting the password, and the reset is complete either
 * way. So the page must read this rather than assert the happy sentence, and
 * the person who just reset their password is the only one present who could
 * act on its being false.
 */
export interface PasswordResetAcceptance {
  readonly sessionsEnded: boolean;
}

@Injectable({ providedIn: 'root' })
export class PasswordResetsApi {
  private readonly api = inject(ApiClient);

  /**
   * Asks for a reset.
   *
   * Resolves for an account that exists and for one that does not, because the
   * platform answers 202 either way (ADR 0098). There is deliberately nothing
   * here for a caller to branch on: a client that inferred "no such account"
   * from anything would be rebuilding the enumeration oracle the uniform
   * answer exists to remove.
   */
  request(login: string, locale: string): Promise<void> {
    return firstValueFrom(this.api.post<void>(REQUEST_PATH, { login, locale }));
  }

  inspect(token: string): Promise<ResetInspection> {
    return firstValueFrom(this.api.post<ResetInspection>(INSPECT_PATH, { token }));
  }

  accept(token: string, password: string): Promise<PasswordResetAcceptance> {
    return firstValueFrom(this.api.post<PasswordResetAcceptance>(ACCEPT_PATH, { token, password }));
  }
}
