import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { asDate } from '../../core/api/dates';
import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { TenantDirectory } from '../../shared/tenant-directory';
import { TenantPicker } from '../../shared/tenant-picker';
import { BrandView, LocationView, TenantsApi } from '../tenants/tenants-api';
import {
  HOLDING_STATES,
  MIGRATION_CAPABILITIES,
  MigrationApi,
  ProgramStatus,
  ProgramView,
  QuarantineItemView,
  RESOLUTION_CODES,
  RUN_TYPES,
  RunStatus,
  RunType,
  RunView,
  ScopeView,
} from './migration-api';
import {
  capabilityKey,
  programStatusKey,
  resolutionKey,
  runStatusKey,
  runTypeKey,
  stateKey,
} from './migration-labels';
import { rememberProgram, rememberedProgram } from './program-memory';

/** How a scope reaches a given next state: each has its own endpoint and, for two, its own capability. */
type MoveKind = 'advance' | 'suspend' | 'rollback' | 'cutover';

/** The program moves the server accepts from each status. */
const PROGRAM_NEXT: Readonly<Record<ProgramStatus, readonly ProgramStatus[]>> = {
  PLANNING: ['ACTIVE', 'ABANDONED'],
  ACTIVE: ['COMPLETED', 'ABANDONED'],
  COMPLETED: [],
  ABANDONED: [],
};

const CHECKSUM = /^[0-9a-f]{64}$/;

/**
 * IA 9.1 Migration runs -- moving tenants over from the legacy system.
 *
 * A program holds capability scopes (one tenant, brand or branch, one
 * capability such as orders). Each scope walks a fixed path from discovery to
 * retirement; runs import or reconcile data along the way, and rows that
 * could not be placed wait in quarantine until someone settles them. Taking
 * ownership happens on the cutover checklist, where a second person signs.
 */
@Component({
  selector: 'app-migration-runs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TenantPicker, RouterLink],
  templateUrl: './migration-runs.html',
  styleUrl: './migration-runs.css',
})
export class MigrationRuns {
  protected readonly i18n = inject(I18nService);
  protected readonly session = inject(SessionContextService);
  protected readonly directory = inject(TenantDirectory);
  private readonly api = inject(MigrationApi);
  private readonly tenantsApi = inject(TenantsApi);

  protected readonly asDate = asDate;
  protected readonly capabilityKey = capabilityKey;
  protected readonly stateKey = stateKey;
  protected readonly runTypeKey = runTypeKey;
  protected readonly runStatusKey = runStatusKey;
  protected readonly programStatusKey = programStatusKey;
  protected readonly resolutionKey = resolutionKey;
  protected readonly capabilities = MIGRATION_CAPABILITIES;
  protected readonly runTypes = RUN_TYPES;
  protected readonly resolutionCodes = RESOLUTION_CODES;
  protected readonly finishStatuses: readonly Exclude<RunStatus, 'RUNNING'>[] = ['COMPLETED', 'FAILED', 'CANCELLED'];

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly actionMessage = signal<string | null>(null);

  // ---------------------------------------------------------------- programs
  protected readonly programs = signal<readonly ProgramView[]>([]);
  protected readonly programId = signal('');
  protected readonly program = computed(() => this.programs().find((p) => p.id === this.programId()) ?? null);
  protected readonly programMove = signal<ProgramStatus | null>(null);
  protected readonly programReason = signal('');

  protected readonly registering = signal(false);
  protected readonly name = signal('');
  protected readonly sourceEnvironment = signal('');
  protected readonly targetEnvironment = signal('horecaos-production');
  protected readonly policyVersion = signal('1');
  protected readonly reason = signal('');

  // ---------------------------------------------------------------- scopes
  protected readonly scopes = signal<readonly ScopeView[]>([]);
  protected readonly scopeId = signal<string | null>(null);
  protected readonly scope = computed(() => this.scopes().find((s) => s.id === this.scopeId()) ?? null);

