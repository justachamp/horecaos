import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';

import { I18n } from '../../core/i18n/i18n';
import { TPipe } from '../../core/i18n/t.pipe';
import {
  ConditionCandidate,
  ConditionGroup,
  ConditionTypeDescriptor,
  ConditionValueKind,
  DAY_OF_WEEK_KEYS,
  descriptorFor,
  evaluateConditionGroups,
} from './condition-types';

/** One rule to preview a candidate against — already priority-ordered by the host, the same order a `q-rule-list` would show it in. */
export interface SimulatedRule {
  readonly id: string;
  readonly label: string;
  readonly enabled: boolean;
  readonly groups: readonly ConditionGroup[];
  /** Already-translated: "10% off", "Assign to the Chilonzor courier pool" — the simulator renders it, never invents it. */
  readonly outcome: string;
}

type RuleResult =
  | { readonly rule: SimulatedRule; readonly state: 'disabled' }
  | { readonly rule: SimulatedRule; readonly state: 'matched' | 'unmatched' };

/**
 * The dry-run every rule engine in this console needs before a rule goes live
 * (ADR 0101, row `X.25`) — "an operator cannot express a provider fallback
 * rule, and nothing can be dry-run before it goes live" is this row's own
 * complaint, and this component is the second half of the answer (the first
 * half is `q-condition-builder`, which produces the {@link SimulatedRule.groups}
 * this reads).
 *
 * **Generalises the existing dry run; does not duplicate it.** Audiences
 * already have one — `segments-page.ts`'s "build a snapshot" and
 * `campaigns-page.ts`'s campaign estimate both send a request and get a
 * member count back. Those stay exactly as they are: a real count over real
 * customer rows is a server job. This component answers a different, cheaper
 * question — "if a candidate looked like *this*, which of my rules would
 * fire, in what order, and what would each one do" — entirely client-side,
 * against a candidate the caller types in, never a real order or customer.
 * `PromotionEvaluator`'s own priority tie-break already exists on the server;
 * this is a preview of that shape, not a second implementation of it.
 *
 * **No backend, on purpose.** "must not read live orders" is the row's own
 * constraint — a dry run that touches production data is the host screen's
 * own capability (a real snapshot, a real estimate), out of scope here.
 */
@Component({
  selector: 'q-rule-simulator',
  imports: [TPipe],
  templateUrl: './rule-simulator.html',
  styleUrl: './rule-simulator.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RuleSimulator {
  protected readonly i18n = inject(I18n);

  readonly catalogue = input.required<readonly ConditionTypeDescriptor[]>();
  /** Already priority-ordered — array order is evaluation order, exactly what `q-rule-list` shows. */
  readonly rules = input.required<readonly SimulatedRule[]>();

  protected readonly days = [1, 2, 3, 4, 5, 6, 7] as const;

  /** What the operator has typed for the candidate so far, one raw string per condition type. Never persisted, never sent — this component makes no request at all. */
  protected readonly candidateDraft = signal<Readonly<Record<string, string>>>({});

  /** Only the condition types this rule set actually mentions — asking for a value nothing checks would just be noise. */
  protected readonly typesInUse = computed<readonly ConditionTypeDescriptor[]>(() => {
    const seen = new Set<string>();
    for (const rule of this.rules()) {
      for (const group of rule.groups) {
        for (const row of group.rows) {
          seen.add(row.type);
        }
      }
    }
    return this.catalogue().filter((descriptor) => seen.has(descriptor.type));
  });

  private readonly candidate = computed<ConditionCandidate>(() => {
    const draft = this.candidateDraft();
    const values: Record<string, string | number | readonly string[] | readonly number[] | null> =
      {};
    for (const descriptor of this.typesInUse()) {
      values[descriptor.type] = toCandidateValue(draft[descriptor.type], descriptor.valueKind);
    }
    return values;
  });

  protected readonly results = computed<readonly RuleResult[]>(() =>
    this.rules().map((rule) => {
      if (!rule.enabled) {
        return { rule, state: 'disabled' as const };
      }
      const matched = evaluateConditionGroups(rule.groups, this.catalogue(), this.candidate());
      const state: 'matched' | 'unmatched' = matched ? 'matched' : 'unmatched';
      return { rule, state };
    }),
  );

  protected typeLabel(type: string): string {
    return this.i18n.t(descriptorFor(this.catalogue(), type).labelKey);
  }

  protected dayLabel(day: number): string {
    return this.i18n.t(DAY_OF_WEEK_KEYS[day - 1]);
  }

  protected setValue(type: string, value: string): void {
    this.candidateDraft.update((current) => ({ ...current, [type]: value }));
  }

  protected setDay(type: string, day: number): void {
    this.setValue(type, String(day));
  }

  protected isDayActive(type: string, day: number): boolean {
    return this.candidateDraft()[type] === String(day);
  }

  protected valueOf(type: string): string {
    return this.candidateDraft()[type] ?? '';
  }
}

function toCandidateValue(
  raw: string | undefined,
  kind: ConditionValueKind,
): string | number | readonly string[] | readonly number[] | null {
  if (raw === undefined || raw.trim() === '') {
    return null;
  }
  switch (kind) {
    case 'NUMERIC':
    case 'MONEY_MINOR':
    case 'PERCENT': {
      const value = Number(raw);
      return Number.isNaN(value) ? null : value;
    }
    case 'DATE':
    case 'DATE_RANGE':
      return raw;
    case 'TEXT_SET':
    case 'REFERENCE':
      return raw
        .split(',')
        .map((value) => value.trim())
        .filter((value) => value.length > 0);
    case 'DAY_OF_WEEK_SET': {
      const day = Number(raw);
      return Number.isNaN(day) ? null : [day];
    }
  }
}
