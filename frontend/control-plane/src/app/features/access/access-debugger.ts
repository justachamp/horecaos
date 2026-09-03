import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { AccessApi, AccessDebugResponse } from './access-api';

/**
 * IA 7.3 Effective access debugger -- "can this principal do this, on this
 * resource, and why" (ADR 0003 + grants cache).
 *
 * Reuses `AuthorizationService.viewFor` and `.has`, the exact functions the
 * server itself calls on every request -- this is not a re-derivation of the
 * decision, it is the decision, asked about somebody else. platform-admin
 * only: a principal reading its own access already has `/session/context`
 * with no extra gate, and reading a colleague's grants is a different act.
 */
@Component({
  selector: 'app-access-debugger',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './access-debugger.html',
  styleUrl: './access-debugger.css',
})
export class AccessDebugger {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(AccessApi);

  protected readonly subject = signal('');
  protected readonly tenantId = signal('');
  protected readonly brandId = signal('');
  protected readonly locationId = signal('');
  protected readonly capability = signal('');

  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly result = signal<AccessDebugResponse | null>(null);

  protected canSubmit(): boolean {
    return !this.loading() && this.subject().trim().length > 0;
  }

  protected async debug(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmit()) {
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    try {
      this.result.set(
        await this.api.debugAccess(
          this.subject().trim(),
          this.tenantId().trim() || undefined,
          this.brandId().trim() || undefined,
          this.locationId().trim() || undefined,
          this.capability().trim() || undefined,
        ),
      );
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
      this.result.set(null);
    } finally {
      this.loading.set(false);
    }
  }
}