  protected readonly openingScope = signal(false);
  protected readonly scopeTenantId = signal(this.directory.selected());
  protected readonly brands = signal<readonly BrandView[]>([]);
  protected readonly locations = signal<readonly LocationView[]>([]);
  protected readonly scopeBrandId = signal('');
  protected readonly scopeLocationId = signal('');
  protected readonly scopeCapability = signal<string>('ORDERS');
  protected readonly scopeSourceOwner = signal('');
  protected readonly scopeTargetOwner = signal('');
  protected readonly scopeReason = signal('');

  protected readonly moveTarget = signal('');
  protected readonly moveReason = signal('');

  // ---------------------------------------------------------------- runs & quarantine
  protected readonly runs = signal<readonly RunView[]>([]);
  protected readonly quarantine = signal<readonly QuarantineItemView[]>([]);
  protected readonly runType = signal<RunType>('BACKFILL');
  protected readonly transformationVersion = signal('1');
  protected readonly startedBy = signal(this.session.current()?.subject ?? '');
  protected readonly runReason = signal('');
  protected readonly finishing = signal<string | null>(null);
  protected readonly finishStatus = signal<Exclude<RunStatus, 'RUNNING'>>('COMPLETED');
  protected readonly checksum = signal('');
  protected readonly finishReason = signal('');
  protected readonly settling = signal<string | null>(null);
  protected readonly resolutionCode = signal<string>(RESOLUTION_CODES[0]);
  protected readonly settleReason = signal('');

  constructor() {
    void this.directory.load();
    void this.loadPrograms(rememberedProgram());
  }

  private async loadPrograms(select: string | null): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      const page = await this.api.listPrograms();
      this.programs.set(page.items);
      const chosen = page.items.find((p) => p.id === select) ?? (page.items.length === 1 ? page.items[0] : null);
      if (chosen && chosen.id !== this.programId()) {
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
    this.programMove.set(null);
    this.scopeId.set(null);
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

  protected programMoves(program: ProgramView): readonly ProgramStatus[] {
    return PROGRAM_NEXT[program.status];
  }

  // ---------------------------------------------------------------- register / program status

  protected canRegister(): boolean {
    return (
      !this.busy() &&
      this.name().trim().length > 0 &&
      this.sourceEnvironment().trim().length > 0 &&
      this.targetEnvironment().trim().length > 0 &&
      this.reason().trim().length > 0 &&
      /^[1-9]\d*$/.test(this.policyVersion().trim())
    );
  }

  protected async register(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canRegister()) {
      return;
    }
    await this.run(async () => {
      const program = await this.api.createOrFindProgram(
        this.name().trim(),
        this.sourceEnvironment().trim(),
        this.targetEnvironment().trim(),
        Number(this.policyVersion().trim()),
        this.reason().trim(),
      );
      this.registering.set(false);
      this.name.set('');
      this.reason.set('');
      await this.loadPrograms(program.id);
      return this.i18n.t('migrationRuns.program.registered', { name: program.name });
    });
  }

  protected async moveProgram(program: ProgramView): Promise<void> {
    const to = this.programMove();
    const reason = this.programReason().trim();
    if (to === null || reason.length === 0 || this.busy()) {
      return;
    }
    await this.run(async () => {
      await this.api.changeProgramStatus(program.id, to, program.version, reason);
      this.programMove.set(null);
      this.programReason.set('');
      await this.loadPrograms(program.id);
      return this.i18n.t('migrationRuns.program.moved', { status: this.i18n.t(programStatusKey(to)) });
    });
  }

  // ---------------------------------------------------------------- open a scope

  protected async chooseScopeTenant(tenantId: string): Promise<void> {
    this.scopeTenantId.set(tenantId);
    this.scopeBrandId.set('');
    this.scopeLocationId.set('');
    this.brands.set([]);
    this.locations.set([]);
    if (tenantId.length > 0) {
      this.brands.set(await this.tenantsApi.getBrands(tenantId).catch(() => []));
    }
  }

  protected async chooseScopeBrand(brandId: string): Promise<void> {
    this.scopeBrandId.set(brandId);
    this.scopeLocationId.set('');
    this.locations.set(
      brandId.length > 0 ? await this.tenantsApi.getLocations(this.scopeTenantId(), brandId).catch(() => []) : [],
    );
  }

