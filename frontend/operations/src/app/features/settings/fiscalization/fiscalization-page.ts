import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import {
  FiscalizationApi,
  LegalEntityView,
  LocationFiscalAssignmentView,
  RegisterLegalEntityRequest,
} from './fiscalization-api';

type FiscalizationTab = 'entities' | 'terminals' | 'classification';

/**
 * 10.7 Fiscalization — `docs/operations-spec/settings.md` §10.7.
 *
 * **Tab 1 (Юридические лица) is real** — `LegalEntityController` is
 * field-complete against the spec's table, including the effective-dated
 * assignment the spec calls "the critical interaction". This screen keeps
 * the spec's own simplification for the assign action: a re-registration
 * closes the current assignment at the new one's start automatically
 * (`LegalEntityService.assign`), so there is no separate "close" step to
 * forget.
 *
 * **Tabs 2 and 3 are not built.** `fiscal.fiscal_terminals` does not exist —
 * confirmed against the migrations, not only against the spec's own "Not
 * started" line — and the classification coverage report / bulk-assign tool
 * has no aggregate query or write endpoint yet, even though per-node
 * classification itself (`catalog.fiscal_classifications`) is further along
 * than the spec's citations suggest. Per-node editing belongs to the catalog
 * product editor regardless (10.7's own text: "not an editor"), so this tab
 * would need a new read, not a moved one.
 */
@Component({
  selector: 'q-fiscalization-page',
  imports: [TPipe],
  templateUrl: './fiscalization-page.html',
  styleUrl: './fiscalization-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FiscalizationPage {
  private readonly api = inject(FiscalizationApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly activeTab = signal<FiscalizationTab>('entities');

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly entities = signal<readonly LegalEntityView[]>([]);
  protected readonly assignments = signal<readonly LocationFiscalAssignmentView[]>([]);

  protected readonly showCreateForm = signal(false);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly newCode = signal('');
  protected readonly newLegalName = signal('');
  protected readonly newTin = signal('');
  protected readonly newVatRegistered = signal(false);

  protected readonly showAssignForm = signal(false);
  protected readonly assignSubmitting = signal(false);
  protected readonly assignError = signal<string | null>(null);
  protected readonly assignEntityId = signal('');
  protected readonly assignEffectiveFrom = signal(new Date().toISOString().slice(0, 10));

  constructor() {
    void this.load();
  }

  protected selectTab(tab: FiscalizationTab): void {
    this.activeTab.set(tab);
  }

  protected canCreate(): boolean {
    return (
      !this.createSubmitting() &&
      this.newCode().trim().length > 0 &&
      this.newLegalName().trim().length > 0 &&
      /^[0-9]{9}$/.test(this.newTin().trim())
    );
  }

  protected async submitCreate(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreate()) {
      return;
    }
    this.createSubmitting.set(true);
    this.createError.set(null);
    const request: RegisterLegalEntityRequest = {
      code: this.newCode().trim().toUpperCase(),
      legalName: this.newLegalName().trim(),
      tin: this.newTin().trim(),
      vatRegistered: this.newVatRegistered(),
    };
    try {
      await this.api.registerLegalEntity(scope, request);
      this.showCreateForm.set(false);
      this.newCode.set('');
      this.newLegalName.set('');
      this.newTin.set('');
      this.newVatRegistered.set(false);
      await this.reloadEntities(scope);
    } catch (error) {
      this.createError.set(this.describe(error));
    } finally {
      this.createSubmitting.set(false);
    }
  }

  protected async activate(entity: LegalEntityView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      await this.api.activateLegalEntity(scope, entity.id, entity.version);
      await this.reloadEntities(scope);
    } catch (error) {
      this.loadError.set(this.describe(error));
    }
  }

  protected canAssign(): boolean {
    return (
      !this.assignSubmitting() &&
      this.assignEntityId().trim().length > 0 &&
      this.assignEffectiveFrom().length > 0
    );
  }

  protected async submitAssign(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canAssign()) {
      return;
    }
    this.assignSubmitting.set(true);
    this.assignError.set(null);
    try {
      await this.api.assign(scope, this.assignEntityId(), this.assignEffectiveFrom());
      this.showAssignForm.set(false);
      await this.reloadAssignments(scope);
    } catch (error) {
      this.assignError.set(this.describe(error));
    } finally {
      this.assignSubmitting.set(false);
    }
  }

  protected activeAssignment(): LocationFiscalAssignmentView | null {
    return this.assignments().find((assignment) => assignment.effectiveUntil === null) ?? null;
  }

  protected entityName(entityId: string): string {
    return this.entities().find((entity) => entity.id === entityId)?.legalName ?? entityId;
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      const [entities, assignments] = await Promise.all([
        this.api.listLegalEntities(scope),
        this.api.assignmentHistory(scope),
      ]);
      this.entities.set(entities);
      this.assignments.set(assignments);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async reloadEntities(
    scope: NonNullable<ReturnType<CurrentLocation['scope']>>,
  ): Promise<void> {
    this.entities.set(await this.api.listLegalEntities(scope));
  }

  private async reloadAssignments(
    scope: NonNullable<ReturnType<CurrentLocation['scope']>>,
  ): Promise<void> {
    this.assignments.set(await this.api.assignmentHistory(scope));
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
