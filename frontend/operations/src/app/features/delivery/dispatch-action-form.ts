import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import {
  DispatchAction,
  DispatchOptions,
  InstallationOption,
  PartnerSelection,
  SOURCING_MODES,
  SourcingMode,
  StartBasis,
} from './dispatch-rules-api';
import {
  MODE_LABELS,
  minutesToSeconds,
  moved,
  parseOptionalInt,
  secondsToMinutes,
  toggled,
  usesFleet,
  usesPartners,
} from './dispatch-rules-model';

const SELECTION_LABELS: Readonly<Record<PartnerSelection, MessageKey>> = {
  LADDER: 'delivery.rules.selection.LADDER',
  CHEAPEST: 'delivery.rules.selection.CHEAPEST',
};

const START_LABELS: Readonly<Record<StartBasis, MessageKey>> = {
  LEAD: 'delivery.rules.start.LEAD',
  CONFIRMATION: 'delivery.rules.start.CONFIRMATION',
  READY: 'delivery.rules.start.READY',
};

const START_BASES: readonly StartBasis[] = ['LEAD', 'CONFIRMATION', 'READY'];
const SELECTIONS: readonly PartnerSelection[] = ['LADDER', 'CHEAPEST'];

/** The default grouping a rule starts with when an operator switches it on. */
const DEFAULT_GROUPING = {
  mergeRadiusMeters: 700,
  maxOrdersPerRun: 3,
  maxWaitSeconds: 120,
} as const;

/**
 * The "then" half of a dispatch rule, and the whole of the default (ADR 0142): how the order is sent
 * (which lanes, in which order), which delivery partners and how one is chosen, when the search for a
 * courier starts, and -- once it is allowed -- grouping.
 *
 * Presentational, like its sibling {@link DispatchConditionsForm}: a whole new {@link DispatchAction}
 * goes out through {@link actionChange}, and the host keeps the draft.
 *
 * **What it does not offer, on purpose.** There is no "book every partner and cancel the losers": ADR
 * 0014 rejected a booking race and ADR 0142 keeps it closed, so a quote race (`CHEAPEST`) is the only
 * race. There is no hold: `holdBeforeConfirm` is reserved until the planner can emit one. Grouping is
 * shown locked, with the reason, until how a courier is paid for one run of several orders is decided.
 */
@Component({
  selector: 'q-dispatch-action-form',
  imports: [TPipe],
  templateUrl: './dispatch-action-form.html',
  styleUrl: './dispatch-action-form.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DispatchActionForm {
  protected readonly i18n = inject(I18n);

  readonly action = input.required<DispatchAction>();
  readonly options = input<DispatchOptions | null>(null);
  /** Whether this deployment lets a rule enable grouping. */
  readonly groupingAllowed = input(false);
  readonly idPrefix = input('action');

  readonly actionChange = output<DispatchAction>();

  protected readonly modes = SOURCING_MODES;
  protected readonly startBases = START_BASES;
  protected readonly selections = SELECTIONS;

  protected readonly asksPartners = computed(() => usesPartners(this.action().mode));
  protected readonly asksFleet = computed(() => usesFleet(this.action().mode));

  protected readonly installations = computed(() => this.options()?.installations ?? []);

  /** Installations not yet in the preferred list or the excluded one, so the "add" picker offers only what can be added. */
  protected readonly addable = computed<readonly InstallationOption[]>(() => {
    const { order, exclude } = this.action().partners;
    return this.installations().filter(
      (installation) => !order.includes(installation.id) && !exclude.includes(installation.id),
    );
  });

  protected readonly offsetMinutes = computed(() =>
    secondsToMinutes(this.action().dispatchAt.offsetSeconds),
  );

  protected modeLabel(mode: SourcingMode): MessageKey {
    return MODE_LABELS[mode];
  }

  protected selectionLabel(selection: PartnerSelection): MessageKey {
    return SELECTION_LABELS[selection];
  }

  protected startLabel(basis: StartBasis): MessageKey {
    return START_LABELS[basis];
  }

  /** An installation's operator-facing name, falling back to its id for one no longer listed (retired since the rule was written). */
  protected installationLabel(id: string): string {
    const found = this.installations().find((installation) => installation.id === id);
    return found ? found.displayName : id;
  }

  protected isKnown(id: string): boolean {
    return this.installations().some((installation) => installation.id === id);
  }

  protected setMode(mode: SourcingMode): void {
    // A mode that never asks a partner cannot carry a partner list: the server refuses the pair, and
    // a list the operator can no longer see is worse than an empty one.
    const partners = usesPartners(mode)
      ? this.action().partners
      : { ...this.action().partners, order: [], exclude: [] };
    // Grouping biases the fleet; under a mode that never asks the fleet it would be refused.
    const grouping = usesFleet(mode) ? this.action().grouping : null;
    this.emit({ mode, partners, grouping });
  }

  protected addPartner(id: string): void {
    if (!id) {
      return;
    }
    const partners = this.action().partners;
    this.emit({ partners: { ...partners, order: [...partners.order, id] } });
  }

  protected removePartner(id: string): void {
    const partners = this.action().partners;
    this.emit({ partners: { ...partners, order: partners.order.filter((entry) => entry !== id) } });
  }

  protected movePartner(index: number, delta: number): void {
    const partners = this.action().partners;
    this.emit({ partners: { ...partners, order: moved(partners.order, index, delta) } });
  }

  protected toggleExclude(id: string): void {
    const partners = this.action().partners;
    this.emit({ partners: { ...partners, exclude: toggled(partners.exclude, id) } });
  }

  protected setSelection(selection: PartnerSelection): void {
    this.emit({ partners: { ...this.action().partners, selection } });
  }

  protected setBasis(basis: StartBasis): void {
    this.emit({ dispatchAt: { ...this.action().dispatchAt, basis } });
  }

  protected setOffsetMinutes(raw: string): void {
    const minutes = parseOptionalInt(raw) ?? 0;
    this.emit({
      dispatchAt: { ...this.action().dispatchAt, offsetSeconds: minutesToSeconds(minutes) },
    });
  }

  protected toggleGrouping(on: boolean): void {
    this.emit({ grouping: on ? { ...DEFAULT_GROUPING } : null });
  }

  protected setGrouping(
    field: 'mergeRadiusMeters' | 'maxOrdersPerRun' | 'maxWaitSeconds',
    raw: string,
  ): void {
    const grouping = this.action().grouping;
    const value = parseOptionalInt(raw);
    if (grouping && value !== null) {
      this.emit({ grouping: { ...grouping, [field]: value } });
    }
  }

  private emit(patch: Partial<DispatchAction>): void {
    this.actionChange.emit({ ...this.action(), ...patch });
  }
}
