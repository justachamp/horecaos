import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation, LocationOption } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import {
  AdjustmentReasonResponse,
  ComplianceFieldName,
  CourierComplianceFileRequest,
  CourierDetailResponse,
  CourierGroupResponse,
  CouriersApi,
  CourierTypeResponse,
  RosterEntryResponse,
  VehicleFuelType,
} from './couriers-api';
import { newIdempotencyKey } from '../../core/api/idempotency';

type DialogKind = {
  readonly kind: 'verify' | 'suspend' | 'adjustment';
  readonly courier: RosterEntryResponse;
} | null;

/**
 * The nine fields in the order the form asks for them: identity, then the
 * vehicle, then how to reach somebody when the vehicle does not come back.
 */
const COMPLIANCE_FIELDS: readonly ComplianceFieldName[] = [
  'PASSPORT',
  'PINFL',
  'DRIVING_LICENCE',
  'VEHICLE_REGISTRATION',
  'VEHICLE_PLATE',
  'ADDRESS',
  'EMERGENCY_CONTACT',
  'REFERRAL',
  'NOTES',
];

const FUEL_TYPES: readonly VehicleFuelType[] = [
  'PETROL',
  'DIESEL',
  'GAS',
  'ELECTRIC',
  'HYBRID',
  'NONE',
];

/**
 * Couriers — operations §3.3, the in-house roster.
 *
 * **Built.** List with type, vehicle class, engagement status, warning state
 * and current load (`CourierRosterQueryService`, wave 30); register a
 * courier and open their engagement (`PENDING_VERIFICATION`); attest a
 * registration was sighted (`verify`, → `ACTIVE`); suspend an engagement with
 * a reason. Registration takes one `fullName` field, not separate first/last
 * — `fulfillment.couriers.protected_full_name` is one envelope-encrypted
 * string, matching the backend as it exists rather than the spec's own
 * first/last split.
 *
 * **The compliance file** (wave P19, V0220/V0221). The register form files
 * whatever documents the operator already has, and the detail pane behind the
 * roster shows *which* of the nine are on file — never their contents. Reading
 * them is a separate act: «Показать документы» asks for a purpose, calls the
 * ADR 0029 reveal, and what comes back is held in a signal for as long as the
 * pane is open and is never written anywhere. The ПИНФЛ in particular is on no
 * list read; the backend does not put it there and this component never asks
 * for it except through that reveal.
 *
 * **Courier groups and branch bindings** (same wave). Author a group, put a
 * courier in one or take them out, bind a courier to a branch or release one —
 * each a `CouriersApi` call this component now makes, not only reads. A branch
 * to bind against comes from {@link CurrentLocation}'s own brand-scoped
 * options, the same list every other branch picker in this console already
 * uses; a group to join comes from the tenant's own list, fetched alongside
 * the roster.
 *
 * Registration still takes one `fullName` field rather than a first/last split,
 * because `fulfillment.couriers.protected_full_name` is one envelope-encrypted
 * string.
 *
 * **Still not built, honestly**: courier-app account provisioning beyond the
 * Keycloak subject typed at registration (ADR 0042 forbids deriving a password
 * from a passport number), and online status and rating, which are read-only
 * per the spec and for which no source exists on either side. Each is omitted
 * rather than rendered disabled, per the rule `not-built-page.ts` follows for a
 * whole screen, applied here to a field on a real one.
 */
