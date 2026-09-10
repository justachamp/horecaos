import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { I18nService } from '../../core/i18n/i18n.service';
import { TenantDirectory } from '../../shared/tenant-directory';
import { MigrationApi, ProgramView, ScopeView } from './migration-api';
import { capabilityKey, stateKey } from './migration-labels';
import { rememberProgram, rememberedProgram } from './program-memory';


/**
 * Choosing a migration scope by program, tenant and capability rather than by
 * pasting its id. Remembers the program across screens for the session.
 */
@Component({
  selector: 'app-migration-scope-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="scopePicker">
      <label class="field">
        <span class="q-caption">{{ i18n.t('migrationPicker.program') }}</span>
        <select class="q-body-sm" name="program" (change)="chooseProgram($any($event.target).value)">
          <option value="" [selected]="programId() === ''">{{ i18n.t('migrationPicker.chooseProgram') }}</option>
          @for (program of programs(); track program.id) {
            <option [value]="program.id" [selected]="program.id === programId()">{{ program.name }}</option>
          }
        </select>
      </label>
      <label class="field">
        <span class="q-caption">{{ i18n.t('migrationPicker.scope') }}</span>
        <select class="q-body-sm" name="scope" [disabled]="scopes().length === 0" (change)="chooseScope($any($event.target).value)">
          <option value="" [selected]="scopeId() === ''">{{ i18n.t('migrationPicker.chooseScope') }}</option>
          @for (scope of scopes(); track scope.id) {
            <option [value]="scope.id" [selected]="scope.id === scopeId()">{{ label(scope) }}</option>
          }
        </select>
      </label>
    </div>
    @if (error(); as message) {
      <p class="q-body-sm pickerError">{{ message }}</p>
    }
  `,
  styles: `
    .scopePicker {
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
      margin-top: 16px;
    }
    .field {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 240px;
    }
    select {
      border: 1px solid var(--q-hairline);
      padding: 8px;
      background: var(--q-canvas);
      color: var(--q-ink);
    }
    .pickerError {
      color: var(--q-error-text);
    }
  `,
})
export class ScopePicker {
  protected readonly i18n = inject(I18nService);
  private readonly api = inject(MigrationApi);
  private readonly directory = inject(TenantDirectory);

  readonly scopeChange = output<ScopeView | null>();

  protected readonly programs = signal<readonly ProgramView[]>([]);
  protected readonly scopes = signal<readonly ScopeView[]>([]);
  protected readonly programId = signal('');
  protected readonly scopeId = signal('');
  protected readonly error = signal<string | null>(null);

  constructor() {
    void this.directory.load();
    void this.loadPrograms();
  }

  private async loadPrograms(): Promise<void> {
    try {
      const page = await this.api.listPrograms();
      this.programs.set(page.items);
      const remembered = rememberedProgram();
      if (remembered && page.items.some((program) => program.id === remembered)) {
        await this.chooseProgram(remembered);
      } else if (page.items.length === 1) {
        await this.chooseProgram(page.items[0].id);
      }
    } catch (error) {
      this.error.set(this.i18n.describe(error as ApiError));
    }
  }

  protected async chooseProgram(programId: string): Promise<void> {
    this.programId.set(programId);
    this.scopeId.set('');
    this.scopes.set([]);
    this.scopeChange.emit(null);
    rememberProgram(programId);
    if (programId.length === 0) {
      return;
    }
    try {
      this.scopes.set((await this.api.listScopes(programId, null, 200)).items);
    } catch (error) {
      this.error.set(this.i18n.describe(error as ApiError));
    }
  }

  protected chooseScope(scopeId: string): void {
    this.scopeId.set(scopeId);
    this.scopeChange.emit(this.scopes().find((scope) => scope.id === scopeId) ?? null);
  }

  protected label(scope: ScopeView): string {
    return `${this.directory.nameOf(scope.tenantId)} · ${this.i18n.t(capabilityKey(scope.capability))} · ${this.i18n.t(stateKey(scope.state))}`;
  }
}
