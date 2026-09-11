import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { command } from '../../core/api/idempotency';

/** `StaffInvitationController` (ADR 0097). Both unauthenticated; the token travels in the body. */
const INSPECT_PATH = '/api/v1/operations/invitations/inspect';
const ACCEPT_PATH = '/api/v1/operations/invitations/accept';

/** `OwnerInvitationService.Inspection`. */
export interface InvitationInspection {
  readonly tenantName: string;
  readonly emailMasked: string | null;
  readonly expiresAt: string;
  readonly locale: 'uz' | 'ru' | 'en' | (string & {});
}

/** `OwnerInvitationService.Accepted`: the name the owner now signs in with. */
export interface InvitationAccepted {
  readonly signInName: string;
}

@Injectable({ providedIn: 'root' })
export class InvitationsApi {
  private readonly api = inject(ApiClient);

  inspect(token: string): Promise<InvitationInspection> {
    return firstValueFrom(
      this.api.post<{ token: string }, InvitationInspection>(INSPECT_PATH, command({ token })),
    );
  }

  accept(
    token: string,
    firstName: string,
    lastName: string,
    password: string,
  ): Promise<InvitationAccepted> {
    return firstValueFrom(
      this.api.post<
        { token: string; firstName: string; lastName: string; password: string },
        InvitationAccepted
      >(ACCEPT_PATH, command({ token, firstName, lastName, password })),
    );
  }
}
