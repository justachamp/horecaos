import { MessageKey } from '../../core/i18n/messages.en';
import { nextDomId } from './overlay';

/**
 * The shared condition/rule vocabulary behind `q-condition-builder`,
 * `q-rule-list` and `q-rule-simulator` (ADR 0101, row `X.25`).
 *
 * Five rule engines in this console share one shape — a rule is a name, a
 * priority, an enabled flag and a boolean expression over typed conditions
 * (promotions, automations, auto-add, dispatch rules, courier bonus/penalty)
 * — and until this file existed the only one actually built was Customers
 * 5.3's segment predicate, hand-rolled inside `segments-page.ts` with no way
 * for another screen to reuse it. This module is deliberately engine-agnostic:
 * it knows how to render and evaluate a typed condition tree against a
 * caller-supplied catalogue, and nothing about promotions, dispatch or any
 * other domain. A consumer supplies its own {@link ConditionTypeDescriptor}
 * catalogue (segments' RFM predicates today; a promotion's channel/cart/time
 * conditions or a dispatch rule's zone/load conditions tomorrow) and gets the
 * builder, the list and the simulator for free.
 */

/** How a condition's value is shaped, and therefore which editor renders it. */
export type ConditionValueKind =
  | 'NUMERIC'
  | 'MONEY_MINOR'
  | 'PERCENT'
  | 'DATE'
  | 'DATE_RANGE'
  | 'TEXT_SET'
  | 'DAY_OF_WEEK_SET'
  | 'REFERENCE';

/**
 * The operator vocabulary every value kind draws from. Not every operator is
 * legal for every kind — {@link operatorsForValueKind} is the source of truth
 * for which ones a row may offer.
 */
export type ConditionOperator = 'AT_LEAST' | 'AT_MOST' | 'BETWEEN' | 'EQUALS' | 'IN' | 'NOT_IN';

/** `q-condition-builder`'s and `q-rule-simulator`'s shared, generic operator vocabulary — never overridden per catalogue, because "at least" means the same thing whatever the condition type is. */
export const CONDITION_OPERATOR_KEYS: Readonly<Record<ConditionOperator, MessageKey>> = {
  AT_LEAST: 'ui.conditionBuilder.operator.AT_LEAST',
  AT_MOST: 'ui.conditionBuilder.operator.AT_MOST',
  BETWEEN: 'ui.conditionBuilder.operator.BETWEEN',
  EQUALS: 'ui.conditionBuilder.operator.EQUALS',
  IN: 'ui.conditionBuilder.operator.IN',
  NOT_IN: 'ui.conditionBuilder.operator.NOT_IN',
};

/** Monday (1) through Sunday (7), ISO 8601 numbering — index `day - 1` — shared by every `DAY_OF_WEEK_SET` editor. */
export const DAY_OF_WEEK_KEYS: readonly MessageKey[] = [
  'ui.dayOfWeek.MON',
  'ui.dayOfWeek.TUE',
  'ui.dayOfWeek.WED',
  'ui.dayOfWeek.THU',
  'ui.dayOfWeek.FRI',
  'ui.dayOfWeek.SAT',
  'ui.dayOfWeek.SUN',
];

/** How a group's own rows combine — `AND` (all) or `OR` (any). Groups themselves always combine with OR. */
export type ConditionCombinator = 'AND' | 'OR';

/** One fixed choice for a `TEXT_SET`/`REFERENCE` condition — already translated, because the catalogue owner knows its own domain vocabulary. */
export interface ConditionFixedValue {
  readonly value: string;
  readonly label: string;
}

/**
 * One entry in a closed condition catalogue — the typed vocabulary a
 * `q-condition-builder` instance offers. Mirrors the pattern
 * `audience-predicates.ts` already used for segments alone: the catalogue is
 * exactly as closed on this side as its backend counterpart (`PredicateType`,
 * `PromotionEvaluator`'s own condition set, `DeliverySourcingPolicies`, …), so
 * it is mirrored here rather than fetched.
 */
export interface ConditionTypeDescriptor {
  readonly type: string;
  readonly labelKey: MessageKey;
  readonly valueKind: ConditionValueKind;
  /** Fixed, already-translated choices for a `TEXT_SET`/`REFERENCE` condition, or `null`/omitted when free text is accepted. */
  readonly fixedValues?: readonly ConditionFixedValue[] | null;
}

