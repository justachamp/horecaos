import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { command } from '../api/idempotency';
import { StaffSessionResponse } from './staff-session';

/** `/api/v1/operations/auth/mfa/*` — a staff member's own second factor (ADR 0148). */
export const MFA_BASE = '/api/v1/operations/auth/mfa';

/** What the platform asks of this person: nothing, an offer, or a requirement (ADR 0148, Decision 4). */
export type MfaRequirement = 'NOT_REQUIRED' | 'OFFERED' | 'REQUIRED';

/** One authenticator. `label` is whatever the person typed when they added it; `createdAt` is Keycloak's date. */
export interface MfaAuthenticator {
  readonly id: string;
  readonly label: string | null;
  readonly createdAt: string | null;
}

export interface OwnMfa {
  readonly enrolled: boolean;
  readonly authenticators: readonly MfaAuthenticator[];
  readonly requirement: MfaRequirement;
  /** How many authenticators an account may hold. */
  readonly maximum: number;
}

/**
 * What a person scans and what the confirming call carries back (`StaffMfaService.Enrolment`).
 *
 * `secret` is the same secret as `otpauthUri` carries, in the Base32 text an authenticator app
 * accepts typed in. It is shown once, on this screen, and kept nowhere: not in a store, a URL
 * or a log. The platform keeps nothing either — `sealedSecret` is the secret sealed to this
 * account for ten minutes.
 */
export interface MfaEnrolment {
  readonly sealedSecret: string;
  readonly otpauthUri: string;
  readonly secret: string;
  readonly expiresAt: string;
}

/**
 * The wire calls of staff second-factor self-service (ADR 0148).
 *
 * The first two take either the signed-in session — attached by the bearer interceptor — or the
 * `enrolmentTicket` a refused sign-in answered with, which is what an account the platform has
 * just locked out of the console has instead of a session. Both re-prove the current password,
 * so a ticket alone enrols nothing.
 */
@Injectable({ providedIn: 'root' })
export class MfaApi {
  private readonly api = inject(ApiClient);

  begin(password: string, enrolmentTicket: string | null): Promise<MfaEnrolment> {
    const body = enrolmentTicket === null ? { password } : { password, enrolmentTicket };
    return firstValueFrom(
      this.api.post<typeof body, MfaEnrolment>(`${MFA_BASE}/enrolments`, command(body)),
    );
  }

  /**
   * Registers the authenticator with its first code.
   *
   * @returns the new session when the enrolment began from a ticket (the platform answers 201 with
   *   it), and `null` from a session (204): that person is already signed in.
   */
  async confirm(request: {
    readonly sealedSecret: string;
    readonly code: string;
    readonly password: string;
    readonly label: string | null;
    readonly enrolmentTicket: string | null;
  }): Promise<StaffSessionResponse | null> {
    const body = {
      sealedSecret: request.sealedSecret,
      code: request.code,
      password: request.password,
      ...(request.label === null || request.label.trim() === ''
        ? {}
        : { label: request.label.trim() }),
      ...(request.enrolmentTicket === null ? {} : { enrolmentTicket: request.enrolmentTicket }),
    };
    const response = await firstValueFrom(
      this.api.send<typeof body, StaffSessionResponse | null>(
        'POST',
        `${MFA_BASE}/enrolments/confirm`,
        command(body),
      ),
    );
    return response.status === 201 ? response.body : null;
  }

  own(): Promise<OwnMfa> {
    return firstValueFrom(this.api.get<OwnMfa>(`${MFA_BASE}/authenticators`)).then(
      (read) => read.value,
    );
  }

  /** Removes one of the caller's authenticators; needs the password and a valid code, and never takes the last one. */
  remove(authenticatorId: string, password: string, code: string): Promise<void> {
    const body = { password, code };
    return firstValueFrom(
      this.api.send<typeof body, void>(
        'DELETE',
        `${MFA_BASE}/authenticators/${encodeURIComponent(authenticatorId)}`,
        command(body),
      ),
    ).then(() => undefined);
  }
}
