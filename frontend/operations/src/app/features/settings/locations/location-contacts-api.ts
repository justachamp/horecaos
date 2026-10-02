import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../../core/api/api-client';
import { command } from '../../../core/api/idempotency';
import { LocationScope } from '../../../core/api/operations-paths';
import { staffPaths } from '../../../core/api/staff-paths';

/** `LocationContactPersonService.RELATIONSHIPS`. */
export const CONTACT_RELATIONSHIPS = [
  'MANAGER',
  'OWNER',
  'LANDLORD',
  'SECURITY',
  'MAINTENANCE',
  'OTHER',
] as const;
export type ContactRelationship = (typeof CONTACT_RELATIONSHIPS)[number];

/** The most contact persons one branch can list (`LocationContactPersonService.MAX_CONTACTS`). */
export const MAX_BRANCH_CONTACTS = 10;

/**
 * Mirrors `LocationContactPersonController.ContactPersonResponse`.
 *
 * A **colleague** carries `staffMemberId` and the name and phone the tenant
 * keeps for them *now* -- nothing is copied, so a number cannot drift from the
 * person's own record. An **outside person** carries a name and a phone typed
 * for them: a third party's data, render it and never log it (ADR 0029).
 */
export interface BranchContactPerson {
  readonly id: string;
  readonly relationshipCode: ContactRelationship;
  readonly staffMemberId: string | null;
  readonly staffMemberReference: string | null;
  readonly name: string | null;
  readonly phone: string | null;
}

/** Mirrors `LocationContactPersonController.ContactPersonsResponse`; `version` is the *location's*. */
export interface BranchContactPersons {
  readonly contacts: readonly BranchContactPerson[];
  readonly version: number;
}

/**
 * Mirrors `LocationContactPersonController.ContactPersonRequest`: a colleague
 * is `staffMemberId` alone (the platform refuses a copied name or phone), an
 * outside person is `name` and `phone`, never both shapes.
 */
export interface BranchContactInput {
  readonly relationshipCode: ContactRelationship;
  readonly staffMemberId?: string | null;
  readonly name?: string | null;
  readonly phone?: string | null;
}

/**
 * A branch's named contact persons (ADR 0139, gap map row `9.2b`): who to call
 * about a branch. **Not** the published line customers and couriers see --
 * `contactPhone` on the location is that, and is untouched here.
 *
 * Reading needs `location.read` and changing `location.write`, both at the
 * branch's own scope. The set is replaced as a whole under the *location's*
 * version, which the replace moves.
 */
@Injectable({ providedIn: 'root' })
export class LocationContactsApi {
  private readonly api = inject(ApiClient);

  async list(scope: LocationScope): Promise<BranchContactPersons> {
    const result = await firstValueFrom(
      this.api.get<BranchContactPersons>(staffPaths.locationContactPersons(scope)),
    );
    return result.value;
  }

  async replace(
    scope: LocationScope,
    contacts: readonly BranchContactInput[],
    version: number,
  ): Promise<BranchContactPersons> {
    return firstValueFrom(
      this.api.put<{ contacts: readonly BranchContactInput[] }, BranchContactPersons>(
        staffPaths.locationContactPersons(scope),
        command({ contacts }),
        { expectedVersion: version },
      ),
    );
  }
}
