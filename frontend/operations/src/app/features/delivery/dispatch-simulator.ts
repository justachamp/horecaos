import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';

import { ApiError } from '../../core/api/problem-details';
import {
  formatDateTime,
  parseZonedDatetimeLocal,
  toZonedDatetimeLocal,
} from '../../core/format/datetime';
import { I18n } from '../../core/i18n/i18n';
import { PlatformLocales } from '../../core/i18n/platform-locales';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import {
  DispatchOptions,
  DispatchRulesApi,
  DispatchRulesDocument,
  RecentPlan,
  ScopeLevel,
  SimulationRequest,
  SimulationResult,
  SOURCE_TYPES,
  TraceState,
} from './dispatch-rules-api';
import { MODE_LABELS, parseOptionalInt } from './dispatch-rules-model';
import { localisedName } from './localised-name';

const STATE_LABELS: Readonly<Record<TraceState, MessageKey>> = {
  MATCHED: 'delivery.rules.sim.state.MATCHED',
  NOT_MATCHED: 'delivery.rules.sim.state.NOT_MATCHED',
  DISABLED: 'delivery.rules.sim.state.DISABLED',
  NOT_EVALUATED: 'delivery.rules.sim.state.NOT_EVALUATED',
};

const FAILED_LABELS: Readonly<Record<string, MessageKey>> = {
  SOURCE: 'delivery.rules.sim.failed.SOURCE',
  CHANNEL: 'delivery.rules.sim.failed.CHANNEL',
  ZONE: 'delivery.rules.sim.failed.ZONE',
  BRANCH: 'delivery.rules.sim.failed.BRANCH',
  PREPARATION: 'delivery.rules.sim.failed.PREPARATION',
  DISTANCE: 'delivery.rules.sim.failed.DISTANCE',
  LOCAL_TIME: 'delivery.rules.sim.failed.LOCAL_TIME',
  PREPAID: 'delivery.rules.sim.failed.PREPAID',
};

const LANE_LABELS: Readonly<Record<'FLEET' | 'PARTNERS', MessageKey>> = {
  FLEET: 'delivery.rules.sim.lane.FLEET',
  PARTNERS: 'delivery.rules.sim.lane.PARTNERS',
};

const SKIP_LABELS: Readonly<Record<string, MessageKey>> = {
  NO_ACTIVE_BINDING: 'delivery.rules.sim.skip.NO_ACTIVE_BINDING',
  EXCLUDED: 'delivery.rules.sim.skip.EXCLUDED',
};

const NOTE_LABELS: Readonly<Record<string, MessageKey>> = {
  NO_PARTNER_AVAILABLE: 'delivery.rules.sim.note.NO_PARTNER_AVAILABLE',
  WINNER_DECIDED_BY_QUOTES: 'delivery.rules.sim.note.WINNER_DECIDED_BY_QUOTES',
  NO_ZONE_EVIDENCE: 'delivery.rules.sim.note.NO_ZONE_EVIDENCE',
};

/** The platform is Uzbekistan-only and Uzbekistan has no DST, so a bare wall-clock time is read here until a result reports the branch's own zone. */
const FALLBACK_ZONE = 'Asia/Tashkent';

/**
 * "Try an order" (IA 3.8, ADR 0142 Decision 4): which rule an order would match, why the rules above it
 * did not, where it would go and when the search for a courier would start.
 *
 * **It asks the server, on purpose.** The evaluator is a pure function in the platform and the live path
 * runs the same method over the same facts; a second implementation here would agree until it did not.
 * (`q-rule-simulator` is the client-side preview for rule sets that have no server evaluator -- the
 * automations' -- and is the wrong tool for a rule a courier's pay and a customer's wait depend on.)
 * It calls no partner, asks no price, and says so on every result.
 *
 * The facts are typed, or taken from a recent order of the branch; the rules are the unsaved draft the
 * editor holds (when there is one and the operator wants it) or what is published.
 */
