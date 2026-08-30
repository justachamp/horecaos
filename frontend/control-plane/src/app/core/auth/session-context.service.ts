import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../api/api-client';
import { Capability, SessionContext } from './capability';

/**
 * What the signed-in principal may do, from `GET /api/v1/session/context`.
 *
 * The server is explicit that this view is not an authorization decision: it
 * exists so a frontend can hide a control it cannot use, and every mutation is
 * authorized again server-side. A test on the platform asserts this view and
 * server enforcement agree, which is what makes it safe to navigate by.
 */
@Injectable({ providedIn: 'root' })
export class SessionContextService {
  private readonly api = inject(ApiClient);

  private readonly context = signal<SessionContext | null>(null);
  readonly current = this.context.asReadonly();

  /** Loaded, versus not-yet-loaded or unreachable. Distinguishes empty from unknown. */
  readonly loaded = computed(() => this.context() !== null);

  private readonly held = computed<ReadonlySet<string>>(
    () => new Set(this.context()?.capabilities ?? []),
  );

  /**
   * True only when the capability is known to be held.
   *
   * Unknown answers false, so nothing is offered before the context has
   * loaded. The opposite default would flash a full navigation on every start
   * and then remove half of it, which reads as a bug.
   */
  has(capability: Capability): boolean {
    return this.held().has(capability);
  }

  hasAll(capabilities: readonly Capability[]): boolean {
    return capabilities.every((capability) => this.has(capability));
  }

  async load(): Promise<void> {
    try {
      this.context.set(await firstValueFrom(this.api.get<SessionContext>('/api/v1/session/context')));
    } catch {
      // Left null. The shell renders a degraded state rather than an empty
      // navigation that looks like a permissions problem.
      this.context.set(null);
    }
  }

  clear(): void {
    this.context.set(null);
  }
}
