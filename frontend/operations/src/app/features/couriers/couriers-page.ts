import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import {
  ComplianceFieldName,
  CourierComplianceFileRequest,
  CourierDetailResponse,
  CouriersApi,
  CourierTypeResponse,
  RosterEntryResponse,
  VehicleFuelType,
} from './couriers-api';

type DialogKind = {
  readonly kind: 'verify' | 'suspend';
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
      const [roster, types] = await Promise.all([
        this.api.roster(scope.tenantId),
        this.api.types(scope.tenantId),
      ]);
      this.roster.set(roster);
      this.types.set(types);
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
