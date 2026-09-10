import { Injectable, inject } from '@angular/core';

import { SessionContextService } from '../core/auth/session-context.service';
import { AccessApi } from '../features/access/access-api';

/**
 * Platform staff other than the signed-in operator: the people who can be the
 * second name on a decision that needs two (an override, a usage correction).
 *
 * Empty when the list cannot be read. The form then asks for the name as
 * text; the server refuses the operator's own name either way.
 */
@Injectable({ providedIn: 'root' })
export class Colleagues {
  private readonly access = inject(AccessApi);
  private readonly session = inject(SessionContextService);

  async others(): Promise<string[]> {
    try {
      const grants = await this.access.listPlatformGrants();
      const me = this.session.current()?.subject;
      const subjects = grants
        .filter((grant) => grant.status === 'ACTIVE' && grant.principalSubject !== me)
        .map((grant) => grant.principalSubject);
      return [...new Set(subjects)].sort();
    } catch {
      return [];
    }
  }
}