@Component({
  selector: 'q-couriers-page',
  imports: [TPipe],
  templateUrl: './couriers-page.html',
  styleUrl: './couriers-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CouriersPage implements OnInit {
  private readonly api = inject(CouriersApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly roster = signal<readonly RosterEntryResponse[]>([]);
  protected readonly types = signal<readonly CourierTypeResponse[]>([]);
  protected readonly groups = signal<readonly CourierGroupResponse[]>([]);
  protected readonly adjustmentReasons = signal<readonly AdjustmentReasonResponse[]>([]);

  protected readonly showRegisterForm = signal(false);
  protected readonly registerSubmitting = signal(false);
  protected readonly registerError = signal<string | null>(null);
  protected readonly newCourierTypeId = signal('');
  protected readonly newPrincipalSubject = signal('');
  protected readonly newDisplayReference = signal('');
  protected readonly newFullName = signal('');
  protected readonly newEngagedFrom = signal(new Date().toISOString().slice(0, 10));
  protected readonly newReason = signal('');

  /**
   * The compliance half of the register form, one signal per field.
   *
   * A record keyed by the backend's own field name rather than nine named
   * signals: the form, the detail pane's editor and the reveal all walk the
   * same list, so a tenth document is one entry here and three labels in the
   * catalogues rather than an edit in four templates.
   */
  protected readonly complianceFields = COMPLIANCE_FIELDS;
  protected readonly fuelTypes = FUEL_TYPES;
  protected readonly newCompliance = signal<Record<ComplianceFieldName, string>>(emptyCompliance());
  protected readonly newFuelType = signal('');

  // ------------------------------------------------------------ detail pane

  protected readonly detail = signal<CourierDetailResponse | null>(null);
  protected readonly detailLoading = signal(false);
  protected readonly detailError = signal<string | null>(null);

  /** Editing the file of the courier the pane is open on. */
  protected readonly editing = signal(false);
  protected readonly editCompliance =
    signal<Record<ComplianceFieldName, string>>(emptyCompliance());
  protected readonly editFuelType = signal('');
  protected readonly editReason = signal('');
  protected readonly editSubmitting = signal(false);

  /**
   * What a reveal returned, and the purpose it was made for.
   *
   * Cleared whenever the pane closes or moves to another courier: these are
   * decrypted documents, and keeping them in a signal past the moment the
   * operator asked for them would quietly turn one audited reveal into an
   * open window.
   */
  protected readonly revealPurpose = signal('');
  protected readonly revealing = signal(false);
  protected readonly revealed = signal<Record<string, string> | null>(null);

  protected readonly dialog = signal<DialogKind>(null);
  protected readonly dialogSubmitting = signal(false);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly verifyRegistrationId = signal('');
  protected readonly verifyValidUntil = signal('');
  protected readonly verifyReason = signal('');
  protected readonly suspendReasonCode = signal('');
  protected readonly suspendReason = signal('');

  // -------------------------------------------------------------- adjustments
  protected readonly adjustmentReasonCode = signal('');
  protected readonly adjustmentAmount = signal(0);
  protected readonly adjustmentCurrency = signal('UZS');
  protected readonly adjustmentReasonText = signal('');

  // ------------------------------------------------------------------ groups

  protected readonly showGroupForm = signal(false);
  protected readonly groupFormSubmitting = signal(false);
  protected readonly groupFormError = signal<string | null>(null);
  protected readonly newGroupCode = signal('');
  protected readonly newGroupDisplayName = signal('');
  protected readonly newGroupReason = signal('');

  protected readonly showJoinGroupForm = signal(false);
  protected readonly joinGroupId = signal('');
  protected readonly joinGroupReason = signal('');
  protected readonly joinSubmitting = signal(false);
  protected readonly joinError = signal<string | null>(null);

  /** The group chip whose «leave» a reason is currently being typed for. */
  protected readonly leavingGroupId = signal<string | null>(null);
  protected readonly leaveGroupReason = signal('');
  protected readonly leaveSubmitting = signal(false);
  protected readonly leaveError = signal<string | null>(null);

  // -------------------------------------------------------- branch bindings

  protected readonly showBindBranchForm = signal(false);
  protected readonly bindLocationId = signal('');
  protected readonly bindPrimary = signal(false);
  protected readonly bindReason = signal('');
  protected readonly bindSubmitting = signal(false);
  protected readonly bindError = signal<string | null>(null);

  /** The branch chip whose «unbind» a reason is currently being typed for. */
  protected readonly unbindingLocationId = signal<string | null>(null);
  protected readonly unbindReason = signal('');
  protected readonly unbindSubmitting = signal(false);
  protected readonly unbindError = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.load();
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
      const [roster, types, groups, adjustmentReasons] = await Promise.all([
        this.api.roster(scope.tenantId),
        this.api.types(scope.tenantId),
        this.api.groups(scope.tenantId),
        this.api.adjustmentReasons(scope.tenantId),
      ]);
      this.roster.set(roster);
      this.types.set(types);
      this.groups.set(groups);
      this.adjustmentReasons.set(adjustmentReasons.filter((reason) => reason.status === 'ACTIVE'));
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

  // -------------------------------------------------------------- register

  protected openRegisterForm(): void {
    this.newCourierTypeId.set(this.types()[0]?.courierTypeId ?? '');
    this.newPrincipalSubject.set('');
    this.newDisplayReference.set('');
    this.newFullName.set('');
    this.newEngagedFrom.set(new Date().toISOString().slice(0, 10));
    this.newReason.set('');
    this.newCompliance.set(emptyCompliance());
    this.newFuelType.set('');
    this.registerError.set(null);
    this.showRegisterForm.set(true);
  }

  protected closeRegisterForm(): void {
    this.showRegisterForm.set(false);
  }

  protected canRegister(): boolean {
    return (
      !this.registerSubmitting() &&
      this.newCourierTypeId() !== '' &&
      this.newPrincipalSubject().trim().length > 0 &&
      this.newDisplayReference().trim().length > 0 &&
      this.newFullName().trim().length > 0 &&
      this.newEngagedFrom() !== '' &&
      this.newReason().trim().length > 0
    );
  }

  protected async submitRegister(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canRegister()) {
      return;
    }
    this.registerSubmitting.set(true);
    this.registerError.set(null);
    try {
      await this.api.register(scope.tenantId, {
        courierTypeId: this.newCourierTypeId(),
        principalSubject: this.newPrincipalSubject().trim(),
        displayReference: this.newDisplayReference().trim(),
        fullName: this.newFullName().trim(),
        engagedFrom: this.newEngagedFrom(),
        reason: this.newReason().trim(),
        ...compliancePayload(this.newCompliance(), this.newFuelType()),
      });
      this.showRegisterForm.set(false);
      await this.load();
    } catch (error) {
      this.registerError.set(this.describe(error));
    } finally {
      this.registerSubmitting.set(false);
    }
  }

  // --------------------------------------------------------- verify/suspend

  protected openVerify(courier: RosterEntryResponse): void {
    this.verifyRegistrationId.set('');
    this.verifyValidUntil.set('');
    this.verifyReason.set('');
    this.dialogError.set(null);
    this.dialog.set({ kind: 'verify', courier });
  }

  protected openSuspend(courier: RosterEntryResponse): void {
    this.suspendReasonCode.set('');
    this.suspendReason.set('');
    this.dialogError.set(null);
    this.dialog.set({ kind: 'suspend', courier });
  }

  protected closeDialog(): void {
    this.dialog.set(null);
  }

  protected canVerify(): boolean {
    return (
      !this.dialogSubmitting() &&
      this.verifyRegistrationId().trim().length > 0 &&
      this.verifyValidUntil() !== '' &&
      this.verifyReason().trim().length > 0
    );
  }

  protected canSuspend(): boolean {
    return (
      !this.dialogSubmitting() &&
      this.suspendReasonCode().trim().length > 0 &&
      this.suspendReason().trim().length > 0
    );
  }

  protected async submitVerify(): Promise<void> {
    const state = this.dialog();
    const scope = this.location.scope();
    if (
      !scope ||
      !state ||
      state.kind !== 'verify' ||
      !state.courier.engagementId ||
      !this.canVerify()
    ) {
      return;
    }
    this.dialogSubmitting.set(true);
    this.dialogError.set(null);
    try {
      await this.api.verify(scope.tenantId, state.courier.engagementId, {
        registrationIdentifier: this.verifyRegistrationId().trim(),
        validUntil: this.verifyValidUntil(),
        method: 'MANUAL_ATTESTATION',
        reason: this.verifyReason().trim(),
      });
      this.dialog.set(null);
      await this.load();
    } catch (error) {
      this.dialogError.set(this.describe(error));
    } finally {
      this.dialogSubmitting.set(false);
    }
  }

  protected async submitSuspend(): Promise<void> {
    const state = this.dialog();
    const scope = this.location.scope();
    if (
      !scope ||
      !state ||
      state.kind !== 'suspend' ||
      !state.courier.engagementId ||
      !this.canSuspend()
    ) {
      return;
    }
    this.dialogSubmitting.set(true);
    this.dialogError.set(null);
    try {
      await this.api.suspend(scope.tenantId, state.courier.engagementId, {
        reasonCode: this.suspendReasonCode().trim(),
        reason: this.suspendReason().trim(),
      });
      this.dialog.set(null);
      await this.load();
    } catch (error) {
      this.dialogError.set(this.describe(error));
    } finally {
      this.dialogSubmitting.set(false);
    }
  }

  // -------------------------------------------------------------- adjustment

  /**
   * A manual bonus or penalty (ADR 0108). There is no origin field to set —
   * every adjustment recorded here is `MANUAL`, and the server stamps that
   * unconditionally; see `CouriersApi.recordAdjustment`'s own doc.
   */
  protected openAdjustment(courier: RosterEntryResponse): void {
    this.adjustmentReasonCode.set(this.adjustmentReasons()[0]?.code ?? '');
    this.adjustmentAmount.set(0);
    this.adjustmentCurrency.set('UZS');
    this.adjustmentReasonText.set('');
    this.dialogError.set(null);
    this.dialog.set({ kind: 'adjustment', courier });
  }

  protected selectedAdjustmentReason(): AdjustmentReasonResponse | null {
    return (
      this.adjustmentReasons().find((reason) => reason.code === this.adjustmentReasonCode()) ?? null
    );
  }

  protected reasonKindLabel(kind: string): string {
    return kind === 'PENALTY'
      ? this.i18n.t('delivery.rates.reasons.kind.PENALTY')
      : this.i18n.t('delivery.rates.reasons.kind.BONUS');
  }

  protected canRecordAdjustment(): boolean {
    return (
      !this.dialogSubmitting() &&
      this.adjustmentReasonCode() !== '' &&
      this.adjustmentAmount() > 0 &&
      this.adjustmentCurrency().trim().length === 3 &&
      this.adjustmentReasonText().trim().length > 0
    );
  }

  protected async submitAdjustment(): Promise<void> {
    const state = this.dialog();
    const scope = this.location.scope();
    const reason = this.selectedAdjustmentReason();
    if (!scope || !state || state.kind !== 'adjustment' || !reason || !this.canRecordAdjustment()) {
      return;
    }
    this.dialogSubmitting.set(true);
    this.dialogError.set(null);
    try {
      const signedAmount =
        reason.kind === 'PENALTY'
          ? -Math.abs(this.adjustmentAmount())
          : Math.abs(this.adjustmentAmount());
      await this.api.recordAdjustment(scope.tenantId, state.courier.courierId, {
        locationId: scope.locationId,
        amountMinor: signedAmount,
        currency: this.adjustmentCurrency().trim().toUpperCase(),
        reasonCode: reason.code,
        origin: 'MANUAL',
        idempotencyKey: newIdempotencyKey(),
        reason: this.adjustmentReasonText().trim(),
      });
      this.dialog.set(null);
    } catch (error) {
      this.dialogError.set(this.describe(error));
    } finally {
      this.dialogSubmitting.set(false);
    }
  }

  // ----------------------------------------------------------- detail pane

  protected async openDetail(courier: RosterEntryResponse): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    // Every reveal belongs to the courier it was made for. Clearing first means
    // opening a second courier can never show the first one's documents,
    // however slowly the request answers.
    this.closeRevealState();
    this.editing.set(false);
    this.closeGroupAndBranchForms();
    this.detail.set(null);
    this.detailError.set(null);
    this.detailLoading.set(true);
    try {
      this.detail.set(await this.api.courier(scope.tenantId, courier.courierId));
    } catch (error) {
      this.detailError.set(this.describe(error));
    } finally {
      this.detailLoading.set(false);
    }
  }

  protected closeDetail(): void {
    this.detail.set(null);
    this.editing.set(false);
    this.closeRevealState();
    this.closeGroupAndBranchForms();
  }

  private closeGroupAndBranchForms(): void {
    this.showJoinGroupForm.set(false);
    this.leavingGroupId.set(null);
    this.showBindBranchForm.set(false);
    this.unbindingLocationId.set(null);
  }

  /** Re-reads the open courier so a group or branch write shows up immediately. */
  private async refreshDetail(): Promise<void> {
    const scope = this.location.scope();
    const courier = this.detail();
    if (!scope || !courier) {
      return;
    }
    this.detail.set(await this.api.courier(scope.tenantId, courier.courierId));
  }

  protected onFile(field: ComplianceFieldName): boolean {
    return this.detail()?.complianceFieldsOnFile.includes(field) ?? false;
  }

  protected missingFieldCount(): number {
    const onFile = this.detail()?.complianceFieldsOnFile ?? [];
    return COMPLIANCE_FIELDS.length - onFile.length;
  }

  // --------------------------------------------------------------- the reveal

  protected canReveal(): boolean {
    return !this.revealing() && this.revealPurpose().trim().length > 0;
  }

  protected async reveal(): Promise<void> {
    const scope = this.location.scope();
    const courier = this.detail();
    if (!scope || !courier || !this.canReveal()) {
      return;
    }
    this.revealing.set(true);
    this.detailError.set(null);
    try {
      const answer = await this.api.revealComplianceFile(
        scope.tenantId,
        courier.courierId,
        this.revealPurpose().trim(),
      );
      const values: Record<string, string> = {};
      for (const entry of answer.fields) {
        values[entry.field] = entry.value;
      }
      this.revealed.set(values);
    } catch (error) {
      this.detailError.set(this.describe(error));
    } finally {
      this.revealing.set(false);
    }
  }

  protected hideRevealed(): void {
    this.closeRevealState();
  }

  protected revealedValue(field: ComplianceFieldName): string | null {
    return this.revealed()?.[field] ?? null;
  }

  private closeRevealState(): void {
    this.revealed.set(null);
    this.revealPurpose.set('');
  }

  // ------------------------------------------------------ editing the file

  protected startEditing(): void {
    this.editCompliance.set(emptyCompliance());
    this.editFuelType.set(this.detail()?.vehicleFuelType ?? '');
    this.editReason.set('');
    this.editing.set(true);
  }

  protected cancelEditing(): void {
    this.editing.set(false);
  }

  /**
   * What this edit would actually change.
   *
   * The fuel picker opens on the value already on file, so it is dropped unless
   * the operator moved it — otherwise "I typed a reason and nothing else" would
   * look like a change, and saving it would stamp a provenance that reads
   * afterwards as though somebody had reviewed the file.
   */
  private pendingFileChanges(): Partial<CourierComplianceFileRequest> {
    const payload = compliancePayload(this.editCompliance(), this.editFuelType());
    if (this.editFuelType() === (this.detail()?.vehicleFuelType ?? '')) {
      delete (payload as Record<string, unknown>)['vehicleFuelType'];
    }
    return payload;
  }

  protected canSaveFile(): boolean {
    if (this.editSubmitting() || this.editReason().trim().length === 0) {
      return false;
    }
    return Object.keys(this.pendingFileChanges()).length > 0;
  }

  protected async saveFile(): Promise<void> {
    const scope = this.location.scope();
    const courier = this.detail();
    if (!scope || !courier || !this.canSaveFile()) {
      return;
    }
    this.editSubmitting.set(true);
    this.detailError.set(null);
    try {
      await this.api.recordComplianceFile(scope.tenantId, courier.courierId, {
        ...this.pendingFileChanges(),
        reason: this.editReason().trim(),
      });
      this.editing.set(false);
      // A saved field changes what is on file, and a stale reveal beside a
      // fresh summary would show the old passport under the new heading.
      this.closeRevealState();
      this.detail.set(await this.api.courier(scope.tenantId, courier.courierId));
      await this.load();
    } catch (error) {
      this.detailError.set(this.describe(error));
    } finally {
      this.editSubmitting.set(false);
    }
  }

  // ------------------------------------------------------------------ groups

  /** Author a group. Tenant-wide, so it lives beside the roster rather than a courier. */
  protected openGroupForm(): void {
    this.newGroupCode.set('');
    this.newGroupDisplayName.set('');
    this.newGroupReason.set('');
    this.groupFormError.set(null);
    this.showGroupForm.set(true);
  }

  protected closeGroupForm(): void {
    this.showGroupForm.set(false);
  }

  protected canCreateGroup(): boolean {
    return (
      !this.groupFormSubmitting() &&
      this.newGroupCode().trim().length > 0 &&
      this.newGroupDisplayName().trim().length > 0 &&
      this.newGroupReason().trim().length > 0
    );
  }

  protected async submitGroupForm(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreateGroup()) {
      return;
    }
    this.groupFormSubmitting.set(true);
    this.groupFormError.set(null);
    try {
      await this.api.createGroup(
        scope.tenantId,
        this.newGroupCode().trim(),
        this.newGroupDisplayName().trim(),
        this.newGroupReason().trim(),
      );
      this.showGroupForm.set(false);
      this.groups.set(await this.api.groups(scope.tenantId));
    } catch (error) {
      this.groupFormError.set(this.describe(error));
    } finally {
      this.groupFormSubmitting.set(false);
    }
  }

  /** Every active group the open courier is not already in. */
  protected availableGroupsToJoin(): readonly CourierGroupResponse[] {
    const courier = this.detail();
    const joined = new Set((courier?.groups ?? []).map((group) => group.groupId));
    return this.groups().filter((group) => group.status === 'ACTIVE' && !joined.has(group.groupId));
  }

  protected openJoinGroupForm(): void {
    this.joinGroupId.set(this.availableGroupsToJoin()[0]?.groupId ?? '');
    this.joinGroupReason.set('');
    this.joinError.set(null);
    this.showJoinGroupForm.set(true);
  }

  protected closeJoinGroupForm(): void {
    this.showJoinGroupForm.set(false);
  }

  protected canJoinGroup(): boolean {
    return (
      !this.joinSubmitting() &&
      this.joinGroupId() !== '' &&
      this.joinGroupReason().trim().length > 0
    );
  }

  protected async submitJoinGroup(): Promise<void> {
    const scope = this.location.scope();
    const courier = this.detail();
    if (!scope || !courier || !this.canJoinGroup()) {
      return;
    }
    this.joinSubmitting.set(true);
    this.joinError.set(null);
    try {
      await this.api.joinGroup(
        scope.tenantId,
        courier.courierId,
        this.joinGroupId(),
        this.joinGroupReason().trim(),
      );
      this.showJoinGroupForm.set(false);
      await this.refreshDetail();
    } catch (error) {
      this.joinError.set(this.describe(error));
    } finally {
      this.joinSubmitting.set(false);
    }
  }

  protected startLeaveGroup(groupId: string): void {
    this.leavingGroupId.set(groupId);
    this.leaveGroupReason.set('');
    this.leaveError.set(null);
  }

  protected cancelLeaveGroup(): void {
    this.leavingGroupId.set(null);
  }

  protected canConfirmLeaveGroup(): boolean {
    return !this.leaveSubmitting() && this.leaveGroupReason().trim().length > 0;
  }

  protected async confirmLeaveGroup(): Promise<void> {
    const scope = this.location.scope();
    const courier = this.detail();
    const groupId = this.leavingGroupId();
    if (!scope || !courier || !groupId || !this.canConfirmLeaveGroup()) {
      return;
    }
    this.leaveSubmitting.set(true);
    this.leaveError.set(null);
    try {
      await this.api.leaveGroup(
        scope.tenantId,
        courier.courierId,
        groupId,
        this.leaveGroupReason().trim(),
      );
      this.leavingGroupId.set(null);
      await this.refreshDetail();
    } catch (error) {
      this.leaveError.set(this.describe(error));
    } finally {
      this.leaveSubmitting.set(false);
    }
  }

  // -------------------------------------------------------- branch bindings

  /**
   * Which branches the open courier could still be bound to — the operator's
   * own current-brand options, the same list every other branch picker in
   * this console already reads off {@link CurrentLocation}, minus the ones
   * already on file.
   */
  protected availableLocationsToBind(): readonly LocationOption[] {
    const courier = this.detail();
    const bound = new Set((courier?.branches ?? []).map((branch) => branch.locationId));
    return this.location.options().filter((option) => !bound.has(option.id));
  }

  protected openBindBranchForm(): void {
    this.bindLocationId.set(this.availableLocationsToBind()[0]?.id ?? '');
    this.bindPrimary.set(false);
    this.bindReason.set('');
    this.bindError.set(null);
    this.showBindBranchForm.set(true);
  }

  protected closeBindBranchForm(): void {
    this.showBindBranchForm.set(false);
  }

  protected canBindBranch(): boolean {
    return (
      !this.bindSubmitting() && this.bindLocationId() !== '' && this.bindReason().trim().length > 0
    );
  }

  protected async submitBindBranch(): Promise<void> {
    const scope = this.location.scope();
    const courier = this.detail();
    if (!scope || !courier || !this.canBindBranch()) {
      return;
    }
    this.bindSubmitting.set(true);
    this.bindError.set(null);
    try {
      await this.api.bindBranch(
        scope.tenantId,
        courier.courierId,
        scope.brandId,
        this.bindLocationId(),
        this.bindPrimary(),
        this.bindReason().trim(),
      );
      this.showBindBranchForm.set(false);
      await this.refreshDetail();
    } catch (error) {
      this.bindError.set(this.describe(error));
    } finally {
      this.bindSubmitting.set(false);
    }
  }

  protected startUnbindBranch(locationId: string): void {
    this.unbindingLocationId.set(locationId);
    this.unbindReason.set('');
    this.unbindError.set(null);
  }

  protected cancelUnbindBranch(): void {
    this.unbindingLocationId.set(null);
  }

  protected canConfirmUnbindBranch(): boolean {
    return !this.unbindSubmitting() && this.unbindReason().trim().length > 0;
  }

  protected async confirmUnbindBranch(): Promise<void> {
    const scope = this.location.scope();
    const courier = this.detail();
    const locationId = this.unbindingLocationId();
    // brandId comes off the binding itself, not the operator's current scope:
    // a branch bound earlier may belong to a brand the operator has since
    // switched away from, and courierBranchUnbinding needs the binding's own.
    const branch = courier?.branches.find((candidate) => candidate.locationId === locationId);
    if (!scope || !courier || !branch || !this.canConfirmUnbindBranch()) {
      return;
    }
    this.unbindSubmitting.set(true);
    this.unbindError.set(null);
    try {
      await this.api.unbindBranch(
        scope.tenantId,
        courier.courierId,
        branch.brandId,
        branch.locationId,
        this.unbindReason().trim(),
      );
      this.unbindingLocationId.set(null);
      await this.refreshDetail();
    } catch (error) {
      this.unbindError.set(this.describe(error));
    } finally {
      this.unbindSubmitting.set(false);
    }
  }

  protected setCompliance(target: 'new' | 'edit', field: ComplianceFieldName, value: string): void {
    const box = target === 'new' ? this.newCompliance : this.editCompliance;
    box.update((current) => ({ ...current, [field]: value }));
  }

  protected complianceLabel(field: ComplianceFieldName): string {
    return this.i18n.t(`couriers.compliance.field.${field}`);
  }

  protected fuelTypeLabel(fuel: VehicleFuelType): string {
    return this.i18n.t(`couriers.compliance.fuel.${fuel}`);
  }

  // --------------------------------------------------------------- display

  protected engagementStatusLabel(status: string | null | undefined): string {
    switch (status) {
      case 'PENDING_VERIFICATION':
        return this.i18n.t('couriers.engagement.status.PENDING_VERIFICATION');
      case 'ACTIVE':
        return this.i18n.t('couriers.engagement.status.ACTIVE');
      case 'SUSPENDED_COMPLIANCE':
        return this.i18n.t('couriers.engagement.status.SUSPENDED_COMPLIANCE');
      case 'SUSPENDED_OPERATIONAL':
        return this.i18n.t('couriers.engagement.status.SUSPENDED_OPERATIONAL');
      case 'ENDED':
        return this.i18n.t('couriers.engagement.status.ENDED');
      case null:
      case undefined:
        return '—';
      default:
        // Unrecognised or absent: render harmlessly, the same rule
        // `order-status.ts`'s `orderStatusLabel` follows for a status this
        // client does not know yet.
        return status;
    }
  }

  /**
   * What the courier rides. Falls through to the raw code for a class this
   * client does not know yet, the same rule `engagementStatusLabel` follows —
   * an unfamiliar vehicle should read as an unfamiliar word, not as an empty
   * cell that looks like a courier with no vehicle at all.
   */
  protected vehicleClassLabel(vehicleClass: string): string {
    switch (vehicleClass) {
      case 'FOOT':
      case 'BICYCLE':
      case 'SCOOTER':
      case 'MOTORCYCLE':
      case 'CAR':
        return this.i18n.t(`couriers.vehicleClass.${vehicleClass}`);
      default:
        return vehicleClass;
    }
  }

  protected canVerifyRow(courier: RosterEntryResponse): boolean {
    return courier.engagementStatus === 'PENDING_VERIFICATION';
  }

  protected canSuspendRow(courier: RosterEntryResponse): boolean {
    return (
      courier.engagementStatus === 'ACTIVE' || courier.engagementStatus === 'PENDING_VERIFICATION'
    );
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}

