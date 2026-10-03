import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { DispatchRulesApi, DispatchScope, SourcingTimings } from './dispatch-rules-api';
import { canonical } from './dispatch-rules-model';

type TimingField = keyof SourcingTimings;

interface FieldSpec {
  readonly field: TimingField;
  readonly labelKey: MessageKey;
  /** Whether the box shows minutes (the stored value is seconds) or the stored unit as it is. */
  readonly unit: 'minutes' | 'seconds' | 'count';
  readonly min: number;
}

const FIELDS: readonly FieldSpec[] = [
  {
    field: 'preparationLeadSeconds',
    labelKey: 'delivery.timings.preparationLead',
    unit: 'minutes',
    min: 0,
  },
  {
    field: 'partnerLeadSeconds',
    labelKey: 'delivery.timings.partnerLead',
    unit: 'minutes',
    min: 0,
  },
  {
    field: 'safetyBufferSeconds',
    labelKey: 'delivery.timings.safetyBuffer',
    unit: 'minutes',
    min: 0,
  },
  {
    field: 'pickupToleranceSeconds',
    labelKey: 'delivery.timings.pickupTolerance',
    unit: 'minutes',
    min: 1,
  },
  { field: 'offerRounds', labelKey: 'delivery.timings.offerRounds', unit: 'count', min: 1 },
  { field: 'maxOfferSeconds', labelKey: 'delivery.timings.maxOffer', unit: 'seconds', min: 15 },
  {
    field: 'latestAssignmentSlackSeconds',
    labelKey: 'delivery.timings.slack',
    unit: 'minutes',
    min: 0,
  },
];

/**
 * The `fulfillment.sourcing` numbers behind ADR 0014 (ADR 0142): how long a courier takes to reach the
 * branch, how wide the pickup window is, how many couriers are asked before a partner is called.
 *
 * The document has been resolved by every plan since ADR 0014 and written by nobody, so the provisional
 * defaults were in force at every branch of every tenant. This is its writer, on the same screen as the
 * rules because the two answer one question between them -- which lane, and how long it is given.
 *
 * Whole-document: the seven numbers are one conversation, and a lead time changed without the buffer that
 * goes with it is a mistake made by editing one of a pair. Values are held in the unit the server stores
 * (seconds) and only *shown* in minutes, so a number the operator never touches is never rounded.
 */
@Component({
  selector: 'q-sourcing-timings-card',
  imports: [TPipe],
  templateUrl: './sourcing-timings-card.html',
  styleUrl: './sourcing-timings-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SourcingTimingsCard {
  private readonly api = inject(DispatchRulesApi);
  protected readonly i18n = inject(I18n);

  readonly scope = input.required<DispatchScope>();
  readonly canWrite = input(true);

  protected readonly fields = FIELDS;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saveError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly isDefaults = signal(true);

  private readonly loaded = signal<SourcingTimings | null>(null);
  private readonly version = signal(0);
  protected readonly draft = signal<SourcingTimings | null>(null);
  protected readonly reason = signal('');

  protected readonly dirty = computed(() => {
    const draft = this.draft();
    const loaded = this.loaded();
    return draft !== null && loaded !== null && canonical(draft) !== canonical(loaded);
  });

  protected readonly canPublish = computed(
    () => this.canWrite() && this.dirty() && this.reason().trim() !== '' && !this.saving(),
  );

  constructor() {
    // Reloads whenever the scope the page is looking at changes.
    effect(() => {
      const scope = this.scope();
      untracked(() => void this.load(scope));
    });
  }

  /** What the box shows: the stored seconds as minutes, or as they are for a count and for seconds. */
  protected shown(spec: FieldSpec): string {
    const value = this.draft()?.[spec.field];
    if (value === undefined) {
      return '';
    }
    return spec.unit === 'minutes' ? String(value / 60) : String(value);
  }

  protected set(spec: FieldSpec, raw: string): void {
    const draft = this.draft();
    const parsed = Number(raw);
    if (!draft || raw.trim() === '' || Number.isNaN(parsed)) {
      return;
    }
    const stored = spec.unit === 'minutes' ? Math.round(parsed * 60) : Math.round(parsed);
    this.draft.set({ ...draft, [spec.field]: stored });
    this.notice.set(null);
  }

  protected discard(): void {
    this.draft.set(this.loaded());
    this.reason.set('');
    this.saveError.set(null);
  }

  protected async publish(): Promise<void> {
    const draft = this.draft();
    if (!draft || !this.canPublish()) {
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    try {
      const published = await this.api.publishTimings(
        this.scope(),
        draft,
        this.reason().trim(),
        this.version(),
      );
      this.adopt(published);
      this.reason.set('');
      this.notice.set(this.i18n.t('delivery.timings.saved', { version: published.policyVersion }));
    } catch (failure) {
      this.saveError.set(this.describe(failure));
    } finally {
      this.saving.set(false);
    }
  }

  private async load(scope: DispatchScope): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    this.saveError.set(null);
    this.notice.set(null);
    try {
      const { value, version } = await this.api.timings(scope);
      this.adopt(value);
      this.version.set(version);
    } catch (failure) {
      this.loadError.set(this.describe(failure));
    } finally {
      this.loading.set(false);
    }
  }

  private adopt(view: SourcingTimings & { isDefaults: boolean; versionAtScope: number }): void {
    const numbers: SourcingTimings = {
      preparationLeadSeconds: view.preparationLeadSeconds,
      partnerLeadSeconds: view.partnerLeadSeconds,
      safetyBufferSeconds: view.safetyBufferSeconds,
      pickupToleranceSeconds: view.pickupToleranceSeconds,
      offerRounds: view.offerRounds,
      maxOfferSeconds: view.maxOfferSeconds,
      latestAssignmentSlackSeconds: view.latestAssignmentSlackSeconds,
    };
    this.loaded.set(numbers);
    this.draft.set(numbers);
    this.isDefaults.set(view.isDefaults);
    this.version.set(view.versionAtScope);
  }

  private describe(failure: unknown): string {
    return failure instanceof ApiError
      ? describeApiError(failure, (key, values) => this.i18n.t(key, values))
      : this.i18n.t('error.unknown.noReference');
  }
}