/** Every value a row may hold, kept as editable strings — converted at the consumer's own boundary (segments' `AudiencePredicate`, a future promotion condition, …). */
export interface ConditionRow {
  readonly id: string;
  readonly type: string;
  readonly operator: ConditionOperator;
  readonly numericLow: string;
  readonly numericHigh: string;
  readonly dateLow: string;
  readonly dateHigh: string;
  /** Comma-separated for `TEXT_SET`/`REFERENCE`. */
  readonly textValues: string;
  /** ISO weekday numbers, 1 (Monday) through 7 (Sunday), for `DAY_OF_WEEK_SET`. */
  readonly dayOfWeekValues: readonly number[];
}

/** A set of rows combined by one `AND`/`OR` combinator — the "and/or grouping" `X.25` asks for. */
export interface ConditionGroup {
  readonly id: string;
  readonly combinator: ConditionCombinator;
  readonly rows: readonly ConditionRow[];
}

export function operatorsForValueKind(kind: ConditionValueKind): readonly ConditionOperator[] {
  switch (kind) {
    case 'NUMERIC':
    case 'MONEY_MINOR':
    case 'PERCENT':
      return ['AT_LEAST', 'AT_MOST', 'BETWEEN'];
    case 'DATE':
      return ['AT_LEAST', 'AT_MOST', 'EQUALS'];
    case 'DATE_RANGE':
      return ['BETWEEN'];
    case 'TEXT_SET':
      return ['IN', 'NOT_IN'];
    case 'DAY_OF_WEEK_SET':
      return ['IN', 'NOT_IN'];
    case 'REFERENCE':
      return ['EQUALS', 'IN', 'NOT_IN'];
  }
}

export function descriptorFor(
  catalogue: readonly ConditionTypeDescriptor[],
  type: string,
): ConditionTypeDescriptor {
  const found = catalogue.find((candidate) => candidate.type === type);
  if (!found) {
    throw new RangeError(`${type} is not in this condition catalogue`);
  }
  return found;
}

export function emptyConditionRow(
  catalogue: readonly ConditionTypeDescriptor[],
  type: string = catalogue[0].type,
): ConditionRow {
  const operator = operatorsForValueKind(descriptorFor(catalogue, type).valueKind)[0];
  return {
    id: nextDomId('condition-row'),
    type,
    operator,
    numericLow: '',
    numericHigh: '',
    dateLow: '',
    dateHigh: '',
    textValues: '',
    dayOfWeekValues: [],
  };
}

export function newConditionGroup(
  catalogue: readonly ConditionTypeDescriptor[],
  combinator: ConditionCombinator = 'AND',
): ConditionGroup {
  return {
    id: nextDomId('condition-group'),
    combinator,
    rows: [emptyConditionRow(catalogue)],
  };
}

/** Whether a row carries enough of a value to be evaluated at all — the builder's own save-gating rule, generalised from `segments-page.ts`'s `rowIsWorkable`. */
export function conditionRowIsWorkable(row: ConditionRow, valueKind: ConditionValueKind): boolean {
  switch (valueKind) {
    case 'NUMERIC':
    case 'MONEY_MINOR':
    case 'PERCENT':
      if (row.numericLow.trim().length === 0) {
        return false;
      }
      return row.operator !== 'BETWEEN' || row.numericHigh.trim().length > 0;
    case 'DATE':
      return row.dateLow.trim().length > 0;
    case 'DATE_RANGE':
      return row.dateLow.trim().length > 0 && row.dateHigh.trim().length > 0;
    case 'TEXT_SET':
    case 'REFERENCE':
      return row.textValues.trim().length > 0;
    case 'DAY_OF_WEEK_SET':
      return row.dayOfWeekValues.length > 0;
  }
}