/** Nine empty strings, keyed by field. */
function emptyCompliance(): Record<ComplianceFieldName, string> {
  return {
    PASSPORT: '',
    PINFL: '',
    DRIVING_LICENCE: '',
    VEHICLE_REGISTRATION: '',
    VEHICLE_PLATE: '',
    ADDRESS: '',
    EMERGENCY_CONTACT: '',
    REFERRAL: '',
    NOTES: '',
  };
}

/**
 * The filled-in fields, and only those.
 *
 * A blank box means "I am not changing this", never "erase it" — the console
 * has never held these plaintexts, so it cannot tell an empty box from a field
 * it simply did not read, and sending the blank would erase a passport the
 * operator never saw. Clearing is the endpoint's `clear` list, a separate and
 * deliberate act this form does not yet offer.
 */
function compliancePayload(
  values: Record<ComplianceFieldName, string>,
  fuelType: string,
): Partial<CourierComplianceFileRequest> {
  const payload: Record<string, string> = {};
  const names: Record<ComplianceFieldName, string> = {
    PASSPORT: 'passport',
    PINFL: 'pinfl',
    DRIVING_LICENCE: 'drivingLicence',
    VEHICLE_REGISTRATION: 'vehicleRegistration',
    VEHICLE_PLATE: 'vehiclePlate',
    ADDRESS: 'homeAddress',
    EMERGENCY_CONTACT: 'emergencyContact',
    REFERRAL: 'referral',
    NOTES: 'remarks',
  };
  for (const field of COMPLIANCE_FIELDS) {
    const value = values[field].trim();
    if (value.length > 0) {
      payload[names[field]] = value;
    }
  }
  if (fuelType !== '') {
    payload['vehicleFuelType'] = fuelType;
  }
  return payload as Partial<CourierComplianceFileRequest>;
}
