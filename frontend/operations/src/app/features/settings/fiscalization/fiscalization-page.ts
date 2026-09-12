import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import {
  ClassifyDeliveryFeeRequest,
  FiscalCoverageSummary,
  FiscalizationApi,
  FiscalTerminalHealth,
  FiscalTerminalKind,
  FiscalTerminalView,
  ISSUE_FISCAL_RECEIPT,
  LegalEntityView,
  LocationFiscalAssignmentView,
  RegisterFiscalTerminalRequest,
  RegisterLegalEntityRequest,
  UpdateLegalEntityRequest,
} from './fiscalization-api';

type FiscalizationTab = 'entities' | 'terminals' | 'classification';

const TERMINAL_KINDS: readonly FiscalTerminalKind[] = [
  'POS',
  'COURIER_TERMINAL',
  'KIOSK',
  'VIRTUAL',
];

/**
 * 10.7 Fiscalization — `docs/operations-spec/settings.md` §10.7.
 *
 * **All three tabs are real as of wave P34.** Tab 1 (`OperationsLegalEntityController`)
 * is field-complete against the spec's table, correction included — a
 * registered entity could never be corrected, suspended or archived from
 * either surface before this wave. Tab 2 registers and drives
 * `fiscal.fiscal_terminals` (`OperationsFiscalTerminalController`), the table
 * ADR 0038 sketched and nobody built, without which
 * `CheckoutSettlementPlanner.responsibilityOf` could never declare cash's
 * fiscal responsibility as `TERMINAL`. Tab 3 is the minimum coverage read
 * this wave builds in place of P21's not-yet-merged fiscal workbench: a
 * per-brand unclassified count and node list, plus the one write this screen
 * keeps for itself — the delivery fee's own ИКПУ and marking control, "the
 * one people forget" — everything else stays the product editor's (10.7's
 * own text: "not an editor").
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
  protected readonly terminalKinds = TERMINAL_KINDS;

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

  // ------------------------------------------------------------ Tab 1: edit

  protected readonly editingEntityId = signal<string | null>(null);
  protected readonly editSubmitting = signal(false);
  protected readonly editError = signal<string | null>(null);
  protected readonly editLegalName = signal('');
  protected readonly editShortName = signal('');
  protected readonly editVatRegistered = signal(false);
  protected readonly editVatCertificateReference = signal('');
  protected readonly editTaxProfileId = signal('');
  protected readonly editRegisteredAddress = signal('');
  protected readonly editContactPhone = signal('');

  protected readonly showAssignForm = signal(false);
  protected readonly assignSubmitting = signal(false);
  protected readonly assignError = signal<string | null>(null);
  protected readonly assignEntityId = signal('');
  protected readonly assignEffectiveFrom = signal(new Date().toISOString().slice(0, 10));

  // -------------------------------------------------------- Tab 2: terminals

  protected readonly terminalsLoaded = signal(false);
  protected readonly terminalsLoading = signal(false);
  protected readonly terminals = signal<readonly FiscalTerminalView[]>([]);
  protected readonly terminalsError = signal<string | null>(null);

  protected readonly showTerminalForm = signal(false);
  protected readonly terminalSubmitting = signal(false);
  protected readonly terminalError = signal<string | null>(null);
  protected readonly newTerminalKind = signal<FiscalTerminalKind>('POS');
  protected readonly newTerminalLegalEntityId = signal('');
  protected readonly newTerminalReference = signal('');
  protected readonly newTerminalProviderBindingId = signal('');
  protected readonly newTerminalIssuesReceipts = signal(false);

  // ---------------------------------------------------------- Tab 3: coverage

  protected readonly coverageLoaded = signal(false);
  protected readonly coverageLoading = signal(false);
  protected readonly coverage = signal<FiscalCoverageSummary | null>(null);
  protected readonly coverageError = signal<string | null>(null);

  protected readonly showDeliveryFeeForm = signal(false);
  protected readonly deliveryFeeSubmitting = signal(false);
  protected readonly deliveryFeeError = signal<string | null>(null);
  protected readonly deliveryFeeMxik = signal('');
  protected readonly deliveryFeeMarkingRequired = signal(false);

  constructor() {
    void this.load();
  }

  protected selectTab(tab: FiscalizationTab): void {
    this.activeTab.set(tab);
    if (tab === 'terminals' && !this.terminalsLoaded()) {
      void this.loadTerminals();
    }
    if (tab === 'classification' && !this.coverageLoaded()) {
      void this.loadCoverage();
    }
  }

  // -------------------------------------------------------------- Tab 1: CRUD

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

  protected startEdit(entity: LegalEntityView): void {
    this.editingEntityId.set(entity.id);
    this.editError.set(null);
    this.editLegalName.set(entity.legalName);
    this.editShortName.set(entity.shortName ?? '');
    this.editVatRegistered.set(entity.vatRegistered);
    this.editVatCertificateReference.set(entity.vatCertificateReference ?? '');
    this.editTaxProfileId.set(entity.taxProfileId ?? '');
    this.editRegisteredAddress.set(entity.registeredAddress ?? '');
    this.editContactPhone.set(entity.contactPhone ?? '');
  }

  protected cancelEdit(): void {
    this.editingEntityId.set(null);
    this.editError.set(null);
  }

  protected canSubmitEdit(): boolean {
    return !this.editSubmitting() && this.editLegalName().trim().length > 0;
  }

  protected async submitEdit(): Promise<void> {
    const scope = this.location.scope();
    const entityId = this.editingEntityId();
    if (!scope || !entityId || !this.canSubmitEdit()) {
      return;
    }
    const entity = this.entities().find((candidate) => candidate.id === entityId);
    if (!entity) {
      return;
    }
    this.editSubmitting.set(true);
    this.editError.set(null);
    const request: UpdateLegalEntityRequest = {
      legalName: this.editLegalName().trim(),
      shortName: this.editShortName().trim() || undefined,
      vatRegistered: this.editVatRegistered(),
      vatCertificateReference: this.editVatCertificateReference().trim() || undefined,
      taxProfileId: this.editTaxProfileId().trim() || undefined,
      registeredAddress: this.editRegisteredAddress().trim() || undefined,
      contactPhone: this.editContactPhone().trim() || undefined,
    };
    try {
      await this.api.updateLegalEntity(scope, entityId, request, entity.version);
      this.editingEntityId.set(null);
      await this.reloadEntities(scope);
    } catch (error) {
      this.editError.set(this.describe(error));
    } finally {
      this.editSubmitting.set(false);
    }
  }

  protected async suspendEntity(entity: LegalEntityView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      await this.api.suspendLegalEntity(scope, entity.id, entity.version);
      await this.reloadEntities(scope);
    } catch (error) {
      this.loadError.set(this.describe(error));
    }
  }

  protected async archiveEntity(entity: LegalEntityView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      await this.api.archiveLegalEntity(scope, entity.id, entity.version);
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

  /** The assignment table's own most-recent-first order, with no closing date on top. */
  protected orderedAssignments(): readonly LocationFiscalAssignmentView[] {
    return [...this.assignments()].sort((a, b) => {
      if (a.effectiveUntil === null && b.effectiveUntil !== null) {
        return -1;
      }
      if (a.effectiveUntil !== null && b.effectiveUntil === null) {
        return 1;
      }
      return b.effectiveFrom.localeCompare(a.effectiveFrom);
    });
  }

  protected activeAssignment(): LocationFiscalAssignmentView | null {
    return this.assignments().find((assignment) => assignment.effectiveUntil === null) ?? null;
  }

  protected entityName(entityId: string): string {
    return this.entities().find((entity) => entity.id === entityId)?.legalName ?? entityId;
  }

  // ------------------------------------------------------- Tab 2: terminals

  protected activeLegalEntities(): readonly LegalEntityView[] {
    return this.entities().filter((entity) => entity.status === 'ACTIVE');
  }

  /**
   * `MessageKey` is a closed union (`t.pipe.ts`'s own reason for existing), so
   * a template cannot build a key by concatenating a runtime enum value onto a
   * string literal. This is the switch that stands in for that concatenation.
   */
  protected terminalKindLabel(kind: FiscalTerminalKind): string {
    switch (kind) {
      case 'POS':
        return this.i18n.t('settings.fiscalization.terminals.kind.POS');
      case 'COURIER_TERMINAL':
        return this.i18n.t('settings.fiscalization.terminals.kind.COURIER_TERMINAL');
      case 'KIOSK':
        return this.i18n.t('settings.fiscalization.terminals.kind.KIOSK');
      case 'VIRTUAL':
        return this.i18n.t('settings.fiscalization.terminals.kind.VIRTUAL');
    }
  }

  protected canRegisterTerminal(): boolean {
    return (
      !this.terminalSubmitting() &&
      this.newTerminalLegalEntityId().trim().length > 0 &&
      this.newTerminalReference().trim().length > 0 &&
      (!this.newTerminalIssuesReceipts() || this.newTerminalProviderBindingId().trim().length > 0)
    );
  }

  protected async submitRegisterTerminal(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canRegisterTerminal()) {
      return;
    }
    this.terminalSubmitting.set(true);
    this.terminalError.set(null);
    const request: RegisterFiscalTerminalRequest = {
      locationId: scope.locationId,
      legalEntityId: this.newTerminalLegalEntityId(),
      kind: this.newTerminalKind(),
      providerBindingId: this.newTerminalProviderBindingId().trim() || undefined,
      terminalReference: this.newTerminalReference().trim(),
      capabilitySnapshot: this.newTerminalIssuesReceipts() ? { [ISSUE_FISCAL_RECEIPT]: true } : {},
    };
    try {
      await this.api.registerFiscalTerminal(scope, request);
      this.showTerminalForm.set(false);
      this.newTerminalReference.set('');
      this.newTerminalLegalEntityId.set('');
      this.newTerminalProviderBindingId.set('');
      this.newTerminalIssuesReceipts.set(false);
      this.newTerminalKind.set('POS');
      await this.reloadTerminals(scope);
    } catch (error) {
      this.terminalError.set(this.describe(error));
    } finally {
      this.terminalSubmitting.set(false);
    }
  }

  protected async checkTerminalHealth(
    terminal: FiscalTerminalView,
    outcome: FiscalTerminalHealth,
  ): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      await this.api.checkFiscalTerminalHealth(scope, terminal.id, outcome, terminal.version);
      await this.reloadTerminals(scope);
    } catch (error) {
      this.terminalsError.set(this.describe(error));
    }
  }

  protected async suspendTerminal(terminal: FiscalTerminalView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      await this.api.suspendFiscalTerminal(scope, terminal.id, terminal.version);
      await this.reloadTerminals(scope);
    } catch (error) {
      this.terminalsError.set(this.describe(error));
    }
  }

  protected async reactivateTerminal(terminal: FiscalTerminalView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      await this.api.reactivateFiscalTerminal(scope, terminal.id, terminal.version);
      await this.reloadTerminals(scope);
    } catch (error) {
      this.terminalsError.set(this.describe(error));
    }
  }

  protected async retireTerminal(terminal: FiscalTerminalView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      await this.api.retireFiscalTerminal(scope, terminal.id, terminal.version);
      await this.reloadTerminals(scope);
    } catch (error) {
      this.terminalsError.set(this.describe(error));
    }
  }

  private async loadTerminals(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.terminalsLoading.set(true);
    this.terminalsError.set(null);
    try {
      this.terminals.set(await this.api.listFiscalTerminals(scope));
      this.terminalsLoaded.set(true);
    } catch (error) {
      this.terminalsError.set(this.describe(error));
    } finally {
      this.terminalsLoading.set(false);
    }
  }

  private async reloadTerminals(
    scope: NonNullable<ReturnType<CurrentLocation['scope']>>,
  ): Promise<void> {
    this.terminals.set(await this.api.listFiscalTerminals(scope));
  }

  // ------------------------------------------------------- Tab 3: coverage

  protected deliveryFeeUnclassified(): boolean {
    return (this.coverage()?.nodes ?? []).some((node) => node.nodeType === 'FEE');
  }

  /** See {@link terminalKindLabel}'s own doc — same reason, a different enum. */
  protected nodeTypeLabel(nodeType: 'VARIANT' | 'MODIFIER_OPTION' | 'FEE'): string {
    switch (nodeType) {
      case 'VARIANT':
        return this.i18n.t('settings.fiscalization.classification.nodeType.VARIANT');
      case 'MODIFIER_OPTION':
        return this.i18n.t('settings.fiscalization.classification.nodeType.MODIFIER_OPTION');
      case 'FEE':
        return this.i18n.t('settings.fiscalization.classification.nodeType.FEE');
    }
  }

  protected canSubmitDeliveryFee(): boolean {
    return !this.deliveryFeeSubmitting();
  }

  protected async submitDeliveryFee(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canSubmitDeliveryFee()) {
      return;
    }
    this.deliveryFeeSubmitting.set(true);
    this.deliveryFeeError.set(null);
    const request: ClassifyDeliveryFeeRequest = {
      mxikCode: this.deliveryFeeMxik().trim() || undefined,
      markingRequired: this.deliveryFeeMarkingRequired(),
    };
    try {
      await this.api.classifyDeliveryFee(scope, request);
      this.showDeliveryFeeForm.set(false);
      this.deliveryFeeMxik.set('');
      this.deliveryFeeMarkingRequired.set(false);
      await this.reloadCoverage(scope);
    } catch (error) {
      this.deliveryFeeError.set(this.describe(error));
    } finally {
      this.deliveryFeeSubmitting.set(false);
    }
  }

  private async loadCoverage(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.coverageLoading.set(true);
    this.coverageError.set(null);
    try {
      this.coverage.set(await this.api.fiscalCoverage(scope));
      this.coverageLoaded.set(true);
    } catch (error) {
      this.coverageError.set(this.describe(error));
    } finally {
      this.coverageLoading.set(false);
    }
  }

  private async reloadCoverage(
    scope: NonNullable<ReturnType<CurrentLocation['scope']>>,
  ): Promise<void> {
    this.coverage.set(await this.api.fiscalCoverage(scope));
  }

  // -------------------------------------------------------------- shared

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