function parseCsv(text: string): readonly string[] {
  return text
    .split(',')
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

/**
 * A candidate to preview a rule set against — one value per condition `type`
 * the ruleset mentions. `q-rule-simulator`'s own input, never a live order or
 * customer: see that component's doc for why.
 */
export type ConditionCandidate = Readonly<
  Record<string, string | number | readonly string[] | readonly number[] | null | undefined>
>;

/**
 * Evaluates one row against a candidate's value for that row's condition type.
 * A missing candidate value never matches — the conservative default for a
 * preview tool that must not guess.
 */
export function evaluateConditionRow(
  row: ConditionRow,
  valueKind: ConditionValueKind,
  candidateValue: ConditionCandidate[string],
): boolean {
  if (candidateValue === null || candidateValue === undefined) {
    return false;
  }
  switch (valueKind) {
    case 'NUMERIC':
    case 'MONEY_MINOR':
    case 'PERCENT': {
      const value = typeof candidateValue === 'number' ? candidateValue : Number(candidateValue);
      if (Number.isNaN(value)) {
        return false;
      }
      const low = row.numericLow.trim() === '' ? null : Number(row.numericLow);
      const high = row.numericHigh.trim() === '' ? null : Number(row.numericHigh);
      switch (row.operator) {
        case 'AT_LEAST':
          return low !== null && value >= low;
        case 'AT_MOST':
          return low !== null && value <= low;
        case 'BETWEEN':
          return low !== null && high !== null && value >= low && value <= high;
        case 'EQUALS':
          return low !== null && value === low;
        default:
          return false;
      }
    }
    case 'DATE': {
      const value = String(candidateValue);
      switch (row.operator) {
        case 'AT_LEAST':
          return row.dateLow !== '' && value >= row.dateLow;
        case 'AT_MOST':
          return row.dateLow !== '' && value <= row.dateLow;
        case 'EQUALS':
          return row.dateLow !== '' && value === row.dateLow;
        default:
          return false;
      }
    }
    case 'DATE_RANGE': {
      const value = String(candidateValue);
      return (
        row.operator === 'BETWEEN' &&
        row.dateLow !== '' &&
        row.dateHigh !== '' &&
        value >= row.dateLow &&
        value <= row.dateHigh
      );
    }
    case 'TEXT_SET':
    case 'REFERENCE': {
      const candidateValues = (
        Array.isArray(candidateValue) ? candidateValue : [candidateValue]
      ).map(String);
      const rowValues = parseCsv(row.textValues);
      switch (row.operator) {
        case 'IN':
          return candidateValues.some((value) => rowValues.includes(value));
        case 'NOT_IN':
          return !candidateValues.some((value) => rowValues.includes(value));
        case 'EQUALS':
          return (
            candidateValues.length === 1 &&
            rowValues.length === 1 &&
            candidateValues[0] === rowValues[0]
          );
        default:
          return false;
      }
    }
    case 'DAY_OF_WEEK_SET': {
      const candidateDays = (Array.isArray(candidateValue) ? candidateValue : [candidateValue]).map(
        Number,
      );
      switch (row.operator) {
        case 'IN':
          return candidateDays.some((day) => row.dayOfWeekValues.includes(day));
        case 'NOT_IN':
          return !candidateDays.some((day) => row.dayOfWeekValues.includes(day));
        default:
          return false;
      }
    }
  }
}

/** A group matches when its rows do, combined by its own `AND`/`OR`. An empty group is vacuously true — no condition to fail. */
export function evaluateConditionGroup(
  group: ConditionGroup,
  catalogue: readonly ConditionTypeDescriptor[],
  candidate: ConditionCandidate,
): boolean {
  const evaluateOne = (row: ConditionRow): boolean =>
    evaluateConditionRow(row, descriptorFor(catalogue, row.type).valueKind, candidate[row.type]);
  return group.combinator === 'AND' ? group.rows.every(evaluateOne) : group.rows.some(evaluateOne);
}

/** A rule's groups always combine with OR — "any of these condition sets" — while each group's own rows combine by its own combinator. No groups at all matches everything, the same as no filter. */
export function evaluateConditionGroups(
  groups: readonly ConditionGroup[],
  catalogue: readonly ConditionTypeDescriptor[],
  candidate: ConditionCandidate,
): boolean {
  if (groups.length === 0) {
    return true;
  }
  return groups.some((group) => evaluateConditionGroup(group, catalogue, candidate));
}
