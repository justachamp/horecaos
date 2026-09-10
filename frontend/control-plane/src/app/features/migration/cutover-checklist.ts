import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { MessageKey } from '../../core/i18n/messages.en';
import { Colleagues } from '../../shared/colleagues';
import { TenantDirectory } from '../../shared/tenant-directory';
import { MigrationApi, ProgramView, ScopeView } from './migration-api';
import { capabilityKey, stateKey } from './migration-labels';
import { rememberProgram, rememberedProgram } from './program-memory';

/** One line of the evidence a cutover decision rests on. */
interface EvidenceLine {
  readonly key: string;
  readonly value: string;
}

type Decision = 'decide' | 'rollback';

const READY_STATES = new Set(['CUTOVER_READY', 'TARGET_OWNED', 'ROLLBACK_WINDOW', 'LEGACY_READ_ONLY', 'RETIRED']);
const BLOCKED_STATES = new Set(['BLOCKED_RECONCILIATION', 'PAUSED', 'ROLLING_BACK']);
const MAX_EVIDENCE = 32;

/**
 * IA 9.4 Cutover checklist -- go or no-go for every scope of a program, and
 * the decision itself.
 *
 * A scope that is ready is approved (HorecaOS takes over its writes) or
 * refused (it stays where it is; the refusal is recorded too). Either way the
 * decision names who asked for it -- never the person approving -- and the
 * figures it rests on. The server re-checks the gates at that moment, so a
 * critical difference found overnight still stops a window approved the
 * evening before. A scope already cut over, or in canary, can be rolled back.
 */
@Component({
  selector: 'app-cutover-checklist',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './cutover-checklist.html',
  styleUrl: './cutover-checklist.css',
})
export class CutoverChecklist {
  protected readonly i18n = inject(I18nService);
  protected readonly session = inject(SessionContextService);
  protected readonly directory = inject(TenantDirectory);
  private readonly api = inject(MigrationApi);
  private readonly colleagues = inject(Colleagues);

  protected readonly capabilityKey = capabilityKey;
  protected readonly stateKey = stateKey;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly programs = signal<readonly ProgramView[]>([]);
  protected readonly programId = signal('');
  protected readonly scopes = signal<readonly ScopeView[]>([]);

  protected readonly openScope = signal<string | null>(null);
  protected readonly mode = signal<Decision>('decide');
  protected readonly requestedBy = signal('');
  protected readonly evidence = signal<readonly EvidenceLine[]>([]);
  protected readonly reason = signal('');
  protected readonly approvers = signal<readonly string[]>([]);
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  protected readonly counts = computed(() => {
    const tally = { go: 0, blocked: 0, pending: 0 };
    this.scopes().forEach((scope) => (tally[this.checklistState(scope)] += 1));
    return tally;
  });

  constructor() {
    void this.directory.load();
    void this.loadPrograms();
  }

  private async loadPrograms(): Promise<void> {
    this.loading.set(true);
    try {
      const page = await this.api.listPrograms();
      this.programs.set(page.items);
      const remembered = rememberedProgram();
      const chosen = page.items.find((p) => p.id === remembered) ?? (page.items.length === 1 ? page.items[0] : null);
      if (chosen) {
        await this.chooseProgram(chosen.id);
      }
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    } finally {
      this.loading.set(false);
    }
  }

  protected async chooseProgram(programId: string): Promise<void> {
    this.programId.set(programId);
    this.openScope.set(null);
    this.scopes.set([]);
    rememberProgram(programId);
    if (programId.length > 0) {
      await this.loadScopes();
    }
  }

