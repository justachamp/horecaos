import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { StaffSessionResponse } from './staff-session';

/** `/api/v1/control-plane/auth/mfa/*` — a platform account's own second factor (ADR 0148). */
export const MFA_BASE = '/api/v1/control-plane/auth/mfa';

/**
 * What a person scans and what the confirming call carries back (`StaffMfaService.Enrolment`).
 * `secret` is the same secret as `otpauthUri` carries, in the Base32 text an authenticator app
 * accepts typed in; it is shown once and kept nowhere.
 */
export interface MfaEnrolment {
  readonly sealedSecret: string;
  readonly otpauthUri: string;
  readonly secret: string;
  readonly expiresAt: string;
}

/**
 * The wire calls of enrolling an authenticator from this console (ADR 0148). Both take the
 * `enrolmentTicket` a refused sign-in answered with — an account the platform requires a factor
 * of has no session to present — and both re-prove the current password, so a ticket alone
 * enrols nothing. Mirrors `frontend/operations`' `MfaApi` for the calls this console needs.
 */
@Injectable({ providedIn: 'root' })
export class MfaApi {
  private readonly api = inject(ApiClient);

  begin(password: string, enrolmentTicket: string): Promise<MfaEnrolment> {
    return firstValueFrom(
      this.api.post<MfaEnrolment>(`${MFA_BASE}/enrolments`, { password, enrolmentTicket }),
    );
  }

  /** The new session: a confirmation begun from a ticket answers 201 with one. */
  async confirm(request: {
    readonly sealedSecret: string;
    readonly code: string;
    readonly password: string;
    readonly label: string | null;
    readonly enrolmentTicket: string;
  }): Promise<StaffSessionResponse | null> {
    const body = {
      sealedSecret: request.sealedSecret,
      code: request.code,
      password: request.password,
      ...(request.label === null || request.label.trim() === ''
        ? {}
        : { label: request.label.trim() }),
      enrolmentTicket: request.enrolmentTicket,
    };
    return firstValueFrom(
      this.api.post<StaffSessionResponse | null>(`${MFA_BASE}/enrolments/confirm`, body),
    );
  }
}
