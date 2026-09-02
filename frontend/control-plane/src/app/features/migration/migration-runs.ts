import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MigrationApi, ProgramView, ScopeView } from './migration-api';

@Component({
  selector: 'app-migration-runs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './migration-runs.html',
  styleUrl: './migration-runs.css',
})
export class MigrationRuns {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(MigrationApi);

  protected readonly name = signal('');
  protected readonly sourceEnvironment = signal('');
  protected readonly targetEnvironment = signal('horecaos-production');
  protected readonly policyVersion = signal('1');
  protected readonly reason = signal('');
  protected readonly submitting = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly program = signal<ProgramView | null>(null);
  protected readonly scopes = signal<readonly ScopeView[]>([]);
  protected readonly nextCursor = signal<string | null>(null);

  protected readonly scopeTenantId = signal('');
  protected readonly scopeCapability = signal('CATALOG');
  protected readonly scopeSourceOwner = signal('');
  protected readonly scopeTargetOwner = signal('');
  protected readonly scopeReason = signal('');
  protected readonly openingScope = signal(false);

  protected canSubmitProgram(): boolean {
    return (
      !this.submitting() &&
      this.name().trim().length > 0 &&
      this.sourceEnvironment().trim().length > 0 &&
      this.targetEnvironment().trim().length > 0 &&
      this.reason().trim().length > 0 &&
      Number(this.policyVersion()) > 0
    );
  }

  protected async findOrCreateProgram(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmitProgram()) {
      return;
    }
    this.submitting.set(true);
    this.loadError.set(null);
    try {
      const program = await this.api.createOrFindProgram(
        this.name().trim(),
        this.sourceEnvironment().trim(),
        this.targetEnvironment().trim(),
        Number(this.policyVersion()),
        this.reason().trim(),
      );
      this.program.set(program);
      await this.loadScopes(program.id);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.submitting.set(false);
    }
  }

  private async loadScopes(programId: string): Promise<void> {
    try {
      const page = await this.api.listScopes(programId);
      this.scopes.set(page.items);
      this.nextCursor.set(page.nextCursor);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected canSubmitScope(): boolean {
    return (
      !this.openingScope() &&
      this.program() !== null &&
      this.scopeTenantId().trim().length > 0 &&
      this.scopeSourceOwner().trim().length > 0 &&
      this.scopeTargetOwner().trim().length > 0 &&
      this.scopeReason().trim().length > 0
    );
  }

  protected async openScope(event: Event): Promise<void> {
    event.preventDefault();
    const program = this.program();
    if (program === null || !this.canSubmitScope()) {
      return;
    }
    this.openingScope.set(true);
    this.loadError.set(null);
    try {
      await this.api.openScope(program.id, {
        tenantId: this.scopeTenantId().trim(),
        capability: this.scopeCapability(),
        sourceOwner: this.scopeSourceOwner().trim(),
        targetOwner: this.scopeTargetOwner().trim(),
        reason: this.scopeReason().trim(),
      });
      this.scopeTenantId.set('');
      this.scopeSourceOwner.set('');
      this.scopeTargetOwner.set('');
      this.scopeReason.set('');
      await this.loadScopes(program.id);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.openingScope.set(false);
    }
  }
}
