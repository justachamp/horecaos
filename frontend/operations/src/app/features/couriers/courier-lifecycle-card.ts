import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { CouriersApi, CourierTypeResponse, RosterEntryResponse } from './couriers-api';

type Mode = 'idle' | 'edit' | 'end' | 'archive';

const REFUSAL_LABELS: Readonly<Record<string, MessageKey>> = {
  COURIER_HAS_OPEN_SHIFT: 'couriers.lifecycle.refused.COURIER_HAS_OPEN_SHIFT',
  COURIER_HAS_ACTIVE_DELIVERIES: 'couriers.lifecycle.refused.COURIER_HAS_ACTIVE_DELIVERIES',
  COURIER_BALANCE_NOT_ZERO: 'couriers.lifecycle.refused.COURIER_BALANCE_NOT_ZERO',
};

/**
 * Correct a courier's reference or type, end their engagement, archive them (IA 3.3 «courier CRUD», couriers.md
 * §5 «Archive … only when balance is zero and no open shift», ADR 0178).
 *
 * Before this the only thing that ended an engagement or archived a courier was the applicant erasure sweep, so a
 * courier who left stayed on the roster for good and a mistyped reference was permanent. The refusals are the
 * point: a courier on a shift, carrying a delivery or owed money is not ended or archived, and the card says which
 * and what to do first, from a stable reason rather than a sentence it parses. Every step asks for a reason that
 * lands in the audit trail.
 */
@Component({
  selector: 'q-courier-lifecycle-card',
  imports: [TPipe],
  templateUrl: './courier-lifecycle-card.html',
  styleUrl: './courier-lifecycle-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CourierLifecycleCard {
  private readonly api = inject(CouriersApi);
  protected readonly i18n = inject(I18n);

  readonly tenantId = input.required<string>();
  /** The roster row, which carries the `version` a correction sends back. */
  readonly courier = input.required<RosterEntryResponse>();
  readonly types = input<readonly CourierTypeResponse[]>([]);

  /** Fired after any step succeeded, so the page can re-read its roster and the open record. */
  readonly changed = output<void>();

  protected readonly mode = signal<Mode>('idle');
  protected readonly reference = signal('');
  protected readonly typeId = signal('');
  protected readonly reason = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly archived = computed(() => this.courier().status === 'ARCHIVED');
  protected readonly hasEngagement = computed(() => {
    const status = this.courier().engagementStatus;
    return status !== null && status !== undefined && status !== 'ENDED';
  });

  protected readonly canSubmit = computed(() => {
    if (this.busy() || this.reason().trim().length === 0) {
      return false;
    }
    if (this.mode() === 'edit') {
      return this.pendingReference() !== null || this.pendingType() !== null;
    }
    return this.mode() === 'end' || this.mode() === 'archive';
  });

  protected open(mode: Mode): void {
    this.reference.set(this.courier().displayReference);
    this.typeId.set(this.courier().courierTypeId);
    this.reason.set('');
    this.error.set(null);
    this.mode.set(mode);
  }

  protected close(): void {
    this.mode.set('idle');
  }

  private pendingReference(): string | null {
    const typed = this.reference().trim();
    return typed.length > 0 && typed !== this.courier().displayReference ? typed : null;
  }

  private pendingType(): string | null {
    return this.typeId() !== '' && this.typeId() !== this.courier().courierTypeId
      ? this.typeId()
      : null;
  }

  protected async submit(): Promise<void> {
    if (!this.canSubmit()) {
      return;
    }
    const tenantId = this.tenantId();
    const courier = this.courier();
    this.busy.set(true);
    this.error.set(null);
    try {
      switch (this.mode()) {
        case 'edit':
          await this.api.correct(tenantId, courier.courierId, {
            expectedVersion: courier.version ?? 0,
            displayReference: this.pendingReference(),
            courierTypeId: this.pendingType(),
            reason: this.reason().trim(),
          });
          break;
        case 'end':
          await this.api.endEngagement(tenantId, courier.courierId, this.reason().trim());
          break;
        case 'archive':
          await this.api.archive(tenantId, courier.courierId, this.reason().trim());
          break;
        default:
          return;
      }
      this.mode.set('idle');
      this.changed.emit();
    } catch (failure) {
      this.error.set(this.describe(failure));
    } finally {
      this.busy.set(false);
    }
  }

  private describe(failure: unknown): string {
    if (!(failure instanceof ApiError)) {
      return this.i18n.t('error.unknown.noReference');
    }
    const reason = failure.problem?.['reason'];
    const refusal = typeof reason === 'string' ? REFUSAL_LABELS[reason] : undefined;
    if (refusal) {
      return this.i18n.t(refusal);
    }
    if (failure.code === ApiErrorCode.STALE_VERSION) {
      return this.i18n.t('couriers.lifecycle.stale');
    }
    if (
      failure.code === ApiErrorCode.RESOURCE_CONFLICT &&
      failure.problem?.['field'] === 'displayReference'
    ) {
      return this.i18n.t('couriers.lifecycle.taken');
    }
    return describeApiError(failure, (key, values) => this.i18n.t(key, values));
  }
}