  private async loadScopes(): Promise<void> {
    try {
      this.scopes.set((await this.api.listScopes(this.programId(), null, 200)).items);
    } catch (error) {
      this.loadError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected checklistState(scope: ScopeView): 'go' | 'blocked' | 'pending' {
    if (READY_STATES.has(scope.state)) {
      return 'go';
    }
    return BLOCKED_STATES.has(scope.state) ? 'blocked' : 'pending';
  }

  protected readinessKey(scope: ScopeView): MessageKey {
    return `cutoverChecklist.readiness.${this.checklistState(scope)}` as MessageKey;
  }

  protected canDecide(scope: ScopeView): boolean {
    return scope.state === 'CUTOVER_READY' && this.session.has('MIGRATION_CUTOVER_APPROVE');
  }

  protected canRollBack(scope: ScopeView): boolean {
    return scope.nextStates.includes('ROLLING_BACK') && this.session.has('MIGRATION_CUTOVER_APPROVE');
  }

  /**
   * Opens the decision for a ready scope, with the evidence filled in from its
   * latest completed reconciliation: the run, where it read up to, and what it
   * produced. The operator can add to it or change it before signing.
   */
  protected async open(scope: ScopeView, mode: Decision): Promise<void> {
    if (this.openScope() === scope.id && this.mode() === mode) {
      this.openScope.set(null);
      return;
    }
    this.openScope.set(scope.id);
    this.mode.set(mode);
    this.reason.set('');
    this.requestedBy.set('');
    this.actionError.set(null);
    this.evidence.set([]);
    if (mode === 'rollback') {
      return;
    }
    if (this.approvers().length === 0) {
      this.approvers.set(await this.colleagues.others());
    }
    try {
      const runs = (await this.api.listRuns(scope)).items;
      const reconciled = runs.find((run) => run.runType === 'RECONCILIATION' && run.status === 'COMPLETED');
      const lines: EvidenceLine[] = [];
      if (reconciled) {
        lines.push({ key: 'reconciliationRunId', value: reconciled.id });
        if (reconciled.sourceWatermark) {
          lines.push({ key: 'sourceWatermark', value: reconciled.sourceWatermark });
        }
        if (reconciled.checksum) {
          lines.push({ key: 'checksum', value: reconciled.checksum });
        }
      }
      this.evidence.set(lines.length > 0 ? lines : [{ key: '', value: '' }]);
    } catch {
      this.evidence.set([{ key: '', value: '' }]);
    }
  }

  protected updateEvidence(index: number, change: Partial<EvidenceLine>): void {
    this.evidence.update((lines) => lines.map((line, at) => (at === index ? { ...line, ...change } : line)));
  }

  protected addEvidence(): void {
    if (this.evidence().length < MAX_EVIDENCE) {
      this.evidence.update((lines) => [...lines, { key: '', value: '' }]);
    }
  }

  protected removeEvidence(index: number): void {
    this.evidence.update((lines) => lines.filter((_, at) => at !== index));
  }

  /** The evidence as sent: blank lines dropped, and null while a filled line is incomplete or too long. */
  private evidenceMap(): Record<string, string> | null {
    const map: Record<string, string> = {};
    for (const line of this.evidence()) {
      const key = line.key.trim();
      const value = line.value.trim();
      if (key.length === 0 && value.length === 0) {
        continue;
      }
      if (key.length === 0 || value.length === 0 || key.length > 64 || value.length > 512 || key in map) {
        return null;
      }
      map[key] = value;
    }
    return Object.keys(map).length > 0 ? map : null;
  }

  protected canSign(): boolean {
    const me = this.session.current()?.subject;
    const requester = this.requestedBy().trim();
    return (
      !this.busy() &&
      requester.length > 0 &&
      requester !== me &&
      this.reason().trim().length > 0 &&
      this.evidenceMap() !== null
    );
  }

  protected async decide(scope: ScopeView, decision: 'approve' | 'refuse'): Promise<void> {
    const evidence = this.evidenceMap();
    if (!this.canSign() || evidence === null) {
      return;
    }
    await this.run(async () => {
      await this.api.decideCutover(scope, decision, {
        requestedBy: this.requestedBy().trim(),
        evidence,
        reason: this.reason().trim(),
      });
      return this.i18n.t(decision === 'approve' ? 'cutoverChecklist.approved' : 'cutoverChecklist.refused', {
        tenant: this.directory.nameOf(scope.tenantId),
      });
    });
  }

  protected async rollBack(scope: ScopeView): Promise<void> {
    const reason = this.reason().trim();
    if (reason.length === 0 || this.busy()) {
      return;
    }
    await this.run(async () => {
      await this.api.rollBackScope(scope, reason);
      return this.i18n.t('cutoverChecklist.rolledBack', { tenant: this.directory.nameOf(scope.tenantId) });
    });
  }

  private async run(write: () => Promise<string>): Promise<void> {
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      const message = await write();
      this.openScope.set(null);
      await this.loadScopes();
      this.actionMessage.set(message);
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
      await this.loadScopes();
    } finally {
      this.busy.set(false);
    }
  }
}