  protected canOpenScope(): boolean {
    return (
      !this.busy() &&
      this.program() !== null &&
      this.scopeTenantId().length > 0 &&
      this.scopeSourceOwner().trim().length > 0 &&
      this.scopeTargetOwner().trim().length > 0 &&
      this.scopeReason().trim().length > 0
    );
  }

  protected async openScope(event: Event): Promise<void> {
    event.preventDefault();
    const program = this.program();
    if (program === null || !this.canOpenScope()) {
      return;
    }
    await this.run(async () => {
      const scope = await this.api.openScope(program.id, {
        tenantId: this.scopeTenantId(),
        brandId: this.scopeBrandId() || undefined,
        locationId: this.scopeLocationId() || undefined,
        capability: this.scopeCapability(),
        sourceOwner: this.scopeSourceOwner().trim(),
        targetOwner: this.scopeTargetOwner().trim(),
        reason: this.scopeReason().trim(),
      });
      this.openingScope.set(false);
      this.scopeSourceOwner.set('');
      this.scopeTargetOwner.set('');
      this.scopeReason.set('');
      await this.loadScopes();
      await this.selectScope(scope.id);
      return this.i18n.t('migrationRuns.scope.opened');
    });
  }

  // ---------------------------------------------------------------- one scope

  protected async selectScope(scopeId: string): Promise<void> {
    if (this.scopeId() === scopeId) {
      this.scopeId.set(null);
      return;
    }
    this.scopeId.set(scopeId);
    this.moveTarget.set('');
    this.moveReason.set('');
    this.finishing.set(null);
    this.settling.set(null);
    await this.loadScopeDetail();
  }

  private async loadScopeDetail(): Promise<void> {
    const scope = this.scope();
    if (scope === null) {
      return;
    }
    try {
      const [runs, quarantine] = await Promise.all([this.api.listRuns(scope), this.api.openQuarantine(scope)]);
      this.runs.set(runs.items);
      this.quarantine.set(quarantine.items);
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
    }
  }

  protected placeOf(scope: ScopeView): string {
    if (scope.locationId !== null) {
      return this.i18n.t('migrationRuns.place.location');
    }
    return scope.brandId !== null ? this.i18n.t('migrationRuns.place.brand') : this.i18n.t('migrationRuns.place.tenant');
  }

  protected isHeld(scope: ScopeView): boolean {
    return HOLDING_STATES.includes(scope.state);
  }

  protected moveKind(scope: ScopeView, target: string): MoveKind {
    if (HOLDING_STATES.includes(target)) {
      return 'suspend';
    }
    if (target === 'ROLLING_BACK') {
      return 'rollback';
    }
    return target === 'TARGET_OWNED' && scope.state === 'CUTOVER_READY' ? 'cutover' : 'advance';
  }

  /** The moves this operator can make here; taking ownership is left to the cutover checklist. */
  protected moves(scope: ScopeView): readonly string[] {
    return scope.nextStates.filter((target) => {
      const kind = this.moveKind(scope, target);
      if (kind === 'cutover') {
        return false;
      }
      return kind === 'rollback' ? this.session.has('MIGRATION_CUTOVER_APPROVE') : this.session.has('MIGRATION_SCOPE_MANAGE');
    });
  }

  protected awaitsCutover(scope: ScopeView): boolean {
    return scope.nextStates.includes('TARGET_OWNED') && scope.state === 'CUTOVER_READY';
  }

  protected async move(scope: ScopeView, event: Event): Promise<void> {
    event.preventDefault();
    const target = this.moveTarget();
    const reason = this.moveReason().trim();
    if (target.length === 0 || reason.length === 0 || this.busy()) {
      return;
    }
    await this.run(async () => {
      switch (this.moveKind(scope, target)) {
        case 'suspend':
          await this.api.suspendScope(scope, target, reason);
          break;
        case 'rollback':
          await this.api.rollBackScope(scope, reason);
          break;
        default:
          await this.api.advanceScope(scope, target, reason);
      }
      await this.afterScopeChange();
      return this.i18n.t('migrationRuns.move.done', { state: this.i18n.t(stateKey(target)) });
    });
  }

