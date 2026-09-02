import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CouriersApi, CourierTypeResponse, RosterEntryResponse } from './couriers-api';

type DialogKind = {
  readonly kind: 'verify' | 'suspend';
  readonly courier: RosterEntryResponse;
} | null;

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
 * **Not built, honestly** (see the wave's final report for the full backend
 * audit — `fulfillment.couriers` carries only id/type/subject/reference/
 * protected-name/status): passport, PINFL, driving licence, vehicle
 * registration/plate/fuel type, photo, emergency contact, address, referral,
 * notes, branch bindings (many-to-many), courier groups, and courier-app
 * account provisioning beyond the Keycloak subject entered at registration.
 * Online status and rating are read-only per the spec, and neither exists on
 * this build's roster row at all. Each is a real column or table this wave's
 * backend does not have — not a UI gap this wave could close by rendering a
 * disabled field, per the "omit, do not disable" rule `not-built-page.ts`
 * follows for a whole screen, applied here to a field on a real one.
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
