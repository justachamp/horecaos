import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { MigrationApi, ProgramView, ScopeView } from './migration-api';

/**
 * IA 9.4 Cutover checklist -- go/no-go per tenant (ADR 0024).
 *
 * Every scope's own state machine already carries the go/no-go the row
 * asks for: `CUTOVER_READY` is go, `BLOCKED_RECONCILIATION` is a named no,
 * and everything else in between is neither yet. Reuses 9.1's own
 * find-a-program-by-name lookup (`MigrationProgramController` has no
 * list-all endpoint) and its scope list, read rather than mutated here --
 * the mutating half of cutover (`MigrationScopeController.cutOver`/
 * `refuseCutover`) belongs to whoever operates a specific cutover window,
 * not to a checklist screen scanning every scope in a program at once.
 */
@Component({
  selector: 'app-cutover-checklist',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './cutover-checklist.html',
  styleUrl: './cutover-checklist.css',
})
export class CutoverChecklist {
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

  private static readonly READY_STATES = new Set(['CUTOVER_READY', 'TARGET_OWNED', 'RETIRED']);
  private static readonly BLOCKED_STATES = new Set(['BLOCKED_RECONCILIATION']);

  protected canSubmit(): boolean {
    return (
      !this.submitting() &&
      this.name().trim().length > 0 &&
      this.sourceEnvironment().trim().length > 0 &&
      this.targetEnvironment().trim().length > 0 &&
      this.reason().trim().length > 0 &&
      Number(this.policyVersion()) > 0
    );
  }

  protected async findProgram(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canSubmit()) {
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
      const page = await this.api.listScopes(program.id, null, 200);
      this.scopes.set(page.items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.submitting.set(false);
    }
  }

  protected checklistState(scope: ScopeView): 'go' | 'blocked' | 'pending' {
    if (CutoverChecklist.READY_STATES.has(scope.state)) {
      return 'go';
    }
    if (CutoverChecklist.BLOCKED_STATES.has(scope.state)) {
      return 'blocked';
    }
    return 'pending';
  }

  protected readinessKey(scope: ScopeView): MessageKey {
    return `cutoverChecklist.readiness.${this.checklistState(scope)}` as MessageKey;
  }
}