@Component({
  selector: 'q-dispatch-simulator',
  imports: [TPipe],
  templateUrl: './dispatch-simulator.html',
  styleUrl: './dispatch-simulator.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DispatchSimulator {
  private readonly api = inject(DispatchRulesApi);
  protected readonly i18n = inject(I18n);
  private readonly registry = inject(PlatformLocales);

  readonly tenantId = input.required<string>();
  /** The branch the order is for: which partners are bound, whose clock, whose timings. */
  readonly brandId = input.required<string>();
  readonly locationId = input.required<string>();
  /** The scope the draft is written for, deciding what the server lets it name. */
  readonly scopeLevel = input<ScopeLevel>('LOCATION');
  readonly draft = input<DispatchRulesDocument | null>(null);
  readonly dirty = input(false);
  readonly options = input<DispatchOptions | null>(null);

  protected readonly sourceTypes = SOURCE_TYPES;

  protected readonly mode = signal<'facts' | 'plan'>('facts');
  protected readonly useDraft = signal(true);
  protected readonly source = signal('WEB');
  protected readonly zoneId = signal('');
  protected readonly channelId = signal('');
  protected readonly preparation = signal('15');
  protected readonly distance = signal('3000');
  protected readonly confirmedAt = signal(toZonedDatetimeLocal(new Date(), FALLBACK_ZONE));
  protected readonly prepaid = signal(true);
  protected readonly planId = signal('');

  protected readonly running = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly result = signal<SimulationResult | null>(null);

  /**
   * The recent orders the operator can re-read: the newest of the scope being viewed, so at the company or brand
   * level they belong to several branches. The server finds a plan only at the branch it belongs to, so the
   * ones whose branch cannot be placed are not offered at all.
   */
  protected readonly recentPlans = computed(() =>
    (this.options()?.recentPlans ?? []).filter((plan) => this.branchOf(plan) !== null),
  );
  protected readonly zones = computed(() => this.options()?.zones ?? []);
  protected readonly channels = computed(() => this.options()?.channels ?? []);
  protected readonly usingDraft = computed(() => this.dirty() && this.useDraft());

  protected readonly canRun = computed(
    () =>
      !this.running() &&
      (this.mode() === 'facts' || this.recentPlans().some((plan) => plan.planId === this.planId())),
  );

  protected zoneLabel(zone: DispatchOptions['zones'][number]): string {
    return localisedName(
      this.i18n.locale(),
      {
        displayNameRu: zone.nameRu,
        displayNameUz: zone.nameUz,
        displayNameEn: zone.nameEn,
      },
      this.registry.fallbackOrder(),
    );
  }

  /** An order's number and state, and the branch it was for when that is not the operator's own. */
  protected planLabel(plan: RecentPlan): string {
    const label = `${plan.orderReference} · ${plan.status}`;
    if (plan.locationId === this.locationId()) {
      return label;
    }
    const branch = this.options()?.locations.find((location) => location.id === plan.locationId);
    return branch ? `${label} · ${branch.displayName}` : label;
  }

  protected modeLabel(mode: SimulationResult['decision']['mode']): MessageKey {
    return MODE_LABELS[mode];
  }

  protected stateLabel(state: TraceState): MessageKey {
    return STATE_LABELS[state];
  }

  protected failedLabel(condition: string): MessageKey | null {
    return FAILED_LABELS[condition] ?? null;
  }

  protected laneLabel(lane: 'FLEET' | 'PARTNERS'): MessageKey {
    return LANE_LABELS[lane];
  }

  protected skipLabel(reason: string): MessageKey | null {
    return SKIP_LABELS[reason] ?? null;
  }

  protected noteLabel(note: string): MessageKey | null {
    return NOTE_LABELS[note] ?? null;
  }

  /** An installation's name for a skip, which carries only its id. */
  protected installationName(id: string): string {
    return (
      this.options()?.installations.find((installation) => installation.id === id)?.displayName ??
      id
    );
  }

  protected whenIn(instant: string, zone: string): string {
    return formatDateTime(new Date(instant), zone);
  }

  protected async run(): Promise<void> {
    if (!this.canRun()) {
      return;
    }
    this.running.set(true);
    this.error.set(null);
    try {
      this.result.set(await this.api.simulate(this.tenantId(), this.request()));
    } catch (failure) {
      this.result.set(null);
      this.error.set(
        failure instanceof ApiError
          ? describeApiError(failure, (k, v) => this.i18n.t(k, v))
          : this.i18n.t('error.unknown.noReference'),
      );
    } finally {
      this.running.set(false);
    }
  }

  /**
   * The branch a recent plan was for, or null when this screen cannot place it.
   *
   * What the draft is judged against follows the same ids (a branch's rules name what that branch may name),
   * so a plan is only placed where the document being written reaches it: any branch of the company at the
   * company level, a branch of this brand at the brand level, and only this branch at the branch level.
   */
  private branchOf(plan: RecentPlan): { brandId: string; locationId: string } | null {
    if (plan.locationId === this.locationId()) {
      return { brandId: this.brandId(), locationId: plan.locationId };
    }
    const level = this.scopeLevel();
    if (level === 'LOCATION') {
      return null;
    }
    const branch = this.options()?.locations.find((location) => location.id === plan.locationId);
    if (!branch || (level === 'BRAND' && branch.brandId !== this.brandId())) {
      return null;
    }
    return { brandId: branch.brandId, locationId: branch.id };
  }

  private request(): SimulationRequest {
    const draft = this.usingDraft() ? this.draft() : null;
    const base = {
      brandId: this.brandId(),
      locationId: this.locationId(),
      scopeType: this.scopeLevel(),
      ...(draft ? { draft } : {}),
    };
    if (this.mode() === 'plan') {
      // The branch is the plan's own, not the operator's: the server answers 404 for a plan asked about at
      // any other branch.
      const plan = this.recentPlans().find((candidate) => candidate.planId === this.planId());
      const branch = plan ? this.branchOf(plan) : null;
      return { ...base, ...(branch ?? {}), planId: this.planId() };
    }
    const zone = this.result()?.facts.branchTimezone ?? FALLBACK_ZONE;
    return {
      ...base,
      scenario: {
        ...(this.source() ? { sourceSystemType: this.source() } : {}),
        ...(this.channelId() ? { channelId: this.channelId() } : {}),
        ...(this.zoneId() ? { zoneId: this.zoneId() } : {}),
        preparationMinutes: parseOptionalInt(this.preparation()) ?? 0,
        distanceMeters: parseOptionalInt(this.distance()) ?? 0,
        confirmedAt: instantOf(this.confirmedAt(), zone),
        prepaid: this.prepaid(),
      },
    };
  }
}

/** A `datetime-local` value read as wall-clock time in the branch's zone, as an ISO instant. */
function instantOf(local: string, zone: string): string {
  return parseZonedDatetimeLocal(local, zone).toISOString();
}
