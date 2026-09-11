import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';

/**
 * `StaffPasswordResetController` (ADR 0098), on this console's own prefix.
 *
 * All three are unauthenticated — somebody who has forgotten their password
 * has no session to present — and the token travels in a request body rather
 * than a URL, so it reaches no server or proxy access log.
 *
 * The prefix is not incidental. It is what tells the platform which console to
 * point the emailed link at, so an operator asking from here is sent back
 * here.
 */
const REQUEST_PATH = '/api/v1/operations/auth/password-resets';
const INSPECT_PATH = '/api/v1/operations/auth/password-resets/inspect';
const ACCEPT_PATH = '/api/v1/operations/auth/password-resets/accept';

/** `PasswordResetService.ResetInspection`. The login is masked by the platform, never whole. */
export interface ResetInspection {
  readonly console: 'CONTROL_PLANE' | 'OPERATIONS' | (string & {});
  readonly maskedLogin: string | null;
  readonly expiresAt: string;
  readonly locale: 'uz' | 'ru' | 'en' | (string & {});
}

@Injectable({ providedIn: 'root' })
export class PasswordResetsApi {
  private readonly api = inject(ApiClient);

  /**
   * Asks for a reset.
   *
   * Resolves for an account that exists and for one that does not, because the
   * platform answers 202 either way (ADR 0098) — there is deliberately nothing
   * here for a caller to branch on, and a caller that tried would be
   * reintroducing the enumeration the uniform answer exists to prevent.
   */
  request(login: string, locale: string): Promise<void> {
    return firstValueFrom(
      this.api.post<{ login: string; locale: string }, void>(
        REQUEST_PATH,
        command({ login, locale }),
      ),
    );
  }

  inspect(token: string): Promise<ResetInspection> {
    return firstValueFrom(
      this.api.post<{ token: string }, ResetInspection>(INSPECT_PATH, command({ token })),
    );
  }

  accept(token: string, password: string): Promise<void> {
    return firstValueFrom(
      this.api.post<{ token: string; password: string }, void>(
        ACCEPT_PATH,
        command({ token, password }),
      ),
    );
  }
}