  protected async resume(scope: ScopeView): Promise<void> {
    const reason = this.moveReason().trim();
    if (reason.length === 0 || this.busy()) {
      return;
    }
    await this.run(async () => {
      await this.api.resumeScope(scope, reason);
      await this.afterScopeChange();
      return this.i18n.t('migrationRuns.move.resumed');
    });
  }

  private async afterScopeChange(): Promise<void> {
    this.moveTarget.set('');
    this.moveReason.set('');
    await this.loadScopes();
    await this.loadScopeDetail();
  }

  // ---------------------------------------------------------------- runs

  protected canStartRun(): boolean {
    return (
      !this.busy() &&
      /^[1-9]\d*$/.test(this.transformationVersion().trim()) &&
      this.startedBy().trim().length > 0 &&
      this.runReason().trim().length > 0
    );
  }

  protected async startRun(scope: ScopeView, event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canStartRun()) {
      return;
    }
    await this.run(async () => {
      await this.api.startRun(scope, {
        runType: this.runType(),
        transformationVersion: Number(this.transformationVersion().trim()),
        startedBy: this.startedBy().trim(),
        reason: this.runReason().trim(),
      });
      this.runReason.set('');
      await this.loadScopeDetail();
      return this.i18n.t('migrationRuns.run.startedDone', { type: this.i18n.t(runTypeKey(this.runType())) });
    });
  }

  protected openFinish(run: RunView): void {
    this.finishing.set(this.finishing() === run.id ? null : run.id);
    this.finishStatus.set('COMPLETED');
    this.checksum.set('');
    this.finishReason.set('');
  }

  protected canFinish(): boolean {
    const checksum = this.checksum().trim();
    return (
      !this.busy() &&
      this.finishReason().trim().length > 0 &&
      (checksum.length === 0 || (this.finishStatus() === 'COMPLETED' && CHECKSUM.test(checksum)))
    );
  }

  protected async finish(scope: ScopeView, run: RunView, event: Event): Promise<void> {
    event.preventDefault();
    if (!this.canFinish()) {
      return;
    }
    const checksum = this.checksum().trim();
    await this.run(async () => {
      await this.api.finishRun(
        scope.tenantId,
        run,
        this.finishStatus(),
        this.finishReason().trim(),
        checksum.length > 0 ? checksum : undefined,
      );
      this.finishing.set(null);
      await this.loadScopeDetail();
      return this.i18n.t('migrationRuns.run.finished', { status: this.i18n.t(runStatusKey(this.finishStatus())) });
    });
  }

  // ---------------------------------------------------------------- quarantine

  protected openSettle(item: QuarantineItemView): void {
    this.settling.set(this.settling() === item.id ? null : item.id);
    this.resolutionCode.set(RESOLUTION_CODES[0]);
    this.settleReason.set('');
  }

  protected async settle(scope: ScopeView, item: QuarantineItemView): Promise<void> {
    const reason = this.settleReason().trim();
    if (reason.length === 0 || this.busy()) {
      return;
    }
    await this.run(async () => {
      await this.api.resolveQuarantine(scope.tenantId, item.id, this.resolutionCode(), reason);
      this.settling.set(null);
      await this.loadScopeDetail();
      return this.i18n.t('migrationRuns.quarantine.settled', { legacyId: item.legacyId });
    });
  }

  /** Runs one write; on a refusal the message is the server's, and a stale version re-reads the scope. */
  private async run(write: () => Promise<string>): Promise<void> {
    this.busy.set(true);
    this.actionError.set(null);
    this.actionMessage.set(null);
    try {
      this.actionMessage.set(await write());
    } catch (error) {
      this.actionError.set(this.i18n.describe(error as ApiError));
      if ((error as ApiError).code === 'STALE_VERSION' || (error as ApiError).code === 'RESOURCE_CONFLICT') {
        await this.loadScopes();
      }
    } finally {
      this.busy.set(false);
    }
  }
}
