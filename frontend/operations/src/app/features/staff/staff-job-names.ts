import { Injectable, signal } from '@angular/core';

import type { ScopeGrant } from '../../core/auth/session-context';
import type { RoleDescriptor } from './staff-api';

/**
 * The names a company gave its own jobs (ADR 0186), by the code a grant carries.
 *
 * A code-owned job has a translated name (`staff-role-labels.ts`). A job the company defined
 * has one free-text name in whatever language its author wrote, and a grant names it only by a
 * generated code (`job-3f9a1c0b2d`), which is never something a person should be shown. This
 * remembers the names from the two places that carry them - the job list a manager reads
 * (`StaffApi.roles`) and the session context a holder reads about herself - so every screen that
 * labels a job says «Старший кассир» and not the code.
 *
 * **Remembers, never forgets.** A closed job drops out of the list, but a revoked grant in the
 * history still names it, and the history should keep saying what the job was called.
 */
@Injectable({ providedIn: 'root' })
export class StaffJobNames {
  private readonly byCode = signal<ReadonlyMap<string, string>>(new Map());

  /** From a job list: the company's own jobs, with their names. */
  remember(roles: readonly RoleDescriptor[]): void {
    const learned = new Map<string, string>();
    for (const role of roles) {
      if (role.custom === true && typeof role.name === 'string' && role.name !== '') {
        learned.set(role.code, role.name);
      }
    }
    this.merge(learned);
  }

  /** From the session context: the jobs the signed-in person holds. */
  rememberFromScopes(scopes: readonly ScopeGrant[]): void {
    const learned = new Map<string, string>();
    for (const scope of scopes) {
      if (typeof scope.roleName === 'string' && scope.roleName !== '') {
        learned.set(scope.roleCode, scope.roleName);
      }
    }
    this.merge(learned);
  }

  /** The company's own name for this job code, or null for a code-owned job or one not yet seen. */
  nameOf(code: string): string | null {
    return this.byCode().get(code) ?? null;
  }

  private merge(learned: ReadonlyMap<string, string>): void {
    if (learned.size === 0) {
      return;
    }
    this.byCode.update((previous) => new Map([...previous, ...learned]));
  }
}
